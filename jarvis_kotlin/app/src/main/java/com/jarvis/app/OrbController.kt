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
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
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
    // 也用来当下一次点击的热区）。真正的贴边隐藏只留一小截（15%），不是原来露 38%
    // 那种半个球还在外面。
    private const val COLLAPSED_VISIBLE_FRACTION = 0.15f
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

    // 差距 6：状态名集中定义——新状态 working/error 只从这里取（JarvisAgent 同样引用
    // 这里，禁止手写字面量）；旧的 idle/listening/thinking/speaking/stopped 仍用字面量，
    // 不做全量 enum 重构。
    const val STATE_WORKING = "working"
    const val STATE_ERROR = "error"
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
    // 收起/展开共用的一段位移动画。拖球时手指接管位置，必须能立刻停掉它——
    // 否则 220ms 的展开动画和手指同时写 lp.x，球会跟手指抢位置、跳来跳去。
    private var edgeAnimator: ValueAnimator? = null
    // M51：拖动吸边动画（dragEnd 里 180ms 播完）的句柄。没有它时 180ms 内再拖，
    // 吸边动画和手指同时写 lp.x，球跟手指抢位置；dragStart() 里一并 cancel()。
    private var snapAnimator: ValueAnimator? = null
    // M53：隐藏期间收到的气泡不启动销毁计时。气泡在 captureHidden
    // 下是 GONE 的，计时照常跑会在恢复前就把气泡销毁、用户永远看不到。
    // bubbleHidePending=true 表示销毁计时被推迟，恢复显示时在 applyVisibility()
    // 里按 bubbleHideDelayMs 重新计时。
    private var bubbleHidePending = false
    private var bubbleHideDelayMs = 0L
    // 球处于收起状态时的这一次触摸，第一下只负责把球展开，不触发点击/长按的正常动作——
    // 不然用户好不容易找到那一小截露出来的球，一点就被当成"点一下开始听"，体验很突兀。
    private var revealOnlyThisGesture = false

    @Volatile
    var dartAlive = false

    /** 投递事件给 Dart，并带回执：cb(true)=Dart 已处理；cb(false)/超时=没处理，调用方必须兜底。 */
    @Volatile
    var eventSink: ((String, (Boolean) -> Unit) -> Unit)? = null

    private var stopUntil = 0L
    // 差距 9、10：350ms 窗口内累计点按次数，超时未满按已点的次数结算（单击说话 / 双击打字 / 三击停止）。
    private var tapCount = 0
    private val tapSettleRunnable = Runnable { settleTaps() }

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
        // 可见性规则 6a：短暂 onPause（系统权限弹窗、通知栏下拉）不立刻把球放出来——
        // 600ms 内 onResume（或 BrowserActivity.onResume）回来就取消，避免球闪现；
        // 真切走才放球。置 true 时立即生效并取消待放的 false。
        main.removeCallbacks(foregroundOffRunnable)
        if (fg) {
            appForeground = true
            refresh()
        } else {
            main.postDelayed(foregroundOffRunnable, 600)
        }
    }

    private val foregroundOffRunnable = Runnable { appForeground = false; refresh() }

    fun setState(s: String) {
        state = s
        main.post {
            orb?.setState(displayState())
            // 差距 6：ERROR 的球上区别——红色（OrbView 按 error 走 stopped 同款红系）+
            // 抖动（Andee Action.SHAKE）+ REJECT 震动（Andee State.ERROR 同款）。
            if (s == STATE_ERROR) {
                orb?.shakeError()
                buzz(orb, HapticFeedbackConstants.REJECT)
            }
        }
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
    // taskHidden = 跨应用任务期（C13/Andee FloatingWindowUi.setWorking：球不隐藏，
    // 只沉到屏幕底沿下一半，留半个球可见、可点；气泡也不藏，任务进度反馈不断线）。
    // 分层是为了截图完恢复不会把任务期的沉底顶掉——恢复只清自己那层。
    // 标志位无条件写（即使 orb 为空也写，保证成对调用下不残留）；真正改
    // View 只在 orb 存在时，且一律走主线程。
    private var captureHidden = false
    private var taskHidden = false
    // C13：任务期沉底前球的位置，任务结束回原位；taskPerched = 当前正沉在底沿。
    private var taskHomeX = 0
    private var taskHomeY = 0
    private var taskPerched = false
    // 差距 8d：用户亲手把沉底的球拖离底沿后，沉底态就此作罢（截图/重显不再拽回）；
    // 拖回底沿重新挂住时清掉。任务结束（setTaskHidden(false)）时也清掉。
    private var taskPerchDetached = false

    /** 仅主线程调：按两层标志位统一落一次可见性。 */
    private fun applyVisibility() {
        if (captureHidden) {
            orb?.visibility = View.GONE
            bubble?.visibility = View.GONE
            return
        }
        if (taskHidden) {
            // C13：任务期间球不隐藏——沉到屏幕底沿下一半，留半个球可见、可点；
            // 气泡也不藏，任务进度反馈不断线。
            orb?.visibility = View.VISIBLE
            bubble?.visibility = View.VISIBLE
            applyTaskPerch()
        } else {
            restoreTaskHome()
            orb?.visibility = View.VISIBLE
            bubble?.visibility = View.VISIBLE
        }
        // M53：恢复显示时，隐藏期间被推迟的气泡销毁计时重新启动。
        if (bubble != null && bubbleHidePending) {
            bubbleHidePending = false
            main.postDelayed(hideBubbleRunnable, bubbleHideDelayMs)
        }
    }

    /** C13：球沉到屏幕底沿下一半的 y（Andee perchY 同款：半个球在沿下）。 */
    private fun perchY(): Int {
        val lp = orbLp ?: return 0
        return bottomEdge() - lp.height / 2
    }

    /** C13/M39：屏幕底沿（Andee bottomEdge 同款：扣掉底部系统栏/刘海 inset，
     * 免得半个球沉到导航栏后面去、只剩一条缝可见）。 */
    private fun bottomEdge(): Int {
        val s = svc ?: return 0
        val w = wm ?: return 0
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                val m = w.currentWindowMetrics
                val inset = m.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                ).bottom
                m.bounds.bottom - inset
            } else {
                s.resources.displayMetrics.heightPixels
            }
        } catch (_: Exception) {
            s.resources.displayMetrics.heightPixels
        }
    }

    /** C13：把球沉到屏幕底沿下一半（只记一次原位；收起态本来就只剩一小截在外面，不再另沉）。 */
    private fun applyTaskPerch() {
        val o = orb ?: return
        val lp = orbLp ?: return
        val w = wm ?: return
        // 差距 8b：任务开始前球已收起，先展开再沉底（收起态只剩一小截在屏外，沉底无意义）。
        if (collapsed) expandInstantly()
        // 差距 8d：用户亲手拖离底沿后不再拽回（除非拖回底沿重新挂住，见 dragEnd 的 wantsPerch）。
        if (taskPerchDetached) return
        if (!taskPerched) {
            taskHomeX = lp.x
            taskHomeY = lp.y
            taskPerched = true
        }
        // 差距 8a：沉底同时收进右下角（Andee FloatingWindowUi.perchX 同款）——
        // 停在底部正中的球落在系统上滑手势带里。
        val tx = perchX()
        val ty = perchY()
        if (lp.x != tx || lp.y != ty) {
            lp.x = tx
            lp.y = ty
            try { w.updateViewLayout(o, lp) } catch (_: Exception) {}
            repositionBubble()
        }
    }

    /** 差距 8a：沉底的 x——右下角（Andee perchX 同款：球窗大半出右沿，只留球面一小截在屏内）。 */
    private fun perchX(): Int {
        val lp = orbLp ?: return 0
        val s = svc ?: return 0
        val d = s.resources.displayMetrics
        val pad = lp.width / 2
        val margin = (8 * d.density).toInt()
        return (d.widthPixels - lp.width + pad - margin).coerceAtLeast(0)
    }

    /** 差距 8d：球是不是被拖回了底沿（距半沉底沿 32dp 内就算挂住）。 */
    private fun wantsPerch(y: Int): Boolean {
        val s = svc ?: return false
        val threshold = (32 * s.resources.displayMetrics.density).toInt()
        return y >= perchY() - threshold
    }

    /** C13：任务结束，球回沉底前的位置。 */
    private fun restoreTaskHome() {
        if (!taskPerched) return
        taskPerched = false
        val o = orb ?: return
        val lp = orbLp ?: return
        lp.x = taskHomeX
        lp.y = taskHomeY
        try { wm?.updateViewLayout(o, lp) } catch (_: Exception) {}
        repositionBubble()
    }

    fun setVisibleForCapture(visible: Boolean) {
        val v = orb
        if (!visible) {
            // L26：主线程上等不了帧——帧回调（postOnAnimation）要在主线程跑，主线程
            // await() 它会死锁；这里只能先隐藏不等帧。主线程的截图调用方必须走
            // hideForCapture{}（隐藏→下一帧回调里继续截图），不要调这个同步版。
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Log.w("OrbController", "setVisibleForCapture(false) on main thread: orb hidden without waiting a frame; main-thread screenshot callers must use hideForCapture{}")
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

    /**
     * L26：主线程可用的“隐藏→等一帧”异步链。同步版 [setVisibleForCapture] 在主线程
     * 上等不了帧（帧回调要主线程跑，主线程 await() 它会死锁），所以主线程的截图调用方
     * 走这里：先隐藏，下一帧回调里再继续截图；后台线程复用同步版的闩锁等帧，回调里
     * 继续。orb 为空时无帧可等，标志位写完直接继续。恢复显示不需要等帧，仍走同步版。
     */
    fun hideForCapture(onHidden: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val v = orb
            captureHidden = true
            applyVisibility()
            if (v == null) onHidden() else v.postOnAnimation { onHidden() }
            return
        }
        setVisibleForCapture(false)
        onHidden()
    }

    /** C13：跨应用任务期球沉底沿（不隐藏，留半个球可见、可点；任务结束回原位）。 */
    fun setTaskHidden(hidden: Boolean) {
        taskHidden = hidden
        if (!hidden) taskPerchDetached = false
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
        if (collapsed) return false // 收起态不让位：球本来就只剩一小截在屏外，让位会把
        // 收起时的屏外坐标记成 dodgeX/dockedX，恢复和再收起全乱
        val m = (8 * density()).toInt()
        if (x < lp.x - m || x > lp.x + lp.width + m || y < lp.y - m || y > lp.y + lp.height + m) return false
        val dm = s.resources.displayMetrics
        dodgeX = lp.x
        dodgeY = lp.y
        dodged = true
        // M35：让位只挪球，气泡跟着球走（不撤掉，下次有新文字也不用等重算）。
        repositionBubble()
        // 让位期间不排收起：1.6 秒的定时器若在让位中途触发，doCollapse 会把
        // 让位后的临时位置记成 dockedX，恢复后球就回不到原来那条边。
        cancelCollapseSchedule()
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
            repositionBubble()
            // 让位恢复后重新排收起：球回到了原来那条边，闲置够了就该照常收起，
            // 不然让位一次之后贴边隐藏就再也不触发了。
            scheduleCollapseIfDocked()
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
        if (dodged) return // 让位期间不收起：这时 lp.x 是让位后的临时位置，记成 dockedX 就错了
        // 差距 8b：任务沉底期间不收起——沉底后 doCollapse 再把它收成 15%，就跟任务沉底打架了。
        if (taskHidden || taskPerched) return
        val w = wm ?: return
        val o = orb ?: return
        val lp = orbLp ?: return
        dockedX = lp.x
        dockedY = lp.y
        val onLeft = lp.x <= 0
        val hiddenAmount = (lp.width * (1f - COLLAPSED_VISIBLE_FRACTION)).toInt()
        val target = if (onLeft) -hiddenAmount else lp.x + hiddenAmount
        collapsed = true
        // M35：收起会把球移出屏幕大半，气泡跟着球走（不撤掉）。
        repositionBubble()
        edgeAnimator?.cancel()
        edgeAnimator = ValueAnimator.ofInt(lp.x, target).apply {
            duration = COLLAPSE_ANIM_MS
            addUpdateListener {
                lp.x = it.animatedValue as Int
                try { w.updateViewLayout(o, lp) } catch (_: Exception) {}
                repositionBubble()
            }
            start()
        }
        o.animate().alpha(COLLAPSED_ALPHA).setDuration(COLLAPSE_ANIM_MS).start()
    }

    /**
     * 瞬时展开（拖球专用）：不停动画、直接把球放回 dockedX。dragStart 里如果还走
     * 220ms 的展开动画，动画和手指会同时写 lp.x，球跟手指抢位置；而且 startX
     * 读到的是收起时的屏外坐标，拖起来整段都偏。瞬时展开后手指从正确位置接管。
     */
    private fun expandInstantly() {
        cancelCollapseSchedule()
        if (!collapsed) return
        collapsed = false
        edgeAnimator?.cancel()
        edgeAnimator = null
        val o = orb ?: return
        val lp = orbLp ?: return
        o.animate().cancel()
        o.alpha = 1f
        lp.x = dockedX
        lp.y = dockedY
        try { wm?.updateViewLayout(o, lp) } catch (_: Exception) {}
    }

    // 急停后的 1.8 秒内球显示“已停止”（红色），不被 Dart 的状态更新盖掉。
    private fun displayState(): String =
        if (System.currentTimeMillis() < stopUntil) "stopped" else state

    /**
     * 一键急停：取消原生任务引擎、球变红 + 气泡“已停止”，并通知 Dart 取消 agent 循环 / 播报 / 聆听。
     * 来源：悬浮球三击（差距 9：急停从双击移到三击）、音量+/-同按、Dart 自己发起（notifyDart=false，避免回环）。
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
        // M39：夹紧用的半个球（pad）与半沉底沿（perchY，Andee clampCompact 同款）。
        val pad = size / 2
        val perchY = bottomEdge() - size / 2
        val p = s.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = p.getInt("x", d.widthPixels - size).coerceIn(-pad, d.widthPixels - size + pad)
        lp.y = p.getInt("y", d.heightPixels / 3).coerceIn(-pad, perchY)
        val v = OrbView(s, object : OrbView.Host {
            override fun dragStart() {
                // 第二梯队批八：球收起时，这一下触摸先只负责展开，不当成"点一下"的正常手势——
                // 用户摸到那一小截露出来的球，期待的是"球回来了"，不是立刻开始听/打开App。
                // 真正的贴边隐藏：收起态下拖球必须瞬时展开，不能走 220ms 动画——
                // 动画和手指同时写 lp.x 会抢位置，而且 startX 会读到屏外坐标、拖起来整段都偏。
                revealOnlyThisGesture = collapsed
                // 差距 8d：用户亲手拖走了沉底的球，沉底态就此作罢（下次截图/重显不再把它拽回底沿）；
                // 拖回底沿重新挂住（dragEnd 里实时算 wantsPerch）。
                if (taskHidden && taskPerched) {
                    taskPerched = false
                    taskPerchDetached = true
                }
                if (collapsed) expandInstantly()
                else cancelCollapseSchedule()
                // M51：180ms 内再拖时，上一轮吸边动画还在播、会跟手指抢 lp.x，
                // 必须先停掉它（edgeAnimator 已在 expandInstantly 里停过）。
                snapAnimator?.cancel()
                snapAnimator = null
                startX = lp.x
                startY = lp.y
                // M35：拖动开始不撤气泡，气泡在 dragBy 里跟着球走。
            }

            override fun dragBy(dx: Float, dy: Float) {
                // M39/Andee clampCompact：球允许半沉出屏（x ∈ [-pad, 宽-side+pad]，y 可到半沉底沿 perchY）。
                lp.x = (startX + dx).toInt().coerceIn(-pad, d.widthPixels - size + pad)
                lp.y = (startY + dy).toInt().coerceIn(-pad, perchY)
                try { w.updateViewLayout(orb, lp) } catch (_: Exception) {}
                repositionBubble()
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
                    // M35：吸边动画会挪动球，气泡在动画回调里跟着球走，不撤掉。
                    // M51：吸边动画存成字段，180ms 内再拖时 dragStart() 里能停掉它。
                    snapAnimator?.cancel()
                    snapAnimator = ValueAnimator.ofInt(lp.x, target).apply {
                        duration = 180
                        addUpdateListener {
                            lp.x = it.animatedValue as Int
                            try { w.updateViewLayout(orb, lp) } catch (_: Exception) {}
                            repositionBubble()
                        }
                        start()
                    }
                }
                p.edit().putInt("x", target).putInt("y", lp.y).apply()
                // 差距 8d：任务期被拖走的球，松手时若已拖回底沿就重新挂住沉底（实时算 wantsPerch），
                // 否则继续由用户摆布，任务结束也不拽回。
                if (taskHidden && taskPerchDetached && wantsPerch(lp.y)) {
                    taskPerchDetached = false
                    applyTaskPerch()
                }
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
                // 差距 10：任务进行中长按不切走当前应用，只显示任务状态气泡，避免打断任务上下文。
                if (taskHidden) {
                    showBubble("任务进行中，稍等一下…")
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
            // C13：任务期只沉底沿不隐藏、截图期才 GONE，都由 applyVisibility() 统一落。
            applyVisibility()
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
     * （内部会先 start() 再 beginListening），全程不打开主界面。
     * 差距 9、10（Andee：单击说话、双击打字、三击停止、长按展开卡片）：350ms 窗口内
     * 累计——1 下等窗口结算后说话，2 下结算后切打字，3 下立即停止；急停从双击移到
     * 三击，350ms 双击窗口误停的问题随迁移自然解决。单击要等 350ms 结算才开听，
     * 是多击接线的固定代价。
     */
    private fun handleTap() {
        buzz(orb, HapticFeedbackConstants.KEYBOARD_TAP) // 点中立即震动，先给手感反馈
        tapCount++
        if (tapCount >= 3) {
            main.removeCallbacks(tapSettleRunnable)
            tapCount = 0
            emergencyStop()
            return
        }
        // 重新计时：下一按要在 350ms 内再来，否则按已点的次数结算。
        main.removeCallbacks(tapSettleRunnable)
        main.postDelayed(tapSettleRunnable, 350)
    }

    /** 350ms 窗口结算：1 下说话，2 下切打字输入。 */
    private fun settleTaps() {
        when (tapCount) {
            1 -> {
                tapCount = 0
                val s = svc ?: return
                JarvisAgent.triggerListenFromAssist(s)
            }
            2 -> {
                tapCount = 0
                onTextInputToggle()
            }
            else -> tapCount = 0
        }
    }

    /** 差距 9：双击 = 切换打字输入（新入口）：打开主界面并切换输入行（MainActivity 的 show_input 动作）。 */
    private fun onTextInputToggle() {
        cancelCollapseSchedule()
        launchApp("show_input")
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
        // M36：球旁气泡截断放宽到 Andee 的 4000 字上限（ChatHistory.MAX_TEXT，约三屏中文），
        // 不再是 120 字。
        val shown = if (text.length > ChatHistory.MAX_TEXT) text.take(ChatHistory.MAX_TEXT) + "…" else text
        val tv = TextView(s).apply {
            this.text = shown
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
        val (bx, by) = bubbleXY(tv, olp, d)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = bx
            this.y = by
        }
        try {
            w.addView(tv, lp)
            bubble = tv
            if (captureHidden) tv.visibility = View.GONE
            // M53：隐藏期间不启动销毁计时——气泡现在是 GONE 的，计时照常跑会在
            // 恢复显示前就把气泡销毁、用户永远看不到。恢复显示时 applyVisibility()
            // 按 bubbleHideDelayMs 重新计时。（任务期 C13 不再隐藏气泡，不走这条。）
            val delayMs = (3500L + text.length * 60L).coerceAtMost(12000L)
            if (captureHidden) {
                bubbleHidePending = true
                bubbleHideDelayMs = delayMs
            } else {
                main.postDelayed(hideBubbleRunnable, delayMs)
            }
        } catch (_: Exception) {}
    }

    /** M35：气泡挂在球上的位置（Andee showSignboard 同款思路：窗口围着球长，球的屏幕位置不动）。
     * 球在左半屏气泡挂右边、在右半屏挂左边；x/y 都夹紧到屏幕内，球半沉底沿时气泡也不出屏。 */
    private fun bubbleXY(tv: TextView, olp: WindowManager.LayoutParams, d: android.util.DisplayMetrics): Pair<Int, Int> {
        val gap = (8 * d.density).toInt()
        val onLeft = olp.x + olp.width / 2 < d.widthPixels / 2
        val x = if (onLeft) olp.x + olp.width + gap else olp.x - tv.measuredWidth - gap
        val y = olp.y + olp.height / 2 - tv.measuredHeight / 2
        return x.coerceIn(0, (d.widthPixels - tv.measuredWidth).coerceAtLeast(0)) to
            y.coerceIn(0, (bottomEdge() - tv.measuredHeight).coerceAtLeast(0))
    }

    /** M35：球一动气泡就跟过去（拖动/吸边/收起/让位/沉底都走这里），不再 removeBubble()。 */
    private fun repositionBubble() {
        val tv = bubble ?: return
        val olp = orbLp ?: return
        val s = svc ?: return
        val w = wm ?: return
        val d = s.resources.displayMetrics
        val lp = tv.layoutParams as? WindowManager.LayoutParams ?: return
        val (bx, by) = bubbleXY(tv, olp, d)
        if (lp.x == bx && lp.y == by) return
        lp.x = bx
        lp.y = by
        try { w.updateViewLayout(tv, lp) } catch (_: Exception) {}
    }

    private fun removeBubble() {
        main.removeCallbacks(hideBubbleRunnable)
        bubbleHidePending = false
        bubble?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }

    private fun removeAll() {
        removeBubble()
        cancelCollapseSchedule()
        edgeAnimator?.cancel()
        edgeAnimator = null
        snapAnimator?.cancel()
        snapAnimator = null
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
     * M39/Andee BubbleUi 同款：拖动阈值 6dp、长按 500ms。
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
        // M39：拖动阈值 6dp（Andee BubbleUi 同款，不再是系统 touch slop × 2.5 约 20dp）。
        private val slop = 6f * dp
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
            val stopped = st == "stopped" || st == STATE_ERROR
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

        /** 状态定转速：待机 30°/秒、思考 90°/秒、任务执行 70°/秒、说话/聆听 55°/秒、停止/出错 0 */
        private fun speedFor(s: String) = when (s) {
            "thinking" -> 90f
            STATE_WORKING -> 70f
            "speaking", "listening" -> 55f
            "stopped", STATE_ERROR -> 0f
            else -> 30f
        }

        private var shakeAnim: ValueAnimator? = null

        /** 差距 6：ERROR 抖动（Andee Action.SHAKE 同款：左右小幅摆动半秒后回正）。 */
        fun shakeError() {
            shakeAnim?.cancel()
            shakeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 500
                addUpdateListener {
                    val p = it.animatedValue as Float
                    translationX = kotlin.math.sin(p * 3f * Math.PI.toFloat()) * (1f - p) * 8f * dp
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        translationX = 0f
                    }
                })
                start()
            }
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
            shakeAnim?.cancel()
            shakeAnim = null
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
            val stopped = st == "stopped" || st == STATE_ERROR
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
                    // M39：长按 500ms（Andee BubbleUi 同款，不再是 600ms）。
                    postDelayed(longRunnable, 500)
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
