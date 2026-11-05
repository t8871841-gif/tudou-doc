/*
 * Copyright 2026 KUAFUAI (wake-word core) — see the individual files in this
 * package for their Andee origin notices.
 *
 * This file is Jarvis-original glue: it wires Andee's wake-word stack
 * (WakeWord / WakeMic / VoiceSegmenter / Mfcc / Dtw / WakeTemplates) into
 * Jarvis's agent state. Licensed under the Apache License, Version 2.0.
 */

package com.jarvis.app.wake

import android.content.Context
import android.os.PowerManager
import com.jarvis.app.JarvisAgent
import com.jarvis.app.JarvisPrefs
import com.jarvis.app.device.CallState

/**
 * The only entry point into the wake-word subsystem. Everything else in
 * `com.jarvis.app.wake` is the ported Andee stack and is never touched
 * directly by the rest of Jarvis.
 *
 * Pipeline (all on-device, no model file, no network):
 * `WakeMic` (16 kHz mono capture) → `VoiceSegmenter` (energy VAD, 300 ms
 * pre-roll ring, 300 ms of quiet ends a segment) → `Mfcc` (12 coeffs / 10 ms,
 * CMVN) → `Dtw` against the enrolled takes in `WakeTemplates`.
 *
 * ## Gate — when it listens at all
 *
 * The supervisor thread asks [gate] every 100 ms and closes the capture the
 * moment it says no:
 * - screen on (`PowerManager.isInteractive`) — never holds the mic while the
 *   screen is off;
 * - agent idle (`JarvisAgent.currentState() == "idle"`) — this is also the
 *   "don't hear ourselves" rule, because Jarvis sets state to `"speaking"`
 *   for the whole TTS播报, so TTS播报 closes the gate;
 * - the wake switch on (`JarvisPrefs.isWakeEnabled`);
 * - a wake phrase enrolled (`WakeTemplates` non-empty) — an unconfigured
 *   install never holds the microphone open.
 *
 * ## Microphone ownership — never two AudioRecords
 *
 * The wake listener owns the mic only while idle. The moment a real ASR turn
 * starts, the agent must call [pause] (it returns only after the capture
 * thread is joined, so the mic is deterministically free before the ASR
 * session opens its own recorder) and call [resume] when the turn is over
 * and the agent is idle again. The 190 MB sherpa recogniser is untouched by
 * this: it only ever runs *after* a wake hit, for the actual utterance.
 *
 * ## Threading
 *
 * [onWake] is invoked on the WakeWord supervisor thread, **not** the main
 * thread. The caller must post to the main thread before touching agent
 * state (e.g. `main.post { beginListening(...) }`). The mic is already
 * closed and the segmenter reset before [onWake] fires, so the ASR path can
 * open its recorder immediately. The same thread rule holds for the
 * `onMicError` callback passed to [start].
 *
 * ## Refractory
 *
 * One wake per 2500 ms ([WakeWord.REFRACTORY_MS] in the ported file), so a
 * doubled phrase cannot start two turns.
 */
object WakeWordEngine {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var started = false

    /**
     * Call once, from `JarvisAgent.start()` (or `MainActivity.onCreate`),
     * before [start]. Only keeps the application context.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Start the supervisor loop. Safe to call repeatedly: the first call
     * starts it, later calls just replace the [onWake] callback and resume
     * listening if it was paused for an ASR turn.
     *
     * Does nothing until `init()` has run. If no wake phrase is enrolled yet
     * the supervisor idles without ever opening the microphone.
     *
     * L3: [onMicError] is the one-shot notice for a permanent mic failure
     * (permission denied / init failed — the supervisor then backs off 60 s
     * before retrying). It runs on the WakeWord supervisor thread, like
     * [onWake]: post to the main thread before touching UI or agent state
     * (e.g. `main.post { showFailureBubble(msg) }`). Null = log only.
     * Wired in `JarvisAgent.start()`.
     */
    fun start(onWake: () -> Unit, onMicError: ((String) -> Unit)? = null) {
        val ctx = appContext ?: return
        if (started) {
            WakeWord.init(ctx, ::gate, onWake, onMicError)
            WakeWord.resume()
            return
        }
        WakeWord.init(ctx, ::gate, onWake, onMicError)
        WakeWord.start()
        started = true
    }

    /** Stop the supervisor loop entirely and release the microphone. */
    fun stop() {
        started = false
        WakeWord.stop()
    }

    fun isRunning(): Boolean = started && WakeWord.isRunning()

    /**
     * Hand the microphone to the main ASR turn. Call at the top of
     * `beginListening()` (and anywhere else that opens a recorder, e.g. the
     * barge-in watch is exempt — it *is* an ASR listener, but it opens its
     * own recorder while speaking, when the gate is already closed; it must
     * still not run concurrently with the wake mic, so pause before it too
     * if it ever runs while idle).
     * Synchronous: returns only after the capture is closed.
     */
    fun pause() {
        WakeWord.pause()
    }

    /**
     * Give the microphone back to the wake listener. Call from every path
     * that returns the agent to idle (today: every `maybeStartWakeLoop()`
     * call site). If the gate is closed (screen off / speaking / switch off)
     * the supervisor simply stays shut until the gate opens.
     */
    fun resume() {
        if (!started) return
        WakeWord.resume()
    }

    /** True once the user has enrolled a wake phrase (templates on disk). */
    fun enrolled(): Boolean {
        val ctx = appContext ?: return false
        return WakeTemplates.enrolled(ctx)
    }

    /**
     * Re-read the templates from disk — call after enrollment changed them
     * (a future enrollment UI saves via `WakeTemplates.save()` then calls
     * this). Next `load()` re-reads the file.
     */
    fun reload() {
        WakeTemplates.invalidate()
    }

    /**
     * Lend the microphone to enrollment (which records its own takes).
     * Wraps the block so a thrown recorder cannot leave the listener off.
     * The caller must hold the mic through this — see `WakeWord.withMicReleased`.
     */
    fun <T> withMicReleased(block: () -> T): T = WakeWord.withMicReleased(block)

    /**
     * The gate, polled every 100 ms by the ported supervisor. Everything
     * here reads only public Jarvis APIs, so no existing file needs a new
     * method for the wake word: `currentState()` is already public, and
     * `"speaking"` is the state Jarvis holds for the whole TTS播报.
     */
    private fun gate(): Boolean {
        val ctx = appContext ?: return false
        if (!JarvisPrefs.isWakeEnabled(ctx)) return false
        if (JarvisAgent.currentState() != "idle") return false
        // 通话中不监听：hardResetToIdle 会把状态置回 idle，只看状态会在通话里误开麦，
        // 所以这里再加一道通话检查（移植自 Andee device/CallState）。
        if (CallState.isInCall()) return false
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return false
        return true
    }
}
