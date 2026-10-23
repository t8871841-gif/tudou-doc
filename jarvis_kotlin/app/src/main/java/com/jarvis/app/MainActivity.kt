package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import com.jarvis.app.asr.OfflineAsrSession
import com.jarvis.app.net.AgnesClient

/**
 * 纯 Kotlin 版主界面：打开即对话页（打字聊天 + 麦克风走语音流程）；
 * 自检与设置是二级页（右上角切换）：连接状态（无障碍/电池豁免/常驻服务/悬浮球/
 * Agnes Key/离线识别）哪一项没通当场跳去开启。悬浮球走无障碍覆盖层，不需要悬浮窗权限。
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_ACTION = "jarvis_action"
        const val EXTRA_TEXT = "jarvis_text"
    }

    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var orbButton: Button
    private lateinit var chatPanel: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatInput: EditText
    private var showingSettings = false
    private var chatInFlight = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置内容（自检 + 对话语音设置 + 审计），装进二级页
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
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

        root.addView(statusView)
        root.addView(btn("开启无障碍服务（悬浮球靠它承载，无需单独的悬浮窗权限）") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        buildAuditSection(root)
        settingsScroll = ScrollView(this).apply {
            addView(root)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        buildChatPanel()
        val toggleBtn = Button(this).apply {
            text = "自检/设置"
            setOnClickListener {
                showingSettings = !showingSettings
                chatPanel.visibility = if (showingSettings) View.GONE else View.VISIBLE
                settingsScroll.visibility = if (showingSettings) View.VISIBLE else View.GONE
                text = if (showingSettings) "回到对话" else "自检/设置"
                if (showingSettings) refresh()
            }
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(6))
            addView(TextView(this@MainActivity).apply {
                text = "Jarvis"
                textSize = 22f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(toggleBtn)
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0E14.toInt())
            addView(topBar)
            addView(chatPanel)
            addView(settingsScroll)
        }
        setContentView(outer)

        handleAssistIntent(intent)
        startKeepAlive(manual = false)
    }

    /** 对话主页：消息气泡列表 + 输入行（发送走 Agnes，麦克风走悬浮球同款语音流程）。 */
    private fun buildChatPanel() {
        chatList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        chatScroll = ScrollView(this).apply { addView(chatList) }
        chatInput = EditText(this).apply {
            hint = "跟 Jarvis 说点什么…"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(10))
            addView(chatInput)
            addView(Button(this@MainActivity).apply {
                text = "🎤"
                setOnClickListener { JarvisAgent.triggerListenFromAssist(this@MainActivity) }
            })
            addView(Button(this@MainActivity).apply {
                text = "发送"
                setOnClickListener { sendChat() }
            })
        }
        chatPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(chatScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(inputRow)
        }
        addBubble("你好，先生。我是 Jarvis，打字发消息或点 🎤 说话都行。", mine = false)
    }

    private fun addBubble(text: String, mine: Boolean) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFFE8ECF4.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (mine) 0xFF1D4ED8.toInt() else 0xFF1E2532.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (mine) Gravity.END else Gravity.START
                topMargin = dp(6)
                marginStart = if (mine) dp(48) else 0
                marginEnd = if (mine) 0 else dp(48)
            }
        }
        chatList.addView(tv)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendChat() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty() || chatInFlight) return
        chatInFlight = true
        chatInput.text.clear()
        addBubble(text, mine = true)
        AgnesClient.ask(this, text,
            onResult = { reply ->
                runOnUiThread {
                    chatInFlight = false
                    addBubble(reply, mine = false)
                }
            },
            onError = { e ->
                runOnUiThread {
                    chatInFlight = false
                    addBubble("出错了：${e.message ?: "未知错误"}（Key 在「自检/设置」里填）", mine = false)
                }
            })
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
            text = "开启唤醒词「贾维斯」（离线识别轮询，非专用低功耗 KWS，见自检详情）"
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

    /** 第 2 条「操作审计日志」：默认收起，不占屏幕——点开才看，符合"每屏只做一件事"的精简原则。 */
    private fun buildAuditSection(root: LinearLayout) {
        val section = TextView(this).apply {
            text = "操作审计日志"
            textSize = 18f
            setPadding(0, dp(24), 0, dp(8))
        }
        root.addView(section)
        val logView = TextView(this).apply {
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        root.addView(btn("显示/隐藏日志") {
            if (logView.visibility == View.VISIBLE) {
                logView.visibility = View.GONE
            } else {
                val lines = AuditLog.readRecent(this)
                logView.text = if (lines.isEmpty()) "暂无记录" else lines.joinToString("\n\n")
                logView.visibility = View.VISIBLE
            }
        })
        root.addView(btn("清空日志") {
            AuditLog.clear(this)
            logView.text = "暂无记录"
            toast("已清空")
        })
        root.addView(logView)
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
        val pm = getSystemService(PowerManager::class.java)
        val battery = pm?.isIgnoringBatteryOptimizations(packageName) == true
        statusView.text = buildString {
            appendLine("无障碍服务：${if (a11y) "已连接" else "未开启"}（悬浮球由它承载，无悬浮窗权限这回事）")
            appendLine("电池优化豁免：${if (battery) "已豁免" else "未豁免"}")
            appendLine("常驻服务：${if (JarvisForegroundService.running) "运行中" else "未运行"}")
            appendLine("悬浮球：${if (OrbController.isEnabled()) "已开启" else "已关闭"}")
            val keyOk = JarvisPrefs.getAgnesKey(this@MainActivity).isNotBlank()
            appendLine("Agnes 对话 Key：${if (keyOk) "已配置" else "未配置（对话会报错）"}")
            appendLine("离线语音识别(sherpa-onnx)：${if (OfflineAsrSession.isAvailable(this@MainActivity)) "模型文件已就位" else "缺模型文件(assets/asr 下没放齐 4 个文件)，听写/唤醒词都用不了"}")
            appendLine("语音合成：Edge TTS（云扬），需联网，无需 Key")
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
