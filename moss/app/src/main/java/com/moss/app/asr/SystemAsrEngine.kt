package com.moss.app.asr

// Moss: the Android system speech recogniser behind the AsrEngine interface.
// Listening half of the keyless "Edge" provider (speaking is EdgeTtsEngine).
// Ported from Jarvis's dormant SystemAsr backup (same author, same device)
// and reshaped to this interface's streaming shape.
//
// Shape mismatch, and how it is bridged: this interface was drawn for
// streaming backends that take PCM pushed into them, while the system
// recogniser owns the microphone itself and decides on its own when an
// utterance ends. So [sendAudio] is a no-op — the caller's capture thread
// keeps running (and keeps the turn machinery's `isStreaming` true, which the
// silence watchdog and stop path depend on), and the PCM is discarded. What
// the recogniser hears comes from its own capture, not from these calls.
//
// Turn end: the recogniser usually ends the turn itself ([onResults]), and
// [endOfTurn]/[close] cover the rest by settling the last partial heard, so a
// turn never hangs on a recogniser that went quiet. A recogniser error that
// only means "nothing was said" (no match, speech timeout) settles quietly
// as an empty final, exactly like an empty turn on the other backends;
// anything else is a real error.
//
// No key, no download, no account. Quality depends on this device's speech
// service, which is why the caller falls back to 火山 when
// [isAvailable] is false — and why this still needs a real-device test.

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
) : AsrEngine {

    companion object {
        private const val TAG = "SystemAsr"

        /** Ready fallback: some services never fire onReadyForSpeech. */
        private const val READY_FALLBACK_MS = 2000L

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
     * first. Longer than 火山's because the system service's own endpointing
     * is what normally fires, and cutting in earlier would clip trailing
     * words the service had not finished settling.
     */
    override val silenceMs: Long = 5000L

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

    @Volatile
    private var recognizer: SpeechRecognizer? = null

    override fun start(sampleRate: Int, channels: Int) {
        val gen = ++generation
        started = true
        lastPartial = ""
        readyFired = false
        delivered.set(false)
        doneLatch = CountDownLatch(1)
        if (!isAvailable(context)) {
            terminalError("系统语音识别不可用")
            return
        }
        main.post {
            if (gen != generation) return@post
            val r = try {
                SpeechRecognizer.createSpeechRecognizer(context)
            } catch (e: Exception) {
                terminalError("启动识别失败：${e.message}")
                return@post
            }
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    fireReady()
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onPartialResults(partialResults: Bundle?) {
                    if (gen != generation) return
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
                    val txt = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    destroyRecognizer(r)
                    deliverFinal(txt)
                }

                override fun onError(error: Int) {
                    if (gen != generation) return
                    destroyRecognizer(r)
                    when (error) {
                        // Nothing said, or nothing intelligible: an empty
                        // turn, not a failure. The caller ends it quietly.
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> deliverFinal(lastPartial)
                        else -> terminalError("识别出错(code=$error)")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                r.startListening(intent)
            } catch (e: Exception) {
                destroyRecognizer(r)
                terminalError("启动识别失败：${e.message}")
                return@post
            }
            main.postDelayed({ if (gen == generation) fireReady() }, READY_FALLBACK_MS)
        }
    }

    /**
     * Discarded on purpose: the system recogniser captures for itself. The
     * caller keeps its capture thread running regardless — see the class
     * KDoc — so this must stay cheap and non-blocking.
     */
    override fun sendAudio(pcm: ByteArray, isLast: Boolean) = Unit

    override fun endOfTurn(bySilence: Boolean) {
        generation++
        cancelRecognizer()
        // The recogniser's own callbacks are now suppressed, so settle here:
        // by construction the last results are already in hand.
        deliverFinal(lastPartial)
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
        deliverFinal(lastPartial)
    }

    private fun fireReady() {
        if (!readyFired) {
            readyFired = true
            listener.onReady()
        }
    }

    /** First delivery wins; every later one (including stale callbacks) is dropped. */
    private fun deliverFinal(text: String) {
        if (delivered.compareAndSet(false, true)) {
            doneLatch.countDown()
            listener.onFinal(text)
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
