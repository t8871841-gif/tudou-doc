package com.jarvis.jarvis_app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.example.jarvis.JarvisAccessibilityService
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.Locale
import kotlin.math.roundToInt

/** Dart ↔ 原生系统能力桥（channel: jarvis/system）。入口事件（默认助手/磁贴/提醒/晨报）也从这里投递。 */
class SystemBridge(private val activity: Activity) {
    private var channel: MethodChannel? = null
    private var ready = false
    private var pendingLaunch: Map<String, Any?>? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsResult: MethodChannel.Result? = null

    fun register(engine: FlutterEngine) {
        val ch = MethodChannel(engine.dartExecutor.binaryMessenger, "jarvis/system")
        channel = ch
        OrbController.eventSink = { type ->
            activity.runOnUiThread { channel?.invokeMethod("onOrbEvent", mapOf("type" to type)) }
        }
        ch.setMethodCallHandler { call, result ->
            try {
                handle(call, result)
            } catch (e: Exception) {
                result.error("NATIVE_ERROR", e.message, null)
            }
        }
    }

    fun release() {
        OrbController.dartAlive = false
        OrbController.eventSink = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }

    // ---------- 入口事件 ----------

    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val extra = intent.getStringExtra(MainActivity.EXTRA_ACTION)
        val action = when {
            extra != null -> extra
            intent.action == Intent.ACTION_ASSIST || intent.action == "android.intent.action.VOICE_COMMAND" -> "assist"
            else -> null
        } ?: return
        val text = intent.getStringExtra(MainActivity.EXTRA_TEXT) ?: ""
        // 取走：避免旋转屏幕 / 重建 Activity 时重复触发
        intent.removeExtra(MainActivity.EXTRA_ACTION)
        intent.removeExtra(MainActivity.EXTRA_TEXT)
        intent.action = Intent.ACTION_MAIN
        val m = mapOf<String, Any?>("action" to action, "text" to text)
        if (ready) channel?.invokeMethod("onLaunchAction", m) else pendingLaunch = m
    }

    // ---------- 方法分发 ----------

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "ready" -> {
                ready = true
                OrbController.dartAlive = true
                pendingLaunch?.let { channel?.invokeMethod("onLaunchAction", it) }
                pendingLaunch = null
                result.success(true)
            }
            "startKeepAlive" -> result.success(JarvisForegroundService.start(activity))
            "stopKeepAlive" -> {
                JarvisForegroundService.stop(activity)
                result.success(true)
            }
            "isIgnoringBattery" -> {
                val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
                result.success(pm.isIgnoringBatteryOptimizations(activity.packageName))
            }
            "requestIgnoreBattery" -> {
                val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(activity.packageName)) {
                    result.success(true)
                } else {
                    val ok = startSafe(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
                    ) || startSafe(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    result.success(ok)
                }
            }
            "scheduleReminder" -> {
                val id = (call.argument<Number>("id") ?: 0).toInt()
                val at = (call.argument<Number>("atMs") ?: 0).toLong()
                result.success(
                    ReminderScheduler.schedule(
                        activity, id, at, call.argument<String>("title") ?: "Jarvis 提醒",
                        call.argument<String>("body") ?: "", "reminder"
                    )
                )
            }
            "cancelAllReminders" -> {
                ReminderScheduler.cancelAll(activity)
                result.success(true)
            }
            "listReminders" -> result.success(ReminderScheduler.list(activity))
            "scheduleDailyBrief" -> result.success(
                ReminderScheduler.scheduleDailyBrief(
                    activity, (call.argument<Number>("hour") ?: 7).toInt(), (call.argument<Number>("minute") ?: 30).toInt()
                )
            )
            "cancelDailyBrief" -> {
                ReminderScheduler.cancelDailyBrief(activity)
                result.success(true)
            }
            "setTimer" -> {
                val secs = (call.argument<Number>("seconds") ?: 0).toInt()
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, call.argument<String>("label") ?: "Jarvis 计时")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                result.success(startSafe(i))
            }
            "volume" -> result.success(volume(call.argument<String>("action") ?: "get", (call.argument<Number>("percent") ?: 0).toInt()))
            "mediaKey" -> result.success(mediaKey(call.argument<String>("action") ?: ""))
            "openPanel" -> result.success(openPanel(call.argument<String>("kind") ?: ""))
            "setDnd" -> result.success(setDnd(call.argument<Boolean>("on") ?: false))
            "openDndAccess" -> result.success(startSafe(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)))
            "getLocation" -> location(result)
            "queryCalendar" -> result.success(
                queryCalendar((call.argument<Number>("startMs") ?: 0).toLong(), (call.argument<Number>("endMs") ?: 0).toLong())
            )
            "notificationAccessEnabled" -> {
                val enabled = Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners") ?: ""
                result.success(enabled.contains(activity.packageName))
            }
            "openNotificationAccess" -> result.success(startSafe(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
            "getNotifications" -> result.success(
                JarvisNotificationListener.recent((call.argument<Number>("limit") ?: 20).toInt().coerceIn(1, 60))
            )
            "ttsSpeak" -> ttsSpeak(
                call.argument<String>("text") ?: "", call.argument<Double>("rate") ?: 0.35,
                call.argument<Double>("pitch") ?: 1.0, result
            )
            "ttsStop" -> {
                try { tts?.stop() } catch (_: Exception) {}
                ttsResult?.success(false)
                ttsResult = null
                result.success(true)
            }
            "openAssistantSettings" -> result.success(
                startSafe(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) ||
                    (Build.VERSION.SDK_INT >= 24 && startSafe(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))) ||
                    startSafe(Intent(Settings.ACTION_SETTINGS))
            )
            "setUserWhitelist" -> {
                val pkgs = call.argument<List<String>>("packages")?.toSet() ?: emptySet()
                JarvisAccessibilityService.setUserWhitelist(pkgs)
                result.success(true)
            }
            "orbSetEnabled" -> {
                val up = OrbController.setEnabled(call.argument<Boolean>("on") ?: false)
                result.success(if (up) "ok" else "need_service")
            }
            "orbState" -> {
                OrbController.setState(call.argument<String>("state") ?: "idle")
                result.success(true)
            }
            "orbBubble" -> {
                OrbController.showBubble(call.argument<String>("text") ?: "")
                result.success(true)
            }
            "openAccessibilitySettings" -> result.success(startSafe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
            else -> result.notImplemented()
        }
    }

    private fun startSafe(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    // ---------- 音量 / 媒体 ----------

    private fun volume(action: String, percent: Int): Int {
        val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return try {
            when (action) {
                "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "max" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                "min" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, 1.coerceAtMost(max), AudioManager.FLAG_SHOW_UI)
                "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "unmute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                "set" -> am.setStreamVolume(
                    AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) * max / 100.0).roundToInt().coerceIn(0, max),
                    AudioManager.FLAG_SHOW_UI
                )
            }
            if (max <= 0) 0 else am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        } catch (e: Exception) {
            -1
        }
    }

    private fun mediaKey(action: String): Boolean {
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return false
        }
        return try {
            val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun openPanel(kind: String): Boolean = when (kind) {
        "wifi" -> startSafe(
            if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            else Intent(Settings.ACTION_WIFI_SETTINGS)
        )
        "airplane" -> startSafe(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
        "bluetooth" -> startSafe(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        "location" -> startSafe(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        else -> false
    }

    private fun setDnd(on: Boolean): String {
        val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return "need_access"
        return try {
            nm.setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            "ok"
        } catch (e: Exception) {
            "error"
        }
    }

    // ---------- 位置 / 日历 ----------

    private fun locMap(l: Location): Map<String, Any?> =
        mapOf("lat" to l.latitude, "lon" to l.longitude, "accuracy" to l.accuracy.toDouble())

    private fun location(result: MethodChannel.Result) {
        val fine = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            result.success(null)
            return
        }
        val lm = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } ?: continue
            if (best == null || l.time > best.time) best = l
        }
        if (best != null) {
            result.success(locMap(best))
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val provider = lm.getProviders(true).firstOrNull { it != LocationManager.PASSIVE_PROVIDER }
            if (provider != null) {
                var replied = false
                val main = Handler(Looper.getMainLooper())
                try {
                    lm.getCurrentLocation(provider, null, activity.mainExecutor) { loc ->
                        if (!replied) {
                            replied = true
                            result.success(loc?.let { locMap(it) })
                        }
                    }
                    main.postDelayed({
                        if (!replied) {
                            replied = true
                            result.success(null)
                        }
                    }, 10_000)
                    return
                } catch (e: SecurityException) {
                    // 落到下面返回 null
                }
            }
        }
        result.success(null)
    }

    private fun queryCalendar(startMs: Long, endMs: Long): List<Map<String, Any?>>? {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, startMs)
        ContentUris.appendId(b, endMs)
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION
        )
        val out = ArrayList<Map<String, Any?>>()
        try {
            activity.contentResolver.query(b.build(), proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && out.size < 30) {
                    out.add(
                        mapOf(
                            "title" to (c.getString(0) ?: "(无标题)"), "begin" to c.getLong(1), "end" to c.getLong(2),
                            "allDay" to (c.getInt(3) == 1), "location" to (c.getString(4) ?: "")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            return null
        }
        return out
    }

    // ---------- 系统 TTS 兜底 ----------

    private fun ttsSpeak(text: String, rate: Double, pitch: Double, result: MethodChannel.Result) {
        ttsResult?.success(false)
        ttsResult = result
        val speak = fun() {
            val t = tts ?: run { finishTts(false); return }
            t.language = Locale.CHINA
            t.setSpeechRate((0.5 + rate).toFloat().coerceIn(0.5f, 2.0f))
            t.setPitch(pitch.toFloat().coerceIn(0.5f, 2.0f))
            val id = "jv${System.nanoTime()}"
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id) activity.runOnUiThread { finishTts(true) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id) activity.runOnUiThread { finishTts(false) }
                }
            })
            if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finishTts(false)
        }
        if (ttsReady && tts != null) {
            speak()
        } else {
            tts = TextToSpeech(activity.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    speak()
                } else {
                    finishTts(false)
                }
            }
        }
    }

    private fun finishTts(ok: Boolean) {
        ttsResult?.success(ok)
        ttsResult = null
    }
}
