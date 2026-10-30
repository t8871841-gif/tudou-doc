package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.jarvis.app.asr.OfflineAsrSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

/**
 * 悬浮球：用无障碍服务的 TYPE_ACCESSIBILITY_OVERLAY 窗口画在所有 App 之上，不需要“悬浮窗”权限。
 *  - 只在 Jarvis 不在前台时显示（前台时界面本身就在）；
 *  - 点一下：常驻服务在跑 → 通知 Dart 开始听；否则拉起 Jarvis 再听（后台没有前台服务时系统不让用麦克风）；
 *  - 长按：打开 Jarvis；拖动：随手摆位，松手吸附左右边缘并记住位置；
 *  - 球的颜色跟随状态（待命/聆听/思考/播报），回答文字在球旁弹出小气泡。
 * 全部 UI 操作在主线程。
 */
object OrbController {
    private const val PREFS = "jarvis_orb"

    // 第二梯队批八：悬浮球自由摆放 + 贴边隐藏。
    // 原来 dragEnd() 不管松手在哪都强制吸到左右边缘——这里改成只有"松手点离某条边够近"
    // 才吸边，否则就停在用户实际松手的位置，真正支持摆在屏幕任意地方（包括正中间）。
    private const val EDGE_SNAP_THRESHOLD_DP = 48f
    // 贴边收起后还留多少比例的球体露在外面（剩下这一截用来给用户看见"球还在"、
    // 也用来当下一次点击的热区，不是整个球都滑出屏幕消失不见）。
    private const val COLLAPSED_VISIBLE_FRACTION = 0.38f
    private const val COLLAPSED_ALPHA = 0.55f
    private const val COLLAPSE_IDLE_MS = 1600L
    private const val COLLAPSE_ANIM_MS = 220L

    private var svc: AccessibilityService? = null
    private var wm: WindowManager? = null
    private var orb: OrbView? = null
    private var orbLp: WindowManager.LayoutParams? = null
    private var bubble: TextView? = null
    private var enabled = false
    private var appForeground = false
    private var state = "idle"
    private var startX = 0
    private var startY = 0
    private val main = Handler(Looper.getMainLooper())
    private val hideBubbleRunnable = Runnable { removeBubble() }

    // 第二梯队批八：贴边收起状态。dockedX/dockedY 是"完全展开"时应该在的位置
    // （要么是吸边吸到的 0/widthPixels-size，要么是自由摆放松手的原始坐标——
    // 自由摆放的位置不会触发收起，所以只有吸边场景会真正用到这俩字段来做"收起后展开回哪"）。
    private var collapsed = false
    private var dockedX = 0
    private var dockedY = 0
    private var collapseRunnable: Runnable? = null
    // 球处于收起状态时的这一次触摸，第一下只负责把球展开，不触发点击/长按的正常动作——
    // 不然用户好不容易找到那一小截露出来的球，一点就被当成"点一下开始听"，体验很突兀。
    private var revealOnlyThisGesture = false

    @Volatile
    var dartAlive = false

    /** 投递事件给 Dart，并带回执：cb(true)=Dart 已处理；cb(false)/超时=没处理，调用方必须兜底。 */
    @Volatile
    var eventSink: ((String, (Boolean) -> Unit) -> Unit)? = null

    private var stopUntil = 0L
    private var lastTapAt = 0L

    /**
     * 2026-10-24 用户定：锁屏和息屏都不显示悬浮球，解锁后恢复。
     * 悬浮球是 TYPE_ACCESSIBILITY_OVERLAY，系统级 overlay 默认会盖在锁屏上，
     * 靠布局参数藏不住，必须主动藏：灭屏/亮屏仍锁着 → removeAll()；
     * 解锁（USER_PRESENT）→ refresh()（仍受 enabled 与 appForeground 门控）。
     * 只藏视觉，不停常驻服务与唤醒词监听。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> removeAll()
                Intent.ACTION_SCREEN_ON -> {
                    val locked = try {
                        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                        km.isKeyguardLocked
                    } catch (_: Exception) {
                        false
                    }
                    if (locked) removeAll() else refresh()
                }
                Intent.ACTION_USER_PRESENT -> refresh()
            }
        }
    }
    private var screenReceiverOn = false

    fun attach(s: AccessibilityService) {
        svc = s
        wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        enabled = s.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)
        if (!screenReceiverOn) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    s.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    s.registerReceiver(screenReceiver, filter)
                }
                screenReceiverOn = true
            } catch (_: Exception) {
                // 注册失败不影响主流程：最坏是锁屏上仍显示球（旧行为）
            }
        }
        refresh()
    }

    fun detach() {
        if (screenReceiverOn) {
            try { svc?.unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            screenReceiverOn = false
        }
        removeAll()
        svc = null
        wm = null
    }

    /** 返回 false = 无障碍服务没在运行（开关值已记住，服务起来后自动显示）。 */
    fun setEnabled(on: Boolean): Boolean {
        enabled = on
        svc?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("on", on)?.apply()
        refresh()
        return svc != null
    }

    fun setAppForeground(fg: Boolean) {
        appForeground = fg
        refresh()
    }

    fun setState(s: String) {
        state = s
        main.post { orb?.setState(displayState()) }
    }

    fun isEnabled(): Boolean = enabled

    /**
     * Andee 对齐：跨应用任务期间球不隐藏——只在截图瞬间空白、点按落点在球下时让位。
     * 截图空白：球 View 设 GONE，截图完恢复（提示词里早已写着“每次截图球都会藏起来”，
     * 代码此前却没做，提示词与实现对不上，本次补齐）。hide 在主线程同步等最多 300ms，
     * 保证空白已合成再拍；restore 只管 post（下一次截图前一定已恢复）。
     * 主线程调用时直接改，不 post（否则闩锁死锁）。气泡一并藏。
     */
    /**
     * 修复（真机追查 Q3）：原来 hide 分支的闩锁，只等到"赋值语句在主线程跑完"就放行——
     * `v.visibility = View.GONE` 这行执行完，不代表 SurfaceFlinger 已经把下一帧（球消失的
     * 那帧）真正合成上屏；球是 `TYPE_ACCESSIBILITY_OVERLAY` 独立窗口，这行代码跟"屏幕上
     * 真的看不见球了"之间天然隔着至少一帧的合成延迟，设备负载高时可能更久。这段时间差
     * 很短，平时很难复现，但截图这种"此刻立即拍一张"的操作恰好最容易撞上这个窗口——
     * 球刚被下令隐藏、画面还没来得及更新，screenshot() 已经拍下去了。
     * 现在改成：赋值之后再等一帧真正回调（`View.postOnAnimation`，挂在下一次 vsync 之后），
     * 确认合成器至少处理过一轮之后才放行；为防止机型/负载异常导致这帧回调迟迟不来，
     * 闩锁整体超时从 300ms 放宽到 400ms（多出来的 100ms 专门留给这一帧的余量）。
     */
    // 两层隐藏，任一层为真球和气泡都不显示：
    // captureHidden = 截图瞬间空白（screenshot/grab/captureScreen）；
    // taskHidden = 跨应用任务期隐藏（用户定：减少屏幕干扰、有利于定位）。
    // 分层是为了截图完恢复不会把任务期的隐藏顶掉——恢复只清自己那层。
    // 标志位无条件写（即使 orb 为空也写，保证成对调用下不残留）；真正改
    // View 只在 orb 存在时，且一律走主线程。
    private var captureHidden = false
    private var taskHidden = false

    /** 仅主线程调：按两层标志位统一落一次可见性。 */
    private fun applyVisibility() {
        val vis = if (captureHidden || taskHidden) View.GONE else View.VISIBLE
        orb?.visibility = vis
        bubble?.visibility = vis
    }

    fun setVisibleForCapture(visible: Boolean) {
        val v = orb
        if (!visible) {
            // 主线程调用时只隐藏不等帧；后台线程才走闩锁等帧（见上方注释）。
            if (Looper.myLooper() == Looper.getMainLooper()) {
                captureHidden = true
                applyVisibility()
                return
            }
            if (v == null) {
                captureHidden = true
                return
            }
            val latch = CountDownLatch(1)
            val doHide = {
                captureHidden = true
                applyVisibility()
                // 等真正跑过一次 vsync 回调，而不是等赋值语句本身跑完——这才是"球已经
                // 从这一帧的合成结果里消失了"的更可靠信号。回调本身挂在 orb 这个 View 上，
                // GONE 之后它还在窗口树里（只是不绘制），postOnAnimation 仍然有效。
                v.postOnAnimation { latch.countDown() }
            }
            main.post(doHide)
            try { latch.await(400, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            captureHidden = false
            applyVisibility()
        } else {
            main.post { captureHidden = false; applyVisibility() }
        }
    }

    /** 跨应用任务期隐藏球（任务结束恢复；急停仍可用音量键双击）。 */
    fun setTaskHidden(hidden: Boolean) {
        taskHidden = hidden
        if (Looper.myLooper() == Looper.getMainLooper()) applyVisibility()
        else main.post { applyVisibility() }
    }

    private var dodgeX = 0
    private var dodgeY = 0
    private var dodged = false

    /**
     * 点按让位（Andee：tap 落点在球下时球让开）：落点在球框内（含 8dp 裕量）时
     * 把球临时移到对侧边缘（只改布局不写偏好，保存的位置不被污染），手势完调
     * restoreDodge() 恢复。返回是否让位了。
     */
    fun dodgeIfNeeded(x: Int, y: Int): Boolean {
        val o = orb ?: return false
        val lp = orbLp ?: return false
        val w = wm ?: return false
        val s = svc ?: return false
        val m = (8 * density()).toInt()
        if (x < lp.x - m || x > lp.x + lp.width + m || y < lp.y - m || y > lp.y + lp.height + m) return false
        val dm = s.resources.displayMetrics
        dodgeX = lp.x
        dodgeY = lp.y
        dodged = true
        val move = {
            lp.x = if (lp.x + lp.width / 2 < dm.widthPixels / 2) (dm.widthPixels - lp.width).coerceAtLeast(0) else 0
            try { w.updateViewLayout(o, lp) } catch (_: Exception) {}
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            move()
        } else {
            val latch = CountDownLatch(1)
            main.post { try { move() } finally { latch.countDown() } }
            try { latch.await(300, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        }
        return true
    }

    fun restoreDodge() {
        if (!dodged) return
        dodged = false
        val o = orb ?: return
        val lp = orbLp ?: return
        val w = wm ?: return
        main.post {
            lp.x = dodgeX
            lp.y = dodgeY
            try { w.updateViewLayout(o, lp) } catch (_: Exception) {}
        }
    }

    // ---------- 第二梯队批八：贴边收起 ----------

    private fun cancelCollapseSchedule() {
        collapseRunnable?.let { main.removeCallbacks(it) }
        collapseRunnable = null
    }

    /** 停在 dockedX 这个位置算不算"贴着屏幕左右边"——只有贴边才值得收起，停在屏幕中间收起没有意义。 */
    private fun isAtScreenEdge(x: Int, screenWidth: Int, size: Int): Boolean = x <= 0 || x >= (screenWidth - size)

    /**
     * 松手安定之后调用：如果设置允许，过一段空闲时间后检查是否确实停在边上、再收起。
     * “贴边”判定必须放在 Runnable 触发时做、不能在排程时做：dragEnd 里吸边动画
     * （180ms）还没播完时 lp.x 还是松手位置，按当时位置判会把“拖到边上松手”
     * 误判成不在边上、收起永远排不上；1.6 秒后再判，动画早已播完，读到的是最终位置。
     */
    private fun scheduleCollapseIfDocked() {
        cancelCollapseSchedule()
        val s = svc ?: return
        if (!JarvisPrefs.isOrbEdgeAutoHideEnabled(s)) return
        val r = Runnable {
            collapseRunnable = null
            val lp = orbLp ?: return@Runnable
            val dm = s.resources.displayMetrics
            if (!isAtScreenEdge(lp.x, dm.widthPixels, lp.width)) return@Runnable
            doCollapse()
        }
        collapseRunnable = r
        main.postDelayed(r, COLLAPSE_IDLE_MS)
    }

    private fun doCollapse() {
        collapseRunnable = null
        if (collapsed) return
        val w = wm ?: return
        val o = orb ?: return
        val lp = orbLp ?: return
        dockedX = lp.x
        dockedY = lp.y
        val onLeft = lp.x <= 0
        val hiddenAmount = (lp.width * (1f - COLLAPSED_VISIBLE_FRACTION)).toInt()
        val target = if (onLeft) -hiddenAmount else lp.x + hiddenAmount
        collapsed = true
        ValueAnimator.ofInt(lp.x, target).apply {
            duration = COLLAPSE_ANIM_MS
            addUpdateListener {
                lp.x = it.animatedValue as Int
                try { w.updateViewLayout(o, lp) } catch (_: Exception) {}
            }
            start()
        }
        o.animate().alpha(COLLAPSED_ALPHA).setDuration(COLLAPSE_ANIM_MS).start()
    }

    /**
     * 从收起状态展开回完整可见。[rescheduleCollapse] 是因为两种触发场景的后续不一样：
     * 用户手指碰到球时要展开+等它再次空闲才重新收起；而一旦球要真正响应点击/拖动/
     * 系统让位（dodge）这些场景，外层调用自己会决定后续要不要收起，这里不重复排。
     */
    private fun expandFromCollapse(rescheduleCollapse: Boolean) {
        cancelCollapseSchedule()
        if (!collapsed) { if (rescheduleCollapse) scheduleCollapseIfDocked(); return }
        collapsed = false
        val w = wm ?: return
        val o = orb ?: return
        val lp = orbLp ?: return
        ValueAnimator.ofInt(lp.x, dockedX).apply {
            duration = COLLAPSE_ANIM_MS
            addUpdateListener {
                lp.x = it.animatedValue as Int
                try { w.updateViewLayout(o, lp) } catch (_: Exception) {}
            }
            start()
        }
        o.animate().alpha(1f).setDuration(COLLAPSE_ANIM_MS).start()
        if (rescheduleCollapse) scheduleCollapseIfDocked()
    }

    // 急停后的 1.8 秒内球显示“已停止”（红色），不被 Dart 的状态更新盖掉。
    private fun displayState(): String =
        if (System.currentTimeMillis() < stopUntil) "stopped" else state

    /**
     * 一键急停：取消原生任务引擎、球变红 + 气泡“已停止”，并通知 Dart 取消 agent 循环 / 播报 / 聆听。
     * 来源：悬浮球双击、音量+/-同按、Dart 自己发起（notifyDart=false，避免回环）。
     * 降级：Dart 不在时只做原生侧取消和反馈。
     */
    fun emergencyStop(notifyDart: Boolean = true) {
        try { JarvisAccessibilityService.cancelTask() } catch (_: Throwable) {}
        stopUntil = System.currentTimeMillis() + 1800
        main.post {
            orb?.setState("stopped")
            buzz(orb, HapticFeedbackConstants.LONG_PRESS)
            doShowBubble("已停止")
            main.postDelayed({ orb?.setState(displayState()) }, 1850)
        }
        if (notifyDart) eventSink?.invoke("estop") { }
    }

    fun showBubble(text: String) {
        main.post { doShowBubble(text) }
    }

    private fun refresh() {
        main.post { if (enabled && !appForeground && svc != null) show() else removeAll() }
    }

    private fun density(): Float = svc?.resources?.displayMetrics?.density ?: 3f

    private fun show() {
        if (orb != null) return
        val s = svc ?: return
        val w = wm ?: return
        val d = s.resources.displayMetrics
        // 真机反馈：jk-v11 时 56dp 太小，jk-v12 放大到 168dp 又太大，用户改定 120dp
        val size = (100 * d.density).toInt()
        val p = s.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = p.getInt("x", d.widthPixels - size).coerceIn(0, (d.widthPixels - size).coerceAtLeast(0))
        lp.y = p.getInt("y", d.heightPixels / 3).coerceIn(0, (d.heightPixels - size).coerceAtLeast(0))
        val v = OrbView(s, object : OrbView.Host {
            override fun dragStart() {
                // 第二梯队批八：球收起时，这一下触摸先只负责展开，不当成"点一下"的正常手势——
                // 用户摸到那一小截露出来的球，期待的是"球回来了"，不是立刻开始听/打开App。
                revealOnlyThisGesture = collapsed
                if (collapsed) expandFromCollapse(rescheduleCollapse = false)
                else cancelCollapseSchedule()
                startX = lp.x
                startY = lp.y
                removeBubble()
            }

            override fun dragBy(dx: Float, dy: Float) {
                lp.x = (startX + dx).toInt().coerceIn(0, (d.widthPixels - size).coerceAtLeast(0))
                lp.y = (startY + dy).toInt().coerceIn(0, (d.heightPixels - size).coerceAtLeast(0))
                try { w.updateViewLayout(orb, lp) } catch (_: Exception) {}
            }

            override fun dragEnd() {
                revealOnlyThisGesture = false
                // 第二梯队批八：原来不管松手在哪都强制吸边，现在只有离左右边缘够近
                // （EDGE_SNAP_THRESHOLD_DP）才吸边，否则就停在实际松手的位置——
                // 真正支持摆在屏幕任意地方，不再只能贴在左右两条边上。
                val snapPx = (EDGE_SNAP_THRESHOLD_DP * d.density).toInt()
                val distToLeft = lp.x
                val distToRight = (d.widthPixels - size) - lp.x
                val nearEdge = minOf(distToLeft, distToRight) <= snapPx
                val target = if (nearEdge) {
                    if (distToLeft < distToRight) 0 else (d.widthPixels - size).coerceAtLeast(0)
                } else {
                    lp.x // 自由摆放：不挪动，就停在松手的地方
                }
                if (target != lp.x) {
                    ValueAnimator.ofInt(lp.x, target).apply {
                        duration = 180
                        addUpdateListener {
                            lp.x = it.animatedValue as Int
                            try { w.updateViewLayout(orb, lp) } catch (_: Exception) {}
                        }
                        start()
                    }
                }
                p.edit().putInt("x", target).putInt("y", lp.y).apply()
                // 只有确实停在边上才值得排"贴边自动收起"；停在屏幕中间的自由摆放不收起。
                scheduleCollapseIfDocked()
            }

            override fun tap() {
                if (revealOnlyThisGesture) {
                    revealOnlyThisGesture = false
                    scheduleCollapseIfDocked()
                    return
                }
                cancelCollapseSchedule()
                handleTap()
                scheduleCollapseIfDocked()
            }

            override fun longPress() {
                if (revealOnlyThisGesture) {
                    revealOnlyThisGesture = false
                    scheduleCollapseIfDocked()
                    return
                }
                launchApp(null)
            }
        })
        v.amplitude = { OfflineAsrSession.currentLevel() }
        v.setState(displayState())
        try {
            w.addView(v, lp)
            orb = v
            orbLp = lp
            if (captureHidden || taskHidden) v.visibility = View.GONE
            // 第二梯队批八：这次显示时如果上次保存的位置本来就贴边，空闲一段时间后也该收起，
            // 不用非得等用户这次又拖一下才开始计时。
            scheduleCollapseIfDocked()
        } catch (_: Exception) {
            // 系统拒绝（极少见）：不显示，不影响其他功能
        }
    }

    /**
     * 短按 = 语音对话（2026-10-24 用户定；长按才进主界面）。
     * 旧逻辑先走 Dart eventSink，桥没起来就兜底 launchApp("assist") 打开主界面——
     * 纯 Kotlin 版里 agent 没启动时桥永远没起来，于是每次短按都变成“进主界面”，
     * 正是用户报的“点击就进入”。现在直接走原生入口 triggerListenFromAssist
     * （内部会先 start() 再 beginListening），全程不打开主界面；
     * 350ms 内连点两次仍是急停。
     */
    private fun handleTap() {
        val now = System.currentTimeMillis()
        buzz(orb, HapticFeedbackConstants.KEYBOARD_TAP) // 点中立即震动，先给手感反馈
        if (now - lastTapAt < 350) {
            lastTapAt = 0
            emergencyStop()
            return
        }
        lastTapAt = now
        val s = svc ?: return
        JarvisAgent.triggerListenFromAssist(s)
    }

    /** 触觉反馈：用 View.performHapticFeedback，不需要 VIBRATE 权限；忽略系统“触感反馈”总开关，保证点中有感觉。 */
    @Suppress("DEPRECATION")
    private fun buzz(v: View?, kind: Int) {
        v?.performHapticFeedback(
            kind,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }

    private fun launchApp(action: String?) {
        val s = svc ?: return
        val i = Intent(s, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (action != null) putExtra(MainActivity.EXTRA_ACTION, action)
        }
        try { s.startActivity(i) } catch (_: Exception) {}
    }

    private fun doShowBubble(text: String) {
        val s = svc ?: return
        val w = wm ?: return
        val olp = orbLp ?: return
        if (orb == null || text.isBlank()) return
        removeBubble()
        val d = s.resources.displayMetrics
        val tv = TextView(s).apply {
            this.text = if (text.length > 120) text.take(120) + "…" else text
            setTextColor(Color.WHITE)
            textSize = 13f
            maxWidth = (240 * d.density).toInt()
            val pad = (10 * d.density).toInt()
            setPadding(pad, pad - 2, pad, pad - 2)
            background = GradientDrawable().apply {
                cornerRadius = 14 * d.density
                setColor(0xE6101828.toInt())
            }
        }
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(tv.maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val gap = (8 * d.density).toInt()
        val onLeft = olp.x + olp.width / 2 < d.widthPixels / 2
        val x = if (onLeft) olp.x + olp.width + gap else olp.x - tv.measuredWidth - gap
        val y = olp.y + olp.height / 2 - tv.measuredHeight / 2
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceAtLeast(0)
            this.y = y.coerceAtLeast(0)
        }
        try {
            w.addView(tv, lp)
            bubble = tv
            if (captureHidden || taskHidden) tv.visibility = View.GONE
            main.postDelayed(hideBubbleRunnable, (3500L + text.length * 60L).coerceAtMost(12000L))
        } catch (_: Exception) {}
    }

    private fun removeBubble() {
        main.removeCallbacks(hideBubbleRunnable)
        bubble?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }

    private fun removeAll() {
        removeBubble()
        cancelCollapseSchedule()
        collapsed = false // 球都没了，收起状态没意义；下次 show() 重新按当前持久化坐标判定
        orb?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        orb = null
        orbLp = null
    }

    /**
     * 球本体：同心三层、全部渐变色（无图标）。
     *  外层：环绕渐变环（SweepGradient，非待命时缓慢旋转）
     *  中层：径向渐变圆盘
     *  核心：高光渐变 + 光晕
     * 颜色随状态：待命蓝 / 聆听青 / 思考橙 / 播报绿 / 急停红。呼吸脉冲沿用原动画机制。
     * 拖动阈值 = 系统 touch slop × 2.5：点按时的轻微位移不再被当成拖动吃掉。
     */
    private class OrbView(ctx: Context, private val host: Host) : View(ctx) {
        interface Host {
            fun dragStart()
            fun dragBy(dx: Float, dy: Float)
            fun dragEnd()
            fun tap()
            fun longPress()
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dp = ctx.resources.displayMetrics.density
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 2.5f
        private var st = "idle"
        private var rot = 0f
        private var anim: ValueAnimator? = null
        private var breathAnim: ValueAnimator? = null
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var longFired = false
        private var pressed = false
        private var lastTickMs = 0L
        private var animStartMs = SystemClock.uptimeMillis()
        /** 音量 0..1：OrbController 接 OfflineAsrSession.currentLevel（聆听时有效） */
        var amplitude: () -> Float = { 0f }
        private var ringShader: Shader? = null
        private var glowShader: Shader? = null
        private var coreShader: Shader? = null
        private val longRunnable = Runnable {
            longFired = true
            performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
            host.longPress()
        }

        // 颜色：设计语言蓝 #2A8CFF / 橙 #FF7A2E；停止态沿用原红色（安全态，不在设计里）。
        private val BLUE = 0xFF2A8CFF.toInt()
        private val ORANGE = 0xFFFF7A2E.toInt()
        private val RED_L = 0xFFFFCDD2.toInt()
        private val RED_B = 0xFFEF5350.toInt()
        private val RED_D = 0xFF8E0000.toInt()

        private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

        private fun buildShaders() {
            val cx = width / 2f
            val cy = height / 2f
            if (cx <= 0f) return
            val half = min(cx, cy)
            val r1 = half * 0.80f
            val stopped = st == "stopped"
            // 外环 sweep：正常蓝→橙→蓝；停止态红系
            ringShader = SweepGradient(
                cx, cy,
                if (stopped) intArrayOf(RED_L, RED_B, RED_D, RED_B, RED_L)
                else intArrayOf(BLUE, ORANGE, BLUE, ORANGE, BLUE),
                null
            )
            // 光晕：中心亮、边缘透明
            val glowC = if (stopped) RED_B else BLUE
            glowShader = RadialGradient(
                cx, cy, r1 * 1.15f,
                intArrayOf(alpha(glowC, 150), alpha(glowC, 50), alpha(glowC, 0)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            // 核心：白→琥珀→橙；停止态红系
            coreShader = RadialGradient(
                cx - r1 * 0.10f, cy - r1 * 0.11f, r1 * 0.55f,
                if (stopped) intArrayOf(0xFFFFFFFF.toInt(), RED_L, RED_B)
                else intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFC08A.toInt(), 0xFFFF7A2E.toInt()),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            buildShaders()
        }

        /** 状态定转速：待机 30°/秒、思考 90°/秒、说话/聆听 55°/秒、停止 0 */
        private fun speedFor(s: String) = when (s) {
            "thinking" -> 90f
            "speaking", "listening" -> 55f
            "stopped" -> 0f
            else -> 30f
        }

        fun setState(s: String) {
            if (s != st) {
                st = s
                buildShaders()
            }
            // 旋转动画常驻（待机也要 30°/秒慢转）；只按状态换速度，不跳变。
            if (anim == null) {
                lastTickMs = SystemClock.uptimeMillis()
                anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1000
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        val now = SystemClock.uptimeMillis()
                        val dt = ((now - lastTickMs).coerceAtLeast(0L)) / 1000f
                        lastTickMs = now
                        rot = (rot + speedFor(st) * dt) % 360f
                        invalidate()
                    }
                    start()
                }
            }
            if (s == "idle") {
                // 待机呼吸：整球极轻微缩放（±2%），3 秒一周期；只动缩放，不动透明度（沿用原机制）。
                if (breathAnim == null) {
                    breathAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 3000
                        repeatCount = ValueAnimator.INFINITE
                        addUpdateListener {
                            val v = it.animatedValue as Float
                            val k = 1f + 0.02f * kotlin.math.sin(v * 2f * Math.PI.toFloat())
                            scaleX = k
                            scaleY = k
                        }
                        start()
                    }
                }
            } else {
                breathAnim?.cancel()
                breathAnim = null
                scaleX = 1f
                scaleY = 1f
            }
            invalidate()
        }

        override fun onDetachedFromWindow() {
            anim?.cancel()
            anim = null
            breathAnim?.cancel()
            breathAnim = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            if (cx <= 0f) return
            val half = min(cx, cy)
            val r1 = half * 0.80f
            val ringW = 3.5f * dp
            val rr = r1 - ringW / 2f
            val coreR = r1 * 0.42f
            val elapsed = (SystemClock.uptimeMillis() - animStartMs) / 1000f
            val stopped = st == "stopped"
            // 有效音量：聆听取麦克风；播报无 TTS 电平，用 2 秒呼吸合成；其余取麦克风（常为 0）。
            val mic = amplitude().coerceIn(0f, 1f)
            val amp = if (st == "speaking") 0.45f + 0.25f * kotlin.math.sin(elapsed * Math.PI.toFloat()) else mic
            val accent = if (stopped) RED_B else BLUE

            paint.reset()
            paint.isAntiAlias = true
            if (pressed) {
                c.save()
                c.scale(0.92f, 0.92f, cx, cy)
            }
            // 层 1：径向光晕（随音量变亮）
            paint.style = Paint.Style.FILL
            paint.shader = glowShader
            paint.alpha = (90 + amp * 130).toInt().coerceIn(0, 255)
            c.drawCircle(cx, cy, r1 * 1.15f, paint)
            paint.shader = null
            // 层 2：外环带缺口（sweep 渐变，270° 弧 + 尾部光点），思考态快闪
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ringW
            paint.strokeCap = Paint.Cap.ROUND
            paint.shader = ringShader
            paint.alpha = if (st == "thinking") (205 + 50 * kotlin.math.sin(elapsed * 12f)).toInt().coerceIn(0, 255) else 255
            val arcRect = android.graphics.RectF(cx - rr, cy - rr, cx + rr, cy + rr)
            c.save()
            c.rotate(rot, cx, cy)
            c.drawArc(arcRect, 0f, 270f, false, paint)
            // 尾部光点：弧终点（旋转系里的正上方）
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = if (stopped) RED_L else 0xFFFFFFFF.toInt()
            c.drawCircle(cx, cy - rr, 2.6f * dp, paint)
            c.restore()
            paint.shader = null
            // 层 3：反向旋转中环（细虚线）
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * dp
            paint.color = accent
            paint.alpha = 160
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * dp, 8f * dp), 0f)
            c.save()
            c.rotate(-rot * 1.5f, cx, cy)
            c.drawCircle(cx, cy, r1 * 0.62f, paint)
            c.restore()
            paint.pathEffect = null
            // 层 4：脉冲波纹（每 2.5 秒一圈，播报/聆听时更明显）
            val rippleT = (elapsed % 2.5f) / 2.5f
            val rippleR = coreR + (r1 * 1.05f - coreR) * rippleT
            val rippleA = ((1f - rippleT) * (if (st == "speaking" || st == "listening") 130f + 110f * amp else 70f)).toInt().coerceIn(0, 255)
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * dp
            paint.color = accent
            paint.alpha = rippleA
            c.drawCircle(cx, cy, rippleR, paint)
            // 层 5：核心光球（2 秒呼吸 + 音量缩放）
            val breath = 1f + 0.06f * kotlin.math.sin(elapsed * Math.PI.toFloat())
            val coreScale = breath * (1f + 0.18f * amp)
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.shader = coreShader
            c.drawCircle(cx, cy, coreR * coreScale, paint)
            paint.shader = null
            // 层 6：环绕小卫星 + 高光点
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            val sat1 = Math.toRadians((elapsed * 40.0) % 360.0)
            c.drawCircle(
                (cx + r1 * 0.95f * kotlin.math.cos(sat1)).toFloat(),
                (cy + r1 * 0.95f * kotlin.math.sin(sat1)).toFloat(),
                2f * dp, paint
            )
            paint.alpha = 200
            val sat2 = Math.toRadians((-elapsed * 28.0 + 180.0) % 360.0)
            c.drawCircle(
                (cx + r1 * 1.02f * kotlin.math.cos(sat2)).toFloat(),
                (cy + r1 * 1.02f * kotlin.math.sin(sat2)).toFloat(),
                1.5f * dp, paint
            )
            paint.alpha = 230
            c.drawCircle(cx - coreR * 0.35f, cy - coreR * 0.38f, coreR * 0.18f, paint)
            if (pressed) c.restore()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    longFired = false
                    pressed = true
                    invalidate()
                    host.dragStart()
                    postDelayed(longRunnable, 600)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        removeCallbacks(longRunnable)
                    }
                    if (moved) host.dragBy(dx, dy)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longRunnable)
                    pressed = false
                    invalidate()
                    if (moved) host.dragEnd() else if (!longFired) host.tap()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longRunnable)
                    pressed = false
                    invalidate()
                    if (moved) host.dragEnd()
                    return true
                }
            }
            return super.onTouchEvent(e)
        }
    }
}
