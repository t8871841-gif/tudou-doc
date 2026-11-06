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
        // 通话中不播报（移植自 Andee device/CallState）：记审计后 onDone(false)，
        // 让调用方知道这次没播成（不要 onDone(true) 假装成功，否则静默吞掉无痕）。
        // 回复文字仍在气泡/历史里，不丢。CallState 未启动（没给电话权限）时 isInCall() 恒 false，门是惰性的。
        if (com.jarvis.app.device.CallState.isInCall()) {
            com.jarvis.app.AuditLog.append(ctx, "语音播报", "通话中，播报跳过", "失败：通话中未播报")
            onDone(false)
            return
        }
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
        // 续播锚点：本轮首个失败句的下标（0-based，与 EdgeTts.splitSentences 切分一致），
        // 由 EdgeTts.speak 的 onSentenceFailed 回调记下；没回调说明不是按句失败
        // （取消/失速/整段异常等），走旧的整段重试。
        var failedIndex: Int? = null
        var failedStoppedHere = false
        EdgeTts.speak(
            ctx, text, voice = voice,
            ratePct = JarvisPrefs.getRatePct(ctx), pitchHz = JarvisPrefs.getPitchHz(ctx),
            onStart = startOnce,
            onDone = { ok ->
                if (ok || attemptsLeft <= 1 || stopSeq != stopSeqAtStart) {
                    done(ok)
                } else {
                    // 从失败句续播（仍只占一次重试，attemptsLeft 照常减一）：切不出尾巴时
                    // （没拿到失败句下标）回落到旧的整段重试；续播再失败直接走 done(false)，
                    // 外层 auto 档照常落系统 TTS，edge 档照常放弃，不静默丢掉。
                    val tailText = resumeText(text, failedIndex, failedStoppedHere)
                    speakEdge(ctx, tailText ?: text, attemptsLeft - 1, startOnce, stopSeqAtStart, onSentence, done)
                }
            },
            onSentence = onSentence,
            onSentenceFailed = { index, _, stoppedHere ->
                failedIndex = index
                failedStoppedHere = stoppedHere
            }
        )
    }

    /**
     * 从失败句切续播文本（下标 0-based，与 EdgeTts.splitSentences 一致）：
     * - stoppedHere=true（播到这句断了）：该句及之后都没播，全续上；
     * - stoppedHere=false（这句被跳过、后面已播完）：只补这一句，不重播已播完的尾巴；
     * - failedIndex 为空或越界（不是按句失败）：返回 null，调用方走旧的整段重试。
     */
    private fun resumeText(text: String, failedIndex: Int?, stoppedHere: Boolean): String? {
        val index = failedIndex ?: return null
        val sentences = EdgeTts.splitSentences(text)
        if (index < 0 || index >= sentences.size) return null
        val tail = if (stoppedHere) sentences.subList(index, sentences.size) else listOf(sentences[index])
        return tail.joinToString(" ").trim().ifEmpty { null }
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
