package com.jarvis.jarvis_app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.Settings
import android.provider.Telephony
import android.view.accessibility.AccessibilityManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.jarvis.JarvisAccessibilityService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channel = "jarvis/sms"
    private val launcherChannel = "jarvis/launcher"
    private val accessibilityChannel = "com.example.jarvis/accessibility"
    private val agentChannel = "com.jarvis.jarvis_app/agent"

    // 真拨电话的待处理请求：申请权限是异步的，授权后自动重试拨号，不直接返回失败。
    private var pendingCallPhone: String? = null
    private var pendingCallResult: MethodChannel.Result? = null
    private val agentCallRequestCode = 4242

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
                    // ---------- 任务模式：每 App 一个适配器，由 Dart 下发声明式步骤 ----------
                    "startTask" -> {
                        // 必须在拉起目标 App 之前调用。包名只能取“硬白名单 + 默认短信 App”的交集。
                        // packages 为空 = 默认（WhatsApp + 系统默认短信 App）；传了也只取与白名单的交集
                        val pkgs = call.argument<List<String>>("packages")?.toSet()
                            ?.takeIf { it.isNotEmpty() } ?: defaultSendPackages()
                        val steps = call.argument<List<Map<String, Any?>>>("steps") ?: emptyList()
                        val windowMs = (call.argument<Number>("windowMs")?.toLong() ?: 40_000L)
                            .coerceIn(5_000L, 90_000L)
                        JarvisAccessibilityService.setExtraWhitelist(smsPackages())
                        result.success(JarvisAccessibilityService.startTask(pkgs, steps, windowMs))
                    }
                    "awaitTask" -> {
                        // 任务结束（成功/失败/超时）时才回调；已结束则立刻返回上一次结果
                        JarvisAccessibilityService.awaitTask { map -> result.success(map) }
                    }
                    "cancelTask" -> {
                        JarvisAccessibilityService.cancelTask()
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
        // ---------- Agent 循环：真拨电话 / 查通话记录 / 看屏 / 通用动作 ----------
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, agentChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "makeCall" -> {
                        val phone = call.argument<String>("phoneNumber")
                        if (phone.isNullOrEmpty()) {
                            result.error("INVALID_ARGUMENT", "Phone number is null", null)
                        } else {
                            makeDirectCall(phone, result)
                        }
                    }
                    "checkCallLog" -> {
                        val phone = call.argument<String>("phoneNumber") ?: ""
                        result.success(checkCallLog(phone))
                    }
                    "perceiveScreen" -> {
                        result.success(JarvisAccessibilityService.perceiveScreenTree())
                    }
                    "performAction" -> {
                        val actionType = call.argument<String>("actionType") ?: ""
                        val targetText = call.argument<String>("targetText") ?: ""
                        val inputText = call.argument<String>("inputText")
                        result.success(
                            JarvisAccessibilityService.performAgentAction(
                                actionType, targetText, inputText
                            )
                        )
                    }
                    else -> result.notImplemented()
                }
            }
    }

    /** 真拨电话（ACTION_CALL）。无权限时先申请，授权后自动重试，不直接返回失败。 */
    private fun makeDirectCall(phoneNumber: String, result: MethodChannel.Result) {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCallPhone = phoneNumber
            pendingCallResult = result
            // 通话记录权限一起申请：拨出后要查通话记录核实。
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG),
                agentCallRequestCode
            )
            return
        }
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            result.success(true)
        } catch (e: Exception) {
            result.error("CALL_FAILED", e.message, null)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != agentCallRequestCode) return
        val phone = pendingCallPhone
        val res = pendingCallResult
        pendingCallPhone = null
        pendingCallResult = null
        if (phone == null || res == null) return
        val idx = permissions.indexOf(Manifest.permission.CALL_PHONE)
        val granted = idx >= 0 && grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            makeDirectCall(phone, res)
        } else {
            res.error("PERMISSION_DENIED", "CALL_PHONE permission denied", null)
        }
    }

    /**
     * 查最近 120 秒的通话记录里有没有拨给 [phone] 的记录。
     * 返回 granted（有没有通话记录权限）+ found（查到 / 没查到）。
     * 无权限时不撒谎：granted=false，Dart 侧如实说“无法核实”。
     */
    private fun checkCallLog(phone: String): Map<String, Any> {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return mapOf("granted" to false, "found" to false)
        }
        val want = phone.replace(Regex("\\D"), "").takeLast(11)
        if (want.isEmpty()) return mapOf("granted" to true, "found" to false)
        val since = System.currentTimeMillis() - 120_000L
        val cursor = try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf(since.toString()),
                "${CallLog.Calls.DATE} DESC"
            )
        } catch (_: Exception) {
            null
        } ?: return mapOf("granted" to true, "found" to false)
        cursor.use {
            val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
            while (it.moveToNext()) {
                val num = (it.getString(numIdx) ?: "")
                    .replace(Regex("\\D"), "").takeLast(11)
                if (num.isNotEmpty() && num == want) {
                    return mapOf("granted" to true, "found" to true)
                }
            }
        }
        return mapOf("granted" to true, "found" to false)
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

    /** 系统默认短信 App 的包名集合（取不到时给常见候选）。 */
    private fun smsPackages(): Set<String> {
        val sms = try {
            Telephony.Sms.getDefaultSmsPackage(this)
        } catch (_: Exception) {
            null
        }
        return if (sms != null) {
            setOf(sms)
        } else {
            setOf(
                "com.android.mms",
                "com.google.android.apps.messaging",
                "com.samsung.android.messaging"
            )
        }
    }

    /** 自动点“发送”的默认白名单：系统默认短信 App + WhatsApp（旧模式 enableAutoClick 用）。 */
    private fun defaultSendPackages(): Set<String> {
        val out = mutableSetOf("com.whatsapp", "com.whatsapp.w4b")
        out.addAll(smsPackages())
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
