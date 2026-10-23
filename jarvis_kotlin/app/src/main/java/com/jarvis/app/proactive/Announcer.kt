package com.jarvis.app.proactive

import android.content.Context
import com.jarvis.app.AuditLog
import com.jarvis.app.JarvisAgent
import com.jarvis.app.JarvisNotificationListener
import com.jarvis.app.JarvisPrefs
import com.jarvis.app.tts.TtsRouter

/**
 * 批六：主动播报。接上 JarvisNotificationListener 早就留好的 announceOn/sink 钩子
 * （Flutter 时代由 Dart 决策，Kotlin 版一直没人接）。决策顺序：
 * 总开关 → 静音 → 勿扰时段 → 验证码不读（沿用 v2 策略）→ 忙碌中（非 idle）不插播 →
 * 来电/短信各自开关、其他应用只播白名单内的。播报走 EdgeTts 现成通道。
 */
object Announcer {

    private val CODE_HINT = Regex("验证码|校验码|动态码|验证代码|一次性密码|OTP", RegexOption.IGNORE_CASE)

    fun attach(ctx: Context) {
        JarvisNotificationListener.announceOn = true
        JarvisNotificationListener.sink = { m -> onNotification(ctx, m) }
    }

    private fun onNotification(ctx: Context, m: Map<String, Any>) {
        try {
            if (!JarvisPrefs.isAnnounceEnabled(ctx)) return
            if (JarvisPrefs.isMuted(ctx)) return
            if (JarvisPrefs.isDndActive(ctx)) return
            if (JarvisAgent.currentState() != "idle") return
            val title = m["title"] as? String ?: ""
            val text = m["text"] as? String ?: ""
            if (CODE_HINT.containsMatchIn(title) || CODE_HINT.containsMatchIn(text)) return
            val app = m["app"] as? String ?: ""
            val pkg = m["pkg"] as? String ?: ""
            val isCall = m["call"] as? Boolean ?: false
            val isMsg = m["msg"] as? Boolean ?: false
            val speakText = when {
                isCall && JarvisPrefs.isAnnounceCalls(ctx) ->
                    "来电：${title.ifBlank { text }}".trim()
                isMsg && JarvisPrefs.isAnnounceSms(ctx) ->
                    "$app，$title：${text.take(80)}"
                pkg in JarvisPrefs.announceAppsSet(ctx) ->
                    "$app：$title，${text.take(80)}"
                else -> return
            }
            if (speakText.isBlank() || speakText.endsWith("：")) return
            AuditLog.append(ctx, "主动播报", speakText.take(60), "已播报")
            TtsRouter.speak(ctx, speakText, onStart = {}, onDone = {})
        } catch (_: Exception) {
        }
    }
}
