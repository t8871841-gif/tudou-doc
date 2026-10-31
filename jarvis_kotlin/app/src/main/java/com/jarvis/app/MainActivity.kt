package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.util.Log
import android.net.TrafficStats
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
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
import androidx.webkit.WebViewAssetLoader
import com.jarvis.app.asr.OfflineAsrSession
import com.jarvis.app.hud.GradientLayerView
import com.jarvis.app.hud.HudPalette
import com.jarvis.app.local.LocalLlm
import com.jarvis.app.memory.MemoryService
import com.jarvis.app.net.AgnesClient
import com.jarvis.app.net.DeepSeekClient
import com.jarvis.app.skill.SkillService
import com.jarvis.app.skill.SkillTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
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
        // 彻底融合第 1 步：竖屏舞台背景图，图库选图（ACTION_OPEN_DOCUMENT 回调）。
        const val REQ_PICK_PORTRAIT_BG = 5153
    }

    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private var orbSwitch: android.widget.Switch? = null
    private var orbSwitchUpdating = false
    private lateinit var homePanel: LinearLayout
    private lateinit var contentView: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatInput: EditText
    private lateinit var inputRow: LinearLayout
    private var stageImageView: ImageView? = null
    private lateinit var wakeChip: TextView
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

    // 彻底融合第 1 步：HUD WebView 从 HudActivity 搬进来，常驻只建一次；
    // 竖屏藏起来停渲染，横屏全屏显示。
    // 第 2 步（桥接）：页面说话/打字改走原生 HudBridge；状态改 onStateChanged 回调推送，
    // 500ms 轮询已删。面板假数据不动，等第 3 步接真数据。
    private lateinit var hudWebView: WebView
    private lateinit var hudAssetLoader: WebViewAssetLoader
    private var hudPageLoaded = false
    private var hudLastPushedState: String? = null

    // 彻底融合第 3 步：面板真数据。
    // 对话记录（内存，addBubble 时追加，上限 20 条；页面加载完后推给 subsHistory）。
    private val hudChatHistory = ArrayList<Triple<String, String, Long>>()
    // 上下行速率采样基线（TrafficStats 累计值 + 采样时刻；两次推送之间算平均速率，
    // 基线在 setupHudWebView 里先取一次，第一次推送就有约 1~2 秒的短窗口速率）。
    private var hudLastRxBytes = -1L
    private var hudLastTxBytes = -1L
    private var hudLastNetMs = 0L

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
        // 全息效果已删（2026-10-27）：背景回到渐变层 + 光晕点阵（原 fallback）。
        root.addView(
            GradientLayerView(
                this,
                intArrayOf(HudPalette.BG_TOP, HudPalette.BG_MID, HudPalette.BG_BOTTOM),
                floatArrayOf(0f, 0.5f, 1f), diagonal = true
            ),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        // 彻底融合第 1 步：竖屏不要任何动效——动态光晕点阵背景（HudBackgroundView）
        // 不再加进来；背景只留上面那层 GradientLayerView 纯色渐变。
        // 批八：原先这里还盖着一层全屏黑色半透明渐变膜（上 65% 黑→中透明→下 80% 黑），
        // 整个界面发朦就是它——按用户要求撤掉；背景的光晕+点阵（上一层）与底部玻璃框保留不动。

        contentView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contentView.addView(buildTopBar())

        homePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        homePanel.addView(buildStage())
        buildChatPanel()
        homePanel.addView(chatScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        homePanel.addView(buildBottomPanel())
        contentView.addView(homePanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        settingsScroll = buildSettingsPanel().apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        // 设置页是根布局上的覆盖层（不再塞进竖屏内容列里），横竖屏都能打开。
        root.addView(settingsScroll)

        root.addView(contentView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // HUD WebView 常驻（只建一次），竖屏藏起来停渲染，横屏全屏显示；再按当前方向摆一次。
        setupHudWebView(root)
        applyOrientationLayout()

        // 第 2 步：状态变化同时推给 HUD（setState）；问答文字走 addBubble 时一并推给 HUD 字幕
        // （addBubble 是打字与语音两条路的唯一汇合点——语音回复走 onReplyText、打字回复走
        // sendChat/askChatAgnes，都落进 addBubble；只在回调里推会漏掉打字回复）。
        JarvisAgent.onStateChanged = { s -> main.post { onAgentStateChanged(s); pushHudState(s) } }
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
    }

    // ---------- 中央区域：竖屏静图舞台（彻底融合第 1 步） ----------
    // 银河动画已封存：舞台不再建 GalaxyHoloStageView，改成一张静图（居中裁剪），
    // 默认图是 HUD 默认皮肤静帧，设置「主界面」里可从图库自选；唤醒词胶囊保留在舞台底部。
    // 舞台仍占上部区域 2/3（权重 2:1），对话与底部面板位置不动 ----------

    private fun buildStage(): FrameLayout {
        val stage = FrameLayout(this)
        stage.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f)
        stageImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }.also {
            stage.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        refreshStageImage()
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
        // 主界面已无状态文字（舞台与 SYSTEM ACTIVE 行均按用户要求删除），
        // 状态只靠徽章/悬浮球颜色与动效表达；这里只刷新唤醒词胶囊。
        refreshWakeChip()
    }

    // ---------- 彻底融合第 1 步：HUD WebView 常驻 + 横竖屏切换 ----------

    /** HUD WebView 从 HudActivity 原样搬进来：WebViewAssetLoader 喂 assets、
     * onPageFinished 注入演示菜单、500ms 状态轮询；只建一次，竖屏停渲染、横屏全屏。 */
    private fun setupHudWebView(parent: FrameLayout) {
        hudAssetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        hudWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 第 2 步：挂原生桥（window.JarvisBridge），必须在 loadUrl 之前挂。
            addJavascriptInterface(HudBridge(), "JarvisBridge")
            // hud 是 Three.js 全屏 WebGL，竖屏藏起来时要停渲染，回来再续。
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    return hudAssetLoader.shouldInterceptRequest(request.url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    hudPageLoaded = true
                    injectDemoMenu()
                    injectHudBridge()
                    // 页面刚加载完，把当前状态同步一次（横屏启动时 applyOrientationLayout
                    // 里的推送因页面没加载完被拦掉了，这里补上）。
                    hudLastPushedState = null
                    pushHudState(JarvisAgent.currentState())
                    // 第 3 步：面板真数据 + 对话记录进 HUD。
                    pushHudPanels()
                    pushHudHistory()
                }
            }
            // 把页面 console 打到 logcat，tag HudWebView：真机上 HUD 出问题先看这里。
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d(
                        "HudWebView",
                        "[${message.messageLevel()}] ${message.message()} " +
                            "(${message.sourceId()}:${message.lineNumber()})"
                    )
                    return true
                }
            }
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        parent.addView(hudWebView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        hudWebView.loadUrl("https://appassets.androidplatform.net/assets/hud/index.html")
        // 第 3 步：上下行速率的采样基线先取一次，页面加载完第一次推系统状态时，
        // 距此约 1~2 秒，算出的就是短窗口内的真实平均速率。
        hudLastRxBytes = TrafficStats.getTotalRxBytes()
        hudLastTxBytes = TrafficStats.getTotalTxBytes()
        hudLastNetMs = SystemClock.elapsedRealtime()
        // 设置页永远在最上面（WebView 后加会盖住它，打开设置时 applyOrientationLayout 也会藏起 WebView）。
        settingsScroll.bringToFront()
    }

    /** 按当前方向摆两套内容：竖屏原生界面、横屏 HUD 全屏；设置页开着时两套都藏，只留设置层。
     * 第 2 步：500ms 轮询已删；切到横屏时把当前状态与模型配置再推一次（竖屏期间状态变过，
     * hudLastPushedState 已记下最新值，先清掉再推，否则去重会吞掉这次同步）。 */
    private fun applyOrientationLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (showingSettings) {
            contentView.visibility = View.GONE
            hudWebView.visibility = View.GONE
            hudWebView.onPause()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        if (landscape) {
            contentView.visibility = View.GONE
            hudWebView.visibility = View.VISIBLE
            hudWebView.onResume()
            hudLastPushedState = null
            pushHudState(JarvisAgent.currentState())
            hudWebView.evaluateJavascript("try{if(window.__jarvisRefreshModel)__jarvisRefreshModel()}catch(e){}", null)
            // 第 3 步：切横屏时面板真数据再推一次。
            pushHudPanels()
            // 横屏看 HUD 时不熄屏；回竖屏即恢复。
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            hudWebView.onPause()
            hudWebView.visibility = View.GONE
            contentView.visibility = View.VISIBLE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationLayout()
    }

    /** 横屏看 HUD 时返回键先退演示，没演示在播才退出 App；竖屏返回键行为不变。 */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape && ::hudWebView.isInitialized && hudPageLoaded) {
            hudWebView.evaluateJavascript("typeof demoLive==='function'?demoLive():false") { r ->
                if (r == "true") {
                    hudWebView.evaluateJavascript("if(typeof demoExit==='function')demoExit()", null)
                } else {
                    finishBackPress()
                }
            }
            return
        }
        finishBackPress()
    }

    /** onBackPressed 的默认行为抽出来：evaluateJavascript 的回调是非内联 lambda，
     * 里面不能直接写 super.xxx，改走这里。 */
    @Suppress("DEPRECATION")
    private fun finishBackPress() {
        super.onBackPressed()
    }

    // 在 HUD 自己的菜单里加四个演示按钮（点“菜单”可见，与“语音播报”同款式）。
    // 再点一次同一个演示即收起（与桌面键盘快捷键同款切换逻辑）。
    // 猛禽拆解的中文标签抄自桌面语音服务的 hologram.py（ZH['raptor']）。
    private fun injectDemoMenu() {
        hudWebView.evaluateJavascript(
            """
            (function(){
              var pop = document.getElementById('menuPop');
              if (!pop || document.getElementById('hudDemoBtns')) return;
              var sep = document.createElement('div');
              sep.style.cssText = 'height:1px;background:var(--line);margin:4px 0';
              pop.appendChild(sep);
              var box = document.createElement('div');
              box.id = 'hudDemoBtns';
              pop.appendChild(box);
              function closeMenu(){
                pop.hidden = true;
                var m = document.getElementById('btnMenu');
                if (m) m.setAttribute('aria-expanded','false');
              }
              function add(label, sub, fn){
                var b = document.createElement('button');
                b.type = 'button';
                b.innerHTML = label + '<b>' + sub + '</b>';
                b.addEventListener('click', function(){ closeMenu(); try { fn(); } catch(e){ console.warn('hud demo:', e); } });
                box.appendChild(b);
              }
              add('星舰发射', 'STARSHIP', function(){
                if (typeof SHIP !== 'undefined' && SHIP && SHIP.state.on && !SHIP.state.ending) SHIP.hide();
                else if (typeof shipCommand === 'function') shipCommand({action:'play'});
              });
              add('Roadster 悬停', 'ROADSTER', function(){
                if (typeof RD !== 'undefined' && RD && RD.state.on && !RD.state.ending) RD.hide();
                else if (typeof roadsterCommand === 'function') roadsterCommand({action:'play'});
              });
              add('Optimus 行走', 'OPTIMUS', function(){
                if (typeof OP !== 'undefined' && OP && OP.state.on && !OP.state.ending) OP.hide();
                else if (typeof optimusCommand === 'function') optimusCommand({action:'play'});
              });
              add('猛禽拆解', 'RAPTOR', function(){
                if (typeof MACH !== 'undefined' && MACH && MACH.on) { if (typeof machineHide === 'function') machineHide(); }
                else if (typeof machineCommand === 'function') machineCommand({action:'show', src:'figure-raptor.html', title:'RAPTOR 2 · 全流量分级燃烧发动机', labels:{
                  nozzle:{name:'喷管',say:'出口 1.3 米 · 甲烷冷却'},
                  downcomer:{name:'下降管与主燃料阀',say:'甲烷送进冷却夹套'},
                  controller:{name:'发动机控制器与线束',say:'7 根线缆'},
                  plumbing:{name:'管路',say:'7 根传感管汇入一块 · 9 根供给、吹扫与点火管'},
                  lox:{name:'液氧导管与主阀',say:'从泵送往预燃室'},
                  ftp:{name:'甲烷涡轮泵与富燃预燃室',say:'由富燃燃气驱动'},
                  hotgas:{name:'热燃气歧管',say:'富燃燃气送往喷注器'},
                  chamber:{name:'喷注器与主燃烧室',say:'300 bar · 不用点火器'},
                  orpb:{name:'富氧预燃室',say:'全部液氧都从这里经过'},
                  otp:{name:'液氧涡轮泵',say:'由富氧燃气驱动'},
                  gimbal:{name:'万向节与推力座',say:'把 230 吨推力传给试车台'}
                }});
              });
            })();
            """.trimIndent(),
            null
        )
    }

    // ---------- 彻底融合第 2 步：HudBridge 桥接 ----------

    /**
     * 原生 → 页面 的桥（挂为 window.JarvisBridge）。
     * 注意：@JavascriptInterface 方法跑在 WebView 的桥接线程上，不是主线程，
     * 所以凡是碰 UI / 走 Agent 的都先 main.post 回主线程。
     * 绝不返回 Key 本身：getModelInfo 只给模型 id、Key 是否已填、本地开关。
     */
    inner class HudBridge {
        @JavascriptInterface
        fun startListening() {
            main.post { JarvisAgent.triggerListenFromAssist(this@MainActivity) }
        }

        @JavascriptInterface
        fun sendText(text: String) {
            val q = text.trim()
            if (q.isEmpty()) return
            main.post { submitText(q) }
        }

        @JavascriptInterface
        fun interrupt() {
            // 只在有进行中的环节时才急停；idle 时按 Esc 不做任何事（否则在输入框里按 Esc 也会把 Agent 重置一遍）。
            main.post {
                if (JarvisAgent.currentState() != "idle") JarvisAgent.stopAll(this@MainActivity)
            }
        }

        @JavascriptInterface
        fun openSettings() {
            main.post { if (!showingSettings) toggleSettings() }
        }

        /** 翻转原生朗读开关，返回翻转后“语音播报是否开着”（开 = 没静音）。 */
        @JavascriptInterface
        fun toggleVoice(): Boolean {
            val nextMuted = !JarvisPrefs.isMuted(this@MainActivity)
            JarvisPrefs.setMuted(this@MainActivity, nextMuted)
            main.post {
                if (::muteButton.isInitialized) {
                    muteButton.setImageResource(if (nextMuted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off)
                }
            }
            return !nextMuted
        }

        /** 朗读是否开着（供页面同步“语音播报”按钮的初始文案）。 */
        @JavascriptInterface
        fun isVoiceOn(): Boolean = !JarvisPrefs.isMuted(this@MainActivity)

        /**
         * 原生模型信息（JSON 字符串）：只含模型 id、Key 是否已填、本地模型开关。
         * Key 本身绝不返回。provider 跟原生任务脑子走（getTaskBrain：agnes 默认 / deepseek）。
         */
        @JavascriptInterface
        fun getModelInfo(): String {
            return try {
                val o = JSONObject()
                if (JarvisPrefs.getTaskBrain(this@MainActivity) == "deepseek") {
                    o.put("provider", "deepseek")
                    o.put("base_url", DeepSeekClient.BASE_URL)
                    o.put("model", DeepSeekClient.MODEL)
                    o.put("has_key", JarvisPrefs.getDeepSeekKey(this@MainActivity).isNotBlank())
                } else {
                    o.put("provider", "agnes")
                    o.put("base_url", AgnesClient.BASE_URL)
                    o.put("model", AgnesClient.MODEL)
                    o.put("has_key", JarvisPrefs.getAgnesKey(this@MainActivity).isNotBlank())
                }
                o.put("local_enabled", JarvisPrefs.isLocalLlmEnabled(this@MainActivity))
                o.put("name", "")
                o.toString()
            } catch (e: Exception) {
                "{\"provider\":\"agnes\",\"base_url\":\"\",\"model\":\"\",\"has_key\":false,\"local_enabled\":false,\"name\":\"\"}"
            }
        }
    }

    /** 只在横屏且页面加载完时，把一句话推给 HUD 字幕（typeOut，js/subtitles.js:77）。
     * 文本用 JSONObject.quote 转义，绝不直接拼进 JS。 */
    private fun pushHudSubtitle(who: String, text: String) {
        if (!::hudWebView.isInitialized || !hudPageLoaded) return
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return
        if (showingSettings) return
        val speed = if (who == "jarvis") 40 else 12
        hudWebView.evaluateJavascript(
            "try{if(typeof typeOut==='function')typeOut(" + JSONObject.quote(who) + "," + JSONObject.quote(text) + "," + speed + ")}catch(e){}",
            null
        )
    }

    /** 状态变化时推给 HUD（setState），只在横屏且页面加载完时推；替代已删的 500ms 轮询。 */
    private fun pushHudState(s: String) {
        if (s == hudLastPushedState) return
        hudLastPushedState = s
        if (!::hudWebView.isInitialized || !hudPageLoaded) return
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return
        if (showingSettings) return
        hudWebView.evaluateJavascript(
            "try{if(typeof setState==='function')setState(" + JSONObject.quote(s) + ")}catch(e){}",
            null
        )
        // 第 3 步：状态变了，大脑那格的 Agent 调度也顺手刷新（不另起轮询）。
        pushHudAgents()
    }

    // ---------- 彻底融合第 3 步：面板真数据 ----------

    /** 面板总推送：日程/调度/系统状态一次推完；天气是异步，到货后单独再推一次。
     * 只在横屏且页面加载完、设置页没开时推；三个调用点：onPageFinished、切横屏、
     * onResume 回横屏。天气失败也推一版占位，不留上海假天气。 */
    private fun pushHudPanels() {
        if (!::hudWebView.isInitialized || !hudPageLoaded) return
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return
        if (showingSettings) return
        val reminders = ReminderScheduler.list(this)
        pushHudSchedule(reminders)
        pushHudAgents(reminders.size)
        pushHudSys()
        fetchHudWeather()
    }

    /** 今日安排 + 工作汇报：原生提醒（ReminderScheduler.list，只取今天还没触发的）
     * + 今日晨报（开着且还没到点才算）；没有就推空列表——面板显示空态，6 条假日程不再留。
     * applySchedule 会同时改写汇报的 done/total 与任务列表。 */
    private fun pushHudSchedule(reminders: List<Map<String, Any?>>) {
        val items = JSONArray()
        val nowMs = System.currentTimeMillis()
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 24L * 60 * 60 * 1000
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val rows = ArrayList<Triple<Long, String, String>>() // atMs, title, note
        for (r in reminders) {
            val at = (r["atMs"] as? Long) ?: continue
            if (at < dayStart || at >= dayEnd) continue
            // 标题绝不能空：页面 applySchedule 用 x.title || x.title_en，空标题会变成 undefined，
            // 页面的 escapeHtml(undefined) 会抛错，整个日程面板都渲染不出来。
            rows.add(Triple(at, (r["title"] as? String).orEmpty().ifEmpty { "提醒" }, (r["body"] as? String).orEmpty()))
        }
        if (ReminderScheduler.isBriefOn(this)) {
            val bc = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, ReminderScheduler.briefHour(this@MainActivity))
                set(Calendar.MINUTE, ReminderScheduler.briefMinute(this@MainActivity))
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (bc.timeInMillis > nowMs) rows.add(Triple(bc.timeInMillis, "Jarvis 晨报", "每日晨报"))
        }
        rows.sortedBy { it.first }.forEach { (at, title, note) ->
            val endCal = Calendar.getInstance().apply { timeInMillis = at + 30L * 60 * 1000 }
            items.put(JSONObject().apply {
                put("start", fmt.format(Date(at)))
                put("end", fmt.format(endCal.time))
                put("title", title)
                put("note", note)
                put("done", false)
            })
        }
        hudWebView.evaluateJavascript(
            "try{if(typeof applySchedule==='function')applySchedule(" + items.toString() + ")}catch(e){}",
            null
        )
    }

    /** Agent 调度：只映射原生真实能读到的状态，读不到的不编。
     * 大脑=对话状态；后台任务=TaskLoop 是否在跑（没跑时报已安排的提醒数）；
     * 学习=记忆系统就绪（没有“正在提取”的实时信号，不装成在跑）；
     * 复盘=无原生对应物，固定 off“暂无”。 */
    private fun pushHudAgents(pendingCount: Int = ReminderScheduler.list(this).size) {
        val state = JarvisAgent.currentState()
        val brainDesc = when (state) {
            "listening" -> "正在聆听"
            "thinking" -> "正在思考"
            "speaking" -> "正在说话"
            else -> "待命"
        }
        val jobsRunning = com.jarvis.app.task.TaskLoop.isRunning()
        val jobsDesc = if (jobsRunning) "跨应用任务进行中"
            else if (pendingCount > 0) "$pendingCount 条提醒已安排"
            else "暂无后台任务"
        val items = JSONArray().apply {
            put(agentItem("brain", if (state == "idle") "off" else "run", brainDesc, 0.5))
            put(agentItem("jobs", if (jobsRunning) "run" else "off", jobsDesc, 0.5))
            put(agentItem("learn", "off", "记忆就绪", 0.0))
            put(agentItem("review", "off", "暂无", 0.0))
        }
        hudWebView.evaluateJavascript(
            "try{if(typeof agentsUpdate==='function')agentsUpdate(" +
                JSONObject().put("items", items).toString() + ")}catch(e){}",
            null
        )
    }

    private fun agentItem(id: String, state: String, desc: String, progress: Double): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("state", state)
            put("desc", desc)
            put("progress", progress)
        }

    /** 系统状态：内存、上下行、电量都是真值；CPU 没有简单可靠的接口，固定 "--"
     * （页面渲染成 "--%"）。上下行是 TrafficStats 两次推送之间的平均速率；
     * 竖屏待久了第一次切回横屏，窗口很长、速率会很小，是真平均值，下次推送就恢复。 */
    private fun pushHudSys() {
        var memPct: Int? = null
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                if (mi.totalMem > 0) {
                    memPct = ((1.0 - mi.availMem.toDouble() / mi.totalMem.toDouble()) * 100).toInt()
                }
            }
        } catch (_: Exception) {
        }
        val nowMs = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        var down = 0L
        var up = 0L
        if (rx >= 0 && tx >= 0) {
            if (hudLastNetMs > 0 && hudLastRxBytes >= 0 && hudLastTxBytes >= 0) {
                val dt = ((nowMs - hudLastNetMs) / 1000.0).coerceAtLeast(1.0)
                down = ((rx - hudLastRxBytes).coerceAtLeast(0) / dt).toLong()
                up = ((tx - hudLastTxBytes).coerceAtLeast(0) / dt).toLong()
            }
            hudLastRxBytes = rx
            hudLastTxBytes = tx
            hudLastNetMs = nowMs
        }
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = try {
            val f = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val st = f?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {
            false
        }
        val o = JSONObject().apply {
            put("cpu", "--")
            if (memPct != null) put("mem", memPct) else put("mem", "--")
            put("down", down)
            put("up", up)
            if (level in 0..100) put("battery", level)
            put("charging", charging)
        }
        hudWebView.evaluateJavascript(
            "try{if(typeof sysUpdate==='function')sysUpdate(" + o.toString() + ")}catch(e){}",
            null
        )
    }

    /** 天气：结构化取数（WeatherTool.fetchStructured，Open-Meteo），城市取原生默认
     * （JarvisPrefs.getWeatherCity，默认新乡）。到货后单独推 wxUpdate；失败也推一版
     * 占位（"--" / 未更新），不让上海假天气一直留在面板上。回调在 OkHttp 线程，
     * 推页面必须回主线程。 */
    private fun fetchHudWeather() {
        val city = JarvisPrefs.getWeatherCity(this)
        com.jarvis.app.data.WeatherTool.fetchStructured(city) { info ->
            main.post {
                if (!::hudWebView.isInitialized || !hudPageLoaded) return@post
                if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return@post
                if (showingSettings) return@post
                pushHudWeather(info)
            }
        }
    }

    private fun pushHudWeather(info: com.jarvis.app.data.WeatherTool.WeatherInfo?) {
        val o = JSONObject()
        if (info == null) {
            o.put("temp", "--")
            o.put("now", "未更新")
            o.put("city", JarvisPrefs.getWeatherCity(this))
            o.put("humidity", "--")
            val days = JSONArray()
            val todayWeek = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1) % 7
            for (i in 0 until 3) {
                days.put(JSONObject().apply {
                    put("week", (todayWeek + i) % 7)
                    put("day", "暂无")
                    put("low", "--")
                    put("high", "--")
                })
            }
            o.put("days", days)
        } else {
            o.put("temp", info.temp)
            o.put("now", info.now)
            o.put("city", info.city)
            o.put("humidity", info.humidity)
            val days = JSONArray()
            info.days.forEach { d ->
                days.put(JSONObject().apply {
                    put("week", d.week)
                    put("day", d.day)
                    put("low", d.low)
                    put("high", d.high)
                })
            }
            o.put("days", days)
        }
        hudWebView.evaluateJavascript(
            "try{if(typeof wxUpdate==='function')wxUpdate(" + o.toString() + ")}catch(e){}",
            null
        )
    }

    /** 对话记录进 HUD：把内存里的最近 20 条（addBubble 时记的）推给 subsHistory；
     * 只在页面加载完推一次。页面自己的 localStorage 会接着保存，之后的新消息走 typeOut。 */
    private fun pushHudHistory() {
        if (hudChatHistory.isEmpty()) return
        val items = JSONArray()
        hudChatHistory.forEach { (who, text, t) ->
            items.put(JSONObject().apply {
                put("who", who)
                put("en", text)
                put("zh", "")
                put("t", t)
            })
        }
        hudWebView.evaluateJavascript(
            "try{if(typeof subsHistory==='function')subsHistory(" + items.toString() + ")}catch(e){}",
            null
        )
    }

    // 运行时注入桥接改写（hud/ 文件一个字不改）：沿用页面自己的“联网版改写”模式
    // （live.js 在 ws 连上后改写 handle/startListen/stopListen 的做法）。
    // 改写后：说话按钮与打字走原生 HudBridge；演示版假听写、本机正则 respond()、
    // speechSynthesis 全都不再走；菜单“语音播报”改成原生朗读开关；“大脑 模型”
    // 浮层喂原生模型配置（ONBOARD.msg 的 status 入口）；Esc 键补一个原生急停，
    // 否则页面只停显示、原生还在听，状态会对不上。
    private fun injectHudBridge() {
        hudWebView.evaluateJavascript(
            """
            (function(){
              try {
                if (typeof JarvisBridge === 'undefined') return;
                if (!window.__jarvisBridgeOn) {
                  window.__jarvisBridgeOn = true;
                  handle = function(q){
                    q = (q || '').trim(); if (!q) return;
                    try { openTalk(); } catch(e){}
                    JarvisBridge.sendText(q);
                  };
                  startListen = function(){
                    try { openTalk(); } catch(e){}
                    JarvisBridge.startListening();
                  };
                  stopListen = function(){};
                  try {
                    addEventListener('keydown', function(e){
                      if (e.key === 'Escape') { try { JarvisBridge.interrupt(); } catch(err){} }
                    }, true);
                  } catch(e){}
                  var vb = document.getElementById('btnVoice');
                  if (vb) {
                    var nb = vb.cloneNode(true);
                    vb.parentNode.replaceChild(nb, vb);
                    var syncVoice = function(){
                      try {
                        var on = JarvisBridge.isVoiceOn();
                        var b = nb.querySelector('b'); if (b) b.textContent = on ? '开' : '关';
                        nb.setAttribute('aria-pressed', String(on));
                      } catch(e){}
                    };
                    nb.addEventListener('click', function(){
                      try { JarvisBridge.toggleVoice(); } catch(e){}
                      syncVoice();
                    });
                    syncVoice();
                  }
                  window.__jarvisPushModel = function(force){
                    try {
                      if (typeof ONBOARD === 'undefined') return;
                      var info = JSON.parse(JarvisBridge.getModelInfo());
                      if (!info.has_key && !force) return;
                      var presets = [];
                      if (info.provider === 'deepseek') {
                        presets.push({id:'deepseek', name:'DeepSeek', base_url: info.base_url, model: info.model, key: true, key_url: 'https://platform.deepseek.com'});
                      } else {
                        presets.push({id:'agnes', name:'Agnes', base_url: info.base_url, model: info.model, key: true, key_url: ''});
                      }
                      ONBOARD.msg({kind:'status', presets: presets, configured: !!info.has_key, provider: info.provider, base_url: info.base_url, model: info.model, has_key: !!info.has_key, name: info.name || ''});
                    } catch(e){ console.warn('hud model:', e); }
                  };
                  window.__jarvisRefreshModel = function(){
                    try { var p = document.getElementById('llmPanel'); if (p && !p.hidden) return; } catch(e){}
                    window.__jarvisPushModel();
                  };
                  var pop = document.getElementById('menuPop');
                  if (pop) {
                    var btns = pop.querySelectorAll('button');
                    for (var i = 0; i < btns.length; i++) {
                      var b = btns[i];
                      if (b.textContent && b.textContent.indexOf('大脑') === 0) {
                        b.addEventListener('click', function(){ setTimeout(function(){ window.__jarvisPushModel(true); }, 60); });
                        break;
                      }
                    }
                    if (!document.getElementById('hudSettingsBtn')) {
                      var sep2 = document.createElement('div');
                      sep2.style.cssText = 'height:1px;background:var(--line);margin:4px 0';
                      pop.appendChild(sep2);
                      var sb = document.createElement('button');
                      sb.type = 'button'; sb.id = 'hudSettingsBtn';
                      sb.innerHTML = '设置<b>原生</b>';
                      sb.addEventListener('click', function(){
                        pop.hidden = true;
                        var m = document.getElementById('btnMenu');
                        if (m) m.setAttribute('aria-expanded','false');
                        try { JarvisBridge.openSettings(); } catch(e){}
                      });
                      pop.appendChild(sb);
                    }
                  }
                  // 第 3 步：出行块没有真实数据源，隐藏（不改 hud/ 文件）；面板标题也改回“天气”。
                  var trip = document.querySelector('#pWeather .trip');
                  if (trip) trip.style.display = 'none';
                  var route = document.getElementById('route');
                  if (route) route.style.display = 'none';
                  var wxH = document.querySelector('#pWeather h2');
                  if (wxH && wxH.firstChild) wxH.firstChild.textContent = '天气 ';
                  // 面板开关标识：平时一直都在，点一下出面板，再点一下收回去。
                  // 挂在 body 下（待机 CSS 只藏 .hud 的子元素，挂 body 下才不会被一起藏掉）；
                  // 演示时用下面的 style 藏掉（演示有自己的返回钮）。
                  if (!document.getElementById('hudPanelToggle')) {
                    var ptStyle = document.createElement('style');
                    ptStyle.textContent = 'body.demo #hudPanelToggle{display:none!important}';
                    document.head.appendChild(ptStyle);
                    var pt = document.createElement('button');
                    pt.type = 'button'; pt.id = 'hudPanelToggle';
                    pt.setAttribute('aria-label', '显示面板');
                    pt.style.cssText = 'position:fixed;right:18px;bottom:18px;z-index:60;width:40px;height:40px;border-radius:50%;border:1px solid rgba(var(--sk-pri),.45);background:rgba(var(--sk-pnl),.72);color:rgb(var(--sk-ink));font-size:19px;line-height:1;display:flex;align-items:center;justify-content:center;opacity:.75;cursor:pointer;padding:0';
                    // 点按前是否在待机（MutationObserver 回调是异步微任务，本次点按的事件派发
                    // 完成前它还不会跑，所以这里存的值永远是点按发生之前的状态）。
                    var ptDormant = document.body.classList.contains('dormant');
                    var ptSync = function(){
                      var hidden = document.body.classList.contains('dormant');
                      pt.textContent = hidden ? '▦' : '✕';
                      pt.setAttribute('aria-label', hidden ? '显示面板' : '隐藏面板');
                    };
                    pt.addEventListener('pointerdown', function(e){
                      try { e.stopPropagation(); } catch(err){}
                      try {
                        // 关键：不能读实时的 class。页面级唤醒监听挂在 window 的 pointerdown 上且是
                        // 捕获阶段（interaction.js:19，第三个参数 true），比按钮目标阶段的处理先跑。
                        // 待机时点按钮，捕获监听先把 dormant 摘了，处理再读 class 读到的已是“不在待
                        // 机”，于是走了收起分支又藏回去，一次点击净效果为零。改读点按前状态 ptDormant。
                        if (ptDormant) {
                          // 点按前在待机：捕获监听已经唤醒了，这里再调一次是幂等的、无害。
                          dormHold = false; wakeUI(); sleepUI(30000);
                          ptDormant = false;
                        } else {
                          // 点按前面板在外面：收起来。
                          // 注意：不能调 dormNow()——它会把 dormHold 置 true，此后 setState('listening')
                          // 调 wakeUI 会直接 return，下一轮说话面板都出不来。这里照抄 sleepUI 定时到点那条
                          // 路（只加 dormant 类、不动 dormHold），说话时 wakeUI 仍能正常唤醒。
                          try { holoHide(); } catch(err2){}
                          clearTimeout(dormT);
                          document.body.classList.add('dormant');
                          ptDormant = true;
                        }
                      } catch(err){}
                      ptSync();
                    });
                    // 图标跟实际走：面板被别的途径唤醒/藏起来时也同步（说话、15 秒定时等），
                    // 同时把 ptDormant 同步成最新值（下次点按读到的就是点按前的状态）。
                    try {
                      var ptObs = new MutationObserver(function(){ ptDormant = document.body.classList.contains('dormant'); ptSync(); });
                      ptObs.observe(document.body, {attributes:true, attributeFilter:['class']});
                    } catch(err){}
                    ptSync();
                    document.body.appendChild(pt);
                  }
                }
                window.__jarvisPushModel();
              } catch(e){ console.warn('hud bridge:', e); }
            })();
            """.trimIndent(),
            null
        )
    }

    // ---------- 彻底融合第 1 步：竖屏舞台静图 ----------

    /** 舞台图：没选过用内置默认静帧；选过用图库那张（按宽度等比缩小解码，大照片不占满内存）。
     * 授权丢了或解不出来就回默认图，不报错。 */
    private fun refreshStageImage() {
        val iv = stageImageView ?: return
        val uriStr = JarvisPrefs.getPortraitBgUri(this)
        if (uriStr.isBlank()) {
            iv.setImageResource(R.drawable.hud_default_still)
            return
        }
        val uri = try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            null
        }
        if (uri == null) {
            iv.setImageResource(R.drawable.hud_default_still)
            return
        }
        uiScope.launch(Dispatchers.IO) {
            val bmp = decodeSampledBitmap(uri, 1080)
            withContext(Dispatchers.Main) {
                if (bmp != null) iv.setImageBitmap(bmp) else iv.setImageResource(R.drawable.hud_default_still)
            }
        }
    }

    private fun decodeSampledBitmap(uri: Uri, reqWidth: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val w = bounds.outWidth
            while (w / sample > reqWidth) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
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
        // 彻底融合第 1 步：竖屏不要任何动效——新消息滑入动画已删，气泡直接出现。
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        // 第 2 步：气泡同时推给 HUD 字幕（见 onCreate 注释——这是两条路的唯一汇合点）。
        pushHudSubtitle(if (mine) "you" else "jarvis", text)
        // 第 3 步：对话记进内存列表（上限 20 条），页面加载完后推给 HUD 字幕记录。
        hudChatHistory.add(Triple(if (mine) "you" else "jarvis", text, System.currentTimeMillis() / 1000))
        if (hudChatHistory.size > 20) hudChatHistory.removeAt(0)
    }

    /** 设置里「清空对话」：只清对话框气泡，回到刚打开时的问候语；长期记忆库不受影响。 */
    private fun clearChat() {
        if (!::chatList.isInitialized) return
        chatList.removeAllViews()
        // 第 3 步：对话记录也清掉（下面 addBubble 会把问候语重新记进去），
        // 否则下次页面加载还会把清掉之前的对话推回 HUD 字幕记录。
        hudChatHistory.clear()
        addBubble("你好，先生。我是 Jarvis，打字发消息或点麦克风说话都行。", mine = false)
    }

    private fun sendChat() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty()) return
        chatInput.text.clear()
        submitText(text)
    }

    /** 打字与 HUD 桥接共用的提交入口（第 2 步抽出来）：记忆拦截→本地指令→天气→记忆注入→本地/云端。
     * 行为与原来 sendChat 里那段一字不差，只是入口从输入框改成参数。 */
    private fun submitText(text: String) {
        val q = text.trim()
        if (q.isEmpty() || chatInFlight) return
        chatInFlight = true
        addBubble(q, mine = true)
        // 批三：打字对话与语音同路——先拦截显式记忆指令，未命中再带长期记忆提问。
        uiScope.launch {
            val ack = MemoryService.processExplicitMemory(this@MainActivity, q)
            if (ack != null) {
                chatInFlight = false
                addBubble(ack, mine = false)
                speakReplyIfEnabled(ack)
                return@launch
            }
            // 批八：打字路径与语音同路过本地指令层（打电话/打开应用），命中直接办、回执进气泡不朗读。
            if (com.jarvis.app.command.LocalCommands.tryHandle(this@MainActivity, q) { reply ->
                    runOnUiThread { addBubble(reply, mine = false) }
                }
            ) {
                chatInFlight = false
                return@launch
            }
            // 批十：打字路径同路——天气走真数据工具，不进模型。
            if (com.jarvis.app.data.WeatherTool.looksLikeWeather(q)) {
                val city = com.jarvis.app.data.WeatherTool.extractCity(q) ?: JarvisPrefs.getWeatherCity(this@MainActivity)
                com.jarvis.app.data.WeatherTool.fetch(city, tomorrow = q.contains("明天")) { answer ->
                    runOnUiThread {
                        chatInFlight = false
                        val a = answer ?: "天气没查到，可能是网络不通，你再试一次"
                        addBubble(a, mine = false)
                        speakReplyIfEnabled(a)
                    }
                }
                return@launch
            }
            // 第一梯队批一：注入改按这句话做相关性检索，不再是「最近 15 条全量拼」。
            val mem = MemoryService.getInjectableMemories(this@MainActivity, q)
            // 批十调配 + 第二梯队批六再调配：智能调配开时 ChatRouter 综合内容/网络/电量/
            // 隐私信号判定，不只是"问答云端、闲聊本地"（与语音同路）
            val localReady = JarvisPrefs.isLocalLlmEnabled(this@MainActivity) && LocalLlm.isReady(this@MainActivity)
            if (localReady && JarvisPrefs.isLocalLlmPrefer(this@MainActivity) && !com.jarvis.app.net.ChatRouter.cloudFirst(this@MainActivity, q)) {
                // 本地模型上下文预算小，只要精简版记忆（Top-2），不是云端那份 mem（Top-8）。
                val localMem = MemoryService.getInjectableMemories(this@MainActivity, q, topN = 2)
                val local = LocalLlm.generate(this@MainActivity, q, localMem)
                if (!local.isNullOrBlank()) {
                    chatInFlight = false
                    addBubble(local, mine = false)
                    speakReplyIfEnabled(local)
                    triggerMemoryExtraction(q, local)
                    return@launch
                }
                askChatAgnes(q, mem, localTried = true)
                return@launch
            }
            askChatAgnes(q, mem, localTried = false)
        }
    }

    /** 批十：界面问答的朗读出口——主界面「朗读」开关开着（未静音）才出声；语音路径由 Agent 自己播，不走这里，防双播。 */
    private fun speakReplyIfEnabled(text: String) {
        if (!JarvisPrefs.isMuted(this)) {
            com.jarvis.app.tts.TtsRouter.speak(this, text, onStart = {}, onDone = {})
        }
    }

    /**
     * 第一梯队批二：视觉链路自测——现画一张白底黑字写着一个随机数字的小图，发给
     * AgnesClient.askVision 问"图里是什么数字"，回复里包含这个数字才算真正识图成功。
     * 只是"接口没报 HTTP 错误"不算数——模型完全可能忽略图片、只按文字瞎编一个答案，
     * 那种情况下旧的"接口通不通"判断法会误报"能用"。
     */
    private fun runVisionProbe(onDone: (Boolean, String) -> Unit) {
        val digit = (1..9).random()
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 180f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText(digit.toString(), 150f, 210f, paint)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        AgnesClient.askVision(
            this, "这张图片正中央写着一个阿拉伯数字，只回答这一个数字，不要任何其他文字。", b64,
            onResult = { reply ->
                val got = Regex("\\d").find(reply)?.value
                if (got == digit.toString()) {
                    onDone(true, "视觉链路正常：发了数字 $digit，模型答对了。")
                } else {
                    onDone(false, "视觉链路不可靠：发了数字 $digit，模型回答「${reply.take(40)}」——" +
                        "接口没报错，但没能正确识图，可能是模型不支持识图或在瞎猜。")
                }
            },
            onError = { msg -> onDone(false, "视觉链路不通：$msg") }
        )
    }

    /** 第一梯队批一：问答回复之后异步触发隐式记忆提取，不阻塞 UI，失败不影响对话。 */
    private fun triggerMemoryExtraction(userText: String, reply: String) {
        uiScope.launch { MemoryService.extractImplicit(this@MainActivity, userText, reply) }
    }

    private fun askChatAgnes(text: String, mem: String, localTried: Boolean) {
        AgnesClient.ask(this, text, memoryContext = mem,
            onResult = { reply ->
                runOnUiThread {
                    chatInFlight = false
                    addBubble(reply, mine = false)
                    speakReplyIfEnabled(reply)
                }
                triggerMemoryExtraction(text, reply)
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
                    val localMem = MemoryService.getInjectableMemories(this@MainActivity, text, topN = 2)
                    val local = LocalLlm.generate(this@MainActivity, text, localMem)
                    chatInFlight = false
                    if (!local.isNullOrBlank()) {
                        addBubble("（云端连不上，本地模型答）$local", mine = false)
                        speakReplyIfEnabled(local)
                        triggerMemoryExtraction(text, local)
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
        settingsScroll.visibility = if (showingSettings) View.VISIBLE else View.GONE
        if (showingSettings) {
            // 设置页是根布局上的覆盖层，横竖屏都能打开；打开时两套主界面内容都藏起来，WebView 也停渲染。
            contentView.visibility = View.GONE
            hudWebView.visibility = View.GONE
            hudWebView.onPause()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            closeSettingsPage()
            refresh()
            refreshMemoryList()
            refreshSkillList()
            refreshLocalStatus()
        } else {
            applyOrientationLayout()
        }
    }

    private fun buildSettingsPanel(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "设置"; textSize = 22f; setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(12))
        })
        root.addView(TextView(this).apply {
            text = "‹ 回到对话"; textSize = 15f; setTextColor(HudPalette.GALAXY_MID)
            setPadding(0, dp(4), 0, dp(12))
            setOnClickListener { toggleSettings() }
        })

        // 真机反馈（jk-v11）：设置改二级页面——先进目录，点一类进一类，不再十个分区一长条。
        settingsListPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsPageHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        settingsPageContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsPageHost.addView(TextView(this).apply {
            text = "‹ 返回设置目录"; textSize = 15f; setTextColor(HudPalette.GALAXY_MID)
            setPadding(0, dp(4), 0, dp(12))
            setOnClickListener { closeSettingsPage() }
        })
        settingsPageHost.addView(settingsPageContent)
        root.addView(settingsListPanel)
        root.addView(settingsPageHost)

        val pages = listOf<Triple<String, String, (LinearLayout) -> Unit>>(
            Triple("✅", "功能自检", { v -> buildSelfCheckSection(v) }),
            Triple("💬", "对话与语音", { v -> buildVoiceSettingsSection(v) }),
            Triple("🖼️", "主界面", { v -> buildHomeScreenSection(v) }),
            Triple("👁️", "视觉看屏", { v -> buildVisionSection(v) }),
            Triple("🚀", "常驻与入口", { v -> buildEntrySection(v) }),
            Triple("🧠", "记忆", { v -> buildMemorySection(v) }),
            Triple("🧩", "技能", { v -> buildSkillSection(v) }),
            Triple("🤖", "本地模型", { v -> buildLocalLlmSection(v) }),
            Triple("📢", "主动播报", { v -> buildAnnounceSection(v) }),
            Triple("⏰", "晨报与出发提醒", { v -> buildBriefSection(v) }),
            Triple("📋", "操作审计", { v -> buildAuditSection(v) }),
            Triple("ℹ️", "关于", { v -> buildAboutSection(v) })
        )
        for ((icon, title, builder) in pages) {
            val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            builder(page)
            styleSettingsInputs(page)
            settingsPages[title] = page
            settingsListPanel.addView(settingRow(icon, title) { openSettingsPage(title) })
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
        val orbRow = switchRow("悬浮球", "由无障碍服务承载，关闭后悬浮球消失", OrbController.isEnabled(), onChecked = { checked ->
            if (orbSwitchUpdating) return@switchRow
            if (!JarvisAccessibilityService.isRunning()) {
                toast("先开启无障碍服务，悬浮球由它承载")
                orbSwitchUpdating = true
                orbSwitch?.isChecked = false
                orbSwitchUpdating = false
                return@switchRow
            }
            OrbController.setEnabled(checked)
            refresh()
        }, onCreated = { orbSwitch = it })
        // 第二梯队批八：悬浮球现在支持摆在屏幕任意位置（不再强制吸左右边）、贴边一段
        // 时间不碰会自动收起一部分、腾出手指误触的地方；这个开关只管"贴边自动收起"
        // 这一下，不想要可以关掉，关了之后贴边也会一直完整显示，不会收起。
        val orbAutoHideRow = switchRow("悬浮球贴边自动收起", "贴着屏幕左右边一段时间不碰，自动收起大半，留一小截在外面",
            JarvisPrefs.isOrbEdgeAutoHideEnabled(this@MainActivity)) { checked ->
            JarvisPrefs.setOrbEdgeAutoHideEnabled(this@MainActivity, checked)
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
        root.addView(orbRow)
        root.addView(orbAutoHideRow)
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

        root.addView(sectionTitle("对话 Key"))
        val keyLabel = TextView(this).apply {
            text = "Agnes API Key（对话用，不会写死在代码里）"; textSize = 12f; setTextColor(0xB3FFFFFF.toInt())
        }
        val keyInput = EditText(this).apply {
            hint = "在此粘贴 Key"; setTextColor(Color.WHITE)
            setText(JarvisPrefs.getAgnesKey(this@MainActivity))
        }
        root.addView(keyLabel)
        root.addView(keyInput)
        root.addView(primaryBtn("保存 Key") {
            JarvisPrefs.setAgnesKey(this, keyInput.text.toString())
            toast("已保存")
        })

        // ---- 批十二：跨应用任务模型切换 + DeepSeek Key ----
        // 任务循环（task/TaskLoop）的大脑二选一：Agnes 带图问；DeepSeek 纯文本，
        // 靠无障碍界面树文字推进（Andee 同款路线，识图不是命脉）。
        root.addView(segmentedRow("跨应用任务模型", listOf("Agnes", "DeepSeek"),
            if (JarvisPrefs.getTaskBrain(this) == "deepseek") 1 else 0) { i ->
            val next = if (i == 1) "deepseek" else "agnes"
            JarvisPrefs.setTaskBrain(this, next)
            toast(
                if (next == "deepseek") "跨应用任务改用 DeepSeek（纯文本，看界面树操作）"
                else "跨应用任务改用 Agnes（带截图问）"
            )
        })

        val dsKeyLabel = TextView(this).apply {
            text = "DeepSeek API Key（跨应用任务选 DeepSeek 时用）"; textSize = 12f; setTextColor(0xB3FFFFFF.toInt())
        }
        val dsKeyInput = EditText(this).apply {
            hint = "DeepSeek Key（用 DeepSeek 跑跨应用任务时填）"; setTextColor(Color.WHITE)
            setText(JarvisPrefs.getDeepSeekKey(this@MainActivity))
        }
        root.addView(dsKeyLabel)
        root.addView(dsKeyInput)
        root.addView(primaryBtn("保存 DeepSeek Key") {
            JarvisPrefs.setDeepSeekKey(this, dsKeyInput.text.toString())
            toast("已保存")
        })

        // ---- 播报声音：下拉选择 + 试听（批二新增） ----
        root.addView(sectionTitle("播报声音"))
        val savedVoice = JarvisPrefs.getVoice(this).ifBlank { com.jarvis.app.tts.EdgeVoices.DEFAULT_ID }
        var selectedVoice = savedVoice
        val voiceSpinner = android.widget.Spinner(this).apply {
            // 浅色主题下原 adapter 的选中行是深字、落在深底上看不清；选中行改白字、下拉行改深字。
            adapter = object : android.widget.ArrayAdapter<String>(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                com.jarvis.app.tts.EdgeVoices.LIST.map { it.label }
            ) {
                override fun getView(pos: Int, v: View?, parent: android.view.ViewGroup): View {
                    val tv = super.getView(pos, v, parent) as TextView
                    tv.setTextColor(HudPalette.ICE)
                    return tv
                }
                override fun getDropDownView(pos: Int, v: View?, parent: android.view.ViewGroup): View {
                    val tv = super.getDropDownView(pos, v, parent) as TextView
                    tv.setTextColor(0xFF111111.toInt())
                    return tv
                }
            }
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
        val previewBtn = styleBtn(Button(this), false).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
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
        root.addView(primaryBtn("保存声音选择") {
            JarvisPrefs.setVoice(this, selectedVoice)
            toast("已保存，先点“试听”确认效果")
        })
        // 批八：语音引擎三档（治两个声来回跳）——自动/只用云扬/只用系统。
        root.addView(segmentedRow("语音引擎", listOf("自动", "只用云扬", "只用系统"),
            when (JarvisPrefs.getTtsEngine(this)) {
                "edge" -> 1
                "system" -> 2
                else -> 0
            }) { i ->
            val next = when (i) {
                1 -> "edge"
                2 -> "system"
                else -> "auto"
            }
            JarvisPrefs.setTtsEngine(this, next)
            toast(
                when (next) {
                    "edge" -> "只用云扬：断网时只出文字，不换系统声"
                    "system" -> "只用系统语音：不依赖网络"
                    else -> "自动：云扬失败重试一次，再落系统语音"
                }
            )
        })
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
        root.addView(primaryBtn("保存天气城市") {
            JarvisPrefs.setWeatherCity(this, cityInput.text.toString().trim())
            toast("天气默认城市已保存：${JarvisPrefs.getWeatherCity(this)}")
        })

        root.addView(sectionTitle("唤醒词"))
        root.addView(switchRow("开启唤醒词「贾维斯」", "离线识别轮询，非专用低功耗 KWS，见自检详情",
            JarvisPrefs.isWakeEnabled(this@MainActivity)) { checked ->
            JarvisPrefs.setWakeEnabled(this@MainActivity, checked)
            if (checked) JarvisAgent.maybeStartWakeLoop() else JarvisAgent.stopWakeLoop()
            refreshWakeChip()
        })

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
            val b = styleBtn(Button(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    JarvisPrefs.setWakeStrictness(this@MainActivity, i)
                    paintStrict(i)
                }
            }, false)
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

        root.addView(switchRow("播报时允许被打断说话", "默认关：麦克风点击才启用；开着时播报期间会开麦听打断",
            JarvisPrefs.isBargeInEnabled(this@MainActivity)) { checked ->
            JarvisPrefs.setBargeInEnabled(this@MainActivity, checked)
        })
    }

    /** 批二新增：视觉看屏配置——开关、模型/接口/Key 覆盖（留空复用 Agnes）、看屏白名单。（坐标格式已按用户要求删除：网格标定法用不上。） */
    private fun buildVisionSection(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "视觉看屏（截图 + 多模态模型）"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(24), 0, dp(8))
        })
        root.addView(switchRow("启用视觉看屏", "",
            JarvisPrefs.isVisionEnabled(this@MainActivity)) { checked ->
            JarvisPrefs.setVisionEnabled(this@MainActivity, checked)
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
        root.addView(primaryBtn("保存视觉看屏设置") {
            JarvisPrefs.setVisionModel(this, vModel.text.toString())
            JarvisPrefs.setVisionBaseUrl(this, vBase.text.toString())
            JarvisPrefs.setVisionApiKey(this, vKey.text.toString())
            JarvisPrefs.setVisionWhitelistRaw(this, whitelistInput.text.toString())
            JarvisAccessibilityService.setUserWhitelist(JarvisPrefs.visionWhitelistSet(this))
            toast("已保存")
        })
        // 第一梯队批二：视觉链路从未实测过"模型到底支不支持识图"——这条链路不行的话，
        // 看屏/跨应用截图兜底全都是摆设。按下按钮现画一张带数字的测试图发过去，
        // 模型答对数字才算真的通了，不是"接口没报错"就算数。
        val visionTestResult = TextView(this).apply {
            textSize = 12f; setTextColor(0x8AFFFFFF.toInt()); setPadding(0, dp(4), 0, 0)
        }
        root.addView(btn("测试视觉模型是否真的能识图") {
            visionTestResult.text = "测试中…（发一张图过去，等模型回答）"
            runVisionProbe { ok, detail ->
                runOnUiThread { visionTestResult.text = detail }
                AuditLog.append(this, "视觉链路自测", if (ok) "通过" else "未通过", detail)
            }
        })
        root.addView(visionTestResult)
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
        root.addView(switchRow("后台常驻待命", "前台服务 + 常驻通知，熄屏也能听“贾维斯”，耗电会增加",
            JarvisPrefs.isKeepAliveEnabled(this@MainActivity)) { checked ->
            JarvisPrefs.setKeepAliveEnabled(this@MainActivity, checked)
            if (checked) startKeepAlive(manual = true)
            // 关闭时不主动杀掉已经在跑的前台服务——只是不再"记住要常驻"，下次打开 App 不会自动再启动。
        })
        root.addView(switchRow("连续对话", "语音回答说完自动继续听，不用再喊一次唤醒词",
            JarvisPrefs.isContinuousChat(this@MainActivity)) { checked ->
            JarvisPrefs.setContinuousChat(this@MainActivity, checked)
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

    /** 彻底融合第 1 步：「主界面」分区——竖屏舞台背景图（图库自选，未选时用内置默认静帧）。 */
    private fun buildHomeScreenSection(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "主界面"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(24), 0, dp(8))
        })
        val statusText = TextView(this).apply {
            textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); setPadding(0, dp(8), 0, 0)
        }
        fun refreshStatus() {
            statusText.text = if (JarvisPrefs.getPortraitBgUri(this).isBlank()) {
                "竖屏舞台现在用默认图（HUD 默认皮肤静帧）"
            } else {
                "竖屏舞台现在用你选的图"
            }
        }
        refreshStatus()
        root.addView(statusText)
        root.addView(btn("从图库选一张竖屏背景图") {
            try {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_PICK_PORTRAIT_BG)
            } catch (e: Exception) {
                toast("打不开图库：${e.message}")
            }
        })
        root.addView(btn("恢复默认图") {
            JarvisPrefs.setPortraitBgUri(this, "")
            refreshStageImage()
            refreshStatus()
            toast("已恢复默认图")
        })
    }

    /** 批三新增：「记忆」分区——总开关、列表（分域+时间）、逐条删除、一键清空。数据在 MemoryService（Room）。 */
    private fun buildMemorySection(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "记忆（画像与长期记忆）"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(24), 0, dp(8))
        })
        root.addView(switchRow("启用长期记忆", "说“记住：……”写入，对话时自动带上",
            JarvisPrefs.isMemoryEnabled(this@MainActivity)) { checked ->
            JarvisPrefs.setMemoryEnabled(this@MainActivity, checked)
            refreshMemoryList()
        })
        // 第一梯队批一新增：自然语言隐式提取——不用说"记住"，聊天里提到的长期信息自动识别写入。
        // 每轮多打一次模型请求，不想要这个开销可以单独关掉（显式"记住："不受影响）。
        root.addView(switchRow("自动从聊天中学习", "不用说“记住”，Jarvis 自己判断要不要记（需额外一次模型请求）",
            JarvisPrefs.isMemoryAutoExtractEnabled(this@MainActivity)) { checked ->
            JarvisPrefs.setMemoryAutoExtractEnabled(this@MainActivity, checked)
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
        root.addView(btn("清空对话") {
            clearChat()
            toast("对话已清空")
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
                // 第一梯队批一：标出来源（手动/自动）与是否永久，方便用户辨认哪些是自己要求记的、
                // 哪些是 Jarvis 自己猜的、猜的那些什么时候会自动过期。
                val sourceTag = if (item.source == com.jarvis.app.memory.MemorySource.STATED.name) "手动" else "自动"
                val expiryTag = if (item.expiresAt == null) "永久" else "至 ${fmt.format(Date(item.expiresAt))}"
                row.addView(TextView(this@MainActivity).apply {
                    text = "[${item.domain}·$sourceTag·$expiryTag] ${item.content} · ${fmt.format(Date(item.timestamp))}"
                    textSize = 13f
                    setTextColor(0xFFE8ECF4.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(styleBtn(Button(this@MainActivity).apply {
                    text = "删除"
                    setOnClickListener {
                        uiScope.launch {
                            MemoryService.deleteMemory(this@MainActivity, item.id)
                            refreshMemoryList()
                        }
                    }
                }, false))
                memoryListContainer.addView(row)
            }
        }
    }

    /** 批四新增：「技能」分区——总开关、技能列表（启停/计数/删除）、轨迹存技能、手动新建。数据在 SkillService（Room）。 */
    private fun buildSkillSection(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "技能（固化任务）"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(24), 0, dp(8))
        })
        root.addView(switchRow("启用技能", "话里命中触发句就直接重播步骤",
            JarvisPrefs.isSkillEnabled(this@MainActivity)) { checked ->
            JarvisPrefs.setSkillEnabled(this@MainActivity, checked)
            refreshSkillList()
        })
        skillListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(skillListContainer)

        // 把最近点按轨迹存为技能
        val traceName = EditText(this).apply {
            hint = "给刚才的操作起个名（如：打开微信收款码）"
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 14f
        }
        root.addView(traceName)
        root.addView(primaryBtn("把最近操作存为技能") {
            val name = traceName.text.toString().trim()
            if (name.isEmpty()) {
                toast("先给技能起个名")
                return@primaryBtn
            }
            val trace = SkillTrace.snapshot()
            if (trace.isEmpty()) {
                toast("还没有可存的操作记录（先让 Jarvis 点按几次）")
                return@primaryBtn
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
            hint = "步骤，一行一步：开：包名 / 点：目标 / 输：要打的字 / 等：800 / 滑：下 / " +
                "场：${com.jarvis.app.command.DeviceCommands.sceneActionHints()}"
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 14f
            minLines = 3
        }
        root.addView(newName)
        root.addView(newTrigger)
        root.addView(newSteps)
        root.addView(primaryBtn("保存新技能") {
            val name = newName.text.toString().trim()
            val trigger = newTrigger.text.toString().trim()
            if (name.isEmpty() || trigger.isEmpty()) {
                toast("技能名和触发句都要填")
                return@primaryBtn
            }
            val arr = JSONArray()
            val lines = newSteps.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                toast("至少写一步")
                return@primaryBtn
            }
            for ((i, line) in lines.withIndex()) {
                val obj: JSONObject? = when {
                    line.startsWith("开：") ->
                        JSONObject().put("t", "open").put("pkg", line.removePrefix("开：").trim())
                    line.startsWith("点：") ->
                        JSONObject().put("t", "tap").put("target", line.removePrefix("点：").trim())
                    // 第一梯队批三新增：输入步骤，紧跟在点开了输入框的"点：…"行之后才有意义，
                    // 这里不做这种上下文校验（校验起来太复杂），交给重播时如实报错。
                    line.startsWith("输：") ->
                        line.removePrefix("输：").trim().takeIf { it.isNotEmpty() }?.let {
                            JSONObject().put("t", "input").put("text", it)
                        }
                    // 第二梯队批六新增：场景动作步骤，action 用英文关键字，手写技能脚本时
                    // 提示里已经列出了可用值（见上面 hint 里的 sceneActionHints()）。
                    line.startsWith("场：") ->
                        line.removePrefix("场：").trim().takeIf { it.isNotEmpty() }?.let {
                            JSONObject().put("t", "sys").put("action", it)
                        }
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
                    return@primaryBtn
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
                row.addView(android.widget.Switch(this@MainActivity).apply {
                    isChecked = item.enabled
                    setOnCheckedChangeListener { _, checked ->
                        uiScope.launch {
                            SkillService.setEnabled(this@MainActivity, item, checked)
                        }
                    }
                })
                row.addView(styleBtn(Button(this@MainActivity).apply {
                    text = "删除"
                    setOnClickListener {
                        uiScope.launch {
                            SkillService.deleteSkill(this@MainActivity, item.id)
                            refreshSkillList()
                        }
                    }
                }, false))
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
        root.addView(switchRow("启用本地模型", "",
            JarvisPrefs.isLocalLlmEnabled(this@MainActivity)) { checked ->
            JarvisPrefs.setLocalLlmEnabled(this@MainActivity, checked)
        })
        root.addView(switchRow("智能调配", "开 = 闲聊本地答、问答云端答；关 = 云端优先、本地只在云端失败时兜底",
            JarvisPrefs.isLocalLlmPrefer(this@MainActivity)) { checked ->
            JarvisPrefs.setLocalLlmPrefer(this@MainActivity, checked)
        })
        val tokensInput = EditText(this).apply {
            hint = "maxTokens（256–4096；gemma3n 上限 4096，旧 gemma3 上限 1024）"
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(JarvisPrefs.getLocalMaxTokens(this@MainActivity).toString())
        }
        root.addView(tokensInput)
        root.addView(primaryBtn("保存 maxTokens") {
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
        // 彻底融合第 1 步：竖屏舞台背景图——图库选图回调，拿持久授权存起来，立即换图。
        if (requestCode == REQ_PICK_PORTRAIT_BG) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // 拿不到持久授权也先用着：本次能显示，下次打开可能回默认图。
                }
                JarvisPrefs.setPortraitBgUri(this, uri.toString())
                refreshStageImage()
                toast("竖屏背景图已换")
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
        root.addView(switchRow("启用主动播报", "来电/短信/指定应用通知读出来",
            JarvisPrefs.isAnnounceEnabled(this@MainActivity)) { checked ->
            JarvisPrefs.setAnnounceEnabled(this@MainActivity, checked)
        })
        root.addView(switchRow("播报来电", "",
            JarvisPrefs.isAnnounceCalls(this@MainActivity)) { checked ->
            JarvisPrefs.setAnnounceCalls(this@MainActivity, checked)
        })
        root.addView(switchRow("播报短信", "",
            JarvisPrefs.isAnnounceSms(this@MainActivity)) { checked ->
            JarvisPrefs.setAnnounceSms(this@MainActivity, checked)
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
        root.addView(primaryBtn("保存播报应用名单") {
            JarvisPrefs.setAnnounceAppsRaw(this@MainActivity, appsInput.text.toString())
            toast("已保存")
        })
        root.addView(switchRow("勿扰时段不播", "",
            JarvisPrefs.isDndEnabled(this@MainActivity)) { checked ->
            JarvisPrefs.setDndEnabled(this@MainActivity, checked)
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
        root.addView(primaryBtn("保存勿扰时段") {
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
        root.addView(primaryBtn("开启/更新晨报") {
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
            toast("正在组稿（含天气查询，稍等）…")
            uiScope.launch {
                val text = com.jarvis.app.proactive.BriefComposer.compose(this@MainActivity)
                if (JarvisPrefs.isMuted(this@MainActivity)) {
                    toast(text.take(120))
                } else {
                    com.jarvis.app.tts.TtsRouter.speak(this@MainActivity, text, onStart = {}, onDone = {})
                }
            }
        })
        root.addView(TextView(this).apply {
            // 第二梯队批六：晨报的天气就是用这个城市——不在这里重复加一个输入框，
            // 复用"聊天"分区里已有的"天气默认城市"那一份设置，两处编辑同一个值容易让人
            // 以为是两份独立配置。
            text = "晨报播的天气城市，跟「天气默认城市」是同一个设置（在上面聊天分区里改）"
            textSize = 12f; setTextColor(0x8AFFFFFF.toInt()); setPadding(0, dp(4), 0, dp(8))
        })
        root.addView(switchRow("出发提醒", "带地点的日程，按固定提前量提醒；没有路程估算",
            JarvisPrefs.isDepartureEnabled(this@MainActivity)) { checked ->
            JarvisPrefs.setDepartureEnabled(this@MainActivity, checked)
        })
        val leadInput = EditText(this).apply {
            hint = "提前分钟数（如 30）"
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(JarvisPrefs.getDepartureLeadMin(this@MainActivity).toString())
        }
        root.addView(leadInput)
        root.addView(primaryBtn("保存提前量") {
            val v = leadInput.text.toString().trim().toIntOrNull()
            if (v == null) {
                toast("填个数字")
            } else {
                JarvisPrefs.setDepartureLeadMin(this@MainActivity, v)
                toast("已保存")
            }
        })
        root.addView(btn("立即扫描今日日程排出发提醒 + 冲突提醒") {
            val n = com.jarvis.app.proactive.DeparturePlanner.scan(this@MainActivity)
            // 第二梯队批四：日程冲突提醒复用同一个"出发提醒"总开关和同一次扫描动作，
            // 不单独加开关——冲突检测本来就是"带地点的日程"之外对日程本身的另一种关心。
            val c = com.jarvis.app.proactive.DeparturePlanner.scanConflicts(this@MainActivity)
            toast(
                when {
                    !JarvisPrefs.isDepartureEnabled(this@MainActivity) -> "出发提醒没开"
                    n == 0 && c == 0 -> "没派出新的（没有带地点且来得及的日程/没有撞车的日程，或日历没授权）"
                    else -> "出发提醒 $n 条，冲突提醒 $c 条"
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
        // 横屏看 HUD 时恢复渲染，并把当前状态与模型配置再推一次；竖屏或设置页开着时 WebView 保持暂停。
        if (!showingSettings && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && ::hudWebView.isInitialized) {
            hudWebView.onResume()
            hudLastPushedState = null
            pushHudState(JarvisAgent.currentState())
            hudWebView.evaluateJavascript("try{if(window.__jarvisRefreshModel)__jarvisRefreshModel()}catch(e){}", null)
            // 第 3 步：回横屏时面板真数据再推一次。
            pushHudPanels()
        }
    }

    override fun onPause() {
        if (::hudWebView.isInitialized) {
            hudWebView.onPause()
        }
        OrbController.setAppForeground(false)
        super.onPause()
    }

    override fun onDestroy() {
        if (::hudWebView.isInitialized) {
            // WebView 必须从父布局摘掉再 destroy，否则下次打开可能白屏。
            (hudWebView.parent as? android.view.ViewGroup)?.removeView(hudWebView)
            hudWebView.destroy()
        }
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
        orbSwitch?.let {
            orbSwitchUpdating = true
            it.isChecked = OrbController.isEnabled()
            orbSwitchUpdating = false
        }
    }

    private fun btn(label: String, onClick: (View) -> Unit): Button =
        styleBtn(Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            setOnClickListener(onClick)
        }, false)

    // ---------- 批二十九：设置页样式 helper（只改外观，不动任何 prefs 键与回调） ----------

    private val SETTING_INPUT_BG = 0xFF1E1533.toInt()
    private val SETTING_STROKE = 0x2EFFFFFF
    private val SETTING_DIVIDER = 0x1EFFFFFF

    /** 目录行：图标 + 标题 + 右箭头，下附细线（参考图样式）。 */
    private fun settingRow(icon: String, title: String, onClick: (View) -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(14), dp(4), dp(14))
            setOnClickListener(onClick)
        }
        row.addView(TextView(this).apply { text = icon; textSize = 24f })
        row.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(HudPalette.ICE)
            setPadding(dp(16), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply { text = "›"; textSize = 20f; setTextColor(0x8AFFFFFF.toInt()) })
        col.addView(row)
        col.addView(View(this).apply {
            setBackgroundColor(SETTING_DIVIDER)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })
        return col
    }

    /** 分组小标题：13sp 旋臂紫。 */
    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(HudPalette.GALAXY_MID)
        setPadding(0, dp(20), 0, dp(6))
    }

    /** 按钮统一样式：primary = 旋臂紫实色，secondary = 深底描边。 */
    private fun styleBtn(b: Button, primary: Boolean): Button = b.apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            if (primary) setColor(HudPalette.GALAXY_MID) else {
                setColor(SETTING_INPUT_BG)
                setStroke(dp(1), SETTING_STROKE)
            }
        }
        setTextColor(Color.WHITE)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    private fun primaryBtn(label: String, onClick: (View) -> Unit): Button =
        styleBtn(Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            setOnClickListener(onClick)
        }, true)

    /** 开关行：标题 + 说明 + Switch（选中态紫色）。初始值先设好再挂 listener，避免误触发；
     * onCreated 供悬浮球那处拿 Switch 引用（refresh 时同步状态用）。 */
    // 注意：onChecked 必须放最后一位（onCreated 之后），这样各处 switchRow(...) { checked -> ... }
    // 的尾随 lambda 才能绑定到 onChecked；若 onChecked 在前，尾随 lambda 会误绑到 onCreated 而编译失败。
    private fun switchRow(
        title: String, desc: String, initial: Boolean,
        onCreated: ((android.widget.Switch) -> Unit)? = null, onChecked: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(HudPalette.ICE) })
        if (desc.isNotEmpty()) texts.addView(TextView(this).apply {
            text = desc; textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); setPadding(0, dp(2), 0, 0)
        })
        row.addView(texts)
        val sw = android.widget.Switch(this).apply {
            isChecked = initial
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(HudPalette.GALAXY_MID, 0xFF9E9E9E.toInt())
            )
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(HudPalette.withAlpha(HudPalette.GALAXY_MID, 0.5f), 0xFF616161.toInt())
            )
        }
        onCreated?.invoke(sw)
        sw.setOnCheckedChangeListener { _, checked -> onChecked(checked) }
        row.addView(sw)
        return row
    }

    /** 分段选择行：标题 + 一排等宽按钮，选中不透明、未选中半透明（沿用唤醒严格度那排的做法）。 */
    private fun segmentedRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title; textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); setPadding(0, dp(8), 0, dp(4))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = ArrayList<Button>()
        options.forEachIndexed { i, label ->
            val b = styleBtn(Button(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(8)
                }
                setOnClickListener {
                    onSelect(i)
                    buttons.forEachIndexed { j, bb -> bb.alpha = if (j == i) 1f else 0.5f }
                }
            }, false)
            b.alpha = if (i == selected) 1f else 0.5f
            buttons.add(b)
            row.addView(b)
        }
        col.addView(row)
        return col
    }

    /** 设置页内所有输入框统一样式：实色圆角深底。页面构建完统一扫一遍，
     * 免得十几个 EditText 创建处逐个改；只扫设置页子树，不动主界面输入框。 */
    private fun styleSettingsInputs(root: android.view.ViewGroup) {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is EditText) {
                c.background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(SETTING_INPUT_BG)
                    setStroke(dp(1), SETTING_STROKE)
                }
                c.setPadding(dp(16), dp(12), dp(16), dp(12))
                c.setTextColor(HudPalette.ICE)
                c.setHintTextColor(0x66FFFFFF)
            } else if (c is android.view.ViewGroup) {
                styleSettingsInputs(c)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
