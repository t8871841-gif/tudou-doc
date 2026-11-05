package com.jarvis.app.net

import android.content.Context
import com.jarvis.app.JarvisPrefs
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 手机专用模型 AutoGLM-Phone（智谱 AutoGLM-Phone-9B）当跨应用任务循环
 * （task/TaskLoop）的可选大脑。模型走 OpenAI 兼容的 /chat/completions 接口，
 * 接口地址、模型名、Key 复用“自定义大模型”那三项设置（用户填智谱地址
 * https://open.bigmodel.cn/api/paas/v4、模型 autoglm-phone、自己的 Key）。
 *
 * 与 CustomClient 的区别只在输出格式：AutoGLM-Phone 不输出 JSON，它输出
 * <think>推理</think><answer>do(action="Tap", element=[x,y])</answer>。
 * 本文件把 do(...) 转成 Jarvis 的 JSON 动作字符串再交回 TaskLoop，
 * 这样 TaskLoop 的 parseAction、重复守卫、错误码回喂都不用改。
 * 动作映射（Open-AutoGLM phone_agent/actions/handler.py + config/prompts_zh.py）：
 *   Launch(app) -> openApp；Tap(element=[x,y]) -> tap(x,y)；
 *   Type/Type_Name(text) -> type(text)；
 *   Swipe(start,end) -> swipe(x1,y1,x2,y2)；Back -> back；Home -> home；
 *   Long Press(element) -> longPress(x,y)；Double Tap(element) -> tap(x,y)（Jarvis 无双击）；
 *   Wait(duration="x seconds") -> wait(ms=x*1000)；
 *   Take_over(message) -> fail（需用户接管：登录/验证码）；
 *   Interact -> fail（需用户选择）；Note/Call_API -> wait 500ms（无对应动作）；
 *   finish(message) -> done(message)。
 * 坐标都是 0-1000 相对值，与 Jarvis 的 tap/swipe 坐标系一致。
 * 回调在后台线程。
 */
object AutoGLMClient {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** AutoGLM 专用的系统提示词：沿用 Open-AutoGLM 的 do(...) 动作定义，
     * 另加 Jarvis 的读屏方式（紧凑表 + 截图、坐标 0-1000）与安全线。 */
    private val AUTO_GLM_SYSTEM_PROMPT =
        "你是一个手机端智能体，根据任务原文、当前界面与操作历史，一步一步完成任务。\n" +
        "你必须严格按以下格式输出：\n" +
        "<think>{think}</think>\n" +
        "<answer>{action}</answer>\n" +
        "其中 {think} 是你为什么选这个操作的简短推理；{action} 是本次执行的具体操作，必须严格遵循下方定义的指令格式。\n" +
        "操作指令如下：\n" +
        "- do(action=\"Launch\", app=\"xxx\") 启动目标 App，比从主屏幕找更快。\n" +
        "- do(action=\"Tap\", element=[x,y]) 点屏幕上的一个点，坐标从左上角 (0,0) 到右下角 (999,999)。可点按钮、列表项、输入框。\n" +
        "- do(action=\"Tap\", element=[x,y], message=\"重要操作\") 同 Tap，点涉及财产、支付、隐私等敏感按钮时用。\n" +
        "- do(action=\"Type\", text=\"xxx\") 往当前已聚焦的输入框输文字，用之前先点输入框让它聚焦。\n" +
        "- do(action=\"Type_Name\", text=\"xxx\") 输入人名，基本同 Type。\n" +
        "- do(action=\"Swipe\", start=[x1,y1], end=[x2,y2]) 从起点拖到终点，用于滚动、翻页、下拉。\n" +
        "- do(action=\"Long Press\", element=[x,y]) 长按一个点，用于出菜单、选文字。\n" +
        "- do(action=\"Double Tap\", element=[x,y]) 双击一个点。\n" +
        "- do(action=\"Back\") 返回上一屏，相当于 Android 返回键。\n" +
        "- do(action=\"Home\") 回桌面。\n" +
        "- do(action=\"Wait\", duration=\"x seconds\") 等 x 秒，用于等页面加载。\n" +
        "- do(action=\"Take_over\", message=\"xxx\") 需要用户接管（登录、验证码、支付确认），message 写清原因。\n" +
        "- do(action=\"Interact\") 有多个选项要用户自己选时用。\n" +
        "- finish(message=\"xxx\") 任务准确完整完成时用，message 写完成情况；做不下去时也用 finish，message 写清卡在哪、什么原因。\n" +
        "读屏方式：每轮你会看到任务原文、当前前台应用、界面元素紧凑表（只列可操作元素，带稳定编号与中心坐标，都是 0-1000 相对值），以及一张全屏截图（带网格标定）。有编号的元素优先用紧凑表里的坐标点；没编号的按截图上的位置估坐标。\n" +
        "必须遵循的规则：\n" +
        "1. 先检查当前 App 是不是目标 App，不是就先 Launch。\n" +
        "2. 进了无关页面先 Back；Back 没变化就点左上角返回或右上角 X。\n" +
        "3. 页面没加载出内容，最多连续 Wait 三次，否则 Back 重进。\n" +
        "4. 找不到目标（联系人、商品等）就 Swipe 滑动查找。\n" +
        "5. 每步前检查上一步是否生效：点了没反应先等一下，再调一下点击位置重试；还不生效就跳过这步继续，并在 finish 里说明。\n" +
        "6. 绝不输入密码、验证码；绝不点付款、转账、删除类按钮，除非任务原文明确要求；登录、验证码场景用 Take_over 交给用户。\n" +
        "7. 一步只做一个动作；发消息这类任务要依次完成打开应用、找到联系人、点开会话、输入消息、点发送，发送完成才能 finish。\n" +
        "8. 紧凑表里带稳定编号的元素优先点它：do(...) 只有坐标参数，取该编号元素的中心坐标填进 element=[x,y]，不要另算坐标、不要凭截图位置重算。\n" +
        "9. 上一步若带错误码，按码换招，别对同一码重复同一动作：ELEMENT_NOT_FOUND 换目标或滑动找、TAP_REFUSED 先 zoom 再点、TIMEOUT 先看界面确认成没成再决定、NO_INPUT_FOCUS 先点输入框、NO_ACTIVE_WINDOW 先 Launch 目标应用、PARSE_ERROR 检查动作格式、UNKNOWN 换路线或 finish 说明；树被扣留的应用（如微信）先 zoom 再点，盲点会被拒绝。"

    /** M11 急停序号：cancelInFlight() 自增，askOnce 回调凭序号丢弃已取消轮次的结果。 */
    @Volatile
    private var cancelSeq = 0

    /** 正在问模型的那一次 OkHttp 调用，cancelInFlight() 连同网络一起取消。 */
    @Volatile
    private var inFlightCall: Call? = null

    /** 急停：取消正在问模型的那一步（序号作废 + 断开网络），回调不再生效。 */
    fun cancelInFlight() {
        cancelSeq++
        inFlightCall?.cancel()
    }

    /**
     * 单轮调用，不进任何对话历史（任务循环 task/TaskLoop 用）。
     * onResult 收到的是转好的 Jarvis JSON 动作字符串（如 {"action":"tap","x":500,"y":800}），
     * 可直接交给 TaskLoop 的 parseAction；onError 是出错原因。回调在后台线程。
     * M9：传入的 systemPrompt 不再丢弃，与 AUTO_GLM_SYSTEM_PROMPT 拼接后一起发。
     * M11：isAlive 由调用方给（TaskLoop 的代次检查），false 即已取消；cancelInFlight()
     * 也能取消本轮（序号作废 + 断开网络），已取消的回调静默丢弃、不再回调。
     */
    fun askOnce(
        ctx: Context, systemPrompt: String, userText: String, jpegBase64: String?,
        isAlive: (() -> Boolean)? = null,
        onResult: (String) -> Unit, onError: (String) -> Unit
    ) {
        // M11：发请求前先检查急停。已取消时静默返回、不回调，TaskLoop 的短轮询
        // 凭 alive 检查提前返回 null（与 CustomClient.ask 丢弃已取消回调的做法一致）。
        val mySeq = cancelSeq
        if (isAlive?.invoke() == false || mySeq != cancelSeq) return
        // GLM 模式专用：接口地址与模型名取内置 GLM 配置（智谱 + autoglm-phone），Key 用共用的 API Key 栏位。
        val base = JarvisPrefs.getTaskBaseUrl(ctx).trim().trimEnd('/')
        val model = JarvisPrefs.getTaskModel(ctx)
        val key = JarvisPrefs.getTaskKey(ctx)
        if (base.isBlank() || model.isBlank()) {
            onError("未配置任务模型接口地址或模型名")
            return
        }
        if (key.isBlank()) {
            onError("未配置 API Key，请先去设置里填写")
            return
        }
        val userContent: Any = if (jpegBase64.isNullOrBlank()) {
            userText
        } else {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", userText))
                .put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64")
                    )
                )
        }
        // M9：传入的 TaskLoop SYSTEM_PROMPT（错误码表、eid 优先、zoom 规则）与自带提示词
        // 拼接后一起发，两份都不丢。输出格式只用 do(...)/finish(...) 这一套，不输出 JSON；
        // eid 优先落到 do(...) 里就是取该编号元素的中心坐标填 element（见上面规则 8）。
        val mergedSystemPrompt = buildString {
            if (systemPrompt.isNotBlank()) {
                append(systemPrompt)
                append("\n\n")
            }
            append(AUTO_GLM_SYSTEM_PROMPT)
            append("\n\n补充说明：上面两份提示词都要遵守；动作输出格式只用 <think>/<answer>do(...)/finish(...) 这一套，不输出 JSON 对象。")
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", mergedSystemPrompt))
            .put(JSONObject().put("role", "user").put("content", userContent))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", 0.2)
        val req = Request.Builder()
            .url("$base/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val httpCall = client.newCall(req)
        inFlightCall = httpCall
        httpCall.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call === inFlightCall) inFlightCall = null
                // M11：等待回调时再检查一次急停，已取消就静默丢弃，不回调。
                if (mySeq != cancelSeq || isAlive?.invoke() == false) return
                onError("网络请求失败：${e.message}")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                if (call === inFlightCall) inFlightCall = null
                if (mySeq != cancelSeq || isAlive?.invoke() == false) return
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        onError("AutoGLM 接口返回错误：HTTP ${resp.code}")
                        return
                    }
                    try {
                        val text = resp.body?.string().orEmpty()
                        val json = JSONObject(text)
                        val reply = json.getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message").getString("content").trim()
                        val actionJson = parseAutoGLMReply(reply)
                            ?: run { onError("AutoGLM 输出无法解析：${reply.take(200)}"); return }
                        onResult(actionJson.toString())
                    } catch (e: Exception) {
                        onError("解析 AutoGLM 返回内容失败：${e.message}")
                    }
                }
            }
        })
    }

    /**
     * 把 AutoGLM 的回复转成 Jarvis 的 JSON 动作；转不了返回 null。
     * 先取 <answer>...</answer> 里的内容，没有标签就用整段回复。
     * 接受 do(...) 与 finish(...) 两种调用形式。
     */
    fun parseAutoGLMReply(reply: String): JSONObject? {
        val inner = if (reply.contains("<answer>")) {
            reply.substringAfter("<answer>").substringBefore("</answer>").trim()
        } else {
            reply.trim()
        }
        if (inner.isEmpty()) return null
        return try {
            if (inner.startsWith("finish(")) parseFinish(inner) else parseDo(inner)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFinish(call: String): JSONObject? {
        val message = stringArg(call, "message") ?: return null
        return JSONObject().put("action", "done").put("message", message)
    }

    private fun parseDo(call: String): JSONObject? {
        val name = stringArg(call, "action") ?: return null
        return when (name) {
            "Launch" -> {
                val app = stringArg(call, "app") ?: return null
                JSONObject().put("action", "openApp").put("app", app)
            }
            "Tap" -> {
                val (x, y) = pointArg(call, "element") ?: return null
                JSONObject().put("action", "tap").put("x", x).put("y", y)
            }
            "Type", "Type_Name" -> {
                val text = stringArg(call, "text") ?: return null
                JSONObject().put("action", "type").put("text", text)
            }
            "Swipe" -> {
                val (x1, y1) = pointArg(call, "start") ?: return null
                val (x2, y2) = pointArg(call, "end") ?: return null
                JSONObject().put("action", "swipe").put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2)
            }
            "Back" -> JSONObject().put("action", "back")
            "Home" -> JSONObject().put("action", "home")
            "Long Press" -> {
                val (x, y) = pointArg(call, "element") ?: return null
                JSONObject().put("action", "longPress").put("x", x).put("y", y)
            }
            "Double Tap" -> {
                // Jarvis 没有双击动作，退化成一次点按。
                val (x, y) = pointArg(call, "element") ?: return null
                JSONObject().put("action", "tap").put("x", x).put("y", y)
            }
            "Wait" -> {
                val duration = stringArg(call, "duration") ?: "1 seconds"
                val seconds = duration.trim().split(" ").firstOrNull()?.toDoubleOrNull() ?: 1.0
                val ms = (seconds * 1000).toLong().coerceIn(300L, 10_000L)
                JSONObject().put("action", "wait").put("ms", ms)
            }
            "Take_over" -> {
                val message = stringArg(call, "message") ?: "需要用户接管"
                JSONObject().put("action", "fail").put("reason", "需要用户接管：$message")
            }
            "Interact" -> JSONObject().put("action", "fail").put("reason", "需要用户选择/确认")
            "Note", "Call_API" -> {
                // 记录/总结类动作没有对应的 Jarvis 动作，原地等一下让模型继续。
                JSONObject().put("action", "wait").put("ms", 500L)
            }
            else -> null
        }
    }

    /** 取 key="..." 形式的字符串参数；双引号内不支持转义之外的复杂写法，够解析模型输出。 */
    private fun stringArg(call: String, key: String): String? {
        val m = Regex("""$key\s*=\s*"((?:[^"\\]|\\.)*)"""").find(call) ?: return null
        return m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
    }

    /** 取 key=[x, y] 形式的坐标参数（0-1000 相对值）。 */
    private fun pointArg(call: String, key: String): Pair<Int, Int>? {
        val m = Regex("""$key\s*=\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]""").find(call) ?: return null
        val x = m.groupValues[1].toIntOrNull() ?: return null
        val y = m.groupValues[2].toIntOrNull() ?: return null
        return x to y
    }
}
