package com.jarvis.native

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * 纯 Kotlin 版主界面（一期）：只做一件事——把连接状态摆在明面上，哪一项没通，
 * 当场就能跳去打开。无障碍/悬浮窗/电池豁免/常驻服务/悬浮球，一屏看全。
 * 对话与语音在二期接入。
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_ACTION = "jarvis_action"
    }

    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var orbButton: Button

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        val title = TextView(this).apply {
            text = "Jarvis"
            textSize = 28f
            gravity = Gravity.START
        }
        val sub = TextView(this).apply {
            text = "纯 Kotlin 版 · 一期（连接与悬浮球）"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(16))
        }
        statusView = TextView(this).apply { textSize = 15f }
        orbButton = Button(this).apply {
            setOnClickListener {
                if (!JarvisAccessibilityService.isRunning()) {
                    toast("先开启无障碍服务，悬浮球由它承载")
                    return@setOnClickListener
                }
                val on = !OrbController.isEnabled()
                OrbController.setEnabled(on)
                refresh()
            }
        }
        detailView = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        }

        root.addView(title)
        root.addView(sub)
        root.addView(statusView)
        root.addView(btn("开启无障碍服务") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("授予悬浮窗权限") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })
        root.addView(btn("申请电池优化豁免") {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        })
        root.addView(btn("启动常驻服务") { startKeepAlive(manual = true) })
        root.addView(orbButton)
        root.addView(btn("测试：读取当前界面") { testPerceive() })
        root.addView(btn("刷新状态") { refresh() })
        root.addView(detailView)

        setContentView(ScrollView(this).apply { addView(root) })

        handleAssistIntent(intent)
        startKeepAlive(manual = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAssistIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        OrbController.setAppForeground(true)
        refresh()
    }

    override fun onPause() {
        OrbController.setAppForeground(false)
        super.onPause()
    }

    private fun handleAssistIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_ACTION) == "assist") {
            detailView.text = "已通过悬浮球/磁贴唤起。语音聆听在二期接入，这里先确认唤起链路通畅。"
        }
    }

    private fun startKeepAlive(manual: Boolean) {
        if (JarvisForegroundService.running) {
            if (manual) toast("常驻服务已在运行")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (manual) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
                toast("先授予录音权限，常驻服务才能启动")
            }
            return
        }
        try {
            ContextCompat.startForegroundService(
                this, Intent(this, JarvisForegroundService::class.java)
            )
        } catch (e: Exception) {
            if (manual) toast("常驻服务启动失败：${e.message}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startKeepAlive(manual = true)
        }
        refresh()
    }

    private fun testPerceive() {
        if (!JarvisAccessibilityService.isRunning()) {
            toast("无障碍未开启，读不到界面")
            return
        }
        val tree = JarvisAccessibilityService.perceiveScreenTree()
        detailView.text = "界面节点树（前 600 字）：\n" + tree.take(600)
    }

    private fun refresh() {
        if (!::statusView.isInitialized) return
        val a11y = JarvisAccessibilityService.isRunning()
        val overlay = if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(this) else true
        val pm = getSystemService(PowerManager::class.java)
        val battery = pm?.isIgnoringBatteryOptimizations(packageName) == true
        statusView.text = buildString {
            appendLine("无障碍服务：${if (a11y) "已连接" else "未开启"}")
            appendLine("悬浮窗权限：${if (overlay) "已授予" else "未授予"}")
            appendLine("电池优化豁免：${if (battery) "已豁免" else "未豁免"}")
            appendLine("常驻服务：${if (JarvisForegroundService.running) "运行中" else "未运行"}")
            appendLine("悬浮球：${if (OrbController.isEnabled()) "已开启" else "已关闭"}")
            if (a11y) append("无障碍详情：${JarvisAccessibilityService.status()}")
        }
        orbButton.text = if (OrbController.isEnabled()) "关闭悬浮球" else "开启悬浮球"
    }

    private fun btn(label: String, onClick: (View) -> Unit): Button =
        Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener(onClick)
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
