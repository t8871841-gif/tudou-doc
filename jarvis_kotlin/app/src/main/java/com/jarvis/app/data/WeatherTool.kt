package com.jarvis.app.data

import android.net.Uri
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 天气数据工具（批十）：问天气不再让模型编——查真数据拼成一句事实中文直接答：
 * 地名 → 地理编码（Nominatim/OSM 为主，Open-Meteo 编码兜底）→ Open-Meteo 预报
 * （免费、无需 Key）。查不到（断网/无此地）回调 null，上层如实说没查到，
 * 不许落回模型编造。
 */
object WeatherTool {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val CITY_IN_TEXT = Regex("^([\\u4e00-\\u9fa5]{2,4}?)(?:今天|明天|后天|现在)?(?:的)?天气")
    private val RAIN_WORDS = listOf("下雨", "带伞", "多少度", "气温", "冷不冷", "热不热", "天气")

    fun looksLikeWeather(text: String): Boolean {
        val t = text.trim()
        if (t.contains("天气")) return true
        // 没出现「天气」二字但明显在问天气的短句
        return t.length <= 12 && RAIN_WORDS.any { it != "天气" && t.contains(it) } &&
            (t.contains("今天") || t.contains("明天") || t.endsWith("吗") || t.contains("多少度"))
    }

    /** 从问句里抠城市名，抠不到返回 null（上层用默认城市）。 */
    fun extractCity(text: String): String? {
        val m = CITY_IN_TEXT.find(text.trim()) ?: return null
        val city = m.groupValues[1]
        // 「今天天气如何」这类会把「今天」误当城市，过滤掉时间词
        return if (city in listOf("今天", "明天", "后天", "现在", "这里", "当地")) null else city
    }

    /** 查 city 的天气，回调一句拼好的中文；任何失败回调 null。 */
    fun fetch(city: String, tomorrow: Boolean, cb: (String?) -> Unit) {
        // 地理编码先走 Nominatim（OSM）：实测 Open-Meteo 自带编码库中文城市覆盖差
        // （搜「新乡」给的是重庆同名乡镇、河南省会级城市反而没有），Nominatim 能正确
        // 给出新乡市/郑州市/波哥大的行政区划坐标。Nominatim 失败再落 Open-Meteo 编码兜底。
        geocodeNominatim(city) { loc ->
            if (loc != null) {
                fetchForecast(city, loc.first, loc.second, tomorrow, cb)
            } else {
                geocodeOpenMeteo(city) { loc2 ->
                    if (loc2 != null) fetchForecast(city, loc2.first, loc2.second, tomorrow, cb) else cb(null)
                }
            }
        }
    }

    /** 结构化天气（彻底融合第 3 步）：给 HUD 天气面板用，直接返回字段，不拼聊天文本。
     * 地理编码与 fetch 共用同一条路；预报多要一个 daily weather_code（给 3 天小格的图标用）。
     * 任何失败回调 null。 */
    data class DayForecast(val week: Int, val day: String, val low: String, val high: String)
    data class WeatherInfo(val temp: String, val now: String, val city: String, val humidity: String, val days: List<DayForecast>)

    fun fetchStructured(city: String, cb: (WeatherInfo?) -> Unit) {
        geocodeNominatim(city) { loc ->
            if (loc != null) {
                fetchForecastStructured(city, loc.first, loc.second, cb)
            } else {
                geocodeOpenMeteo(city) { loc2 ->
                    if (loc2 != null) fetchForecastStructured(city, loc2.first, loc2.second, cb) else cb(null)
                }
            }
        }
    }

    private fun fetchForecastStructured(cityName: String, lat: Double, lon: Double, cb: (WeatherInfo?) -> Unit) {
        if (lat.isNaN() || lon.isNaN()) {
            cb(null)
            return
        }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=Asia%2FShanghai&forecast_days=3"
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cb(null)
            override fun onResponse(call: Call, resp: Response) {
                cb(try {
                    parseStructured(cityName, JSONObject(resp.body?.string().orEmpty()))
                } catch (_: Exception) {
                    null
                })
            }
        })
    }

    private fun parseStructured(city: String, root: JSONObject): WeatherInfo? {
        val cur = root.optJSONObject("current") ?: return null
        val daily = root.optJSONObject("daily") ?: return null
        val maxArr = daily.optJSONArray("temperature_2m_max") ?: return null
        val minArr = daily.optJSONArray("temperature_2m_min") ?: return null
        val codeArr = daily.optJSONArray("weather_code")
        val timeArr = daily.optJSONArray("time")
        val temp = cur.optDouble("temperature_2m", Double.NaN)
        val hum = cur.optInt("relative_humidity_2m", -1)
        // 周几（0=周日…6=周六，对应页面 WD 数组）；日期串解析失败就用今天顺延兜底，
        // 绝不把 -1 传过去（页面 WD[-1] 是 undefined，会渲染出 "undefined"）。
        val todayWeek = (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1) % 7
        val days = ArrayList<DayForecast>()
        val n = minOf(maxArr.length(), minArr.length(), 3)
        for (i in 0 until n) {
            val hi = maxArr.optDouble(i, Double.NaN)
            val lo = minArr.optDouble(i, Double.NaN)
            val week = dayOfWeek(timeArr?.optString(i)).let { if (it < 0) (todayWeek + i) % 7 else it }
            days.add(
                DayForecast(
                    week = week,
                    day = codeText(codeArr?.optInt(i, -1) ?: -1),
                    low = if (lo.isNaN()) "--" else fmt(lo),
                    high = if (hi.isNaN()) "--" else fmt(hi)
                )
            )
        }
        return WeatherInfo(
            temp = if (temp.isNaN()) "--" else fmt(temp),
            now = codeText(cur.optInt("weather_code", -1)),
            city = city,
            humidity = if (hum < 0) "--" else hum.toString(),
            days = days
        )
    }

    /** "yyyy-MM-dd" → 周几（0=周日…6=周六）；解析失败返回 -1（调用方兜底）。 */
    private fun dayOfWeek(dateStr: String?): Int {
        if (dateStr.isNullOrBlank()) return -1
        return try {
            val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(dateStr) ?: return -1
            val c = java.util.Calendar.getInstance().apply { time = d }
            (c.get(java.util.Calendar.DAY_OF_WEEK) - 1) % 7
        } catch (_: Exception) {
            -1
        }
    }

    private fun geocodeNominatim(city: String, cb: (Pair<Double, Double>?) -> Unit) {
        val url = "https://nominatim.openstreetmap.org/search?q=${Uri.encode(city)}&format=json&limit=5&accept-language=zh"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Jarvis/1.0 (personal assistant app)")
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cb(null)
            override fun onResponse(call: Call, resp: Response) {
                cb(try {
                    val arr = org.json.JSONArray(resp.body?.string().orEmpty())
                    var first: Pair<Double, Double>? = null
                    var admin: Pair<Double, Double>? = null
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val p = o.optDouble("lat", Double.NaN) to o.optDouble("lon", Double.NaN)
                        if (p.first.isNaN()) continue
                        if (first == null) first = p
                        if (o.optString("type") == "administrative") {
                            admin = p
                            break
                        }
                    }
                    admin ?: first
                } catch (_: Exception) {
                    null
                })
            }
        })
    }

    private fun geocodeOpenMeteo(city: String, cb: (Pair<Double, Double>?) -> Unit) {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=${Uri.encode(city)}&count=10&language=zh&format=json"
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cb(null)
            override fun onResponse(call: Call, resp: Response) {
                cb(try {
                    val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("results")
                    var best: Pair<Double, Double>? = null
                    var bestPop = -1L
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val pop = o.optLong("population", 0)
                            if (pop > bestPop) {
                                bestPop = pop
                                best = o.optDouble("latitude", Double.NaN) to o.optDouble("longitude", Double.NaN)
                            }
                        }
                    }
                    best?.takeIf { !it.first.isNaN() }
                } catch (_: Exception) {
                    null
                })
            }
        })
    }

    private fun fetchForecast(cityName: String, lat: Double, lon: Double, tomorrow: Boolean, cb: (String?) -> Unit) {
        if (lat.isNaN() || lon.isNaN()) {
            cb(null)
            return
        }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=Asia%2FShanghai&forecast_days=3"
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cb(null)
            override fun onResponse(call: Call, resp: Response) {
                cb(try {
                    compose(cityName, JSONObject(resp.body?.string().orEmpty()), tomorrow)
                } catch (_: Exception) {
                    null
                })
            }
        })
    }

    private fun compose(city: String, root: JSONObject, tomorrow: Boolean): String? {
        val cur = root.optJSONObject("current") ?: return null
        val daily = root.optJSONObject("daily") ?: return null
        val idx = if (tomorrow) 1 else 0
        val maxArr = daily.optJSONArray("temperature_2m_max")
        val minArr = daily.optJSONArray("temperature_2m_min")
        val popArr = daily.optJSONArray("precipitation_probability_max")
        if (maxArr == null || minArr == null || maxArr.length() <= idx) return null
        val desc = codeText(cur.optInt("weather_code", -1))
        val now = cur.optDouble("temperature_2m", Double.NaN)
        val feel = cur.optDouble("apparent_temperature", Double.NaN)
        val hum = cur.optInt("relative_humidity_2m", -1)
        val wind = cur.optDouble("wind_speed_10m", Double.NaN)
        val hi = maxArr.optDouble(idx, Double.NaN)
        val lo = minArr.optDouble(idx, Double.NaN)
        val pop = if (popArr != null && popArr.length() > idx && !popArr.isNull(idx)) popArr.optInt(idx, -1) else -1
        val dayWord = if (tomorrow) "明天" else "今天"
        return buildString {
            append("${city}${dayWord}：${desc}")
            if (!tomorrow && !now.isNaN()) append("，现在 ${fmt(now)}°")
            if (!hi.isNaN() && !lo.isNaN()) append("，最高 ${fmt(hi)}°、最低 ${fmt(lo)}°")
            if (!tomorrow && !feel.isNaN()) append("，体感 ${fmt(feel)}°")
            if (!tomorrow && hum >= 0) append("，湿度 ${hum}%")
            if (!tomorrow && !wind.isNaN()) append("，风速 ${fmt(wind)} km/h")
            if (pop >= 0) append("，降水概率 ${pop}%")
            append("。（数据：Open-Meteo）")
        }
    }

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)

    /** WMO 天气代码 → 中文。 */
    private fun codeText(code: Int): String = when (code) {
        0 -> "晴"
        1, 2 -> "多云"
        3 -> "阴"
        45, 48 -> "有雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95 -> "雷阵雨"
        96, 99 -> "雷阵雨伴冰雹"
        else -> "天气状况不明"
    }
}
