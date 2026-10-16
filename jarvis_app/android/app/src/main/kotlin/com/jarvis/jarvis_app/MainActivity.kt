package com.jarvis.jarvis_app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.provider.Telephony
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.example.jarvis.JarvisAccessibilityService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channel = "jarvis/sms"
    private val launcherChannel = "jarvis/launcher"
    private val accessibilityChannel = "com.example.jarvis/accessibility"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getInboxSms" -> {
                        val limit = (call.argument<Int>("limit") ?: 5).coerceIn(1, 20)
                        result.success(getInboxSms(limit))
                    }
                    else -> result.notImplemented()
                }
            }
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, launcherChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "openApp" -> {
                        val pkg = call.argument<String>("package") ?: ""
                        result.success(openApp(pkg))
                    }
                    else -> result.notImplemented()
                }
            }
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, accessibilityChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "enableAutoClick" -> {
                        // 可选参数：packages（白名单包名）、windowMs（有效时长，3-60 秒）
                        val pkgs = call.argument<List<String>>("packages")?.toSet()
                            ?: defaultSendPackages()
                        val windowMs = (call.argument<Number>("windowMs")?.toLong()
                            ?: JarvisAccessibilityService.DEFAULT_WINDOW_MS)
                            .coerceIn(3_000L, 60_000L)
                        JarvisAccessibilityService.arm(pkgs, windowMs)
                        result.success(true)
                    }
                    "disableAutoClick" -> {
                        JarvisAccessibilityService.disarm()
                        result.success(true)
                    }
                    "autoClickStatus" -> {
                        // idle / armed / clicked / timeout
                        result.success(JarvisAccessibilityService.status())
                    }
                    "isAccessibilityEnabled" -> {
                        result.success(isAccessibilityEnabled())
                    }
                    "openAccessibilitySettings" -> {
                        try {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                            result.success(true)
                        } catch (e: Exception) {
                            result.success(false)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, JarvisAccessibilityService::class.java)
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val viaManager = am?.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )?.any {
            it.resolveInfo.serviceInfo.packageName == expected.packageName &&
                it.resolveInfo.serviceInfo.name == expected.className
        } ?: false
        if (viaManager) return true
        // 兜底：部分国产 ROM 上 AccessibilityManager 的列表不可靠，直接读系统设置
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == expected
        }
    }

    /** 自动点“发送”的默认白名单：系统默认短信 App + WhatsApp。 */
    private fun defaultSendPackages(): Set<String> {
        val out = mutableSetOf("com.whatsapp", "com.whatsapp.w4b")
        val sms = try {
            Telephony.Sms.getDefaultSmsPackage(this)
        } catch (_: Exception) {
            null
        }
        if (sms != null) {
            out.add(sms)
        } else {
            out.addAll(
                listOf(
                    "com.android.mms",
                    "com.google.android.apps.messaging",
                    "com.samsung.android.messaging"
                )
            )
        }
        return out
    }

    /// Launch an app by package name, the canonical way.
    /// getLaunchIntentForPackage returns the app's real launcher
    /// intent (explicit component). Never build an implicit
    /// ACTION_MAIN + CATEGORY_LAUNCHER intent and hand it to
    /// android_intent_plus: that plugin strips the package when
    /// resolveActivity(MATCH_DEFAULT_ONLY) fails (launcher
    /// activities don't declare CATEGORY_DEFAULT), which turns the
    /// launch into an implicit intent and pops a system chooser
    /// ("打开方式") instead of opening the app.
    private fun openApp(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getInboxSms(limit: Int): List<Map<String, Any>> {
        val out = mutableListOf<Map<String, Any>>()
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return out
        }
        val cursor = contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        ) ?: return out
        cursor.use {
            val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
            while (it.moveToNext() && out.size < limit) {
                out.add(
                    mapOf(
                        "address" to (it.getString(addressIdx) ?: ""),
                        "body" to (it.getString(bodyIdx) ?: ""),
                        "date" to it.getLong(dateIdx)
                    )
                )
            }
        }
        return out
    }
}
