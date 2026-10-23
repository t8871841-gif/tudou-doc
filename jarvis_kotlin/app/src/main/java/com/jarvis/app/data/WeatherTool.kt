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
 * 天气数据工具（批十）：问天气不再让模型编——直连 Open-Meteo（免费、无需 Key）：
 * 地名 → 地理编码 → 实时+今日预报，拼成一句事实中文直接答。
 * 查不到（断网/无此地）回调 null，上层如实说没查到，不许落回模型编造。
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
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${Uri.encode(city)}&count=1&language=zh&format=json"
        client.newCall(Request.Builder().url(geoUrl).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cb(null)
            override fun onResponse(call: Call, resp: Response) {
                val loc = try {
                    val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("results")
                    arr?.optJSONObject(0)
                } catch (_: Exception) {
                    null
                }
                if (loc == null) {
                    cb(null)
                    return
                }
                fetchForecast(
                    cityName = loc.optString("name", city),
                    lat = loc.optDouble("latitude", Double.NaN),
                    lon = loc.optDouble("longitude", Double.NaN),
                    tomorrow = tomorrow,
                    cb = cb
                )
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
