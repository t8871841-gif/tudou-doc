package com.jarvis.app.net

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.jarvis.app.local.NpuModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * NPU 档的系统提示（短版，LocalBrain.systemPrompt 在 NPU 档下直接用这份，
 * 不用 LocalPrompt 那份长的——4096 上下文装不下它）。
 *
 * 模型-facing 的文字一律英文（LocalBrain 的规矩）；只要求它最后回给用户
 * 的话用中文。工具调用优先：有工具能办的事必须调工具，不许现编数值；
 * 工具办不了就用正文直说，不要硬调一个不对的工具。
 */
val NPU_SYSTEM_PROMPT = """
You are Jarvis, the user's on-device personal assistant. You run on the phone's NPU, offline.
Answer ONLY by calling one of the tools below. Never invent values (battery, time, prices) — call a tool to read them.
If the tools cannot do what the user asked, reply in plain text saying so briefly. Do not call a tool you do not need.
Always reply in Chinese. Keep answers to one short sentence.
""".trimIndent()

/**
 * NPU 档（2026-11-27）：LocalBrain 的 LiteRT-LM 后端（BrainLlmClient 的
 * NPU 实现），与 DeepSeekLlmClient 同一个调用契约——complete(messages,
 * tools) -> Reply，LocalBrain 一字不用改。
 *
 * 契约上有三处是翻译，不是透传：
 *
 * 1. **工具调用**：LiteRT-LM 的工具是 OpenApiTool，automaticToolCalling
 *    关掉后模型只返回 toolCalls、不替我们执行；这里把每条 toolCall
 *    原样翻成 DeepSeekLlmClient.ToolCall（名 + JSON 参数串），执行仍是
 *    LocalBrain 的 runTool。工具表是 NpuTools 的精简版（见下），不是
 *    29 个全量——4096 上下文装不下全量 schema，NPU 档也只接简单任务。
 * 2. **历史**：complete() 每次收到的是全量 history，而 LiteRT-LM 的
 *    Conversation 是有状态的。所以这里只在对话里留一份状态，按
 *    sentCount 差分喂新消息：user 文本直接发，tool 结果合成一条
 *    Message(Role.TOOL) 发，assistant 消息跳过（上一轮的输出已在对话状态里）。
 *    历史被改写（trim/fold）时能认出来——长度缩了，或上次消费的那条
 *    指纹变了——就关掉旧会话、按新历史重放一遍。
 * 3. **图片**：SM8550 的 NPU 模型只吃文本。history 里的 image_url 块
 *    直接丢掉，只留文字。看屏类任务本来就不走 NPU（路由层拦着），这里
 *    丢图只是兜底，不静默降级——真需要看图时模型会因无工具可用而直说
 *    办不了，回合按正文结束。
 *
 * 线程：complete() 阻塞调用线程（LocalBrain 的单循环线程），与
 * DeepSeekLlmClient.complete 同款；cancel() 只置位，不关引擎——正在
 * 生成的那一次跑完即停（LocalBrain 的 generation 检查会拦住下一轮），
 * 关引擎要重加载 6–10 秒，不值得。
 */
class NpuLlmClient(private val appContext: Context) : BrainLlmClient {

    @Volatile
    private var cancelled = false

    private var conversation: Conversation? = null
    private var conversationEngine: Engine? = null
    private var sentCount = 0
    private var lastFingerprint = 0
    private var lastUserText: String? = null

    override fun cancel() {
        cancelled = true
    }

    override fun retune(model: String, thinking: Boolean, reasoningEffort: String) {
        // NPU 模型是固定的（Gemma 4 E2B SM8550），没有可调的模型名/思考档。
    }

    override fun complete(messages: List<JSONObject>, tools: JSONArray): DeepSeekLlmClient.Reply {
        // 新回合开始时清掉上一轮的取消置位：取消只对当轮有效，否则一次
        // 喊停会把下一回合的第一次 complete 也掐掉。用户消息带 [now …]
        // 时间戳，每回合必然不同，拿它当回合分界。
        val curUserText = messages.lastOrNull { it.optString("role") == "user" }?.let { extractText(it) }
        if (curUserText != null && curUserText != lastUserText) {
            cancelled = false
            lastUserText = curUserText
        }
        if (cancelled) throw IllegalStateException("已取消")
        val engine = NpuModel.ensureEngine(appContext)
        if (conversation == null || conversationEngine !== engine || needsReset(messages)) {
            resetConversation(engine, messages, tools)
        }
        val conv = conversation ?: throw IllegalStateException("NPU 会话没建起来")
        val newEntries = messages.subList(sentCount, messages.size)
        var response: Message? = null
        val toolResponses = mutableListOf<Content.ToolResponse>()
        var userText: String? = null
        val lastIndex = newEntries.size - 1
        for ((i, entry) in newEntries.withIndex()) {
            when (entry.optString("role")) {
                "user" -> {
                    val t = extractText(entry)
                    if (t.isNotBlank()) userText = if (userText == null) t else "$userText\n$t"
                }
                "tool" -> toToolResponse(entry, messages)?.let { toolResponses += it }
                "assistant" -> {
                    // 只有一种 assistant 消息要当成输入发出去：内部回合（收割/
                    // 复核）把提示词挂在 assistant 消息上；它是新一批的最后一条、
                    // 且没带 tool_calls——真正的模型输出要么带着 tool_calls，
                    // 要么后面还跟着工具结果。见 LocalBrain.runTurn 的 internal 分支。
                    if (i == lastIndex && entry.optJSONArray("tool_calls") == null) {
                        val t = entry.optString("content")
                        if (t.isNotBlank()) userText = if (userText == null) t else "$userText\n$t"
                    }
                }
                else -> Unit // system 消息已是 systemInstruction；未知角色跳过。
            }
        }
        if (userText != null) {
            checkCancelled()
            response = conv.sendMessage(userText)
        }
        if (toolResponses.isNotEmpty()) {
            checkCancelled()
            // Message.tool 是废弃工厂（0.19.1），直接用主构造；Contents
            // 只有 of(String) 与 of(vararg Content) 两个工厂，列表走构造器。
            response = conv.sendMessage(Message(Role.TOOL, Contents(toolResponses)))
        }
        sentCount = messages.size
        lastFingerprint = if (messages.isNotEmpty()) fingerprint(messages.last()) else 0
        val msg = response ?: return emptyReply()
        checkCancelled()
        return toReply(msg)
    }

    private fun checkCancelled() {
        if (cancelled) throw IllegalStateException("已取消")
    }

    /**
     * 历史被改写了（trim/fold 换掉了已消费的部分）就得重建会话。
     * 长度缩了能直接看出来；长度没变但内容被换了，就看上次消费的那条
     * 指纹还对不对得上。没改写时一律不重建——重建要重放全部历史，白花
     * 一次 prefill。
     */
    private fun needsReset(messages: List<JSONObject>): Boolean {
        if (messages.size < sentCount) return true
        if (sentCount == 0 || messages.isEmpty()) return false
        return fingerprint(messages[sentCount - 1]) != lastFingerprint
    }

    private fun resetConversation(engine: Engine, messages: List<JSONObject>, tools: JSONArray) {
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
        conversationEngine = null
        sentCount = 0
        lastFingerprint = 0
        val system = messages.firstOrNull { it.optString("role") == "system" }
            ?.optString("content").orEmpty().ifBlank { NPU_SYSTEM_PROMPT }
        conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                tools = buildOpenApiTools(tools),
                automaticToolCalling = false,
            )
        )
        conversationEngine = engine
        Log.d(TAG, "NPU conversation reset (history ${messages.size} msgs, ${tools.length()} tools)")
    }

    /** LiteRT-LM 的 OpenApiTool 只要「名 + 描述 + 参数」三件套；method 是本机分发键，模型不需要。
     * 注意 Tool 是注解（@Tool），工具提供方是 ToolProvider；tool() 返回的就是 ToolProvider。 */
    private fun buildOpenApiTools(tools: JSONArray): List<ToolProvider> {
        val out = mutableListOf<ToolProvider>()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = JSONObject()
                .put("name", t.optString("name"))
                .put("description", t.optString("description"))
                .put(
                    "parameters",
                    t.optJSONObject("parameters")
                        ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                )
            val json = fn.toString()
            out += tool(object : OpenApiTool() {
                override fun getToolDescriptionJsonString(): String = json
                override fun execute(paramsJsonString: String): String =
                    """{"error":"manual tool calling is on; execute() must not be called"}"""
            })
        }
        return out
    }

    /** 只留文字块；image_url 块丢掉（NPU 模型看不了图）。 */
    private fun extractText(entry: JSONObject): String {
        val c = entry.opt("content") ?: return ""
        if (c is String) return c
        if (c is JSONArray) {
            val sb = StringBuilder()
            for (i in 0 until c.length()) {
                val block = c.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") {
                    val t = block.optString("text")
                    if (t.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(t)
                    }
                }
            }
            return sb.toString()
        }
        return ""
    }

    /**
     * tool 消息 -> ToolResponse：名要到前面的 assistant 消息里按
     * tool_call_id 对出来（tool 消息自己只带 id 不带名）。
     */
    private fun toToolResponse(entry: JSONObject, all: List<JSONObject>): Content.ToolResponse? {
        val id = entry.optString("tool_call_id")
        val content = entry.optString("content")
        var name = "unknown_tool"
        for (m in all) {
            if (m.optString("role") != "assistant") continue
            val calls = m.optJSONArray("tool_calls") ?: continue
            for (i in 0 until calls.length()) {
                val tc = calls.optJSONObject(i) ?: continue
                if (tc.optString("id") == id) {
                    name = tc.optJSONObject("function")?.optString("name")
                        .orEmpty().ifEmpty { "unknown_tool" }
                }
            }
        }
        return Content.ToolResponse(name, content)
    }

    private fun toReply(msg: Message): DeepSeekLlmClient.Reply {
        val text = msg.plainText()
        val calls = msg.toolCalls.mapIndexed { i, tc ->
            DeepSeekLlmClient.ToolCall(
                id = "call_$i",
                name = tc.name,
                // Map.toString 是 {k=v} 不是 JSON，runTool 那边按 JSON 解析会失败；
                // JSONObject(Map) 才是真 JSON（嵌套 Map/List 也包得住）。
                argumentsRaw = JSONObject(tc.arguments).toString(),
            )
        }
        val assistant = JSONObject().put("role", "assistant")
            .put("content", if (text.isEmpty()) JSONObject.NULL else text)
        if (calls.isNotEmpty()) {
            val arr = JSONArray()
            for (tc in calls) {
                arr.put(
                    JSONObject()
                        .put("id", tc.id)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", tc.name)
                                .put("arguments", tc.argumentsRaw),
                        )
                )
            }
            assistant.put("tool_calls", arr)
        }
        return DeepSeekLlmClient.Reply(
            content = text,
            reasoning = "",
            toolCalls = calls,
            assistantMessage = assistant,
            promptTokens = 0,
            completionTokens = 0,
            cachedTokens = 0,
        )
    }

    private fun emptyReply(): DeepSeekLlmClient.Reply = DeepSeekLlmClient.Reply(
        content = "",
        reasoning = "",
        toolCalls = emptyList(),
        assistantMessage = JSONObject().put("role", "assistant").put("content", JSONObject.NULL),
        promptTokens = 0,
        completionTokens = 0,
        cachedTokens = 0,
    )

    private fun fingerprint(msg: JSONObject): Int =
        (msg.optString("role") + "|" + msg.opt("content").toString()).hashCode()

    companion object {
        private const val TAG = "Brain"
    }
}

/**
 * Message 没有 text 属性：正文在 contents 里，是 Content.Text 块的列表。
 */
private fun Message.plainText(): String =
    contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

/**
 * NPU 档的工具表：只留设备类 + 开应用 7 个，不带看屏/点按/发消息——
 * 4096 上下文装不下 29 个工具的全量描述，NPU 档也只接简单任务。
 *
 * 参数与 method 一律从 ToolSchemas 原样抄（只把描述换成短版）：参数
 * 稍有出入分发就对不上，method 是 runTool 认的分发键。深拷贝后再改
 * 描述——ToolSchemas.all() 返回的 JSONObject 是共享的，直接改会把
 * 云端那份也改了。
 */
object NpuTools {

    private val NAMES = setOf(
        "get_device_status",
        "get_battery",
        "launch_app",
        "set_brightness",
        "set_volume",
        "set_alarm",
        "system_action",
    )

    private val SHORT_DESC = mapOf(
        "get_device_status" to
            "Check whether the device is online and how (wifi / cellular / none). Use to answer \"are you connected?\".",
        "get_battery" to
            "Battery level and charging state: percent, charging, plugged.",
        "launch_app" to
            "Launch an app by package name (e.g. 'com.tencent.mm') or display label (e.g. '微信'). Direct launch, no tapping needed.",
        "set_brightness" to
            "Read or set screen brightness (1..255). Omit the value to read only.",
        "set_volume" to
            "Read or set media volume (0..max). Omit level/mute to just read.",
        "set_alarm" to
            "Set a system alarm. Hour/minute in 24h local time.",
        "system_action" to
            "System navigation: 'back' to go back, 'home' for the launcher, 'recents' for the task switcher, 'notifications' to pull down the shade.",
    )

    fun specs(): JSONArray {
        val out = JSONArray()
        val all = ToolSchemas.all()
        for (i in 0 until all.length()) {
            val t = all.optJSONObject(i) ?: continue
            val name = t.optString("name")
            if (name !in NAMES) continue
            val copy = JSONObject(t.toString())
            copy.put("description", SHORT_DESC[name] ?: t.optString("description"))
            out.put(copy)
        }
        return out
    }
}
