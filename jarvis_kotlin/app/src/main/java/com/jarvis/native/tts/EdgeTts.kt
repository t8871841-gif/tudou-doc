package com.jarvis.native.tts

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 微软 Edge 朗读(Read Aloud) TTS 的免费 WebSocket 协议重新实现（无需 Key）。
 *
 * 说明：需求文档原本要求直接从仓库 tudou-doc 的历史提交 ce22c12 取回已验证的
 * `tts/EdgeTts.kt` 直接复用——但本次交付环境没有该仓库的访问权限（无公网 / 无 git 凭据），
 * 取不到那份代码，所以这里按 Edge Read Aloud 公开协议重写了一份功能等价的实现，
 * 未在真机上跑过，首次联调如果协议细节（header 格式、音频分片边界）对不上，优先在这个文件里改。
 *
 * 协议要点：
 *  1. 建立到 wss://speech.platform.bing.com/.../synthesize/readaloud/edge/v1 的 WebSocket；
 *  2. 先发一条 speech.config 文本消息，再发一条 ssml 文本消息；
 *  3. 服务端推回若干二进制帧（帧头是"Path:audio"的文本头 + 紧跟着的 mp3 二进制数据）；
 *  4. 收到文本消息里带 "Path:turn.end" 即合成结束，关闭连接。
 */
object EdgeTts {
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
    private const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"

    private val client by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接，不设读超时
            .build()
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var mySeq = 0

    /**
     * 合成并播放一段文本。onStart 在音频真正开始播放时回调（用于球变"播报绿"）；
     * onDone(success) 在播放结束 / 出错 / 被 stop() 打断后回调一次。
     */
    fun speak(ctx: Context, text: String, voice: String = DEFAULT_VOICE, onStart: () -> Unit, onDone: (Boolean) -> Unit) {
        stop() // 保证同一时刻只有一个播报在跑
        val seq = ++mySeq
        val cleanText = text.replace(Regex("[\\r\\n]+"), " ").trim()
        if (cleanText.isEmpty()) {
            onDone(false)
            return
        }
        val audioBuf = java.io.ByteArrayOutputStream()
        val req = Request.Builder().url(WSS_URL)
            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .build()
        val socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (seq != mySeq) { webSocket.close(1000, null); return }
                webSocket.send(buildConfigMessage())
                webSocket.send(buildSsmlMessage(cleanText, voice))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (seq != mySeq) return
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, null)
                    finish(ctx, seq, audioBuf.toByteArray(), onStart, onDone)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (seq != mySeq) return
                // 帧结构：前 2 字节大端 = 头部长度；头部是形如
                // "X-RequestId:...\r\nContent-Type:audio/mpeg\r\nPath:audio\r\n\r\n" 的文本；
                // 剩余字节才是 mp3 音频数据。
                val arr = bytes.toByteArray()
                if (arr.size < 2) return
                val headerLen = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                val headerEnd = 2 + headerLen
                if (headerEnd > arr.size) return
                val header = String(arr, 2, headerLen, Charsets.UTF_8)
                if (header.contains("Path:audio")) {
                    audioBuf.write(arr, headerEnd, arr.size - headerEnd)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (seq != mySeq) return
                main.post { onDone(false) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // 正常流程下 finish() 已经在 onMessage(turn.end) 里处理过了；
                // 这里兜底一次，避免个别情况下服务端直接关闭而没发 turn.end。
                if (seq == mySeq && audioBuf.size() > 0 && player == null) {
                    finish(ctx, seq, audioBuf.toByteArray(), onStart, onDone)
                }
            }
        })
        ws = socket
    }

    private fun finish(
        ctx: Context, seq: Int, audio: ByteArray,
        onStart: () -> Unit, onDone: (Boolean) -> Unit
    ) {
        if (seq != mySeq) return
        if (audio.isEmpty()) {
            main.post { onDone(false) }
            return
        }
        try {
            val f = File(ctx.cacheDir, "jarvis_tts_$seq.mp3")
            FileOutputStream(f).use { it.write(audio) }
            main.post {
                if (seq != mySeq) return@post
                val mp = MediaPlayer()
                player = mp
                try {
                    mp.setDataSource(f.absolutePath)
                    mp.setOnPreparedListener {
                        if (seq != mySeq) { mp.release(); return@setOnPreparedListener }
                        onStart()
                        mp.start()
                    }
                    mp.setOnCompletionListener {
                        if (seq == mySeq) { player = null; onDone(true) }
                        mp.release()
                        f.delete()
                    }
                    mp.setOnErrorListener { _, _, _ ->
                        if (seq == mySeq) { player = null; onDone(false) }
                        true
                    }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    player = null
                    onDone(false)
                }
            }
        } catch (e: Exception) {
            main.post { onDone(false) }
        }
    }

    /** 打断播报：急停、或检测到用户开口说话时调用。不回调 onDone——调用方自己知道是主动打断的。 */
    fun stop() {
        mySeq++ // 让所有还在飞的网络/播放回调失效
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        main.post {
            try { player?.stop() } catch (_: Exception) {}
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }

    fun isSpeaking(): Boolean = player != null

    private fun buildConfigMessage(): String {
        val ts = timestamp()
        return "X-Timestamp:$ts\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
    }

    private fun buildSsmlMessage(text: String, voice: String): String {
        val ts = timestamp()
        val reqId = UUID.randomUUID().toString().replace("-", "")
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
            "<voice name='$voice'><prosody rate='0%' pitch='0%'>$escaped</prosody></voice></speak>"
        return "X-RequestId:$reqId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:$ts\r\n" +
            "Path:ssml\r\n\r\n" + ssml
    }

    private fun timestamp(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date())
    }
}
