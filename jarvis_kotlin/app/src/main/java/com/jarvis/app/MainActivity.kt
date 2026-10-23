package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.jarvis.app.asr.OfflineAsrSession
import com.jarvis.app.hud.GradientLayerView
import com.jarvis.app.hud.HudBackgroundView
import com.jarvis.app.hud.HudPalette
import com.jarvis.app.hud.HudStageView
import com.jarvis.app.net.AgnesClient
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 批一：主页复刻 Flutter 版 `home_screen.dart` 的 HUD 布局——深夜蓝对角渐变背景 + 点阵/极光层
 * + 中央呼吸光球舞台 + 对话气泡流 + 底部麦克风/输入区。自检与设置挪到二级页（点舞台左上角
 * 齿轮图标切换），内容和二期/三期交付时一样，没有改动。
 *
 * 没有照搬的部分（见 V3_CHANGES.md「批一」一节的完整偏差说明）：开机页呼吸 Logo 动画、
 * 舞台里嵌的地球贴图、状态读数条（POWER/LATENCY/SIGNAL 等装饰性数字）、打字机逐字输出。
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_ACTION = "jarvis_action"
        const val EXTRA_TEXT = "jarvis_text"
    }

    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var orbButton: Button
    private lateinit var homePanel: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatInput: EditText
    private lateinit var inputRow: LinearLayout
    private lateinit var stageView: HudStageView
    private lateinit var bgView: HudBackgroundView
    private lateinit var statusEnView: TextView
    private lateinit var statusCnView: TextView
    private lateinit var clockView: TextView
    private lateinit var wakeChip: TextView
    private lateinit var muteButton: ImageButton
    private lateinit var keyboardToggle: ImageButton
    private var showingSettings = false
    private var showInput = false
    private var chatInFlight = false

    private val main = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var tickRunnable: Runnable? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.addView(
            GradientLayerView(
                this,
                intArrayOf(HudPalette.BG_TOP, HudPalette.BG_MID, HudPalette.BG_BOTTOM),
                floatArrayOf(0f, 0.5f, 1f), diagonal = true
            ),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        bgView = HudBackgroundView(this).apply {
            mode = { JarvisAgent.currentState() }
            micLevel = { OfflineAsrSession.currentLevel() }
        }
        root.addView(bgView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(
            GradientLayerView(
                this,
                intArrayOf(Color.argb(166, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(204, 0, 0, 0)),
                floatArrayOf(0f, 0.5f, 1f), diagonal = false
            ),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(buildTopBar())

        homePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        homePanel.addView(buildStage())
        buildChatPanel()
        homePanel.addView(chatScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        homePanel.addView(buildBottomPanel())
        content.addView(homePanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        settingsScroll = buildSettingsPanel().apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        content.addView(settingsScroll)

        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        JarvisAgent.onStateChanged = { s -> main.post { onAgentStateChanged(s) } }
        onAgentStateChanged(JarvisAgent.currentState())

        handleAssistIntent(intent)
        startKeepAlive(manual = false)
        startClock()
    }

    // ---------- 顶栏：status dot + SYSTEM ACTIVE ... 时钟 ----------

    private fun buildTopBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(20), 0)
        val dot = View(this@MainActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF00FF9D.toInt()) }
        }
        addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        addView(TextView(this@MainActivity).apply {
            text = "SYSTEM ACTIVE"
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 12f
            letterSpacing = 0.12f
        })
        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
        clockView = TextView(this@MainActivity).apply {
            setTextColor(0x8AFFFFFF.toInt())
            textSize = 11f
            letterSpacing = 0.2f
        }
        addView(clockView)
    }

    private fun startClock() {
        tickRunnable = object : Runnable {
            override fun run() {
                clockView.text = clockFmt.format(java.util.Date())
                main.postDelayed(this, 1000)
            }
        }
        main.post(tickRunnable!!)
    }

    // ---------- 舞台：HudStageView + 齿轮(设置) + 状态文字 + 唤醒词胶囊 ----------

    private fun buildStage(): FrameLayout {
        val stage = FrameLayout(this)
        stage.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(330))

        stageView = HudStageView(this).apply {
            mode = { JarvisAgent.currentState() }
            micLevel = { OfflineAsrSession.currentLevel() }
        }
        stage.addView(stageView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val gear = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x0DFFFFFF)
                setStroke(dp(1), 0x29FFFFFF)
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { toggleSettings() }
            contentDescription = "设置"
        }
        stage.addView(gear, FrameLayout.LayoutParams(dp(38), dp(38)).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(10); topMargin = dp(10)
        })

        val statusCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        statusEnView = TextView(this).apply {
            textSize = 12f; setTextColor(0x8AFFFFFF.toInt()); letterSpacing = 0.25f; gravity = Gravity.CENTER
        }
        statusCnView = TextView(this).apply {
            textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        statusCol.addView(statusEnView)
        statusCol.addView(statusCnView)
        stage.addView(statusCol, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        wakeChip = TextView(this).apply {
            text = "唤醒词 · 关"
            textSize = 12f
            setPadding(dp(16), dp(7), dp(16), dp(7))
            setTextColor(0x99FFFFFF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x2EFFFFFF)
            }
            setOnClickListener {
                val next = !JarvisPrefs.isWakeEnabled(this@MainActivity)
                JarvisPrefs.setWakeEnabled(this@MainActivity, next)
                if (next) JarvisAgent.maybeStartWakeLoop() else JarvisAgent.stopWakeLoop()
                refreshWakeChip()
            }
        }
        stage.addView(wakeChip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(4)
        })
        return stage
    }

    private fun refreshWakeChip() {
        val on = JarvisPrefs.isWakeEnabled(this)
        wakeChip.text = "唤醒词 · ${if (on) "开" else "关"}"
        wakeChip.setTextColor(if (on) Color.WHITE else 0x99FFFFFF.toInt())
        val color = HudPalette.colorFor(JarvisAgent.currentState())
        wakeChip.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            if (on) {
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(HudPalette.withAlpha(color, 0.30f), HudPalette.withAlpha(color, 0.30f))
                setStroke(dp(1), HudPalette.withAlpha(color, 0.8f))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), 0x2EFFFFFF)
            }
        }
    }

    private fun onAgentStateChanged(state: String) {
        val color = HudPalette.colorFor(state)
        val (en, cn) = HudPalette.labelsFor(state, JarvisPrefs.isWakeEnabled(this))
        if (::statusEnView.isInitialized) {
            statusEnView.text = en
            statusCnView.text = cn
            statusCnView.setTextColor(color)
        }
        refreshWakeChip()
    }

    // ---------- 对话气泡流 ----------

    private fun buildChatPanel() {
        chatList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        chatScroll = ScrollView(this).apply { addView(chatList) }
        addBubble("你好，先生。我是 Jarvis，打字发消息或点麦克风说话都行。", mine = false)
    }

    private fun addBubble(text: String, mine: Boolean) {
        val color = HudPalette.colorFor(JarvisAgent.currentState())
        val (sec, _) = HudPalette.secondaryTertiary(color)
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15.5f
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(HudPalette.ICE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = if (mine) {
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(HudPalette.withAlpha(color, 0.38f), HudPalette.withAlpha(sec, 0.30f))).apply {
                    cornerRadii = floatArrayOf(dp(18).toFloat(), dp(18).toFloat(), dp(4).toFloat(), dp(4).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat())
                }
            } else {
                GradientDrawable().apply {
                    setColor(0x0FFFFFFF)
                    setStroke(dp(1), 0x14FFFFFF)
                    cornerRadii = floatArrayOf(dp(4).toFloat(), dp(4).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat())
                }
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = if (mine) Gravity.END else Gravity.START
                topMargin = dp(6); bottomMargin = dp(6)
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
            onResult = { reply -> runOnUiThread { chatInFlight = false; addBubble(reply, mine = false) } },
            onError = { e ->
                runOnUiThread {
                    chatInFlight = false
                    addBubble("出错了：${e.message ?: "未知错误"}（Key 在设置里填）", mine = false)
                }
            })
    }

    // ---------- 底部：静音 / 麦克风 / 键盘切换 + 折叠输入行 ----------

    private fun buildBottomPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(4), dp(16), dp(14))

        inputRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(8))
            chatInput = EditText(this@MainActivity).apply {
                hint = "跟 Jarvis 说点什么…"
                setTextColor(Color.WHITE)
                setHintTextColor(0x61FFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(chatInput)
            addView(Button(this@MainActivity).apply {
                text = "发送"
                setOnClickListener { sendChat() }
            })
        }
        addView(inputRow)

        val micRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        muteButton = ImageButton(this@MainActivity).apply {
            setImageResource(if (JarvisPrefs.isMuted(this@MainActivity)) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off)
            background = null
            setOnClickListener {
                val next = !JarvisPrefs.isMuted(this@MainActivity)
                JarvisPrefs.setMuted(this@MainActivity, next)
                setImageResource(if (next) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off)
                toast(if (next) "已静音，只显示文字" else "已取消静音")
            }
        }
        micRow.addView(muteButton, LinearLayout.LayoutParams(dp(44), dp(44)))

        val micButton = View(this@MainActivity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF00F0FF.toInt(), 0xFF7000FF.toInt())).apply {
                shape = GradientDrawable.OVAL
            }
            setOnClickListener { JarvisAgent.triggerListenFromAssist(this@MainActivity) }
        }
        micRow.addView(micButton, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginStart = dp(20); marginEnd = dp(20) })

        keyboardToggle = ImageButton(this@MainActivity).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            background = null
            setOnClickListener {
                showInput = !showInput
                inputRow.visibility = if (showInput) View.VISIBLE else View.GONE
                if (showInput) chatInput.requestFocus()
            }
        }
        micRow.addView(keyboardToggle, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(micRow)

        addView(TextView(this@MainActivity).apply {
            text = "点击麦克风开始语音交互"
            textSize = 11f
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        detailView = TextView(this@MainActivity).apply {
            textSize = 11f
            setTextColor(0x61FFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        addView(detailView)
    }

    // ---------- 设置/自检二级页：内容跟之前交付的一样，没有改动 ----------

    private fun toggleSettings() {
        showingSettings = !showingSettings
        homePanel.visibility = if (showingSettings) View.GONE else View.VISIBLE
        settingsScroll.visibility = if (showingSettings) View.VISIBLE else View.GONE
        if (showingSettings) refresh()
    }

    private fun buildSettingsPanel(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        root.addView(btn("← 回到对话") { toggleSettings() })
        statusView = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE); setPadding(0, dp(12), 0, 0) }
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
        val detail2 = TextView(this).apply { textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); setPadding(0, dp(12), 0, 0) }

        root.addView(statusView)
        root.addView(btn("开启无障碍服务（悬浮球靠它承载，无需单独的悬浮窗权限）") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("申请电池优化豁免") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        root.addView(btn("启动常驻服务") { startKeepAlive(manual = true) })
        root.addView(orbButton)
        root.addView(btn("测试：读取当前界面") {
            if (!JarvisAccessibilityService.isRunning()) { toast("无障碍未开启，读不到界面"); return@btn }
            detail2.text = "界面节点树（前 600 字）：\n" + JarvisAccessibilityService.perceiveScreenTree().take(600)
        })
        root.addView(btn("刷新状态") { refresh() })
        root.addView(detail2)
        buildVoiceSettingsSection(root)
        buildAuditSection(root)
        return ScrollView(this).apply { addView(root) }
    }

    /** 二期：对话 Key、唤醒词开关与灵敏度、打断说话开关——都在设置里直接能看能改。 */
    private fun buildVoiceSettingsSection(root: LinearLayout) {
        val section = TextView(this).apply {
            text = "对话与语音设置"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(24), 0, dp(8))
        }
        root.addView(section)

        val keyLabel = TextView(this).apply {
            text = "Agnes API Key（对话用，不会写死在代码里）"; textSize = 12f; setTextColor(0xB3FFFFFF.toInt())
        }
        val keyInput = EditText(this).apply {
            hint = "在此粘贴 Key"; setTextColor(Color.WHITE)
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
            setTextColor(0xB3FFFFFF.toInt())
            isChecked = JarvisPrefs.isWakeEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                JarvisPrefs.setWakeEnabled(this@MainActivity, checked)
                if (checked) JarvisAgent.maybeStartWakeLoop() else JarvisAgent.stopWakeLoop()
                refreshWakeChip()
            }
        }
        root.addView(wakeBox)

        val sensLabel = TextView(this).apply { textSize = 12f; setTextColor(0xB3FFFFFF.toInt()) }
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
            setTextColor(0xB3FFFFFF.toInt())
            isChecked = JarvisPrefs.isBargeInEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> JarvisPrefs.setBargeInEnabled(this@MainActivity, checked) }
        })
    }

    /** 第 2 条「操作审计日志」：默认收起，不占屏幕——点开才看，符合"每屏只做一件事"的精简原则。 */
    private fun buildAuditSection(root: LinearLayout) {
        val section = TextView(this).apply {
            text = "操作审计日志"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(24), 0, dp(8))
        }
        root.addView(section)
        val logView = TextView(this).apply {
            textSize = 11f; setTextColor(0xB3FFFFFF.toInt()); setPadding(0, dp(8), 0, 0); visibility = View.GONE
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
            AuditLog.clear(this); logView.text = "暂无记录"; toast("已清空")
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
        if (showingSettings) refresh()
    }

    override fun onPause() {
        OrbController.setAppForeground(false)
        super.onPause()
    }

    override fun onDestroy() {
        tickRunnable?.let { main.removeCallbacks(it) }
        if (JarvisAgent.onStateChanged != null) JarvisAgent.onStateChanged = null
        super.onDestroy()
    }

    private fun handleAssistIntent(intent: Intent?) {
        JarvisAgent.start(this) // 幂等：App 自己打开时也顺带确保 eventSink 接上（不依赖常驻服务一定先启动）
        if (intent?.getStringExtra(EXTRA_ACTION) == "assist") {
            if (::detailView.isInitialized) detailView.text = "已通过悬浮球/磁贴/数字助理唤起，已开始听。"
            JarvisAgent.triggerListenFromAssist(this)
        }
    }

    private fun startKeepAlive(manual: Boolean) {
        if (JarvisForegroundService.running) {
            if (manual) toast("常驻服务已在运行")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (manual) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
                toast("先授予录音权限，常驻服务才能启动")
            }
            return
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, JarvisForegroundService::class.java))
        } catch (e: Exception) {
            if (manual) toast("常驻服务启动失败：${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startKeepAlive(manual = true)
        }
        if (showingSettings) refresh()
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            setOnClickListener(onClick)
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
