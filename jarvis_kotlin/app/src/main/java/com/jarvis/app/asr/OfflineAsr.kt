package com.jarvis.app.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 移植自参考实现（原 net.kuafuai.andee.asr.OfflineAsr）：sherpa-onnx 流式 zipformer
 * 中英双语离线识别，和之前 Flutter 版 Jarvis 用的同一套模型。逻辑基本没改，只是：
 *   - 去掉了未用到的 VoiceConfig 构造参数；
 *   - 包名从 net.kuafuai.andee.asr 改成 com.jarvis.app.asr。
 *
 * 这个类本身只管"喂 PCM 进去、吐文字出来"，不摸麦克风——采集音频、判断"用户说完了没有"
 * 是 [OfflineAsrSession] 的活，两层分开是为了跟 SystemAsr 的降级版保持同一种调用方式
 * （上层 JarvisAgent 只认 listenOnce/stop/isAvailable，这层细节切换不影响它）。
 *
 * **编译前提**：app/libs/ 下要有 sherpa-onnx 的 aar（二进制，不进 git，CI 上下载）。
 * 模型文件（文件名见下面 ENCODER/DECODER/JOINER/TOKENS）不再随 APK 打包：
 * 首次运行时 [prepareModelDir] 先从 assets 复制（旧包还带模型时），缺的改从
 * GitHub Release（asr-models-v1）下载，只在 WiFi 下，下完按字节数校验才写
 * marker。缺模型且非 WiFi 时语音不可用，等连上 WiFi 再试。
 */
class OfflineAsr(
    private val listener: AsrEngine.Listener,
    private val context: Context,
) : AsrEngine {

    /** 本地解码很快，这个等待主要是留给最后几帧处理，不是在等网络。 */
    override val finalWaitMs: Long = 2_000L

    /** 调用方（OfflineAsrSession）自己的安静判断窗口，这里只是声明给别人看，本类不依赖它。 */
    override val silenceMs: Long = 3_000L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OfflineAsr").apply { isDaemon = true }
    }

    private val finishedLatch = CountDownLatch(1)

    @Volatile
    private var closed = false

    /**
     * C3：close() 已被请求——此后新来的音频直接丢弃，但已经排进 io 队列的
     * sendAudio 先处理完，释放时才置 [closed]。原来 close() 立置 closed=true，
     * 在途音频会被 sendAudio 入口和队内检查两处丢弃，final 可能直接丢。
     */
    @Volatile
    private var closeRequested = false

    /**
     * M3：sherpa 端点命中后置位，[OfflineAsrSession] 用它作为轮次结束的信号之一
     * （与 partial 时钟配合），不再只用来攒文本。
     */
    @Volatile
    private var endpointHit = false

    /** M3：本轮是否命中过 sherpa 端点。 */
    fun isEndpointHit(): Boolean = endpointHit

    // ---- 以下字段只在 io 这一条工作线程上读写 ----

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var failed = false
    private var finalSent = false

    /** 端点检测器已经切出来的片段，跨多次 decode 累积。 */
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
        if (closeRequested) return
        val block = if (isLast) null else pcm.copyOf()
        runCatching {
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
    }

    override fun endOfTurn(bySilence: Boolean) = Unit

    override fun waitFinished(timeoutMs: Long): Boolean =
        finishedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        // C3：不要立置 closed=true 丢弃在途音频——把释放排到 io 队尾，已排队的
        // sendAudio 先跑完，释放时才置 closed 并停掉线程池。
        if (closeRequested) return
        closeRequested = true
        runCatching {
            io.execute {
                runCatching { stream?.release() }
                stream = null
                closed = true
                io.shutdown()
            }
        }
    }

    // ---- 以下只在 io 工作线程上调用 ----

    private fun decodeAvailable(rec: OnlineRecognizer, s: OnlineStream) {
        while (rec.isReady(s)) rec.decode(s)
        if (rec.isEndpoint(s)) {
            // M3：端点信号不只用来攒文本——置位后 Session 拿它当轮次结束信号之一。
            endpointHit = true
            Log.d(TAG, "endpoint hit (committed=${committed.length} chars)")
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

    /** 16bit 小端 PCM → 识别器要的浮点波形。 */
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

        // sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20 的四个文件，
        // 放在 app/src/main/assets/asr/ 下（不进 git，二进制，需要你们自己放）。
        private const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
        private const val DECODER = "decoder-epoch-99-avg-1.onnx"
        private const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
        private const val TOKENS = "tokens.txt"
        val MODEL_FILES = listOf(ENCODER, DECODER, JOINER, TOKENS)

        /** 模型换版本时改这个，旧的已复制文件会被判定过期重新复制。 */
        private const val MODEL_MARKER = ".ready_v1"

        /**
         * 瘦身：模型不再随 APK 打包，改从 GitHub Release 直链下载（只下一次）。
         * Release tag：asr-models-v1（t8871841-gif/tudou-doc）。下面是各文件的
         * 期望字节数，下载后逐个校验大小，不对就删掉重报，不让残缺文件进 recognizer。
         */
        private const val MODEL_BASE_URL =
            "https://github.com/t8871841-gif/tudou-doc/releases/download/asr-models-v1/"
        private val MODEL_URLS = mapOf(
            ENCODER to MODEL_BASE_URL + ENCODER,
            DECODER to MODEL_BASE_URL + DECODER,
            JOINER to MODEL_BASE_URL + JOINER,
            TOKENS to MODEL_BASE_URL + TOKENS,
        )
        private val MODEL_EXPECTED_SIZES = mapOf(
            ENCODER to 181895032L,
            DECODER to 13876452L,
            JOINER to 3228404L,
            TOKENS to 56317L,
        )

        /** 正在下载模型（modelStatus 用，给设置页看 "downloading"）。 */
        @Volatile
        private var downloading = false

        @Volatile
        private var shared: OnlineRecognizer? = null
        private val sharedLock = Any()

        init {
            // aar 自己的类里也会加载一次，这里重复加载是空操作；放在这里是为了让失败能被
            // 日志捕捉到，而不是悄悄崩在 native 层。
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
                    endpointConfig = EndpointConfig(
                        // M3：不要空构造全用库默认——规则值与 Session 的时钟对齐：
                        // rule1：4 秒没解出任何东西也算端点（对齐 SILENT_START_GRACE_MS）；
                        // rule2：出字后 3 秒尾部静音算端点（对齐 SILENCE_MS）；
                        // rule3：单轮 60 秒封顶（对齐 MAX_TURN_MS）。
                        rule1 = EndpointRule(false, 4.0f, 0.0f),
                        rule2 = EndpointRule(true, 3.0f, 0.0f),
                        rule3 = EndpointRule(false, 0.0f, 60.0f),
                    ),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                )
                val rec = OnlineRecognizer(null, config)
                shared = rec
                return rec
            }
        }

        /**
         * 模型文件要的是真实路径，首次运行准备到 filesDir/asr/。顺序：
         * 1. marker 在 = 已就位，直接返回（老用户覆盖安装不重下）；
         * 2. 逐个先从 assets 复制（瘦身前的旧包还带模型时走这条）；
         * 3. assets 里缺的文件改从 GitHub Release 下载——只在 WiFi 下，
         *    非 WiFi 直接抛错（语音暂不可用，等连上 WiFi 再试，不走流量）；
         * 4. 全部 4 个文件按期望字节数校验，齐了才写 marker；任一文件
         *    不完整/下载失败都抛错，残缺文件当场删掉，不进 recognizer。
         */
        private fun prepareModelDir(context: Context): File {
            val dir = File(context.filesDir, "asr")
            val marker = File(dir, MODEL_MARKER)
            if (marker.exists()) return dir
            dir.mkdirs()
            val toDownload = mutableListOf<String>()
            for (name in MODEL_FILES) {
                val target = File(dir, name)
                try {
                    context.assets.open("asr/$name").use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) {
                    // assets 里没有（瘦身后的包不带模型）——改走下载，残缺复制先清掉。
                    target.delete()
                    toDownload.add(name)
                }
            }
            if (toDownload.isNotEmpty()) {
                // 第 64 批：用户令“不要仅wifi”——下载不限网络，WiFi/移动数据都能下。
                // 原先的 WiFi 限制（非 WiFi 抛错）已拿掉。
                downloading = true
                try {
                    for (name in toDownload) downloadModelFile(name, File(dir, name))
                } finally {
                    downloading = false
                }
            }
            for (name in MODEL_FILES) {
                val f = File(dir, name)
                val expected = MODEL_EXPECTED_SIZES[name] ?: continue
                if (!f.exists() || f.length() != expected) {
                    throw IllegalStateException(
                        "离线识别模型 $name 校验失败（期望 $expected 字节，实际 ${if (f.exists()) f.length().toString() else "缺失"}）"
                    )
                }
            }
            marker.writeText("ok")
            return dir
        }

        /**
         * 从 GitHub Release 下一个模型文件：先下到 .tmp，大小校验通过才改名到位。
         * 下载失败/大小不对都删掉临时文件和目标文件并抛错，不留残缺文件。
         */
        private fun downloadModelFile(name: String, target: File) {
            val url = MODEL_URLS[name]
                ?: throw IllegalStateException("未知模型文件 $name")
            val expected = MODEL_EXPECTED_SIZES[name]
                ?: throw IllegalStateException("未知模型文件 $name")
            val tmp = File(target.parentFile, "$name.tmp")
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000
                conn.instanceFollowRedirects = true
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    throw IOException("HTTP ${conn.responseCode}")
                }
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                if (tmp.length() != expected) {
                    throw IOException("大小不符：期望 $expected 字节，实际 ${tmp.length()} 字节")
                }
                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            } catch (e: Exception) {
                tmp.delete()
                target.delete()
                throw IOException("下载模型 $name 失败：${e.message}", e)
            }
        }

        /**
         * 设置页用：模型状态。
         * "ready" 已就位可用（filesDir 有 marker，或 APK 的 assets 里有全套可现拷）；
         * "downloading" 正在下载；
         * "missing" 未下载（首次使用语音时会自动下载，约 190MB）。
         * 第 64 批：用户令“不要仅wifi”，"no-wifi" 状态已删，下载不限网络。
         */
        fun modelStatus(context: Context): String {
            val dir = File(context.filesDir, "asr")
            if (File(dir, MODEL_MARKER).exists()) return "ready"
            if (downloading) return "downloading"
            if (assetsPresent(context)) return "ready"
            return "missing"
        }

        /** 自检页用：assets 里是不是已经放齐了四个模型文件（不保证内容完整/版本对，只查存在性）。 */
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
