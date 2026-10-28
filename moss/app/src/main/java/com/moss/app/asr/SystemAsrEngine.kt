package com.moss.app.asr

// Moss: the Android system speech recogniser behind the AsrEngine interface.
// Listening half of the keyless "Edge" provider (speaking is EdgeTtsEngine).
// Ported from Jarvis's dormant SystemAsr backup (same author, same device)
// and reshaped to this interface's streaming shape.
//
// Shape mismatch, and how it is bridged: this interface was drawn for
// streaming backends that take PCM pushed into them, while the system
// recogniser owns the microphone itself and decides on its own when an
// utterance ends. So [sendAudio] is a no-op and the caller opens no capture
// of its own for this engine ([ownsMicrophone]) — the turn's liveness is
// tracked by the caller instead of read off its own stream. What the
// recogniser hears comes from its own capture, not from these calls.
//
// Turn end: the recogniser usually ends the turn itself ([onResults]), and
// [endOfTurn]/[close] cover the rest by settling the last partial heard, so a
// turn never hangs on a recogniser that went quiet. A recogniser error that
// only means "nothing was said" (no match, speech timeout) settles as an
// empty end that carries its cause ([AsrEngine.Listener.onEmptyEnd]),
// so the caller can report it instead of clearing the turn silently —
// a silent empty end reads exactly like a dead tap. Anything else is a
// real error. A service that takes the request and never answers at all —
// no callback of any kind within [NO_CALLBACK_MS] — is a real error too:
// without the deadline the turn would end silently on the caller's
// silence watchdog and the tap would look dead.
//
// No key, no download, no account. Quality depends on this device's speech
// service, which is why the caller checks [isAvailable] before starting a
// turn — and why this still needs a real-device test.

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SystemAsrEngine(
    private val context: Context,
    private val listener: AsrEngine.Listener,
    /**
     * The recognition service to bind, or null for the system default. A
     * device can carry several services and the default can be a stub
     * that takes the session and never answers; pinning one is how the
     * user picks a service that actually hears.
     */
    private val service: ComponentName? = null,
) : AsrEngine {

    companion object {
        private const val TAG = "SystemAsr"

        /** Ready fallback: some services never fire onReadyForSpeech. */
        private const val READY_FALLBACK_MS = 2000L

        /**
         * No-callback deadline: a service that has not produced a single
         * callback this long after the request is not listening. Long
         * enough that a slow first bind never trips it — onReadyForSpeech
         * normally arrives within a second — short enough that the user
         * is told promptly instead of watching a dead turn.
         */
        private const val NO_CALLBACK_MS = 8000L

        fun isAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * The final is produced by the recogniser's own endpointing or settled
     * synchronously by [endOfTurn]/[close], so the wait is only ever a
     * hand-off, never a network round trip.
     */
    override val finalWaitMs: Long = 1000L

    /**
     * Backstop only: the recogniser ends turns on its own voice detection
     * first. Long, because the system service's own endpointing is what
     * normally fires, and cutting in earlier would clip trailing words the
     * service had not finished settling.
     */
    override val silenceMs: Long = 5000L

    /** The system recogniser owns the microphone; see [AsrEngine.ownsMicrophone]. */
    override val ownsMicrophone: Boolean = true

    private val main = Handler(Looper.getMainLooper())

    /** Bump on every end/close so callbacks from a dead session are ignored. */
    @Volatile
    private var generation = 0

    private val delivered = AtomicBoolean(false)

    @Volatile
    private var doneLatch = CountDownLatch(1)

    @Volatile
    private var started = false

    @Volatile
    private var lastPartial = ""

    @Volatile
    private var readyFired = false

    /**
     * True once the service has spoken back in any form this session:
     * ready, speech, partial, results, or error. Only the service's own
     * callbacks count — the [READY_FALLBACK_MS] timer is this class's,
     * not the service's, so it must not set this.
     */
    @Volatile
    private var heardFromService = false

    @Volatile
    private var recognizer: SpeechRecognizer? = null

    override fun start(sampleRate: Int, channels: Int) {
        val gen = ++generation
        started = true
        lastPartial = ""
        readyFired = false
        heardFromService = false
        delivered.set(false)
        doneLatch = CountDownLatch(1)
        if (!isAvailable(context)) {
            terminalError("系统语音识别不可用")
            return
        }
        main.post {
            if (gen != generation) return@post
            val r = try {
                if (service != null) SpeechRecognizer.createSpeechRecognizer(context, service)
                else SpeechRecognizer.createSpeechRecognizer(context)
            } catch (e: Exception) {
                terminalError("启动识别失败：${e.message}")
                return@post
            }
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    heardFromService = true
                    fireReady()
                }

                override fun onBeginningOfSpeech() {
                    heardFromService = true
                }

                override fun onRmsChanged(rmsdB: Float) {
                    heardFromService = true
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    heardFromService = true
                }

                override fun onEndOfSpeech() {
                    heardFromService = true
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (gen != generation) return
                    heardFromService = true
                    val txt = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (txt.isNotBlank()) {
                        lastPartial = txt
                        listener.onPartial(txt)
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (gen != generation) return
                    heardFromService = true
                    val txt = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    destroyRecognizer(r)
                    deliverFinal(txt, "识别没有返回文字")
                }

                override fun onError(error: Int) {
                    if (gen != generation) return
                    heardFromService = true
                    destroyRecognizer(r)
                    when (error) {
                        // Nothing said, or nothing intelligible: an empty
                        // end with its cause, not a failure. The caller
                        // reports the cause instead of ending quietly.
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> deliverFinal(
                            lastPartial,
                            "没听见：识别没有返回内容",
                        )
                        else -> terminalError("识别出错(code=$error)")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    heardFromService = true
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Which app is asking. Optional by the docs, but some
                // services misbehave without it — a session that ends
                // with no callback at all is one of the ways that shows.
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            try {
                r.startListening(intent)
            } catch (e: Exception) {
                destroyRecognizer(r)
                terminalError("启动识别失败：${e.message}")
                return@post
            }
            main.postDelayed({ if (gen == generation) fireReady() }, READY_FALLBACK_MS)
            // No-callback deadline: a service that takes the request and
            // never answers must fail loudly, or the tap looks dead. The
            // generation check is the cancellation — [endOfTurn]/[close]
            // bump it — and the flag is only ever set by the service's
            // own callbacks, never by the ready fallback above.
            main.postDelayed({
                if (gen == generation && !heardFromService) {
                    destroyRecognizer(r)
                    terminalError("系统识别没有反应")
                }
            }, NO_CALLBACK_MS)
        }
    }

    /**
     * Discarded on purpose: the system recogniser captures for itself. The
     * caller opens no capture of its own for this engine, so this must stay
     * cheap and non-blocking.
     */
    override fun sendAudio(pcm: ByteArray, isLast: Boolean) = Unit

    override fun endOfTurn(bySilence: Boolean) {
        generation++
        cancelRecognizer()
        // The recogniser's own callbacks are now suppressed, so settle here:
        // by construction the last results are already in hand. A turn the
        // user ended by hand settles silently, as before; a turn the silence
        // rule ended carries its cause, so an empty end is reported instead
        // of looking like a dead tap.
        val cause = if (!bySilence) null
            else if (!heardFromService) "没听见：识别服务没有回调"
            else "没听见：静默结束，没有内容"
        deliverFinal(lastPartial, cause)
    }

    override fun waitFinished(timeoutMs: Long): Boolean {
        return try {
            doneLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
    }

    override fun close() {
        if (!started) return
        generation++
        cancelRecognizer()
        // Covers the path where the wait timed out with no delivery: settle
        // rather than hang the turn. No-op when a final already went out.
        // The cause only matters on the standalone path — inside a stop the
        // delivery above has already won — but it costs nothing to carry it.
        val cause = if (!heardFromService) "没听见：识别服务没有回调"
            else "没听见：没有听到内容"
        deliverFinal(lastPartial, cause)
    }

    private fun fireReady() {
        if (!readyFired) {
            readyFired = true
            listener.onReady()
        }
    }

    /**
     * First delivery wins; every later one (including stale callbacks) is
     * dropped. A blank line carries its cause to
     * [AsrEngine.Listener.onEmptyEnd] instead of [onFinal], so the caller
     * can say why nothing was heard rather than clearing the turn silently.
     * A blank line with no cause — a turn the user ended by hand — keeps
     * today's silent [onFinal] path.
     */
    private fun deliverFinal(text: String, cause: String? = null) {
        if (delivered.compareAndSet(false, true)) {
            doneLatch.countDown()
            if (text.isBlank() && cause != null) listener.onEmptyEnd(cause)
            else listener.onFinal(text)
        }
    }

    private fun terminalError(msg: String) {
        Log.w(TAG, msg)
        if (delivered.compareAndSet(false, true)) {
            doneLatch.countDown()
            listener.onError(msg)
        }
    }

    private fun cancelRecognizer() {
        val r = recognizer
        recognizer = null
        if (r != null) {
            main.post {
                runCatching { r.cancel() }
                runCatching { r.destroy() }
            }
        }
    }

    private fun destroyRecognizer(r: SpeechRecognizer) {
        main.post {
            runCatching { r.destroy() }
            if (recognizer === r) recognizer = null
        }
    }
}
