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

    /**
     * 停止代次（L4 连锁修补）：EdgeTts/SystemTts 现在取消时也会回调 onDone(false)，
     * TtsRouter 必须分清“真失败”和“用户点了停”。stop() 时代次自增；speak() 开始时
     * 记下当代次，重试/兜底前校验代次未变——变了说明中途被停了，直接报失败不再重试、
     * 不再兜底到系统 TTS，否则打断后会重念整段旧回复（乒乓重播）。
     */
    @Volatile
    private var stopSeq = 0

    fun speak(ctx: Context, text: String, onStart: () -> Unit, onDone: (Boolean) -> Unit, onSentence: ((String) -> Unit)? = null) {
        val myStopSeq = stopSeq
        var started = false
        val startOnce: () -> Unit = {
            if (!started) {
                started = true
                onStart()
            }
        }
        when (JarvisPrefs.getTtsEngine(ctx)) {
            "system" -> speakSystem(ctx, text, startOnce, onSentence) { ok -> onDone(ok) }
            "edge" -> speakEdge(ctx, text, attemptsLeft = 2, startOnce, myStopSeq, onSentence) { ok -> onDone(ok) }
            else -> speakEdge(ctx, text, attemptsLeft = 2, startOnce, myStopSeq, onSentence) { ok ->
                if (ok) {
                    onDone(true)
                } else if (stopSeq != myStopSeq) {
                    // 中途被停了：不要兜底到系统 TTS，直接报失败（调用方靠 genSeq 丢弃）。
                    onDone(false)
                } else {
                    speakSystem(ctx, text, startOnce, onSentence) { sysOk -> onDone(sysOk) }
                }
            }
        }
    }

    /**
     * 系统 TTS 兜底：Edge 的播报焦点只覆盖 Edge 路径，这里播报前同样申请焦点、
     * 播完释放；SystemTts 不分句回调，onSentence 只能在开念时对整段发一次，
     * 句级跟念只在 Edge 路径上才有。
     */
    private fun speakSystem(
        ctx: Context,
        text: String,
        startOnce: () -> Unit,
        onSentence: ((String) -> Unit)?,
        done: (Boolean) -> Unit
    ) {
        EdgeTts.acquireAudioFocus(ctx)
        var sentenceFired = false
        val startWithSentence: () -> Unit = {
            startOnce()
            if (!sentenceFired) {
                sentenceFired = true
                onSentence?.invoke(text)
            }
        }
        SystemTts.speak(ctx, text, onStart = startWithSentence) { sysOk ->
            EdgeTts.releaseAudioFocus()
            done(sysOk)
        }
    }

    private fun speakEdge(
        ctx: Context,
        text: String,
        attemptsLeft: Int,
        startOnce: () -> Unit,
        stopSeqAtStart: Int,
        onSentence: ((String) -> Unit)?,
        done: (Boolean) -> Unit
    ) {
        val voice = JarvisPrefs.getVoice(ctx).ifBlank { EdgeVoices.DEFAULT_ID }
        EdgeTts.speak(
            ctx, text, voice = voice,
            ratePct = JarvisPrefs.getRatePct(ctx), pitchHz = JarvisPrefs.getPitchHz(ctx),
            onStart = startOnce,
            onDone = { ok ->
                if (ok || attemptsLeft <= 1 || stopSeq != stopSeqAtStart) {
                    done(ok)
                } else {
                    speakEdge(ctx, text, attemptsLeft - 1, startOnce, stopSeqAtStart, onSentence, done)
                }
            },
            onSentence = onSentence
        )
    }

    /** L8 Earcon：开麦/思考提示音，委托给 EdgeTts（不另起音频系统）。 */
    fun playEarcon(kind: EdgeTts.EarconKind) {
        EdgeTts.playEarcon(kind)
    }

    fun stop() {
        stopSeq++
        EdgeTts.stop()
        SystemTts.stop()
    }
}
