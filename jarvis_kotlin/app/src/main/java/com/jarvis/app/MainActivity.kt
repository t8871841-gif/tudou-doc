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
import android.widget.ImageView
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
import com.jarvis.app.local.LocalLlm
import com.jarvis.app.memory.MemoryService
import com.jarvis.app.net.AgnesClient
import com.jarvis.app.skill.SkillService
import com.jarvis.app.skill.SkillTrace
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
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
        const val REQ_PICK_MODEL = 5151
        const val REQ_PROJECTION = 5152
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
    private lateinit var bgView: HudBackgroundView
    private lateinit var wakeChip: TextView
    private lateinit var emblemMotionView: com.jarvis.app.hud.EmblemMotionView
    private lateinit var muteButton: ImageButton
    private lateinit var keyboardToggle: ImageButton
    private lateinit var memoryListContainer: LinearLayout
    private lateinit var skillListContainer: LinearLayout
    private lateinit var localStatusView: TextView

    // 设置二级页面：目录 + 分页宿主（真机反馈：不再十个分区一长条）
    private lateinit var settingsListPanel: LinearLayout
    private lateinit var settingsPageHost: LinearLayout
    private lateinit var settingsPageContent: LinearLayout
    private val settingsPages = mutableMapOf<String, LinearLayout>()

    // 批三：记忆列表加载/删除等异步 UI 操作的作用域，onDestroy 取消
    private val uiScope = MainScope()
    private var showingSettings = false
    private var showInput = false
    private var chatInFlight = false

    private val main = Handler(Looper.getMainLooper())

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 批八：真全屏——隐藏系统状态栏（顶部下拉可临时唤出），此前主界面一直顶着状态栏不算全屏。
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

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
        // 批八：原先这里还盖着一层全屏黑色半透明渐变膜（上 65% 黑→中透明→下 80% 黑），
        // 整个界面发朦就是它——按用户要求撤掉；背景的光晕+点阵（上一层）与底部玻璃框保留不动。

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(buildTopBar())

        homePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        homePanel.addView(buildEmblem())
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
        // 真机反馈（jk-v11）：语音问答同步进对话——用户的话和回复都落成气泡
        JarvisAgent.onUserText = { t -> runOnUiThread { addBubble(t, mine = true) } }
        JarvisAgent.onReplyText = { t -> runOnUiThread { addBubble(t, mine = false) } }
        onAgentStateChanged(JarvisAgent.currentState())

        handleAssistIntent(intent)
        // 批二：常驻与否现在是"设置里记住的选择"，不再是每次开 App 都无条件偷偷启动。
        if (JarvisPrefs.isKeepAliveEnabled(this)) startKeepAlive(manual = false)
    }

    // ---------- 顶栏：只留齿轮设置图标（2026-10-24 用户定：徽章移居中，SYSTEM ACTIVE 整行删除） ----------

    private fun buildTopBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(20), 0)
        val gear = ImageButton(this@MainActivity).apply {
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
        addView(gear, LinearLayout.LayoutParams(dp(38), dp(38)))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
    }

    // ---------- 中央徽章：批二十二反应堆放大——容器 420dp（点阵外沿 380dp + 唤醒词胶囊区 40dp），
    // 徽章本体 176dp 不动，虚线环 drawable 放大到 330dp 慢转；上面不压任何字 ----------

    private fun buildEmblem(): FrameLayout {
        val emblem = FrameLayout(this)
        emblem.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(420))
        // 批十八：徽章反应堆动效层（衬在徽章后面，只画环/粒子/声波，不盖徽章本体）。
        // 批二十二：动效层与徽章/虚线环共用 bottomMargin 40dp，使三者圆心对齐在容器 190dp 处。
        emblemMotionView = com.jarvis.app.hud.EmblemMotionView(this).apply {
            mode = { JarvisAgent.currentState() }
            micLevel = { OfflineAsrSession.currentLevel() }
        }
        emblem.addView(emblemMotionView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            bottomMargin = dp(40)
        })
        val badge = ImageView(this).apply {
            setImageResource(R.drawable.hud_badge)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setOnClickListener { if (::emblemMotionView.isInitialized) emblemMotionView.pulse() }
        }
        emblem.addView(badge, FrameLayout.LayoutParams(dp(176), dp(176), Gravity.CENTER).apply {
            bottomMargin = dp(40)
        })
        val ring = ImageView(this).apply {
            setImageResource(R.drawable.hud_badge_ring)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        emblem.addView(ring, FrameLayout.LayoutParams(dp(330), dp(330), Gravity.CENTER).apply {
            bottomMargin = dp(40)
        })
        android.animation.ObjectAnimator.ofFloat(ring, "rotation", 0f, 360f).apply {
            duration = 60000
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
        // 徽章呼吸：±2% 缩放，3 秒一周期（与悬浮球呼吸同款语言）。
        for (v in listOf(badge, ring)) {
            android.animation.ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.02f, 1f).apply {
                duration = 3000
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
            android.animation.ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.02f, 1f).apply {
                duration = 3000
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        }
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
        emblem.addView(wakeChip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(4)
        })
        return emblem
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
        // 主界面已无状态文字（舞台与 SYSTEM ACTIVE 行均按用户要求删除），
        // 状态只靠徽章/悬浮球颜色与动效表达；这里只刷新唤醒词胶囊。
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
        // 新消息滑入动效（玻璃框本身不动）：从下方 12dp 处 220ms 滑入并淡入。
        tv.translationY = dp(12).toFloat()
        tv.alpha = 0f
        tv.animate().translationY(0f).alpha(1f).setDuration(220).start()
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendChat() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty() || chatInFlight) return
        chatInFlight = true
        chatInput.text.clear()
        addBubble(text, mine = true)
        // 批三：打字对话与语音同路——先拦截显式记忆指令，未命中再带长期记忆提问。
        uiScope.launch {
            val ack = MemoryService.processExplicitMemory(this@MainActivity, text)
            if (ack != null) {
                chatInFlight = false
                addBubble(ack, mine = false)
                speakReplyIfEnabled(ack)
                return@launch
            }
            // 批八：打字路径与语音同路过本地指令层（打电话/打开应用），命中直接办、回执进气泡不朗读。
            if (com.jarvis.app.command.LocalCommands.tryHandle(this@MainActivity, text) { reply ->
                    runOnUiThread { addBubble(reply, mine = false) }
                }
            ) {
                chatInFlight = false
                return@launch
            }
            // 批十：打字路径同路——天气走真数据工具，不进模型。
            if (com.jarvis.app.data.WeatherTool.looksLikeWeather(text)) {
                val city = com.jarvis.app.data.WeatherTool.extractCity(text) ?: JarvisPrefs.getWeatherCity(this@MainActivity)
                com.jarvis.app.data.WeatherTool.fetch(city, tomorrow = text.contains("明天")) { answer ->
                    runOnUiThread {
                        chatInFlight = false
                        val a = answer ?: "天气没查到，可能是网络不通，你再试一次"
                        addBubble(a, mine = false)
                        speakReplyIfEnabled(a)
                    }
                }
                return@launch
            }
            val mem = MemoryService.getInjectableMemories(this@MainActivity)
            // 批十调配：智能调配开时问答类云端优先、闲聊类才本地先答（与语音同路）
            val localReady = JarvisPrefs.isLocalLlmEnabled(this@MainActivity) && LocalLlm.isReady(this@MainActivity)
            if (localReady && JarvisPrefs.isLocalLlmPrefer(this@MainActivity) && !com.jarvis.app.net.ChatRouter.cloudFirst(text)) {
                val local = LocalLlm.generate(this@MainActivity, text)
                if (!local.isNullOrBlank()) {
                    chatInFlight = false
                    addBubble(local, mine = false)
                    speakReplyIfEnabled(local)
                    return@launch
                }
                askChatAgnes(text, mem, localTried = true)
                return@launch
            }
            askChatAgnes(text, mem, localTried = false)
        }
    }

    /** 批十：界面问答的朗读出口——主界面「朗读」开关开着（未静音）才出声；语音路径由 Agent 自己播，不走这里，防双播。 */
    private fun speakReplyIfEnabled(text: String) {
        if (!JarvisPrefs.isMuted(this)) {
            com.jarvis.app.tts.TtsRouter.speak(this, text, onStart = {}, onDone = {})
        }
    }

    private fun askChatAgnes(text: String, mem: String, localTried: Boolean) {
        AgnesClient.ask(this, text, memoryContext = mem,
            onResult = { reply ->
                runOnUiThread {
                    chatInFlight = false
                    addBubble(reply, mine = false)
                    speakReplyIfEnabled(reply)
                }
            },
            onError = { e ->
                val canFallback = !localTried && JarvisPrefs.isLocalLlmEnabled(this) && LocalLlm.isReady(this)
                if (!canFallback) {
                    runOnUiThread {
                        chatInFlight = false
                        addBubble("出错了：$e（Key 在设置里填）", mine = false)
                    }
                    return@ask
                }
                // 云端失败 → 本地兜底（批五），答不出再报原来的错
                uiScope.launch {
                    val local = LocalLlm.generate(this@MainActivity, text)
                    chatInFlight = false
                    if (!local.isNullOrBlank()) {
                        addBubble("（云端连不上，本地模型答）$local", mine = false)
                        speakReplyIfEnabled(local)
                    } else {
                        addBubble("出错了：$e（Key 在设置里填）", mine = false)
                    }
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
            contentDescription = "回答朗读开关"
            setOnClickListener {
                val next = !JarvisPrefs.isMuted(this@MainActivity)
                JarvisPrefs.setMuted(this@MainActivity, next)
                setImageResource(if (next) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off)
                toast(if (next) "回答朗读已关，只显示文字" else "回答朗读已开")
            }
        }
        // 批十：按钮下加「朗读」标签——用户反馈界面上找不到语音播放的开关（原先只有一个没说明的喇叭图标）
        val muteCol = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        muteCol.addView(muteButton, LinearLayout.LayoutParams(dp(44), dp(40)))
        muteCol.addView(TextView(this@MainActivity).apply {
            text = "朗读"
            textSize = 9f
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
        })
        micRow.addView(muteCol)

        val micButton = View(this@MainActivity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF00F0FF.toInt(), 0xFF7000FF.toInt())).apply {
                shape = GradientDrawable.OVAL
            }
            setOnClickListener {
                if (::emblemMotionView.isInitialized) emblemMotionView.pulse()
                JarvisAgent.triggerListenFromAssist(this@MainActivity)
            }
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
        if (showingSettings) {
            closeSettingsPage()
            refresh()
            refreshMemoryList()
            refreshSkillList()
            refreshLocalStatus()
        }
    }

    private fun buildSettingsPanel(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        root.addView(btn("← 回到对话") { toggleSettings() })

        // 真机反馈（jk-v11）：设置改二级页面——先进目录，点一类进一类，不再十个分区一长条。
        settingsListPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsPageHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        settingsPageContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsPageHost.addView(btn("← 返回设置目录") { closeSettingsPage() })
        settingsPageHost.addView(settingsPageContent)
        root.addView(settingsListPanel)
        root.addView(settingsPageHost)

        val pages = listOf<Pair<String, (LinearLayout) -> Unit>>(
            "功能自检" to { v -> buildSelfCheckSection(v) },
            "对话与语音" to { v -> buildVoiceSettingsSection(v) },
            "视觉看屏" to { v -> buildVisionSection(v) },
            "常驻与入口" to { v -> buildEntrySection(v) },
            "记忆" to { v -> buildMemorySection(v) },
            "技能" to { v -> buildSkillSection(v) },
            "本地模型" to { v -> buildLocalLlmSection(v) },
            "主动播报" to { v -> buildAnnounceSection(v) },
            "晨报与出发提醒" to { v -> buildBriefSection(v) },
            "操作审计" to { v -> buildAuditSection(v) },
            "关于" to { v -> buildAboutSection(v) }
        )
        for ((title, builder) in pages) {
            val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            builder(page)
            settingsPages[title] = page
            settingsListPanel.addView(btn(title) { openSettingsPage(title) })
        }
        return ScrollView(this).apply { addView(root) }
    }

    private fun openSettingsPage(title: String) {
        val page = settingsPages[title] ?: return
        (page.parent as? android.view.ViewGroup)?.removeView(page)
        settingsPageContent.removeAllViews()
        settingsPageContent.addView(page)
        settingsListPanel.visibility = View.GONE
        settingsPageHost.visibility = View.VISIBLE
        settingsScroll.scrollTo(0, 0)
    }

    private fun closeSettingsPage() {
        if (!::settingsPageHost.isInitialized) return
        settingsPageHost.visibility = View.GONE
        settingsListPanel.visibility = View.VISIBLE
        settingsScroll.scrollTo(0, 0)
    }

    /** 关于页（2026-10-24 用户定）：上一张 Jarvis 金属字图标放大展示。 */
    private fun buildAboutSection(root: LinearLayout) {
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.about_jarvis)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        })
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        root.addView(TextView(this).apply {
            text = if (ver.isEmpty()) "Jarvis · 个人智能助手" else "Jarvis · 个人智能助手（$ver）"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })
    }

    /** 功能自检页（原设置页顶部块，拆成二级页）：状态、无障碍/电池/常驻入口、悬浮球开关、节点读取。 */
    private fun buildSelfCheckSection(root: LinearLayout) {
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
        root.addView(btn("申请通讯录权限（「打电话给…」指令要用）") {
            requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 6201)
        })
        root.addView(btn("启动常驻服务") { startKeepAlive(manual = true) })
        root.addView(orbButton)
        root.addView(btn("测试：读取当前界面") {
            if (!JarvisAccessibilityService.isRunning()) { toast("无障碍未开启，读不到界面"); return@btn }
            detail2.text = "界面节点树（前 600 字）：\n" + JarvisAccessibilityService.perceiveScreenTree().take(600)
        })
        root.addView(btn("刷新状态") { refresh() })
        root.addView(detail2)
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

        // ---- 批十二：跨应用任务模型切换 + DeepSeek Key ----
        // 任务循环（task/TaskLoop）的大脑二选一：Agnes 带图问；DeepSeek 纯文本，
        // 靠无障碍界面树文字推进（Andee 同款路线，识图不是命脉）。
        val taskBrainBtn = Button(this)
        fun refreshTaskBrainBtn() {
            taskBrainBtn.text = if (JarvisPrefs.getTaskBrain(this) == "deepseek") {
                "跨应用任务模型：DeepSeek"
            } else {
                "跨应用任务模型：Agnes"
            }
        }
        taskBrainBtn.setOnClickListener {
            val next = if (JarvisPrefs.getTaskBrain(this) == "deepseek") "agnes" else "deepseek"
            JarvisPrefs.setTaskBrain(this, next)
            refreshTaskBrainBtn()
            toast(
                if (next == "deepseek") "跨应用任务改用 DeepSeek（纯文本，看界面树操作）"
                else "跨应用任务改用 Agnes（带截图问）"
            )
        }
        refreshTaskBrainBtn()
        root.addView(taskBrainBtn)

        val dsKeyLabel = TextView(this).apply {
            text = "DeepSeek API Key（跨应用任务选 DeepSeek 时用）"; textSize = 12f; setTextColor(0xB3FFFFFF.toInt())
        }
        val dsKeyInput = EditText(this).apply {
            hint = "DeepSeek Key（用 DeepSeek 跑跨应用任务时填）"; setTextColor(Color.WHITE)
            setText(JarvisPrefs.getDeepSeekKey(this@MainActivity))
        }
        root.addView(dsKeyLabel)
        root.addView(dsKeyInput)
        root.addView(btn("保存 DeepSeek Key") {
            JarvisPrefs.setDeepSeekKey(this, dsKeyInput.text.toString())
            toast("已保存")
        })

        // ---- 播报声音：下拉选择 + 试听（批二新增） ----
        root.addView(TextView(this).apply { text = "播报声音"; textSize = 12f; setTextColor(0xB3FFFFFF.toInt()) })
        val savedVoice = JarvisPrefs.getVoice(this).ifBlank { com.jarvis.app.tts.EdgeVoices.DEFAULT_ID }
        var selectedVoice = savedVoice
        val voiceSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                com.jarvis.app.tts.EdgeVoices.LIST.map { it.label }
            )
            val idx = com.jarvis.app.tts.EdgeVoices.LIST.indexOfFirst { it.id == savedVoice }.coerceAtLeast(0)
            setSelection(idx)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    selectedVoice = com.jarvis.app.tts.EdgeVoices.LIST[pos].id
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        root.addView(voiceSpinner)
        val previewBtn = Button(this)
        previewBtn.text = "试听"
        previewBtn.setOnClickListener {
            if (com.jarvis.app.tts.EdgeTts.isSpeaking()) {
                com.jarvis.app.tts.EdgeTts.stop()
                previewBtn.text = "试听"
                return@setOnClickListener
            }
            previewBtn.text = "停止"
            val label = com.jarvis.app.tts.EdgeVoices.labelFor(selectedVoice)
            com.jarvis.app.tts.EdgeTts.speak(
                this, "您好，我是 Jarvis，这是$label，现在的声音就是这样。",
                voice = selectedVoice, ratePct = JarvisPrefs.getRatePct(this), pitchHz = JarvisPrefs.getPitchHz(this),
                onStart = {}, onDone = { ok ->
                    main.post {
                        previewBtn.text = "试听"
                        if (!ok) toast("试听失败：Edge 语音连不上（网络原因居多）")
                    }
                }
            )
        }
        root.addView(previewBtn)
        root.addView(btn("保存声音选择") {
            JarvisPrefs.setVoice(this, selectedVoice)
            toast("已保存，先点“试听”确认效果")
        })
        // 批八：语音引擎三档（治两个声来回跳）——自动/只用云扬/只用系统，点按循环切换。
        val engineBtn = Button(this)
        fun refreshEngineBtn() {
            engineBtn.text = "语音引擎：${JarvisPrefs.ttsEngineLabel(JarvisPrefs.getTtsEngine(this))}（点按切换）"
        }
        engineBtn.setOnClickListener {
            val next = when (JarvisPrefs.getTtsEngine(this)) {
                "auto" -> "edge"
                "edge" -> "system"
                else -> "auto"
            }
            JarvisPrefs.setTtsEngine(this, next)
            refreshEngineBtn()
            toast(
                when (next) {
                    "edge" -> "只用云扬：断网时只出文字，不换系统声"
                    "system" -> "只用系统语音：不依赖网络"
                    else -> "自动：云扬失败重试一次，再落系统语音"
                }
            )
        }
        refreshEngineBtn()
        root.addView(engineBtn)
        // 批十：天气默认城市（问天气不带城市名时用这个）
        root.addView(TextView(this).apply {
            text = "天气默认城市（问天气不带城市时用）"
            textSize = 12f
            setTextColor(0xB3FFFFFF.toInt())
            setPadding(0, dp(10), 0, 0)
        })
        val cityInput = EditText(this).apply {
            setText(JarvisPrefs.getWeatherCity(this@MainActivity))
            setTextColor(Color.WHITE)
            setHintTextColor(0x61FFFFFF.toInt())
        }
        root.addView(cityInput)
        root.addView(btn("保存天气城市") {
            JarvisPrefs.setWeatherCity(this, cityInput.text.toString().trim())
            toast("天气默认城市已保存：${JarvisPrefs.getWeatherCity(this)}")
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

        // ---- 唤醒严格度：严格/标准/宽松三档（批二替换掉原来的 0..100 滑杆） ----
        root.addView(TextView(this).apply {
            text = "唤醒严格度"; textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); setPadding(0, dp(8), 0, 0)
        })
        val strictRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val strictLabels = listOf("严格", "标准", "宽松")
        val strictButtons = ArrayList<Button>()
        fun paintStrict(sel: Int) {
            strictButtons.forEachIndexed { i, b ->
                b.alpha = if (i == sel) 1f else 0.5f
            }
        }
        strictLabels.forEachIndexed { i, label ->
            val b = Button(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    JarvisPrefs.setWakeStrictness(this@MainActivity, i)
                    paintStrict(i)
                }
            }
            strictButtons.add(b)
            strictRow.addView(b)
        }
        paintStrict(JarvisPrefs.getWakeStrictness(this))
        root.addView(strictRow)
        root.addView(TextView(this).apply {
            text = "严格：“贾维斯”须在句首且整句较短；标准：在句首附近；宽松：句中任意位置。" +
                "唤醒后约 6 秒没有有效指令会自动回到待命；10 分钟内误唤醒 3 次会临时调到严格。"
            textSize = 11f; setTextColor(0x8AFFFFFF.toInt()); setPadding(0, dp(4), 0, dp(8))
        })

        root.addView(CheckBox(this).apply {
            text = "播报时允许被打断说话（默认关：麦克风点击才启用；开着时播报期间会开麦听打断）"
            setTextColor(0xB3FFFFFF.toInt())
            isChecked = JarvisPrefs.isBargeInEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> JarvisPrefs.setBargeInEnabled(this@MainActivity, checked) }
        })
    }

    /** 批二新增：视觉看屏配置——开关、模型/接口/Key 覆盖（留空复用 Agnes）、看屏白名单。（坐标格式已按用户要求删除：网格标定法用不上。） */
    private fun buildVisionSection(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "视觉看屏（截图 + 多模态模型）"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(24), 0, dp(8))
        })
        root.addView(CheckBox(this).apply {
            text = "启用视觉看屏"
            setTextColor(0xB3FFFFFF.toInt())
            isChecked = JarvisPrefs.isVisionEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> JarvisPrefs.setVisionEnabled(this@MainActivity, checked) }
        })
        root.addView(TextView(this).apply {
            text = "界面读不到文字、或点不到目标时，截图发给下面配置的模型兜底；银行/支付/钱包/系统设置类界面永不截图。"
            textSize = 11f; setTextColor(0x8AFFFFFF.toInt())
        })
        val vModel = EditText(this).apply { hint = "视觉模型（留空 = 同对话模型）"; setTextColor(Color.WHITE); setText(JarvisPrefs.getVisionModel(this@MainActivity)) }
        val vBase = EditText(this).apply { hint = "视觉接口地址（留空 = 同上）"; setTextColor(Color.WHITE); setText(JarvisPrefs.getVisionBaseUrl(this@MainActivity)) }
        val vKey = EditText(this).apply { hint = "视觉 Key（留空 = 同上）"; setTextColor(Color.WHITE); setText(JarvisPrefs.getVisionApiKey(this@MainActivity)) }
        root.addView(vModel); root.addView(vBase); root.addView(vKey)

        val whitelistInput = EditText(this).apply {
            hint = "额外允许看屏点击的 App 包名，每行一个"
            setTextColor(Color.WHITE)
            minLines = 2; maxLines = 4
            setText(JarvisPrefs.getVisionWhitelistRaw(this@MainActivity))
        }
        root.addView(TextView(this).apply { text = "看屏白名单（高级）"; textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); setPadding(0, dp(8), 0, 0) })
        root.addView(whitelistInput)
        root.addView(TextView(this).apply {
            text = "坐标点击只在“不在黑名单 且 在看屏白名单内”的 App 里允许；含 bank/pay/wallet/settings 的包名会被原生层拒绝。"
            textSize = 11f; setTextColor(0x8AFFFFFF.toInt())
        })
        root.addView(btn("保存视觉看屏设置") {
            JarvisPrefs.setVisionModel(this, vModel.text.toString())
            JarvisPrefs.setVisionBaseUrl(this, vBase.text.toString())
            JarvisPrefs.setVisionApiKey(this, vKey.text.toString())
            JarvisPrefs.setVisionWhitelistRaw(this, whitelistInput.text.toString())
            JarvisAccessibilityService.setUserWhitelist(JarvisPrefs.visionWhitelistSet(this))
            toast("已保存")
        })
        // 批十四补线：Andee 的 ScreenCapture（录屏截图兜底）授权入口——授权必须从 Activity 发起。
        // 无障碍截图拿不到图时任务循环才走这条；不授权不影响主链路（自动退回纯界面树）。
        root.addView(btn("录屏授权（跨应用截图兜底）") {
            val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            if (mpm == null) {
                toast("这台手机拿不到录屏服务")
            } else {
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
            }
        })
    }

    /** 批二新增：常驻与入口——后台常驻待命开关、连续对话开关、几个系统设置快捷入口。 */
    private fun buildEntrySection(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "常驻与入口"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(24), 0, dp(8))
        })
        root.addView(CheckBox(this).apply {
            text = "后台常驻待命（前台服务 + 常驻通知，熄屏也能听“贾维斯”，耗电会增加）"
            setTextColor(0xB3FFFFFF.toInt())
            isChecked = JarvisPrefs.isKeepAliveEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                JarvisPrefs.setKeepAliveEnabled(this@MainActivity, checked)
                if (checked) startKeepAlive(manual = true)
                // 关闭时不主动杀掉已经在跑的前台服务——只是不再"记住要常驻"，下次打开 App 不会自动再启动。
            }
        })
        root.addView(CheckBox(this).apply {
            text = "连续对话（语音回答说完自动继续听，不用再喊一次唤醒词）"
            setTextColor(0xB3FFFFFF.toInt())
            isChecked = JarvisPrefs.isContinuousChat(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> JarvisPrefs.setContinuousChat(this@MainActivity, checked) }
        })
        val linkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        linkRow.addView(btn("设为默认助手") {
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (e: Exception) {
                toast("这台设备没有这个系统页面，去系统设置里手动找“默认数字助理”")
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        root.addView(linkRow)
        root.addView(btn("授权读取通知（来电/短信播报要用）") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(TextView(this).apply {
            text = "“默认助手”具体页面因 ROM 而异，找不到“贾维斯”选项就说明这台设备走的是另一条系统路径，" +
                "暂时只能靠悬浮球/磁贴唤起。"
            textSize = 11f; setTextColor(0x8AFFFFFF.toInt()); setPadding(0, dp(4), 0, 0)
        })
    }

    /** 批三新增：「记忆」分区——总开关、列表（分域+时间）、逐条删除、一键清空。数据在 MemoryService（Room）。 */
    private fun buildMemorySection(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "记忆（画像与长期记忆）"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(24), 0, dp(8))
        })
        root.addView(CheckBox(this).apply {
            text = "启用长期记忆（说“记住：……”写入，对话时自动带上）"
            setTextColor(0xB3FFFFFF.toInt())
            isChecked = JarvisPrefs.isMemoryEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                JarvisPrefs.setMemoryEnabled(this@MainActivity, checked)
                refreshMemoryList()
            }
        })
        memoryListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(memoryListContainer)
        root.addView(btn("刷新记忆列表") { refreshMemoryList() })
        root.addView(btn("清空全部记忆") {
            uiScope.launch {
                MemoryService.clearAll(this@MainActivity)
                toast("记忆已清空")
                refreshMemoryList()
            }
        })
    }

    private fun refreshMemoryList() {
        if (!::memoryListContainer.isInitialized) return
        memoryListContainer.removeAllViews()
        if (!JarvisPrefs.isMemoryEnabled(this)) {
            memoryListContainer.addView(TextView(this).apply {
                text = "记忆已关闭，不会写入也不会带上。"; textSize = 12f; setTextColor(0x8AFFFFFF.toInt())
            })
            return
        }
        memoryListContainer.addView(TextView(this).apply {
            text = "加载中…"; textSize = 12f; setTextColor(0x8AFFFFFF.toInt())
        })
        uiScope.launch {
            val list = MemoryService.getAllMemories(this@MainActivity)
            memoryListContainer.removeAllViews()
            if (list.isEmpty()) {
                memoryListContainer.addView(TextView(this@MainActivity).apply {
                    text = "还没有记忆。对 Jarvis 说“记住：……”就能存一条。"; textSize = 12f; setTextColor(0x8AFFFFFF.toInt())
                })
                return@launch
            }
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            for (item in list) {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = "[${item.domain}] ${item.content} · ${fmt.format(Date(item.timestamp))}"
                    textSize = 13f
                    setTextColor(0xFFE8ECF4.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(this@MainActivity).apply {
                    text = "删除"
                    setOnClickListener {
                        uiScope.launch {
                            MemoryService.deleteMemory(this@MainActivity, item.id)
                            refreshMemoryList()
                        }
                    }
                })
                memoryListContainer.addView(row)
            }
        }
    }

    /** 批四新增：「技能」分区——总开关、技能列表（启停/计数/删除）、轨迹存技能、手动新建。数据在 SkillService（Room）。 */
    private fun buildSkillSection(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "技能（固化任务）"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(24), 0, dp(8))
        })
        root.addView(CheckBox(this).apply {
            text = "启用技能（话里命中触发句就直接重播步骤）"
            setTextColor(0xB3FFFFFF.toInt())
            isChecked = JarvisPrefs.isSkillEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                JarvisPrefs.setSkillEnabled(this@MainActivity, checked)
                refreshSkillList()
            }
        })
        skillListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(skillListContainer)

        // 把最近点按轨迹存为技能
        val traceName = EditText(this).apply {
            hint = "给刚才的操作起个名（如：打开微信收款码）"
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 14f
        }
        root.addView(traceName)
        root.addView(btn("把最近操作存为技能") {
            val name = traceName.text.toString().trim()
            if (name.isEmpty()) {
                toast("先给技能起个名")
                return@btn
            }
            val trace = SkillTrace.snapshot()
            if (trace.isEmpty()) {
                toast("还没有可存的操作记录（先让 Jarvis 点按几次）")
                return@btn
            }
            uiScope.launch {
                SkillService.addSkill(this@MainActivity, name, name, SkillService.tapsToStepsJson(trace))
                SkillTrace.clear()
                traceName.text.clear()
                toast("技能「$name」已保存（${trace.size} 步）")
                refreshSkillList()
            }
        })

        // 手动新建技能：一行一步（开：包名 / 点：目标 / 等：毫秒 / 滑：下）
        val newName = EditText(this).apply {
            hint = "新技能名"
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 14f
        }
        val newTrigger = EditText(this).apply {
            hint = "触发句（话里包含它就执行，如：打开收款码）"
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 14f
        }
        val newSteps = EditText(this).apply {
            hint = "步骤，一行一步：开：包名 / 点：目标 / 等：800 / 滑：下"
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 14f
            minLines = 3
        }
        root.addView(newName)
        root.addView(newTrigger)
        root.addView(newSteps)
        root.addView(btn("保存新技能") {
            val name = newName.text.toString().trim()
            val trigger = newTrigger.text.toString().trim()
            if (name.isEmpty() || trigger.isEmpty()) {
                toast("技能名和触发句都要填")
                return@btn
            }
            val arr = JSONArray()
            val lines = newSteps.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                toast("至少写一步")
                return@btn
            }
            for ((i, line) in lines.withIndex()) {
                val obj: JSONObject? = when {
                    line.startsWith("开：") ->
                        JSONObject().put("t", "open").put("pkg", line.removePrefix("开：").trim())
                    line.startsWith("点：") ->
                        JSONObject().put("t", "tap").put("target", line.removePrefix("点：").trim())
                    line.startsWith("等：") ->
                        line.removePrefix("等：").trim().toLongOrNull()?.let {
                            JSONObject().put("t", "wait").put("ms", it)
                        }
                    line.startsWith("滑：") -> {
                        val d = line.removePrefix("滑：").trim()
                        if (d == "上" || d == "下") JSONObject().put("t", "swipe").put("dir", if (d == "上") "up" else "down") else null
                    }
                    else -> null
                }
                if (obj == null || obj.length() == 0 || (obj.optString("pkg").isEmpty() && obj.optString("t") == "open") ||
                    (obj.optString("target").isEmpty() && obj.optString("t") == "tap")
                ) {
                    toast("第 ${i + 1} 行看不懂：$line")
                    return@btn
                }
                arr.put(obj)
            }
            uiScope.launch {
                SkillService.addSkill(this@MainActivity, name, trigger, arr.toString())
                newName.text.clear(); newTrigger.text.clear(); newSteps.text.clear()
                toast("技能「$name」已保存")
                refreshSkillList()
            }
        })
        root.addView(btn("刷新技能列表") { refreshSkillList() })
    }

    private fun refreshSkillList() {
        if (!::skillListContainer.isInitialized) return
        skillListContainer.removeAllViews()
        if (!JarvisPrefs.isSkillEnabled(this)) {
            skillListContainer.addView(TextView(this).apply {
                text = "技能已关闭，不会命中也不会重播。"; textSize = 12f; setTextColor(0x8AFFFFFF.toInt())
            })
            return
        }
        skillListContainer.addView(TextView(this).apply {
            text = "加载中…"; textSize = 12f; setTextColor(0x8AFFFFFF.toInt())
        })
        uiScope.launch {
            val list = SkillService.getAllSkills(this@MainActivity)
            skillListContainer.removeAllViews()
            if (list.isEmpty()) {
                skillListContainer.addView(TextView(this@MainActivity).apply {
                    text = "还没有技能。可以把最近操作存为技能，或在下面手动新建。"; textSize = 12f; setTextColor(0x8AFFFFFF.toInt())
                })
                return@launch
            }
            for (item in list) {
                val stepCount = SkillService.parseSteps(item.stepsJson).size
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = "${item.name}（${stepCount}步 · 触发：${item.triggerText} · 成功${item.successCount}/失败${item.failCount}）"
                    textSize = 13f
                    setTextColor(0xFFE8ECF4.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(CheckBox(this@MainActivity).apply {
                    isChecked = item.enabled
                    setOnCheckedChangeListener { _, checked ->
                        uiScope.launch {
                            SkillService.setEnabled(this@MainActivity, item, checked)
                        }
                    }
                })
                row.addView(Button(this@MainActivity).apply {
                    text = "删除"
                    setOnClickListener {
                        uiScope.launch {
                            SkillService.deleteSkill(this@MainActivity, item.id)
                            refreshSkillList()
                        }
                    }
                })
                skillListContainer.addView(row)
            }
        }
    }

    /** 批五新增：「本地模型」分区——导入 .task、开关、优先本地、maxTokens、试答、删除。运行时是 LocalLlm（MediaPipe）。 */
    private fun buildLocalLlmSection(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "本地模型（端侧小模型）"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(24), 0, dp(8))
        })
        localStatusView = TextView(this).apply { textSize = 12f; setTextColor(0x8AFFFFFF.toInt()) }
        root.addView(localStatusView)
        refreshLocalStatus()
        root.addView(btn("导入模型文件（.task）") {
            try {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_PICK_MODEL)
            } catch (e: Exception) {
                toast("打不开文件选择器：${e.message}")
            }
        })
        root.addView(CheckBox(this).apply {
            text = "启用本地模型"
            setTextColor(0xB3FFFFFF.toInt())
            isChecked = JarvisPrefs.isLocalLlmEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> JarvisPrefs.setLocalLlmEnabled(this@MainActivity, checked) }
        })
        root.addView(CheckBox(this).apply {
            text = "智能调配（开 = 闲聊本地答、问答云端答；关 = 云端优先、本地只在云端失败时兜底）"
            setTextColor(0xB3FFFFFF.toInt())
            isChecked = JarvisPrefs.isLocalLlmPrefer(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> JarvisPrefs.setLocalLlmPrefer(this@MainActivity, checked) }
        })
        val tokensInput = EditText(this).apply {
            hint = "maxTokens（256–1024，你的 gemma3 上限就是 1024）"
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(JarvisPrefs.getLocalMaxTokens(this@MainActivity).toString())
        }
        root.addView(tokensInput)
        root.addView(btn("保存 maxTokens") {
            val v = tokensInput.text.toString().trim().toIntOrNull()
            if (v == null) {
                toast("填个数字")
            } else {
                JarvisPrefs.setLocalMaxTokens(this@MainActivity, v)
                tokensInput.setText(JarvisPrefs.getLocalMaxTokens(this@MainActivity).toString())
                toast("已保存（实际生效值 ${JarvisPrefs.getLocalMaxTokens(this@MainActivity)}）")
            }
        })
        val testInput = EditText(this).apply {
            hint = "试答一句（如：给我讲个一句话笑话）"
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 14f
        }
        root.addView(testInput)
        root.addView(btn("用本地模型试答") {
            val q = testInput.text.toString().trim()
            if (q.isEmpty()) {
                toast("先写一句话")
                return@btn
            }
            if (!LocalLlm.isReady(this@MainActivity)) {
                toast("还没导入模型")
                return@btn
            }
            toast("本地生成中，稍等…")
            uiScope.launch {
                val out = LocalLlm.generate(this@MainActivity, q)
                toast(if (out.isNullOrBlank()) "本地模型没答出来（加载或生成失败）" else "本地答：${out.take(120)}")
            }
        })
        root.addView(btn("删除已导入的模型") {
            LocalLlm.deleteModel(this@MainActivity)
            refreshLocalStatus()
            toast("模型已删除")
        })
    }

    private fun refreshLocalStatus() {
        if (!::localStatusView.isInitialized) return
        localStatusView.text = LocalLlm.statusText(this)
    }

    @Deprecated("文件选择器回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK) {
                val ok = com.jarvis.app.screen.ScreenCapture.shared(this).start(resultCode, data)
                toast(if (ok) "录屏授权好了，截图兜底可用" else "录屏没启动起来：" + com.jarvis.app.screen.ScreenCapture.shared(this).lastError.ifEmpty { "再试一次" })
            } else {
                toast("没给录屏授权，截图兜底先不用")
            }
            return
        }
        if (requestCode != REQ_PICK_MODEL) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        toast("模型导入中，文件大请稍等…")
        uiScope.launch {
            val path = LocalLlm.importPicked(this@MainActivity, uri)
            if (path != null) {
                JarvisPrefs.setLocalLlmPath(this@MainActivity, path)
                toast("模型已导入")
            } else {
                toast("导入失败，文件读不进来")
            }
            refreshLocalStatus()
        }
    }

    private fun parseHhMm(s: String): Int? {
        val p = s.trim().split(":")
        if (p.size != 2) return null
        val h = p[0].trim().toIntOrNull()
        val m = p[1].trim().toIntOrNull()
        if (h == null || m == null || h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun fmtHhMm(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    /** 批六新增：「主动播报」分区——总开关、来电/短信开关、播报应用名单、勿扰时段。决策在 proactive/Announcer。 */
    private fun buildAnnounceSection(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "主动播报"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(24), 0, dp(8))
        })
        root.addView(CheckBox(this).apply {
            text = "启用主动播报（来电/短信/指定应用通知读出来）"
            setTextColor(0xB3FFFFFF.toInt())
            isChecked = JarvisPrefs.isAnnounceEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> JarvisPrefs.setAnnounceEnabled(this@MainActivity, checked) }
        })
        root.addView(CheckBox(this).apply {
            text = "播报来电"
            setTextColor(0xB3FFFFFF.toInt())
            isChecked = JarvisPrefs.isAnnounceCalls(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> JarvisPrefs.setAnnounceCalls(this@MainActivity, checked) }
        })
        root.addView(CheckBox(this).apply {
            text = "播报短信"
            setTextColor(0xB3FFFFFF.toInt())
            isChecked = JarvisPrefs.isAnnounceSms(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> JarvisPrefs.setAnnounceSms(this@MainActivity, checked) }
        })
        root.addView(TextView(this).apply {
            text = "其他应用通知只播下面名单里的（一行一个包名，如 com.tencent.mm）；验证码类永远不读。"
            textSize = 12f; setTextColor(0x8AFFFFFF.toInt()); setPadding(0, dp(8), 0, 0)
        })
        val appsInput = EditText(this).apply {
            hint = "播报应用包名，一行一个"
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 14f
            minLines = 2
            setText(JarvisPrefs.getAnnounceAppsRaw(this@MainActivity))
        }
        root.addView(appsInput)
        root.addView(btn("保存播报应用名单") {
            JarvisPrefs.setAnnounceAppsRaw(this@MainActivity, appsInput.text.toString())
            toast("已保存")
        })
        root.addView(CheckBox(this).apply {
            text = "勿扰时段不播"
            setTextColor(0xB3FFFFFF.toInt())
            isChecked = JarvisPrefs.isDndEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> JarvisPrefs.setDndEnabled(this@MainActivity, checked) }
        })
        val dndStart = EditText(this).apply {
            hint = "勿扰开始（如 22:00）"
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 14f
            setText(fmtHhMm(JarvisPrefs.getDndStartMin(this@MainActivity)))
        }
        val dndEnd = EditText(this).apply {
            hint = "勿扰结束（如 08:00）"
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 14f
            setText(fmtHhMm(JarvisPrefs.getDndEndMin(this@MainActivity)))
        }
        root.addView(dndStart)
        root.addView(dndEnd)
        root.addView(btn("保存勿扰时段") {
            val s = parseHhMm(dndStart.text.toString())
            val e = parseHhMm(dndEnd.text.toString())
            if (s == null || e == null) {
                toast("时间格式不对，写成 22:00 这样")
            } else {
                JarvisPrefs.setDndStartMin(this@MainActivity, s)
                JarvisPrefs.setDndEndMin(this@MainActivity, e)
                toast("已保存")
            }
        })
    }

    /** 批六新增：「晨报与出发提醒」分区——晨报开关/时间（调度在 ReminderScheduler）、试听、出发提醒（固定提前量降级版）。 */
    private fun buildBriefSection(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "晨报与出发提醒"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(24), 0, dp(8))
        })
        val briefStatus = TextView(this).apply { textSize = 12f; setTextColor(0x8AFFFFFF.toInt()) }
        fun updateBriefStatus() {
            briefStatus.text = if (ReminderScheduler.isBriefOn(this)) {
                "晨报：每天 %02d:%02d".format(ReminderScheduler.briefHour(this), ReminderScheduler.briefMinute(this))
            } else {
                "晨报：关"
            }
        }
        root.addView(briefStatus)
        updateBriefStatus()
        val briefTime = EditText(this).apply {
            hint = "晨报时间（如 07:30）"
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 14f
            setText("%02d:%02d".format(ReminderScheduler.briefHour(this@MainActivity), ReminderScheduler.briefMinute(this@MainActivity)))
        }
        root.addView(briefTime)
        root.addView(btn("开启/更新晨报") {
            val t = parseHhMm(briefTime.text.toString())
            if (t == null) {
                toast("时间格式不对，写成 07:30 这样")
            } else if (ReminderScheduler.scheduleDailyBrief(this@MainActivity, t / 60, t % 60)) {
                updateBriefStatus()
                toast("晨报已开启")
            } else {
                toast("晨报没排上（闹钟权限？）")
            }
        })
        root.addView(btn("关闭晨报") {
            ReminderScheduler.cancelDailyBrief(this@MainActivity)
            updateBriefStatus()
            toast("晨报已关闭")
        })
        root.addView(btn("现在试听一份晨报") {
            val text = com.jarvis.app.proactive.BriefComposer.compose(this@MainActivity)
            if (JarvisPrefs.isMuted(this@MainActivity)) {
                toast(text.take(120))
            } else {
                toast("正在生成晨报语音…")
                com.jarvis.app.tts.TtsRouter.speak(this@MainActivity, text, onStart = {}, onDone = {})
            }
        })
        root.addView(CheckBox(this).apply {
            text = "出发提醒（带地点的日程，按固定提前量提醒；没有路程估算）"
            setTextColor(0xB3FFFFFF.toInt())
            isChecked = JarvisPrefs.isDepartureEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> JarvisPrefs.setDepartureEnabled(this@MainActivity, checked) }
        })
        val leadInput = EditText(this).apply {
            hint = "提前分钟数（如 30）"
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(JarvisPrefs.getDepartureLeadMin(this@MainActivity).toString())
        }
        root.addView(leadInput)
        root.addView(btn("保存提前量") {
            val v = leadInput.text.toString().trim().toIntOrNull()
            if (v == null) {
                toast("填个数字")
            } else {
                JarvisPrefs.setDepartureLeadMin(this@MainActivity, v)
                toast("已保存")
            }
        })
        root.addView(btn("立即扫描今日日程排出发提醒") {
            val n = com.jarvis.app.proactive.DeparturePlanner.scan(this@MainActivity)
            toast(
                when {
                    !JarvisPrefs.isDepartureEnabled(this@MainActivity) -> "出发提醒没开"
                    n == 0 -> "没派出新的（没有带地点且来得及的日程，或日历没授权）"
                    else -> "已排上 $n 条出发提醒"
                }
            )
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
        if (JarvisAgent.onStateChanged != null) JarvisAgent.onStateChanged = null
        JarvisAgent.onUserText = null
        JarvisAgent.onReplyText = null
        uiScope.cancel()
        super.onDestroy()
    }

    private fun handleAssistIntent(intent: Intent?) {
        JarvisAgent.start(this) // 幂等：App 自己打开时也顺带确保 eventSink 接上（不依赖常驻服务一定先启动）
        if (intent?.getStringExtra(EXTRA_ACTION) == "assist") {
            if (::detailView.isInitialized) detailView.text = "已通过悬浮球/磁贴/数字助理唤起，已开始听。"
            JarvisAgent.triggerListenFromAssist(this)
        }
        // 批六：点开晨报/提醒/出发提醒的通知 → 正文进对话并朗读（静音时只显示）
        val action = intent?.getStringExtra(EXTRA_ACTION)
        if (action == "morning_brief" || action == "reminder") {
            val body = intent.getStringExtra(EXTRA_TEXT).orEmpty()
            if (body.isNotBlank() && ::chatList.isInitialized) {
                addBubble(body, mine = false)
                if (!JarvisPrefs.isMuted(this)) {
                    com.jarvis.app.tts.TtsRouter.speak(this, body, onStart = {}, onDone = {})
                }
            }
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
        if (requestCode == 6201) {
            toast(
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) "通讯录权限已给，「打电话给…」能用了"
                else "没给通讯录权限，「打电话给…」用不了"
            )
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
