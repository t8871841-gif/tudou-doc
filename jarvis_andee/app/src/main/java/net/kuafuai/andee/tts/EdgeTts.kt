package net.kuafuai.andee.tts

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import net.kuafuai.andee.config.VoiceConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
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
 * Microsoft Edge neural TTS （云扬） — the Jarvis fork's default speaker.
 *
 * A line-for-line port of the Flutter Jarvis build's Edge client, which is
 * the implementation the owner has run on this device family: Microsoft
 * moved Read Aloud off `speech.platform.bing.com`, so the connection goes
 * to `api.msedgeservices.com` with the public client token carried as
 * `Ocp-Apim-Subscription-Key` and a `Sec-MS-GEC` proof — and the socket is
 * hand-rolled for the same reason the Dart one is: the service answers the
 * upgrade with `Connection: close`, which strict WebSocket clients reject
 * even though the upgrade itself succeeds (101).
 *
 * The wire gives MP3; [TtsEngine] deals in PCM. The MP3 for a sentence is
 * collected whole and decoded with MediaCodec — the same "one synthesis
 * per sentence, streamed to the listener as chunks" shape as [GoogleTts].
 *
 * No API key: the token below is the public client token shipped with the
 * open-source edge-tts project, not a credential of the owner's.
 */
class EdgeTts(
    @Suppress("unused") private val config: VoiceConfig,
    private val listener: TtsEngine.Listener,
) : TtsEngine {

    private val text = StringBuilder()

    @Volatile
    private var cancelled = false

    /** The in-flight socket, so [cancel] can cut a long answer short. */
    @Volatile
    private var socket: SSLSocket? = null

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EdgeTts").apply { isDaemon = true }
    }

    /** Like [GoogleTts], the session is declared up front: MP3 at 24 kHz in, PCM at 24 kHz out. */
    override fun open() {
        io.execute {
            if (cancelled) return@execute
            listener.onOpen(SAMPLE_RATE)
        }
    }

    override fun push(text: String) {
        synchronized(this) { this.text.append(text) }
    }

    override fun finish() {
        val pending = synchronized(this) { this.text.toString() }
        io.execute {
            runCatching { synth(pending) }.onFailure {
                if (cancelled) return@onFailure
                Log.e(TAG, "synth failed", it)
                listener.onError(it.message ?: "edge tts failed")
            }
        }
    }

    override fun cancel() {
        cancelled = true
        runCatching { socket?.close() }
    }

    private fun synth(pending: String) {
        if (cancelled) return
        val sentences = split(pending)
        if (sentences.isEmpty()) {
            listener.onDone()
            return
        }
        var sawAny = false
        for ((i, sentence) in sentences.withIndex()) {
            if (cancelled) return
            listener.onSentenceStart(sentence)
            val got = speakSentence(sentence)
            listener.onSentenceEnd(sentence)
            if (cancelled) return
            sawAny = sawAny || got
            if (i == 0 && !got) {
                listener.onError("Edge TTS returned no audio")
                return
            }
        }
        if (cancelled) return
        if (!sawAny) {
            listener.onError("Edge TTS returned no audio")
            return
        }
        listener.onDone()
    }

    /** One sentence: fetch its MP3, decode it, hand the PCM to the listener. */
    private fun speakSentence(sentence: String): Boolean {
        val mp3 = fetchMp3(sentence)
        if (mp3.isEmpty()) return false
        val pcms = decodeMp3(mp3)
        for (pcm in pcms) {
            if (cancelled) return true
            if (pcm.isNotEmpty()) listener.onAudio(pcm)
        }
        return pcms.isNotEmpty()
    }

    // ---- The Edge wire protocol (ported from the Flutter build) ---------

    private fun fetchMp3(sentence: String): ByteArray {
        val ssl = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        socket = ssl
        try {
            ssl.connect(InetSocketAddress(HOST, 443), 15_000)
            ssl.soTimeout = 45_000
            ssl.startHandshake()
            val reader = FrameReader(ssl.inputStream)
            val out = ssl.outputStream

            val key = android.util.Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, android.util.Base64.NO_WRAP)
            val path = "/tts/cognitiveservices/websocket/v1" +
                "?Ocp-Apim-Subscription-Key=$TOKEN" +
                "&ConnectionId=${hexId()}" +
                "&Sec-MS-GEC=${secMsGec()}" +
                "&Sec-MS-GEC-Version=1-$CHROMIUM_MAJOR.0.3650.75"
            val request = "GET $path HTTP/1.1\r\n" +
                "Host: $HOST\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
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
                ssml(sentence)
            sendTextFrame(out, ssmlMsg)

            val binAll = ByteArrayOutputStream()
            var gotAudio = false
            while (true) {
                if (cancelled) break
                val (opcode, payload) = reader.readFrame()
                when (opcode) {
                    0x2 -> if (payload.isNotEmpty()) {
                        binAll.write(payload)
                        gotAudio = true
                    }
                    0x1 -> {
                        val msg = String(payload, Charsets.UTF_8)
                        if (msg.contains("turn.end")) break
                    }
                    0x8 -> break
                }
            }
            if (!gotAudio) return ByteArray(0)
            return extractAudio(binAll.toByteArray())
        } finally {
            socket = null
            runCatching { ssl.close() }
        }
    }

    /** Splits the accumulated binary payload on `Path:audio` headers, as the Dart build does. */
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
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun ssml(text: String): String {
        val esc = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val rateStr = (if (RATE_PCT >= 0) "+" else "") + RATE_PCT + "%"
        val pitchStr = (if (PITCH_HZ >= 0) "+" else "") + PITCH_HZ + "Hz"
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
            "<voice name='$VOICE'><prosody rate='$rateStr' pitch='$pitchStr'>$esc</prosody></voice></speak>"
    }

    /** Sec-MS-GEC: SHA-256 (upper hex) of Windows ticks rounded down to 5 minutes, plus the token. */
    private fun secMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000 + 11644473600L
        ticks -= ticks % 300
        val winTicks = ticks * 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$winTicks$TOKEN".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
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

    /** One masked client-to-server text frame. */
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

    /** Buffered socket reader with the exact-read semantics the frame codec needs. */
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
            while (buf.size < n) {
                if (!fill()) throw java.io.IOException("Edge 语音连接已关闭")
            }
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

        /** Returns (opcode, payload); server frames are unmasked, but tolerate a mask anyway. */
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
            if (mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }
            return opcode to payload
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }
    }

    // ---- MP3 → PCM --------------------------------------------------------

    private fun decodeMp3(mp3: ByteArray): List<ByteArray> {
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
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return out
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return out
            val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                SAMPLE_RATE
            }
            if (rate != SAMPLE_RATE) {
                Log.e(TAG, "Edge MP3 is ${rate}Hz but the player was opened at ${SAMPLE_RATE}Hz")
            }
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (cancelled) break
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
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
        return out
    }

    /** Same sentence split as [GoogleTts]: the terminator stays on its sentence. */
    private fun split(pending: String): List<String> =
        pending.split(Regex("(?<=[。！？!?\\n])|(?<=\\. )"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.any { ch -> ch.isLetterOrDigit() } }

    companion object {
        private const val TAG = "EdgeTts"

        private const val HOST = "api.msedgeservices.com"

        /** Public client token shipped with the open-source edge-tts project. */
        private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

        private const val CHROMIUM_MAJOR = "143"

        private const val VOICE = "zh-CN-YunyangNeural"

        /** The Flutter build's resting pace: rate 0.48 on its −50%…+100% scale. */
        private const val RATE_PCT = -2

        private const val PITCH_HZ = 0

        /** Signed 16-bit little-endian mono, the rate declared in [open]. */
        private const val SAMPLE_RATE = 24_000

        private val HEADER_END = "\r\n\r\n".toByteArray(Charsets.UTF_8)
    }
}
