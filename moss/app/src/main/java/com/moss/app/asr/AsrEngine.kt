package com.moss.app.asr

/**
 * One speech-recognition session, whichever backend is behind it.
 *
 * The shape is a streaming session's: open it, push PCM into it as it
 * arrives, ask it to finish, wait for the final line. The interface exists
 * rather than a branch inside [AsrController] so the caller never knows
 * which backend is behind it.
 *
 * **The caller's silence rule reads [Listener.onPartial] and nothing else**:
 * the engine keeps reporting while the user talks, so a window that exceeds
 * its reporting cadence needs to know nothing about the room or the
 * microphone.
 */
interface AsrEngine {

    /**
     * How long [waitFinished] should be given — **this engine's own doing
     * included**.
     *
     * Asked of the engine rather than fixed by the caller, because how a
     * final arrives is structural to the backend: a recogniser that ends
     * turns on its own voice detector has usually already answered by the
     * time it is asked, while one that only produces a final in response to
     * being told the utterance is over needs the wait to cover the round
     * trip. One shared number cannot be right for both shapes.
     */
    val finalWaitMs: Long

    /**
     * How long a silence the caller should let pass before ending the turn
     * itself.
     *
     * **Two meanings, and which one applies depends on the engine.** For one
     * that never announces an end of turn this *is* what ends the turn — a
     * pause is all there is. For one that does (the system recogniser ends
     * turns on its own voice detection), this is the backstop for when that
     * report does not arrive, and the only thing that closes a microphone
     * that has gone quiet.
     *
     * Per-engine for the same reason as [finalWaitMs]: a single shared value
     * cannot be right for every backend.
     */
    val silenceMs: Long

    /**
     * Whether this engine captures the microphone itself.
     *
     * False for engines that take pushed PCM ([sendAudio]); true for the
     * system recogniser, which owns the microphone and decides on its own
     * when an utterance ends. When true, the caller must not open its own
     * capture alongside it: two captures on one microphone starve each
     * other, and the pushed PCM is discarded anyway. The caller tracks the
     * turn's liveness itself instead of reading it off its own stream.
     */
    val ownsMicrophone: Boolean
        get() = false

    interface Listener {
        /**
         * The engine is connected and can hear.
         *
         * **The caller's silence clock must not run before this.** The
         * microphone opens at one moment and the recogniser's session comes up
         * at another, and on this device they are 1.4–1.9 s apart — against a
         * silence window of 1.5 s. With the clock counting from the microphone,
         * the deadline landed on top of the handshake, and four measured turns
         * were cut off at 1.5 s with the first result arriving 100–350 ms later.
         * Whether any given turn survived came down to tens of milliseconds of
         * connection luck.
         *
         * It does not cover for a broken engine: audio is still captured and
         * buffered from the moment the microphone opens, and one that never
         * connects reports [onError] and the turn ends that way.
         */
        fun onReady() {}

        /**
         * What has been heard so far, good enough to draw but not to act on.
         *
         * **The engine calls this while the user is still talking**, and
         * `AsrController` depends on it: the silence watchdog that closes the
         * microphone is fed from these calls and from nothing else.
         */
        fun onPartial(text: String)

        /** The finished transcript. Empty means "there was nothing to hear". */
        fun onFinal(text: String)

        /**
         * The turn ended with no transcript, and the engine knows why.
         *
         * Default is [onFinal] with an empty line, so a listener that only
         * settles on finals (a meeting segment) keeps today's behaviour
         * without noticing. A listener that talks to the user overrides
         * this to say the cause out loud: an empty end that stays silent
         * reads exactly like a dead tap, and the cause is the only thing
         * that tells the two apart.
         */
        fun onEmptyEnd(cause: String) {
            onFinal("")
        }

        fun onError(msg: String)

        /** The upstream connection ended. */
        fun onClosed()
    }

    /**
     * The turn is ending, and **how** it is ending.
     *
     * [bySilence] is true when the caller's own silence rule expired — see
     * [silenceMs] — and false when the user ended it by hand or something else
     * did. The two are not interchangeable to an engine: a silence-ended turn
     * has, by construction, already been quiet for [silenceMs], which is far
     * longer than any recogniser needs to settle what it heard, so the last
     * results are already in hand. A hand-ended turn has no such guarantee —
     * the user may have stopped mid-word a moment ago.
     *
     * Optional to implement: an engine with nothing to gain ignores it, which
     * is why it carries a default body.
     */
    fun endOfTurn(bySilence: Boolean) = Unit

    fun start(sampleRate: Int, channels: Int)

    /**
     * One block of 16-bit little-endian PCM, or the end-of-utterance marker
     * when [isLast] — in which case [pcm] is ignored.
     *
     * Called on the capture thread; must not block.
     */
    fun sendAudio(pcm: ByteArray, isLast: Boolean)

    /**
     * Block until the final line has been delivered, or [timeoutMs] passes.
     *
     * The timeout is the caller's because how long a final takes to arrive
     * depends on the backend behind the session.
     */
    fun waitFinished(timeoutMs: Long): Boolean

    fun close()
}
