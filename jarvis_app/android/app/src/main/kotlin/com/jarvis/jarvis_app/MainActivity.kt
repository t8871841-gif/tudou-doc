package com.jarvis.jarvis_app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.MediaStore
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
                    // 所有 App 秒开：按名字匹配已安装 App（内置表里没有的名字走这里）。
                    "openAppByName" -> {
                        val name = call.argument<String>("name") ?: ""
                        result.success(openAppByName(name))
                    }
                    // 手电筒：CameraManager.setTorchMode（需要 CAMERA 权限，Dart 侧先申请）。
                    "setTorch" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        result.success(setTorch(enabled))
                    }
                    // 本地搜索：图片 / 视频 / 音乐 / 文档（MediaStore，只返回能看见的）。
                    "searchLocalMedia" -> {
                        val kind = call.argument<String>("kind") ?: ""
                        val keyword = call.argument<String>("keyword") ?: ""
                        result.success(searchLocalMedia(kind, keyword))
                    }
                    // 亮度：写系统亮度（需 WRITE_SETTINGS，Dart 侧先判授权）。
                    "setBrightness" -> {
                        val mode = call.argument<String>("mode") ?: "set"
                        val percent = call.argument<Int>("percent") ?: 0
                        result.success(setBrightness(mode, percent))
                    }
                    "openWriteSettings" -> {
                        try {
                            startActivity(Intent(
                                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:$packageName")
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                            result.success(true)
                        } catch (e: Exception) {
                            result.success(false)
                        }
                    }
                    // 蓝牙：开关（需 BLUETOOTH_CONNECT，Dart 侧先申请）。
                    "setBluetooth" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        result.success(setBluetooth(enabled))
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
    /// activities don't declare CATEGORY_DEFAULT), which turns
    /// the launch into an implicit intent and pops a system
    /// chooser ("打开方式") instead of opening the app.
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

    /**
     * 按名字打开已安装 App（“打开XXX”且 XXX 不在内置表里时走这里，所有 App 都秒开）。
     * 先精确匹配启动器 App 名（去空格、忽略大小写），再唯一包含匹配；
     * 包含匹配有多个命中时不开，避免开错 App。
     */
    private fun openAppByName(name: String): Boolean {
        val query = name.trim()
        if (query.isEmpty()) return false
        val apps = try {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )
        } catch (e: Exception) {
            return false
        }
        val norm = { s: String -> s.replace(Regex("[\\s　]"), "").lowercase() }
        val q = norm(query)
        var match = apps.firstOrNull {
            norm(it.loadLabel(packageManager).toString()) == q
        }
        if (match == null) {
            val hits = apps.filter {
                norm(it.loadLabel(packageManager).toString()).contains(q)
            }
            if (hits.size == 1) match = hits.first()
        }
        if (match == null) return false
        return openApp(match.activityInfo.packageName)
    }

    /** 手电筒开关。后置闪光灯优先；无闪光灯 / 相机被占用 / 无权限时返回 false。 */
    private fun setTorch(enabled: Boolean): Boolean {
        val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return false
        return try {
            val ids = cm.cameraIdList
            val backFlash = ids.firstOrNull { id ->
                val c = cm.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
            val anyFlash = backFlash ?: ids.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cm.setTorchMode(anyFlash, enabled)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 写系统亮度。mode: set（percent 1..100）/ up（+20%）/ down（-20%）。
     * 返回 "ok" / "need_auth"（未授予 WRITE_SETTINGS）/ "error"。
     */
    private fun setBrightness(mode: String, percent: Int): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.System.canWrite(this)
        ) {
            return "need_auth"
        }
        return try {
            val resolver = contentResolver
            val current = try {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Exception) {
                128
            }
            val target = when (mode) {
                "up" -> (current + 51).coerceIn(10, 255)
                "down" -> (current - 51).coerceIn(10, 255)
                else -> ((percent.coerceIn(1, 100)) * 255 / 100).coerceIn(10, 255)
            }
            Settings.System.putInt(
                resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                resolver, Settings.System.SCREEN_BRIGHTNESS, target
            )
            "ok"
        } catch (_: Exception) {
            "error"
        }
    }

    /** 蓝牙开关。无蓝牙硬件 / 调用失败返回 false。 */
    private fun setBluetooth(enabled: Boolean): Boolean {
        return try {
            val manager =
                getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter ?: return false
            if (enabled) adapter.enable() else adapter.disable()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 本地搜索（MediaStore）：image / video / audio / document。
     * keyword 为空 = 列最新的 20 个；否则按文件名 LIKE 匹配。
     * document 按常见文档 MIME 过滤；无 MANAGE_EXTERNAL_STORAGE 时只返回能看见的，如实返回。
     */
    private fun searchLocalMedia(kind: String, keyword: String): List<Map<String, Any>> {
        val uri = when (kind) {
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            "document" -> MediaStore.Files.getContentUri("external")
            else -> return emptyList()
        }
        val projection = mutableListOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE
        )
        if (kind == "audio") {
            projection.add(MediaStore.Audio.Media.TITLE)
            projection.add(MediaStore.Audio.Media.ARTIST)
        }
        val sel = StringBuilder()
        val args = mutableListOf<String>()
        if (kind == "document") {
            sel.append("${MediaStore.MediaColumns.MIME_TYPE} IN (")
            val mimes = listOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "text/plain",
                "application/rtf",
                "application/vnd.oasis.opendocument.text",
                "application/vnd.oasis.opendocument.spreadsheet",
                "application/vnd.oasis.opendocument.presentation"
            )
            sel.append(mimes.joinToString(",") { "?" })
            sel.append(")")
            args.addAll(mimes)
        }
        if (keyword.isNotEmpty()) {
            if (sel.isNotEmpty()) sel.append(" AND ")
            sel.append("${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
            args.add("%$keyword%")
        }
        val cursor = try {
            contentResolver.query(
                uri,
                projection.toTypedArray(),
                if (sel.isEmpty()) null else sel.toString(),
                if (args.isEmpty()) null else args.toTypedArray(),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val out = mutableListOf<Map<String, Any>>()
        cursor.use {
            val nameIdx = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateIdx = it.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val sizeIdx = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val titleIdx = if (kind == "audio") {
                it.getColumnIndex(MediaStore.Audio.Media.TITLE)
            } else -1
            val artistIdx = if (kind == "audio") {
                it.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            } else -1
            while (it.moveToNext() && out.size < 20) {
                val map = mutableMapOf<String, Any>(
                    "name" to (if (nameIdx >= 0) it.getString(nameIdx) ?: "" else ""),
                    "date" to (if (dateIdx >= 0) it.getLong(dateIdx) * 1000L else 0L),
                    "size" to (if (sizeIdx >= 0) it.getLong(sizeIdx) else 0L)
                )
                if (kind == "audio") {
                    val title = if (titleIdx >= 0) it.getString(titleIdx) ?: "" else ""
                    val artist = if (artistIdx >= 0) it.getString(artistIdx) ?: "" else ""
                    val extra = listOf(artist, title).filter { s -> s.isNotEmpty() }
                        .joinToString(" - ")
                    if (extra.isNotEmpty()) map["extra"] = extra
                }
                out.add(map)
            }
        }
        return out
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
