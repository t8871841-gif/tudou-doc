package com.jarvis.native

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 第 2 条「操作审计日志」：记录每次任务的关键动作，本地持久化(纯文本 JSON Lines，存
 * filesDir/jarvis_audit.log，不上云)，界面可查看、可清空；敏感内容脱敏后再落盘——
 * 验证码/密码类字样一律不写真实内容，只写"[已脱敏]"。
 *
 * 一条日志 = 一个任务的一次关键动作：时间 + 任务描述 + 做了什么 + 核验结果。
 * 不追求完整的结构化检索，够"做完一个跨应用任务后能完整回看这条链路"就行。
 */
object AuditLog {
    private const val FILE_NAME = "jarvis_audit.log"
    private const val MAX_READ_LINES = 500
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    // 验证码/密码类内容脱敏：连续 4-8 位数字，以及任何提到"密码"的整段文字。
    private val CODE_PATTERN = Regex("\\b\\d{4,8}\\b")
    private val SENSITIVE_WORD = Regex("密码|验证码|password|passcode", RegexOption.IGNORE_CASE)

    private fun redact(s: String): String {
        if (SENSITIVE_WORD.containsMatchIn(s)) return "[含敏感字样内容已整体脱敏]"
        return CODE_PATTERN.replace(s, "******")
    }

    private fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE_NAME)

    /**
     * @param task 任务描述，例如"微信给老妈发消息"
     * @param action 做了什么，例如"点击发送按钮" / "拨打电话 138****0000"
     * @param result 核验结果，例如"成功" / "失败：第 2 步未找到发送按钮"
     */
    @Synchronized
    fun append(ctx: Context, task: String, action: String, result: String) {
        try {
            val entry = JSONObject()
                .put("t", fmt.format(java.util.Date()))
                .put("task", redact(task))
                .put("action", redact(action))
                .put("result", redact(result))
            file(ctx).appendText(entry.toString() + "\n")
        } catch (_: Exception) {
            // 日志写失败不应该影响主流程
        }
    }

    /** 最近的在前。界面展示用的人类可读一行。 */
    @Synchronized
    fun readRecent(ctx: Context): List<String> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().takeLast(MAX_READ_LINES).asReversed().map { line ->
                try {
                    val j = JSONObject(line)
                    "[${j.optString("t")}] ${j.optString("task")} —— ${j.optString("action")} —— ${j.optString("result")}"
                } catch (_: Exception) {
                    line
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) {}
    }
}
