package com.jarvis.app.tts

import android.content.Context
import com.jarvis.app.JarvisPrefs

/**
 * 播报路由（批八加引擎三档）：按设置里的语音引擎走——
 * - auto（默认）：Edge 云扬先行，失败**重试一次**再落系统 TTS 兜底（jk-v12 反馈两个声
 *   来回跳，多半是 Edge 偶发失败立刻落系统；重试一次能吃掉大部分抖动）；
 * - edge：只用云扬，失败重试一次后放弃（断网只出文字，不换声）；
 * - system：只用系统 TTS。
 * onStart 只回调一次；两边都失败才把 onDone(false) 交回去。
 */
object TtsRouter {

    fun speak(ctx: Context, text: String, onStart: () -> Unit, onDone: (Boolean) -> Unit) {
        var started = false
        val startOnce: () -> Unit = {
            if (!started) {
                started = true
                onStart()
            }
        }
        when (JarvisPrefs.getTtsEngine(ctx)) {
            "system" -> SystemTts.speak(ctx, text, onStart = startOnce) { ok -> onDone(ok) }
            "edge" -> speakEdge(ctx, text, attemptsLeft = 2, startOnce) { ok -> onDone(ok) }
            else -> speakEdge(ctx, text, attemptsLeft = 2, startOnce) { ok ->
                if (ok) {
                    onDone(true)
                } else {
                    SystemTts.speak(ctx, text, onStart = startOnce) { sysOk -> onDone(sysOk) }
                }
            }
        }
    }

    private fun speakEdge(
        ctx: Context,
        text: String,
        attemptsLeft: Int,
        startOnce: () -> Unit,
        done: (Boolean) -> Unit
    ) {
        val voice = JarvisPrefs.getVoice(ctx).ifBlank { EdgeVoices.DEFAULT_ID }
        EdgeTts.speak(
            ctx, text, voice = voice,
            ratePct = JarvisPrefs.getRatePct(ctx), pitchHz = JarvisPrefs.getPitchHz(ctx),
            onStart = startOnce,
            onDone = { ok ->
                if (ok || attemptsLeft <= 1) {
                    done(ok)
                } else {
                    speakEdge(ctx, text, attemptsLeft - 1, startOnce, done)
                }
            }
        )
    }

    fun stop() {
        EdgeTts.stop()
        SystemTts.stop()
    }
}
