package com.jarvis.app.tts

import android.content.Context
import com.jarvis.app.JarvisPrefs

/**
 * 播报路由：先 Edge 神经语音（设置里的音色/语速/音调），失败自动落系统 TTS 兜底，
 * 两边都失败才把 onDone(false) 交回去。旧 Flutter 版就是 Edge + 系统 TTS 两条腿，
 * Kotlin 版此前只搬了 Edge 一条——jk-v11 真机上网络不通就完全没声，这次补齐。
 */
object TtsRouter {

    fun speak(ctx: Context, text: String, onStart: () -> Unit, onDone: (Boolean) -> Unit) {
        val voice = JarvisPrefs.getVoice(ctx).ifBlank { EdgeVoices.DEFAULT_ID }
        var started = false
        EdgeTts.speak(
            ctx, text, voice = voice,
            ratePct = JarvisPrefs.getRatePct(ctx), pitchHz = JarvisPrefs.getPitchHz(ctx),
            onStart = {
                if (!started) {
                    started = true
                    onStart()
                }
            },
            onDone = { ok ->
                if (ok) {
                    onDone(true)
                } else {
                    SystemTts.speak(ctx, text) { sysOk -> onDone(sysOk) }
                }
            }
        )
    }

    fun stop() {
        EdgeTts.stop()
        SystemTts.stop()
    }
}
