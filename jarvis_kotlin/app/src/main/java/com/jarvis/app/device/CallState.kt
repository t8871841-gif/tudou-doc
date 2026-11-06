/*
 * Copyright 2026 KUAFUAI
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * Ported from Andee (net.kuafuai.andee.device.CallState) — the "call is
 * happening right now" flag every mic gate reads.
 *
 * 检测方式与 Andee 不同：Andee 靠 NotificationListenerService 认出来电通知
 * （能覆盖微信语音等 VoIP 通话）；Jarvis 没有通知监听服务，本移植改走
 * TelephonyManager 通话状态回调，只覆盖运营商电话来电 / 去电 / 通话中。
 * VoIP 通话（微信语音等）不在本移植覆盖范围——那是另一路（通知监听），未做。
 */
package com.jarvis.app.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 通话状态：来电响铃 / 通话中 / 已挂断。
 *
 * 对外只暴露 [isInCall] / [isActive] 查询与 [Listener] 回调，不把 Telephony
 * 的细节（TelephonyManager / PhoneStateListener / TelephonyCallback）漏到各处。
 * 来电时各方自己停手：EdgeTts 停播并释放音频焦点，JarvisAgent 停掉当前收音
 * 轮次与播报；挂断后不自动续播、不自动续听，由 Agent 自己恢复待命。
 *
 * 需要权限 `android.permission.READ_PHONE_STATE`（Manifest 里加，见汇报）。
 * 权限没给时 [start] 只记日志、不注册、不抛异常；[permissionMissing] 为 true，
 * 播报/收音不会因来电自动停——原因写在日志里，不崩。权限补上后下次 [start]
 * 会重试注册。
 */
object CallState {
    private const val TAG = "CallState"

    /** 通话状态变化回调；在主线程回调。 */
    fun interface Listener {
        fun onCallStateChanged(inCall: Boolean)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var inCall = false
    @Volatile private var started = false

    /**
     * 通话门超时复位：系统送了 RINGING/OFFHOOK 却再没送 IDLE 时（回调丢失、
     * 双卡/Flyme 回调异常），inCall 会一直卡 true、播报/收音永久被门挡住。
     * inCall=true 超过此时长且无状态变化，自动复位为 false。10 分钟：正常通话
     * 也可能超过 10 分钟，但通话中途本来就不会播报，复位后下一次来电回调会
     * 重新把门关上；卡住的假 true 则能自己恢复。
     */
    private const val IN_CALL_TIMEOUT_MS = 10L * 60L * 1000L

    /** inCall 变为 true 的时刻（SystemClock.elapsedRealtime）；不在通话时为 0。 */
    @Volatile private var inCallSinceMs = 0L

    /** 超时复位检查：只在主线程跑；inCall 已变 false 或未超时时什么都不做。 */
    private val inCallTimeoutCheck = Runnable {
        if (!inCall) return@Runnable
        val elapsed = SystemClock.elapsedRealtime() - inCallSinceMs
        if (elapsed < IN_CALL_TIMEOUT_MS) {
            main.postDelayed(inCallTimeoutCheck, IN_CALL_TIMEOUT_MS - elapsed)
            return@Runnable
        }
        inCall = false
        inCallSinceMs = 0L
        Log.w(TAG, "inCall stuck true for ${elapsed}ms (> ${IN_CALL_TIMEOUT_MS}ms), auto reset to false")
        main.post { listeners.forEach { l -> runCatching { l.onCallStateChanged(false) } } }
    }

    private fun scheduleInCallTimeout() {
        main.removeCallbacks(inCallTimeoutCheck)
        main.postDelayed(inCallTimeoutCheck, IN_CALL_TIMEOUT_MS)
    }

    private fun cancelInCallTimeout() {
        main.removeCallbacks(inCallTimeoutCheck)
        inCallSinceMs = 0L
    }

    /** READ_PHONE_STATE 没给（或注册被系统拒绝）时为 true：监听未生效。 */
    @Volatile var permissionMissing = false
        private set

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null

    /** 与 Andee 同名：通话进行中（响铃或通话中）。 */
    val isActive: Boolean get() = inCall

    /** 通话进行中（响铃或通话中）。 */
    fun isInCall(): Boolean = inCall

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * 注册通话监听；幂等，多次调用只注册一次。
     * 无权限 / 取不到 TelephonyManager 时只记日志，不抛异常。
     */
    fun start(ctx: Context) {
        if (started) return
        val app = ctx.applicationContext
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionMissing = true
            Log.w(TAG, "READ_PHONE_STATE not granted: call detection disabled, " +
                "playback/recording will NOT auto-stop on incoming calls")
            return
        }
        permissionMissing = false
        val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm == null) {
            Log.w(TAG, "TelephonyManager unavailable, call detection disabled")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = updateState(state)
                }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(app), cb)
                telephonyCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = updateState(state)
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                phoneStateListener = l
            }
        } catch (e: SecurityException) {
            permissionMissing = true
            Log.w(TAG, "call listener register failed (permission?): ${e.message}")
            return
        }
        // 启动核对：注册回调只收之后的变化，注册前已在通话中时回调不会补发，
        // 主动读一次当前通话状态做初始核对，避免门一开始就是错的。
        // 无权限时上面已 return，这里读不到也不会抛到调用方（fail-open：恒 false）。
        try {
            val initialState = tm.callState
            if (initialState == TelephonyManager.CALL_STATE_RINGING ||
                initialState == TelephonyManager.CALL_STATE_OFFHOOK
            ) {
                updateState(initialState)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "initial callState read failed (permission?): ${e.message}")
        }
        telephonyManager = tm
        started = true
        Log.i(TAG, "call listener started")
    }

    /** 注销通话监听；幂等。 */
    fun stop() {
        if (!started) return
        started = false
        cancelInCallTimeout()
        val tm = telephonyManager
        telephonyManager = null
        telephonyCallback?.let { cb ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { tm?.unregisterTelephonyCallback(cb) }
            }
        }
        telephonyCallback = null
        @Suppress("DEPRECATION")
        phoneStateListener?.let { l ->
            runCatching { tm?.listen(l, PhoneStateListener.LISTEN_NONE) }
        }
        phoneStateListener = null
    }

    /** 响铃 / 通话中 = 在通话；空闲 = 不在通话。来电号码不记录、不透传。 */
    private fun updateState(state: Int) {
        val nowInCall = state == TelephonyManager.CALL_STATE_RINGING ||
            state == TelephonyManager.CALL_STATE_OFFHOOK
        if (nowInCall == inCall) return
        inCall = nowInCall
        if (nowInCall) {
            inCallSinceMs = SystemClock.elapsedRealtime()
            scheduleInCallTimeout()
        } else {
            cancelInCallTimeout()
        }
        Log.i(TAG, "call state changed: inCall=$nowInCall (telephony state=$state)")
        main.post { listeners.forEach { l -> runCatching { l.onCallStateChanged(nowInCall) } } }
    }
}
