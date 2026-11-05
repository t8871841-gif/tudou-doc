package com.jarvis.app.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * 把 [OfflineAsr]（只管喂 PCM 吐文字）包成和原来 SystemAsr 一样形状的调用方式
 * （listenOnce / stop / isAvailable），这样 JarvisAgent 这边基本不用改调用代码，
 * 只需要把 import 从 SystemAsr 换成这个类。
 *
 * 这一层多做的事：开麦克风、读 PCM 喂给引擎、自己判断"安静多久算说完"
 * （本地识别没有网络那种自然的"服务端认为你说完了"信号，得靠调用方自己掐）。
 *
 * 结束判断的信号优先级（照 Andee AsrController / VoiceSegmenter）：
 *  1. 识别器自己的输出：[AsrEngine.Listener.onPartial] 文本变化才刷新静音时钟；
 *     sherpa 的端点信号（[OfflineAsr.isEndpointHit]）也是轮次结束的信号之一。
 *  2. 能量只做兜底：partial 长时间（[PARTIAL_STALL_MS]）完全不更新时才看能量——
 *     能量还响就暂缓掐断（识别器出字可能滞后），能量安静了才收尾。
 *  3. 自适应底噪 VAD（[VoiceSegmenter] 同款算法）只负责"能量还响不响"这个判断，
 *     不再直接掐轮次。固定阈值 600 的老做法已删：同一台手机换个房间表现完全不同，
 *     持续噪声下轮次永远等不到安静，轻声/停顿思考超 3 秒的长句会被拦腰截断。
 *
 * 计时一律用 [SystemClock.uptimeMillis]，不用挂钟（会被 NTP 对时、用户改时间拨动）。
 * 超时从点球调 listenOnce 的那一刻（[turnStartedAt]）起算，不是从开麦后起算。
 */
object OfflineAsrSession {
    private const val TAG = "OfflineAsrSession"
    private const val SAMPLE_RATE = OfflineAsr.SAMPLE_RATE // 16000，必须跟引擎声明的一致
    private const val CHUNK_MS = 100
    private const val CHUNK_BYTES = SAMPLE_RATE * 2 * CHUNK_MS / 1000 // 16bit=2字节/采样

    // ---- H1：自适应底噪 VAD，照 Andee VoiceSegmenter 的算法 ----
    /** 帧长 10ms @16kHz，与 VoiceSegmenter.FRAME 一致。 */
    private const val VAD_FRAME_SAMPLES = 160
    /** RMS 低于此一律算房间底噪（VoiceSegmenter.ABS_MIN）。 */
    private const val VAD_ABS_MIN = 350f
    private const val VAD_START_MULT = 3.5f // 开口阈值 max(floor*3.5, 350)
    private const val VAD_END_MULT = 2.0f // 结束阈值 max(floor*2.0, 350*0.7=245)
    /** 连续 8 帧（80ms）超线才算开口；连续 30 帧（300ms）安静才算结束。 */
    private const val VAD_START_FRAMES = 8
    private const val VAD_END_FRAMES = 30

    /** 说过话之后，安静满这么久就认为这一轮说完了（与 OfflineAsr.silenceMs 一致）。 */
    private const val SILENCE_MS = 3_000L
    /** C1：partial 超过这么久完全不更新，才咨询能量兜底（见采集循环里的注释）。 */
    private const val PARTIAL_STALL_MS = 10_000L
    /** M5：最长轮次 60 秒（Andee MAX_TURN_MS），从点球那一刻起算。 */
    private const val MAX_TURN_MS = 60_000L
    /** M5：空轮次起步宽限 4 秒（Andee SILENT_START_GRACE_MS），同样从点球起算。 */
    private const val SILENT_START_GRACE_MS = 4_000L
    /** C2：模型加载超时 20 秒。 */
    private const val MODEL_LOAD_TIMEOUT_MS = 20_000L
    /** C2：开麦缓存上限约 20 秒（200 个 100ms 块），满了丢最旧的。 */
    private const val MAX_BUFFERED_CHUNKS = 200

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var seq = 0

    @Volatile
    private var recordThread: Thread? = null

    @Volatile
    private var currentEngine: OfflineAsr? = null

    /** 批一新增：给 HUD 舞台画声波用的实时电平 0..1，缩放系数是估的，没有真机校过响度。 */
    @Volatile
    private var lastLevel01 = 0f

    fun currentLevel(): Float = lastLevel01

    // ---- 当前轮次的状态：listenOnce 开头复位；跨线程读的加 @Volatile ----
    /** M5：这一轮开始的时刻——点球调 listenOnce 的那一刻，不是开麦后。 */
    @Volatile
    private var turnStartedAt = 0L
    /** C1：识别器最后一次报出*新* partial 文本的时刻（uptime 时钟）。 */
    @Volatile
    private var lastPartialAt = 0L
    @Volatile
    private var lastPartialText = ""
    @Volatile
    private var heardAnything = false
    @Volatile
    private var engineReady = false
    /** M4：本轮已送给识别器的块数（空轮次日志用；空轮次仍送块，死麦克风不送）。 */
    @Volatile
    private var chunksSent = 0
    @Volatile
    private var loadTimeoutRunnable: Runnable? = null

    // ---- H3：收音侧音频焦点（内联实现，不另建共享文件）----
    /** 当前轮次的焦点句柄（stop() 取走它去释放；每轮一个对象，不会错放新轮次的焦点）。 */
    @Volatile
    private var currentFocus: FocusHandle? = null

    /** 一轮焦点就是一个 AudioFocusRequest + 一次 abandon；release() 幂等。 */
    private class FocusHandle(val am: AudioManager, val request: AudioFocusRequest) {
        private val released = AtomicBoolean(false)
        fun release() {
            if (!released.compareAndSet(false, true)) return
            runCatching { am.abandonAudioFocusRequest(request) }
        }
    }

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

        // M5：超时从点球这一刻起算。
        turnStartedAt = SystemClock.uptimeMillis()
        lastPartialAt = turnStartedAt
        lastPartialText = ""
        heardAnything = false
        engineReady = false
        chunksSent = 0

        var gotAnyFinalOrError = false

        // C2：模型加载加超时——20 秒还没 onReady 就报“识别加载超时”，而不是静默无反应。
        val loadTimeout = Runnable {
            if (mySeq != seq || gotAnyFinalOrError || engineReady) return@Runnable
            Log.w(TAG, "model load timed out after ${MODEL_LOAD_TIMEOUT_MS}ms")
            gotAnyFinalOrError = true
            main.post { if (mySeq == seq) onError("识别加载超时") }
            teardownTurn(currentEngine)
        }
        loadTimeoutRunnable = loadTimeout
        main.postDelayed(loadTimeout, MODEL_LOAD_TIMEOUT_MS)

        // H3：收音前先向系统申请音频焦点；来电丢焦点时直接 stop()。
        val focusHandle = acquireFocus(appCtx, mySeq)
        currentFocus = focusHandle

        val engine = OfflineAsr(object : AsrEngine.Listener {
            override fun onReady() {
                if (mySeq != seq || gotAnyFinalOrError) return
                // C1：时钟从引擎能听见才开始走（Andee：lastSpeechAt 设在 onReady）。
                lastPartialAt = SystemClock.uptimeMillis()
                engineReady = true
                main.removeCallbacks(loadTimeout)
                Log.i(TAG, "engine ready after ${SystemClock.uptimeMillis() - turnStartedAt}ms")
            }

            override fun onPartial(text: String) {
                if (mySeq != seq) return
                if (text.isEmpty()) return
                heardAnything = true
                // C1：只有文本变化才刷新静音时钟；识别器重发同一假设不算说话。
                if (text != lastPartialText) {
                    lastPartialText = text
                    lastPartialAt = SystemClock.uptimeMillis()
                }
                main.post { if (mySeq == seq) onPartial(text) }
            }

            override fun onFinal(text: String) {
                if (mySeq != seq || gotAnyFinalOrError) return
                gotAnyFinalOrError = true
                main.removeCallbacks(loadTimeout)
                // H2：空轮次不是错误——原样走 onFinal("")（只清字幕、回 idle），不要当成错误报出去。
                if (text.isBlank()) Log.i(TAG, "empty turn (chunksSent=$chunksSent)")
                main.post {
                    if (mySeq != seq) return@post
                    onFinal(text)
                }
            }

            override fun onError(msg: String) {
                if (mySeq != seq || gotAnyFinalOrError) return
                gotAnyFinalOrError = true
                main.removeCallbacks(loadTimeout)
                main.post { if (mySeq == seq) onError(msg) }
            }

            override fun onClosed() {}
        }, appCtx)
        currentEngine = engine
        // C2：先开麦采集并缓存，模型就绪后再把缓存刷给识别器；开麦不等模型就绪。
        val micError = startCapture(mySeq, engine, focusHandle)
        if (micError != null) {
            // M4：录音器建失败是麦克风故障，不是“没说话”——记日志、报 distinct 的错误。
            gotAnyFinalOrError = true
            main.removeCallbacks(loadTimeout)
            main.post { if (mySeq == seq) onError("麦克风打开失败") }
            teardownTurn(engine)
            return
        }
        engine.start(SAMPLE_RATE, 1)
    }

    /** 急停 / 打断 / 下一轮开始前调用：中止麦克风采集和识别引擎，不回调任何结果。 */
    fun stop() {
        seq++
        recordThread?.interrupt()
        recordThread = null
        val eng = currentEngine
        currentEngine = null
        main.removeCallbacks(loadTimeoutRunnable)
        loadTimeoutRunnable = null
        val focus = currentFocus
        currentFocus = null
        finishTurnAsync(eng, focus, bySilence = false)
    }

    /**
     * C3：收尾顺序照 Andee AsrController.stopImpl——先发 isLast、再调
     * waitFinished(finalWaitMs) 等最终结果、再 close()、最后放音频焦点。
     * 在后台线程跑，不阻塞调用方（stop() 常在主线程被调）。
     * 多次调用是安全的：isLast/final 有 finalSent 守卫，close() 有 closeRequested
     * 守卫，焦点释放有 FocusHandle.released 守卫。
     */
    private fun finishTurnAsync(engine: OfflineAsr?, focus: FocusHandle?, bySilence: Boolean) {
        if (engine == null) {
            focus?.release()
            return
        }
        Thread({
            runCatching { engine.endOfTurn(bySilence) }
            runCatching { engine.sendAudio(ByteArray(0), true) }
                .onFailure { Log.w(TAG, "isLast failed", it) }
            runCatching { engine.waitFinished(engine.finalWaitMs) }
            runCatching { engine.close() }
            // final 到了（或等超时了）再释放焦点，别让别的 App 提前恢复盖住尾音。
            focus?.release()
        }, "OfflineAsrFinish").apply { isDaemon = true; start() }
    }

    /**
     * 轮内收尾（模型加载超时 / 开麦失败）：不碰 seq（调用方已经或即将把错误
     * 回调出去，seq 仍属于本轮），只停采集、走 C3 收尾、放焦点、撤超时看门狗。
     */
    private fun teardownTurn(engine: OfflineAsr?) {
        main.removeCallbacks(loadTimeoutRunnable)
        loadTimeoutRunnable = null
        recordThread?.interrupt()
        recordThread = null
        val focus = currentFocus
        currentFocus = null
        finishTurnAsync(engine, focus, bySilence = false)
    }

    /**
     * H3：收音前向系统申请音频焦点（USAGE_ASSISTANT + GAIN_TRANSIENT）。
     * 来电丢焦点时直接 stop()，不要继续录一通电话。
     * 申请被拒也继续——拒了多半是在通话中，整轮失败比盖过通话更糟。
     * AudioFocusRequest 要 API 26+，低版本直接跳过（本机 Android 16，不影响）。
     */
    private fun acquireFocus(appCtx: Context, mySeq: Int): FocusHandle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.i(TAG, "audio focus skipped: API < 26")
            return null
        }
        val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) {
                Log.i(TAG, "audio focus lost ($change) — stopping turn")
                if (mySeq == seq) stop()
            }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(listener)
            .setWillPauseWhenDucked(true)
            .build()
        val granted = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            Log.w(TAG, "audio focus request denied — continuing anyway")
            return null
        }
        return FocusHandle(am, request)
    }

    /**
     * C2：开麦不等模型就绪。采集线程一启动就读 PCM：模型未就绪时先缓存在
     * pending 队列里（上限约 20 秒，满了丢最旧的），就绪后由采集线程自己按
     * 顺序刷给识别器（单线程发送，保序）。
     * 返回 null 表示开麦成功，否则为失败原因（M4：录音器建失败要记日志、
     * 与“没说话”区分开；不要再发个空 isLast 让上层当成错误了事）。
     */
    private fun startCapture(
        mySeq: Int,
        engine: OfflineAsr,
        focus: FocusHandle?
    ): String? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.w(TAG, "capture start failed: getMinBufferSize=$minBuf")
            return "getMinBufferSize=$minBuf"
        }
        val recorder = try {
            val bufSize = maxOf(minBuf, CHUNK_BYTES * 4)
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: Exception) {
            Log.w(TAG, "capture start failed: AudioRecord ctor", e)
            return "AudioRecord ctor: ${e.message}"
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "capture start failed: recorder state=${recorder.state}")
            runCatching { recorder.release() }
            return "recorder state=${recorder.state}"
        }
        // M2：不要主动创建并启用 AEC/NS/AGC——Andee 实测会 stall HAL
        //（只挂 NS 首个 read() 就要 10.2 秒）；VOICE_RECOGNITION 自带平台降噪已够用。
        val t = Thread({
            val buf = ByteArray(CHUNK_BYTES)
            val pending = ArrayDeque<ByteArray>()
            var droppedBuffered = 0
            var captureError: String? = null
            var endedBySilence = false
            // H1：VAD 状态（只在采集线程里读写）。
            var vadSpeech = false
            var loudRun = 0
            var quietRun = 0
            var floor = -1f
            var vadQuietSince = turnStartedAt
            var stallDeferLogged = false
            try {
                recorder.startRecording()
                while (mySeq == seq && !Thread.currentThread().isInterrupted) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) {
                        // M4：read() <= 0 是麦克风故障，不是“没说话”——记日志、带原因收尾。
                        captureError = "read()=$n"
                        Log.w(TAG, "capture read returned $n — ending turn (chunksSent=$chunksSent)")
                        break
                    }
                    val chunk = buf.copyOf(n)
                    val now = SystemClock.uptimeMillis()
                    // H1：每 10ms 一帧算真 RMS，走自适应底噪 + 迟滞 + 去抖。
                    var maxRms = 0f
                    var off = 0
                    while (off + VAD_FRAME_SAMPLES * 2 <= chunk.size) {
                        val rms = frameRms(chunk, off)
                        if (rms > maxRms) maxRms = rms
                        if (floor < 0f) floor = rms
                        val startAt = maxOf(floor * VAD_START_MULT, VAD_ABS_MIN)
                        val endAt = maxOf(floor * VAD_END_MULT, VAD_ABS_MIN * 0.7f)
                        if (!vadSpeech) {
                            // 底噪只在空闲时缓慢学习：向下学得快、向上学得慢。
                            floor = if (rms < floor) floor * 0.9f + rms * 0.1f
                            else floor * 0.995f + rms * 0.005f
                            if (rms > startAt) {
                                if (++loudRun >= VAD_START_FRAMES) {
                                    vadSpeech = true
                                    quietRun = 0
                                }
                            } else loudRun = 0
                        } else {
                            if (rms < endAt) {
                                if (++quietRun >= VAD_END_FRAMES) {
                                    vadSpeech = false
                                    loudRun = 0
                                    quietRun = 0
                                }
                            } else quietRun = 0
                        }
                        if (rms >= endAt) vadQuietSince = now
                        off += VAD_FRAME_SAMPLES * 2
                    }
                    lastLevel01 = (maxRms / 4000f).coerceIn(0f, 1f)

                    // C2：先缓存，模型就绪后由采集线程按顺序刷出（单线程发送，保序）。
                    pending.addLast(chunk)
                    while (pending.size > MAX_BUFFERED_CHUNKS) {
                        pending.removeFirst()
                        droppedBuffered++
                    }
                    if (engineReady) {
                        while (true) {
                            val c = pending.removeFirstOrNull() ?: break
                            engine.sendAudio(c, false)
                            chunksSent++
                        }
                    }

                    // ---- 轮次结束判断（M1：一律 uptime 时钟；M5：从 turnStartedAt 起算）----
                    if (now - turnStartedAt >= MAX_TURN_MS) {
                        Log.i(TAG, "turn hit ${MAX_TURN_MS}ms ceiling — auto-stop")
                        endedBySilence = true
                        break
                    }
                    // 时钟等引擎能听见才走（Andee：Nothing counts until the engine can hear）；
                    // 但上面的 MAX_TURN 上限不豁免。
                    if (!engineReady) continue
                    // M3：sherpa 端点信号是轮次结束的信号之一（规则值见 OfflineAsr，与 SILENCE_MS 对齐）。
                    if (engine.isEndpointHit()) {
                        Log.i(TAG, "endpoint reached — auto-stop")
                        endedBySilence = true
                        break
                    }
                    if (!heardAnything && now - turnStartedAt < SILENT_START_GRACE_MS) continue
                    // C1：静音时钟只由 partial 文本变化喂给（lastPartialAt）。
                    val quiet = now - lastPartialAt
                    if (quiet < SILENCE_MS) continue
                    val energyQuietFor = now - vadQuietSince
                    if (energyQuietFor >= SILENCE_MS) {
                        Log.i(TAG, "silence ${quiet}ms (limit=$SILENCE_MS) — auto-stop")
                        endedBySilence = true
                        break
                    }
                    // partial 已停 SILENCE_MS，但能量显示最近还在出声：识别器出字可能
                    // 滞后（长词、慢解码），先不掐断；partial 再有 PARTIAL_STALL_MS
                    // 完全不更新，就按能量兜底收尾——能量兜底只在这种情况下才判。
                    if (quiet >= PARTIAL_STALL_MS) {
                        Log.i(
                            TAG,
                            "partial stalled ${quiet}ms, VAD quiet=${energyQuietFor}ms " +
                                "— auto-stop (energy fallback)"
                        )
                        endedBySilence = true
                        break
                    }
                    if (!stallDeferLogged) {
                        stallDeferLogged = true
                        Log.i(TAG, "silence ${quiet}ms but VAD still loud — deferring stop (partial may be lagging)")
                    }
                    // 能量兜底窗口内：继续等 partial 更新，或等到 PARTIAL_STALL_MS。
                }
            } catch (t: Throwable) {
                // M4：采集异常记日志、带原因，不要静默吞掉。
                captureError = "${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, "capture failed (chunksSent=$chunksSent)", t)
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                try { recorder.release() } catch (_: Exception) {}
                lastLevel01 = 0f
                if (droppedBuffered > 0) {
                    Log.w(TAG, "dropped $droppedBuffered buffered chunks (model not ready in time)")
                }
                if (captureError != null) {
                    Log.w(TAG, "capture ended with error: $captureError (chunksSent=$chunksSent)")
                }
                // 退出前把还没刷出去的缓存补发给识别器，再走 C3 收尾。
                if (engineReady) {
                    while (true) {
                        val c = pending.removeFirstOrNull() ?: break
                        runCatching { engine.sendAudio(c, false) }
                        chunksSent++
                    }
                }
                finishTurnAsync(engine, focus, bySilence = endedBySilence)
            }
        }, "OfflineAsrCapture")
        t.isDaemon = true
        recordThread = t
        t.start()
        return null
    }

    /** H1：真均方根（原来那个叫 rms 的其实是样本绝对值平均数，不是均方根）。 */
    private fun frameRms(pcm: ByteArray, offset: Int): Float {
        var sum = 0.0
        var i = 0
        while (i < VAD_FRAME_SAMPLES) {
            val lo = pcm[offset + i * 2].toInt() and 0xFF
            val hi = pcm[offset + i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += sample.toDouble() * sample
            i++
        }
        return sqrt(sum / VAD_FRAME_SAMPLES).toFloat()
    }
}
