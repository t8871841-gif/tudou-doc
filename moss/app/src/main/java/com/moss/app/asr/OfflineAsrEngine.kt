package com.moss.app.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ported from Jarvis's OfflineAsr (itself ported from the reference
 * implementation, originally net.kuafuai.andee.asr.OfflineAsr): sherpa-onnx
 * streaming zipformer bilingual offline recognition, the same model the
 * Flutter Jarvis used. Logic unchanged except:
 *   - package renamed to com.moss.app.asr;
 *   - constructor order swapped to Moss's (context, listener) convention,
 *     matching [SystemAsrEngine].
 *
 * This class only turns "PCM in, text out" and never touches the microphone —
 * capturing audio and deciding "the user has finished" is [AsrController]'s
 * job, which already captures at 16 kHz in 100 ms chunks and ends turns on
 * its own silence rule. Two layers stay separate for the same reason as in
 * Jarvis: the caller only ever sees [AsrEngine].
 *
 * **Build premise**: app/libs/ must hold the sherpa-onnx aar and
 * app/src/main/assets/asr/ must hold the four model files (names below in
 * ENCODER/DECODER/JOINER/TOKENS). Both are binaries, neither enters git;
 * the CI workflow downloads them. Missing the aar, the build fails
 * outright (the dependency is a file reference). Missing the model files,
 * [start] reports "offline recognition unavailable" and [assetsPresent]
 * reports false for SelfCheck — it never pretends to work.
 */
class OfflineAsrEngine(
    private val context: Context,
    private val listener: AsrEngine.Listener,
) : AsrEngine {

    /** Local decode is fast; the wait mostly covers the last frames, not a network. */
    override val finalWaitMs: Long = 2_000L

    /** The caller's own silence window, declared here for others to read; this class does not depend on it. */
    override val silenceMs: Long = 3_000L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OfflineAsr").apply { isDaemon = true }
    }

    private val finishedLatch = CountDownLatch(1)

    @Volatile
    private var closed = false

    // ---- Fields below are only read/written on the single io thread ----

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var failed = false
    private var finalSent = false

    /** Segments the endpoint detector has already cut, accumulated across decodes. */
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

    override fun endOfTurn(bySilence: Boolean) = Unit

    override fun waitFinished(timeoutMs: Long): Boolean =
        finishedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        closed = true
        runCatching {
            io.execute {
                runCatching { stream?.release() }
                stream = null
            }
            io.shutdown()
        }
    }

    // ---- Only called on the io worker thread ----

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

    /** 16-bit little-endian PCM → the float waveform the recogniser wants. */
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
        const val SAMPLE_RATE = 16_000

        // sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20's four files,
        // under app/src/main/assets/asr/ (not in git — binaries, the CI workflow
        // downloads them).
        private const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
        private const val DECODER = "decoder-epoch-99-avg-1.onnx"
        private const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
        private const val TOKENS = "tokens.txt"
        val MODEL_FILES = listOf(ENCODER, DECODER, JOINER, TOKENS)

        /** Change when the model version changes; stale copied files are then re-copied. */
        private const val MODEL_MARKER = ".ready_v1"

        @Volatile
        private var shared: OnlineRecognizer? = null
        private val sharedLock = Any()

        init {
            // The aar's own classes load this again; the repeat is a no-op, and
            // placing it here lets a failure be caught in logs instead of
            // crashing quietly in native code.
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

        /** Model files ship in assets, but native code needs real paths: copied to filesDir on first use. */
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

        /** SelfCheck use: whether the four model files are present in assets (existence only — not integrity or version). */
        fun assetsPresent(context: Context): Boolean {
            return try {
                val names = context.assets.list("asr")?.toSet() ?: emptySet()
                MODEL_FILES.all { it in names }
            } catch (_: Exception) {
                false
            }
        }
    }
}
