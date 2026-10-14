package com.jarvis.jarvis_app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channel = "jarvis/sms"
    private val launcherChannel = "jarvis/launcher"

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
    }

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
