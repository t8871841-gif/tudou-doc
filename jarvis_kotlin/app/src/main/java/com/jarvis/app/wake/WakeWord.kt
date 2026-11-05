/*
 * Copyright 2026 KUAFUAI
 *
 * Licensed under the Apache License, Version 2.0 (the "License"), as modified
 * by the additional conditions in Part A of the Andee LICENSE; you may not use
 * this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Ported from Andee (net.kuafuai.andee.wake.WakeWord). Package changed; added pause()/resume()/isRunning() for Jarvis (see below) so the main ASR turn can take the microphone deterministically. All other logic is verbatim Andee.
 * This file was changed for Jarvis: see the note above (Apache 2.0 section 4(b)
 * / Andee LICENSE Part A (c)).
 */

package com.jarvis.app.wake

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * The wake word — whatever phrase the user enrolled, the hands-free way to do
 * what tapping the ball does. Nothing here knows what the phrase is.
 *
 * Entirely on device: [WakeMic] → [VoiceSegmenter] → [Mfcc] → [Dtw] against
 * the three takes the user enrolled. No network, no model file, no per-call
 * cost, and nothing recoverable as speech ever leaves the microphone thread.
 * The trade is accuracy — it recognises *this user's* phrase rather than the
 * phrase, and it will occasionally miss. That is the right trade for something
 * whose failure mode is "tap the ball instead".
 *
 * ## Who owns the microphone
 *
 * Never this class, when anything else wants it. The supervisor thread asks
 * [gate] a few times a second and closes the capture the moment it says no —
 * the conversation's own recorder must never fail because something was
 * listening for a wake word. The gate is also how "only while the screen is
 * on" is enforced, and why Andee does not hear itself: TTS speaking closes it
 * too.
 *
 * Idle when nothing is enrolled, deliberately: an unconfigured install should
 * not hold the microphone open, and the platform's green recording dot should
 * not appear for a feature the user has not set up.
 */
object WakeWord {

    private const val TAG = "Wake"

    /** How often the supervisor reconsiders. Also the worst-case wake latency added. */
    private const val POLL_MS = 100L

    /**
     * L3: permanent mic failure (permission denied / init failed) backoff —
     * same 60 s the old polling loop (4e16b61 `wakeTick`) waited before
     * retrying. `mic.start()` returning false means the device refused the
     * microphone, which will not heal in 2 s; retrying every 2 s spun
     * the supervisor thread on a dead mic. Transient "mic busy" is not told
     * apart here — `start()` returns only a Boolean — so every open failure
     * takes the long backoff.
     */
    private const val PERMANENT_RETRY_MS = 60_000L

    /** One wake per this long, so a doubled phrase can't start two turns. */
    private const val REFRACTORY_MS = 2_500L

    private var appContext: Context? = null
    private var gate: () -> Boolean = { false }
    private var onWake: () -> Unit = {}

    @Volatile private var running = false

    /** Held by enrollment, which needs the microphone to itself. */
    @Volatile private var paused = false

    /** Set on the capture thread, acted on by the supervisor — see [onSegment]. */
    @Volatile private var heard = false

    @Volatile private var lastWakeAt = 0L

    /** L3: earliest uptime (ms) a failed mic open may be retried; 0 = no backoff active. */
    @Volatile private var nextRetryAt = 0L

    /** L3: the current permanent-failure episode has already been reported once. */
    @Volatile private var micErrorNoted = false

    /**
     * L3: one-shot listener for a permanent mic failure, set via [init].
     * Invoked on the supervisor thread (like [onWake]); the caller must post
     * to the main thread before touching UI or agent state. Null = log only.
     */
    private var onMicError: ((String) -> Unit)? = null

    private var thread: Thread? = null
    private val mic = WakeMic { pcm, n -> segmenter.feed(pcm, n) }
    private val segmenter = VoiceSegmenter { seg -> onSegment(seg) }

    fun init(
        context: Context,
        gate: () -> Boolean,
        onWake: () -> Unit,
        onMicError: ((String) -> Unit)? = null,
    ) {
        this.appContext = context.applicationContext
        this.gate = gate
        this.onWake = onWake
        this.onMicError = onMicError
    }

    fun enrolled(): Boolean {
        val ctx = appContext ?: return false
        return WakeTemplates.enrolled(ctx)
    }

    /** Re-read the templates after enrollment changed them. */
    fun reload() {
        WakeTemplates.invalidate()
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            while (running) {
                supervise()
                Thread.sleep(POLL_MS)
            }
            mic.stop()
        }.also { it.name = "WakeWord"; it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        // L3: a fresh start() after stop() is a new episode — it may notify again.
        nextRetryAt = 0L
        micErrorNoted = false
        val t = thread
        thread = null
        mic.stop()
        runCatching { t?.join(500) }
    }

    fun isRunning(): Boolean = running

    /**
     * Jarvis adaptation (not in Andee): the main ASR turn takes the microphone.
     *
     * [pause] closes the capture synchronously — [WakeMic.stop] joins the
     * capture thread (≤ 500 ms) — so by the time it returns, no second
     * AudioRecord can be fighting the ASR session's recorder. The supervisor
     * thread keeps running and will not reopen the mic while [paused].
     * [resume] just clears the flag; the supervisor reopens the mic on its
     * next 100 ms poll, if the gate allows it.
     */
    fun pause() {
        paused = true
        mic.stop()
        segmenter.reset()
    }

    fun resume() {
        paused = false
    }

    /**
     * Enrollment takes the microphone. Wrapped rather than exposed as two calls
     * so a thrown recorder can't leave the listener switched off for good.
     */
    fun <T> withMicReleased(block: () -> T): T {
        paused = true
        mic.stop()
        segmenter.reset()
        return try {
            block()
        } finally {
            paused = false
        }
    }

    private fun supervise() {
        if (heard) {
            heard = false
            // Close before waking: the very next thing that happens is the ASR
            // path opening its own recorder, and two live captures inside one
            // app is a device-dependent coin toss we have no reason to flip.
            mic.stop()
            segmenter.reset()
            runCatching { onWake() }.onFailure { Log.w(TAG, "onWake failed", it) }
            return
        }
        val want = !paused && enrolled() && runCatching { gate() }.getOrDefault(false)
        if (want) {
            if (!mic.isOpen()) {
                // L3: permanent open failure (mic refused / init failed) backs
                // off 60 s before the next attempt — the old polling loop's
                // 60_000 + notify-once (4e16b61 wakeTick). While backing off
                // the loop just re-polls every POLL_MS; no 2 s spin, no retry.
                val now = SystemClock.uptimeMillis()
                if (now < nextRetryAt) return
                segmenter.reset()
                if (!mic.start()) {
                    nextRetryAt = now + PERMANENT_RETRY_MS
                    if (!micErrorNoted) {
                        micErrorNoted = true
                        val reason = mic.lastStartError ?: "未知原因"
                        val listener = onMicError
                        if (listener != null) {
                            runCatching { listener("唤醒监听停了：麦克风打不开（$reason），60 秒后重试") }
                                .onFailure { Log.w(TAG, "onMicError failed", it) }
                        } else {
                            Log.w(TAG, "wake mic failed to open ($reason); retrying in 60s")
                        }
                    }
                } else {
                    // Mic opened again: this failure episode is over; a later
                    // failure notifies once again (old loop cleared its flag
                    // when recognition produced a result).
                    nextRetryAt = 0L
                    micErrorNoted = false
                }
            }
        } else if (mic.isOpen()) {
            mic.stop()
            segmenter.reset()
        }
    }

    /** Capture thread. Must not block: the microphone stops being read while it runs. */
    private fun onSegment(pcm: ShortArray) {
        val ctx = appContext ?: return
        val store = WakeTemplates.load(ctx) ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastWakeAt < REFRACTORY_MS) return
        val feats = Mfcc.features(pcm)
        if (feats.isEmpty()) return
        val d = WakeTemplates.bestDistance(store, feats)
        if (d <= store.threshold) {
            Log.i(TAG, "wake: d=%.2f <= %.2f (%d ms)".format(d, store.threshold, pcm.size / 16))
            lastWakeAt = now
            heard = true
        } else if (d != Dtw.NO_MATCH) {
            // The one number worth having when the user says "it didn't hear
            // me": a near miss and an unrelated sentence look identical from
            // the outside, and only this tells them apart.
            Log.d(TAG, "no wake: d=%.2f > %.2f (%d ms)".format(d, store.threshold, pcm.size / 16))
        }
    }
}
