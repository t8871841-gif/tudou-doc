package com.jarvis.native.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * 把 [OfflineAsr]（只管喂 PCM 吐文字）包成和原来 SystemAsr 一样形状的调用方式
 * （listenOnce / stop / isAvailable），这样 JarvisAgent 这边基本不用改调用代码，
 * 只需要把 import 从 SystemAsr 换成这个类。
 *
 * 这一层多做的事：开麦克风、读 PCM 喂给引擎、自己判断"安静多久算说完"
 * （本地识别没有网络那种自然的"服务端认为你说完了"信号，得靠调用方自己掐）。
 *
 * 安静判断阈值是沙盒里写的，没有真机调校过：简单能量阈值 + 固定安静时长，
 * 如果真机上出现"该停的时候不停"或"还没说完就被打断"，优先调这里的
 * `VOICE_RMS_THRESHOLD` / `SILENCE_MS` / `MAX_WAIT_FOR_SPEECH_MS` 三个常量。
 */
object OfflineAsrSession {
    private const val SAMPLE_RATE = OfflineAsr.SAMPLE_RATE // 16000，必须跟引擎声明的一致
    private const val CHUNK_MS = 100
    private const val CHUNK_BYTES = SAMPLE_RATE * 2 * CHUNK_MS / 1000 // 16bit=2字节/采样

    /** 简单能量阈值：16bit PCM 样本绝对值的平均数超过这个数就算"有人在说话"。 */
    private const val VOICE_RMS_THRESHOLD = 600
    /** 说过话之后，安静满这么久就认为这一轮说完了。 */
    private const val SILENCE_MS = 1_600L
    /** 从开始监听到现在，如果一直没检测到人声，等这么久就放弃（避免无限占着麦克风）。 */
    private const val MAX_WAIT_FOR_SPEECH_MS = 6_000L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var seq = 0

    @Volatile
    private var recordThread: Thread? = null

    @Volatile
    private var currentEngine: OfflineAsr? = null

    fun isAvailable(ctx: Context): Boolean = OfflineAsr.assetsPresent(ctx)

    fun listenOnce(
        ctx: Context,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        val mySeq = ++seq
        val appCtx = ctx.applicationContext
        if (ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("缺少录音权限")
            return
        }
        if (!isAvailable(appCtx)) {
            onError("离线识别模型文件缺失（assets/asr 下没放齐 4 个模型文件）")
            return
        }

        var gotAnyFinalOrError = false
        val engine = OfflineAsr(object : AsrEngine.Listener {
            override fun onReady() {
                if (mySeq != seq) return
                startCapture(mySeq, appCtx)
            }

            override fun onPartial(text: String) {
                if (mySeq != seq) return
                main.post { if (mySeq == seq) onPartial(text) }
            }

            override fun onFinal(text: String) {
                if (mySeq != seq || gotAnyFinalOrError) return
                gotAnyFinalOrError = true
                main.post {
                    if (mySeq != seq) return@post
                    if (text.isBlank()) onError("没听清") else onFinal(text)
                }
            }

            override fun onError(msg: String) {
                if (mySeq != seq || gotAnyFinalOrError) return
                gotAnyFinalOrError = true
                main.post { if (mySeq == seq) onError(msg) }
            }

            override fun onClosed() {}
        }, appCtx)
        currentEngine = engine
        engine.start(SAMPLE_RATE, 1)
    }

    /** 急停 / 打断 / 下一轮开始前调用：中止麦克风采集和识别引擎，不回调任何结果。 */
    fun stop() {
        seq++
        recordThread?.interrupt()
        recordThread = null
        currentEngine?.let { eng -> Thread { runCatching { eng.close() } }.start() }
        currentEngine = null
    }

    private fun startCapture(mySeq: Int, ctx: Context) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = if (minBuf <= 0) null else try {
            val bufSize = maxOf(minBuf, CHUNK_BYTES * 4)
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: Exception) {
            null
        }
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            // 录音初始化失败：让引擎收到 isLast=true 走一次空结果收尾，上层会报"没听清"
            // （不是最精确的错误文案，但这种情况真机上基本不会出现，不值得为此单开一条错误通道）。
            currentEngine?.sendAudio(ByteArray(0), true)
            return
        }
        val t = Thread({
            val buf = ByteArray(CHUNK_BYTES)
            var spokeAny = false
            var lastVoiceAt = System.currentTimeMillis()
            val startedAt = lastVoiceAt
            try {
                recorder.startRecording()
                while (mySeq == seq && !Thread.currentThread().isInterrupted) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val chunk = if (n == buf.size) buf else buf.copyOf(n)
                    val now = System.currentTimeMillis()
                    if (rms(chunk) > VOICE_RMS_THRESHOLD) {
                        spokeAny = true
                        lastVoiceAt = now
                    }
                    currentEngine?.sendAudio(chunk, false)
                    if (spokeAny && now - lastVoiceAt > SILENCE_MS) break
                    if (!spokeAny && now - startedAt > MAX_WAIT_FOR_SPEECH_MS) break
                }
            } catch (_: Exception) {
                // 采集异常直接走收尾逻辑，不单独报错——引擎那边 isLast=true 之后
                // 会在 onFinal/onError 里统一处理
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                try { recorder.release() } catch (_: Exception) {}
                if (mySeq == seq) currentEngine?.sendAudio(ByteArray(0), true)
            }
        }, "OfflineAsrCapture")
        t.isDaemon = true
        recordThread = t
        t.start()
    }

    private fun rms(pcm: ByteArray): Long {
        if (pcm.size < 2) return 0
        var sum = 0L
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += abs(sample)
            count++
            i += 2
        }
        return if (count == 0) 0 else sum / count
    }
}
