package com.jarvis.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jarvis.app.device.CallState
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * 微软 Edge 神经网络语音（云扬）——移植自参考实现（Flutter 版 Jarvis 真机验证过的协议）。
 *
 * v2 协议（2026-10-26 更新，旧 v1 写法握手已返回 200 不升级、彻底没声）：
 *  - 端点迁回 speech.platform.bing.com，路径为 /consumer/speech/synthesize/readaloud/edge/v1，
 *    参数改 TrustedClientToken（不再是 Ocp-Apim-Subscription-Key）；
 *  - Sec-MS-GEC-Version 前缀由 1- 改为 2-，GEC 值由纯 SHA-256 改为 CS64-SHA256 两段式
 *    （算法见 EdgeGec，移植自 edge-tts 7.3.1 的 drm.py；新写法已实测握手返回 101）；
 *  - 握手成功(101)后服务端会带 `Connection: close`，OkHttp 等标准 WebSocket 客户端可能直接
 *    判定握手失败，所以这里跟参考实现一样手写 TLS socket + 手搓 WebSocket 帧，不用 OkHttp；
 *  - 拿到的是 MP3，用 MediaCodec 解成 PCM 后用 AudioTrack 流式播放（而不是先落盘成 mp3
 *    文件再丢给 MediaPlayer）——边解码边播，首字延迟更低，也不需要写临时文件。
 *
 * 对外的调用方式（speak/stop/isSpeaking）跟第一版保持一致，JarvisAgent 那边不用改。
 */
object EdgeTts {
    private const val TAG = "EdgeTts"
    private const val HOST = "speech.platform.bing.com"

    /** 开源 edge-tts 项目公开的客户端令牌，不是任何人的私密凭据。 */
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM_MAJOR = "143"
    private const val DEFAULT_VOICE = "zh-CN-YunyangNeural" // "云扬"，需求文档点名的那个声音
    private const val DEFAULT_RATE_PCT = -2 // 参考实现调过的静息语速，-50%~+100% 这个刻度上的 -2%
    private const val DEFAULT_PITCH_HZ = 0
    private const val SAMPLE_RATE = 24_000

    private val HEADER_END = "\r\n\r\n".toByteArray(Charsets.UTF_8)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "EdgeTts").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    // M8 预取：下一句的取音/解码在上一句还在写 AudioTrack 时就跑在另一条线程上，
    // 与主合成线程（io）分开，否则上一句写完前下一句根本没机会开始。
    private val prefetchIo = Executors.newSingleThreadExecutor { r -> Thread(r, "EdgeTts-Prefetch").apply { isDaemon = true } }

    @Volatile private var mySeq = 0
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var prefetchSocket: SSLSocket? = null
    @Volatile private var track: AudioTrack? = null
    // 语音诊断批：最近一次 writePcmFully 失败的原因（失败时才写，成功不动它）。
    // 调用方在已有失败记录旁补一行审计，不改任何 reason 字符串；下面那个
    // stoppedHere 判据靠 reason 精确比对，必须原样。
    @Volatile private var lastWriteDiag = ""

    // H4 失速看门狗（对齐 Andee TtsController.armStallWatchdog）：每 5 秒查一次，
    // 以 lastProgressAt 为准，20 秒无 PCM 进展即取消本次合成、停播、回 idle 并记可见报错。
    // lastProgressAt 每次收到二进制帧 / 解出 PCM / 写出音频时刷新（主线程与预取线程都会刷）。
    // 计时起点不在 arm 时：握手成功（101）时 requireHandshake101 里重置一次，建连/握手/
    // GEC 时间不计入 20 秒；会话建立失败回落时 runSynthesis 里也重置一次；流式解码器
    // （MediaCodec）创建成功后同样重置一次。
    private const val STALL_CHECK_MS = 5_000L
    private const val STALL_AFTER_MS = 20_000L
    // 预取等待上限：等下一句预取最多 5 秒，超时放弃预取、直接走当前句流式现取现播，不死等。
    private const val PREFETCH_WAIT_TIMEOUT_MS = 5_000L
    // 语音第 2 批：焦点暂失后等 GAIN 的上限，与预取超时一致（5 秒）。
    private const val FOCUS_GAIN_TIMEOUT_MS = 5_000L
    // 流式解码器已死的失败原因前缀（见 DecoderDeadException）：句循环凭它认出
    // “解码器错”并走整段回落；与握手失败的 IllegalStateException 区分开。
    private const val DECODER_DEAD_REASON = "流式解码器已释放"
    // 握手 429 退避：下次握手前等待至此时刻（elapsedRealtime），避免 429 后立刻重握手再被限流。
    private const val HANDSHAKE_429_BACKOFF_MS = 2_000L
    @Volatile private var handshakeRetryAfterMs = 0L
    @Volatile private var lastProgressAt = 0L
    @Volatile private var stallFired = false
    private var stallWatchdog: Runnable? = null
    private var stallWatchdogSeq = -1

    // H3 播报侧音频焦点（内联实现，不新建共享文件；对齐 Andee AudioFocusGate 的
    // USAGE_ASSISTANT + AUDIOFOCUS_GAIN_TRANSIENT）：播报前申请，播完/取消/失速时释放；
    // 丢焦点（来电/别的 App 抢焦点）时停播。
    @Volatile private var focusRequest: AudioFocusRequest? = null
    @Volatile private var focusManager: AudioManager? = null
    @Volatile private var focusHeld = false
    @Volatile private var focusSeq = -1
    // 焦点暂失时暂停的标记：LOSS_TRANSIENT / CAN_DUCK 只暂停不断播，GAIN 到来时恢复；
    // 只有 LOSS 才 stop()。stop() / 新一轮 acquire 时清零。
    @Volatile private var focusPaused = false

    // L8 Earcon（对齐 Andee audio/Earcon.kt）：开麦上行五度、思考下行四度，
    // 峰值 0.20，尾部留 250ms 静默防抖（quietUntil），150ms 内不重复响。
    enum class EarconKind { LISTENING, THINKING }
    private const val EARCON_RATE = 24_000
    private const val EARCON_PEAK = 0.20
    private const val EARCON_TAIL_MS = 250L
    private const val EARCON_MIN_GAP_MS = 150L
    @Volatile private var earconQuietUntil = 0L
    @Volatile private var earconLastPlayAt = 0L

    /**
     * onSentenceFailed：某句播报失败时回调一次（只报首个失败句，与 abortedAt 一致），
     * 供 TtsRouter 从失败句续播。failedIndex 从 0 计，与 runSynthesis 内 sentences.withIndex()
     * 的下标一致；stoppedHere=true 表示播到这句就断了（该句及之后都没播），false 表示
     * 这句被跳过、后面的句子已播完（只缺这一句）。默认空，现有调用不用改。
     */
    fun speak(
        ctx: Context, text: String, voice: String = DEFAULT_VOICE,
        ratePct: Int = DEFAULT_RATE_PCT, pitchHz: Int = DEFAULT_PITCH_HZ,
        onStart: () -> Unit, onDone: (Boolean) -> Unit,
        onSentence: ((String) -> Unit)? = null,
        onSentenceFailed: ((failedIndex: Int, reason: String, stoppedHere: Boolean) -> Unit)? = null
    ) {
        attachCallState(ctx)
        stop()
        val seq = ++mySeq
        val clean = cleanText(text)
        if (clean.isEmpty()) { onDone(false); return }
        io.execute { runSynthesis(ctx, seq, clean, voice, ratePct, pitchHz, onStart, onDone, onSentence, onSentenceFailed) }
    }

    /** 急停/打断：关掉在飞的 socket（唤醒读写阻塞立即抛异常）和播放轨道；在飞的 runSynthesis 会在 finally 里回调 onDone(false)（L4），调用方状态机总能收敛。 */
    fun stop() {
        mySeq++
        focusPaused = false
        runCatching { socket?.close() }
        socket = null
        runCatching { prefetchSocket?.close() }
        prefetchSocket = null
        releaseAudioFocus()
        val t = track
        track = null
        if (t != null) {
            runCatching { t.pause(); t.flush(); t.stop() }
            runCatching { t.release() }
        }
    }

    fun isSpeaking(): Boolean = track != null

    private fun cancelled(seq: Int) = seq != mySeq

    // ---------- H3 音频焦点（内联） ----------

    // 通话接线（移植自 Andee device/CallState 的停播规则）：来电/通话中时停播
    // 并释放音频焦点（stop() 内已释放）；挂断后不自动续播。监听只注册一次；
    // CallState.start() 在无权限时只记日志、不抛异常，这里不额外处理权限缺失。
    // 注意：这里不做 speak() 入口的通话门——speak() 被拒会调 onDone(false)，
    // TtsRouter 在 auto 档会把它当 Edge 失败、兜底到系统 TTS 反而在通话中出声；
    // 新开播报的总门在 TtsRouter.speak 入口（onDone(true) 吞掉，不重试不兜底），
    // JarvisAgent 的 beginListening 门是第二道。
    @Volatile
    private var callListenerAttached = false

    fun attachCallState(ctx: Context) {
        if (callListenerAttached) return
        callListenerAttached = true
        CallState.start(ctx.applicationContext)
        CallState.addListener { inCall ->
            if (inCall && isSpeaking()) {
                Log.i(TAG, "call started, stopping playback and releasing audio focus")
                stop()
            }
        }
    }

    /** 播报前申请焦点；系统拒绝也继续播（来电中直接哑火比盖过铃声更糟，沿用 Andee 的做法）。 */
    fun acquireAudioFocus(ctx: Context, seq: Int = mySeq): Boolean {
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                // 焦点分级处理（只认当前这一轮，旧轮的回调不碰新轮）：
                // LOSS 才停播；LOSS_TRANSIENT / CAN_DUCK 只暂停不断播，GAIN 到来时恢复——
                // 一条通知音引发的短暂 duck 不再掐断整段播报；setWillPauseWhenDucked(true)
                // 与此一致（duck 时暂停，而非停播）。
                if (focusSeq == mySeq) {
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            Log.i(TAG, "audio focus lost ($change), stopping playback")
                            focusHeld = false
                            focusPaused = false
                            stop()
                            // 系统兜底路径也在播时同样停掉（EdgeTts.stop() 只停 Edge）。
                            SystemTts.stop()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            Log.i(TAG, "audio focus transient loss ($change), pausing playback, will resume on gain")
                            focusPaused = true
                            runCatching { track?.pause() }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            if (focusPaused) {
                                focusPaused = false
                                Log.i(TAG, "audio focus gained, resuming playback")
                                runCatching { track?.play() }
                            }
                        }
                    }
                }
            }
            .setWillPauseWhenDucked(true)
            .build()
        val ok = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (ok) {
            focusManager = am
            focusRequest = req
            focusHeld = true
            focusSeq = seq
        } else {
            Log.w(TAG, "audio focus request denied, speaking anyway")
        }
        return ok
    }

    /** 播完/取消/失速时释放；可重复调。 */
    fun releaseAudioFocus() {
        val req = focusRequest
        val am = focusManager
        focusRequest = null
        focusManager = null
        if (!focusHeld) return
        focusHeld = false
        if (req != null && am != null) runCatching { am.abandonAudioFocusRequest(req) }
    }

    // ---------- H4 失速看门狗 ----------

    private fun markProgress() {
        lastProgressAt = SystemClock.elapsedRealtime()
    }

    private fun armStallWatchdog(ctx: Context, seq: Int) {
        disarmStallWatchdog()
        stallFired = false
        stallWatchdogSeq = seq
        markProgress()
        val r = object : Runnable {
            override fun run() {
                if (seq != mySeq || seq != stallWatchdogSeq) return
                if (cancelled(seq)) return
                if (SystemClock.elapsedRealtime() - lastProgressAt < STALL_AFTER_MS) {
                    main.postDelayed(this, STALL_CHECK_MS)
                    return
                }
                // 20 秒无进展：取消本次合成、停播、记可见报错；stop() 会让 runSynthesis
                // 的循环看到 cancelled 而收尾，finally 里按 stallFired 记审计并报 onDone(false)。
                stallWatchdog = null
                stallFired = true
                Log.e(TAG, "edge tts stall: no progress for ${STALL_AFTER_MS}ms, cancelling synthesis")
                com.jarvis.app.AuditLog.append(ctx, "语音播报", "Edge 播报失速已停播", "20 秒无音频进展")
                stop()
            }
        }
        stallWatchdog = r
        main.postDelayed(r, STALL_CHECK_MS)
    }

    private fun disarmStallWatchdog() {
        stallWatchdog?.let { main.removeCallbacks(it) }
        stallWatchdog = null
    }

    // ---------- L8 Earcon ----------

    /** 开麦上行 / 思考下行提示音；峰值 0.20，尾部留 250ms 静默防抖。 */
    fun playEarcon(kind: EarconKind) {
        val now = SystemClock.uptimeMillis()
        if (now - earconLastPlayAt < EARCON_MIN_GAP_MS) return
        earconLastPlayAt = now
        val pcm = when (kind) {
            EarconKind.LISTENING -> renderEarcon(listOf(880.0 to 420, 1318.5 to 470), listOf(0, 85), listOf(1.0, 0.9))
            EarconKind.THINKING -> renderEarcon(listOf(587.33 to 460, 440.0 to 560), listOf(0, 110), listOf(0.55, 0.5))
        }
        val ms = pcm.size.toLong() / 2 * 1000 / EARCON_RATE
        earconQuietUntil = now + ms + EARCON_TAIL_MS
        io.execute {
            runCatching {
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(EARCON_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                if (t.write(pcm, 0, pcm.size) > 0) {
                    t.play()
                    try { Thread.sleep(ms + 80) } catch (_: InterruptedException) { }
                    runCatching { t.stop() }
                }
                t.release()
            }
        }
    }

    fun isEarconSounding(): Boolean = SystemClock.uptimeMillis() < earconQuietUntil

    /** 两段正弦叠加、快起音 + 指数衰减（Andee Earcon 的简化版：峰值归一到 0.20）。 */
    private fun renderEarcon(notes: List<Pair<Double, Int>>, atMs: List<Int>, gains: List<Double>): ByteArray {
        val totalMs = notes.indices.maxOf { atMs[it] + notes[it].second }
        val total = EARCON_RATE * totalMs / 1000
        val buf = DoubleArray(total)
        val attack = EARCON_RATE * 7 / 1000
        for (k in notes.indices) {
            val hz = notes[k].first
            val len = EARCON_RATE * notes[k].second / 1000
            val start = EARCON_RATE * atMs[k] / 1000
            val tau = len / 4.5
            for (i in 0 until len) {
                val at = start + i
                if (at >= total) break
                val t = i.toDouble() / EARCON_RATE
                val decay = exp(-i / tau)
                val rise = if (i < attack) 0.5 - 0.5 * cos(PI * i / attack) else 1.0
                var s = sin(2 * PI * hz * t)
                s += 0.5 * sin(2 * PI * hz * 1.004 * t)
                s += 0.12 * sin(4 * PI * hz * t) * exp(-i / (tau * 0.4))
                buf[at] += s * rise * decay * gains[k]
            }
        }
        var peak = 0.0
        for (v in buf) if (v > peak) peak = v else if (-v > peak) peak = -v
        val scale = if (peak > 0) EARCON_PEAK * Short.MAX_VALUE / peak else 0.0
        val out = ByteArray(total * 2)
        for (i in 0 until total) {
            val v = (buf[i] * scale).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    // M12：一段回复只建一次 AudioTrack（runSynthesis 内一个 track 用到底，不每句新建）；
    // M4 会话复用仍在：多句复用同一 EdgeSession，建会话失败或会话中途失败再逐句回落。
    private fun runSynthesis(ctx: Context, seq: Int, text: String, voice: String, ratePct: Int, pitchHz: Int, onStart: () -> Unit, onDone: (Boolean) -> Unit, onSentence: ((String) -> Unit)? = null, onSentenceFailed: ((failedIndex: Int, reason: String, stoppedHere: Boolean) -> Unit)? = null) {
        val sentences = split(text)
        if (sentences.isEmpty()) { main.post { onDone(false) }; return }
        // H3：播报前申请音频焦点；H4：失速看门狗在合成开始时武装。
        acquireAudioFocus(ctx, seq)
        armStallWatchdog(ctx, seq)
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096) * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        var started = false
        var sawAny = false
        // 2026-10-24 语音播不完整修复：AudioTrack.write() 只保证数据进了缓冲区，
        // 不保证已经播出；旧代码写完最后一句立刻 stop()，会把缓冲区里还没播的
        // 尾巴（约一个缓冲区，0.3~1.3 秒）直接丢掉——每句回复都被截尾。
        // 这里累计实际写入的帧数，播完后先等放音头追上再 stop。
        var totalFrames = 0L
        var abortedAt: Int? = null
        var firstFailureReason: String? = null
        var session: EdgeSession? = null
        var prefetchFuture: Future<PrefetchResult>? = null
        var prefetchIndex = -1
        // 流式解码器已确认死在这台机上（第一次喂就报 Released）：之后各句不再往它
        // 上撞，直接走整段回落；预取也不再发起（它走同一条流式路，必失败）。
        var streamingDecoderDead = false
        // M9：onStart 早发——会话打开（或确定走逐句回落）时就发，不等首句取音+解码+首段写入。
        val fireStartOnce: () -> Unit = {
            if (!started) {
                started = true
                main.post { if (!cancelled(seq)) onStart() }
            }
        }
        // M10：中间句失败当时就记一次可见报错（带原因），不只在 finally 留一条审计；
        // abortedAt/firstFailureReason 让 finally 的 onDone(false) 告诉调用方这段缺了一句。
        // 失败句下标从 0 计（与 sentences.withIndex() 一致）；stoppedHere=true 表示播到
        // 这句就断了（该句及之后都没播），false 表示这句被跳过、后面的句子已播完。
        // 只回调首个失败（abortedAt == null 时），供 TtsRouter 从失败句续播。
        val recordFailure: (Int, String, Boolean) -> Unit = { i, reason, stoppedHere ->
            Log.e(TAG, "edge tts sentence $i failed: $reason")
            if (abortedAt == null) {
                abortedAt = i
                firstFailureReason = reason
                onSentenceFailed?.invoke(i, reason, stoppedHere)
            }
            com.jarvis.app.AuditLog.append(ctx, "语音播报", "Edge 第 ${i + 1}/${sentences.size} 句播报失败", reason)
        }
        try {
            // 语音第 2 批：建轨后立即验 state——Flyme 上 Builder 可能悄悄建成
            // 一个未初始化的轨，往里写必报 AudioTrack.write 失败；未初始化就
            // 直接记失败走现有兜底（return 会进 finally：关轨、释焦点、发 onDone(false)）。
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                recordFailure(0, "AudioTrack 未初始化（state=${t.state}）", true)
                return
            }
            // play() 无返回值（Unit）；验它是否真启动：抛异常或 playState 未进
            // PLAYING 都按本句失败处理。
            try {
                t.play()
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack.play threw", e)
                recordFailure(0, "AudioTrack 播放失败", true)
                return
            }
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                recordFailure(0, "AudioTrack 播放未启动（playState=${t.playState}）", true)
                return
            }
            try {
                session = openSession()
            } catch (e: Exception) {
                Log.w(TAG, "edge tts session open failed, fallback to per-sentence", e)
                // 握手失败的状态码已在异常消息里（见 requireHandshake101），记一条审计便于回看；
                // 建连/握手耗时不计入看门狗：会话建立失败回落时也重置一次计时起点。
                if ((e.message ?: "").contains("握手失败")) {
                    com.jarvis.app.AuditLog.append(ctx, "语音播报", "Edge 会话握手失败", e.message ?: "握手失败")
                }
                markProgress()
                session = null
            }
            fireStartOnce()
            for ((i, sentence) in sentences.withIndex()) {
                if (cancelled(seq)) break
                // M8 预取：下一句的取音/解码在上一句还在写 AudioTrack 时就跑在 prefetchIo 上，
                // 不等上一句写完才开始；预取走独立逐句连接，不抢当前句的共享会话。
                if (i + 1 < sentences.size && prefetchFuture == null && !streamingDecoderDead) {
                    val nextSentence = sentences[i + 1]
                    prefetchIndex = i + 1
                    prefetchFuture = prefetchIo.submit(Callable<PrefetchResult> {
                        if (cancelled(seq)) return@Callable PrefetchResult(emptyList(), false, "cancelled")
                        val collected = ArrayList<ByteArray>()
                        val r = streamSentence(seq, nextSentence, voice, ratePct, pitchHz, null, usePrefetchSocket = true) { pcm ->
                            collected.add(pcm)
                            true
                        }
                        if (cancelled(seq)) PrefetchResult(emptyList(), false, "cancelled")
                        else if (!r.ok) PrefetchResult(emptyList(), false, r.reason ?: "prefetch failed")
                        else PrefetchResult(collected, true, null)
                    })
                }
                // 1) 预取已到：直接写已解好的 PCM，不再等取音。
                var sentenceDone = false
                if (prefetchIndex == i && prefetchFuture != null) {
                    val fut = prefetchFuture
                    prefetchFuture = null
                    prefetchIndex = -1
                    var pref: PrefetchResult? = null
                    try {
                        // 预取等待上限 5 秒：超时放弃预取、直接走当前句流式现取现播，不死等
                        // （旧代码无上限，预取卡住会把整段拖到看门狗 20 秒误杀）。
                        val prefetchDeadline = SystemClock.elapsedRealtime() + PREFETCH_WAIT_TIMEOUT_MS
                        var prefetchTimedOut = false
                        while (!fut.isDone) {
                            if (cancelled(seq)) break
                            if (SystemClock.elapsedRealtime() >= prefetchDeadline) {
                                prefetchTimedOut = true
                                Log.w(TAG, "edge tts prefetch wait timeout after ${PREFETCH_WAIT_TIMEOUT_MS}ms, streaming fallback")
                                break
                            }
                            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                        }
                        if (!cancelled(seq) && !prefetchTimedOut) pref = fut.get()
                    } catch (e: Exception) {
                        Log.w(TAG, "edge tts prefetch wait failed, streaming fallback", e)
                    }
                    if (pref != null && pref.ok && pref.pcms.isNotEmpty()) {
                        // H5：每句开始播放时回调一次 onSentence(sentence)。
                        main.post { if (!cancelled(seq)) onSentence?.invoke(sentence) }
                        var writeOk = true
                        for (pcm in pref.pcms) {
                            if (cancelled(seq)) break
                            if (pcm.isEmpty()) continue
                            val frames = writePcmFully(t, pcm)
                            if (frames < 0) { writeOk = false; break }
                            totalFrames += frames
                            sawAny = true
                        }
                        if (!writeOk) {
                            // 预取 PCM 写轨道失败即 break：这句及之后都没播，续播从这句开始。
                            recordFailure(i, "AudioTrack.write 失败", true)
                            // 语音诊断批：补一行写轨诊断，reason 字符串不动。
                            com.jarvis.app.AuditLog.append(ctx, "语音播报", "Edge 第 ${i + 1}/${sentences.size} 句写轨诊断", lastWriteDiag)
                            break
                        }
                        sentenceDone = true
                    } else if (pref != null && !pref.ok) {
                        // 预取失败不直接判死刑：下面走流式现取现播再试一次，成功了就不算缺句。
                        Log.w(TAG, "edge tts sentence $i prefetch failed (${pref.reason}), streaming fallback")
                    }
                }
                if (sentenceDone) continue
                if (cancelled(seq)) break
                // 2) 流式现取现播：边收帧边解码边写（见 streamSentence），不收满整句 MP3。
                var sentenceFired = false
                // 写 PCM 进 AudioTrack：流式句与整段回落句共用同一份（行为一字不差——
                // 空 PCM 照过、首段到时回调 onSentence、写失败返回 false）。
                val writePcm: (ByteArray) -> Boolean = { pcm ->
                    if (pcm.isEmpty()) {
                        true
                    } else {
                        if (!sentenceFired) {
                            sentenceFired = true
                            // H5：每句开始播放时回调一次 onSentence(sentence)。
                            main.post { if (!cancelled(seq)) onSentence?.invoke(sentence) }
                        }
                        val frames = writePcmFully(t, pcm)
                        if (frames < 0) {
                            false
                        } else {
                            totalFrames += frames
                            sawAny = true
                            true
                        }
                    }
                }
                val playOne: (EdgeSession?) -> StreamResult = { sess ->
                    streamSentence(seq, sentence, voice, ratePct, pitchHz, sess, onPcm = writePcm)
                }
                var result: StreamResult = try {
                    if (streamingDecoderDead) {
                        // 流式解码器已确认死：直接整段取音+解码，不再往死解码器上撞。
                        streamSentenceWhole(seq, sentence, voice, ratePct, pitchHz, null, writePcm)
                    } else if (session != null) {
                        try {
                            playOne(session)
                        } catch (e: Exception) {
                            closeSession(session)
                            session = null
                            if (cancelled(seq)) throw e
                            if (e is DecoderDeadException) {
                                // 解码器已死：逐句重试必定再死，别浪费一次合成，直接抛给外层走整段回落。
                                Log.w(TAG, "edge tts session sentence $i decoder dead, skip per-sentence retry", e)
                                throw e
                            }
                            Log.w(TAG, "edge tts session sentence $i failed, fallback to per-sentence", e)
                            playOne(null)
                        }
                    } else {
                        playOne(null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "edge tts sentence $i failed", e)
                    // sentenceFired 即“已有 PCM 写进轨道”：异常路径也不能硬填 false，
                    // 否则下面的整段回落会误判、把播过一半的句整句重播。
                    StreamResult(false, e.message ?: "取音失败", sentenceFired)
                }
                if (cancelled(seq)) break
                if (!result.ok) {
                    // 流式解码器到手即死（Flyme 上第一次喂就报 Released）：还没出过 PCM 时，
                    // 整句改走整段 MediaExtractor 解码路再试一次；已出过 PCM 就不能整句重播
                    // （会重复），按原规则失败。只试一次；成了这句就算播完。
                    if (!cancelled(seq) && !streamingDecoderDead && !result.sawAudio &&
                        (result.reason ?: "").startsWith(DECODER_DEAD_REASON)
                    ) {
                        streamingDecoderDead = true
                        // 在途的预取停掉（它走同一条流式路，必失败）；共享会话还攺着半截
                        // turn 的帧，不能再用：关掉、改逐句。
                        prefetchFuture?.cancel(true)
                        prefetchFuture = null
                        prefetchIndex = -1
                        if (session != null) {
                            closeSession(session)
                            session = null
                        }
                        Log.w(TAG, "edge tts streaming decoder dead, sentence $i fallback to whole-mp3")
                        result = streamSentenceWhole(seq, sentence, voice, ratePct, pitchHz, null, writePcm)
                        if (result.ok) continue
                        // 回落也失败了：审计里同时留解码器死因和取音失败原因，
                        // 别只剩后者（原来只剩“取音为空”，死因只在 Logcat 里）。
                        // 注意：这里只补记一条审计，不改 result.reason——
                        // 下面的 stoppedHere 判据（reason == "AudioTrack.write 失败"）必须原样。
                        com.jarvis.app.AuditLog.append(
                            ctx, "语音播报",
                            "Edge 第 ${i + 1}/${sentences.size} 句流式解码器已死",
                            "${DECODER_DEAD_REASON}，整段回落又失败（${result.reason ?: "取音/解码失败"}）"
                        )
                    }
                    // 首句失败或写轨道失败会 break（该句及之后都没播）；其余失败只跳过这句、
                    // 继续播后面的句子，续播时只补这一句、不重播已播完的尾巴。
                    val stoppedHere = i == 0 || result.reason == "AudioTrack.write 失败"
                    recordFailure(i, result.reason ?: "取音/解码失败", stoppedHere)
                    // 语音诊断批：写轨失败时补一行写轨诊断，reason 字符串不动。
                    if (result.reason == "AudioTrack.write 失败") {
                        com.jarvis.app.AuditLog.append(ctx, "语音播报", "Edge 第 ${i + 1}/${sentences.size} 句写轨诊断", lastWriteDiag)
                    }
                    // 第一句就失败当整体失败；写轨道失败说明轨道已死，后面也不用再试。
                    if (stoppedHere) break
                    continue
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "edge tts failed", e)
        } finally {
            disarmStallWatchdog()
            prefetchFuture?.cancel(true)
            prefetchFuture = null
            val ps = prefetchSocket
            prefetchSocket = null
            runCatching { ps?.close() }
            if (!cancelled(seq)) {
                // 等尾巴播完再停：放音头追上总帧数，或超过“音频时长 + 3 秒”就放弃等。
                if (totalFrames > 0) {
                    val deadline = System.currentTimeMillis() + totalFrames * 1000 / SAMPLE_RATE + 3000
                    while (System.currentTimeMillis() < deadline && !cancelled(seq)) {
                        try {
                            if (t.playbackHeadPosition.toLong() >= totalFrames) break
                        } catch (_: Exception) {
                            break
                        }
                        try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    }
                }
                runCatching { t.stop() }
            }
            runCatching { t.release() }
            if (track === t) track = null
            closeSession(session)
            // H3：播完/取消/失速时释放音频焦点。
            releaseAudioFocus()
            // L4：取消时也回调一次 onDone(false)，调用方状态机总能收敛；
            // finally 只走一次，正常完成与取消只发一条，不会调两次。
            // wasCancelled 在 post 前先定住，避免 post 执行前又被新的 stop 翻转。
            // M10：中间有句子失败被跳过时 onDone(false)，调用方知道这段回复缺了一句
            // （失败原因已在当时记进审计，不在这里重复记一条）。
            val wasCancelled = cancelled(seq) || stallFired
            val incomplete = abortedAt != null
            val failReason = firstFailureReason
            main.post {
                if (!wasCancelled && incomplete) {
                    Log.w(TAG, "edge tts incomplete: sentence ${abortedAt!! + 1} failed: $failReason")
                }
                onDone(if (wasCancelled) false else if (incomplete) false else sawAny)
            }
        }
    }

    private data class PrefetchResult(val pcms: List<ByteArray>, val ok: Boolean, val reason: String?)
    private data class StreamResult(val ok: Boolean, val reason: String?, val sawAudio: Boolean)

    /**
     * 整段取音的结果：mp3 为空时 failure 一定非空；“取音为空”只留给真正
     * turn.end 到达且零音频帧的情况，其他失败一律记真实原因。
     */
    private data class FetchResult(val mp3: ByteArray, val failure: String?)

    /**
     * 服务端文本帧里的合成错误：正常帧（turn.start / response 元数据 / 空体
     * turn.end）的体里都没有 error/code 键；含这些键即认为是服务端报错，
     * 返回体文本（单行化、截断）供调用方直接报错——不等 turn.end 再判“取音为空”。
     * 注意：错误体的具体 JSON 结构以服务端实发为准，这里只认键名，未实测过。
     */
    private fun serverErrorIn(msg: String): String? {
        val body = msg.substringAfter(String(HEADER_END, Charsets.UTF_8), "")
        if (body.isBlank()) return null
        if (!body.contains("\"error\"") && !body.contains("\"code\"")) return null
        return body.lines().joinToString(" ").take(300)
    }

    /**
     * 取音失败原因分类：握手状态码 / 超时 / TLS / 连接关闭 / GEC / 发 SSML /
     * 服务端合成错误 / 其他。握手失败的消息里已带 HTTP 状态码
     * （requireHandshake101），原样返回；其余一律带上原始异常消息，不再吞成空字节。
     */
    private fun classifyFetchFailure(e: Exception): String {
        val msg = e.message ?: ""
        val detail = msg.ifEmpty { e.javaClass.simpleName }
        return when {
            msg.startsWith("Edge 语音握手失败") -> msg
            msg.startsWith("Edge 语音合成失败") -> msg
            msg.startsWith("取音 GEC 生成失败") -> msg
            msg.startsWith("发 SSML 失败") -> msg
            e is java.net.SocketTimeoutException ->
                if (msg.contains("connect", ignoreCase = true)) "取音连接超时（建连 15 秒）：$detail"
                else "取音读取超时（45 秒无响应）：$detail"
            e is javax.net.ssl.SSLHandshakeException -> "取音 TLS 握手失败：$detail"
            e is javax.net.ssl.SSLException -> "取音 TLS 失败：$detail"
            e is java.io.IOException && msg.contains("连接已关闭") -> "取音连接已关闭"
            else -> "取音失败：$detail"
        }
    }

    // 语音第 2 批：焦点暂失时挂起写循环，别往暂停的轨里写——等 GAIN（playState 回到
    // PLAYING）或 5 秒超时；超时返回 false，调用方按写失败处理（reason 仍是
    // "AudioTrack.write 失败"，stoppedHere 判据不变）。
    // stop() 会先把 track 置空再释放，等待中发现轨道已换也立刻返回 false，不死等。
    private fun waitForFocusGain(t: AudioTrack): Boolean {
        if (!focusPaused) return true
        val deadline = SystemClock.elapsedRealtime() + FOCUS_GAIN_TIMEOUT_MS
        while (true) {
            if (track !== t) return false
            val playing = runCatching { t.playState == AudioTrack.PLAYSTATE_PLAYING }.getOrDefault(false)
            if (!focusPaused && playing) return true
            if (SystemClock.elapsedRealtime() >= deadline) {
                Log.w(TAG, "audio focus gain timeout after ${FOCUS_GAIN_TIMEOUT_MS}ms, dropping write")
                return false
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return false
            }
        }
    }

    // 语音诊断批：write 返回码中文名（官方文档）+ 当时轨状态，失败诊断行用。
    private fun writeCodeName(n: Int): String = when (n) {
        AudioTrack.ERROR -> "通用错 ERROR"
        AudioTrack.ERROR_BAD_VALUE -> "参数错 BAD_VALUE"
        AudioTrack.ERROR_INVALID_OPERATION -> "轨未初始化 INVALID_OPERATION"
        AudioTrack.ERROR_DEAD_OBJECT -> "轨已死 DEAD_OBJECT"
        else -> "未知码"
    }

    private fun trackStateText(t: AudioTrack): String {
        val state = runCatching { t.state }.getOrNull()
        val stateName = when (state) {
            AudioTrack.STATE_INITIALIZED -> "已初始化"
            AudioTrack.STATE_UNINITIALIZED -> "未初始化"
            AudioTrack.STATE_NO_STATIC_DATA -> "无静态数据"
            else -> "未知"
        }
        val playState = runCatching { t.playState }.getOrNull()
        val playName = when (playState) {
            AudioTrack.PLAYSTATE_PLAYING -> "播放中"
            AudioTrack.PLAYSTATE_PAUSED -> "已暂停"
            AudioTrack.PLAYSTATE_STOPPED -> "已停止"
            else -> "未知"
        }
        return "state=$state（$stateName），playState=$playState（$playName）"
    }

    // L7：AudioTrack.write 的返回值必须检查，<= 0 即停；返回实际写入的帧数（失败返回 -1），
    // 调用方按返回值累加帧数，不再丢弃返回值后按 pcm.size/2 硬算。
    // 每次成功写出都刷新失速看门狗的 lastProgressAt。
    // 语音诊断批：三支失败都把原因写进 lastWriteDiag（之前只回 -1，审计里和
    // write 错分不出来）；调用方在失败记录旁补一行审计，reason 字符串一字不动。
    private fun writePcmFully(t: AudioTrack, pcm: ByteArray): Int {
        var off = 0
        while (off < pcm.size) {
            // 语音第 2 批：焦点暂失时挂起，不往暂停的轨里写。
            if (!waitForFocusGain(t)) {
                lastWriteDiag = "焦点等待失败（5 秒未等到 GAIN，或轨已被换掉）；${trackStateText(t)}"
                return -1
            }
            val n = try {
                t.write(pcm, off, pcm.size - off)
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack.write threw", e)
                lastWriteDiag = "write 抛异常：${e.javaClass.simpleName}；${trackStateText(t)}"
                return -1
            }
            if (n <= 0) {
                Log.e(TAG, "AudioTrack.write returned $n, stopping playback")
                lastWriteDiag = "write 返回 $n（${writeCodeName(n)}）；${trackStateText(t)}"
                return -1
            }
            off += n
            markProgress()
        }
        return off / 2
    }

    // ---------- M8 句内流式：边收帧边解码边写 ----------

    /** 二进制帧里的 "Path:audio" 段解析器：跨帧的段头也能拼出来，未到下一个段头前不吐旧数据。 */
    private class AudioSegmentParser {
        private val buf = ByteArrayOutputStream()
        private val hdr = "Path:audio\r\n".toByteArray(Charsets.UTF_8)
        private var sawHeader = false

        fun feed(payload: ByteArray): ByteArray {
            buf.write(payload)
            val data = buf.toByteArray()
            val out = ByteArrayOutputStream()
            var pos = 0
            while (true) {
                val h = indexOfFromStatic(data, hdr, pos)
                if (h < 0) break
                sawHeader = true
                val segStart = h + hdr.size
                val nextH = indexOfFromStatic(data, hdr, segStart)
                if (nextH < 0) break
                if (nextH > segStart) out.write(data, segStart, nextH - segStart)
                pos = nextH
            }
            val keep = data.copyOfRange(pos, data.size)
            buf.reset()
            buf.write(keep)
            return out.toByteArray()
        }

        fun finish(): ByteArray {
            val data = buf.toByteArray()
            buf.reset()
            if (!sawHeader) return data
            val h = indexOfFromStatic(data, hdr, 0)
            if (h < 0) return ByteArray(0)
            val start = h + hdr.size
            return if (start < data.size) data.copyOfRange(start, data.size) else ByteArray(0)
        }

        companion object {
            fun indexOfFromStatic(haystack: ByteArray, needle: ByteArray, from: Int): Int {
                if (needle.isEmpty() || haystack.size < needle.size) return -1
                outer@ for (i in from..haystack.size - needle.size) {
                    for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                    return i
                }
                return -1
            }
        }
    }

    /**
     * 流式解码器已死：MediaCodec 在 Released 状态下被喂。Flyme 上手写裸 format 造出的
     * OMX 解码器可能到手即死（configure/start 看似成功，第一次 feed 就报 Released
     * state）。单独成类、消息带固定前缀，调用方凭前缀认出它、走整段回落，而不是把
     * MediaCodec 的原始 IllegalStateException 裸奔到审计里。
     */
    private class DecoderDeadException(message: String) : Exception(message)

    /** 增量 MP3 解码器：MP3 块随到随喂 MediaCodec，随到随出 PCM；不用 MediaExtractor 等整段。 */
    private class StreamingMp3Decoder {
        private val codec: MediaCodec
        private var rateChecked = false
        private var outputDone = false
        private var finished = false
        private var closed = false

        init {
            codec = MediaCodec.createDecoderByType("audio/mpeg")
            val format = MediaFormat.createAudioFormat("audio/mpeg", SAMPLE_RATE, 1)
            codec.configure(format, null, null, 0)
            codec.start()
            // 诊断：记下到底是哪个 OMX 解码器——以后只凭这一行就能区分“创建即死”
            // 还是“中途被释放”。只记 Log，不记审计。
            Log.i(TAG, "streaming decoder created: ${runCatching { codec.name }.getOrNull() ?: "?"}")
        }

        fun feed(mp3: ByteArray): List<ByteArray> {
            if (closed) throw DecoderDeadException("流式解码器已释放：feed 在 close 之后被调用")
            val out = ArrayList<ByteArray>()
            var off = 0
            var guard = 0
            try {
                while (off < mp3.size) {
                    if (++guard > 10_000) {
                        Log.e(TAG, "streaming decoder feed guard tripped")
                        break
                    }
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx < 0) {
                        out.addAll(drainAll(0))
                        try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                        continue
                    }
                    val inBuf = codec.getInputBuffer(inIdx) ?: continue
                    inBuf.clear()
                    val n = min(inBuf.remaining(), mp3.size - off)
                    inBuf.put(mp3, off, n)
                    off += n
                    codec.queueInputBuffer(inIdx, 0, n, 0, 0)
                    out.addAll(drainAll(0))
                }
            } catch (e: IllegalStateException) {
                // CodecException 也是 IllegalStateException 的子类：到手即死的解码器
                // 第一次喂就从这里炸出来，转成带前缀的错、触发整段回落；不要吞掉当没事。
                throw DecoderDeadException("流式解码器已释放：${e.message}")
            }
            return out
        }

        fun finish(): List<ByteArray> {
            if (closed) throw DecoderDeadException("流式解码器已释放：finish 在 close 之后被调用")
            if (finished) return emptyList()
            finished = true
            val out = ArrayList<ByteArray>()
            var tries = 0
            try {
                while (tries++ < 200) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        break
                    }
                    out.addAll(drainAll(0))
                    if (outputDone) return out
                }
                val deadline = System.currentTimeMillis() + 10_000L
                while (!outputDone && System.currentTimeMillis() < deadline) {
                    out.addAll(drainAll(10_000))
                }
            } catch (e: IllegalStateException) {
                throw DecoderDeadException("流式解码器已释放：${e.message}")
            }
            return out
        }

        fun close() {
            closed = true
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        private fun drainAll(timeoutUs: Long): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            while (true) {
                val pcm = drainOnce(timeoutUs) ?: break
                out.add(pcm)
                if (outputDone) break
            }
            return out
        }

        private fun drainOnce(timeoutUs: Long): ByteArray? {
            val info = MediaCodec.BufferInfo()
            val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                checkRate(runCatching { codec.outputFormat }.getOrNull())
                return null
            }
            if (outIdx < 0) return null
            var pcm: ByteArray? = null
            val outBuf = codec.getOutputBuffer(outIdx)
            if (outBuf != null && info.size > 0) {
                pcm = ByteArray(info.size)
                outBuf.position(info.offset)
                outBuf.limit(info.offset + info.size)
                outBuf.get(pcm)
            }
            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            codec.releaseOutputBuffer(outIdx, false)
            if (eos) outputDone = true
            return pcm
        }

        // L7：核对解码器实际采样率与声明的 24000 是否一致，不一致记日志告警。
        private fun checkRate(format: MediaFormat?) {
            if (rateChecked || format == null) return
            rateChecked = true
            val rate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull() ?: return
            if (rate != SAMPLE_RATE) {
                Log.w(TAG, "Edge MP3 sample rate $rate != declared $SAMPLE_RATE, audio may sound wrong")
            }
        }
    }

    /**
     * 流式合成一句：发 SSML 后边读 WebSocket 帧边把已到的 MP3 解码、边解边把 PCM 交给 onPcm
     * （调用方写进 AudioTrack，或预取时收进列表）。session 非空用共享会话，否则自建逐句连接。
     * 解码器建不起来时（个别设备）回落到旧的整句取音+解码路径，不哑火；预取线程不走这个回落，
     * 以免它动全局 socket，失败了由主线程流式重试。
     */
    private fun streamSentence(
        seq: Int, sentence: String, voice: String, ratePct: Int, pitchHz: Int,
        session: EdgeSession?, usePrefetchSocket: Boolean = false,
        onPcm: (ByteArray) -> Boolean
    ): StreamResult {
        val decoder: StreamingMp3Decoder
        try {
            decoder = StreamingMp3Decoder()
        } catch (e: Exception) {
            Log.w(TAG, "streaming decoder unavailable, fallback to whole-mp3", e)
            if (usePrefetchSocket) return StreamResult(false, "流式解码器不可用", false)
            return streamSentenceWhole(seq, sentence, voice, ratePct, pitchHz, session, onPcm)
        }
        // MediaCodec 创建耗时不计入看门狗 20 秒：解码器建好后再重置一次计时起点。
        markProgress()
        val parser = AudioSegmentParser()
        var sawPcm = false
        var gotAudio = false
        var writeFailed = false
        // 收到 turn.end 才算合成正常结束：没等到 turn.end 就断开（0x8）且零音频时，
        // 不再冒充“取音为空”，而是记连接已关闭。
        var sawTurnEnd = false
        try {
            val ssmlMsg = "X-RequestId:${hexId()}\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:${dateString()}Z\r\n" +
                "Path:ssml\r\n\r\n" +
                ssml(sentence, voice, ratePct, pitchHz)
            val readLoop: (FrameReader, Boolean) -> Unit = { reader, closeThrows ->
                while (!cancelled(seq) && !writeFailed) {
                    val (opcode, payload) = reader.readFrame()
                    when (opcode) {
                        0x2 -> if (payload.isNotEmpty()) {
                            markProgress()
                            gotAudio = true
                            val mp3chunk = parser.feed(payload)
                            if (mp3chunk.isNotEmpty()) {
                                for (pcm in decoder.feed(mp3chunk)) {
                                    if (pcm.isEmpty()) continue
                                    sawPcm = true
                                    markProgress()
                                    if (!onPcm(pcm)) { writeFailed = true; break }
                                }
                            }
                        }
                        0x1 -> {
                            val msg = String(payload, Charsets.UTF_8)
                            // 服务端文本帧里报合成失败时直接抛，带上服务端原文，
                            // 不等 turn.end 再判空。
                            val err = serverErrorIn(msg)
                            if (err != null) throw java.io.IOException("Edge 语音合成失败：$err")
                            if (msg.contains("turn.end")) { sawTurnEnd = true; break }
                        }
                        0x8 -> if (closeThrows) throw java.io.IOException("Edge 语音连接已关闭") else break
                    }
                }
            }
            if (session != null) {
                sendTextFrame(session.out, ssmlMsg)
                readLoop(session.reader, true)
            } else {
                val own = openEdgeSocket(usePrefetchSocket)
                try {
                    sendTextFrame(own.out, ssmlMsg)
                    readLoop(own.reader, false)
                } finally {
                    closeSession(own)
                }
            }
            if (cancelled(seq)) return StreamResult(false, "cancelled", sawPcm)
            if (!writeFailed) {
                val tailMp3 = parser.finish()
                if (tailMp3.isNotEmpty()) {
                    for (pcm in decoder.feed(tailMp3)) {
                        if (pcm.isEmpty()) continue
                        sawPcm = true
                        markProgress()
                        if (!onPcm(pcm)) { writeFailed = true; break }
                    }
                }
            }
            if (!writeFailed) {
                for (pcm in decoder.finish()) {
                    if (pcm.isEmpty()) continue
                    sawPcm = true
                    markProgress()
                    if (!onPcm(pcm)) { writeFailed = true; break }
                }
            }
        } catch (e: Exception) {
            if (cancelled(seq)) return StreamResult(false, "cancelled", sawPcm)
            throw e
        } finally {
            decoder.close()
        }
        if (writeFailed) return StreamResult(false, "AudioTrack.write 失败", sawPcm)
        if (!sawPcm) {
            // “取音为空”只保留给真正 turn.end 到达且零音频帧的情况。
            val reason = if (gotAudio) "解码无音频"
                else if (sawTurnEnd) "取音为空"
                else "取音连接已关闭（未等到 turn.end）"
            return StreamResult(false, reason, false)
        }
        return StreamResult(true, null, true)
    }

    /** 流式解码器不可用时的旧路径：整句取音再整句解码（已验证过的回落，不动它）。 */
    private fun streamSentenceWhole(
        seq: Int, sentence: String, voice: String, ratePct: Int, pitchHz: Int,
        session: EdgeSession?, onPcm: (ByteArray) -> Boolean
    ): StreamResult {
        // 会话路（fetchMp3OnSession）仍抛异常：0x8 提前关连接时抛是会话失效的信号，
        // 调用方凭它关会话、改逐句重试——不在这里吞掉。逐句路（fetchMp3）已改成
        // 返回带原因的 FetchResult，不再吞成空字节。
        val mp3: ByteArray
        var fetchFailure: String? = null
        if (session != null) {
            mp3 = fetchMp3OnSession(session, seq, sentence, voice, ratePct, pitchHz)
        } else {
            val fetched = fetchMp3(seq, sentence, voice, ratePct, pitchHz)
            mp3 = fetched.mp3
            fetchFailure = fetched.failure
        }
        if (cancelled(seq)) return StreamResult(false, "cancelled", false)
        if (mp3.isEmpty()) return StreamResult(false, fetchFailure ?: "取音为空", false)
        val pcms = decodeMp3(seq, mp3)
        if (pcms.isEmpty()) return StreamResult(false, "解码为空", false)
        var saw = false
        for (pcm in pcms) {
            if (pcm.isEmpty()) continue
            saw = true
            markProgress()
            if (!onPcm(pcm)) return StreamResult(false, "AudioTrack.write 失败", saw)
        }
        return StreamResult(true, null, saw)
    }

    /** 解析握手响应状态行里的 HTTP 状态码，如 "HTTP/1.1 403 Forbidden" -> 403，解析不到返回 -1。 */
    private fun handshakeStatusCode(statusLine: String): Int {
        val parts = statusLine.trim().split(Regex("\\s+"))
        if (parts.size < 2) return -1
        return parts[1].toIntOrNull() ?: -1
    }

    /** 下次握手前先等 429 退避到期（在 io / 预取线程上 sleep，不阻塞主线程）。 */
    private fun waitHandshakeBackoff() {
        val until = handshakeRetryAfterMs
        if (until == 0L) return
        val waitMs = until - SystemClock.elapsedRealtime()
        if (waitMs > 0) {
            Log.i(TAG, "edge tts handshake backoff: waiting ${waitMs}ms after 429 before next handshake")
            try { Thread.sleep(waitMs) } catch (_: InterruptedException) { /* 中断即继续握手 */ }
        }
    }

    /**
     * 握手 101 校验：成功时重置看门狗计时起点（建连/握手/GEC 时间不计入 20 秒）；
     * 非 101 时把实际状态码记进 Log，异常消息里也带状态码（调用方的按句审计/会话
     * 审计会原样落盘）；429 时设 2 秒退避，下次握手前先等到期。
     * 成功返回，失败抛 IllegalStateException。
     */
    private fun requireHandshake101(statusLine: String, site: String) {
        if (statusLine.contains("101")) {
            markProgress()
            return
        }
        val code = handshakeStatusCode(statusLine)
        Log.e(TAG, "edge tts handshake failed at ${site}: HTTP status ${code}, status line: $statusLine")
        if (code == 429) {
            handshakeRetryAfterMs = SystemClock.elapsedRealtime() + HANDSHAKE_429_BACKOFF_MS
            Log.w(TAG, "edge tts handshake 429, backing off ${HANDSHAKE_429_BACKOFF_MS}ms before next handshake")
        }
        throw IllegalStateException("Edge 语音握手失败（${site}）：HTTP 状态码 ${code}：$statusLine")
    }

    /** 流式逐句连接用的握手 + speech.config（与 openSession/fetchMp3 同流程，只供新流式路径用）。 */
    private fun openEdgeSocket(isPrefetch: Boolean): EdgeSession {
        waitHandshakeBackoff()
        val ssl = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        if (isPrefetch) prefetchSocket = ssl else socket = ssl
        try {
            ssl.connect(InetSocketAddress(HOST, 443), 15_000)
            ssl.soTimeout = 45_000
            ssl.startHandshake()
            val reader = FrameReader(ssl.inputStream)
            val out = ssl.outputStream
            val key = android.util.Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, android.util.Base64.NO_WRAP)
            val path = "/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$TOKEN" +
                "&ConnectionId=${hexId()}" +
                "&Sec-MS-GEC=${secMsGec()}" +
                "&Sec-MS-GEC-Version=2-$CHROMIUM_MAJOR.0.3650.75"
            val request = "GET $path HTTP/1.1\r\n" +
                "Host: $HOST\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Pragma: no-cache\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n" +
                "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0\r\n" +
                "\r\n"
            out.write(request.toByteArray(Charsets.UTF_8))
            out.flush()
            val head = reader.readUntil(HEADER_END)
            val statusLine = String(head, Charsets.UTF_8).lineSequence().firstOrNull().orEmpty()
            requireHandshake101(statusLine, "openEdgeSocket")
            val date = dateString()
            val configMsg = "X-Timestamp:$date\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
            sendTextFrame(out, configMsg)
            return EdgeSession(ssl, reader, out)
        } catch (e: Exception) {
            if (isPrefetch) {
                if (prefetchSocket === ssl) prefetchSocket = null
            } else {
                if (socket === ssl) socket = null
            }
            runCatching { ssl.close() }
            throw e
        }
    }

    // ---------- 协议部分：手写 TLS + WebSocket 帧，跟参考实现一致 ----------

    // ---------- M4 会话式合成：一段回复只握手一次，多句复用同一连接 ----------
    //
    // Edge 协议本身按 turn 分句（每句 SSML 一个 X-RequestId，读到 turn.end 即该句结束），
    // 服务端允许多 turn 复用同一 WebSocket；逐句握手只是旧实现的保守做法。
    // 安全回落：建会话失败 → 全程逐句握手；会话中某句失败（发 SSML/读帧任一步抛异常，
    // 或服务端没给 turn.end 就关连接）→ 关会话，该句及之后全改逐句握手继续念完本段。
    // openSession 的握手 + config 与 fetchMp3 里的流程故意重复一份，不抽公共函数，
    // 免得动到已验证的逐句回落路径。

    /** 一次会话连接：握手 + speech.config 已发好，后续只发 SSML。 */
    private class EdgeSession(val ssl: SSLSocket, val reader: FrameReader, val out: java.io.OutputStream)

    private fun openSession(): EdgeSession {
        waitHandshakeBackoff()
        val ssl = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        socket = ssl
        try {
            ssl.connect(InetSocketAddress(HOST, 443), 15_000)
            ssl.soTimeout = 45_000
            ssl.startHandshake()
            val reader = FrameReader(ssl.inputStream)
            val out = ssl.outputStream

            val key = android.util.Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, android.util.Base64.NO_WRAP)
            val path = "/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$TOKEN" +
                "&ConnectionId=${hexId()}" +
                "&Sec-MS-GEC=${secMsGec()}" +
                "&Sec-MS-GEC-Version=2-$CHROMIUM_MAJOR.0.3650.75"
            val request = "GET $path HTTP/1.1\r\n" +
                "Host: $HOST\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Pragma: no-cache\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n" +
                "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0\r\n" +
                "\r\n"
            out.write(request.toByteArray(Charsets.UTF_8))
            out.flush()
            val head = reader.readUntil(HEADER_END)
            val statusLine = String(head, Charsets.UTF_8).lineSequence().firstOrNull().orEmpty()
            requireHandshake101(statusLine, "openSession")

            val date = dateString()
            val configMsg = "X-Timestamp:$date\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
            sendTextFrame(out, configMsg)
            return EdgeSession(ssl, reader, out)
        } catch (e: Exception) {
            if (socket === ssl) socket = null
            runCatching { ssl.close() }
            throw e
        }
    }

    private fun closeSession(session: EdgeSession?) {
        if (session == null) return
        if (socket === session.ssl) socket = null
        runCatching { session.ssl.close() }
    }

    /** 会话连接上合成一句：新 X-RequestId 发 SSML，读帧读到该句 turn.end 才停；任一步失败抛异常，调用方回落逐句握手。 */
    private fun fetchMp3OnSession(session: EdgeSession, seq: Int, sentence: String, voice: String, ratePct: Int, pitchHz: Int): ByteArray {
        val ssmlMsg = "X-RequestId:${hexId()}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${dateString()}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml(sentence, voice, ratePct, pitchHz)
        sendTextFrame(session.out, ssmlMsg)

        val binAll = ByteArrayOutputStream()
        var gotAudio = false
        while (true) {
            if (cancelled(seq)) break
            val (opcode, payload) = session.reader.readFrame()
            when (opcode) {
                0x2 -> if (payload.isNotEmpty()) { binAll.write(payload); gotAudio = true }
                0x1 -> {
                    val msg = String(payload, Charsets.UTF_8)
                    val err = serverErrorIn(msg)
                    if (err != null) throw java.io.IOException("Edge 语音合成失败：$err")
                    if (msg.contains("turn.end")) break
                }
                // 服务端没给 turn.end 就关连接：当本次合成失败，调用方回落逐句握手重念该句。
                0x8 -> throw java.io.IOException("Edge 语音连接已关闭")
            }
        }
        if (!gotAudio) return ByteArray(0)
        return extractAudio(binAll.toByteArray())
    }

    private fun fetchMp3(seq: Int, sentence: String, voice: String, ratePct: Int, pitchHz: Int): FetchResult {
        waitHandshakeBackoff()
        val ssl = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        socket = ssl
        try {
            ssl.connect(InetSocketAddress(HOST, 443), 15_000)
            ssl.soTimeout = 45_000
            ssl.startHandshake()
            val reader = FrameReader(ssl.inputStream)
            val out = ssl.outputStream

            val key = android.util.Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, android.util.Base64.NO_WRAP)
            val path = "/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$TOKEN" +
                "&ConnectionId=${hexId()}" +
                "&Sec-MS-GEC=${secMsGec()}" +
                "&Sec-MS-GEC-Version=2-$CHROMIUM_MAJOR.0.3650.75"
            val request = "GET $path HTTP/1.1\r\n" +
                "Host: $HOST\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Pragma: no-cache\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n" +
                "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0\r\n" +
                "\r\n"
            out.write(request.toByteArray(Charsets.UTF_8))
            out.flush()
            val head = reader.readUntil(HEADER_END)
            val statusLine = String(head, Charsets.UTF_8).lineSequence().firstOrNull().orEmpty()
            requireHandshake101(statusLine, "fetchMp3")

            val date = dateString()
            val configMsg = "X-Timestamp:$date\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
            sendTextFrame(out, configMsg)

            val ssmlMsg = "X-RequestId:${hexId()}\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:${date}Z\r\n" +
                "Path:ssml\r\n\r\n" +
                ssml(sentence, voice, ratePct, pitchHz)
            // 发 SSML 失败要单独记原因，别让它在外层 catch 里混成笼统的“取音失败”。
            try {
                sendTextFrame(out, ssmlMsg)
            } catch (e: Exception) {
                throw java.io.IOException("发 SSML 失败：${e.message ?: e.javaClass.simpleName}")
            }

            val binAll = ByteArrayOutputStream()
            var gotAudio = false
            var endedCleanly = false
            while (true) {
                if (cancelled(seq)) break
                val (opcode, payload) = reader.readFrame()
                when (opcode) {
                    0x2 -> if (payload.isNotEmpty()) { binAll.write(payload); gotAudio = true }
                    0x1 -> {
                        val msg = String(payload, Charsets.UTF_8)
                        val err = serverErrorIn(msg)
                        if (err != null) throw java.io.IOException("Edge 语音合成失败：$err")
                        if (msg.contains("turn.end")) { endedCleanly = true; break }
                    }
                    0x8 -> break
                }
            }
            if (cancelled(seq)) return FetchResult(ByteArray(0), null)
            if (!gotAudio) return FetchResult(
                ByteArray(0),
                if (endedCleanly) "取音为空" else "取音连接已关闭（未等到 turn.end）"
            )
            val audio = extractAudio(binAll.toByteArray())
            return if (audio.isEmpty()) FetchResult(ByteArray(0), "收到音频帧但未解析出音频段")
            else FetchResult(audio, null)
        } catch (e: Exception) {
            if (cancelled(seq)) return FetchResult(ByteArray(0), null)
            Log.e(TAG, "fetchMp3 failed: ${e.message}")
            return FetchResult(ByteArray(0), classifyFetchFailure(e))
        } finally {
            if (socket === ssl) socket = null
            runCatching { ssl.close() }
        }
    }

    private fun extractAudio(data: ByteArray): ByteArray {
        val hdr = "Path:audio\r\n".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        var pos = 0
        while (true) {
            val h = indexOfFrom(data, hdr, pos)
            if (h < 0) break
            val segStart = h + hdr.size
            val nextH = indexOfFrom(data, hdr, segStart)
            val segEnd = if (nextH >= 0) nextH else data.size
            if (segEnd > segStart) out.write(data, segStart, segEnd - segStart)
            if (nextH < 0) break
            pos = segStart
        }
        return out.toByteArray()
    }

    private fun indexOfFrom(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun ssml(text: String, voice: String, ratePct: Int, pitchHz: Int): String {
        val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val rateStr = (if (ratePct >= 0) "+" else "") + ratePct + "%"
        val pitchStr = (if (pitchHz >= 0) "+" else "") + pitchHz + "Hz"
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
            "<voice name='$voice'><prosody rate='$rateStr' pitch='$pitchStr'>$esc</prosody></voice></speak>"
    }

    /** Sec-MS-GEC v2：格式为“CS64-SHA256”，算法见 EdgeGec（移植自 edge-tts 7.3.1 的 drm.py）。 */
    private fun secMsGec(): String = try {
        EdgeGec.generate(TOKEN)
    } catch (e: Exception) {
        // GEC 生成失败要单独记原因，别让它混进后面的握手/超时里。
        throw IllegalStateException("取音 GEC 生成失败：${e.message ?: e.javaClass.simpleName}", e)
    }

    private fun hexId(): String {
        val r = SecureRandom()
        return (1..32).map { "0123456789abcdef"[r.nextInt(16)] }.joinToString("")
    }

    private fun dateString(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun sendTextFrame(out: java.io.OutputStream, text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val frame = ByteArrayOutputStream()
        frame.write(0x81)
        when {
            data.size < 126 -> frame.write(0x80 or data.size)
            data.size < 65536 -> {
                frame.write(0x80 or 126)
                frame.write((data.size shr 8) and 0xFF)
                frame.write(data.size and 0xFF)
            }
            else -> {
                frame.write(0x80 or 127)
                for (i in 7 downTo 0) frame.write((data.size.toLong() shr (8 * i)).toInt() and 0xFF)
            }
        }
        frame.write(mask)
        for (i in data.indices) frame.write(data[i].toInt() xor mask[i % 4].toInt())
        out.write(frame.toByteArray())
        out.flush()
    }

    private class FrameReader(private val input: InputStream) {
        private var buf = ByteArray(0)

        private fun fill(): Boolean {
            val chunk = ByteArray(8192)
            val n = input.read(chunk)
            if (n < 0) return false
            val merged = ByteArray(buf.size + n)
            System.arraycopy(buf, 0, merged, 0, buf.size)
            System.arraycopy(chunk, 0, merged, buf.size, n)
            buf = merged
            return true
        }

        fun take(n: Int): ByteArray {
            while (buf.size < n) if (!fill()) throw java.io.IOException("Edge 语音连接已关闭")
            val out = buf.copyOfRange(0, n)
            buf = buf.copyOfRange(n, buf.size)
            return out
        }

        fun readUntil(delim: ByteArray): ByteArray {
            while (true) {
                val idx = indexOf(buf, delim)
                if (idx >= 0) {
                    val end = idx + delim.size
                    val out = buf.copyOfRange(0, end)
                    buf = buf.copyOfRange(end, buf.size)
                    return out
                }
                if (!fill()) throw java.io.IOException("Edge 语音连接已关闭")
            }
        }

        fun readFrame(): Pair<Int, ByteArray> {
            val h = take(2)
            val opcode = h[0].toInt() and 0x0F
            var len = (h[1].toInt() and 0x7F).toLong()
            if (len == 126L) {
                val e = take(2)
                len = ((e[0].toInt() and 0xFF) shl 8 or (e[1].toInt() and 0xFF)).toLong()
            } else if (len == 127L) {
                val e = take(8)
                len = 0
                for (b in e) len = (len shl 8) or (b.toInt() and 0xFF).toLong()
            }
            val masked = (h[1].toInt() and 0x80) != 0
            val mask = if (masked) take(4) else null
            val payload = take(len.toInt())
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            return opcode to payload
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }

    // ---------- MP3 → PCM ----------

    private fun decodeMp3(seq: Int, mp3: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        // M5：超时放弃标记（在 try 外声明，供最后 return 用）。
        var timedOut = false
        try {
            extractor.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= mp3.size) return -1
                    val n = min(size.toLong(), mp3.size - position).toInt()
                    System.arraycopy(mp3, position.toInt(), buffer, offset, n)
                    return n
                }
                override fun getSize(): Long = mp3.size.toLong()
                override fun close() {}
            })
            var format: MediaFormat? = null
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return out
            // L7：核对 MediaFormat 里的采样率与声明的 24000 是否一致，不一致记日志告警。
            val wireRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull()
            if (wireRate != null && wireRate != SAMPLE_RATE) {
                Log.w(TAG, "Edge MP3 sample rate $wireRate != declared $SAMPLE_RATE, audio may sound wrong")
            }
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return out
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            // M5：坏 MP3 不能无限循环卡死语音线程：总耗时上限 10 秒 + 迭代上限双保险，
            // 超时即放弃该句（返回空，调用方按句跳过并记审计）。
            val decodeDeadline = System.currentTimeMillis() + 10_000L
            var iterations = 0
            while (!outputDone) {
                if (cancelled(seq)) break
                if (++iterations > 100_000 || System.currentTimeMillis() > decodeDeadline) {
                    Log.e(TAG, "decodeMp3 timeout after $iterations iterations, abandon sentence")
                    timedOut = true
                    break
                }
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.get(pcm)
                        out.add(pcm)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeMp3 failed", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
        // M5：超时放弃的句子返回空，不播残缺音频，调用方按句跳过。
        return if (timedOut) emptyList() else out
    }

    /** 与 speak() 同一套清洗（换行折成空格、去首尾空白），供 splitSentences 用。 */
    private fun cleanText(text: String): String = text.replace(Regex("[\r\n]+"), " ").trim()

    /**
     * 对外切句：与 speak() → runSynthesis() 内 split() 的切分完全一致（先清洗再切），
     * 供 TtsRouter 从失败句续播时切尾巴用；返回的下标从 0 计，与 onSentenceFailed
     * 回调的 failedIndex 对齐。
     */
    fun splitSentences(text: String): List<String> = split(cleanText(text))

    private fun split(pending: String): List<String> =
        pending.split(Regex("(?<=[。！？!?\\n])|(?<=\\. )"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.any { ch -> ch.isLetterOrDigit() } }
}
