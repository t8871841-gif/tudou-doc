package com.jarvis.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min

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

    @Volatile private var mySeq = 0
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var track: AudioTrack? = null

    fun speak(
        ctx: Context, text: String, voice: String = DEFAULT_VOICE,
        ratePct: Int = DEFAULT_RATE_PCT, pitchHz: Int = DEFAULT_PITCH_HZ,
        onStart: () -> Unit, onDone: (Boolean) -> Unit
    ) {
        stop()
        val seq = ++mySeq
        val clean = text.replace(Regex("[\r\n]+"), " ").trim()
        if (clean.isEmpty()) { onDone(false); return }
        io.execute { runSynthesis(ctx, seq, clean, voice, ratePct, pitchHz, onStart, onDone) }
    }

    /** 急停/打断：关掉在飞的 socket（唤醒读写阻塞立即抛异常）和播放轨道，不回调 onDone——调用方自己知道是主动打断。 */
    fun stop() {
        mySeq++
        runCatching { socket?.close() }
        socket = null
        val t = track
        track = null
        if (t != null) {
            runCatching { t.pause(); t.flush(); t.stop() }
            runCatching { t.release() }
        }
    }

    fun isSpeaking(): Boolean = track != null

    private fun cancelled(seq: Int) = seq != mySeq

    private fun runSynthesis(ctx: Context, seq: Int, text: String, voice: String, ratePct: Int, pitchHz: Int, onStart: () -> Unit, onDone: (Boolean) -> Unit) {
        val sentences = split(text)
        if (sentences.isEmpty()) { main.post { onDone(false) }; return }
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
        try {
            t.play()
            for ((i, sentence) in sentences.withIndex()) {
                if (cancelled(seq)) break
                try {
                    val mp3 = fetchMp3(seq, sentence, voice, ratePct, pitchHz)
                    if (cancelled(seq)) break
                    if (mp3.isEmpty()) {
                        if (i == 0) break // 第一句就没声音，当失败处理
                        continue
                    }
                    val pcms = decodeMp3(seq, mp3)
                    for (pcm in pcms) {
                        if (cancelled(seq)) break
                        if (pcm.isEmpty()) continue
                        if (!started) { started = true; main.post { if (!cancelled(seq)) onStart() } }
                        sawAny = true
                        t.write(pcm, 0, pcm.size)
                        totalFrames += pcm.size / 2 // PCM 16bit 单声道：2 字节一帧
                    }
                } catch (e: Exception) {
                    // 旧代码：中间一句取音/解码一抛，整个循环直接进 finally，
                    // 后面所有句子全丢，表现为“播到一半就断了”。现在只记一次、
                    // 跳过这一句继续播后面的；第一句就失败才当整体失败。
                    Log.e(TAG, "edge tts sentence $i failed", e)
                    if (i == 0) { abortedAt = i; break }
                    abortedAt = abortedAt ?: i
                    continue
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "edge tts failed", e)
        } finally {
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
            if (!cancelled(seq)) main.post {
                // 中间有句子失败被跳过时留一条审计，否则“少播一句”从界面上看不见原因。
                if (abortedAt != null) {
                    com.jarvis.app.AuditLog.append(ctx, "语音播报", "Edge 中间句子失败已跳过", "第 ${abortedAt + 1}/${sentences.size} 句")
                }
                onDone(sawAny)
            }
        }
    }

    // ---------- 协议部分：手写 TLS + WebSocket 帧，跟参考实现一致 ----------

    private fun fetchMp3(seq: Int, sentence: String, voice: String, ratePct: Int, pitchHz: Int): ByteArray {
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
            check(statusLine.contains("101")) { "Edge 语音握手失败：$statusLine" }

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
            sendTextFrame(out, ssmlMsg)

            val binAll = ByteArrayOutputStream()
            var gotAudio = false
            while (true) {
                if (cancelled(seq)) break
                val (opcode, payload) = reader.readFrame()
                when (opcode) {
                    0x2 -> if (payload.isNotEmpty()) { binAll.write(payload); gotAudio = true }
                    0x1 -> { val msg = String(payload, Charsets.UTF_8); if (msg.contains("turn.end")) break }
                    0x8 -> break
                }
            }
            if (!gotAudio) return ByteArray(0)
            return extractAudio(binAll.toByteArray())
        } catch (e: Exception) {
            if (!cancelled(seq)) Log.e(TAG, "fetchMp3 failed: ${e.message}")
            return ByteArray(0)
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
    private fun secMsGec(): String = EdgeGec.generate(TOKEN)

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
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return out
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (cancelled(seq)) break
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
        return out
    }

    private fun split(pending: String): List<String> =
        pending.split(Regex("(?<=[。！？!?\\n])|(?<=\\. )"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.any { ch -> ch.isLetterOrDigit() } }
}
