package com.moss.app.tts

/**
 * One utterance, whichever backend speaks it.
 *
 * The call sequence is fixed: [open], then [push] with the text (once, from
 * the driver's `onOpen`) — for a backend that synthesises on demand, however
 * many times — then [finish]. Audio comes back through [Listener.onAudio] as
 * raw 16-bit little-endian PCM.
 *
 * **What a backend cannot promise is when the text arrives relative to the
 * network call.** Edge's output format is fixed at 24 kHz, so the rate is
 * known before any text arrives. [Listener.onOpen] is therefore called
 * *before* [push] by construction, and the driver pushes from that callback.
 */
interface TtsEngine {

    /** Why a session ended. Narrated to nobody; used to describe a dead session. */
    enum class CloseReason { USER, SERVER, ERROR }

    interface Listener {
        /**
         * The session is up and [sampleRate] is what the PCM will be in.
         *
         * Called once, before any [onAudio], and — for every backend — before
         * the driver can push text, because the driver pushes from this
         * callback.
         */
        fun onOpen(sampleRate: Int)

        fun onAudio(pcm: ByteArray)

        fun onSentenceStart(text: String)

        fun onSentenceEnd(text: String)

        /** No more audio is coming. The driver keeps its own drain clock. */
        fun onDone()

        fun onError(msg: String)

        fun onClose(reason: CloseReason)
    }

    fun open()

    fun push(text: String)

    fun finish()

    fun cancel()
}
