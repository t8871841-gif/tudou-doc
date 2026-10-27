package com.moss.app.tts

// Moss: Edge neural voice behind the TtsEngine interface. Protocol + MP3 decode
// ported from Jarvis (same author, same device): hand-rolled TLS + WebSocket
// frames, Sec-MS-GEC v2 (see EdgeGec), MP3 decoded to PCM via MediaCodec.
// No API key, no account. The driver (TtsController) owns playback; this
// engine only produces PCM, one utterance per instance.

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min

class EdgeTtsEngine(
    private val listener: TtsEngine.Listener,
    private val voice: String = EdgeVoices.DEFAULT_ID,
) : TtsEngine {

    companion object {
        private const val TAG = "EdgeTts"
        private const val HOST = "speech.platform.bing.com"

        /** Openly published client token from the edge-tts project, not a private credential. */
        private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val CHROMIUM_MAJOR = "143"

        /** Resting rate from the Jarvis reference build, on the -50%..+100% scale. */
        private const val RATE_PCT = -2
        private const val PITCH_HZ = 0

        /** The requested output format is fixed 24 kHz, so the PCM rate is known up front. */
        const val SAMPLE_RATE = 24_000
    }

    private val HEADER_END = "\r\n\r\n".toByteArray(Charsets.UTF_8)

    @Volatile
    private var cancelled = false

    /** Set once [onError] has fired: no [onDone] may follow it. */
    @Volatile
    private var terminal = false

    @Volatile
    private var socket: SSLSocket? = null

    private val finishMarker = Any()
    private val queue: LinkedBlockingQueue<Any> = LinkedBlockingQueue()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EdgeTtsEngine").apply { isDaemon = true }
    }

    @Volatile
    private var started = false

    override fun open() {
        if (started) return
        started = true
        listener.onOpen(SAMPLE_RATE)
        worker.execute { runQueue() }
    }

    override fun push(text: String) {
        if (cancelled || terminal) return
        queue.offer(text)
    }

    override fun finish() {
        if (cancelled || terminal) return
        queue.offer(finishMarker)
    }

    override fun cancel() {
        if (cancelled) return
        cancelled = true
        runCatching { socket?.close() }
        socket = null
        worker.shutdownNow()
        runCatching { listener.onClose(TtsEngine.CloseReason.USER) }
    }

    private fun runQueue() {
        try {
            while (true) {
                val item = queue.take()
                if (cancelled) return
                if (item === finishMarker) {
                    if (!terminal) listener.onDone()
                    return
                }
                processText(item as String)
                if (terminal) return
            }
        } catch (_: InterruptedException) {
            // cancel() shut the worker down; no callbacks after a cancel.
        }
    }

    private fun processText(text: String) {
        val sentences = split(text)
        if (sentences.isEmpty()) return
        for ((i, sentence) in sentences.withIndex()) {
            if (cancelled || terminal) return
            val mp3 = fetchMp3(sentence)
            if (cancelled || terminal) return
            if (mp3.isEmpty()) {
                if (i == 0) {
                    terminal = true
                    listener.onError("Edge 语音取音失败")
                    return
                }
                Log.e(TAG, "edge tts sentence $i failed, skipped")
                continue
            }
            listener.onSentenceStart(sentence)
            for (pcm in decodeMp3(mp3)) {
                if (cancelled || terminal) return
                if (pcm.isNotEmpty()) listener.onAudio(pcm)
            }
            listener.onSentenceEnd(sentence)
        }
    }

    // ---------- Protocol: hand-rolled TLS + WebSocket frames ----------

    private fun fetchMp3(sentence: String): ByteArray {
        val ssl = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        socket = ssl
        try {
            ssl.connect(InetSocketAddress(HOST, 443), 15_000)
            ssl.soTimeout = 45_000
            ssl.startHandshake()
            val reader = FrameReader(ssl.inputStream)
            val out = ssl.outputStream

            val key = android.util.Base64.encodeToString(
                ByteArray(16).also { SecureRandom().nextBytes(it) },
                android.util.Base64.NO_WRAP,
            )
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
                ssml(sentence, voice, RATE_PCT, PITCH_HZ)
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
        } catch (e: Exception) {
            if (!cancelled) Log.e(TAG, "fetchMp3 failed: ${e.message}")
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

    /** Sec-MS-GEC v2: "CS64-SHA256", see EdgeGec (ported from edge-tts 7.3.1 drm.py). */
    private fun secMsGec(): String = EdgeGec.generate(TOKEN)

    private fun hexId(): String {
        val r = SecureRandom()
        return (1..32).map { "0123456789abcdef"[r.nextInt(16)] }.joinToString("")
    }

    private fun dateString(): String {
        val fmt = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US,
        )
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun sendTextFrame(out: OutputStream, text: String) {
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
            if (mask != null) for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
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

    // ---------- MP3 -> PCM ----------

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
