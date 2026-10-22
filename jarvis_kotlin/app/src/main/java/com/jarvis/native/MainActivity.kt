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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.jarvis.native.asr.SystemAsr

/**
 * 纯 Kotlin 版主界面：连接状态自检（无障碍/悬浮窗/电池豁免/常驻服务/悬浮球/
 * Agnes Key/系统语音识别），哪一项没通当场跳去开启；二期新增对话与语音设置区。
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
            text = "纯 Kotlin 版 · 二期（对话 + 语音）"
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
        buildVoiceSettingsSection(root)

        setContentView(ScrollView(this).apply { addView(root) })

        handleAssistIntent(intent)
        startKeepAlive(manual = false)
    }

    /** 二期：对话 Key、唤醒词开关与灵敏度、打断说话开关——都在设置里直接能看能改。 */
    private fun buildVoiceSettingsSection(root: LinearLayout) {
        val section = TextView(this).apply {
            text = "对话与语音设置"
            textSize = 18f
            setPadding(0, dp(24), 0, dp(8))
        }
        root.addView(section)

        val keyLabel = TextView(this).apply {
            text = "Agnes API Key（对话用，不会写死在代码里）"
            textSize = 12f
        }
        val keyInput = EditText(this).apply {
            hint = "在此粘贴 Key"
            setText(JarvisPrefs.getAgnesKey(this@MainActivity))
        }
        root.addView(keyLabel)
        root.addView(keyInput)
        root.addView(btn("保存 Key") {
            JarvisPrefs.setAgnesKey(this, keyInput.text.toString())
            toast("已保存")
        })

        val wakeBox = CheckBox(this).apply {
            text = "开启唤醒词「贾维斯」（降级版：系统识别轮询，见自检详情）"
            isChecked = JarvisPrefs.isWakeEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                JarvisPrefs.setWakeEnabled(this@MainActivity, checked)
                if (checked) JarvisAgent.maybeStartWakeLoop() else JarvisAgent.stopWakeLoop()
            }
        }
        root.addView(wakeBox)

        val sensLabel = TextView(this).apply { textSize = 12f }
        fun refreshSensLabel(v: Int) { sensLabel.text = "唤醒灵敏度：$v（越大越容易唤醒，也越容易误唤醒）" }
        refreshSensLabel(JarvisPrefs.getWakeSensitivity(this))
        root.addView(sensLabel)
        root.addView(SeekBar(this).apply {
            max = 100
            progress = JarvisPrefs.getWakeSensitivity(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    refreshSensLabel(v)
                    if (fromUser) JarvisPrefs.setWakeSensitivity(this@MainActivity, v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        root.addView(CheckBox(this).apply {
            text = "播报时允许被打断说话（降级版：无回声消除，见自检详情）"
            isChecked = JarvisPrefs.isBargeInEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> JarvisPrefs.setBargeInEnabled(this@MainActivity, checked) }
        })
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
        JarvisAgent.start(this) // 幂等：App 自己打开时也顺带确保 eventSink 接上（不依赖常驻服务一定先启动）
        if (intent?.getStringExtra(EXTRA_ACTION) == "assist") {
            detailView.text = "已通过悬浮球/磁贴/数字助理唤起，已开始听。"
            JarvisAgent.triggerListenFromAssist(this)
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
            val keyOk = JarvisPrefs.getAgnesKey(this@MainActivity).isNotBlank()
            appendLine("Agnes 对话 Key：${if (keyOk) "已配置" else "未配置（对话会报错）"}")
            appendLine("系统语音识别：${if (SystemAsr.isAvailable(this@MainActivity)) "可用" else "不可用（本机没有可用的识别服务，听写/唤醒词都用不了）"}")
            appendLine("语音合成：Edge TTS，需联网，无需 Key")
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
