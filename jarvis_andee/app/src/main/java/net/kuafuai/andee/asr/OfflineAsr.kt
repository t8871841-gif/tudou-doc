package net.kuafuai.andee.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import net.kuafuai.andee.config.VoiceConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Offline listener for the Jarvis fork: sherpa-onnx streaming zipformer
 * (bilingual zh-en), the same model and runtime the owner's Flutter Jarvis
 * ran on this device family — its transcript quality is the known quantity
 * here, not a hope.
 *
 * The shape is [HuoshanAsr]'s, because the interface is: audio is pushed in
 * by the caller, the end of the turn arrives as the `isLast` marker on
 * [sendAudio], and [waitFinished] is a latch. Everything runs on one worker
 * thread — recognition state is confined to it, and [sendAudio] only ever
 * copies and enqueues, never blocks the capture thread.
 *
 * The recogniser itself is process-wide: building it costs seconds (four
 * model files, ~180 MB copied out of the APK's assets on first run), so it
 * is built once and each turn takes a fresh stream from it.
 */
class OfflineAsr(
    @Suppress("unused") config: VoiceConfig,
    private val listener: AsrEngine.Listener,
    private val context: Context,
) : AsrEngine {

    /** Local decoding settles fast; the wait is for the last frames, not a network. */
    override val finalWaitMs: Long = 2_000L

    /** Same outer bound as the other engines — the caller's rule, not the recogniser's endpointing. */
    override val silenceMs: Long = 3_000L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OfflineAsr").apply { isDaemon = true }
    }

    private val finishedLatch = CountDownLatch(1)

    @Volatile
    private var closed = false

    // ---- Worker-thread confined ------------------------------------------

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var failed = false
    private var finalSent = false

    /** Segments the endpoint detector has already closed, kept across resets. */
    private var committed = ""
    private var lastPartial = ""

    override fun start(sampleRate: Int, channels: Int) {
        io.execute {
            try {
                val rec = sharedRecognizer(context)
                recognizer = rec
                stream = rec.createStream()
                listener.onReady()
            } catch (t: Throwable) {
                Log.e(TAG, "recogniser unavailable", t)
                failed = true
                listener.onError("离线识别不可用：${t.message ?: "模型加载失败"}")
                finishedLatch.countDown()
            }
        }
    }

    override fun sendAudio(pcm: ByteArray, isLast: Boolean) {
        if (closed) return
        val block = if (isLast) null else pcm.copyOf()
        io.execute {
            if (closed) return@execute
            if (failed) {
                finishedLatch.countDown()
                return@execute
            }
            val rec = recognizer ?: return@execute
            val s = stream ?: return@execute
            if (block != null && block.isNotEmpty()) {
                s.acceptWaveform(toFloats(block), SAMPLE_RATE)
                decodeAvailable(rec, s)
            }
            if (isLast) finishTurn(rec, s)
        }
    }

    /** The final arrives through the `isLast` marker, like 火山's — there is nothing to gain from [endOfTurn] here. */
    override fun endOfTurn(bySilence: Boolean) = Unit

    override fun waitFinished(timeoutMs: Long): Boolean =
        finishedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        closed = true
        // runCatching: a second close() must not throw on a shut-down executor.
        runCatching {
            io.execute {
                runCatching { stream?.release() }
                stream = null
            }
            io.shutdown()
        }
    }

    // ---- Worker thread only ------------------------------------------------

    private fun decodeAvailable(rec: OnlineRecognizer, s: OnlineStream) {
        while (rec.isReady(s)) rec.decode(s)
        if (rec.isEndpoint(s)) {
            val seg = rec.getResult(s).text
            if (seg.isNotBlank()) committed += seg
            rec.reset(s)
        }
        reportPartial(rec, s)
    }

    private fun reportPartial(rec: OnlineRecognizer, s: OnlineStream) {
        val text = committed + rec.getResult(s).text
        if (text != lastPartial) {
            lastPartial = text
            if (text.isNotEmpty()) listener.onPartial(text)
        }
    }

    private fun finishTurn(rec: OnlineRecognizer, s: OnlineStream) {
        if (finalSent) return
        finalSent = true
        try {
            s.inputFinished()
            while (rec.isReady(s)) rec.decode(s)
            val full = committed + rec.getResult(s).text
            listener.onFinal(full)
        } catch (t: Throwable) {
            Log.e(TAG, "final decode failed", t)
            listener.onError("离线识别出错：${t.message ?: "解码失败"}")
        } finally {
            finishedLatch.countDown()
        }
    }

    /** 16-bit little-endian PCM → the float waveform the recogniser eats. */
    private fun toFloats(pcm: ByteArray): FloatArray {
        val n = pcm.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = pcm[2 * i].toInt() and 0xFF
            val hi = pcm[2 * i + 1].toInt()
            out[i] = ((hi shl 8) or lo) / 32768.0f
        }
        return out
    }

    companion object {
        private const val TAG = "OfflineAsr"

        private const val SAMPLE_RATE = 16_000

        // The four files of sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20,
        // staged into the APK's assets by CI (see build-jarvis-andee-apk.yml);
        // they are not in git.
        private const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
        private const val DECODER = "decoder-epoch-99-avg-1.onnx"
        private const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
        private const val TOKENS = "tokens.txt"
        private val MODEL_FILES = listOf(ENCODER, DECODER, JOINER, TOKENS)

        /** Bump when the bundled model changes, so a stale copy is replaced. */
        private const val MODEL_MARKER = ".ready_v1"

        @Volatile
        private var shared: OnlineRecognizer? = null
        private val sharedLock = Any()

        init {
            // The AAR's own classes also load it; a double load is a no-op,
            // and doing it here keeps the failure where it can be reported.
            runCatching { System.loadLibrary("sherpa-onnx-jni") }
        }

        private fun sharedRecognizer(context: Context): OnlineRecognizer {
            shared?.let { return it }
            synchronized(sharedLock) {
                shared?.let { return it }
                val dir = prepareModelDir(context.applicationContext)
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = File(dir, ENCODER).absolutePath,
                            decoder = File(dir, DECODER).absolutePath,
                            joiner = File(dir, JOINER).absolutePath,
                        ),
                        tokens = File(dir, TOKENS).absolutePath,
                        numThreads = 2,
                        modelType = "zipformer",
                    ),
                    endpointConfig = EndpointConfig(),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                )
                val rec = OnlineRecognizer(null, config)
                shared = rec
                return rec
            }
        }

        /**
         * The models ride in the APK as assets but are recognised from real
         * files — the same staging the Flutter build used, for the same
         * reason: native code wants paths, not asset handles. Copied once;
         * the marker is written only after every file is whole.
         */
        private fun prepareModelDir(context: Context): File {
            val dir = File(context.filesDir, "asr")
            val marker = File(dir, MODEL_MARKER)
            if (marker.exists()) return dir
            dir.mkdirs()
            for (name in MODEL_FILES) {
                context.assets.open("asr/$name").use { input ->
                    FileOutputStream(File(dir, name)).use { output -> input.copyTo(output) }
                }
            }
            marker.writeText("ok")
            return dir
        }
    }
}
