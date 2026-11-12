package com.jarvis.app.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.jarvis.app.JarvisAccessibilityService
import com.jarvis.app.task.UiSelector
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// ============================================================================
// 批九移植说明（2026-10-24）：本文件自 Andee 开源工程
// （andee/app/src/main/java/net/kuafuai/andee/screen/ScreenController.kt，3,781 行）
// 整体移植，屏幕感知与动作的实现逻辑原样保留，只动接线层：
//   1. 包名改为 com.jarvis.app.screen；构造入参的 AccessibilityService 由调用方
//      传入 JarvisAccessibilityService 实例。
//   2. marker 类型 net.kuafuai.andee.ui.TapMarkerUi → 本包的 TapMarkerUi
//      （绘制层未移植，只保留落点状态记录，见该文件说明）。
//   3. 输入兜底通道：原版 ADBKeyboard 广播路线依赖第三方输入法 App 与
//      ui/ImeSwitch，本工程没有这套设施，批九曾替换为 typeViaSetText
//      （对焦点编辑节点执行 ACTION_SET_TEXT + 双路读回，与
//      JarvisAccessibilityService 的 Agent 输入动作同款机制）；
//      A11yIme 首选通道原样保留。2026-11-05（C9）：ADBKeyboard 广播以受 guard 的
//      形式恢复为一档兜底（typeViaAdbKeyboard）——只在它已安装且已是当前输入法时
//      才发广播，否则如实报不可用；ImeSwitch（借还输入法）仍没有，不切输入法。
//   4. dumpUiTree 的 withShot 默认值原为 ToolSchemas.UI_TREE_WITH_SHOT_DEFAULT
//      （=false），改为字面量 false。
//   5. awaitHelper 原版往 Andee 聊天记录里加一行提示（ChatHistory/AppLocale/R），
//      本工程无此设施，删去该行，轮询逻辑不变。
//   6. annotate 里 Andee 自家卡片（ui.OwnCard / ui.FullscreenCard）的两个分支：
//      OwnCard 分支改为「前台是 Jarvis 自己的包」判定；FullscreenCard 分支
//      无对应设施，整支删除。模型可见文案中的 "Andee" 相应改为 "Jarvis"。
//   7. reviveNote 的前台包名来源 DeviceState.foregroundPkg →
//      JarvisAccessibilityService.foregroundPackage()。
//   8. screenshot 结果里的来源标记原引用 ScreenCapture 的常量
//      （MediaProjection 兜底类未移植），改为字面量 "source"/"accessibility"；
//      SHOT_MAX_SIDE 同理改为字面量 1280（与 Andee 的 ScreenCapture 同值）。
//   9. GroundingLog / TreeHelper / A11yIme 同批移植，同包引用，无需改名。
// 未移植：ScreenCapture（MediaProjection 截图兜底，需要 Activity 授权流，本工程
// 已有 JarvisAccessibilityService.captureScreen 视觉通道，不在本批范围）。
// ============================================================================

/**
 * Screen perception + actuation: tap / swipe / long-press / type / screenshot /
 * global-action / ui-tree-dump. Wraps AccessibilityService APIs.
 */
class ScreenController(
    private val service: AccessibilityService,
    /**
     * Where injected gestures get drawn after the fact. Null in any caller that
     * has no overlay to draw on; see [TapMarkerUi] for why
     * it is marked *after* the stroke and never before.
     */
    private val marker: TapMarkerUi? = null,
    /** Where our own (still visible) ball window sits, masked out of pixel comparisons. */
    private val ownArea: () -> Rect? = { ownOverlayBounds(service) },
) {

    /**
     * Runs a capture with our own windows blanked. Set by the dispatcher, which
     * owns the overlays; null where there are none, and then the capture just runs.
     *
     * Needed because the effect snapshot after a tap is taken from inside [tap],
     * i.e. inside the dispatcher's gesture passthrough, which only makes the ball
     * untouchable and leaves it drawn. Without this the brain's `after_shot` shows
     * the ball over whatever sits in the bottom-right corner — a field report had
     * it read the ball as covering Alipay's 我的 tab and abandon the route.
     */
    @Volatile
    var shotGuard: ((() -> JSONObject) -> JSONObject)? = null

    private fun guardedShot(shot: () -> JSONObject): JSONObject =
        shotGuard?.invoke(shot) ?: shot()

    /**
     * Wraps a single injected gesture — [tap], [longPress], [swipe] — so the
     * dispatcher can get the ball window out of the way when the stroke would
     * land under it. The target rect is the stroke's bounding box in pixels;
     * the wrapper decides whether moving the overlay is worth it.
     *
     * Measured on this hardware (2026-10-09, Alipay bottom tabs): without this,
     * taps that landed inside the ball window's 168 dp frame returned
     * `completed:true, screen_changed:true` from the accessibility layer but the
     * target app never saw them — Android marks a stroke "obscured" when any
     * untrusted overlay sits on it, and payment apps drop obscured taps. Making
     * the overlay non-touchable (what `passthroughForGesture` already does) is
     * not enough: an invisible non-touchable overlay still contributes to the
     * obscuration ceiling.
     *
     * Null in a caller with no overlay to park.
     */
    @Volatile
    var gestureGuard: ((android.graphics.Rect, () -> JSONObject) -> JSONObject)? = null

    private fun guardedGesture(target: android.graphics.Rect, body: () -> JSONObject): JSONObject =
        gestureGuard?.invoke(target, body) ?: body()

    /** Last meaningful accessibility event from a non-overlay app. */
    private val lastContentEventMs = AtomicLong(0L)

    fun noteEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == service.packageName) return
        when (event.eventType) {
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED,
            android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                lastContentEventMs.set(System.currentTimeMillis())
        }
    }

    // ---- normalized coordinate protocol -----------------------------------
    //
    // The brain speaks 0-1000 RELATIVE integers for every gesture, never
    // pixels: screenshots may have been downscaled on their way to the model,
    // so only a relative space survives the trip and stays resolution-
    // independent. Values outside 0-1000 are REJECTED, not clamped — a silent
    // clamp to the edge is a mis-tap the model can't even see happening.

    // The whole display, not displayMetrics: heightPixels leaves out the
    // navigation bar (2523 of 2560 on this tablet), while screenshots and
    // injected gestures both live in the full display. Mapping 0-1000 against
    // the short height put every coordinate tap ~1.4% too high.
    private val screenW: Int get() = displaySize(service).x
    private val screenH: Int get() = displaySize(service).y

    private fun Int.toPixel(max: Int): Int = this * max / NORM_MAX

    private fun requireNorm(v: Int, name: String): Int {
        if (v !in 0..NORM_MAX) {
            throw IllegalArgumentException(
                "coordinate $name=$v out of range — expected 0..$NORM_MAX (relative, not pixels)"
            )
        }
        return v
    }

    // ---- 第九批·坐标契约（DroidRun _require_active_coordinate_contract 的 Jarvis 版） ----
    //
    // 坐标的流向：模型输出的 x/y、cell/part、OCR 行里的 [x,y]、zoom 图里的
    // 0-1000，都是“某张图上的相对值”，不是像素；执行层在这里统一换算
    // （tapNorm/longPressNorm/swipeNorm/lookRegion），换算用的尺寸只从一个
    // 地方来——displaySize()（WindowManager.maximumWindowMetrics.bounds），
    // screenW/screenH 本就是每次现读的 getter，不缓存。
    //
    // 契约（校验集中在这一处，各坐标入口只调 contractSize，不各写一份）：
    //   1. 范围：0-1000（旧 requireNorm，不钳制——静默钳到边是模型看不见的误点）。
    //   2. 尺寸已知且没过期：执行时的现读尺寸必须等于最近一次观察时盖的戳
    //      （observedSize，由 dumpUiTree/screenshot 盖戳）；没盖过戳、读不到
    //      尺寸、或两次尺寸不一样（旋转/分屏/悬浮窗变了尺寸），都直接拒执行。
    //   3. 换算后像素落屏内：1000 恰好换算成宽/高本身，已在屏外，拒（旧行为里
    //      像素层 tap() 也拒，本批只是把话说清、把码统一）。
    // 校验与换算用同一份现读尺寸（contractSize 把它返回给调用方），不让
    // “校验时一个尺寸、换算时另一个尺寸”的竞态漏过去。
    //
    // 拒绝形态：IllegalStateException，文案以“坐标契约拒绝：”开头——刻意不用
    // 守卫的 "refused:" 前缀：TaskLoop 的自动 zoom 重拍只认 "refused:"，契约
    // 拒绝不要触发它——被拒的坐标动作不该再拍一张 zoom 去点，而该让模型下一轮
    // 改走编号 eid。TaskLoop 外层 catch 按文案映射成错误码 COORD_CONTRACT，
    // 记一步失败、写进 lastError（复用现有失败计数与升级逻辑，不静默跳过）。
    //
    // 不受此限：eid/sel（tapByEid/tapBySelector 按活树当场重定位，像素来自
    // 当前树，不走本函数）；像素入口 tap()/swipe()/longPress()（内部调用方
    // 按窗口 bounds 自己算像素，如 expandFloatingApp，也不走本函数）。
    //
    // zoom 图坐标系：zoom 图内的 0-1000 是图自身的空间，不直接套屏契约——
    // zoomToScreen 先验 zoom 图的尺寸戳（zoomSize，拍图时与 zoomRegion 同批
    // 记录），过期/尺寸变了就拒；换算出的屏坐标再过 tapNorm 的屏契约，两道门。
    //
    // 保留缺口：displaySize 是整屏最大窗口，不是目标应用的实际窗口；目标应用
    // 在分屏/小窗里时映射本来就按全屏算，本批不改映射，只保证尺寸已知且没过期。

    /** 最近一次观察（dumpUiTree/screenshot）时盖的屏幕尺寸戳；没观察过为 null。 */
    @Volatile
    private var observedSize: android.graphics.Point? = null

    /** 盖一次观察戳：读不到尺寸时记 null（下次坐标动作按“尺寸未知”拒）。 */
    private fun noteObservedSize() {
        observedSize = runCatching { displaySize(service) }.getOrNull()
            ?.takeIf { it.x > 0 && it.y > 0 }
    }

    /**
     * 坐标契约校验：范围 + 尺寸已知 + 与最近一次观察一致 + 换算后像素落屏内。
     * 通过时返回校验用的那份现读尺寸，调用方按它换算；不通过抛
     * IllegalStateException（文案见上，不用 "refused:" 前缀）。
     */
    private fun contractSize(vararg pts: Pair<Int, Int>): android.graphics.Point {
        for ((nx, ny) in pts) {
            requireNorm(nx, "x")
            requireNorm(ny, "y")
        }
        val expected = observedSize
            ?: throw IllegalStateException(
                "坐标契约拒绝：屏幕尺寸未知（本轮还没读到过界面/截图，或读尺寸失败），" +
                    "坐标不可信，请改走编号 eid 点目标，不要盲点坐标。"
            )
        val now = runCatching { displaySize(service) }.getOrNull()
            ?.takeIf { it.x > 0 && it.y > 0 }
            ?: throw IllegalStateException(
                "坐标契约拒绝：屏幕尺寸未知（现在读不到屏幕尺寸），坐标不可信，" +
                    "请改走编号 eid 点目标，不要盲点坐标。"
            )
        if (now != expected) {
            throw IllegalStateException(
                "坐标契约拒绝：屏幕尺寸已变（观察时 ${expected.x}x${expected.y}，现在 " +
                    "${now.x}x${now.y}，可能旋转/分屏/悬浮窗变了），坐标不可信，请改走编号 " +
                    "eid 点目标，不要盲点坐标。"
            )
        }
        for ((nx, ny) in pts) {
            val px = nx.toPixel(now.x)
            val py = ny.toPixel(now.y)
            if (px !in 0 until now.x || py !in 0 until now.y) {
                throw IllegalStateException(
                    "坐标契约拒绝：坐标 ($nx,$ny) 换算后像素 ($px,$py) 落在当前屏幕 " +
                        "${now.x}x${now.y} 之外，请改走编号 eid 点目标，不要盲点坐标。"
                )
            }
        }
        return now
    }

    fun tapNorm(nx: Int, ny: Int, beforePx: FloatArray? = null, handover: Boolean = false): JSONObject {
        // 第九批·坐标契约：范围 + 尺寸已知/未过期 + 像素落屏内，一处校验；
        // 换算用校验返回的那份现读尺寸（contractSize 已含 requireNorm）。
        val size = contractSize(nx to ny)
        requireForeground(handover = handover)
        val w = size.x; val h = size.y
        val px = nx.toPixel(w); val py = ny.toPixel(h)
        guardPopup(px, py, nx, ny)
        // 第2批·坐标点按过遮挡：点之前判断该点有没有被盖住；被盖住改道到同一
        // 元素最大未遮挡块的中心，全盖住抛错（TaskLoop 映射成 TAP_BLOCKED，
        // 模型去处理遮挡物）；没被盖住、树上没东西、算不清时原点照点。
        val point = divertCoveredPoint(px, py, "($nx,$ny)")
        val ax = point.x; val ay = point.y
        // Logged at the four norm/eid entry points rather than in [tap], even
        // though TapMarkerUi is hooked there and treats every path alike. It
        // can afford to — a ring is a ring. Here the distinction is the whole
        // point: only a coordinate the BRAIN chose is a grounding sample; an
        // e-tap's pixels were computed by us and would poison the set.
        guardRepetition(ax, ay) {
            GroundingLog.noteTapCoord(nx, ny, ax, ay, w, h, hold = false, refused = true, pkg = lastPkg())
        }
        GroundingLog.noteTapCoord(nx, ny, ax, ay, w, h, hold = false, refused = false, pkg = lastPkg())
        val result = tap(ax, ay, beforePx, handover = handover).put("norm", "$nx,$ny")
        point.divertedBy?.let { result.put("diverted_by", it) }
        return result
    }

    fun longPressNorm(nx: Int, ny: Int, durationMs: Long, handover: Boolean = false): JSONObject {
        // 第九批·坐标契约：与 tapNorm 同一道门（contractSize 已含 requireNorm）。
        val size = contractSize(nx to ny)
        requireForeground(handover = handover)
        val w = size.x; val h = size.y
        val px = nx.toPixel(w); val py = ny.toPixel(h)
        guardPopup(px, py, nx, ny)
        guardRepetition(px, py) {
            GroundingLog.noteTapCoord(nx, ny, px, py, w, h, hold = true, refused = true, pkg = lastPkg())
        }
        GroundingLog.noteTapCoord(nx, ny, px, py, w, h, hold = true, refused = false, pkg = lastPkg())
        // L11：时长钳制只在 longPress 里做一次，那里会把实际用的时长与原值一起返回。
        return longPress(px, py, durationMs, handover = handover).put("norm", "$nx,$ny")
    }

    // ---- the popup guard ---------------------------------------------------
    //
    // A popup menu is the one place on a blind screen where a small aim error
    // is not merely a wasted tap. Its items sit a few dozen pixels apart and
    // one of them is routinely irreversible — 删除, 撤回, 清空, 不再关注 — so
    // "aim again" is not a recovery, it is the damage.
    //
    // Measured 2026-10-20, WeChat Moments, tree withheld: reaching for the
    // 「··」 menu the brain landed on its own comment first, got the
    // 复制/删除 pair, aimed at 评论, and hit 删除. It reported deleting a
    // comment it had written the day before. (The comment survived — the tap
    // did something else — but nothing in the software had prevented it.)
    //
    // No OCR is involved and none is needed. A menu is a WINDOW, and window
    // bounds are readable even when every node inside is withheld: a small,
    // non-active, application-type window covering the point is a popup. That
    // is the whole test. What it buys is the requirement, not the judgement:
    // the point must have been inside a zoom taken in the last [ZOOM_CONFIRM_MS],
    // because a magnified look is the only way this brain can tell 评论 from
    // 删除 before it commits.
    //
    // Deliberately not limited to blind trees. An app that gives us e-numbers
    // is one where the brain taps by eid and never reaches this function, so
    // the only coordinate taps that arrive here are ones where the aim is its
    // own — which is exactly the case that needs a second look.

    /** A small floating window of the app, under the point, or null. */
    private fun popupAt(px: Int, py: Int): Rect? = popups().firstOrNull { it.contains(px, py) }

    /** Every small floating window of another app on screen — menus, bubbles, sheets. */
    private fun popups(): List<Rect> {
        val windows = runCatching { service.windows }.getOrNull() ?: return emptyList()
        val screenArea = screenW.toLong() * screenH
        // M26: 系统型窗口只有属于目标包（最近一次 dump 的包 / 前台包）时才算弹窗，
        // 否则输入法、系统悬浮窗都会被当成菜单。
        val targetPkg = lastPkg() ?: foregroundPkg()
        val out = mutableListOf<Rect>()
        val r = Rect()
        // 先收一遍各窗口的 bounds 与包名：主窗口是其中最大的应用窗口，弹窗是
        // “非全屏且非主窗口”的那一个——不只看面积百分比（M26）。M16：面积阈值
        // 回到 Andee 的 45%，再加高度规则（高 > 屏幕 60% 的不是弹窗，是分屏/侧栏）。
        data class WinInfo(val rect: Rect, val pkg: String, val type: Int)
        val infos = ArrayList<WinInfo>()
        try {
            for (win in windows) {
                val type = runCatching { win.type }.getOrNull() ?: continue
                // IMEs, status/navigation bars and our own overlay are not menus.
                if (type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                    type != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
                ) continue
                val root = runCatching { rootOf(win) }.getOrNull()
                val pkg = runCatching { root?.packageName?.toString() }.getOrNull().orEmpty()
                runCatching { root?.recycle() }
                if (pkg == service.packageName) continue
                // NOT filtered on isActive: a popup takes focus, so "the active
                // window" is exactly what a menu is. Measured 2026-10-20 — the
                // first cut skipped active windows and the guard never fired on
                // WeChat's chat-list long-press menu, which is focused. Size is
                // what separates a menu from the app: a main window covers the
                // screen, a popup does not.
                if (!runCatching { win.getBoundsInScreen(r); true }.getOrDefault(false)) continue
                if (r.width() <= 0 || r.height() <= 0) continue
                infos.add(WinInfo(Rect(r), pkg, type))
            }
            val mainArea = infos
                .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .maxOfOrNull { it.rect.width().toLong() * it.rect.height() } ?: 0L
            for (info in infos) {
                val area = info.rect.width().toLong() * info.rect.height()
                if (area > screenArea * 45 / 100) continue // M16：面积 >45% 不是弹窗（Andee 原值）
                if (info.rect.height() > screenH * 6 / 10) continue // M16：高 >60% 不是弹窗（分屏/侧栏）
                if (info.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                    area >= mainArea
                ) continue // 主窗口不是弹窗
                if (info.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM &&
                    (targetPkg.isNullOrEmpty() || info.pkg != targetPkg)
                ) continue // 系统窗口只有属于目标包时才算弹窗
                out += Rect(info.rect)
            }
            return out
        } finally {
            windows.forEach { runCatching { it.recycle() } }
        }
    }

    /**
     * Say whether a menu is open, so the brain does not have to judge it from
     * pixels. Measured 2026-10-20 on WeChat Moments: the 「··」 menu is its own
     * window even with the tree withheld, and the brain, unable to tell open
     * from closed off a picture, reopened the same menu six times. The window
     * is NOT focused, which is also why `back` leaves the page instead of
     * closing it — said here because this is where the brain decides.
     */
    private fun putPopups(out: JSONObject) {
        val list = runCatching { popups() }.getOrDefault(emptyList())
        out.put("popup_open", list.isNotEmpty())
        if (list.isEmpty()) return
        val arr = org.json.JSONArray()
        for (p in list) {
            arr.put(
                org.json.JSONArray()
                    .put(p.left * NORM_MAX / screenW).put(p.top * NORM_MAX / screenH)
                    .put(p.right * NORM_MAX / screenW).put(p.bottom * NORM_MAX / screenH)
            )
        }
        out.put("popups", arr).put(
            "popup_note",
            "A popup (menu / bubble) is open, at popups = [left, top, right, bottom] in 0-1000. " +
                "To close it, tap the button that opened it again; `back` usually goes to the " +
                "page underneath and leaves it."
        )
    }

    /**
     * 小窗展开（jk-v64，A 方案）：跨应用 openApp 要全屏，但 Flyme/Android 16 上
     * setLaunchWindowingMode 反射不存在（NoSuchMethodException），回落普通启动后
     * 支付宝/高德这类应用会开成小窗；任务循环整套按全屏做（坐标、网格、前台判断），
     * 小窗里的东西点不到。打开后调这里：按包名找到目标应用的窗口，若未铺满屏幕，
     * 就把它的顶栏拖到屏幕顶沿展开成全屏，再复核一次。成败都如实返回，不抛异常、
     * 不阻塞任务——展开失败时任务照常继续，审计里能看到原因。
     */
    fun expandFloatingApp(targetPkg: String): JSONObject {
        val out = JSONObject()
        // 等窗口出现：冷启动的应用 1.2 秒可能还没打开窗口，最多等 3 秒；
        // 窗口一出现就动手，不硬等满。
        var before: Rect? = null
        val deadline = SystemClock.uptimeMillis() + 3000
        while (before == null && SystemClock.uptimeMillis() < deadline) {
            before = appWindow(targetPkg)
            if (before == null) {
                try { Thread.sleep(300) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
        if (before == null) {
            out.put("expanded", false)
            out.put("detail", "没找到应用窗口，可能还没打开")
            return out
        }
        if (isFullscreen(before)) {
            out.put("expanded", false)
            out.put("detail", "已是全屏，不用展开")
            return out
        }
        // 横条在窗口最下方：往下拖横条展开成全屏（用户纠正：往下走是全屏，
        // 往上走是小窗缩小比例——之前往上拖的方向反了）。
        val startX = before.centerX()
        val startY = before.bottom - 12
        // 展开拖动只认目标包的前台（S2 的 expectedPkg）；目标还没到前台时
        // swipe 会拒绝，展开如实报失败，不抛到任务循环里。
        val dragged = runCatching {
            swipe(startX, startY, screenW / 2, screenH - 5, 400, fling = false, expectedPkg = targetPkg)
            true
        }.onFailure { Log.w("Body", "expandFloatingApp: drag refused: ${it.message}") }
            .getOrDefault(false)
        if (!dragged) {
            out.put("expanded", false)
            out.put("detail", "展开拖动未执行（目标应用不在前台），展开失败")
            return out
        }
        try { Thread.sleep(900) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        val after = appWindow(targetPkg)
        if (after != null && isFullscreen(after)) {
            out.put("expanded", true)
            out.put("detail", "小窗已展开成全屏")
        } else {
            out.put("expanded", false)
            out.put("detail", "拖动后仍是小窗，展开失败")
        }
        return out
    }

    /** 该包名的应用窗口（取最大的一个）；没有返回 null。 */
    private fun appWindow(targetPkg: String): Rect? {
        val windows = runCatching { service.windows }.getOrNull() ?: return null
        val r = Rect()
        var best: Rect? = null
        var bestArea = 0L
        try {
            for (win in windows) {
                val type = runCatching { win.type }.getOrNull() ?: continue
                if (type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = runCatching { rootOf(win) }.getOrNull()
                val pkg = runCatching { root?.packageName?.toString() }.getOrNull().orEmpty()
                runCatching { root?.recycle() }
                if (pkg != targetPkg) continue
                if (!runCatching { win.getBoundsInScreen(r); true }.getOrDefault(false)) continue
                if (r.width() <= 0 || r.height() <= 0) continue
                val area = r.width().toLong() * r.height()
                if (area > bestArea) {
                    bestArea = area
                    best = Rect(r)
                }
            }
        } finally {
            windows.forEach { runCatching { it.recycle() } }
        }
        return best
    }

    /** 是否铺满屏幕：面积 ≥ 屏幕 80% 即算全屏（留出状态栏/导航栏的余量）。 */
    private fun isFullscreen(rect: Rect): Boolean {
        val area = rect.width().toLong() * rect.height()
        val screenArea = screenW.toLong() * screenH
        return area >= screenArea * 80 / 100
    }

    /** Was (nx,ny) covered by a zoom taken recently enough to still be true? */
    private fun zoomConfirms(nx: Int, ny: Int): Boolean {
        val z = zoomRegion ?: return false
        if (SystemClock.uptimeMillis() - zoomAtMs > ZOOM_CONFIRM_MS) return false
        return nx >= z[0] && nx <= z[2] && ny >= z[1] && ny <= z[3]
    }

    private fun guardPopup(px: Int, py: Int, nx: Int, ny: Int) {
        val box = popupAt(px, py) ?: return
        if (zoomConfirms(nx, ny)) return
        val where = "${box.left},${box.top} to ${box.right},${box.bottom}"
        throw IllegalStateException(
            "Refused: that point is inside a popup ($where px). Nothing in it is readable " +
                "through accessibility, its items sit a few dozen pixels apart, and one of " +
                "them is usually irreversible — 删除 / 撤回 / 清空 / 不再关注. A tap aimed at " +
                "评论 can land on 删除, and that cannot be undone, so this one is NOT fired " +
                "blind. Look first: call zoom_screen_region on this point, read the items in " +
                "the magnified image, then send the same tap with on=\"zoom\" (or a lowercase " +
                "cell). A zoom that covers this point, taken within " +
                "${ZOOM_CONFIRM_MS / 1000} s, is what unlocks it."
        )
    }

    // ---- the aim grid, and the two ways to name a point --------------------
    //
    // This exists because a general-purpose vision model *reads* a position off
    // an image far better than it *computes* one, and the arithmetic is where
    // it has been losing: on a self-drawn pane — the exact case where the
    // element tree is empty and pixels are all there is — the old protocol
    // asked the model to measure a point on a 1280-px image and rescale it to
    // 0-1000 by hand. Measured in the field, three attempts at one button
    // spread across 410 px.
    //
    // So every image the brain sees now carries a faint labelled grid, and it
    // may answer with a cell label instead of a number. Two vocabularies, and
    // they are deliberately the same one twice: a cell is always A1..H12 of
    // whatever image the label was read off, and `on` says which image that
    // was.
    //
    // A cell alone is too coarse to tap with, and that is measured, not
    // guessed: a cell is 150x222 px on a 1200x2664 phone, so aiming at its
    // centre hits a 460x140 button 64% of the time and a 110 px icon 38% —
    // *for a model that reads the cell perfectly*. Hence `part`, a third of the
    // cell each way (top-left … bottom-right): the same perfect reader then
    // hits the icon every time and a 66 px one 90% of the time, in one step.
    // Zooming a cell is still there for anything smaller.
    //
    // Nothing here redefines a coordinate. Every path still ends in
    // [tapNorm]'s 0-1000 screen space, so the stuck-loop guard and
    // [GroundingLog] see exactly the same numbers they saw before, and a cell
    // tap is still a coordinate tap as far as the offline pairing is concerned.

    /**
     * Left, top, right, bottom of the last [lookRegion], in screen 0-1000
     * space. This is the whole of what a zoom has to remember: the region's
     * geometry is a fact about the screen, so a point inside it converts the
     * same way whether the content has since changed or not.
     */
    @Volatile
    private var zoomRegion: IntArray? = null

    @Volatile
    private var zoomAtMs = 0L

    /**
     * 第九批·坐标契约：拍 zoom 图时的屏幕尺寸，与 zoomRegion/zoomAtMs 同批记录
     * （见 lookRegion）。zoomToScreen 换算前先比它：图是旧尺寸拍的，图上坐标
     * 已不可信，直接拒，不换算。
     */
    @Volatile
    private var zoomSize: android.graphics.Point? = null

    /**
     * Set when [typeText] puts text into an editor on a tree-blind app; cleared by
     * the next tap. While set, a tap that was not aimed off a fresh zoom is
     * refused — see [requireAimedSend].
     */
    @Volatile
    private var typedBlindAt = 0L

    /** Last injected gesture or global action — a zoom older than this shows a screen that is gone. */
    @Volatile
    private var screenMovedAtMs = 0L

    private class Aim(val nx: Int, val ny: Int, val label: String)

    private fun cellLabel(col: Int, row: Int): String = "${('A' + col)}${row + 1}"

    /**
     * The centre of one third-by-third of a cell, in the 0-1000 space of the
     * image it was read off. `fx`/`fy` are 0..2; (1, 1) is the cell's centre.
     */
    private fun cellPoint(col: Int, row: Int, fx: Int, fy: Int): IntArray = intArrayOf(
        ((col * PART_COLS + fx) * 2 + 1) * NORM_MAX / (2 * GRID_COLS * PART_COLS),
        ((row * PART_ROWS + fy) * 2 + 1) * NORM_MAX / (2 * GRID_ROWS * PART_ROWS),
    )

    /** `part` → which subdivision of the cell, as (fx, fy); null means the centre. */
    private fun parsePart(part: String?): IntArray {
        if (part.isNullOrBlank()) return intArrayOf(1, MID_ROW)
        val p = part.trim().lowercase().replace('_', '-').replace(' ', '-')
        return PART_THIRDS[p] ?: throw IllegalArgumentException(
            "unknown part=\"$part\" — expected one of ${PARTS.joinToString()} " +
                "(which part of the cell the target is in; omit it for the centre)"
        )
    }

    private fun badCell(cell: String) =
        "bad grid cell '$cell' — expected a column letter A..${'A' + GRID_COLS - 1} " +
            "and a row number 1..$GRID_ROWS, exactly as labelled on the image you were " +
            "shown (e.g. \"D7\")"

    private fun parseCell(cell: String): IntArray {
        val s = cell.trim().uppercase()
        val row = if (s.length >= 2) s.substring(1).toIntOrNull()?.minus(1) else null
        val col = if (s.isNotEmpty()) s[0] - 'A' else -1
        if (row == null || col !in 0 until GRID_COLS || row !in 0 until GRID_ROWS) {
            throw IllegalArgumentException(badCell(cell))
        }
        return intArrayOf(col, row)
    }

    /** A point in the last zoom image's own 0-1000 space → screen 0-1000. */
    private fun zoomToScreen(lx: Int, ly: Int): IntArray {
        val r = zoomRegion
            ?: throw IllegalStateException(
                "on=\"zoom\" needs a zoom to have been taken first — call " +
                    "zoom_screen_region and read the aim off the image it returns"
            )
        val age = SystemClock.uptimeMillis() - zoomAtMs
        if (age > ZOOM_STALE_MS) {
            throw IllegalStateException(
                "the last zoom image is ${age / 1000}s old — the screen has almost " +
                    "certainly moved since. Take a fresh zoom_screen_region (or a " +
                    "screenshot) and aim from that image instead of this one"
            )
        }
        // M27: 只看时效不够——上次手势之后屏幕已经动过，zoom 再新也过期。
        if (zoomAtMs <= screenMovedAtMs) {
            throw IllegalStateException(
                "the last zoom predates the last gesture, so it no longer shows this " +
                    "screen (zoom 已过期）. Take a fresh zoom_screen_region and aim from " +
                    "that image instead of this one"
            )
        }
        // 第九批·坐标契约（zoom 图坐标系）：图内 0-1000 是图自身的空间，换算前先
        // 验拍图时的尺寸戳——拍图后旋转/分屏/悬浮窗变了尺寸，图上读的点已对不上
        // 现在的屏，直接拒（重拍 zoom 或改走编号 eid）。文案不用 "refused:"
        // 前缀，免得 TaskLoop 的自动 zoom 重拍把它当盲点守卫接住。
        val zoomedSize = zoomSize
        val nowSize = runCatching { displaySize(service) }.getOrNull()
            ?.takeIf { it.x > 0 && it.y > 0 }
        if (zoomedSize == null || nowSize == null) {
            throw IllegalStateException(
                "坐标契约拒绝：放大图没有记录拍图时的屏幕尺寸（或现在读不到），图上坐标" +
                    "不可信，请重新 zoom 再点，或改走编号 eid。"
            )
        }
        if (nowSize != zoomedSize) {
            throw IllegalStateException(
                "坐标契约拒绝：放大图是旧屏幕尺寸拍的（拍时 ${zoomedSize.x}x${zoomedSize.y}，" +
                    "现在 ${nowSize.x}x${nowSize.y}），图上坐标已不可信，请重新 zoom 再点，" +
                    "或改走编号 eid。"
            )
        }
        requireNorm(lx, "x"); requireNorm(ly, "y")
        return intArrayOf(
            r[0] + lx * (r[2] - r[0]) / NORM_MAX,
            r[1] + ly * (r[3] - r[1]) / NORM_MAX,
        )
    }

    /**
     * Turn whatever the brain said into screen coordinates.
     *
     * @param on which image the aim was read off — "screen" (the default) or
     *   "zoom" (the last [lookRegion] image, whose own 0-1000 space maps onto
     *   the region it showed).
     * @param cell a grid label from that image, e.g. "D7".
     * @param part which third of that cell, e.g. "bottom-right"; null = centre.
     */
    private fun resolveAim(on: String?, x: Int?, y: Int?, cell: String?, part: String?): Aim {
        var zoom = when (on?.trim()?.lowercase()) {
            null, "", "screen" -> false
            "zoom" -> true
            else -> throw IllegalArgumentException(
                "unknown on=\"$on\" — expected \"screen\" (a full screenshot) or \"zoom\" " +
                    "(the image zoom_screen_region returned)"
            )
        }
        if (cell != null && cell.isNotBlank()) {
            // The grid labels a zoom in lowercase and a full screen in
            // uppercase, so the label alone says where it was read — and it has
            // to be honoured, because "d7 from the magnified picture" and "D7
            // from the screen" are 120 units apart and both plausible. Only
            // consulted when `on` was not given: an explicit on= wins, so a
            // model that says on="screen" with a lowercase label is corrected
            // by the parser rather than silently followed off the zoom.
            if (on.isNullOrBlank() && cell.trim().all { it.isLowerCase() || !it.isLetter() } &&
                cell.trim().any { it.isLetter() }
            ) {
                zoom = true
            }
            val c = parseCell(cell)
            val f = parsePart(part)
            val local = cellPoint(c[0], c[1], f[0], f[1])
            val where = if (part.isNullOrBlank()) "cell $cell" else "cell $cell ${part.trim()}"
            if (!zoom) return Aim(local[0], local[1], where)
            val s = zoomToScreen(local[0], local[1])
            return Aim(s[0], s[1], "$where of the zoom image")
        }
        if (x == null || y == null) {
            throw IllegalArgumentException(
                "aim needs either {\"cell\": \"D7\"} or {\"x\": …, \"y\": …} — see " +
                    "the grid drawn on the last image you were shown"
            )
        }
        if (!zoom) return Aim(requireNorm(x, "x"), requireNorm(y, "y"), "screen $x,$y")
        val s = zoomToScreen(x, y)
        return Aim(s[0], s[1], "zoom $x,$y")
    }

    /**
     * On an app that withholds its tree, a coordinate tap must land inside a
     * zoom taken since the screen last moved.
     *
     * Measured 2026-10-20 on WeChat 8.0.79 (HONOR AMM-AN00, tree withheld):
     * taps aimed off a zoom changed the screen 33 times in 41; taps aimed off
     * the full screenshot 124 in 211, and the misses were the expensive kind —
     * a profile page, a 关注 instead of 谢谢, a 删除 instead of 评论. On a phone
     * the shot is downscaled to ~0.48× and a 「··」 is about 30 px in it, which
     * is below what this brain can place a point on. Only the region's
     * geometry is checked, so a zoom aimed at one cell then a tap named off
     * the full screen still passes if it falls inside the magnified area.
     */
    private fun requireZoomedOnBlind(a: Aim) {
        val z = zoomRegion
        val fresh = z != null && zoomAtMs > screenMovedAtMs &&
            SystemClock.uptimeMillis() - zoomAtMs <= ZOOM_CONFIRM_MS
        if (fresh && a.nx in z!![0]..z[2] && a.ny in z[1]..z[3]) return
        throw IllegalStateException(
            "refused: this app hides its contents from accessibility, so this tap would be " +
                "aimed by eye off a downscaled screenshot — where small buttons are a few dozen " +
                "pixels and a near miss opens a profile, follows someone or deletes something. " +
                (if (z != null && zoomAtMs <= screenMovedAtMs) "Your last zoom predates the last gesture, so it no longer shows this screen. " else "") +
                "NEXT: zoom_screen_region on the target (same cell + part), check the crosshair, " +
                "then tap with on=\"zoom\" from THAT image. One zoom per screen: any tap, swipe " +
                "or back makes the next tap need a fresh one."
        )
    }

    fun tapAim(on: String?, x: Int?, y: Int?, cell: String?, part: String?, handover: Boolean = false): JSONObject {
        val a = resolveAim(on, x, y, cell, part)
        val blind = treeBlind()
        if (blind) requireZoomedOnBlind(a)
        requireAimedSend(a)
        val s = if (blind) snapToShape(a.nx, a.ny) else null
        val nx = s?.nx ?: a.nx
        val ny = s?.ny ?: a.ny
        val r = tapNorm(nx, ny, s?.signature, handover = handover).put("aimed_at", a.label)
        if (s?.moved == true) {
            r.put("snapped_from", "${a.nx},${a.ny}")
                .put("snapped_to", "$nx,$ny")
                .put(
                    "snap_note",
                    "Your point was on blank background ${s.distPx} px from one small isolated " +
                        "shape, so the device tapped that shape's centre instead. If that shape " +
                        "was not your target, aim again further from it."
                )
        }
        return r
    }

    private class Snap(val nx: Int, val ny: Int, val moved: Boolean, val distPx: Int, val signature: FloatArray?)

    /**
     * The tap after typing on a tree-blind app is almost always "send", and its
     * button sits in a strip a few dozen px tall directly on top of the content it
     * replies to — on WeChat Moments a grid-cell guess landed on the shared article
     * below in two runs out of two, opening it and leaving the comment unsent. A
     * zoom taken after the typing is the only image that shows the strip at a size
     * a model can hit, so until a zoom-aimed tap lands, every other tap is refused
     * with that instruction. Expires after [SEND_AIM_WINDOW_MS] so a task that typed
     * and moved on is not haunted by it.
     */
    private fun requireAimedSend(a: Aim) {
        val typed = typedBlindAt
        if (typed == 0L) return
        // 已过期：守卫自然解除，清掉。
        if (SystemClock.uptimeMillis() - typed > SEND_AIM_WINDOW_MS) {
            typedBlindAt = 0L
            return
        }
        // M28: 输入框没了（已发送 / 已收起 / 应用切走）时不要清零——editing() 读不到
        // 也可能是瞬时状态，先放行这一次点按，但守卫保持武装，下一次点按仍要查。
        if (!A11yIme.editing(service)) return
        // M28 + M10-A: 放行只认“有 zoom 图背书”——zoom 区域包含目标点，且 zoom
        // 新于打字、新于上次手势、且在确认时效内。on="zoom" 的标签不再是放行条件：
        // 从 zoom 图上读的点换算后本来就在区域内；而 on="screen" 的坐标点只要落在
        // 一张新鲜 zoom 的区域里，同样是看过放大图才点的，也放行。
        val z = zoomRegion
        val zoomOk = z != null && zoomAtMs > typed && zoomAtMs > screenMovedAtMs &&
            SystemClock.uptimeMillis() - zoomAtMs <= ZOOM_CONFIRM_MS &&
            a.nx in z[0]..z[2] && a.ny in z[1]..z[3]
        if (zoomOk) {
            typedBlindAt = 0L
            return
        }
        // 不放行：typedBlindAt 保持原值，守卫继续武装，直到一次 zoom 背书的点按
        // 通过、或过期、或输入框消失。
        throw IllegalStateException(
            "refused: you just typed into a box on an app that hides its tree, and this tap " +
                "was not aimed off a fresh zoom. The Send button and the input sit in a thin " +
                "strip right on top of the post/message below — a grid-cell tap here lands on " +
                "that content and opens it, losing the send. NEXT: zoom_screen_region on the " +
                "Send button (cell + part), then tap with on=\"zoom\" from THAT image — or tap " +
                "a point inside that fresh zoom's region. If you " +
                "meant to tap something other than Send, the same applies: zoom first."
        )
    }

    /** A tree of a handful of nodes says nothing about what is on screen — see [BLIND_TREE_NODES]. */
    private fun treeBlind(): Boolean {
        // S7: 没有根节点就是全盲——之前返回 false 会跳过 zoom 守卫，在完全没树的
        // 屏上放行盲目坐标点。
        val root = runCatching { activeRoot() }.getOrNull() ?: return true
        return try {
            // 节点数少还不够：只数节点会把“只有一个可点按钮的弹窗”也判成盲。
            // ≤3 个节点且其中没有任何可操作节点（可点/可滑/带文本、描述或 id）
            // 时才是盲树。
            if (countNodes(root, BLIND_TREE_NODES + 1) > BLIND_TREE_NODES) false
            else !hasActionableNode(root, 64)
        } finally {
            root.recycle()
        }
    }

    /** 树里有没有哪怕一个可操作节点（有界递归，节点数少时才调用）。 */
    private fun hasActionableNode(node: AccessibilityNodeInfo, budget: Int): Boolean {
        if (budget <= 0) return false
        if (node.isClickable || node.isScrollable || node.isFocusable || node.isChecked ||
            !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty() ||
            !node.viewIdResourceName.isNullOrEmpty()
        ) return true
        for (i in 0 until node.childCount) {
            val c = runCatching { childOf(node, i) }.getOrNull() ?: continue
            try {
                if (hasActionableNode(c, budget - 1)) return true
            } finally {
                c.recycle()
            }
        }
        return false
    }

    private fun countNodes(node: AccessibilityNodeInfo, cap: Int): Int {
        var n = 1
        for (i in 0 until node.childCount) {
            if (n >= cap) break
            val c = runCatching { childOf(node, i) }.getOrNull() ?: continue
            try {
                n += countNodes(c, cap - n)
            } finally {
                c.recycle()
            }
        }
        return n
    }

    /**
     * Pull a coordinate tap that landed on blank background onto the one small
     * shape beside it.
     *
     * Measured on WeChat Moments with the tree withheld: the "··" button is
     * ~70×44 px, and four aimed taps in a row missed it — the last one inside
     * its column and 2 px above its top edge. Vision models read *which* thing
     * well and *where exactly* poorly, and a miss by a few pixels onto blank
     * space does nothing at all. So when the point is on uniform background and
     * exactly one compact shape sits within [SNAP_RADIUS_DP], the device taps
     * that shape's centre. Anything less clear-cut — the point is on content,
     * nothing is near, two things are near, the near thing is large or runs out
     * of the search patch — leaves the tap where it was aimed.
     *
     * Only for blind trees: with a real tree the brain has e-numbers and does
     * not need this. The same frame doubles as the before-image for the pixel
     * comparison in [tap], so snapping costs no extra capture there.
     */
    private fun snapToShape(nx: Int, ny: Int): Snap? {
        val mask = ownArea()
        val density = service.resources.displayMetrics.density
        val radius = (SNAP_RADIUS_DP * density).toInt()
        val minSide = (SNAP_MIN_DP * density).toInt().coerceAtLeast(4)
        val maxSide = (SNAP_MAX_DP * density).toInt()
        val half = radius + maxSide
        return captureFrame { hw ->
            val sig = signatureOf(hw, mask)
            val w = hw.width; val h = hw.height
            val tx = nx.toPixel(w); val ty = ny.toPixel(h)
            val unmoved = Snap(nx, ny, false, 0, sig)
            val x0 = (tx - half).coerceAtLeast(0)
            val y0 = (ty - half).coerceAtLeast(0)
            val x1 = (tx + half).coerceAtMost(w)
            val y1 = (ty + half).coerceAtMost(h)
            val pw = x1 - x0; val ph = y1 - y0
            if (pw <= 0 || ph <= 0) return@captureFrame unmoved
            val crop = Bitmap.createBitmap(hw, x0, y0, pw, ph)
            val soft = if (crop.config == Bitmap.Config.HARDWARE) {
                crop.copy(Bitmap.Config.ARGB_8888, false).also { crop.recycle() }
            } else crop
            val px = IntArray(pw * ph)
            soft.getPixels(px, 0, pw, 0, 0, pw, ph)
            soft.recycle()
            val found = findSnapTarget(px, pw, ph, tx - x0, ty - y0, x0, y0, mask, radius, minSide, maxSide)
                ?: return@captureFrame unmoved
            val sx = ((found[0] + x0) * NORM_MAX + w / 2) / w
            val sy = ((found[1] + y0) * NORM_MAX + h / 2) / h
            Snap(sx.coerceIn(0, NORM_MAX), sy.coerceIn(0, NORM_MAX), true, found[2], sig)
        }
    }

    /**
     * Connected components of "not the background" in the patch, joined across
     * gaps of up to one block so the two dots of a "··" count as one shape.
     * Returns {centreX, centreY, distance} in patch pixels, or null.
     */
    private fun findSnapTarget(
        px: IntArray, pw: Int, ph: Int, tx: Int, ty: Int, ox: Int, oy: Int,
        mask: Rect?, radius: Int, minSide: Int, maxSide: Int,
    ): IntArray? {
        if (tx !in 0 until pw || ty !in 0 until ph) return null
        if (mask != null && mask.contains(tx + ox, ty + oy)) return null
        val bg = px[ty * pw + tx]
        fun fg(c: Int): Boolean {
            val d = maxOf(
                kotlin.math.abs(Color.red(c) - Color.red(bg)),
                kotlin.math.abs(Color.green(c) - Color.green(bg)),
                kotlin.math.abs(Color.blue(c) - Color.blue(bg)),
            )
            return d >= SNAP_FG_DELTA
        }
        // Only the pixel itself: the miss this exists for was 2 px off the plate's edge.
        if (fg(px[ty * pw + tx])) return null

        val b = SNAP_BLOCK
        val bw = (pw + b - 1) / b; val bh = (ph + b - 1) / b
        val blockFg = BooleanArray(bw * bh)
        val blockMasked = BooleanArray(bw * bh)
        for (yy in 0 until ph) for (xx in 0 until pw) {
            val bi = (yy / b) * bw + xx / b
            if (mask != null && mask.contains(xx + ox, yy + oy)) blockMasked[bi] = true
            else if (!blockFg[bi] && fg(px[yy * pw + xx])) blockFg[bi] = true
        }

        val label = IntArray(bw * bh) { -1 }
        val queue = IntArray(bw * bh)
        var near = 0
        var pick: IntArray? = null
        var comp = 0
        for (start in 0 until bw * bh) {
            if (!blockFg[start] || label[start] >= 0) continue
            var head = 0; var tail = 0
            queue[tail++] = start; label[start] = comp
            var bx0 = Int.MAX_VALUE; var by0 = Int.MAX_VALUE; var bx1 = -1; var by1 = -1
            var spoiled = false
            while (head < tail) {
                val i = queue[head++]
                val cx = i % bw; val cy = i / bw
                bx0 = minOf(bx0, cx); by0 = minOf(by0, cy); bx1 = maxOf(bx1, cx); by1 = maxOf(by1, cy)
                if (cx == 0 || cy == 0 || cx == bw - 1 || cy == bh - 1) spoiled = true
                for (dy in -SNAP_GAP_BLOCKS..SNAP_GAP_BLOCKS) for (dx in -SNAP_GAP_BLOCKS..SNAP_GAP_BLOCKS) {
                    val nxb = cx + dx; val nyb = cy + dy
                    if (nxb !in 0 until bw || nyb !in 0 until bh) continue
                    val j = nyb * bw + nxb
                    if (blockMasked[j]) spoiled = true
                    if (blockFg[j] && label[j] < 0) { label[j] = comp; queue[tail++] = j }
                }
            }
            // Pixel-exact bounds of the component, not block-rounded.
            var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var btm = -1
            for (yy in by0 * b until minOf((by1 + 1) * b, ph)) for (xx in bx0 * b until minOf((bx1 + 1) * b, pw)) {
                if (label[(yy / b) * bw + xx / b] == comp && fg(px[yy * pw + xx])) {
                    if (xx < l) l = xx; if (xx > r) r = xx
                    if (yy < t) t = yy; if (yy > btm) btm = yy
                }
            }
            comp++
            if (r < 0) continue
            val dx = maxOf(l - tx, 0, tx - r); val dy = maxOf(t - ty, 0, ty - btm)
            val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toInt()
            if (dist > radius) continue
            near++
            if (near > 1) return null
            val sw = r - l + 1; val sh = btm - t + 1
            pick = if (spoiled || sw < minSide || sh < minSide || sw > maxSide || sh > maxSide) null
            else intArrayOf((l + r) / 2, (t + btm) / 2, dist)
        }
        return if (near == 1) pick else null
    }

    fun longPressAim(
        on: String?, x: Int?, y: Int?, cell: String?, part: String?, durationMs: Long,
        handover: Boolean = false,
    ): JSONObject {
        val a = resolveAim(on, x, y, cell, part)
        if (treeBlind()) requireZoomedOnBlind(a)
        return longPressNorm(a.nx, a.ny, durationMs, handover = handover).put("aimed_at", a.label)
    }

    /**
     * Zoom on the point a cell (+ part) would tap, with a crosshair on it —
     * look before you fire.
     *
     * Centred on the aim point and two cells each way, not on the cell
     * itself: targets sit on grid lines as often as not (a dialog's ✕ is
     * centred on the screen, and the screen's centre *is* a grid line), and
     * a zoom of exactly one cell cut such a target into quarters. The
     * crosshair turns "where is the target" — the question vision models get
     * wrong — into "is the mark on it", which they get right.
     */
    fun zoomAim(cell: String, part: String?): JSONObject {
        val a = resolveAim("screen", null, null, cell, part)
        val out = lookRegion(
            a.nx, a.ny, 2 * NORM_MAX / GRID_COLS, 2 * NORM_MAX / GRID_ROWS,
            mark = intArrayOf(a.nx, a.ny),
        )
        val args = if (part.isNullOrBlank()) "{\"cell\": \"$cell\"}"
        else "{\"cell\": \"$cell\", \"part\": \"${part.trim()}\"}"
        return out.put("aimed_at", a.label).put(
            "aim_check",
            "The RED CROSSHAIR is exactly where tap_by_coordinates $args would land. " +
                "If it sits on the target, tap with those same arguments. If it does " +
                "not, do NOT nudge the screen cell: name the lowercase cell (+ part) " +
                "of THIS image where the target is, with on=\"zoom\".",
        )
    }

    /**
     * Draw the aim grid onto an image the brain is about to be shown, in
     * place-of-copy (the source is left alone; the caller recycles the copy).
     *
     * The lines are deliberately **faint and two-tone** — a dark hairline with
     * a white halo — because this lands on top of content nobody chose for it
     * to sit on: a white document, a night-mode chat, a photo. One colour
     * alone disappears on half the things this device looks at, and a grid you
     * cannot see is worse than no grid, because the model will still aim by it.
     * The **labels are not faint**: they are the thing being read, so they sit
     * on dark pills. They are rulers — letters along the top edge, numbers down
     * the left — and never inside a cell, where they covered the target.
     *
     * Same grid, same size, on every image — and on a zoom it is *lowercase*,
     * which is the whole disambiguation: a label read off a magnified region
     * must never be mistakable for a cell of the full screen, and the case
     * carries that from the image itself rather than from a rule the model has
     * to remember. [resolveAim] honours it in the other direction too — a
     * lowercase cell with no `on` means the zoom.
     *
     * The returned PNG is what the brain sees AND what [GroundingLog] keeps.
     * One encode, deliberately: the pairing's job is to show what the model was
     * actually looking at, and a training set of ungridded images beside
     * gridded inferences would be a set about a screen nobody saw.
     */
    /**
     * A hollow ring with a gap at its centre, so the mark says where without
     * hiding what is under it. Red with a white halo: it has to win against
     * the grid and against whatever the app painted.
     */
    private fun drawCrosshair(bmp: Bitmap, x: Float, y: Float) {
        runCatching {
            val canvas = Canvas(bmp)
            val r = minOf(bmp.width, bmp.height) * 0.045f
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.WHITE
                strokeWidth = r * 0.30f
            }
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                // Magenta, not red. WeChat (and every Chinese super-app) marks
                // an unread message with a small red dot, and a red crosshair
                // landing next to one is indistinguishable from it — measured
                // 2026-10-20: the brain read the mark as "just a hint badge,
                // not something I can open" and went looking for another
                // entrance. Nothing in these apps is magenta.
                color = CROSSHAIR
                strokeWidth = r * 0.14f
            }
            for (p in listOf(halo, ink)) {
                canvas.drawCircle(x, y, r, p)
                canvas.drawLine(x - r * 1.8f, y, x - r * 0.4f, y, p)
                canvas.drawLine(x + r * 0.4f, y, x + r * 1.8f, y, p)
                canvas.drawLine(x, y - r * 1.8f, x, y - r * 0.4f, p)
                canvas.drawLine(x, y + r * 0.4f, x, y + r * 1.8f, p)
            }
            canvas.drawCircle(x, y, r * 0.08f, ink.apply { style = Paint.Style.FILL })
        }
    }

    private fun withAimGrid(src: Bitmap, zoom: Boolean): Bitmap {
        val out = runCatching { src.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull() ?: return src
        runCatching {
            val w = out.width
            val h = out.height
            val cw = w.toFloat() / GRID_COLS
            val ch = h.toFloat() / GRID_ROWS
            val canvas = Canvas(out)
            val unit = minOf(cw, ch)
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.WHITE
                alpha = 38
                strokeWidth = (unit * 0.06f).coerceIn(2f, 3f)
            }
            val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.BLACK
                alpha = 96
                strokeWidth = 1f
            }
            // Edges too: the last row and column are aimable, and a cell whose
            // far side is the screen edge reads as open-ended without them.
            for (i in 0..GRID_COLS) {
                val x = (i * cw).coerceIn(0.5f, w - 0.5f)
                canvas.drawLine(x, 0f, x, h.toFloat(), halo)
                canvas.drawLine(x, 0f, x, h.toFloat(), hair)
            }
            for (i in 0..GRID_ROWS) {
                val y = (i * ch).coerceIn(0.5f, h - 0.5f)
                canvas.drawLine(0f, y, w.toFloat(), y, halo)
                canvas.drawLine(0f, y, w.toFloat(), y, hair)
            }
            val textSize = (unit * 0.20f).coerceIn(9f, 24f)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = textSize
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.BLACK
                alpha = 150
            }
            val pill = android.graphics.RectF()
            fun mark(label: String, cx: Float, baseline: Float) {
                val half = text.measureText(label) / 2f
                pill.set(cx - half - textSize * 0.25f, baseline - textSize * 1.02f, cx + half + textSize * 0.25f, baseline + textSize * 0.30f)
                canvas.drawRoundRect(pill, textSize * 0.3f, textSize * 0.3f, pillPaint)
                text.style = Paint.Style.FILL
                text.color = Color.WHITE
                text.alpha = 235
                canvas.drawText(label, cx, baseline, text)
            }
            // Rulers on the top and left edges only, never inside a cell.
            // A label at a cell's centre sat on whatever that cell held, and
            // on WeChat Moments the 「··」 button is exactly at column H's
            // centre — measured 2026-10-20, the H7 label covered it entirely,
            // so the one target of the task was invisible in the image the
            // brain aimed from. Not the right or bottom edge either: that is
            // where 「··」 and the send button live.
            val top = textSize * 1.25f
            for (col in 0 until GRID_COLS) {
                val l = ('A' + col).toString()
                mark(if (zoom) l.lowercase() else l, (col + 0.5f) * cw, top)
            }
            for (row in 0 until GRID_ROWS) {
                val l = (row + 1).toString()
                val half = text.measureText(l) / 2f
                mark(l, half + textSize * 0.35f, (row + 0.5f) * ch + textSize * 0.35f)
            }
        }.onFailure { Log.w("Body", "aim grid: could not draw: ${it.message}") }
        return out
    }

    fun swipeNorm(
        nx1: Int, ny1: Int, nx2: Int, ny2: Int,
        durationMs: Long, fling: Boolean, screens: Int? = null,
        // H6：交接步的滑动同样要把标志带给 swipe，否则前台校验会拦。
        handover: Boolean = false,
    ): JSONObject {
        // 第九批·坐标契约：起终两点同过一道门（contractSize 已含 requireNorm）；
        // 换算用校验返回的那份现读尺寸。像素层 swipe() 本身没有落屏检查，
        // 1000 这类边值以前会直接发出去，本批起在归一化层就拒。
        val size = contractSize(nx1 to ny1, nx2 to ny2)
        // Not a sample, but it moves everything on screen: without it the
        // offline pairing would read a post-scroll retry as a correction of a
        // pre-scroll miss, at a point that no longer means the same thing.
        // L11：这里记原值；钳制只在 swipe/swipeScreens 里做一次，那里会把实际
        // 用的时长与原值一起返回给调用方。
        GroundingLog.noteSwipe(nx1, ny1, nx2, ny2, durationMs, actor = "brain")
        val px1 = nx1.toPixel(size.x); val py1 = ny1.toPixel(size.y)
        val px2 = nx2.toPixel(size.x); val py2 = ny2.toPixel(size.y)
        if (screens != null) return swipeScreens(px1, py1, px2, py2, durationMs, screens, handover)
        return swipe(px1, py1, px2, py2, durationMs, fling, handover = handover)
    }

    /**
     * N deterministic swipes of one finger-travel each, stopping early when the
     * list stops moving.
     *
     * This exists because a fling is the wrong tool for an agent and the right
     * tool for a thumb. A fling's landing distance is decided by the system's
     * velocity integration, so the one thing the brain needs afterwards — "how
     * far did I move?" — is exactly what it cannot have. Measured 2026-10-20:
     * 21 flings hunting one old post, no position ever established.
     *
     * One full-travel swipe, by contrast, moves the list about as far as the
     * finger did, every time. N of them move it N times that far, which makes
     * distance something the brain can COUNT: "three screens down from the top"
     * is a place, and a place can be returned to. The early stop is the second
     * half of it — a list that has nowhere left to go says so instead of
     * swallowing the gesture.
     */
    private fun swipeScreens(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, screens: Int, handover: Boolean = false): JSONObject {
        // L11：时长在这里钳制一次（每段 swipe 内部还会按同一区间钳制，结果一致），
        // 实际用的时长与原值一起返回，不静默。
        val dur = durationMs.coerceIn(SWIPE_DURATION_MIN_MS, SWIPE_DURATION_MAX_MS)
        val n = screens.coerceIn(1, MAX_SCREENS)
        var moved = 0
        var stoppedAtEnd = false
        for (i in 0 until n) {
            val before = screenFingerprint()
            val beforePx = if (before.optBoolean("blind")) pixelSignature() else null
            swipe(x1, y1, x2, y2, durationMs, fling = false, handover = handover)
            try { Thread.sleep(SCREEN_SETTLE_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            val after = screenFingerprint()
            var changed = before.optString("pkg") != after.optString("pkg") ||
                before.optInt("sig") != after.optInt("sig")
            if (!changed && beforePx != null) {
                pixelSignature()?.let { if (pixelsMoved(beforePx, it)) changed = true }
            }
            if (!changed) { stoppedAtEnd = true; break }
            moved++
        }
        val out = JSONObject()
            .put("from", "$x1,$y1").put("to", "$x2,$y2")
            .put("duration_ms", dur).put("fling", false)
            .put("screens_moved", moved)
            .put("requested", n)
            .apply {
                if (dur != durationMs) {
                    put("requested_duration_ms", durationMs)
                    put("duration_clamped", true)
                }
            }
        if (stoppedAtEnd) {
            out.put(
                "list_end",
                "true — the screen stopped moving after $moved of $n swipes, so this list is at " +
                    "its end in that direction. More swipes the same way will do nothing.",
            )
        } else {
            out.put(
                "position_hint",
                "You moved $moved screen${if (moved == 1) "" else "s"} at one full finger-travel " +
                    "each. Count from where you started: that is how you get back, and how you " +
                    "know you have passed something.",
            )
        }
        return out
    }

    // ---- stuck-loop guard (per-location counting) --------------------------
    //
    // A model that cannot tell whether a tap worked re-taps the same spot with
    // slightly different coordinates forever. Counting *consecutive* taps or
    // requiring "screen didn't change" both fail in the field: apps emit
    // content events constantly, and the real dead-loop interleaves menu
    // opens, wrong pages and backs so the screen is always changing. Only
    // PER-LOCATION counting catches it: every gesture within
    // [LOOP_RADIUS_PX] of a remembered spot bumps that spot's counter, no
    // matter what happens in between. Within [LOOP_WINDOW_MS], the 4th attempt
    // on the same spot is refused with an error telling the brain to stop,
    // re-observe, and ask the user for help. Swipes are exempt — scrolling a
    // long list legitimately repeats the same gesture.

    private val loopLock = Any()
    private val loopSpots = HashMap<String, Int>()   // "x,y" bucket -> count
    private var lastLoopSweep = 0L

    /** Consecutive same-direction flings — see [guardFlingStreak]. */
    private var flingStreak = 0
    private var flingStreakAt = 0L
    private var flingStreakDown = false

    /** Set by [dumpUiTree]; read by [lastPkg]. Written and read off-thread. */
    @Volatile private var lastDumpPkg: String? = null

    /**
     * Packages we have actually read a usable element list from, and when.
     *
     * The one fact the device owns that the brain cannot work out for itself:
     * whether *this* app has ever been readable here. WeChat is the app that
     * forced it — its tree is perfectly obtainable, but the accessibility
     * connection sometimes goes stale and the same screen that dumped fine ten
     * minutes ago starts handing back nothing at all. No tool call repairs
     * that; a human toggling Andee off and on in 设置 → 无障碍 does, in about
     * three seconds. The brain cannot ask for that unless it knows the app was
     * supposed to work.
     *
     * Deliberately a record rather than a rule. Nothing here decides anything —
     * [reviveNote] turns it into one sentence of evidence and the brain judges,
     * which is why it is not keyed to `com.tencent.mm`: the same signature from
     * any app means the same thing, and hardcoding the package would have us
     * silent on the second app that does it.
     *
     * Unbounded, and that is fine: one `String`/`Long` per package a human has
     * opened on this tablet, which is tens, not thousands. Never persisted —
     * after a reboot the service is fresh, so old evidence would only argue for
     * a restart that already happened.
     */
    private val goodDumps = ConcurrentHashMap<String, Long>()

    /**
     * @param onRefuse run while still holding [loopLock], just before the
     *   refusal is thrown. The refusal is the strongest mis-tap label this
     *   system produces — the guard already knows a bucket has been hammered,
     *   and its scope (norm taps only, e-taps exempt) is exactly the set
     *   [GroundingLog] collects — so it must be written down rather than left
     *   to ride out on the exception.
     */
    private fun guardRepetition(x: Int, y: Int, onRefuse: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        synchronized(loopLock) {
            if (now - lastLoopSweep > LOOP_WINDOW_MS) {
                loopSpots.clear()
                lastLoopSweep = now
            }
            val key = "${x / LOOP_RADIUS_PX},${y / LOOP_RADIUS_PX}"
            val n = (loopSpots[key] ?: 0) + 1
            loopSpots[key] = n
            if (n > LOOP_MAX_TRIES) {
                onRefuse()
                throw IllegalStateException(LOOP_HELP)
            }
        }
    }

    /**
     * Package behind the last dump — what the brain was actually looking at.
     * Preferred over `JarvisAccessibilityService.foregroundPackage()`, which
     * can read `com.jarvis.app` whenever one of our own activities is up.
     */
    private fun lastPkg(): String? = lastDumpPkg

    /**
     * S2: 前台包——本文件内的等价取法。HEAD 的
     * JarvisAccessibilityService.foregroundPackage() 仍是旧版（直接取
     * rootInActiveWindow 的包名，会把 Jarvis 自身 / SystemUI / 输入法当成前台，
     * 见审计 S9），所以这里不调它，自己扫窗口：优先取 isActive && isFocused 的
     * TYPE_APPLICATION 窗口，退到任一 active 应用窗口，最后才看 active root。
     * 返回原始包名（含自身/SystemUI，由调用方判定）；取不到返回 null。
     */
    private fun foregroundPkg(): String? {
        val windows = runCatching { service.windows }.getOrNull()
        if (windows != null) {
            try {
                for (onlyFocused in listOf(true, false)) {
                    for (w in windows) {
                        val type = runCatching { w.type }.getOrNull() ?: continue
                        if (type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                        if (!runCatching { w.isActive }.getOrDefault(false)) continue
                        if (onlyFocused && !runCatching { w.isFocused }.getOrDefault(false)) continue
                        val root = runCatching { rootOf(w) }.getOrNull() ?: continue
                        val pkg = runCatching { root.packageName?.toString() }.getOrNull().orEmpty()
                        runCatching { root.recycle() }
                        if (pkg.isNotEmpty()) return pkg
                    }
                }
            } finally {
                windows.forEach { runCatching { it.recycle() } }
            }
        }
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        return try {
            root.packageName?.toString().orEmpty().takeIf { it.isNotEmpty() }
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * S2: 所有手势/输入入口的统一前台校验。目标包默认取 [lastDumpPkg]（最近一次
     * dump 的包）；还没 dump 过（null）时只校验“前台是一个真实应用”，不比对包名，
     * 以免冷启动的坐标流（autoglm-phone 只出坐标）第一步就被拦住。自身 /
     * 取不到前台时一律拒绝，不发手势。
     *
     * H6: 只在目标明确且前台确实异常时拦——交接步骤不要拦。调用方明确声明本次是
     * 交接步骤（[handover]，如打开分享面板/系统选择器/浏览器外链/权限弹窗后的
     * 下一步，前台包自然会变），或前台本身就是系统面（[HANDOVER_SYSTEM_PKGS]，
     * 分享面板、选择器、权限弹窗、安装器都住在系统包里）时，包名不一致是预期的，
     * 不是手势会落错应用的证据，直接放行。门禁本身不删：非交接、非系统面、
     * 前台是另一个普通应用时，照旧拒绝。
     */
    private fun requireForeground(expectedPkg: String? = lastDumpPkg, handover: Boolean = false) {
        val fg = foregroundPkg()
        if (fg.isNullOrEmpty()) {
            throw IllegalStateException(
                "refused: cannot tell which app is in front (no foreground window) — " +
                    "open the target app first, then dump its screen"
            )
        }
        if (fg == service.packageName) {
            throw IllegalStateException(
                "refused: Jarvis itself is in front — switch to the target app first, " +
                    "then dump its screen; this gesture was NOT sent"
            )
        }
        if (handover || fg in HANDOVER_SYSTEM_PKGS) return
        if (!expectedPkg.isNullOrEmpty() && fg != expectedPkg) {
            throw IllegalStateException(
                "refused: the app in front is $fg, not $expectedPkg — the target app is " +
                    "not in front, so this gesture was NOT sent. Open $expectedPkg first " +
                    "and dump its screen, then retry"
            )
        }
    }

    // ---- element-index tapping ----------------------------------------------
    //
    // Coordinate math by the model is the top accuracy risk: models
    // mis-convert pixel bounds against screen size exactly on the small,
    // tightly-packed targets where a few percent of error taps the wrong row.
    // The proven design (generic_bridge / phone.sh): the tree itself carries
    // system-computed e-numbers, and the model only ever says "e7". Zero
    // model math, zero scaling errors — coordinates come from the same source
    // of truth (getBoundsInScreen) that the gesture path converts with.
    //
    // STALENESS: the e-number alone is not enough to identify an element
    // after the page shifts (images load in, content settles) — e28 can
    // become a different view between the dump the brain read and the tap.
    // So the index keeps each entry's identity triple (pkg, viewId, bounds)
    // and tapByEid RE-LOCATES live: find a node NOW matching that triple. S4:
    // 活节点找不到时直接失败，要求重新 dump 再点——旧版静默用陈旧像素中心点，
    // 页面滚动/切页后点错且不自知。S5: 每个条目还带 dump 代际 epoch，上一屏的
    // e 号在新 dump 后直接拒掉。Phone.sh never had this problem because
    // its step loop dumps-and-acts in one breath; we let the brain think
    // between the two, so the tap has to catch up.
    //
    // e-taps are exempt from the stuck-loop guard: re-tapping a real element
    // is legitimate (toggles, list rows), and the guard exists to catch
    // coordinate GUESSING, not acting on confirmed elements.

    private class EidEntry(
        val px: IntArray,        // remembered pixel center — fallback only
        val pkg: String,         // owning app, from the node's root at dump time
        val viewId: String?,     // resource id if any
        val bounds: String,      // "l,t,r,b" in pixels at dump time
        val epoch: Long,         // S5: 哪一次 dump 编的号；过期后 lookupEid 拒绝
        // 第十一批·定位回退：dump 时的显示文字（UiSelector.label / 紧凑表 label，
        // 与 stableId 里参与编号的那份文字同一份）。三重信息就存在这里——
        // resource_id（viewId）+ bounds + text 全在 EidEntry 里，不另造索引。
        // 空串或 "#" 开头的（只有内部 id、屏上无字）不参与文字回退。
        val label: String = "",
    )

    /**
     * 第十批·tapBlockers：重定位到的活节点。node 是 obtain() 出来的独立副本，
     * 调用方用完负责 recycle（walk 沿途的原节点照常回收，副本不受影响）。
     * 第十一批：via 记这一重定位是哪一重找到的——"eid"（resource_id 强匹配）、
     * "bounds"（dump 时记下的位置：bounds 精确或放宽后的位置匹配）、"text"
     * （dump 时记下的文字）。调用方把 via 写进结果，模型据此知道旧编号已过期。
     */
    private class LiveNode(val bounds: IntArray, val node: AccessibilityNodeInfo, val via: String = "eid")

    private val eidLock = Any()
    private val eidIndex = HashMap<Int, EidEntry>()
    // 稳定编号索引：紧凑表的 s<hex> 编号 → 同一个 EidEntry。tapByEid/longPressByEid
    // 同时认 e 数字和 s 编号，所以模型用稳定编号点、流里用稳定编号，都走同一条路。
    private val sidIndex = HashMap<String, EidEntry>()
    /** S5: dump 代际计数，每次 rebuildIndexAndList 加一；上一屏的 e 号随即作废。 */
    @Volatile private var eidEpoch = 0L

    /** Fresh dump in progress — see [tapByEid] for why taps must wait. */
    private val dumpLock = Object()

    fun tapByEid(eid: String, handover: Boolean = false): JSONObject {
        val entry = lookupEid(eid)
        // S2: 目标包就是该元素所属的包；前台不是它就不发手势。H6：交接步骤放行。
        requireForeground(entry.pkg, handover)
        // Re-locate live before touching anything: a match means the element
        // still exists where we found it; no match means either it scrolled
        // away or the pane changed — 第十一批·定位回退：先走三重回退链
        // （见 locateLive），三重都找不到才失败，不用陈旧像素兜底。
        val live = locateLive(entry, eid)
        try {
            // 第十批·tapBlockers：按活树算遮挡，点最大未遮挡子区域的中心；
            // 全被盖住时 resolveTapPoint 直接抛错，绝不强点。
            val point = resolveTapPoint(live.node, "'${eid}'")
            val x = point.x
            val y = point.y
            GroundingLog.noteTapEid(eid, x, y, screenW, screenH, hold = false, relocated = true, pkg = lastPkg())
            val result = tap(x, y, expectedPkg = entry.pkg, handover = handover)
                .put("eid", eid)
                .put("px", "$x,$y")
                .put("relocated", true)
                .put("via", live.via)
            if (live.via != "eid") result.put("fallback", true)
            point.divertedBy?.let { result.put("diverted_by", it) }
            return result
        } finally {
            live.node.recycle()
        }
    }

    fun longPressByEid(eid: String, durationMs: Long, handover: Boolean = false): JSONObject {
        val entry = lookupEid(eid)
        requireForeground(entry.pkg, handover)
        // 第十一批·定位回退：与 tapByEid 同一条 locateLive（三重回退链）。
        val live = locateLive(entry, eid, verb = "long-pressed")
        try {
            // 第十批·tapBlockers：与 tapByEid 同一道遮挡判定（长按点错地方一样糟）。
            val point = resolveTapPoint(live.node, "'${eid}'")
            val x = point.x
            val y = point.y
            GroundingLog.noteTapEid(eid, x, y, screenW, screenH, hold = true, relocated = true, pkg = lastPkg())
            val result = longPress(x, y, durationMs, expectedPkg = entry.pkg, handover = handover)
                .put("eid", eid)
                .put("px", "$x,$y")
                .put("relocated", true)
                .put("via", live.via)
            if (live.via != "eid") result.put("fallback", true)
            point.divertedBy?.let { result.put("diverted_by", it) }
            return result
        } finally {
            live.node.recycle()
        }
    }

    // ---- 第十批·tapBlockers（DroidRun _add_tap_blockers / get_element_coords 的 Jarvis 版） ----
    //
    // 问题：点一个 eid/sel 定位的元素时，旧代码永远点目标 bounds 的中心。
    // 若中心正上方盖着弹窗、悬浮按钮、键盘按键等可交互覆盖物，这一 tap
    // 实际落在覆盖物上——点错东西，比点不中更糟（可能误触删除/发送）。
    //
    // 做法（在 eid/sel 点按的活树重定位处算：tapByEid/longPressByEid/
    // tapSelector 调 resolveTapPoint；纯坐标点按 tapNorm 也算——先判模型要
    // 的点有没有被盖住，被盖住改道到同一元素最大未遮挡块的中心，全盖住报
    // TAP_BLOCKED；没盖住、树上没东西、算不清时原点照点，不替模型改点；
    // tapById 走节点自身的 performAction，不经过坐标，也不算）：
    //   1. 以目标节点为基准，在同一时刻的活树里找遮挡者：可见、已启用、
    //      可点或可长按、bounds 与目标重叠、且确实画在目标上面。
    //   2. “画在上面”的判定（drawingOrder/windowId 都现取自活节点，不存
    //      dump、不预计算——dump 时的树到点按时可能已变）：
    //      - 跨窗口：比 AccessibilityWindowInfo.layer（service.windows 现读），
    //        层高者在上；任一层读不到就不认（证据不足，不改动）。跨窗口
    //        的祖先链不在同一棵树里，绝不拿链的前缀关系比（链 [0] 在别
    //        的窗口里不是目标的祖先）。
    //      - 同窗口：drawingOrder 只在同父级内相对（Android 文档原话：
    //        "Drawing order is determined only within the node's parent"），
    //        跨分支直接比大小是错的；所以沿两条祖先链找到最低公共祖先，
    //        比分叉处那一级的 drawingOrder，大者在上；相等视为证据不足，不认。
    //      - 目标的祖先（链是目标链的真前缀）是包含关系不是遮挡，永不认；
    //        目标的子孙（目标链是其前缀）画在目标里面、盖住目标的一部分，认。
    //   3. 矩形减法：目标 bounds 减去全部遮挡者，取面积最大的剩余块，点其中心。
    //      原中心没被盖住时最大块仍含中心附近，落点基本就是中心，不折腾。
    //   4. 全被盖住：抛 IllegalStateException，文案以“目标被遮住”开头（TaskLoop
    //      映射成 TAP_BLOCKED，记一步失败、写进 lastError），绝不强点。
    //
    // 证据不足时一律退回旧行为（点中心），不拒点、不改点：
    //   - 遮挡 walk 被预算截断（SEARCH_BUDGET_MS，与重定位同级）、树太深、
    //     目标在 walk 里没再找到、窗口层读不到——都只说明“这次算不清”，
    //     不说明“被盖住了”。
    // 代价：每次 eid/sel 点按多一次带预算的全树 walk，与重定位那次同量级
    // （findLiveNode 本来就每点一走）；dump 不走这里，不影响读屏成本。

    /** resolveTapPoint 的返回：落点像素 + 被改道时盖住原中心的那块遮挡物。 */
    private data class TapPoint(val x: Int, val y: Int, val divertedBy: String?)

    /** 遮挡 walk 里记下的一个候选：bounds、名字、祖先链、所在窗口。 */
    private class Blocker(
        val rect: Rect,
        val label: String,
        val chain: List<Int>,
        val windowId: Int,
    )

    /** 遮挡 walk 的共享状态：目标链（找到目标时填）与是否被截断。 */
    private class OcclusionWalk(
        val targetRect: Rect,
        val targetWindowId: Int,
    ) {
        val blockers = ArrayList<Blocker>()
        var targetChain: List<Int>? = null
        var cut = false
    }

    /**
     * 对活树里的目标节点算一个能点下去的落点：最大未遮挡子区域的中心。
     * [ref] 只进报错文案（如 "'s3f9a2'"、"选择器匹配的元素「发送」"）。
     * 全被盖住抛 IllegalStateException（文案以“目标被遮住”开头）；
     * 算不清（walk 截断/目标已不在 walk 里）退回目标中心，不拒点。
     */
    private fun resolveTapPoint(target: AccessibilityNodeInfo, ref: String): TapPoint {
        val targetRect = Rect()
        runCatching { target.getBoundsInScreen(targetRect) }
        val cx = (targetRect.left + targetRect.right) / 2
        val cy = (targetRect.top + targetRect.bottom) / 2
        val center = TapPoint(cx, cy, null)
        if (targetRect.isEmpty) return center
        val targetWindowId = runCatching { target.windowId }.getOrNull() ?: -1
        // 第2批·遮挡收集抽成 blockersAbove（坐标点按复用同一道判定）。
        val blockers = blockersAbove(targetRect, targetWindowId) ?: return center
        if (blockers.isEmpty()) return center
        val screen = Rect(0, 0, screenW, screenH)
        val pieces = subtractRects(targetRect, blockers.map { it.rect })
            .mapNotNull { p -> if (p.intersect(screen)) Rect(p) else null }
            .filter { !it.isEmpty }
        if (pieces.isEmpty()) {
            val top = blockers.maxByOrNull { overlapArea(it.rect, targetRect) }
            val name = top?.label ?: "遮挡物"
            throw IllegalStateException(
                "目标被遮住，点不下去：${ref}被「${name}」完全遮住，点原位置只会点到遮挡物。" +
                    "请先把遮挡物处理掉（关弹窗、收起悬浮窗、滑开遮挡，或先点遮挡物本身），" +
                    "再重新读屏点目标，不要原样重发这一步。"
            )
        }
        val best = pieces.maxByOrNull { it.width().toLong() * it.height() } ?: return center
        val x = (best.left + best.right) / 2
        val y = (best.top + best.bottom) / 2
        if (x == cx && y == cy) return center
        val coveringCenter = blockers.firstOrNull { it.rect.contains(cx, cy) }
            ?: blockers.maxByOrNull { overlapArea(it.rect, targetRect) }
        return TapPoint(x, y, coveringCenter?.label)
    }

    /**
     * 第2批·遮挡收集（从 resolveTapPoint 抽出，供坐标点按复用）：对目标矩形做一
     * 次全窗口 walk，返回真正画在目标上面的遮挡者（跨窗口先比层，同窗口再比链，
     * 与原来 resolveTapPoint 里的过滤一字不差）。算不清（walk 截断、目标链没
     * 找到、窗口层读不到）时返回 null——调用方退回旧行为，不拒点、不改点。
     */
    private fun blockersAbove(targetRect: Rect, targetWindowId: Int): List<Blocker>? {
        val walk = OcclusionWalk(targetRect, targetWindowId)
        val budget = WalkBudget(SEARCH_BUDGET_MS)
        // 各窗口的层 + 各窗口的根：同一批 windows 对象一次取完、用完回收。
        val layers = HashMap<Int, Int>()
        val windows = runCatching { service.windows }.getOrNull()
        try {
            if (windows != null) {
                for (w in windows) {
                    val id = runCatching { w.id }.getOrNull() ?: continue
                    runCatching { w.layer }.getOrNull()?.let { layers[id] = it }
                }
                for (w in windows) {
                    if (budget.spent()) {
                        walk.cut = true
                        break
                    }
                    val root = runCatching { rootOf(w) }.getOrNull() ?: continue
                    val pkg = runCatching { root.packageName?.toString() }.getOrNull().orEmpty()
                    if (pkg == service.packageName) {
                        root.recycle()
                        continue
                    }
                    try {
                        walkOcclusion(root, emptyList(), walk, budget, 0)
                    } finally {
                        root.recycle()
                    }
                    if (walk.cut) break
                }
            }
            if ((windows == null || windows.isEmpty()) && !walk.cut) {
                // 窗口列表拿不到：退回只走活动窗口（同窗口遮挡仍能算）。
                val root = runCatching { activeRoot() }.getOrNull()
                if (root != null) {
                    try {
                        walkOcclusion(root, emptyList(), walk, budget, 0)
                    } finally {
                        root.recycle()
                    }
                }
            }
        } finally {
            windows?.forEach { runCatching { it.recycle() } }
        }
        // 算不清返回 null，调用方退回旧行为（resolveTapPoint 是点中心）。
        if (walk.cut) return null
        val targetChain = walk.targetChain ?: return null
        // 只留真正画在目标上面的遮挡者（跨窗口先比层，同窗口再比链）。
        return walk.blockers.filter { b ->
            if (b.windowId != targetWindowId) {
                val bLayer = layers[b.windowId] ?: return@filter false
                val tLayer = layers[targetWindowId] ?: return@filter false
                bLayer > tLayer
            } else if (isChainPrefix(b.chain, targetChain)) {
                // 祖先是包含关系，不是遮挡。
                false
            } else if (isChainPrefix(targetChain, b.chain)) {
                // 子孙画在目标里面、盖住目标的一部分。
                true
            } else {
                // 同窗口：沿两条链找到分叉处，比那一级的 drawingOrder；
                // 分不出来（链长不够/分叉处相等）视为证据不足，不认。
                val d = firstDivergence(b.chain, targetChain) ?: return@filter false
                b.chain[d] > targetChain[d]
            }
        }
    }

    /** 第2批·点上的命中：节点 + 它所在窗口的类型（输入法窗口单独处理）。 */
    private class PointHit(val node: AccessibilityNodeInfo, val windowType: Int)

    /**
     * 第2批·找点上的节点：全窗口里找包含 (px,py) 的最上层可见节点（即手势实际
     * 会落到的那个元素）。跨窗口按 layer 高者为准，同窗口按祖先链比较（子孙在
     * 上、分叉处比 drawingOrder，与遮挡收集同一套规则）；自家包的窗口跳过。
     * 找不到返回 null（没树、点在空白处）。返回的节点调用方负责回收。
     */
    private fun nodeAtPoint(px: Int, py: Int): PointHit? {
        var best: AccessibilityNodeInfo? = null
        var bestChain: List<Int>? = null
        var bestLayer = Int.MIN_VALUE
        var bestType = -1
        val budget = WalkBudget(SEARCH_BUDGET_MS)
        fun visit(node: AccessibilityNodeInfo, chain: List<Int>, layer: Int, type: Int, depth: Int) {
            if (budget.spent() || depth > OCCLUSION_MAX_DEPTH) return
            val r = Rect()
            runCatching { node.getBoundsInScreen(r) }
            val order = runCatching { node.drawingOrder }.getOrNull() ?: 0
            val myChain = chain + order
            if (!r.isEmpty && r.contains(px, py) && node.isVisibleToUser) {
                val known = bestChain
                val above = when {
                    known == null -> true
                    bestLayer < layer -> true
                    bestLayer > layer -> false
                    isChainPrefix(known, myChain) -> true
                    isChainPrefix(myChain, known) -> false
                    else -> {
                        val d = firstDivergence(myChain, known)
                        d != null && myChain[d] > known[d]
                    }
                }
                if (above) {
                    best?.recycle()
                    best = AccessibilityNodeInfo.obtain(node)
                    bestChain = myChain
                    bestLayer = layer
                    bestType = type
                }
            }
            // 子节点框可能溢出父节点框（悬浮元素），父节点框不住该点时仍要下行，
            // 与 walkOcclusion 同款做法；预算与深度封顶。
            for (i in 0 until node.childCount) {
                val child = childOf(node, i) ?: continue
                try {
                    visit(child, myChain, layer, type, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }
        val windows = runCatching { service.windows }.getOrNull()
        try {
            if (windows != null) {
                for (w in windows) {
                    val layer = runCatching { w.layer }.getOrNull() ?: continue
                    val type = runCatching { w.type }.getOrNull() ?: continue
                    val root = runCatching { rootOf(w) }.getOrNull() ?: continue
                    val pkg = runCatching { root.packageName?.toString() }.getOrNull().orEmpty()
                    if (pkg == service.packageName) {
                        root.recycle()
                        continue
                    }
                    try {
                        visit(root, emptyList(), layer, type, 0)
                    } finally {
                        root.recycle()
                    }
                    if (budget.spent()) break
                }
            } else {
                val root = runCatching { activeRoot() }.getOrNull()
                if (root != null) {
                    try {
                        visit(root, emptyList(), 0, android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION, 0)
                    } finally {
                        root.recycle()
                    }
                }
            }
        } finally {
            windows?.forEach { runCatching { it.recycle() } }
        }
        val node = best ?: return null
        return PointHit(node, bestType)
    }

    /**
     * 第2批·坐标点按过遮挡：点之前判断模型要的这个点有没有被盖住。返回实际要
     * 点的像素点（可能与原点相同）；被盖住时改道到同一元素最大未遮挡块的中
     * 心，全盖住抛错（文案以“目标被遮住”开头，TaskLoop 映射成 TAP_BLOCKED，
     * 模型去处理遮挡物）；树上没东西、算不清、点没被盖住时返回原点，不改点。
     * 点落在键盘上单独处理：键盘盖住的是下面的元素，改道没有意义，直接拒。
     * 目标太大（地图/网页整面，超过屏幕 1/4）时也不改道——改道会点错位置，
     * 直接拒，让模型先把遮挡物处理掉再重读屏点。
     */
    private fun divertCoveredPoint(px: Int, py: Int, ref: String): TapPoint {
        val hit = nodeAtPoint(px, py) ?: return TapPoint(px, py, null)
        val target = hit.node
        try {
            val targetRect = Rect()
            runCatching { target.getBoundsInScreen(targetRect) }
            if (targetRect.isEmpty) return TapPoint(px, py, null)
            if (hit.windowType == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                throw IllegalStateException(
                    "目标被遮住，点不下去：${ref}所在的点被键盘盖住了，请先收起键盘再重新读屏点目标，不要原样重发这一步。"
                )
            }
            val targetWindowId = runCatching { target.windowId }.getOrNull() ?: -1
            // 算不清（walk 截断/目标链没找到）时原点照点，不改点、不拒点。
            val blockers = blockersAbove(targetRect, targetWindowId) ?: return TapPoint(px, py, null)
            // 只有盖住模型要的这个点的遮挡者才算数。
            val covering = blockers.filter { it.rect.contains(px, py) }
            if (covering.isEmpty()) return TapPoint(px, py, null)
            if (targetRect.width().toLong() * targetRect.height() > screenW.toLong() * screenH / 4) {
                val name = covering.maxByOrNull { overlapArea(it.rect, targetRect) }?.label ?: "遮挡物"
                throw IllegalStateException(
                    "目标被遮住，点不下去：${ref}被「${name}」盖住了，目标太大、改道会点错位置，" +
                        "请先把遮挡物处理掉（关弹窗、收起键盘、滑开遮挡）再重新读屏点目标，不要原样重发这一步。"
                )
            }
            val screen = Rect(0, 0, screenW, screenH)
            val pieces = subtractRects(targetRect, blockers.map { it.rect })
                .mapNotNull { p -> if (p.intersect(screen)) Rect(p) else null }
                .filter { !it.isEmpty }
            if (pieces.isEmpty()) {
                val top = covering.maxByOrNull { overlapArea(it.rect, targetRect) }
                val name = top?.label ?: "遮挡物"
                throw IllegalStateException(
                    "目标被遮住，点不下去：${ref}被「${name}」完全遮住，点原位置只会点到遮挡物。" +
                        "请先把遮挡物处理掉（关弹窗、收起悬浮窗、滑开遮挡，或先点遮挡物本身），" +
                        "再重新读屏点目标，不要原样重发这一步。"
                )
            }
            val best = pieces.maxByOrNull { it.width().toLong() * it.height() } ?: return TapPoint(px, py, null)
            val x = (best.left + best.right) / 2
            val y = (best.top + best.bottom) / 2
            val by = covering.maxByOrNull { overlapArea(it.rect, targetRect) }?.label
            return TapPoint(x, y, by)
        } finally {
            target.recycle()
        }
    }

    /** 遮挡 walk：沿树下行，记下目标链与全部可能遮住目标的候选节点。 */
    private fun walkOcclusion(
        node: AccessibilityNodeInfo,
        chain: List<Int>,
        walk: OcclusionWalk,
        budget: WalkBudget,
        depth: Int,
    ) {
        if (walk.cut) return
        if (budget.spent() || depth > OCCLUSION_MAX_DEPTH) {
            walk.cut = true
            return
        }
        val r = Rect()
        runCatching { node.getBoundsInScreen(r) }
        val order = runCatching { node.drawingOrder }.getOrNull() ?: 0
        val myChain = chain + order
        val wid = runCatching { node.windowId }.getOrNull() ?: -1
        val isTarget = r == walk.targetRect &&
            (walk.targetWindowId == -1 || wid == walk.targetWindowId)
        if (isTarget) {
            // 同 bounds 的包装层可能多层：留最深（最具体）的那条链。
            val known = walk.targetChain
            if (known == null || myChain.size > known.size) walk.targetChain = myChain
        } else if (!r.isEmpty && Rect.intersects(r, walk.targetRect) &&
            node.isVisibleToUser && node.isEnabled &&
            (node.isClickable || node.isLongClickable)
        ) {
            val label = UiSelector.label(node).ifEmpty {
                node.className?.toString()?.substringAfterLast('.') ?: "未命名元素"
            }
            walk.blockers.add(Blocker(Rect(r), label, myChain, wid))
        }
        for (i in 0 until node.childCount) {
            if (walk.cut) break
            if (budget.spent()) {
                walk.cut = true
                break
            }
            val child = childOf(node, i) ?: continue
            try {
                walkOcclusion(child, myChain, walk, budget, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    /** [short] 是不是 [long] 的真前缀（祖先链关系）。 */
    private fun isChainPrefix(short: List<Int>, long: List<Int>): Boolean =
        short.size < long.size && long.subList(0, short.size) == short

    /** 两条链第一个分叉处的下标；一条是另一条的前缀时返回 null。 */
    private fun firstDivergence(a: List<Int>, b: List<Int>): Int? {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) if (a[i] != b[i]) return i
        return null
    }

    /** 两矩形重叠面积（像素²），算“谁盖得最多”与“盖住中心的是谁”用。 */
    private fun overlapArea(a: Rect, b: Rect): Long {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0 || h <= 0) return 0L
        return w.toLong() * h
    }

    /**
     * 矩形减法：base 减去全部 cutters，返回剩余的不重叠小块（DroidRun
     * find_uncovered_point 的同款思路，纯几何，不碰节点）。cutter 与某块
     * 只碰边（零面积重叠）时那块原样保留。
     */
    private fun subtractRects(base: Rect, cutters: List<Rect>): List<Rect> {
        var pieces = listOf(Rect(base))
        for (c in cutters) {
            if (pieces.isEmpty()) break
            val next = ArrayList<Rect>()
            for (p in pieces) {
                if (!Rect.intersects(p, c)) {
                    next.add(p)
                    continue
                }
                val ixL = maxOf(p.left, c.left)
                val ixT = maxOf(p.top, c.top)
                val ixR = minOf(p.right, c.right)
                val ixB = minOf(p.bottom, c.bottom)
                if (ixL >= ixR || ixT >= ixB) {
                    next.add(p)
                    continue
                }
                if (p.left < ixL) next.add(Rect(p.left, p.top, ixL, p.bottom))
                if (ixR < p.right) next.add(Rect(ixR, p.top, p.right, p.bottom))
                if (p.top < ixT) next.add(Rect(ixL, p.top, ixR, ixT))
                if (ixB < p.bottom) next.add(Rect(ixL, ixB, ixR, p.bottom))
            }
            pieces = next
        }
        return pieces.filter { it.width() > 0 && it.height() > 0 }
    }

    /**
     * 按多条件选择器找元素（TaskLoop 的 find 动作与流的 find 步用）：选择器里
     * 给的条件全 AND 起来，在当前活树里逐个比对。返回总命中数与前
     * [SELECTOR_MAX_MATCHES] 个匹配（稳定编号 | 类型 | 文字 | 中心坐标），
     * 编号算法与紧凑表同一套，模型拿到就能用 eid 点；命中 0 个也如实回
     * total=0，不抛异常。找到的编号会登记进 sidIndex（与紧凑表同一条
     * 纪律：下次 dump 就清掉），所以 find 回来的编号下一轮能直接点。
     */
    fun findBySelector(sel: JSONObject): JSONObject {
        val found = selectNodes(sel)
        val nodes = found.nodes
        val matches = JSONArray()
        val usedSids = HashSet<String>()
        try {
            for (n in nodes) {
                if (matches.length() >= SELECTOR_MAX_MATCHES) break
                val r = Rect()
                runCatching { n.getBoundsInScreen(r) }
                if (r.isEmpty) continue
                val cls = n.className?.toString().orEmpty()
                val label = UiSelector.label(n)
                val bounds = "${r.left},${r.top},${r.right},${r.bottom}"
                var sid = stableId(n.viewIdResourceName?.ifEmpty { null }, cls, label, bounds)
                if (sid in usedSids) {
                    var i = 2
                    while ("$sid-$i" in usedSids) i++
                    sid = "$sid-$i"
                }
                usedSids.add(sid)
                val cxPx = (r.left + r.right) / 2
                val cyPx = (r.top + r.bottom) / 2
                // S5/S6: 登记当代际 epoch，与 dump 同锁（dumpLock → eidLock），
                // 下次 dump 加代际后这批 sid 随即作废。
                synchronized(dumpLock) {
                    synchronized(eidLock) {
                        sidIndex[sid] = EidEntry(
                            px = intArrayOf(cxPx, cyPx),
                            pkg = lastPkg().orEmpty(),
                            viewId = n.viewIdResourceName?.ifEmpty { null },
                            bounds = bounds,
                            epoch = eidEpoch,
                            label = label,
                        )
                    }
                }
                matches.put(JSONObject().apply {
                    put("sid", sid)
                    put("type", compactType(cls, n.isClickable, n.isScrollable, n.isChecked, n.isFocused))
                    put("text", label)
                    put("cx", cxPx * NORM_MAX / screenW)
                    put("cy", cyPx * NORM_MAX / screenH)
                })
            }
        } finally {
            nodes.forEach { it.recycle() }
        }
        // M33: 预算耗尽 / 命中上限截断时如实标记 partial，total 只是已返回的个数。
        return JSONObject().put("total", nodes.size).put("matches", matches)
            .apply { if (found.partial) put("partial", true) }
    }

    /**
     * 按选择器点第 [index] 个匹配（TaskLoop 的 tap 加 sel 时用，流里是
     * tap_sel 步）。匹配的节点本身不可点时，沿父链爬到最近的可点祖先再点；
     * 没有可点祖先就直说。index 超出会直说（“只找到 N 个，第 M 个不存在”），
     * 不点别的元素。
     * 第十一批：全条件零命中且 index 为 0 时，若 sel 带文字条件，退到只按文字
     * 找唯一匹配（多个同文字报歧义不乱点）；命中了几个但 index 超出时不回退。
     */
    // H6：交接步（分享面板/外部链接/权限弹窗后在另一应用上点）把标志带进来，
    // 否则 tapSelector 里 requireForeground() 会以前台包不对把手势拦住。
    fun tapBySelector(sel: JSONObject, index: Int, handover: Boolean = false): JSONObject =
        tapSelector(sel, index, hold = false, durationMs = 0, handover = handover)

    fun longPressBySelector(sel: JSONObject, index: Int, durationMs: Long, handover: Boolean = false): JSONObject =
        tapSelector(sel, index, hold = true, durationMs = durationMs, handover = handover)

    private fun tapSelector(sel: JSONObject, index: Int, hold: Boolean, durationMs: Long, handover: Boolean = false): JSONObject {
        requireForeground(handover = handover)
        val found = selectNodes(sel)
        var nodes = found.nodes
        var viaTextFallback = false
        // M33: 预算耗尽时结果是截断的，按索引点可能点到“第 N 个”之外的元素——拒绝。
        if (found.partial) {
            nodes.forEach { it.recycle() }
            throw IllegalStateException(
                "selector search hit the walk budget — the match list is PARTIAL, so " +
                    "tapping by index could hit the wrong element. Narrow the selector " +
                    "or dump the screen and tap by eid instead"
            )
        }
        if (index < 0 || index >= nodes.size) {
            // 第十一批·定位回退（sel 侧）：全条件 AND 一个都没命中、且 index 为 0
            // （缺省）时，若选择器带了文字条件，退到“只按文字”再找一次——选择器
            // 里的 viewId/className 可能已过期，文字还在。只认唯一匹配，多个同
            // 文字报歧义不乱点；命中了几个、但 index 超出时不回退（模型要的是
            // 第 M 个，换一个点就是点错）；要第 N 个（N>0）时也不回退。
            if (nodes.isEmpty() && index == 0) {
                val fallbackNode = findSelectorTextFallback(sel)
                if (fallbackNode != null) {
                    nodes = listOf(fallbackNode)
                    viaTextFallback = true
                }
            }
            if (!viaTextFallback) {
                val total = nodes.size
                nodes.forEach { it.recycle() }
                throw IllegalArgumentException("只找到 $total 个，第 ${index + 1} 个不存在")
            }
        }
        val target = nodes[index]
        var cur: AccessibilityNodeInfo? = target
        var climbed = false
        while (cur != null && !cur.isClickable) {
            val p = cur.parent
            if (cur !== target) cur.recycle()
            cur = p
            climbed = true
        }
        try {
            if (cur == null) {
                throw IllegalStateException("找到的元素不可点，也没有可点的父节点")
            }
            val r = Rect()
            cur.getBoundsInScreen(r)
            val label = UiSelector.label(cur)
            val sid = stableId(
                cur.viewIdResourceName?.ifEmpty { null },
                cur.className?.toString().orEmpty(),
                label,
                "${r.left},${r.top},${r.right},${r.bottom}",
            )
            // 第十批·tapBlockers：与 tapByEid 同一道遮挡判定（点按前按活树算，
            // 全被盖住直接抛错；纯坐标点按不走这里）。
            val point = resolveTapPoint(cur, "选择器匹配的元素「${label.ifEmpty { sid }}」")
            val cx = point.x
            val cy = point.y
            GroundingLog.noteTapEid(sid, cx, cy, screenW, screenH, hold = hold, relocated = false, pkg = lastPkg())
            val result = if (hold) longPress(cx, cy, durationMs) else tap(cx, cy)
            point.divertedBy?.let { result.put("diverted_by", it) }
            // 第十一批：文字回退命中的记 via "text" + fallback=true（与 eid 回退同款
            // 词汇），TaskLoop 的 summarize 据此告诉模型旧选择器已过期、下轮重读屏。
            if (viaTextFallback) result.put("fallback", true)
            return result.put("sid", sid).put("via", if (viaTextFallback) "text" else "selector").put("climbed", climbed)
        } finally {
            nodes.forEach { it.recycle() }
            if (cur != null && cur !== target) cur.recycle()
        }
    }

    /**
     * sel 的文字回退：全条件 AND 零命中时，只按选择器里的文字条件（text /
     * textContains / description / descriptionContains，取第一个有的）再找一次。
     * 唯一匹配返回 obtain() 副本（归调用方）；多个匹配抛“多个同文字”不乱点；
     * 没有文字条件、零匹配或列表被预算截断时返回 null，调用方按原错误处理。
     */
    private fun findSelectorTextFallback(sel: JSONObject): AccessibilityNodeInfo? {
        val relaxed = JSONObject()
        var key: String? = null
        for (k in listOf("text", "textContains", "description", "descriptionContains")) {
            if (sel.has(k) && sel.optString(k).isNotEmpty()) {
                key = k
                relaxed.put(k, sel.optString(k))
                break
            }
        }
        if (key == null) return null
        val found = selectNodes(relaxed)
        val matches = found.nodes
        try {
            // 截断的列表不可信：唯一匹配也可能是截断后剩下的假唯一。
            if (found.partial) return null
            if (matches.isEmpty()) return null
            if (matches.size > 1) {
                throw IllegalStateException(
                    "选择器没命中，但按文字「${sel.optString(key)}」找到多个同文字的元素，" +
                        "分不清是哪一个——只找到多个匹配、不乱点。Call get_screen_element again " +
                        "and use an eid from the fresh list, do not guess coordinates"
                )
            }
            return AccessibilityNodeInfo.obtain(matches[0])
        } finally {
            matches.forEach { it.recycle() }
        }
    }

    /** 按选择器在活树里找节点：命中条件全 AND；返回命中的节点（调用方负责 recycle）。 */
    private class SelectResult(val nodes: List<AccessibilityNodeInfo>, val partial: Boolean)

    private fun selectNodes(sel: JSONObject): SelectResult {
        val root = activeRoot() ?: return SelectResult(emptyList(), false)
        val kept = ArrayList<AccessibilityNodeInfo>()
        val budget = WalkBudget(SEARCH_BUDGET_MS)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty() && !budget.spent() && kept.size < SELECTOR_MAX_MATCHES) {
            val n = stack.removeFirst()
            for (i in 0 until n.childCount) childOf(n, i)?.let { stack.add(it) }
            val r = Rect()
            runCatching { n.getBoundsInScreen(r) }
            if (!r.isEmpty && UiSelector.matches(n, sel)) kept.add(n) else n.recycle()
        }
        // M33: 预算耗尽（栈里还剩没走完的节点）即截断，调用方如实标记/拒点；
        // 只因命中满 50 个而停的不算截断——前 50 个仍是按文档序排好的有效匹配。
        val partial = budget.spent()
        while (stack.isNotEmpty()) stack.removeFirst().recycle()
        return SelectResult(kept, partial)
    }

    /** S6: 索引读路径与重建互斥——同一把 dumpLock，见 [rebuildIndexAndList]。 */
    private fun lookupEid(eid: String): EidEntry {
        synchronized(dumpLock) { return lookupEidLocked(eid) }
    }

    private fun lookupEidLocked(eid: String): EidEntry {
        // 稳定编号（紧凑表的 s<hex>）：同一元素跨轮不变，查 sidIndex。
        if (eid.startsWith("s")) {
            synchronized(eidLock) {
                val entry = sidIndex[eid]
                    ?: throw IllegalArgumentException(
                        "unknown element '$eid' — the stable id is not on the current screen. " +
                            "Use a stable id from the latest compact list, do not guess coordinates"
                    )
                checkEpoch(eid, entry)
                return entry
            }
        }
        val key = eid.removePrefix("e").toIntOrNull()
            ?: throw IllegalArgumentException("bad element id '$eid' — expected e<number> or a stable s<hex> id from the element list")
        synchronized(eidLock) {
            val entry = eidIndex[key]
                ?: throw IllegalArgumentException(
                    "unknown element '$eid' — the page has changed since your last get_screen_element. " +
                        "Call get_screen_element again and use an eid from the fresh list, do not guess coordinates"
                )
            checkEpoch(eid, entry)
            return entry
        }
    }

    /** S5: 上一屏的 e 号在新 dump 后不能再被用来点。 */
    private fun checkEpoch(eid: String, entry: EidEntry) {
        if (entry.epoch != eidEpoch) {
            throw IllegalArgumentException(
                "element '$eid' is from an older screen (元素表已过期，重新 dump) — " +
                    "the element list has been rebuilt since this id was issued. " +
                    "Call get_screen_element again and use an eid from the fresh list, " +
                    "do not guess coordinates"
            )
        }
    }

    /**
     * Try to find, RIGHT NOW, the node this entry described at dump time.
     * Matches on viewId when present (strongest), else on bounds-exact within
     * the entry's app, else null. Walking the whole tree per tap costs a few
     * ms on this hardware; correctness of "did I tap what I meant" is
     * worth far more. S4: 同 id 的候选必须同时满足两点才算找到——中心离 dump
     * 时中心 ≤150px，且 bounds 与旧 bounds 至少部分重合；否则返回 null，
     * 调用方直接失败，不用陈旧坐标兜底。
     *
     * 第十批·tapBlockers：返回活节点本身（LiveNode，obtain() 副本归调用方），
     * 不只返回 bounds——遮挡判定要读目标节点的 windowId，bounds 里没有。
     * 匹配逻辑与旧版一字不改，只多带回节点对象。
     */
    private fun findLiveNode(entry: EidEntry): LiveNode? {
        val root = runCatching { activeRoot() }.getOrNull() ?: return null
        try {
            if (root.packageName?.toString() != entry.pkg) return null
            val oldParts = entry.bounds.split(",").mapNotNull { it.toIntOrNull() }
            val oldRect = if (oldParts.size == 4) Rect(oldParts[0], oldParts[1], oldParts[2], oldParts[3]) else null
            val maxDistSq = LIVE_MAX_DIST_PX.toLong() * LIVE_MAX_DIST_PX
            // Strong path: resource id narrows it down, but WeChat reuses ids
            // across siblings (every bottom tab shares one id), so among the
            // matches pick the one CLOSEST to the remembered center — the
            // entry's own geometry, not list order, decides.
            if (!entry.viewId.isNullOrEmpty()) {
                val matches = runCatching {
                    root.findAccessibilityNodeInfosByViewId(entry.viewId)
                }.getOrNull().orEmpty()
                try {
                    var bestArr: IntArray? = null
                    var bestNode: AccessibilityNodeInfo? = null
                    var bestDist = Long.MAX_VALUE
                    for (m in matches) {
                        val r = Rect()
                        runCatching { m.getBoundsInScreen(r) }
                        if (r.width() > 0 && r.height() > 0) {
                            // 旧 bounds 完全不重合的候选不是同一处元素（已滚动/切页）。
                            if (oldRect != null && !Rect.intersects(r, oldRect)) continue
                            val cx = (r.left + r.right) / 2
                            val cy = (r.top + r.bottom) / 2
                            val dx = (cx - entry.px[0]).toLong()
                            val dy = (cy - entry.px[1]).toLong()
                            val dist = dx * dx + dy * dy
                            if (dist < bestDist) {
                                bestDist = dist
                                bestArr = intArrayOf(r.left, r.top, r.right, r.bottom)
                                bestNode = m
                            }
                        }
                    }
                    // 最近的候选离得太远（>150px）也视为未找到。
                    if (bestDist > maxDistSq) return null
                    val node = bestNode ?: return null
                    // obtain() 在 finally 回收 matches 之前做：副本归调用方。
                    return LiveNode(bestArr ?: return null, AccessibilityNodeInfo.obtain(node))
                } finally {
                    matches.forEach { it.recycle() }
                }
            }
            // Weak path: walk for a node with the exact same bounds. Budgeted:
            // a miss falls through to the 第十一批 fallback chain (locateLive)
            // instead of failing here — this rung is the "coordinates" rung
            // (bounds 精确命中），via 记 "bounds" 以区别于 resource_id 强匹配。
            val hit = findNodeByBounds(root, entry.bounds, WalkBudget(SEARCH_BUDGET_MS))
                ?: return null
            val r = Rect()
            runCatching { hit.getBoundsInScreen(r) }
            return LiveNode(intArrayOf(r.left, r.top, r.right, r.bottom), hit, via = "bounds")
        } finally {
            root.recycle()
        }
    }

    // ---- 第十一批·定位三重信息 + 回退链（Minitap focus_element_if_needed 的 Jarvis 版） ----
    //
    // 三重信息存在 EidEntry 里（不另造索引、不改 dump 格式、不改 JSON 动作协议）：
    //   第 1 重 resource_id（viewId）+ 第 2 重 bounds + 第 3 重 text（label）。
    //   stableId 本来就是 MD5(viewId|类名|label|取整 bounds），三重早就在编号里，
    //   EidEntry 只是此前没把 label 单独留下来——本批只补了这一个字段。
    //
    // 回退顺序（只在 eid/sel 定位失败时触发，纯坐标动作 tapNorm/tapAim 不走）：
    //   1. eid 强匹配（findLiveNode 原有）：viewId + bounds 相交 + 中心 ≤150px，
    //      或 bounds 精确命中（via "eid" / "bounds"）。
    //   2. 位置放宽（findFallbackNode 的坐标 rung，via "bounds"）：
    //      (a) 同 viewId 但几何条件放宽（滚远了）：取离 dump 中心最近的；
    //          微信会复用 id（如底部 tab 共用一个 id），最近中心是唯一判据，
    //          这一点与强匹配的 S4 注释同款。(b) 与旧 bounds 重叠面积最大的节点，
    //          须带文字或可点（滤掉无字大容器——旧 bounds 很大时整屏容器都会相交）。
    //   3. 文字（via "text"）：label 与 dump 时完全相等（忽略大小写）、可见的节点。
    //      歧义处理：多于 1 个匹配时取离 dump 中心最近的；但最近两个离 dump 中心的
    //      距离差 ≤40px 时视为分不清——不乱点，报“多个同文字”失败，让模型重读屏
    //      用新编号点。label 为空或以 "#" 开头（只有内部 id、屏上无字）时跳过本重。
    //   三重都找不到才报失败（文案含“三重回退”，TaskLoop 映射 ELEMENT_NOT_FOUND）。
    //
    // 成本：回退只在主定位失败时多走一次带预算的全树 walk，与重定位同量级；
    // 顺利的点按（主定位命中）零新增成本。回退找到的节点照常过第十批遮挡判定
    // （调用方 tapByEid/longPressByEid 里 resolveTapPoint）与第九批坐标契约
    // （契约只管纯坐标动作，eid 路径本就不受其限）——回退不绕过任何一道守卫。
    // 回退成功不算失败，但结果里写 via + fallback=true，模型知道旧编号已过期，
    // 下轮只用最新紧凑表里的编号；回退失败记一步失败（复用现有失败计数与升级）。

    /** 回退结果：node 为 null 时 ambiguousText 区分“没找到”与“同文字太多分不清”。 */
    private class FallbackOutcome(val node: LiveNode?, val ambiguousText: Boolean)

    /**
     * eid 定位的统一入口：先主定位（findLiveNode），失败再走三重回退链。
     * 找不到时抛错（调用方 tapByEid/longPressByEid 共用，长按同款）。
     */
    private fun locateLive(entry: EidEntry, eid: String, verb: String = "tapped"): LiveNode {
        findLiveNode(entry)?.let { return it }
        val fb = findFallbackNode(entry)
        if (fb.ambiguousText) {
            throw IllegalStateException(
                "找到多个同文字「${entry.label}」的元素，分不清是哪一个——只找到多个匹配、" +
                    "不乱点。Call get_screen_element again and use an eid from the fresh list, " +
                    "do not guess coordinates"
            )
        }
        fb.node?.let { return it }
        throw IllegalStateException(
            "element '$eid' is no longer on screen where the last dump put it " +
                "(scrolled away or page changed) — 三重回退（编号→位置→文字）都没找到它，" +
                "the remembered position is NOT ${verb}. Call get_screen_element again " +
                "and use an eid from the fresh list"
        )
    }

    /**
     * 三重回退链的第 2、3 重：一次 walk 同时收三类候选（同 viewId / 与旧 bounds
     * 重叠 / 同文字），按“放宽的 resource_id → 位置 → 文字”顺序取用。
     * 预算截断（walk 被 SEARCH_BUDGET_MS 截断）时算不清、不猜，直接报没找到。
     */
    private fun findFallbackNode(entry: EidEntry): FallbackOutcome {
        val root = runCatching { activeRoot() }.getOrNull() ?: return FallbackOutcome(null, false)
        try {
            if (root.packageName?.toString() != entry.pkg) return FallbackOutcome(null, false)
            val oldParts = entry.bounds.split(",").mapNotNull { it.toIntOrNull() }
            val oldRect = if (oldParts.size == 4) Rect(oldParts[0], oldParts[1], oldParts[2], oldParts[3]) else null
            val search = FallbackSearch(entry, oldRect)
            walkFallback(root, search, WalkBudget(SEARCH_BUDGET_MS), 0)
            if (search.cut) {
                search.recycleAll()
                return FallbackOutcome(null, false)
            }
            val idNode = search.bestIdNode
            if (idNode != null) {
                search.bestOverlapNode?.recycle()
                search.bestTextNode?.recycle()
                return FallbackOutcome(LiveNode(search.bestIdBounds, idNode, via = "bounds"), false)
            }
            val overlapNode = search.bestOverlapNode
            if (overlapNode != null) {
                search.bestTextNode?.recycle()
                return FallbackOutcome(LiveNode(search.bestOverlapBounds, overlapNode, via = "bounds"), false)
            }
            val textNode = search.bestTextNode
            if (textNode != null) {
                if (search.secondTextDist - search.bestTextDist <= FALLBACK_TEXT_TIE_PX) {
                    textNode.recycle()
                    return FallbackOutcome(null, true)
                }
                return FallbackOutcome(LiveNode(search.bestTextBounds, textNode, via = "text"), false)
            }
            return FallbackOutcome(null, false)
        } finally {
            root.recycle()
        }
    }

    /** 回退 walk 的候选收集：每类只留当前最优者的 obtain() 副本，换优时回收旧的。 */
    private class FallbackSearch(val entry: EidEntry, val oldRect: Rect?) {
        var cut = false
        var bestIdNode: AccessibilityNodeInfo? = null
        var bestIdDist = Long.MAX_VALUE
        var bestIdBounds = intArrayOf(0, 0, 0, 0)
        var bestOverlapNode: AccessibilityNodeInfo? = null
        var bestOverlap = 0L
        var bestOverlapDist = Long.MAX_VALUE
        var bestOverlapBounds = intArrayOf(0, 0, 0, 0)
        var bestTextNode: AccessibilityNodeInfo? = null
        var bestTextDist = Double.MAX_VALUE
        var bestTextBounds = intArrayOf(0, 0, 0, 0)
        var secondTextDist = Double.MAX_VALUE
        // 文字回退只认屏上印的字：空 label 或 "#内部 id" 不参与。
        val useText = entry.label.isNotEmpty() && !entry.label.startsWith("#")

        fun distSq(cx: Int, cy: Int): Long {
            val dx = (cx - entry.px[0]).toLong()
            val dy = (cy - entry.px[1]).toLong()
            return dx * dx + dy * dy
        }

        fun recycleAll() {
            bestIdNode?.recycle()
            bestOverlapNode?.recycle()
            bestTextNode?.recycle()
            bestIdNode = null
            bestOverlapNode = null
            bestTextNode = null
        }
    }

    /** 回退 walk：沿树下行，三类候选各记最优（同 viewId 取最近中心、重叠取最大面积、同文字取最近两个以便判歧义）。 */
    private fun walkFallback(
        node: AccessibilityNodeInfo,
        search: FallbackSearch,
        budget: WalkBudget,
        depth: Int,
    ) {
        if (search.cut) return
        if (budget.spent() || depth > OCCLUSION_MAX_DEPTH) {
            search.cut = true
            return
        }
        val r = Rect()
        runCatching { node.getBoundsInScreen(r) }
        if (r.width() > 0 && r.height() > 0) {
            val cx = (r.left + r.right) / 2
            val cy = (r.top + r.bottom) / 2
            val distSq = search.distSq(cx, cy)
            val label = UiSelector.label(node)
            // 第 2 重 (a)：同 viewId，几何放宽——离 dump 中心最近的。
            if (!search.entry.viewId.isNullOrEmpty() &&
                node.viewIdResourceName == search.entry.viewId &&
                distSq < search.bestIdDist
            ) {
                search.bestIdNode?.recycle()
                search.bestIdNode = AccessibilityNodeInfo.obtain(node)
                search.bestIdDist = distSq
                search.bestIdBounds = intArrayOf(r.left, r.top, r.right, r.bottom)
            }
            // 第 2 重 (b)：与旧 bounds 重叠面积最大；须带文字或可点，滤无字大容器。
            val old = search.oldRect
            if (old != null && Rect.intersects(r, old) &&
                (label.isNotEmpty() || node.isClickable || node.isLongClickable)
            ) {
                val overlap = overlapArea(r, old)
                if (overlap > 0 && (overlap > search.bestOverlap ||
                        (overlap == search.bestOverlap && distSq < search.bestOverlapDist))
                ) {
                    search.bestOverlapNode?.recycle()
                    search.bestOverlapNode = AccessibilityNodeInfo.obtain(node)
                    search.bestOverlap = overlap
                    search.bestOverlapDist = distSq
                    search.bestOverlapBounds = intArrayOf(r.left, r.top, r.right, r.bottom)
                }
            }
            // 第 3 重：文字完全相等（忽略大小写）且可见；留最近两个，判歧义用。
            if (search.useText && node.isVisibleToUser &&
                label.equals(search.entry.label, ignoreCase = true)
            ) {
                val d = kotlin.math.sqrt(distSq.toDouble())
                if (d < search.bestTextDist) {
                    search.secondTextDist = search.bestTextDist
                    search.bestTextNode?.recycle()
                    search.bestTextNode = AccessibilityNodeInfo.obtain(node)
                    search.bestTextDist = d
                    search.bestTextBounds = intArrayOf(r.left, r.top, r.right, r.bottom)
                } else if (d < search.secondTextDist) {
                    search.secondTextDist = d
                }
            }
        }
        for (i in 0 until node.childCount) {
            if (search.cut) break
            if (budget.spent()) {
                search.cut = true
                break
            }
            val child = runCatching { childOf(node, i) }.getOrNull() ?: continue
            try {
                walkFallback(child, search, budget, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    private fun findNodeByBounds(node: AccessibilityNodeInfo, bounds: String, budget: WalkBudget): AccessibilityNodeInfo? {
        val r = Rect()
        runCatching { node.getBoundsInScreen(r) }
        if (r.width() > 0 && r.height() > 0 && "${r.left},${r.top},${r.right},${r.bottom}" == bounds) {
            // 第十批·tapBlockers：返回 obtain() 副本归调用方——walk 的 finally
            // 会回收沿途节点（包括命中节点本身），副本不受影响。
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            if (budget.spent()) return null
            val child = runCatching { childOf(node, i) }.getOrNull() ?: continue
            try {
                val hit = findNodeByBounds(child, bounds, budget)
                if (hit != null) return hit
            } finally {
                child.recycle()
            }
        }
        return null
    }

    fun tap(x: Int, y: Int, snapFrame: FloatArray? = null, expectedPkg: String? = null, handover: Boolean = false): JSONObject {
        // S2: 像素坐标必须落在屏幕内；前台必须是指定的目标包（默认最近一次 dump 的包）。
        if (x !in 0 until screenW || y !in 0 until screenH) {
            throw IllegalArgumentException("tap ($x,$y) is outside the screen ${screenW}x$screenH — refusing to send")
        }
        requireForeground(expectedPkg ?: lastDumpPkg, handover)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        // 150ms, not the classic 100ms: WeChat's Moments like/comment buttons
        // (and other touch-filtering surfaces) ignore injections shorter than
        // ~100ms — a 100ms stroke was landing as a no-op while the exact same
        // coordinates at 150ms pop the bubble every time (measured: `input tap`
        // at ~10ms fails, dispatchGesture at 100ms fails, a swipe held at one
        // point for 150ms succeeds). 150ms is still well below any
        // long-press threshold (400ms+), so nothing that was a tap becomes
        // a long-press.
        val stroke = GestureDescription.StrokeDescription(path, 0L, 150L)
        val before = screenFingerprint()
        val beforePx = if (before.optBoolean("blind")) snapFrame ?: pixelSignature() else null
        // Andee 对齐：上一圈还亮着时先藏起来——注入落点正好是圈坐着的位置。
        marker?.hideNow()
        // M15: 落点在球下时球先让开（Andee wireScreen 的 gestureGuard 同款思路，
        // 本工程由 OrbController.dodgeIfNeeded 落地，按 ownArea 的球框判定）。
        val dodged = com.jarvis.app.OrbController.dodgeIfNeeded(x, y)
        val result = try {
            guardedGesture(android.graphics.Rect(x - 4, y - 4, x + 4, y + 4)) {
                runGesture(GestureDescription.Builder().addStroke(stroke).build(), 3000L)
            }
        } finally {
            if (dodged) com.jarvis.app.OrbController.restoreDodge()
        }
        // L11: 手势没完成（被取消）也不抛异常——completed=false 的结果连同效果快照
        // 一起返回，调用方按 completed 判断成败（Andee 同款：tap 从不因 completed 抛异常）。
        marker?.mark(x, y, TapMarkerUi.Kind.TAP)
        result.put("x", x).put("y", y)
        attachEffectSnapshot(result, before, beforePx)
        putPopups(result)
        return result
    }

    /**
     * Cheap before/after of the screen around a gesture: pkg + window title +
     * a node-tree signature. The point is not what it says, it is that it
     * CHANGES (or doesn't) — a tap that did nothing leaves the fingerprint
     * identical, and the brain reading `screen_changed: false` knows to
     * re-aim instead of assuming success and building on sand.
     *
     * Collected here rather than left to the brain's next dump because the
     * transition window is exactly the moment a dump is least reliable: the
     * animation is still running, elements are mid-flight, and "dump again
     * immediately" reads a screen that does not exist yet. One fingerprint,
     * taken after a fixed beat, answers "did it land" without any of that.
     */
    private fun screenFingerprint(): JSONObject {
        val root = runCatching { activeRoot() }.getOrNull()
            // No active window at all — a full-screen video surface is the
            // usual cause (WeChat's 视频号 playback page answers null here,
            // measured 2026-10-20). Marked blind for the same reason a
            // withheld root is: an empty answer is identical before and after
            // any gesture, so the tree cannot say whether one landed and the
            // caller must fall back to [pixelSignature]. Without this flag the
            // fingerprint read as pkg="" / sig=0 / not blind, which skipped
            // the pixel comparison entirely and left `screen_changed` false
            // for every tap on that page — 50 of them in one task, each of
            // which had in fact changed the screen.
            ?: return JSONObject().put("pkg", "").put("sig", 0)
                .put("blind", true).put("no_root", true)
        return try {
            val f = FingerprintStats()
            val budget = WalkBudget(FINGERPRINT_BUDGET_MS)
            collectFingerprint(root, f, 0, budget)
            // Window COUNT is part of the fingerprint on purpose: WeChat's
            // Moments like/comment bar is a full-screen overlay window that
            // changes nothing in the active window's node tree — measured in
            // the field: the bar appeared, screen_changed said false, and the
            // only honest signal was the window list growing by one.
            // L15: 取到的窗口列表用完即回收。
            val winCount = runCatching {
                service.windows.let { wins ->
                    try { wins.size } finally { wins.forEach { runCatching { it.recycle() } } }
                }
            }.getOrDefault(0)
            JSONObject()
                .put("pkg", root.packageName?.toString().orEmpty())
                .put("sig", f.sig() * 31 + winCount)
                // Two cut fingerprints stop at different nodes, so their
                // signatures differ whether or not the screen did.
                .apply { if (budget.cut) put("cut", true) }
                // A withheld root (or a bare canvas) gives the same signature
                // before and after any tap, so the tree cannot say whether one
                // landed — see [pixelSignature].
                .apply { if (f.count <= BLIND_TREE_NODES) put("blind", true) }
        } finally {
            root.recycle()
        }
    }

    private class FingerprintStats {
        var count = 0
        var hash = 1
        fun visit(node: AccessibilityNodeInfo) {
            count++
            hash = hash * 31 + node.childCount
            node.viewIdResourceName?.let { hash = hash * 31 + it.hashCode() }
            // TEXT is part of the fingerprint: structure alone cannot see a
            // translation replacing every string on the page, a comment
            // loading in, or a counter ticking — same nodes, same ids, new
            // words. Measured in the field (2026-09-20): dismissing the
            // browser's translate bar changed nothing structural, and
            // screen_changed said false while the screen had visibly changed.
            node.text?.let { hash = hash * 31 + it.hashCode() }
            node.contentDescription?.let { hash = hash * 31 + it.hashCode() }
        }
        fun sig(): Int = count * 31 + hash
    }

    private fun collectFingerprint(node: AccessibilityNodeInfo, f: FingerprintStats, depth: Int, budget: WalkBudget) {
        if (depth > 18 || f.count > 900) return  // cap: fingerprint, not a dump
        f.visit(node)
        for (i in 0 until node.childCount) {
            if (budget.spent()) return
            val c = runCatching { childOf(node, i) }.getOrNull() ?: continue
            try {
                collectFingerprint(c, f, depth + 1, budget)
            } finally {
                c.recycle()
            }
        }
    }

    /**
     * Mean luma per cell of a [PX_COLS]×[PX_ROWS] grid over the live screen,
     * with the status bar and our own ball window set to NaN so a ticking
     * clock or a blinking ball cannot read as the tap landing. Null if the
     * capture failed — the caller then falls back to the tree's answer.
     */
    private fun pixelSignature(): FloatArray? {
        val mask = ownArea()
        return captureFrame { hw -> signatureOf(hw, mask) }
    }

    private fun signatureOf(hw: Bitmap, mask: Rect?): FloatArray {
        return run {
            val sw = PX_COLS * PX_SAMPLE
            val sh = PX_ROWS * PX_SAMPLE
            val scaled = Bitmap.createScaledBitmap(hw, sw, sh, true)
            val soft = if (scaled.config == Bitmap.Config.HARDWARE) {
                scaled.copy(Bitmap.Config.ARGB_8888, false).also { scaled.recycle() }
            } else scaled
            val px = IntArray(sw * sh)
            soft.getPixels(px, 0, sw, 0, 0, sw, sh)
            soft.recycle()
            val cellW = hw.width.toFloat() / PX_COLS
            val cellH = hw.height.toFloat() / PX_ROWS
            val statusBar = (hw.height * STATUS_BAR_FRACTION).toInt()
            FloatArray(PX_COLS * PX_ROWS) { i ->
                val cx = i % PX_COLS
                val cy = i / PX_COLS
                val cell = Rect(
                    (cx * cellW).toInt(), (cy * cellH).toInt(),
                    ((cx + 1) * cellW).toInt(), ((cy + 1) * cellH).toInt(),
                )
                if (cell.top < statusBar || (mask != null && Rect.intersects(mask, cell))) {
                    return@FloatArray Float.NaN
                }
                var sum = 0f
                for (yy in 0 until PX_SAMPLE) for (xx in 0 until PX_SAMPLE) {
                    val c = px[(cy * PX_SAMPLE + yy) * sw + cx * PX_SAMPLE + xx]
                    sum += 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                }
                sum / (PX_SAMPLE * PX_SAMPLE)
            }
        }
    }

    private fun pixelsMoved(a: FloatArray, b: FloatArray): Boolean {
        if (a.size != b.size) return true
        var moved = 0
        for (i in a.indices) {
            if (a[i].isNaN() || b[i].isNaN()) continue
            if (kotlin.math.abs(a[i] - b[i]) > PX_CELL_DELTA) moved++
        }
        return moved >= PX_MIN_CELLS
    }

    /** takeScreenshot refuses calls closer than ~333 ms apart; space them out instead of failing. */
    private val lastCaptureAt = AtomicLong(0L)

    private fun awaitCaptureGap() {
        val wait = lastCaptureAt.get() + CAPTURE_GAP_MS - SystemClock.uptimeMillis()
        if (wait > 0) runCatching { Thread.sleep(wait) }
    }

    private fun <T> captureFrame(transform: (Bitmap) -> T): T? {
        // Two attempts, because this call is allowed to fail transiently:
        // takeScreenshot is rate-limited by the system (roughly one per
        // [CAPTURE_GAP_MS]) and answers ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
        // when it judges the gap too small. [awaitCaptureGap] approximates that
        // allowance from captures issued here, and the system's own accounting
        // is stricter than the approximation — so a failure is not a bug, it is
        // a delay. One retry converts it into one. It mattered: measured
        // 2026-10-20, the after-frame of a tap was lost this way, which left
        // `screen_changed` false on a screen that had changed completely — and
        // the response then told the brain "the screen did NOT react".
        repeat(2) { attempt ->
            awaitCaptureGap()
            var errCode = 0
            val latch = CountDownLatch(1)
            var out: T? = null
            val executor = Executors.newSingleThreadExecutor()
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val hb = screenshot.hardwareBuffer
                            try {
                                Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)?.let { out = transform(it) }
                            } catch (t: Throwable) {
                                errCode = -2
                                Log.w("Body", "captureFrame: ${t.message}")
                            } finally {
                                hb.close()
                                latch.countDown()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            errCode = errorCode
                            latch.countDown()
                        }
                    },
                )
                if (!latch.await(3, TimeUnit.SECONDS) && errCode == 0) errCode = -3
            } finally {
                lastCaptureAt.set(SystemClock.uptimeMillis())
                executor.shutdown()
            }
            if (out != null) return out
            // Silent until now, which is why a lost after-frame was invisible
            // in the field: no log, no field in the response, just `false`.
            Log.w("Body", "captureFrame: no frame, attempt ${attempt + 1}, code $errCode")
            if (attempt == 0) runCatching { Thread.sleep(CAPTURE_GAP_MS) }
        }
        return null
    }

    /**
     * The "did my tap land" half of the effect snapshot. Waits one beat for
     * the app to react (a transition that has not started yet reads as no
     * change), then fingerprints again and reports the delta:
     *   - `screen_changed` — the honest one-bit answer
     *   - `now_pkg` — where we landed, if we landed somewhere
     *   - on SUCCESS: `after_shot` — a full screenshot WITH the marker ring
     *     already drawn at this tap's position (the ring is deliberately
     *     never blanked — see TapMarkerUi). The brain sees exactly where its
     *     tap landed relative to everything on screen, which is how a hit
     *     confirms the aim AND how a near-miss ("ring is 40px left of the
     *     button") self-corrects.
     *   - on MISS: `miss_shot` — same idea, zoomed: the ring at the tapped
     *     spot, the neighbourhood magnified, aim the retry from measurement.
     *
     * Both carry `tap_mark: [x,y]` in 0-1000 so the ring's position is
     * machine-readable, not just visible.
     */
    private fun attachEffectSnapshot(result: JSONObject, before: JSONObject, beforePx: FloatArray? = null) {
        try {
            // 400ms caught the launcher still holding focus mid-launch (tap
            // camera → changed=true but now_pkg=launcher — measured on
            // device). 700ms lets the activity-change settle while staying
            // well under the brain's own next-step latency.
            Thread.sleep(700)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val after = screenFingerprint()
        var changed = before.optString("pkg") != after.optString("pkg") ||
            before.optInt("sig") != after.optInt("sig")
        // Was there ever a second opinion? "No change" is an answer only when
        // something compared the screen; a before-frame whose after-frame never
        // arrived is not. Measured 2026-10-20: on WeChat 视频号 the after-frame
        // was silently lost, `changed` stayed false, and the response told the
        // brain "the screen did NOT react" about a page it had just turned —
        // the single most expensive lie this tool can tell.
        var pixelTold = false
        if (!changed && beforePx != null) {
            // Measured 2026-10-08 on a withheld WeChat: every tap that opened a
            // page came back `false`, and the brain re-aimed at a screen that
            // had already moved on — most of what looked like bad aim.
            val afterPx = pixelSignature()
            if (afterPx != null) {
                pixelTold = true
                if (pixelsMoved(beforePx, afterPx)) changed = true
            }
        }
        val blindTree = before.optBoolean("blind") || after.optBoolean("blind")
        when {
            !changed && pixelTold -> result.put(
                "screen_changed_note",
                if (before.optBoolean("no_root") || after.optBoolean("no_root")) {
                    // Same mechanism, different cause: there is no window at
                    // all on a full-screen video page, not a withheld one.
                    "This screen hands accessibility no window at all — a full-screen video page " +
                        "is the usual reason — so screen_changed was judged from the pixels. A " +
                        "playing video or an animation also counts as a change, so confirm from " +
                        "the attached screenshot rather than trusting the flag on its own."
                } else {
                    "This app hides its contents from accessibility, so screen_changed was judged " +
                        "from the pixels. A playing video or animation also counts as a change — " +
                        "confirm from the attached screenshot."
                },
            )

            !changed && blindTree ->
                // Blind tree AND no second opinion: nothing on this device
                // observed the screen before or after. `false` is then an
                // absence of evidence, not a finding.
                result.put(
                    "screen_changed_note",
                    "Nothing on this device read the screen before or after this tap, so " +
                        "screen_changed:false here means UNKNOWN, not 'it missed'. Judge from the " +
                        "attached image: if it is not the screen you aimed from, the tap landed " +
                        "and you should carry on from what you see.",
                )
        }
        result.put("screen_changed", changed)
        if (before.optBoolean("cut") || after.optBoolean("cut")) {
            result.put(
                "screen_changed_note",
                "This app answers accessibility too slowly to compare the screen before and " +
                    "after, so screen_changed is a guess — judge whether the tap landed from the " +
                    "attached screenshot.",
            )
        }
        result.put("now_pkg", after.optString("pkg"))
        val tapX = result.optInt("x")
        val tapY = result.optInt("y")
        // JSONArray, element-wise: a bare IntArray goes through as one opaque
        // object and serialises to "[I@41d5a25" — a machine-readable field
        // that no machine can read.
        result.put("tap_mark", org.json.JSONArray().put(
            (tapX * 1000f / screenW).toInt()
        ).put(
            (tapY * 1000f / screenH).toInt()
        ))
        if (changed) {
            // Success still deserves the evidence: the ring-marked screen is
            // the cheapest possible confirmation, and it doubles as "this is
            // what I was aiming at" context for the next step.
            runCatching {
                if (tapX > 0 || tapY > 0) {
                    // The dispatcher's gesture-passthrough still has the
                    // marker hidden at this point (we run inside it) —
                    // reveal, then give the post a beat to land.
                    marker?.revealNow()
                    Thread.sleep(80)
                    result.put("after_shot", guardedShot { screenshot("tap_after") })
                }
            }
            // The NEW screen's element list, so the brain can chain its next
            // action (tap / type) WITHOUT a get_screen_element round trip it
            // would otherwise burn on "what did the screen become?". The
            // screenshot above already carries the pixels; this carries the
            // e-numbers. One tap response = full next-step context.
            runCatching {
                val tree = dumpOnce(false, WalkBudget(ATTACHED_TREE_MS))
                if (tree.optInt("nodes_useful", 0) > 0) {
                    rebuildIndexAndList(tree)
                    // S2: 点后切了应用时，目标包跟着新树走，否则下一次坐标点按
                    // 还拿旧包校验会被误拦。
                    tree.optString("pkg").takeIf { it.isNotEmpty() }?.let { lastDumpPkg = it }
                    val elements = tree.optString("elements")
                    if (elements.isNotEmpty()) {
                        result.put("elements", elements)
                            .put("elements_pkg", tree.optString("pkg"))
                            .put("elements_hint",
                                "This is the POST-TAP screen. Chain your next tap_screen_element / " +
                                "type_text directly from these e-numbers — do NOT re-dump first." +
                                if (tree.optBoolean("walk_cut")) {
                                    " The list is PARTIAL: this app answered too slowly to read it " +
                                        "all, so what is in the screenshot but not here was not reached."
                                } else "")
                    }
                }
            }
        } else {
            // Does the device actually know this tap missed? Only when the
            // fingerprint had something to compare (a real tree) or the pixel
            // comparison ran. A blind tree with no before-frame is the one case
            // where it does not — and there, "the screen did NOT react" reads
            // as a measurement that was never taken. Measured 2026-10-20 on a
            // 视频号 page: that sentence, repeated while the page was in fact
            // changing under every tap, sent the brain into re-aim → stuck-loop
            // guard → back out → re-enter, which is what the user watched.
            val told = !before.optBoolean("blind") || pixelTold
            result.put(
                if (told) "same_spot_hint" else "unknown_spot_hint",
                if (told) {
                    "the screen did NOT react to this tap. miss_shot shows where the ring " +
                        "landed — the tapped point, magnified with its surroundings. Its grid " +
                        "is lowercase (a1..h12) because it is a zoom, so a cell read off it is " +
                        "aimed with {\"cell\": \"d7\", \"part\": \"…\", \"on\": \"zoom\"}, which " +
                        "the device converts for you. Aim the retry from that image, not from " +
                        "the previous full screenshot."
                } else {
                    "I could not tell whether that tap landed. miss_shot shows where the ring " +
                        "landed, magnified — but treat it as a place, not as a verdict: this " +
                        "screen gave the accessibility layer nothing to compare and the " +
                        "pixel check did not run either. If the image is not the screen you " +
                        "aimed from, the tap DID work; carry on from what you see instead of " +
                        "aiming again, and never back out and re-enter the app to " +
                        "\"start over\" — that only returns you to this same page."
                },
            )
            runCatching {
                if (tapX > 0 || tapY > 0) {
                    // Region around the tap, in 0-1000 space: ~30% of the
                    // shorter axis each way, clamped by lookRegion itself.
                    // revealNow: same reason as the success path — the ring
                    // is the evidence, and passthrough has it hidden.
                    marker?.revealNow()
                    Thread.sleep(80)
                    val rx = (tapX * 1000f / screenW).toInt()
                    val ry = (tapY * 1000f / screenH).toInt()
                    val size = 300
                    val hUnits = (size * screenH.toFloat() / screenW).toInt().coerceAtLeast(1)
                    result.put("miss_shot", guardedShot { lookRegion(rx, ry, size, hUnits) })
                }
            }
        }
    }

    fun tapById(id: String, handover: Boolean = false): JSONObject {
        requireForeground(handover = handover)
        val root = service.rootInActiveWindow ?: throw IllegalStateException("no active window")
        try {
            val matches = root.findAccessibilityNodeInfosByViewId(id).orEmpty()
            try {
                if (matches.isEmpty()) throw IllegalArgumentException("no node with id: $id")
                // L18: 只认可见且可点的节点；M17：多个符合时不报歧义，取离旧中心
                // 最近的那个（微信复用 id 的处理，见 nearestToLastMark）。
                val usable = matches.filter { it.isVisibleToUser && it.isClickable }
                if (usable.isEmpty()) {
                    throw IllegalStateException(
                        "no visible clickable node with id: $id " +
                            "(found ${matches.size} node(s), none visible+clickable)"
                    )
                }
                val target = nearestToLastMark(usable)
                val performed = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return JSONObject().put("performed", performed).put("id", id)
                    .put("matches", matches.size).put("candidates", usable.size)
            } finally {
                matches.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * M17: 复用 id 的多个候选里，取离“旧中心”最近的那个。Andee 的 findLiveNode
     * 就是这么处理微信复用 id 的（离 dump 时记下的中心最近者即同一处元素）；
     * Andee 的 tapById 本身取第一个。tapById 没有 dump 时的条目，旧中心取上次
     * 落点标记 [TapMarkerUi.lastMarkPx]：同一个 id 反复点（如微信底部栏）时，
     * 离上次点过的位置最近的候选最可能是同一个控件。还没有任何落点时退回第
     * 一个，与 Andee 的 tapById 一致。
     */
    private fun nearestToLastMark(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo {
        val mark = marker?.lastMarkPx()
        if (mark == null || mark.size < 2) return nodes[0]
        val r = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestDist = Long.MAX_VALUE
        for (n in nodes) {
            runCatching { n.getBoundsInScreen(r) }
            if (r.isEmpty) continue
            val dx = ((r.left + r.right) / 2 - mark[0]).toLong()
            val dy = ((r.top + r.bottom) / 2 - mark[1]).toLong()
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                best = n
            }
        }
        return best ?: nodes[0]
    }

    /**
     * Unless [fling], the finger holds still at the end before lifting. A stroke
     * that lifts while moving hands the list its full release velocity, and a
     * 400 ms swipe over 40% of the screen coasted the WeChat feed several screens
     * past the post being aimed at (measured 2026-10-19). Held, the content moves
     * about as far as the finger did. The hold is a continued stroke rather than
     * a longer duration because release velocity is distance/duration — slowing
     * the stroke shrinks the coast but never removes it.
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, fling: Boolean, expectedPkg: String? = null, handover: Boolean = false): JSONObject {
        requireForeground(expectedPkg ?: lastDumpPkg, handover)
        // L11：时长在这里钳制一次，实际用的时长与原值一起返回，不静默。
        val dur = durationMs.coerceIn(SWIPE_DURATION_MIN_MS, SWIPE_DURATION_MAX_MS)
        if (fling) guardFlingStreak(y2 > y1) else synchronized(loopLock) { flingStreak = 0 }
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        // A fling is the one gesture whose landing the brain cannot predict, so
        // it is the one that has to report where it ended up. Measured
        // 2026-10-20: hunting a post from 105 days ago, the brain flung the
        // WeChat timeline 21 times and never learned anything from any of them
        // — top, bottom, top again — because the reply said only "swiped".
        // Chained flings without a look are how a list becomes a maze.
        val before = if (fling) screenFingerprint() else null
        val beforePx = if (fling && before?.optBoolean("blind") == true) pixelSignature() else null
        // Andee 对齐：上一圈还亮着时先藏起来。
        marker?.hideNow()
        // Andee 对齐：滑动起点或终点在球下时球先让开，手势完恢复。
        var dodged = com.jarvis.app.OrbController.dodgeIfNeeded(x1, y1)
        if (!dodged) dodged = com.jarvis.app.OrbController.dodgeIfNeeded(x2, y2)
        val target = android.graphics.Rect(
            minOf(x1, x2) - 4, minOf(y1, y2) - 4,
            maxOf(x1, x2) + 4, maxOf(y1, y2) + 4,
        )
        val result = try {
            guardedGesture(target) {
            if (fling) {
                val stroke = GestureDescription.StrokeDescription(path, 0L, dur)
                runGesture(GestureDescription.Builder().addStroke(stroke).build(), dur + 2000)
            } else {
                val move = GestureDescription.StrokeDescription(path, 0L, dur, true)
                val moved = runGesture(GestureDescription.Builder().addStroke(move).build(), dur + 2000)
                if (moved.optBoolean("completed")) {
                    val still = Path().apply { moveTo(x2.toFloat(), y2.toFloat()) }
                    // A cancelled hold still leaves the list where the finger put it:
                    // ACTION_CANCEL ends a scroll without a fling.
                    // L16: 第二段 hold 的结果不再丢弃——失败即在结果里注明。
                    val holdResult = runGesture(
                        GestureDescription.Builder()
                            .addStroke(move.continueStroke(still, 0L, SWIPE_HOLD_MS, false))
                            .build(),
                        SWIPE_HOLD_MS + 2000,
                    )
                    if (!holdResult.optBoolean("completed", false)) {
                        moved.put("hold_completed", false)
                            .put(
                                "hold_note",
                                "the end-hold stroke was cancelled — the list should still be " +
                                    "where the finger put it (ACTION_CANCEL ends a scroll without " +
                                    "a fling), but the hold itself did not complete"
                            )
                    } else {
                        moved.put("hold_completed", true)
                    }
                }
                moved
            }
            }
        } finally {
            if (dodged) com.jarvis.app.OrbController.restoreDodge()
        }
        // L11: 主手势没完成（被取消）也不抛异常——completed=false 的结果连同
        // fling 快照一起返回，调用方按 completed 判断成败（Andee 同款）。
        if (fling && before != null) attachFlingSnapshot(result, before, beforePx)
        return result.put("from", "$x1,$y1").put("to", "$x2,$y2").put("duration_ms", dur)
            .put("fling", fling)
            .apply {
                if (dur != durationMs) {
                    put("requested_duration_ms", durationMs)
                    put("duration_clamped", true)
                }
            }
    }

    /**
     * Where a fling ended up, in the only terms a blind screen allows: whether
     * anything moved, the picture if it did, and a plain word about the case
     * that matters — a fling that moves nothing means the list is at its end.
     *
     * No claim about HOW FAR it went: without a scroll offset or a tree there
     * is nothing to measure it against, and a number invented from the finger's
     * distance would be wrong exactly when the fling coasted. What the picture
     * does buy is recognition: the brain can see the post it just passed, or
     * see that it is looking at the same three posts as last time.
     */
    private fun attachFlingSnapshot(result: JSONObject, before: JSONObject, beforePx: FloatArray?) {
        try {
            // A fling keeps coasting after the finger lifts; 700 ms caught it
            // mid-coast and made the signature disagree with itself.
            Thread.sleep(FLING_SETTLE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val after = screenFingerprint()
        var changed = before.optString("pkg") != after.optString("pkg") ||
            before.optInt("sig") != after.optInt("sig")
        if (!changed && beforePx != null) {
            pixelSignature()?.let { if (pixelsMoved(beforePx, it)) changed = true }
        }
        result.put("screen_changed", changed)
        if (changed) {
            runCatching { result.put("after_shot", guardedShot { screenshot("fling_after") }) }
        } else {
            result.put(
                "fling_note",
                "Nothing on screen moved. On a list that means you are already at its " +
                    "end in that direction — flinging the same way again will keep doing " +
                    "nothing. If you are hunting one particular old item, stop scrolling " +
                    "and use the app's search instead.",
            )
        }
    }

    /**
     * Chained flings in one direction, with no look in between, are the shape of
     * "lost in a long list": the gesture is cheap, it lands somewhere the brain
     * is not told about, and nothing about repeating it adds information.
     *
     * Deliberately generous — 6 in a row is someone browsing a feed, and
     * browsing is legitimate. The 7th in the same direction within
     * [FLING_STREAK_WINDOW_MS] is refused, and the error says what to do
     * instead. The streak is reset by a fling the other way or by a deliberate
     * scroll ([swipe] without fling), which is what a brain that is reading the
     * screen actually does between jumps.
     */
    private fun guardFlingStreak(downward: Boolean) {
        val now = SystemClock.uptimeMillis()
        synchronized(loopLock) {
            if (now - flingStreakAt > FLING_STREAK_WINDOW_MS || flingStreakDown != downward) {
                flingStreak = 0
            }
            flingStreak++
            flingStreakAt = now
            flingStreakDown = downward
            if (flingStreak > FLING_MAX_STREAK) {
                throw IllegalStateException(
                    "Refused: this is the ${flingStreak}th fling in the same direction without " +
                        "reading the screen in between. A fling lands where it lands and tells " +
                        "you nothing about where you are, so repeating it is how a long list " +
                        "turns into a maze — 21 of these in one task is the record. Do one of " +
                        "these instead: take_screenshot and look at where you actually are; " +
                        "swipe without fling (which moves about as far as your finger did); or, " +
                        "if you are looking for ONE specific old item, use the app's search " +
                        "rather than scrolling for it.",
                )
            }
        }
    }

    fun longPress(x: Int, y: Int, durationMs: Long, expectedPkg: String? = null, handover: Boolean = false): JSONObject {
        requireForeground(expectedPkg ?: lastDumpPkg, handover)
        // L11：时长在这里钳制一次，实际用的时长与原值一起返回，不静默。
        val dur = durationMs.coerceIn(LONG_PRESS_MIN_MS, LONG_PRESS_MAX_MS)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, dur)
        // Andee 对齐：上一圈还亮着时先藏起来。
        marker?.hideNow()
        // Andee 对齐：落点在球下时球先让开，手势完恢复。
        val dodged = com.jarvis.app.OrbController.dodgeIfNeeded(x, y)
        val result = try {
            guardedGesture(android.graphics.Rect(x - 4, y - 4, x + 4, y + 4)) {
                runGesture(GestureDescription.Builder().addStroke(stroke).build(), dur + 2000)
            }
        } finally {
            if (dodged) com.jarvis.app.OrbController.restoreDodge()
        }
        // L11: 手势没完成（被取消）也不抛异常——completed=false 的结果连同弹窗快照
        // 一起返回，调用方按 completed 判断成败（Andee 同款）。
        marker?.mark(x, y, TapMarkerUi.Kind.HOLD)
        // A long-press menu appears a beat after the finger lifts.
        // L16: sleep 包 runCatching，中断时恢复中断位。
        runCatching { Thread.sleep(POPUP_SETTLE_MS) }
            .onFailure { if (it is InterruptedException) Thread.currentThread().interrupt() }
        putPopups(result)
        return result.put("x", x).put("y", y).put("duration_ms", dur)
            .apply {
                if (dur != durationMs) {
                    put("requested_duration_ms", durationMs)
                    put("duration_clamped", true)
                }
            }
    }

    fun longPressById(id: String, handover: Boolean = false): JSONObject {
        requireForeground(handover = handover)
        val root = service.rootInActiveWindow ?: throw IllegalStateException("no active window")
        try {
            val matches = root.findAccessibilityNodeInfosByViewId(id).orEmpty()
            if (matches.isEmpty()) throw IllegalArgumentException("no node with id: $id")
            // L18: 只认可见且可长按的节点；M17：多个符合时不报歧义，取离旧中心
            // 最近的那个（微信复用 id 的处理，见 nearestToLastMark）。
            val usable = matches.filter { it.isVisibleToUser && it.isLongClickable }
            if (usable.isEmpty()) {
                throw IllegalStateException(
                    "no visible long-clickable node with id: $id " +
                        "(found ${matches.size} node(s), none visible+long-clickable)"
                )
            }
            val target = nearestToLastMark(usable)
            try {
                val performed = target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                return JSONObject().put("performed", performed).put("id", id)
                    .put("matches", matches.size).put("candidates", usable.size)
            } finally {
                matches.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
    }

    fun typeText(text: String, id: String?, handover: Boolean = false): JSONObject =
        typeText(text, id, secret = false, handover = handover)

    /**
     * [secret] = this text is a vault password. Everything works the same
     * except that the value never appears in the result — see
     * [typeViaSetText].
     */
    fun typeText(text: String, id: String?, secret: Boolean, handover: Boolean = false): JSONObject {
        // ── 输入通道(批九接线 + C9):无障碍输入法 [A11yIme] 优先,
        // ACTION_SET_TEXT 兜底，ADBKeyboard 广播（受 guard 的 commitText 级通道）
        // 再兜底，剪贴板粘贴最后兜底 ──
        //
        // 首选 [A11yIme]:API 33 起,声明了 flagInputMethodEditor 的无障碍
        // 服务自己就能 commitText 到焦点编辑器 —— 和键盘同一条 InputConnection,
        // 不是一个输入法:不用装、不用启用、不用切,用户自己的键盘全程还在
        // （本工程的无障碍配置已加该 flag）。
        //
        // 第二档 [typeViaSetText]:对焦点编辑节点执行 ACTION_SET_TEXT 并双路读回
        // 验证,与 JarvisAccessibilityService 的 Agent 输入动作同款机制;少数自绘
        // 编辑器可能不响应 SET_TEXT,那时读回不一致会如实报错并提示以截图
        // 为准,不假装成功。
        //
        // 第三档 [typeViaAdbKeyboard]:Andee 的 ADBKeyboard 广播(commitText 正统
        // 通道,能进自绘编辑器)。只在它已安装且已是当前输入法时才发广播，否则
        // 如实报不可用，不硬写一条走不通的路；ImeSwitch（借还输入法）本工程没有，
        // 不切输入法。密码照常打，只是不回声。
        //
        // 第四档 [typeViaClipboardPaste]:上面三档都打不进去时,先选中全部
        // 再 ACTION_PASTE,读回验证同款。SET_TEXT 平台调用返回过 true、但
        // 读回不对时,粘贴前先把框清空,免得新旧文字叠在一起。C9：密码框拒绝
        // 只拦这一档（剪贴板路径）；前面三档密码照常打。粘贴完恢复用户原来的
        // 剪贴板，不毁掉、不清成空串。
        // S2: 打字也要先确认前台是目标应用，否则文字可能进错应用。
        // H6: 交接步骤（handover）放行前台校验。
        requireForeground(handover = handover)
        // S3: id != null 时先按 viewId 定位并聚焦该输入框，找不到就报错；
        // 只有 id == null 才用当前焦点框。旧版全程忽略 id，文字会打进焦点框。
        if (id != null) focusInputById(id)
        val root = service.rootInActiveWindow ?: throw IllegalStateException("no active window")
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } finally {
            root.recycle()
        }
        if (focused == null) {
            // A withheld tree has no focus node to find, but an editor bound to our
            // input connection is still there to type into.
            if (A11yIme.editing(service)) {
                typeViaA11yIme(text, secret)?.let { return markBlindTyping(it) }
            }
            throw IllegalStateException("no input focus (tap into an input field first)")
        }
        runCatching { if (focused !== root) focused.recycle() }
        // 四档输入，只有一档能真正落字：A11yIme 返回非 null 就是定论（绝不叠加）；
        // SET_TEXT 打进去（performed）但读回不对时，粘贴前先把框清空，免得新旧
        // 文字叠在一起；四档都失败就如实报错，模型别原样重发 type——C9：前面档
        // 的失败原因不要静默吞掉，一并带回。
        val viaIme = typeViaA11yIme(text, secret)
        if (viaIme != null) return markBlindTyping(viaIme)
        val viaSet = typeViaSetText(text, secret)
        if (viaSet.optBoolean("performed", false)) return markBlindTyping(viaSet)
        val viaAdb = typeViaAdbKeyboard(text, secret)
        if (viaAdb.optBoolean("performed", false)) return markBlindTyping(viaAdb)
        val viaPaste = typeViaClipboardPaste(text, secret, viaSet.optBoolean("set_performed", false))
        if (!viaPaste.optBoolean("performed", false)) {
            viaSet.optString("error").takeIf { it.isNotEmpty() }?.let { viaPaste.put("set_text_error", it) }
            viaAdb.optString("error").takeIf { it.isNotEmpty() }?.let { viaPaste.put("adb_keyboard_error", it) }
        }
        return markBlindTyping(viaPaste)
    }

    /**
     * S3: 按 viewId 定位输入框并聚焦。多个同 id 时只认可见、可编辑/可聚焦的；
     * 一个可见的都没有（或根本没有）就报错，不回落到当前焦点框。聚焦后轮询
     * 核对焦点节点的 viewId，落不上就报错——否则文字仍会打进旧焦点框。
     */
    private fun focusInputById(id: String) {
        val root = service.rootInActiveWindow ?: throw IllegalStateException("no active window")
        try {
            val matches = root.findAccessibilityNodeInfosByViewId(id).orEmpty()
            try {
                if (matches.isEmpty()) {
                    throw IllegalArgumentException("no node with id: $id")
                }
                val visible = matches.filter { it.isVisibleToUser }
                if (visible.isEmpty()) {
                    throw IllegalStateException(
                        "no visible node with id: $id (found ${matches.size} node(s), none visible)"
                    )
                }
                // 可编辑优先，其次可聚焦；多个都符合时取第一个可见的（id 在
                // 同屏复用时，焦点框本身应唯一可编辑）。
                val target = visible.firstOrNull { it.isEditable }
                    ?: visible.firstOrNull { it.isFocusable }
                    ?: visible[0]
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } finally {
                matches.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
        // 焦点切换是异步的：短轮询核对，确认焦点确为该 id 再返回。
        val deadline = SystemClock.uptimeMillis() + 500
        while (SystemClock.uptimeMillis() < deadline) {
            val r = service.rootInActiveWindow ?: break
            try {
                val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                try {
                    if (f != null && f.viewIdResourceName == id) return
                } finally {
                    if (f != null && f !== r) f.recycle()
                }
            } finally {
                r.recycle()
            }
            runCatching { Thread.sleep(100) }
        }
        throw IllegalStateException(
            "could not focus the input with id: $id — focus did not land there, " +
                "so typing would go into the wrong field"
        )
    }

    private fun markBlindTyping(r: JSONObject): JSONObject {
        if (r.optBoolean("performed") && treeBlind()) {
            typedBlindAt = SystemClock.uptimeMillis()
            r.put(
                "next",
                "Text is in the box. To send: if a Send/发送 button is visible, zoom_screen_region on it " +
                    "first and tap with on=\"zoom\" — a plain grid-cell tap is refused here, because this " +
                    "strip sits on top of the content below and a missed tap opens that instead.",
            )
        }
        return r
    }

    /**
     * 首选通道:本服务自己的无障碍输入法([A11yIme])。
     *
     * 返回 null = **这条路不通、且一个字都没打**(API < 33、当前没有
     * 焦点编辑器、自绘编辑器拿不到 InputConnection),交给
     * [typeViaSetText] 兜底。返回非 null = 已经 commit 过,成功与
     * 否都在这里定论,绝不能再兜底一次 —— 那是真正会输两遍的情况。
     *
     * 它排在 ADBKeyboard 前面的理由不是快(虽然省掉了借还输入法的
     * ~0.75s),是**用户不需要电脑**:ADB 通道要侧载一个第三方 APK,
     * 还要 `adb shell pm grant … WRITE_SECURE_SETTINGS` 才能改默认输入
     * 法。没有电脑的用户在那条路上一个字也打不了。
     */
    private fun typeViaA11yIme(text: String, secret: Boolean): JSONObject? {
        val got = A11yIme.replace(service, text) ?: return null
        val ok = got == text
        return JSONObject()
            .put("performed", ok)
            .put("via", A11yIme.VIA)
            .put("len", text.length)
            .apply {
                if (ok) {
                    if (!secret) put("text", got)
                } else if (secret) {
                    put(
                        "error",
                        "committed through the accessibility IME but the field holds " +
                            "${got.length} chars instead of ${text.length} — " +
                            "verify with a screenshot",
                    )
                } else {
                    put(
                        "error",
                        "committed through the accessibility IME but the field reads " +
                            "'${got.take(30)}' — verify with a screenshot",
                    )
                }
            }
    }

    /**
     * 兜底输入通道(批九接线层替换):对当前焦点编辑节点执行 ACTION_SET_TEXT,
     * 再双路读回验证(焦点框优先,全树兜底 ——与原版读回纪律一致)。
     *
     * ACTION_SET_TEXT 是 JarvisAccessibilityService 的 Agent
     * 输入动作一直在用的机制:大多数原生/EditText/WebView 表单可用,少数
     * 自绘编辑器可能不响应 —— 那时读回不一致会如实报错,并提示以截图为准,
     * 不假装成功。C9：密码框照常填（密码拒绝只拦剪贴板粘贴那条路）；
     * 失败时返回明确的失败原因（error 字段），调用方不得静默吞掉。
     *
     * [secret] 为真时读回照常做内部比对,但成功不回声、失败不带文本片段。
     */
    private fun typeViaSetText(text: String, secret: Boolean): JSONObject {
        val root = service.rootInActiveWindow ?: throw IllegalStateException("no active window")
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: throw IllegalStateException("no input focus (tap into an input field first)")
            try {
                focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text,
                    )
                }
                val performed = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                // 轮询读回而不是睡一个固定值:SET_TEXT 之后 WebView 还要把新值
                // 回抛给无障碍树,读早了会报「没输进去」这种会诱发重发的假错误。
                var afterFocused = ""
                var afterTree = ""
                val deadline = SystemClock.uptimeMillis() + READBACK_BUDGET_MS
                do {
                    Thread.sleep(READBACK_POLL_MS)
                    afterFocused = readFocusedText()
                    afterTree = if (afterFocused == text) "" else readTextFromTree(text)
                    if (afterFocused == text || afterTree == text) break
                } while (SystemClock.uptimeMillis() < deadline)
                val ok = performed && (afterFocused == text || afterTree == text)
                return if (ok) {
                    JSONObject()
                        .put("performed", true)
                        .put("set_performed", true)
                        .put("via", "set_text")
                        .put("len", text.length)
                        .apply {
                            if (!secret) put("text", if (afterFocused == text) afterFocused else afterTree)
                        }
                } else if (secret) {
                    JSONObject()
                        .put("performed", false)
                        .put("set_performed", performed)
                        .put("via", "set_text")
                        .put("len", text.length)
                        .put(
                            "error",
                            "set-text sent but the field does not hold the expected value " +
                                "(read back ${afterFocused.length} chars from the focused node, " +
                                "${afterTree.length} from the tree) — the editor may ignore " +
                                "ACTION_SET_TEXT or hide its text; verify with a screenshot",
                        )
                } else {
                    JSONObject()
                        .put("performed", false)
                        .put("set_performed", performed)
                        .put("via", "set_text")
                        .put("len", text.length)
                        .put(
                            "error",
                            "set-text sent but field text != expected (focused: '${afterFocused.take(30)}', tree: '${afterTree.take(30)}') — " +
                                "the editor may ignore ACTION_SET_TEXT or hide its text; verify with a screenshot",
                        )
                }
            } finally {
                if (focused !== root) focused.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * 第三档输入：ADBKeyboard 广播（commitText 级通道，Andee 原版兜底）。
     *
     * ADBKeyboard 是激活的输入法，收到 ADB_INPUT_TEXT 后走
     * InputConnection.commitText()——与真人敲键盘同一条路径，目标应用无法拒绝，
     * 能进 CodeMirror 等自绘编辑器。action 是裸字符串，没有包名前缀，加上
     * setPackage 钉死收件人（Andee 实测：带包名前缀的 action 匹配不上它注册的
     * filter，广播静默消失）。
     *
     * C9：这条路只在真实可用时才发，绝不硬写一条走不通的路。可用的条件缺一不可：
     * 设备上装了 com.android.adbkeyboard，且它当前就是默认输入法。借还输入法
     * （Andee 的 ImeSwitch）本工程没有，也没有 WRITE_SECURE_SETTINGS 授权路径
     * （须电脑 adb grant），所以未激活时不在这里切输入法，只如实报不可用。
     * 已激活时：先 ADB_CLEAR_TEXT 清残留，再 ADB_INPUT_TEXT，最后双路读回验证。
     *
     * [secret] 为真时（保险箱密码）照常打字，只是不回声——Andee 同款；拒绝密码
     * 的只有剪贴板那条路。
     */
    private fun typeViaAdbKeyboard(text: String, secret: Boolean): JSONObject {
        fun unavailable(reason: String): JSONObject =
            JSONObject().put("performed", false).put("via", "adb_keyboard").put("len", text.length)
                .put("error", reason)
        val installed = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                service.packageManager.getPackageInfo(
                    ADB_KEYBOARD_PKG,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                service.packageManager.getPackageInfo(ADB_KEYBOARD_PKG, 0)
            }
            true
        }.getOrDefault(false)
        if (!installed) {
            return unavailable(
                "ADBKeyboard is not installed on this device (com.android.adbkeyboard) — " +
                    "the commitText broadcast has nobody to receive it. Install it and make it " +
                    "the active IME (needs a computer: adb shell pm grant … WRITE_SECURE_SETTINGS), " +
                    "or type through the accessibility IME / set-text instead",
            )
        }
        val currentIme = runCatching {
            android.provider.Settings.Secure.getString(
                service.contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
            )
        }.getOrNull().orEmpty()
        if (currentIme != BRAIN_IME) {
            return unavailable(
                "ADBKeyboard is installed but is not the active IME (current: " +
                    currentIme.ifEmpty { "unknown" } + ") — the broadcast would be delivered to nobody. " +
                    "Jarvis has no ImeSwitch (borrow/return) and no WRITE_SECURE_SETTINGS grant, so it " +
                    "cannot switch the IME itself; switch it manually or use another channel",
            )
        }
        runCatching {
            service.sendBroadcast(
                android.content.Intent(ADB_CLEAR_TEXT).setPackage(ADB_KEYBOARD_PKG),
            )
            Thread.sleep(150)
        }
        service.sendBroadcast(
            android.content.Intent(ADB_INPUT_TEXT)
                .putExtra("msg", text)
                .setPackage(ADB_KEYBOARD_PKG),
        )
        var afterFocused = ""
        var afterTree = ""
        val deadline = SystemClock.uptimeMillis() + READBACK_BUDGET_MS
        do {
            Thread.sleep(READBACK_POLL_MS)
            afterFocused = readFocusedText()
            afterTree = if (afterFocused == text) "" else readTextFromTree(text)
            if (afterFocused == text || afterTree == text) break
        } while (SystemClock.uptimeMillis() < deadline)
        val ok = afterFocused == text || afterTree == text
        return if (ok) {
            JSONObject()
                .put("performed", true)
                .put("via", "adb_keyboard")
                .put("len", text.length)
                .apply {
                    if (!secret) put("text", if (afterFocused == text) afterFocused else afterTree)
                }
        } else if (secret) {
            JSONObject()
                .put("performed", false)
                .put("via", "adb_keyboard")
                .put("len", text.length)
                .put(
                    "error",
                    "broadcast sent but the field does not hold the expected value " +
                        "(read back ${afterFocused.length} chars from the focused node, " +
                        "${afterTree.length} from the tree) — ADBKeyboard may not be the " +
                        "active IME, or the target hides its text; verify with a screenshot",
                )
        } else {
            JSONObject()
                .put("performed", false)
                .put("via", "adb_keyboard")
                .put("len", text.length)
                .put(
                    "error",
                    "broadcast sent but field text != expected (focused: '${afterFocused.take(30)}', tree: '${afterTree.take(30)}') — " +
                        "ADBKeyboard may not be the active IME, or the target hides its text; verify with a screenshot",
                )
        }
    }

    /**
     * 第四档输入：剪贴板粘贴。少数自绘编辑器既不响应无障碍输入法、也不响应
     * ACTION_SET_TEXT，粘贴是最后一条路：先选中全部、再 ACTION_PASTE，
     * 读回验证与 SET_TEXT 同款双路（焦点框优先，全树兜底）。
     *
     * [clearFirst]：SET_TEXT 平台调用返回过 true、但读回不对时，框里可能已
     * 经有字，先选中全部 + SET_TEXT("") 清空再粘，免得新旧文字叠在一起。
     * [secret] 为真时结果里不出现文本；C9：粘贴完恢复用户原来的剪贴板
     * （有备份就恢复备份，没有备份才清成空串），别让刚输的文字（尤其是密码）
     * 留在剪贴板里，也别毁掉用户原来的内容。恢复失败只记一句，不因此判失败。
     * 密码框不填（C9：密码拒绝只拦剪贴板这一档）。
     */
    /**
     * 全选输入框里的文字。无障碍 API 里没有 ACTION_SELECT_ALL，全选要走
     * ACTION_SET_SELECTION（起点 0，终点文字长度）。读不到文字时长度按 0，
     * 选区为空，不报错。
     */
    private fun selectAllText(node: AccessibilityNodeInfo) {
        val len = node.text?.length ?: 0
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun typeViaClipboardPaste(text: String, secret: Boolean, clearFirst: Boolean): JSONObject {
        val root = service.rootInActiveWindow ?: throw IllegalStateException("no active window")
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: throw IllegalStateException("no input focus (tap into an input field first)")
            try {
                if (focused.isPassword) {
                    return JSONObject()
                        .put("performed", false)
                        .put("via", "clipboard_paste")
                        .put("len", text.length)
                        .put("error", "focused field is a password field — Jarvis does not type into password fields")
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (clearFirst) {
                    selectAllText(focused)
                    val clearArgs = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            "",
                        )
                    }
                    focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
                }
                val clip = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                // C9：先备份用户剪贴板——粘贴完恢复原样，不毁掉用户原来的内容；
                // 失败也不清成空串。备份读不到时记下来，恢复时只清掉我们这次写进去的。
                val originalClip = runCatching { clip.primaryClip }.getOrNull()
                clip.setPrimaryClip(ClipData.newPlainText("jarvis-type", text))
                selectAllText(focused)
                val pasted = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                // 轮询读回而不是睡一个固定值：粘贴之后 WebView 还要把新值回抛给
                // 无障碍树，读早了会报「没输进去」这种会诱发重发的假错误。
                var afterFocused = ""
                var afterTree = ""
                val deadline = SystemClock.uptimeMillis() + READBACK_BUDGET_MS
                do {
                    Thread.sleep(READBACK_POLL_MS)
                    afterFocused = readFocusedText()
                    afterTree = if (afterFocused == text) "" else readTextFromTree(text)
                    if (afterFocused == text || afterTree == text) break
                } while (SystemClock.uptimeMillis() < deadline)
                val ok = pasted && (afterFocused == text || afterTree == text)
                // C9：粘贴完恢复用户原来的剪贴板（有备份就恢复备份，没有备份才清成
                // 空串），别让刚输的文字（尤其是密码）留在剪贴板里，也别毁掉用户
                // 原来的内容。恢复失败只记一句，不因此判失败。
                val restored = runCatching {
                    if (originalClip != null) clip.setPrimaryClip(originalClip)
                    else clip.setPrimaryClip(ClipData.newPlainText("", ""))
                    true
                }.getOrDefault(false)
                val note = if (!restored) "clipboard could not be restored after paste" else ""
                return if (ok) {
                    JSONObject()
                        .put("performed", true)
                        .put("via", "clipboard_paste")
                        .put("len", text.length)
                        .apply {
                            if (!secret) put("text", if (afterFocused == text) afterFocused else afterTree)
                            if (note.isNotEmpty()) put("clipboard_note", note)
                        }
                } else if (secret) {
                    JSONObject()
                        .put("performed", false)
                        .put("via", "clipboard_paste")
                        .put("len", text.length)
                        .put(
                            "error",
                            "paste sent but the field does not hold the expected value " +
                                "(read back ${afterFocused.length} chars from the focused node, " +
                                "${afterTree.length} from the tree) — the editor may ignore " +
                                "ACTION_PASTE or hide its text; verify with a screenshot",
                        )
                        .apply { if (note.isNotEmpty()) put("clipboard_note", note) }
                } else {
                    JSONObject()
                        .put("performed", false)
                        .put("via", "clipboard_paste")
                        .put("len", text.length)
                        .put(
                            "error",
                            "paste sent but field text != expected (focused: '${afterFocused.take(30)}', tree: '${afterTree.take(30)}') — " +
                                "the editor may ignore ACTION_PASTE or hide its text; verify with a screenshot",
                        )
                        .apply { if (note.isNotEmpty()) put("clipboard_note", note) }
                }
            } finally {
                if (focused !== root) focused.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    /** Focused input's text, or "" — never throws, never leaks nodes. */
    private fun readFocusedText(): String = runCatching {
        val r = service.rootInActiveWindow ?: return@runCatching ""
        val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        try { f?.text?.toString().orEmpty() } finally {
            if (f != null && f !== r) f.recycle()
            r.recycle()
        }
    }.getOrDefault("")
    /**
     * Deep-scan the active window's tree for a node whose text equals
     * [want] — the fallback read path for WebViews that keep the focused
     * node's text hidden from accessibility. Returns "" when absent.
     */
    private fun readTextFromTree(want: String): String {
        if (want.isEmpty()) return ""
        val r = service.rootInActiveWindow ?: return ""
        val budget = WalkBudget(SEARCH_BUDGET_MS)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(r)
        try {
            while (stack.isNotEmpty() && !budget.spent()) {
                val n = stack.removeFirst()
                try {
                    val t = n.text?.toString().orEmpty()
                    if (t == want) return t
                    for (i in 0 until n.childCount) childOf(n, i)?.let { stack.add(it) }
                } finally {
                    // L15: 弹出的节点逐个回收；根节点由外层 finally 回收。
                    if (n !== r) n.recycle()
                }
            }
        } finally {
            while (stack.isNotEmpty()) stack.removeFirst().recycle()
            r.recycle()
        }
        return ""
    }

    /**
     * Fire the current input field's IME action (Enter / Send / Search /
     * Next / Done — whichever the field was set up for). Prefer this over
     * synthesizing a keyboard Enter, because IME action is what the app is
     * actually listening for.
     *
     * When `id` is null we use the currently focused input.
     */
    fun submitInput(id: String?): JSONObject {
        if (id == null && A11yIme.sendsOnlyFromButton(service)) {
            throw IllegalStateException(
                "this field has no keyboard send action — it sends only from its own Send/发送 " +
                    "button. Tap that button" +
                    (if (treeBlind()) ": zoom_screen_region on it first and tap with on=\"zoom\"." else ".")
            )
        }
        // An app that withholds its tree (WeChat on the Honor phone) has no focus
        // node to find, yet its editor is still bound to our input connection —
        // type_text reaches it that way. Ask the editor for its own action before
        // calling that a failure.
        if (id == null) {
            val r = service.rootInActiveWindow
            val focus = r?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            focus?.recycle()
            r?.recycle()
            if (focus == null) {
                if (A11yIme.editorAction(service)) {
                    return JSONObject().put("performed", true).put("via", A11yIme.VIA)
                }
                throw IllegalStateException(
                    "no input focus, and the focused editor (if any) declares no Send/Search action — " +
                        "tap into the field first, or tap the app's own send button"
                )
            }
        }
        val root = service.rootInActiveWindow ?: throw IllegalStateException("no active window")
        try {
            val target: AccessibilityNodeInfo = if (id != null) {
                root.findAccessibilityNodeInfosByViewId(id).orEmpty().firstOrNull()
                    ?: throw IllegalArgumentException("no node with id: $id")
            } else {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: throw IllegalStateException("no input focus (tap into an input field first)")
            }
            try {
                val performed = target.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                )
                // A WebView's editor often reports the action but does not act on
                // it. [A11yIme.editorAction] asks the editor's own EditorInfo what
                // it is (Send / Search / Next) and fires that, which is what its
                // listener is registered for. Second rather than first because
                // ACTION_IME_ENTER needs no focused *input connection* and covers
                // the `id`-addressed case this method also serves.
                val viaIme = if (!performed && id == null) {
                    A11yIme.editorAction(service)
                } else {
                    false
                }
                return JSONObject()
                    .put("performed", performed || viaIme)
                    .put("target_id", target.viewIdResourceName)
                    .apply { if (viaIme) put("via", A11yIme.VIA) }
            } finally {
                if (target !== root) target.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * @param actor who pressed it. The same method serves the brain's
     *   `system_action` and the user's own top-bar back/home button, and from
     *   in here they are indistinguishable — but a navigation the user chose
     *   invalidates the episode around it, so [GroundingLog] has to be told
     *   which it was. Defaulted to "brain" because the dispatcher, the only
     *   caller that can't be the user, is also the one that never passes it.
     */
    fun globalAction(action: String, actor: String = "brain"): JSONObject {
        // Leaving the screen ends whatever was being typed into.
        typedBlindAt = 0L
        screenMovedAtMs = SystemClock.uptimeMillis()
        val code = when (action.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> throw IllegalArgumentException("unknown global action: $action")
        }
        GroundingLog.noteNav(action.lowercase(), actor)
        val ok = service.performGlobalAction(code)
        return JSONObject().put("performed", ok).put("action", action)
    }

    /**
     * @param reason why this capture happened — "tool" (take_screenshot),
     *   "with_shot", or "auto_empty" (the dump had no list to give). It is the
     *   field the offline pairing filters on: only the self-drawn panes behind
     *   "auto_empty" carry coordinate taps worth learning from.
     * @param grid draw the [withAimGrid] overlay. Off only for a capture that
     *   is not itself shown to the brain — [lookRegion] takes a full shot and
     *   then crops it, and a crop carrying the full screen's grid lines and
     *   cell labels would put two contradictory grids on one image.
     */
    fun screenshot(reason: String = "tool", grid: Boolean = true, fullRes: Boolean = false): JSONObject {
        // Only a screenshot the brain ASKED for clears the fling streak. Every
        // internal capture (tap_after, fling_after, miss_shot) goes through
        // here too, and counting those would make the streak unbuildable.
        if (reason == "tool") synchronized(loopLock) { flingStreak = 0 }
        // H7: 不做前台拒绝——转场瞬间、通知栏/最近任务/权限弹窗在前、视频全屏页、
        // 自家界面在前时也要能截图（Andee 同款：开头只清 fling streak、做稳定等待，
        // 无任何前台包检查）。前台包只读来做诊断（secure_window 报错里带上它），
        // 不拦截截图；是否拍得到只由系统 FLAG_SECURE 决定。
        // lookRegion/zoomAim 内部调 screenshot，这条链路也跟着通。
        val fgPkg = foregroundPkg().orEmpty()
        // 第九批·坐标契约：拍图就是一次观察，盖戳——模型若对着这张图瞄准，
        // 执行时比的就是拍图时的尺寸；旋转发生在 dump 与截图之间时，以这张
        // 更晚的戳为准，不让旧 dump 的戳误拒一张新图上读的坐标。
        noteObservedSize()
        // Settle before capture: images loading and lists settling MOVE the
        // things a screenshot is about to be used to aim at — a shot taken
        // mid-settle gives the brain targets that have already shifted by
        // the time it taps (the "aimed at A, screen became B" miss). Same
        // stability criterion as dumpUiTree: two consecutive tree signatures
        // agree. Cheap when still (one extra fingerprint pass), capped when
        // genuinely animated — the shot is taken anyway and `still_moving`
        // says so, rather than pretending.
        // M34: 稳定失败（多次采样始终不一致）时结果里写 still_moving:true。
        var stillMoving = false
        runCatching {
            val first = screenFingerprint()
            // A slow app cannot be fingerprinted to a stable answer, and each
            // try would cost a full budget — take the picture as it is.
            if (first.optBoolean("cut")) return@runCatching
            var prev = first.optInt("sig", -1)
            var tries = 0
            while (tries < STABLE_MAX_TRIES) {
                Thread.sleep(STABLE_POLL_MS)
                val again = screenFingerprint()
                if (again.optBoolean("cut")) return@runCatching
                val sig = again.optInt("sig", -2)
                if (sig == prev) return@runCatching
                prev = sig
                tries++
            }
            stillMoving = true
        }
        val latch = CountDownLatch(1)
        var pngBytes: ByteArray? = null
        var errCode = 0
        var w = 0;
        var h = 0
        val executor = Executors.newSingleThreadExecutor()
        awaitCaptureGap()
        // Andee 对齐：每次截图球都要藏起来（提示词里一直这么写，代码此前没做）。
        com.jarvis.app.OrbController.setVisibleForCapture(false)
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hb = screenshot.hardwareBuffer
                            val cs = screenshot.colorSpace
                            val bmp = Bitmap.wrapHardwareBuffer(hb, cs)
                                ?: run { errCode = -1; return }
                            w = bmp.width; h = bmp.height
                            // Downscale to max side 1280 before PNG-encoding
                            // (generic_bridge finding): the raw 1600x2560 shot
                            // is ~765KB base64 and models gain nothing from
                            // the extra pixels, while every vision token is
                            // billed. Safe for coordinates because the whole
                            // protocol is 0-1000 relative — scale-invariant.
                            // 例外：fullRes=true 时不缩（OCR 专用）：ML Kit 认小字
                            // 需要原分辨率，缩到一半后 14sp 的字只剩 20 来像素，
                            // 认错/认不出；原图 40 多像素才稳。OCR 不走模型 token，
                            // 不怕图大，认完位图就回收。
                            val scale = if (fullRes) 1f else SHOT_MAX_SIDE.toFloat() / maxOf(w, h)
                            val outBmp = if (scale < 1f) {
                                Bitmap.createScaledBitmap(
                                    bmp,
                                    (w * scale).toInt().coerceAtLeast(1),
                                    (h * scale).toInt().coerceAtLeast(1),
                                    true,
                                )
                            } else bmp
                            // The grid goes on the downscaled copy and only
                            // there: this PNG is what the brain reads a cell
                            // off, so the lines must be drawn at the
                            // resolution it will be looking at. `bmp` is left
                            // untouched for the same reason it was never ours
                            // to keep — it belongs to the hardware buffer.
                            val gridded = if (grid) withAimGrid(outBmp, zoom = false) else outBmp
                            val bos = ByteArrayOutputStream()
                            gridded.compress(Bitmap.CompressFormat.PNG, 100, bos)
                            pngBytes = bos.toByteArray()
                            if (gridded !== outBmp) gridded.recycle()
                            if (outBmp !== bmp) outBmp.recycle()
                            hb.close()
                        } catch (t: Throwable) {
                            errCode = -2
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        errCode = errorCode
                        latch.countDown()
                    }
                },
            )
            if (!latch.await(5, TimeUnit.SECONDS)) throw IllegalStateException("screenshot timeout")
            val bytes = pngBytes ?: throw IllegalStateException(
                if (errCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                    "screenshot refused: secure_window — the foreground app (${fgPkg.ifEmpty { "unknown" }}) " +
                        "blocks screenshots (FLAG_SECURE); judge from the element list instead"
                } else {
                    "screenshot failed with code $errCode"
                }
            )
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            // Hooked here, not in onSuccess: the PNG is already encoded at this
            // point, so logging costs no second capture and no second compress —
            // and the callback runs on `executor`, which is shutdown two lines
            // below. Same downscale arithmetic as above, since `w`/`h` are
            // deliberately reported as the SOURCE dimensions.
            // fullRes=true 时上面没缩，这里也别算缩了，保持日志与实际一致。
            val scale = if (fullRes) 1f else SHOT_MAX_SIDE.toFloat() / maxOf(w, h)
            val outW = if (scale < 1f) (w * scale).toInt().coerceAtLeast(1) else w
            val outH = if (scale < 1f) (h * scale).toInt().coerceAtLeast(1) else h
            val shotId = GroundingLog.noteShot(
                png = bytes,
                srcW = w, srcH = h, outW = outW, outH = outH,
                reason = reason,
                pkg = lastPkg(),
                markerPx = marker?.lastMarkPx(),
                markerAgeMs = marker?.lastMarkAgeMs(),
            )
            return JSONObject()
                .put("png_base64", b64)
                .put("width", w)
                .put("height", h)
                .put("bytes", bytes.size)
                // Which way in this picture came through, named rather than
                // implied. The other value is
                // "media_projection", whose shots carry no
                // aim grid and no element list — so "no `source` field" would
                // have to mean "rich", and a third source later would silently
                // inherit that meaning. Both are spelled out instead.
                .put("source", "accessibility")
                // Lets the response the brain saw be matched to the file on
                // disk. Absent when the log is off.
                .also { if (shotId != null) it.put("shot_id", shotId) }
                // The grid, named. A model that has to infer the labels' meaning
                // from the picture alone may never use them at all, and the
                // whole point is to take the rescaling arithmetic out of its
                // hands.
                .also {
                    if (grid) it.put(
                        "aim_grid",
                        "This image carries an 8x12 aim grid, labelled A1 (top-left) to " +
                            "${'A' + GRID_COLS - 1}$GRID_ROWS (bottom-right), a letter per " +
                            "column and a number per row. The letters run along the TOP edge " +
                            "and the numbers down the LEFT edge, like a map's rulers; nothing " +
                            "is printed inside a cell. To tap something, follow its column up " +
                            "to the letter and its row left to the number, name that cell " +
                            "plus which third of that cell it is in — {\"cell\": \"D7\", " +
                            "\"part\": \"bottom-right\"} on tap_by_coordinates / " +
                            "long_press_by_coordinates (part is one of " +
                            "${PARTS.joinToString("/")}; omit it for the centre) — and the " +
                            "device converts it to the right pixels itself. For anything " +
                            "not clearly bigger than a cell (an icon, a ✕ close button), " +
                            "first send the same cell and part to zoom_screen_region: it " +
                            "shows a magenta crosshair where the tap would land, so you can " +
                            "confirm it or re-aim off the magnified image. The grid lines, " +
                            "the labels and anything MAGENTA are drawn by the device and are " +
                            "not part of the app: the magenta ring with a \"tap …\" chip marks " +
                            "where your last gesture landed, and the magenta crosshair marks " +
                            "where the next one would. Your own floating ball is hidden for " +
                            "every capture. Everything else in the picture — including " +
                            "anything round, blue or red — is the app.",
                    )
                    if (reason == "tool") putPopups(it)
                }
                // Piggyback the element list. The brain's known failure mode
                // (measured, 2026-09-20): it takes a screenshot, measures a
                // target in pixels off the image, and taps blind — even on
                // panes where the tree was available one tool call earlier,
                // because the image arrived WITHOUT the list and "out of
                // sight, out of mind". Attaching the same system-computed
                // 0-1000 centers the tree tool returns makes every screenshot
                // self-sufficient: aim by eid, not by eyeballing pixels.
                // Deliberately not a parameter — there is no case where a
                // screenshot is better WITHOUT the list; on self-drawn panes
                // it comes back empty and costs nothing.
                .also {
                    runCatching {
                        val tree = dumpOnce(false, WalkBudget(ATTACHED_TREE_MS))
                        if (tree.optInt("nodes_useful", 0) > 0) {
                            rebuildIndexAndList(tree)
                            // S2: 截图时切了应用，目标包跟着新树走。
                            tree.optString("pkg").takeIf { it.isNotEmpty() }?.let { lastDumpPkg = it }
                            val elements = tree.optString("elements")
                            if (elements.isNotEmpty()) {
                                it.put("elements", elements)
                                    .put("nodes_useful", tree.optInt("nodes_useful"))
                                    .put("aim_hint",
                                        "Prefer tap_id(e) / type(id) with these elements — " +
                                        "system-computed, always accurate. Pixel-aiming " +
                                        "from the image is the fallback, not the default." +
                                        if (tree.optBoolean("walk_cut")) {
                                            " The list is PARTIAL: this app answered too slowly " +
                                                "to read it all, so aim at what is missing from the image."
                                        } else "")
                            }
                        }
                    }
                }
                // M34: 稳定失败时如实标记，模型别把一张还在动的截图当定稿来瞄准。
                .also { if (stillMoving) it.put("still_moving", true) }
        } finally {
            com.jarvis.app.OrbController.setVisibleForCapture(true)
            lastCaptureAt.set(SystemClock.uptimeMillis())
            executor.shutdown()
        }
    }

    /**
     * Zoomed look at a region — the fix for "small target, big guess".
     *
     * A full screenshot compresses a `...` bubble to ~25px, and a vision
     * model's ±10px read on that is ±32px on the real screen — half the
     * button's hit area, so taps land or miss at coin-flip odds and every
     * retry re-guesses from the same un-zoomed picture (measured in the
     * field: three aims at one button, y=700/547/709 — 410px of spread).
     * This returns the region magnified, so the same ±10px read becomes
     * ±3px on screen: the brain sees the button clearly, aims once, done.
     *
     * Coordinates are the same 0-1000 relative space as everything else;
     * `w`/`h` are the region's size in that space. The returned image keeps
     * its true aspect, is scaled so the LONGER side hits
     * [SHOT_MAX_SIDE] (same budget as a full shot), and the response
     * restates the region's screen bounds plus a `scale` factor — the brain
     * reads a point off the zoomed image, divides by scale, adds the
     * region's origin, and has a screen-true coordinate without the device
     * doing math for it.
     */
    fun lookRegion(nx: Int, ny: Int, w: Int, h: Int, mark: IntArray? = null): JSONObject {
        requireNorm(nx, "x"); requireNorm(ny, "y")
        // 第九批·坐标契约：zoom 的中心点也是屏坐标，先过屏契约（尺寸未知/已变
        // 就拒，不拍一张对不上现在屏幕的放大图）。中心点通过后，区域裁剪仍用
        // 现读尺寸做钳制（见下），保证裁出的区域一定在屏内。
        contractSize(nx to ny)
        if (w <= 0 || h <= 0 || w > 1000 || h > 1000) {
            throw IllegalArgumentException("w and h must be 1..1000 (relative), got w=$w h=$h")
        }
        val left = (nx.toPixel(screenW) - w.toPixel(screenW) / 2).coerceIn(0, screenW - 1)
        val top = (ny.toPixel(screenH) - h.toPixel(screenH) / 2).coerceIn(0, screenH - 1)
        val right = (left + w.toPixel(screenW)).coerceAtMost(screenW)
        val bottom = (top + h.toPixel(screenH)).coerceAtMost(screenH)
        if (right - left < 8 || bottom - top < 8) {
            throw IllegalArgumentException("region too small after clamping to screen")
        }
        val full = screenshot("look_region", grid = false)
        val bytes = Base64.decode(full.getString("png_base64"), Base64.NO_WRAP)
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("could not decode screenshot")
        // The DECODED bitmap's own size, not the response's "width"/"height":
        // those report the SOURCE screen dimensions while the PNG itself is
        // downscaled to max 1280 (first field report bug: x+width overflowed
        // the real bitmap and createBitmap threw).
        val shotW = bmp.width
        val shotH = bmp.height
        val sx = shotW.toFloat() / screenW
        val sy = shotH.toFloat() / screenH
        val rLeft = (left * sx).toInt().coerceIn(0, shotW - 2)
        val rTop = (top * sy).toInt().coerceIn(0, shotH - 2)
        val rRight = (right * sx).toInt().coerceAtLeast(rLeft + 4).coerceAtMost(shotW)
        val rBottom = (bottom * sy).toInt().coerceAtLeast(rTop + 4).coerceAtMost(shotH)
        // Magnify the crop so its longer side hits the same budget a full
        // screenshot gets — the entire point is more pixels-per-target.
        val cw = rRight - rLeft
        val ch = rBottom - rTop
        val mag = (SHOT_MAX_SIDE.toFloat() / maxOf(cw, ch)).coerceAtLeast(1f)
        val outW = (cw * mag).toInt().coerceAtLeast(1)
        val outH = (ch * mag).toInt().coerceAtLeast(1)
        try {
            val region = Bitmap.createBitmap(bmp, rLeft, rTop, cw, ch)
            val scaled = Bitmap.createScaledBitmap(region, outW, outH, true)
            // Region bounds in the 0-1000 space the brain speaks — computed
            // before the grid so the recorded zoom describes the screen
            // rectangle that was actually magnified.
            val relLeft = (left * 1000f / screenW).toInt()
            val relTop = (top * 1000f / screenH).toInt()
            val relRight = (right * 1000f / screenW).toInt()
            val relBottom = (bottom * 1000f / screenH).toInt()
            // Same grid, same 8×12, drawn on a picture of a region ~120 units
            // wide: each cell here is ~15 screen units against the ~125 of a
            // full shot, which is what makes two steps enough. Remembered so
            // `on="zoom"` and a lowercase cell can be turned back into screen
            // coordinates without the brain doing the arithmetic itself.
            zoomRegion = intArrayOf(relLeft, relTop, relRight, relBottom)
            zoomAtMs = SystemClock.uptimeMillis()
            // 第九批·坐标契约：与 zoomRegion 同批记下拍图时的屏幕尺寸，供
            // zoomToScreen 验图上坐标是否还对得上现在的屏；读不到记 null，
            // 下次 on="zoom" 按“尺寸未知”拒（fail closed）。
            zoomSize = runCatching { displaySize(service) }.getOrNull()
                ?.takeIf { it.x > 0 && it.y > 0 }
            val gridded = withAimGrid(scaled, zoom = true)
            if (mark != null) {
                drawCrosshair(
                    gridded,
                    (mark[0] - relLeft) * outW.toFloat() / (relRight - relLeft).coerceAtLeast(1),
                    (mark[1] - relTop) * outH.toFloat() / (relBottom - relTop).coerceAtLeast(1),
                )
            }
            val bos = ByteArrayOutputStream()
            gridded.compress(Bitmap.CompressFormat.PNG, 100, bos)
            val outBytes = bos.toByteArray()
            region.recycle()
            if (gridded !== scaled) gridded.recycle()
            if (scaled !== region) scaled.recycle()
            return JSONObject()
                .put("png_base64", Base64.encodeToString(outBytes, Base64.NO_WRAP))
                .put("width", outW)
                .put("height", outH)
                .put("bytes", outBytes.size)
                .put(
                    "region",
                    JSONObject()
                        .put("left", relLeft).put("top", relTop)
                        .put("right", relRight).put("bottom", relBottom),
                )
                // shot px per screen-1000 unit: a point read off the image at
                // (px, py) maps to screen-relative
                // (relLeft + px / scale_x, relTop + py / scale_y).
                .put("scale_x", outW.toFloat() / (relRight - relLeft).coerceAtLeast(1))
                .put("scale_y", outH.toFloat() / (relBottom - relTop).coerceAtLeast(1))
                // The arithmetic above still works and is still documented,
                // but it is no longer what the brain is asked to do: this is
                // the magnified picture, so its labels are lowercase and a tap
                // may name one of them with no conversion at all.
                .put(
                    "zoom_hint",
                    "This image is a MAGNIFIED REGION, and its grid is lowercase " +
                        "(a1..h12) so a label can never be confused with a cell of the " +
                        "full screen. Letters run along its top edge and numbers down its " +
                        "left edge; nothing is printed inside a cell. " +
                        "Name one of these labels, plus which third of it the target is " +
                        "in — {\"cell\": \"d7\", \"part\": \"top-left\", \"on\": \"zoom\"} " +
                        "on tap_by_coordinates / long_press_by_coordinates — and the " +
                        "device converts it for you; no arithmetic, and none of the " +
                        "scale_x/scale_y math is needed." +
                        // The one mis-read this image invites: a small red dot in
                        // the app (an unread badge) versus our own mark. Said
                        // explicitly because it happened (2026-10-20).
                        " The magenta circle-and-cross IS DEVICE-DRAWN: it is where " +
                        "this tap would land, not app content, and nothing in this " +
                        "picture is ever a reason to dismiss the app's own red badge — " +
                        "a red dot in the app usually marks the very button you are " +
                        "aiming at.",
                )
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Whether the window in front reports itself as visible to us **right
     * now** — the one question [net.kuafuai.andee.net.CommandDispatcher]'s fold
     * has to be able to wait on.
     *
     * A fullscreen overlay of ours occludes the app, and every node of an
     * occluded window answers `isVisibleToUser == false`; [buildNode] drops
     * those before it counts them, so the whole tree reads as empty. Folding
     * the card fixes it, but `updateViewLayout` only *asks* — the system
     * recomputes occlusion a frame or two later, measured here at ~140 ms after
     * the fold call has already returned. A dump that beats that recomputation
     * gets zero nodes from a healthy screen.
     *
     * False is deliberately also the answer when there is no root at all: the
     * caller polls to a deadline, and a screen mid-transition is worth the same
     * short wait as one still being uncovered.
     */
    fun activeWindowVisible(): Boolean {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return false
        return try {
            // L22: 只看 isVisibleToUser 不够——根节点属于 Jarvis 自己或 SystemUI
            // 时也算“可见”，调用方会误以为目标应用已就绪。
            val pkg = runCatching { root.packageName?.toString() }.getOrNull().orEmpty()
            if (pkg.isEmpty() || pkg == service.packageName || pkg == SYSTEM_UI_PKG) return false
            runCatching { root.isVisibleToUser }.getOrDefault(false)
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * 第七批·取树重试 + 恢复（DroidRun `fetch_state_with_retry` 的手机内版）：
     * [dumpUiTreeAttempt] 是单次尝试；这里只在“瞬时读不到”时重试，仍失败
     * 就返回最后一次的结果、绝不抛错，让调用方按“树不够用”走现有降级
     * （截图/OCR/带图问模型），不要直接判死整轮任务。
     *
     * 只重试这三种瞬时情况（见 [isDumpUsable]）：
     *   - 单次尝试抛错（无障碍层瞬时异常，如取根节点失败）；
     *   - 返回缺关键字段（连 `nodes_useful` 都没有，如 no active window 的
     *     `{empty, error}`）；
     *   - 树里一个节点都没有（`nodes_useful`==0 且 `nodes_total`==0、未被
     *     扣留）——窗口还没出现 / 正在过渡，值得再等一等。
     * 不重试“稳定的盲树”：`nodes_total`>0（自绘界面/WebView，层级在、内容
     * 在像素里）或 `withheld`（应用扣留了树）时重试也读不到，白白烧时间，
     * 直接把结果交给调用方走截图/OCR。
     *
     * 参数（DroidRun 是 7 次、退避 [1,2,3,5,8,10] 秒、总计约 29 秒；手机内
     * 单步观察不能这么拖——单次尝试里已有 1500ms settle 轮询，每多一次
     * 尝试都是一次全量重读）：
     *   - 最多 [DUMP_RETRY_ATTEMPTS] 次尝试（首次 + 2 次重试）；
     *   - 退避 400ms、800ms（共 ≤1200ms）；
     *   - 单次调用最坏约 6 秒：3 次尝试 × 每试至多 1500ms settle + 1200ms
     *     退避（触发重试时树里一个节点都没有，walk 本身极快，不会撞上
     *     8000ms 的 [TREE_BUDGET_MS]；walk 被预算截断又返回 0 节点的极端
     *     情况除外，那时单试就可能吃满预算——今天不重试时单次调用也一样）。
     * 重试在单次观察内完成，不占 TaskLoop 的 25 步额度。
     *
     * 诊断字段（只在重试过时才附加，现有字段一字不动）：
     * `dump_retries` = 实际重试次数；`dump_last_error` = 最后一次抛错的
     * 信息（只在抛过错时有）。TaskLoop 读它写“重试 N 次仍失败”的历史。
     */
    fun dumpUiTree(
        verbose: Boolean = false,
        withShot: Boolean = false,
        capture: (String) -> JSONObject = { screenshot(it) },
    ): JSONObject {
        var last: JSONObject? = null
        var lastError: Exception? = null
        var attempts = 0
        while (attempts < DUMP_RETRY_ATTEMPTS) {
            attempts++
            val out = try {
                dumpUiTreeAttempt(verbose, withShot, capture)
            } catch (e: Exception) {
                lastError = e
                null
            }
            if (out != null) {
                last = out
                lastError = null
                if (isDumpUsable(out)) {
                    if (attempts > 1) out.put("dump_retries", attempts - 1)
                    // 第九批·坐标契约：读到一张树就盖一次观察戳（尺寸与树的好坏无关，
                    // 只记“此刻的屏是这个尺寸”，供本轮坐标动作比对）。
                    noteObservedSize()
                    return out
                }
            }
            if (attempts < DUMP_RETRY_ATTEMPTS) {
                try {
                    Thread.sleep(DUMP_RETRY_BACKOFF_MS[attempts - 1])
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        // 重试耗尽：返回最后一次的结果（而不是抛错）；一次都没拿到结果时
        // 合成一张与正常空树同字段的 JSON，调用方的 optInt/optString 读法
        // 不变，也就不用再为“我到底拿到没有”写分支。
        val out = last ?: JSONObject()
            .put("empty", true)
            .put("error", "dump failed: ${lastError?.message ?: "unknown"}")
            .put("nodes_total", 0)
            .put("nodes_useful", 0)
            .put("pkg", "")
            .put("elements", "")
            .put("compact", "")
        out.put("dump_retries", attempts - 1)
        if (lastError != null) {
            out.put("dump_last_error", lastError.message ?: lastError.javaClass.simpleName)
        }
        // 第九批·坐标契约：重试耗尽也盖戳——树没读到不代表尺寸未知，尺寸是
        // 另一路读的；盖了戳，本轮按截图/OCR 坐标点的动作仍有尺寸可比对。
        noteObservedSize()
        return out
    }

    /**
     * 这张树是不是“够格交给调用方”的结果——够格不等于“有可点元素”：
     * 稳定的盲树（自绘/扣留）一样够格，调用方走截图/OCR；只有“瞬时
     * 什么都没读到”才不够格、值得 [dumpUiTree] 再试一次。
     */
    private fun isDumpUsable(out: JSONObject): Boolean {
        val useful = out.optInt("nodes_useful", -1)
        // 关键字段缺失：连 nodes_useful 都没有（如 no active window 的
        // {empty, error}），不是一张完整的树。
        if (useful < 0) return false
        if (useful > 0) return true
        // useful == 0：有层级（nodes_total > 0）或被扣留（withheld）是稳定
        // 的盲树——重试也读不到，走截图/OCR 才是正路，不重试。
        if (out.optBoolean("withheld", false)) return true
        if (out.optInt("nodes_total", 0) > 0) return true
        // 一个节点都没有：窗口还没出现 / 正在过渡，瞬时情况，值得重试。
        return false
    }

    /**
     * Dump the active window's UI tree, with enough diagnostics that an empty
     * tree can say *why* it is empty.
     *
     * Three different failures look the same from the brain's side ("no nodes"),
     * and only one of them is fixable inside this app:
     *
     *   - `no active window` — the root is null; usually a transition, sometimes
     *     a window the app refuses to hand over.
     *   - `nodes_total` high, `nodes_useful` 0 — we can see the hierarchy but
     *     it carries no id/text/desc. Almost always an app that draws content
     *     itself (WebView page, Flutter/X5 pane, custom-rendered list, game).
     *     No flag fixes this; you have to look at pixels.
     *   - `nodes_total` low — the tree really is thin, or the content lives in
     *     a *different* window, which is why we go looking for one below.
     *
     * Anything an app draws into its own render surface (see [isRenderSurface])
     * is invisible to accessibility by construction — that is a property of the
     * Android view system, not of this service — so the answer there is never
     * "try harder", it's `take_screenshot` and tap by coordinate.
     *
     * 单次尝试本体：重试包装在 [dumpUiTree]，直接调这里会绕过重试。
     */
    private fun dumpUiTreeAttempt(
        verbose: Boolean = false,
        withShot: Boolean = false,
        capture: (String) -> JSONObject = { screenshot(it) },
    ): JSONObject {
        // Asking for the tree is the deliberate act of looking. It — and
        // screenshot() — are what clear the fling streak; the shot attached to
        // a fling itself does not, or the streak could never grow. See
        // [guardFlingStreak].
        synchronized(loopLock) { flingStreak = 0 }
        val ts = System.currentTimeMillis()
        val budget = WalkBudget(TREE_BUDGET_MS)
        val firstStart = SystemClock.uptimeMillis()
        var out = dumpOnce(verbose, budget)
        val firstMs = SystemClock.uptimeMillis() - firstStart
        var waited = 0L
        while (!verbose && out.optInt("nodes_useful", 0) == 0 && waited < SETTLE_BUDGET_MS && !budget.spent()) {
            val age = System.currentTimeMillis() - lastContentEventMs.get()
            if (lastContentEventMs.get() != 0L && age >= QUIET_WINDOW_MS) break
            try { Thread.sleep(SETTLE_POLL_MS) } catch (_: InterruptedException) { break }
            waited += SETTLE_POLL_MS
            out = dumpOnce(verbose, budget)
        }
        val helped = !verbose && out.optBoolean("withheld") && TreeHelper.wake(service)
        if (helped) out = awaitHelper(out)
        // Stability pass — the half the old loop never covered: "has content"
        // is not "has stopped moving". A screen mid-animation (list settling,
        // pager sliding, splash fading) dumps real elements at positions that
        // are already stale by the time the brain acts on them. Re-dump until
        // two consecutive passes agree on the tree signature, capped tight:
        // this runs on EVERY dump, so the cost when the screen was already
        // still is exactly one extra pass — and that pass is also what
        // guarantees the eid index the brain is about to read was built from
        // the settled geometry, not a mid-flight one.
        //
        // Skipped for a slow app: each re-dump is another full walk, and a
        // re-dump cut short by the budget is kept out of `out` — a partial
        // tree must never replace a whole one just because it came second.
        if (!verbose && out.optInt("nodes_useful", 0) > 0 &&
            !out.optBoolean("walk_cut") && firstMs < STABLE_SKIP_MS
        ) {
            var prevSig = treeSignature(out)
            var stableTries = 0
            while (waited < SETTLE_BUDGET_MS && stableTries < STABLE_MAX_TRIES) {
                try { Thread.sleep(STABLE_POLL_MS) } catch (_: InterruptedException) { break }
                waited += STABLE_POLL_MS
                val again = dumpOnce(verbose, budget)
                if (again.optBoolean("walk_cut")) break
                val sig = treeSignature(again)
                if (sig == prevSig) {
                    out = again
                    break
                }
                prevSig = sig
                out = again
                stableTries++
            }
            if (stableTries >= STABLE_MAX_TRIES) {
                out.put("still_moving", true)
            }
        }
        if (helped) out.put("tree_helper_enabled", true)
        if (!verbose && out.optInt("nodes_useful", 0) == 0 && waited >= SETTLE_BUDGET_MS) {
            out.put("settling", true).put("settle_waited_ms", waited)
        }
        // Flat element list + eid index — the phone.sh contract: one line per
        // element, system-computed 0-1000 centers, model never does math.
        if (!verbose) rebuildIndexAndList(out)
        // Ahead of the shot block on purpose: `screenshot()` stamps its record
        // with [lastPkg], and the picture belongs to the screen we just read,
        // not to the one before it.
        // M30: 空 dump 时把 lastDumpPkg 置空，不保留旧包名——否则后来的 tap 会
        // 拿上一个应用的包当目标包校验。
        lastDumpPkg = out.optString("pkg").takeIf { it.isNotEmpty() }
        // Evidence for [reviveNote], recorded only on a dump that actually
        // worked. Our own package is excluded: our Activities read fine and
        // would be recorded as known-good, which is true and useless — nobody
        // is going to ask the user to restart the service so we can read
        // ourselves.
        if (!verbose && out.optInt("nodes_useful", 0) > 0) {
            out.optString("pkg")
                .takeIf { it.isNotEmpty() && it != service.packageName }
                ?.let { goodDumps[it] = ts }
        }
        if (!verbose) {
            GroundingLog.noteTree(
                pkg = out.optString("pkg").takeIf { it.isNotEmpty() },
                total = out.optInt("nodes_total", 0),
                useful = out.optInt("nodes_useful", 0),
                // Lines, not characters: "did the brain have anything to tap"
                // is the only question the offline filter asks of this.
                elements = out.optString("elements").let {
                    if (it.isEmpty()) 0 else it.count { c -> c == '\n' } + 1
                },
                surface = out.optString("surface").takeIf { it.isNotEmpty() },
            )
        }
        // Tell the brain which way the screen is facing: a mid-task rotation
        // (keyboard attached, user flips the tablet) invalidates every
        // coordinate and geometric prior it is holding — the least we owe it
        // is the fact. Coordinates in our 0-1000 space are still correct
        // (they are relative to the CURRENT screen), but "the comment bubble
        // sits left of the button" style priors are portrait-only knowledge.
        runCatching {
            val rot = service.resources.configuration.orientation
            out.put("orientation", if (rot == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait")
        }
        // A picture, but only when it is worth something. With a populated
        // element list it is not: the list already says what is tappable and
        // where, in the same 0-1000 space, for a fraction of the tokens — so
        // `with_shot` defaults to false and the brain has to ask.
        //
        // The exception is the case the list cannot serve. An empty list means
        // this pane draws its own pixels, and there the image is not a bonus
        // next to the list, it IS the observation — the brain would otherwise
        // have to spend a second round trip on take_screenshot to learn
        // anything at all about the screen it is standing on. `shot_auto`
        // marks the ones it did not ask for.
        //
        // Also empty in effect: a dump that came back describing US. Our
        // overlays are hidden from accessibility outright and the window sweep
        // refuses our package, so this should no longer be reachable through
        // those two routes — but `rootInActiveWindow` can still be one of our
        // own Activities (Html/Scan/Look), which are ordinary app windows and
        // legitimately have a tree. It is our tree, not the brain's business,
        // so treat it as no tree and give it the picture instead.
        //
        // Ordered AFTER the index build so the image and the e-numbers
        // describe the same screen state. `capture` rather than `screenshot()`
        // so the caller can blank our overlay first — see CommandDispatcher.
        val treeEmpty = out.optInt("nodes_useful", 0) == 0 ||
            out.optString("pkg") == service.packageName
        // A walk the budget cut short is the same case in part: whatever it
        // did not reach exists only in the pixels.
        val walkCut = out.optBoolean("walk_cut")
        if (!verbose && (withShot || treeEmpty || walkCut)) {
            val reason = when {
                withShot -> "with_shot"
                treeEmpty -> "auto_empty"
                else -> "auto_partial"
            }
            runCatching { out.put("shot", capture(reason)) }
                .onFailure { out.put("shot_error", it.message ?: "capture failed") }
            if (!withShot) out.put("shot_auto", true)
        }
        out.put("ts", ts)
        annotate(out, verbose)
        return out
    }

    /**
     * Re-dump after [TreeHelper] switched the system's helper on. The setting
     * is written at once but the service binds asynchronously, and the app
     * only hands over its tree once it has — so this polls rather than sleeps.
     * Its own budget: the wait is not walking, and the first dump may already
     * have spent most of [TREE_BUDGET_MS].
     */
    private fun awaitHelper(first: JSONObject): JSONObject {
        // 批九：原版在这里往 Andee 的聊天记录里加一行「已开启树助手」提示，
        // 本工程无此设施，删去；轮询重读逻辑原样保留。
        var out = first
        val until = SystemClock.uptimeMillis() + HELPER_WAIT_MS
        while (SystemClock.uptimeMillis() < until) {
            try { Thread.sleep(HELPER_POLL_MS) } catch (_: InterruptedException) { break }
            out = dumpOnce(false, WalkBudget(TREE_BUDGET_MS))
            if (!out.optBoolean("withheld")) break
        }
        return out
    }

    /**
     * Signature of a dump's content for the stability pass: which nodes
     * exist, their ids and their bounds. Bounds are the part that matters —
     * a list mid-settle keeps the same nodes while their coordinates slide,
     * and the brain taps coordinates. Text is deliberately included: content
     * streaming in (images loading, counters ticking) is also "not settled".
     */
    private fun treeSignature(out: JSONObject): String {
        val sb = StringBuilder(256)
        val nodes = ArrayList<Pair<Int, JSONObject>>()
        collectNodes(out, nodes, 0)
        for ((_, n) in nodes) {
            sb.append(n.optString("id")).append('|')
                .append(n.optString("bounds")).append('|')
                .append(n.optString("text")).append('\n')
        }
        return sb.toString()
    }

    private fun dumpOnce(verbose: Boolean, budget: WalkBudget): JSONObject {
        val active = activeRoot()
        val out = if (active != null) {
            try {
                val stats = Stats(budget)
                val body = buildNode(active, verbose, stats) ?: JSONObject().put("empty", true)
                withStats(body, active.packageName?.toString() ?: "", stats).also {
                    if (isWithheld(active)) it.put("withheld", true)
                }
            } finally {
                active.recycle()
            }
        } else {
            JSONObject().put("empty", true).put("error", "no active window")
        }

        // Nothing usable and we may simply be looking at the wrong window: a
        // lot of Chinese apps host their real content in a second application
        // window (WeChat's mini-program / web-view pannes do exactly this).
        // Also entered when the "active" window turned out to be a status-bar
        // or other system shell during an app transition.
        // M31: 只有 0 才查别的窗口太苛刻——≤3 个可用节点（基本等于盲树）时也查。
        if (!verbose && out.optInt("nodes_useful", 0) <= BLIND_TREE_NODES && !budget.spent()) tryOtherWindows(out, budget)
        // A status bar (SystemUI) can be the active window mid-transition and
        // hand us ~27 "useful" status icons — real nodes, wrong screen. The
        // user-facing app is always a better source when it has ANY content.
        // Only run this correction when we did NOT just come from
        // tryOtherWindows with a genuinely better window.
        // M31: 输入法窗口占优时同理——键盘弹起时 dump 读到的是键盘的键，不是应用。
        if (!verbose && (out.optString("pkg") == "com.android.systemui" || activeWindowIsIme()) && !budget.spent()) tryOtherWindows(out, budget)

        return out
    }

    /** 当前顶在前面的窗口是不是输入法窗口（键盘弹起时它会占优）。 */
    private fun activeWindowIsIme(): Boolean {
        val windows = runCatching { service.windows }.getOrNull() ?: return false
        try {
            return windows.any {
                runCatching { it.type }.getOrNull() == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    (runCatching { it.isActive }.getOrDefault(false) ||
                        runCatching { it.isFocused }.getOrDefault(false))
            }
        } finally {
            windows.forEach { runCatching { it.recycle() } }
        }
    }

    /**
     * The app handed accessibility a root with no class, no size and no
     * children — a placeholder, not a pane.
     *
     * Measured on WeChat 8.0.78 (Xiaomi Pad 5, 2026-10-08): the system's own
     * `uiautomator dump` got the same single blank node with **no**
     * accessibility service enabled and a freshly started WeChat, while
     * Settings dumped 89 nodes. With MIUI's `MiuiEnhanceTBService` enabled
     * alongside us the same screen dumped 219 nodes, and removing it blanked the
     * root again — reproducible both ways, and the real "有时候可以有时候不行".
     * So it is the app refusing every client, not our
     * connection going stale — and the two must not share a hint, because the
     * stale one sends the user to toggle Andee, which cannot help here.
     * Occlusion (our card) and self-drawn panes are both distinguishable: they
     * keep real bounds and their containers.
     */
    private fun isWithheld(root: AccessibilityNodeInfo): Boolean = runCatching {
        if (root.childCount != 0 || root.packageName?.toString() == service.packageName) return@runCatching false
        val r = android.graphics.Rect()
        root.getBoundsInScreen(r)
        r.isEmpty && root.className.isNullOrEmpty()
    }.getOrDefault(false)

    /**
     * Fallback for the case above: walk every window we can see and keep the
     * best one. Only ever entered when the active window gave us nothing, so the
     * extra work is rare.
     *
     * Some apps can expose a transient or empty application window while another
     * window owns the visible content. We therefore inspect all accessible
     * windows before concluding that the current pane has no usable nodes.
     *
     * Whatever we reject, we report: a per-window summary goes out when nothing
     * was found, so the next person debugging this (or the model) can see which
     * window existed and which one had content, instead of a bare `{}`.
     */
    private fun tryOtherWindows(out: JSONObject, budget: WalkBudget) {
        val windows = runCatching { service.windows }.getOrNull()
        if (windows == null) {
            out.put("windows_seen", 0)
            return
        }
        // Whether the currently-chosen window belongs to a user app (as
        // opposed to SystemUI). Set BEFORE the loop so the SystemUI-rejection
        // rule below can reference it.
        val outPkgIsApp = out.optString("pkg", "") !in setOf("", "com.android.systemui")
        val detail = JSONArray()
        var best: JSONObject? = null
        var bestUseful = out.optInt("nodes_useful", 0)
        try {
            for (w in windows) {
                if (budget.spent()) break
                val stats = Stats(budget)
                val root = runCatching { rootOf(w) }.getOrNull()
                // Read everything we need off the node *before* recycling it.
                val pkg = if (root != null) runCatching { root.packageName?.toString() }.getOrNull().orEmpty() else ""
                val body = if (root != null) {
                    try {
                        buildNode(root, false, stats)
                    } catch (t: Throwable) {
                        null
                    } finally {
                        root.recycle()
                    }
                } else {
                    null
                }
                detail.put(
                    JSONObject()
                        .put("title", w.title?.toString() ?: "")
                        .put("type", windowTypeName(w.type))
                        .put("active", w.isActive)
                        .put("focused", w.isFocused)
                        .put("layer", w.layer)
                        .put("root", root != null)
                        .put("nodes_total", stats.total)
                        .put("nodes_useful", stats.useful),
                )
                // C8（114 项 S8 的回归，修回 Andee）：接受任意类型窗口，按 nodes_useful
                // 排序采纳。微信把朋友圈真内容放在 system 型窗口，主应用窗口是空壳；
                // 只认 TYPE_APPLICATION 等于把屏幕上唯一一棵树扔掉。
                //
                // S8 的担忧改用包归属过滤：system 型窗口仅当它的 pkg 与当前应用一致
                // 时才采纳——键盘、后台应用的 system 窗口包名与当前应用不同，仍被排除；
                // 非 system 型窗口（application / ime 等）沿用 Andee 的做法，只按
                // nodes_useful 排序，不再要求 isActive || isFocused。
                //
                // Ranking: a user-app window with strictly more useful nodes
                // beats anything SystemUI — the status bar never stops "having
                // content" (clock, icons), so without this rule it wins every
                // app transition.
                val isSystemUi = pkg == "com.android.systemui"
                // Never us. [net.kuafuai.andee.ui.hideFromAccessibility] should
                // already have left our overlays with nothing to find, but this
                // sweep is the one place where being wrong is invisible: it
                // runs only when the real pane has no tree, which is exactly
                // when our own toolbar is the sole thing on screen with
                // `useful > 0`, so it would win and the brain would be handed
                // an element list of our buttons. Two independent guards for
                // the same fault because the failure is silent and the cost is
                // a comparison. Our own Activities (Html/Scan/Look) are real
                // app windows and are NOT overlays, so they are caught here and
                // only here.
                if (pkg == service.packageName) continue
                val winType = runCatching { w.type }.getOrNull()
                // 当前应用：这次 dump 的包优先，上次 dump 的包兜底；两个都拿不到
                // 时 system 窗口一律不采纳（包归属无法验证，宁可不要）。
                val currentPkg = out.optString("pkg", "").ifEmpty { lastDumpPkg.orEmpty() }
                val systemNotOurs = winType == AccessibilityWindowInfo.TYPE_SYSTEM &&
                    (currentPkg.isEmpty() || pkg != currentPkg)
                val beatsBest = stats.useful > bestUseful &&
                    (!isSystemUi || !outPkgIsApp)
                if (body != null && root != null && !systemNotOurs && beatsBest) {
                    bestUseful = stats.useful
                    best = withStats(body, pkg, stats, source = "other_window(${windowTypeName(w.type)})")
                }
            }
        } finally {
            windows.forEach { runCatching { it.recycle() } }
        }
        val b = best
        if (b != null) {
            for (k in ArrayList(out.keys().asSequence().toList())) out.remove(k)
            for (k in b.keys()) out.put(k, b[k])
            // Which window this came from matters: tapping a node found in a
            // background window still works (bounds are screen coordinates),
            // but the brain should know it was not the focused one.
            out.put("windows_seen", windows.size)
            return
        }
        out.put("windows_seen", windows.size)
        out.put("windows_detail", detail)
    }

    private fun windowTypeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "a11y_overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_divider"
        else -> "other($type)"
    }

    /**
     * Turn the pruned nested tree into (a) a fresh eid index and (b) the
     * element outline the brain actually reads — one line per element, in
     * document order, indented by depth:
     *
     *   e17|LinearLayout||[500,570]
     *     e18*|ImageView|#avatar|[62,523]
     *     e19*|TextView|好友3|[181,509]
     *     e24*|ImageView|#comment_btn|[950,621]
     *
     * `*` marks clickable. The bracket pair is the element's center in
     * 0-1000 RELATIVE coordinates — same space tap_screen speaks — computed
     * by us from getBoundsInScreen, never by the model. Even when the model
     * ignores e-numbers and taps by coordinate, it copies a system-computed
     * value instead of eyeballing the screenshot.
     *
     * ## Why it is indented, and why that replaced useful-first sorting
     *
     * This list used to be sorted text-first, then clickable, then
     * top-to-bottom, so that a context-limit truncation ate the junk tail.
     * On any feed that ordering is fatal. A 朋友圈 page is five posts of
     * {avatar, name, body, photos, time, ⋯}; sorting scattered each post
     * across the list and stacked twenty label-less `ImageView` lines at the
     * bottom, identical to one another. "Like 好友3's post" was then not
     * merely hard but *unanswerable* from the list — the comment button that
     * belongs to that post is indistinguishable from the other four, so the
     * model can only guess, which is precisely the mis-tap [GroundingLog] was
     * built to collect.
     *
     * Indentation says what the sort threw away, and says it adjacently: the
     * lines of one post are the lines under one container. Measured on a
     * five-post feed it costs about 20% more characters than the sorted flat
     * list; carrying the nested JSON instead — the other way to keep the
     * structure — costs 170% more for the same information, nearly all of it
     * braces and repeated field names. So the tree comes back, rendered
     * cheaply rather than as JSON.
     *
     * Containers earn their line here. An unlabeled `LinearLayout` is worth
     * nothing on its own, but it is the anchor the indentation hangs off, and
     * it is what separates one post from the next — which is why [trim] drops
     * leaves and never containers.
     *
     * The truncation argument that justified sorting is answered by
     * [MAX_ELEMENT_LINES] instead: we do the cutting ourselves, worst-first,
     * and say so in `elements_dropped`, rather than leaving it to whatever
     * the model's context happens to clip.
     */
    /** S6: 索引重建与 [lookupEid] 用同一把 dumpLock 互斥，不读半建成的索引。 */
    private fun rebuildIndexAndList(out: JSONObject) {
        synchronized(dumpLock) { rebuildIndexAndListLocked(out) }
    }

    private fun rebuildIndexAndListLocked(out: JSONObject) {
        // S5: 新代际——旧 e 号随即作废；清表与加代际在同一把 eidLock 里完成。
        val epoch = synchronized(eidLock) {
            eidEpoch += 1
            eidIndex.clear(); sidIndex.clear()
            eidEpoch
        }
        val w = screenW
        val h = screenH
        if (w <= 0 || h <= 0) return

        val nodes = ArrayList<Pair<Int, JSONObject>>()
        collectNodes(out, nodes, 0)

        // Pass 1: document order is the final order — no sort. Nodes whose
        // bounds we cannot parse are skipped; their children keep their own
        // depth, so the gap shows up as a missing indent level rather than as
        // a reparented subtree.
        val rows = ArrayList<Row>(nodes.size)
        for ((depth, n) in nodes) {
            val bounds = n.optString("bounds", "")
            if (bounds.isEmpty()) continue
            val parts = bounds.split(",")
            if (parts.size != 4) continue
            val l = parts[0].toIntOrNull() ?: continue
            val t = parts[1].toIntOrNull() ?: continue
            val r = parts[2].toIntOrNull() ?: continue
            val b = parts[3].toIntOrNull() ?: continue
            if (r <= l || b <= t) continue
            rows.add(
                Row(
                    depth = depth,
                    px = intArrayOf((l + r) / 2, (t + b) / 2),
                    node = n,
                    bounds = bounds,
                    label = labelOf(n),
                    clickable = n.optBoolean("clickable", false),
                    container = n.optJSONArray("children") != null,
                )
            )
        }

        val dropped = trim(rows)

        // Pass 2: number in document order, index, render. Numbering comes
        // after the trim so the e-numbers the model reads are contiguous —
        // a gap would look like a stale list and send it back for a re-dump.
        val pkg = out.optString("pkg", "")
        val lines = ArrayList<String>(rows.size)
        for ((eidNum, row) in rows.withIndex()) {
            val eidLabel = "e$eidNum"
            val mark = if (row.clickable) "*" else ""
            val cls = row.node.optString("class", "View")
            val normCx = row.px[0] * NORM_MAX / w
            val normCy = row.px[1] * NORM_MAX / h
            synchronized(eidLock) {
                eidIndex[eidNum] = EidEntry(
                    px = row.px,
                    pkg = pkg,
                    viewId = row.node.optString("id").ifEmpty { null },
                    bounds = row.bounds,
                    epoch = epoch,
                    label = row.label,
                )
            }
            val indent = "  ".repeat(minOf(row.depth, MAX_INDENT))
            lines.add("$indent$eidLabel$mark|$cls|${row.label}|[$normCx,$normCy]")
        }

        // Replace the nested tree with the outline — the JSON was only ever
        // scaffolding for this. Keep the stats fields, drop the bulk.
        val keep = setOf(
            "ts", "pkg", "nodes_total", "nodes_kept", "nodes_useful", "surface",
            "source", "windows_seen", "windows_detail", "settling", "settle_waited_ms",
            "error", "empty", "withheld", "tree_helper_enabled",
        )
        val rootKeys = ArrayList(out.keys().asSequence().toList())
        for (k in rootKeys) if (k !in keep) out.remove(k)
        if (lines.isNotEmpty()) out.put("elements", lines.joinToString("\n"))
        if (dropped > 0) out.put("elements_dropped", dropped)

        // 紧凑表（Phase 2）：只列可操作元素，一行一个，稳定编号 + 语义类型 +
        // 文字 + 中心坐标。同一批 rows、同一遍走，不另扫树。elements 的格式和
        // 编号原样不动，FlowRuntime 的 find/守卫继续按 elements 匹配。
        val compact = buildCompactList(rows, pkg, w, h, epoch)
        if (compact.lines.isNotEmpty()) out.put("compact", compact.lines.joinToString("\n"))
        if (compact.dropped > 0) out.put("compact_dropped", compact.dropped)
    }

    /** 紧凑表的一行 + 被截掉的个数。 */
    private class CompactResult(val lines: List<String>, val dropped: Int)

    /**
     * 从同一批 [rows] 里筛出可操作元素，生成紧凑表。稳定编号取自元素的
     * 识别特征（resourceId + 类名 + 文字 + 取整后的 bounds），同一元素在
     * 同一屏态下每轮同一个编号；编号冲突时加 -2、-3 区分。
     */
    private fun buildCompactList(rows: List<Row>, pkg: String, w: Int, h: Int, epoch: Long): CompactResult {
        val lines = ArrayList<String>()
        var dropped = 0
        val usedSids = HashSet<String>()
        for (row in rows) {
            if (!isCompactActionable(row)) continue
            if (lines.size >= MAX_COMPACT_LINES) {
                dropped++
                continue
            }
            val cls = row.node.optString("class", "View")
            val type = compactType(
                cls,
                row.clickable,
                row.node.optBoolean("scrollable", false),
                row.node.optBoolean("checked", false),
                row.node.optBoolean("focused", false),
            )
            var sid = stableId(row.node.optString("id").ifEmpty { null }, cls, row.label, row.bounds)
            if (sid in usedSids) {
                var n = 2
                while ("$sid-$n" in usedSids) n++
                sid = "$sid-$n"
            }
            usedSids.add(sid)
            val normCx = row.px[0] * NORM_MAX / w
            val normCy = row.px[1] * NORM_MAX / h
            synchronized(eidLock) {
                sidIndex[sid] = EidEntry(
                    px = row.px,
                    pkg = pkg,
                    viewId = row.node.optString("id").ifEmpty { null },
                    bounds = row.bounds,
                    epoch = epoch,
                    label = row.label,
                )
            }
            lines.add("$sid|$type|${row.label}|[$normCx,$normCy]")
        }
        return CompactResult(lines, dropped)
    }

    /** 紧凑表只收能动手的元素：可点、可滑、可勾、已聚焦，或本身就是输入/开关类控件。 */
    private fun isCompactActionable(row: Row): Boolean {
        if (row.clickable) return true
        val n = row.node
        if (n.optBoolean("scrollable", false)) return true
        if (n.optBoolean("checked", false)) return true
        if (n.optBoolean("focused", false)) return true
        val cls = n.optString("class", "")
        return cls.contains("EditText") || cls.contains("CheckBox") || cls.contains("RadioButton") ||
            cls.contains("Switch") || cls.contains("SeekBar") || cls.contains("Spinner") ||
            cls.contains("RatingBar")
    }

    /** 类名 → 紧凑表的语义类型；认不出的可点物按 Button、可滑物按 Scroll，其余按 View。 */
    private fun compactType(
        cls: String,
        clickable: Boolean,
        scrollable: Boolean,
        checked: Boolean,
        focused: Boolean,
    ): String = when {
        cls.contains("EditText") -> "Input"
        cls.contains("CheckBox") || cls.contains("RadioButton") || cls.contains("Switch") ||
            cls.contains("ToggleButton") -> "Checkbox"
        cls.contains("ImageButton") -> "Button"
        cls.contains("Button") -> "Button"
        cls.contains("ImageView") -> "Image"
        cls.contains("TextView") -> "Text"
        cls.contains("ListView") || cls.contains("RecyclerView") || cls.contains("GridView") -> "List"
        cls.contains("ScrollView") || cls.contains("NestedScrollView") || cls.contains("ViewPager") -> "Scroll"
        cls.contains("SeekBar") || cls.contains("RatingBar") || cls.contains("Spinner") -> "Input"
        cls.contains("WebView") -> "Text"
        scrollable -> "Scroll"
        clickable -> "Button"
        focused -> "Input"
        checked -> "Checkbox"
        else -> "View"
    }

    /**
     * 稳定编号：MD5(资源 id | 类名 | 文字 | 取整 bounds）的前 6 位 hex，
     * 前缀 s 与 e 数字编号区分。bounds 每边取整到 20px，容忍布局小抖动；
     * 元素滚动移位后 bounds 变了、编号跟着变——编号稳的是同一屏态，不是跨滚动。
     */
    private fun stableId(viewId: String?, cls: String, label: String, bounds: String): String {
        val parts = bounds.split(",")
        val rounded = if (parts.size == 4) {
            parts.map { (it.toIntOrNull() ?: 0).let { v -> (v + 10) / 20 * 20 } }.joinToString(",")
        } else bounds
        val raw = listOf(viewId.orEmpty(), cls, label, rounded).joinToString("|")
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(raw.toByteArray(Charsets.UTF_8))
        val hex = digest.take(3).joinToString("") { "%02x".format(it) }
        return "s$hex"
    }

    /** One candidate line, before numbering. */
    private class Row(
        val depth: Int,
        val px: IntArray,
        val node: JSONObject,
        val bounds: String,
        val label: String,
        val clickable: Boolean,
        val container: Boolean,
    )

    /**
     * Bring [rows] under [MAX_ELEMENT_LINES], worst-first, and report how many
     * went. Only decorative leaves are eligible — no label, nothing to tap,
     * no children to group — because they are the one kind of line whose
     * removal costs the model nothing. Containers are never dropped even
     * though they are usually label-less: the indent level they provide is
     * what keeps two adjacent posts from reading as one.
     *
     * If that is not enough the tail goes, which on a screen read in document
     * order means the bottom of it — recoverable by scrolling, unlike a
     * mis-association, which is not recoverable at all.
     */
    private fun trim(rows: ArrayList<Row>): Int {
        if (rows.size <= MAX_ELEMENT_LINES) return 0
        val before = rows.size
        var over = rows.size - MAX_ELEMENT_LINES
        val it = rows.listIterator(rows.size)
        while (it.hasPrevious() && over > 0) {
            val r = it.previous()
            if (!r.container && r.label.isEmpty() && !r.clickable) {
                it.remove()
                over--
            }
        }
        if (rows.size > MAX_ELEMENT_LINES) {
            rows.subList(MAX_ELEMENT_LINES, rows.size).clear()
        }
        return before - rows.size
    }

    /**
     * What to print in the label column: the text, else the description, else
     * the tail of the view id with a `#` on it.
     *
     * The id fallback is the fix for a whole class of blind spot. 朋友圈's
     * `⋯`, the avatars and the photos are all `ImageView`s with neither text
     * nor description, so the column came out empty and the model was told
     * (by HARD RULE 2, in so many words) to tap them by number without
     * knowing what they were. The node carried
     * `com.tencent.mm:id/comment_btn` the whole time — we were dropping it
     * when building the line. The `#` is not decoration: it marks the value
     * as a resource name rather than something written on the screen, which
     * the model would otherwise be entitled to read back to the user.
     */
    private fun labelOf(n: JSONObject): String {
        n.optString("text").takeIf { it.isNotEmpty() }?.let { return oneLine(it) }
        n.optString("desc").takeIf { it.isNotEmpty() }?.let { return oneLine(it) }
        n.optString("id").takeIf { it.isNotEmpty() }
            ?.let { return "#" + oneLine(it.substringAfterLast('/')) }
        return ""
    }

    /**
     * The outline is one element per line with `|` between fields, so a label
     * containing either character tears the row in half — and a multi-line
     * TextView (a 朋友圈 body, a long notification) contains newlines as a
     * matter of course. Neither was escaped before; the rows it produced were
     * unparseable and the model had to guess where the next element started.
     */
    private fun oneLine(s: String): String =
        s.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim()

    /** Collect every node in the (pruned) tree, depth-first, with its depth. */
    private fun collectNodes(node: JSONObject, sink: MutableList<Pair<Int, JSONObject>>, depth: Int) {
        sink.add(depth to node)
        val kids = node.optJSONArray("children") ?: return
        for (i in 0 until kids.length()) {
            val kid = kids.optJSONObject(i) ?: continue
            collectNodes(kid, sink, depth + 1)
        }
    }

    /** Main-thread code only; see [Stats] for why these numbers matter. */
    private fun withStats(
        body: JSONObject,
        pkg: String,
        stats: Stats,
        source: String? = null,
    ): JSONObject {
        body.put("pkg", pkg)
        body.put("nodes_total", stats.total)
        body.put("nodes_kept", stats.kept)
        body.put("nodes_useful", stats.useful)
        stats.surface?.let { body.put("surface", shortenClass(it, false)) }
        if (stats.budget.cut) body.put("walk_cut", true)
        if (source != null) body.put("source", source)
        return body
    }

    /**
     * What one dump pass saw, so the caller can say why a tree is useless
     * instead of just handing back `{}`:
     *
     *   [total]  — nodes visited, before any pruning
     *   [kept]   — nodes that survived pruning
     *   [useful] — nodes carrying something you can act on (text / desc / id)
     *   [surface]— first self-rendering view found, if any
     */
    private class Stats(val budget: WalkBudget = WalkBudget.UNLIMITED) {
        var total = 0
        var kept = 0
        var useful = 0
        var surface: String? = null
    }

    /**
     * Wall-clock cap on one tree walk. Every `getChild` is a binder round trip
     * answered on the *target app's* UI thread, so an app busy animating
     * answers each one late — 红果's 福利 page measured 551 nodes in 135 s,
     * ~245 ms a node — and a walk with no cap outlives the brain's tool deadline while
     * still holding the app's attention. A cut walk reports itself
     * (`walk_cut`) so the caller can say the list is partial, not absent.
     */
    private class WalkBudget(ms: Long) {
        private val until = if (ms == Long.MAX_VALUE) Long.MAX_VALUE else SystemClock.uptimeMillis() + ms
        var cut = false
            private set

        fun spent(): Boolean {
            if (!cut && SystemClock.uptimeMillis() >= until) cut = true
            return cut
        }

        companion object {
            val UNLIMITED get() = WalkBudget(Long.MAX_VALUE)
        }
    }

    /**
     * API 33+ lets one round trip carry up to 50 descendants back with the
     * node asked for. `UNINTERRUPTIBLE` is the half that matters for a walk:
     * by default the app abandons the prefetch the moment our next request
     * arrives, and a depth-first walk sends the next request immediately —
     * so most nodes came back one IPC each. Older versions keep the plain call.
     */
    private fun childOf(node: AccessibilityNodeInfo, i: Int): AccessibilityNodeInfo? =
        if (android.os.Build.VERSION.SDK_INT >= 33) node.getChild(i, PREFETCH_WALK) else node.getChild(i)

    private fun activeRoot(): AccessibilityNodeInfo? =
        if (android.os.Build.VERSION.SDK_INT >= 33) service.getRootInActiveWindow(PREFETCH_WALK)
        else service.rootInActiveWindow

    private fun rootOf(w: AccessibilityWindowInfo): AccessibilityNodeInfo? =
        if (android.os.Build.VERSION.SDK_INT >= 33) w.getRoot(PREFETCH_WALK) else w.root

    /**
     * Tell the brain what it is looking at when there is nothing to act on, in
     * its language rather than ours: not "empty tree" but "this is a surface you
     * have to read with your eyes". The empty-tree branch carries the
     * generic_bridge NO_TREE policy verbatim in spirit: prefer back/home over
     * blind coordinate taps — a wrong exit costs one tap, a wrong tap can cost
     * the task.
     *
     * Every branch here fires exactly where [dumpUiTree] attaches a screenshot
     * unasked, so the hints point at the image already in hand rather than
     * sending the brain off to `take_screenshot` for a picture of the same
     * screen it was just given. Called after the shot block for that reason —
     * if the capture failed, say so instead of promising an image that is not
     * there.
     *
     * The branches are not all the same verdict. Only the first four are
     * genuinely empty; the last has nodes that simply have no names, and since
     * [rebuildIndexAndList] started rendering nesting and centres for every
     * node rather than sorting the labelled ones to the top, that case still
     * has a usable outline and must not be told to tap by coordinate.
     *
     * [reviveNote] is appended to the two genuinely-empty branches only — the
     * two that mean "no nodes AT ALL" and have no other explanation. That is
     * the whole discrimination: a self-drawn pane still reports its containers,
     * so a mini-program never reaches those branches and never nags the user
     * about restarting anything. The two branches ahead of them are the cases
     * where a window of *ours* explains the zero, and they must not carry it —
     * that note asks for a permission toggle, and asking for one when nothing
     * is wrong is how this last went wrong.
     */
    private fun annotate(out: JSONObject, verbose: Boolean) {
        if (verbose) return
        val total = out.optInt("nodes_total", 0)
        val useful = out.optInt("nodes_useful", 0)
        // "Look at the attached image" vs "go fetch one" — never both.
        val eyes = if (out.has("shot")) {
            "the screenshot attached to this response (do not call take_screenshot " +
                "for the same screen)"
        } else {
            "take_screenshot"
        }
        val revive = reviveNote()
        when {
            // Ours is the window in front — so the zero nodes are expected,
            // not a stale service. First because it otherwise falls into the
            // reviveNote branches and sends the user off to toggle a setting
            // that is working fine.（批九：判定由 Andee 的 OwnCard 状态改为
            // 「dump 的 pkg 就是 Jarvis 自己」。）
            total == 0 && out.optString("pkg") == service.packageName ->
                out.put(
                    "hint",
                    "Jarvis's own interface is in front (the dump describes Jarvis itself), so " +
                        "there is no other app's tree to read. Nothing is broken. The user has " +
                        "to switch to the target app first — say so in plain words if the task " +
                        "needs another app. Do not tap blindly at Jarvis's own UI.",
                )

            // 批九：原版这里还有一个 FullscreenCard（Andee 自家全屏卡片遮挡下方 App）
            // 的分支，本工程没有这种卡片，整支删去。

            // Ahead of the two [reviveNote] branches: the app worked before,
            // so they would send the user off to toggle Andee — which is
            // exactly what they did, to no effect. See [isWithheld].
            useful == 0 && out.optBoolean("withheld") -> {
                val app = out.optString("pkg").ifEmpty { "This app" }
                val helper = if (out.optBoolean("tree_helper_enabled")) {
                    "Jarvis has just switched that helper on itself and the app has not picked " +
                        "it up yet — the next dump may read normally, so retry once before " +
                        "giving up on the list; if it is still blank, the user may need to " +
                        "reopen the app. "
                } else {
                    "Jarvis could not switch it on here, and the user cannot either: it is a " +
                        "hidden companion MIUI only runs alongside TalkBack, and TalkBack " +
                        "would read the whole screen aloud and change how every tap works. So " +
                        "do not send the user to look for any setting — if it matters, say " +
                        "once that in this app you are working from the picture, and carry on. "
                }
                out.put(
                    "hint",
                    "`$app` is withholding its UI from accessibility: its window hands every " +
                        "accessibility client — the system's own uiautomator included — a blank " +
                        "placeholder with no size and no children. That is the app's choice, not a " +
                        "fault on this device; switching Jarvis off and on does not change it, so " +
                        "do not ask the user to. What it does depend on is which *other* " +
                        "accessibility services are on: WeChat was measured exposing its full " +
                        "tree only while MIUI's TalkBack helper (MiuiEnhanceTalkback) was also " +
                        "enabled. " + helper + "It covers " +
                        "this whole app, not just this screen — so backing out to find a readable " +
                        "page will not help either. Work from $eyes: find the target in the image " +
                        "and use tap_by_coordinates / swipe_by_coordinates (0-1000), checking the " +
                        "result in the screenshot each tap returns. type_text and submit_input " +
                        "still work on a focused field.",
                )
            }

            out.has("error") && total == 0 ->
                out.put(
                    "hint",
                    "No window was handed to accessibility. Either the app is mid-transition " +
                        "(retry once) or this window refuses to expose itself. Judge by $eyes." +
                        revive,
                )

            useful == 0 && total == 0 ->
                // An empty root, not a filtered-away tree — and every other
                // accessible window was empty too (see [tryOtherWindows]).
                // Keep the conclusion scoped to this pane; never write off the
                // whole app because one screen has no semantic tree.
                out.put(
                    "hint",
                    "No accessibility nodes anywhere: the active window's root is empty and " +
                        "none of the ${out.optInt("windows_seen", 0)} other windows held content " +
                        "either. Almost always a pane that draws its own pixels — a " +
                        "mini-program, a WebView article, a game canvas — or, if `settling` is " +
                        "set, a screen still mid-transition. NO_TREE policy: judge by $eyes, " +
                        "and prefer back / home to exit and retry via a " +
                        "different route rather than blind coordinate taps. This is a property " +
                        "of this pane, not of the app: the same app's other screens very likely " +
                        "dump fine, so do not stop calling this tool on them." +
                        revive,
                )

            useful == 0 && out.has("surface") ->
                out.put(
                    "hint",
                    "Everything actionable here is inside ${out.optString("surface")}, which draws " +
                        "its own content and therefore has no accessibility nodes — ever, on any flag. " +
                        "NO_TREE policy: read the screen from $eyes, and prefer " +
                        "back / home to exit and re-enter via a different route over blind " +
                        "coordinate taps.",
                )

            useful == 0 && total > 0 ->
                // Not the same dead end as the branches above: the nodes exist,
                // they are merely nameless, so the outline still carries their
                // nesting and their centres. Aiming at an e-number there is
                // strictly better than a coordinate tap — the device resolves
                // the element's real centre, and the guard exempts e-taps.
                out.put(
                    "hint",
                    "$total nodes but none with text, desc or id: this app draws its own content, " +
                        "so every line in `elements` has an empty label. Read them anyway — the " +
                        "indentation still groups them and each line still carries its centre. " +
                        "Match what you see in $eyes against the outline and tap the `*` line " +
                        "sitting at that spot by e-number; drop to a coordinate tap only if no " +
                        "line covers it.",
                )
        }
        // Independent of the verdict above: a cut walk can still have produced
        // a perfectly good partial list, and the model needs both facts.
        if (out.optBoolean("walk_cut")) {
            out.put(
                "walk_note",
                "This app answered accessibility too slowly (it is busy animating), so reading " +
                    "stopped after ${TREE_BUDGET_MS / 1000}s with only part of the screen listed. " +
                    "The e-numbers above are real and tappable; anything you see in $eyes but not " +
                    "in the list was simply not reached — aim at it with tap_by_coordinates. " +
                    "Calling this tool again will be just as slow. Nothing is broken; do not tell " +
                    "the user you cannot see the screen.",
            )
        }
    }

    /**
     * The sentence appended when an app that **has worked on this device** is
     * suddenly handing back no nodes at all — see [goodDumps].
     *
     * Deliberately evidence and not an instruction. It states the two facts
     * only the device knows (this package read fine, that long ago) and what
     * the human would have to do; whether that is worth interrupting the user
     * over is the brain's call, because only the brain knows what the user
     * asked for and how far along it is. Hard-coding "if pkg == WeChat and the
     * tree is empty, tell the user to restart" would fire on every mini-program
     * WeChat hosts and train the user to ignore us.
     *
     * Empty string, not null, so the callers can concatenate unconditionally.
     *
     * Identity is best-effort by design: an empty dump usually has no `pkg` of
     * its own, so it falls back to the last package we read and then to the
     * accessibility event stream. Both are "the app we believe is in front" and
     * both can be one screen stale — which is why the note names the package it
     * is talking about instead of saying "this app", so a wrong guess is
     * visible to the brain rather than silently misattributed.
     *
     * Covers one shape of the fault and not the other. A stale connection
     * normally shows up as `rootInActiveWindow == null` — no nodes at all —
     * which is where this fires. If instead it were to hand back a root that
     * prunes down to nothing *useful*, that lands in the nameless-nodes branch
     * and gets no note, because there it is indistinguishable from a
     * mini-program: same package, same known-good history, same counts. Better
     * silent than nagging the user every time WeChat opens a mini-program. If
     * the other shape turns out to happen on a real device, it needs a sharper
     * signal than this one (`windows_seen == 0` is the obvious candidate) —
     * not a looser condition here.
     */
    private fun reviveNote(): String {
        val pkg = lastDumpPkg
            ?: (service as? JarvisAccessibilityService)?.foregroundPackage()?.takeIf { it.isNotEmpty() }
            ?: return ""
        if (pkg == service.packageName) return ""
        val seen = goodDumps[pkg] ?: return ""
        val mins = (System.currentTimeMillis() - seen) / 60_000
        val ago = if (mins < 1) "less than a minute ago" else "$mins minutes ago"
        return " THIS APP HAS WORKED HERE BEFORE: `$pkg` gave a usable " +
            "element list $ago, and is now returning no accessibility nodes at all. " +
            "A pane that draws its own pixels does not do that — it still reports its " +
            "containers. Zero nodes from an app that was readable is the signature of " +
            "our accessibility connection having gone stale, and no tool call can " +
            "repair it: only the person holding the tablet can, by opening 设置 → " +
            "无障碍 (Settings → Accessibility), switching Jarvis off and back on. That " +
            "takes about three seconds. Retry this tool once first in case the screen " +
            "was merely mid-transition; if it is still empty, say so to the user in " +
            "plain words and wait for them. Taps and swipes still work and you still " +
            "have the screenshot, so you are not stuck — but grinding on blind " +
            "coordinates is worth less than asking for a three-second toggle."
    }

    private fun runGesture(gesture: GestureDescription, timeoutMs: Long): JSONObject {
        screenMovedAtMs = SystemClock.uptimeMillis()
        val latch = CountDownLatch(1)
        var completed = false
        val ok = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    completed = true; latch.countDown()
                }

                override fun onCancelled(g: GestureDescription?) {
                    completed = false; latch.countDown()
                }
            },
            null,
        )
        if (!ok) throw IllegalStateException("dispatchGesture returned false")
        // M29: latch.await() 的返回值不能忽略——超时即抛，不返回假的 completed。
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("gesture timeout")
        }
        return JSONObject().put("completed", completed)
    }

    /**
     * Build one node's JSON. Bottom-up pruning:
     *   1. Recurse into children first (so we know how many survive).
     *   2. `hasReason` = has id/text/desc, or is clickable/scrollable/focused/
     *      checked, or is explicitly disabled, or is a render surface.
     *   3. keep if hasReason, OR if it has ≥ 2 surviving children (it's a
     *      grouping container that tells the LLM "these belong together").
     *   4. hoist if it has exactly 1 surviving child and no reason — return
     *      that child in place of self. Collapses deep layout wrapper chains.
     *   5. drop if it has 0 children and no reason — decorative leaves like
     *      Spacers, dividers, dots — unless it [looksLikeIcon].
     *
     * `verbose=true` disables all of this and emits every node with every
     * field, matching pre-pruning behavior.
     *
     * [stats] collects what was seen vs what survived, so the caller can tell
     * "there is no tree" apart from "we threw the tree away".
     */
    private fun buildNode(node: AccessibilityNodeInfo, verbose: Boolean, stats: Stats? = null): JSONObject? {
        // M32: 先递归子节点，父级自身再按规则剪枝——零尺寸/不可见的父级只丢
        // 自己，不丢孩子。旧版先判可见性/零尺寸再递归，整棵子树都被丢掉。
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val st = stats
        if (st != null) st.total++

        val kids = mutableListOf<JSONObject>()
        for (i in 0 until node.childCount) {
            if (st != null && st.budget.spent()) break
            val child = childOf(node, i) ?: continue
            try {
                buildNode(child, verbose, stats)?.let { kids.add(it) }
            } finally {
                child.recycle()
            }
        }

        if (!verbose && (!node.isVisibleToUser || bounds.width() <= 0 || bounds.height() <= 0)) {
            if (kids.isEmpty()) return null
            if (kids.size == 1) return kids[0]
            // 多个孩子：父级只留一个空壳挂孩子，不占 e 号（无 bounds 行会被跳过）。
            val shell = JSONObject()
            val arr = JSONArray()
            for (k in kids) arr.put(k)
            shell.put("children", arr)
            return shell
        }

        val id = node.viewIdResourceName
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val clickable = node.isClickable
        val focused = node.isFocused
        val scrollable = node.isScrollable
        val checked = node.isChecked
        val selected = node.isSelected
        val enabled = node.isEnabled
        val cls = node.className?.toString()
        val surface = if (isRenderSurface(cls)) cls else null

        val hasReason = !id.isNullOrEmpty() || !text.isNullOrEmpty() || !desc.isNullOrEmpty()
                || clickable || focused || scrollable || checked || selected || !enabled
                || surface != null

        if (!verbose) {
            // Lynx parks hidden panels at x≈4000 and still calls them visible;
            // an e-number there would send a tap past the screen edge.
            if (kids.isEmpty() && !Rect.intersects(bounds, Rect(0, 0, screenW, screenH))) return null
            if (!hasReason && kids.isEmpty() && !looksLikeIcon(bounds)) return null   // drop decorative leaf
            if (!hasReason && kids.size == 1) return kids[0]        // hoist single child
        }

        if (st != null) {
            st.kept++
            if (!id.isNullOrEmpty() || !text.isNullOrEmpty() || !desc.isNullOrEmpty()) st.useful++
            if (surface != null && st.surface == null) st.surface = surface
        }

        val obj = JSONObject()
        if (!cls.isNullOrEmpty()) obj.put("class", shortenClass(cls, verbose))
        // Surfaced even though it carries no text: it is the one thing that
        // tells the brain "don't wait for a tree here, you need the pixels".
        if (surface != null) obj.put("opaque", true)
        if (!id.isNullOrEmpty()) obj.put("id", id)
        if (!text.isNullOrEmpty()) obj.put("text", text)
        if (!desc.isNullOrEmpty()) obj.put("desc", desc)
        obj.put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
        if (verbose || clickable) obj.put("clickable", clickable)
        if (verbose || focused) obj.put("focused", focused)
        if (verbose || scrollable) obj.put("scrollable", scrollable)
        if (verbose || checked) obj.put("checked", checked)
        if (verbose || selected) obj.put("selected", selected)
        if (verbose || !enabled) obj.put("enabled", enabled)

        if (kids.isNotEmpty()) {
            val arr = JSONArray()
            for (k in kids) arr.put(k)
            obj.put("children", arr)
        }
        return obj
    }

    /**
     * A nameless, non-clickable leaf that is still worth an e-number: ad SDKs
     * draw their ✕ as a bare ImageView with no desc and the click handled by
     * a parent or a touch listener, so step 5 used to delete the one control
     * the brain was looking for and leave it aiming from a screenshot.
     * Icon-sized and roughly square keeps dots, dividers and banners out;
     * fully on-screen keeps out nodes caught mid-animation with bounds that
     * would hand the brain an e-number it cannot hit.
     */
    private fun looksLikeIcon(b: Rect): Boolean {
        if (b.left < 0 || b.top < 0 || b.right > screenW || b.bottom > screenH) return false
        val d = service.resources.displayMetrics.density
        val w = b.width() / d
        val h = b.height() / d
        if (minOf(w, h) < ICON_MIN_DP || maxOf(w, h) > ICON_MAX_DP) return false
        return maxOf(w, h) <= ICON_MAX_ASPECT * minOf(w, h)
    }

    /**
     * Views that draw their own content and therefore have no accessibility
     * children: a WebView page, a Flutter/X5 pane (WeChat mini-programs), a
     * game canvas, a video surface, Compose Canvas. Nothing inside them will
     * ever appear in a UI tree — no service flag changes that.
     *
     * We bother because knowing *that* is the difference between the brain
     * retrying get_screen_element forever and switching to pixels.
     */
    private fun isRenderSurface(cls: String?): Boolean {
        if (cls.isNullOrEmpty()) return false
        return cls.contains("WebView", ignoreCase = true)      // incl. X5 / XWeb / WKWebView-ish names
                || cls.contains("SurfaceView", ignoreCase = true)
                || cls.contains("TextureView", ignoreCase = true)
                || cls.contains("GLSurfaceView", ignoreCase = true)
                || cls.contains("Flutter", ignoreCase = true)
                || cls.contains("unity", ignoreCase = true)
                || cls.contains("cocos", ignoreCase = true)
                || cls.contains("CanvasView", ignoreCase = true)
    }

    /**
     * Strip the common android.* / androidx.* / android.view.* prefixes off
     * a class name so `android.widget.FrameLayout` becomes `FrameLayout`.
     * In verbose mode keep the fully-qualified name.
     */
    private fun shortenClass(cls: String?, verbose: Boolean): String? {
        if (cls == null) return null
        if (verbose) return cls
        val prefixes = arrayOf(
            "android.widget.", "android.view.", "android.webkit.",
            "androidx.compose.ui.platform.",
            "androidx.recyclerview.widget.", "androidx.viewpager.widget.",
            "androidx.appcompat.widget.", "androidx.constraintlayout.widget.",
        )
        for (p in prefixes) if (cls.startsWith(p)) return cls.substring(p.length)
        return cls
    }

    companion object {
        /** Full display size in the current rotation, system bars included — the space gestures and screenshots use. */
        fun displaySize(ctx: android.content.Context): android.graphics.Point {
            val b = ctx.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            return android.graphics.Point(b.width(), b.height())
        }

        // C9：ADBKeyboard 广播接口常量（Andee 原值，2026-11-05 恢复为受 guard 的兜底通道）。
        // action 是裸字符串，没有包名前缀——加了前缀就匹配不上它注册的 filter，广播静默消失。
        private const val ADB_KEYBOARD_PKG = "com.android.adbkeyboard"
        private const val ADB_INPUT_TEXT = "ADB_INPUT_TEXT"
        private const val ADB_CLEAR_TEXT = "ADB_CLEAR_TEXT"
        /** ADBKeyboard 在系统设置里的完整 IME id（包名 + 服务类名），用于核对它是否当前输入法。 */
        private const val BRAIN_IME = "com.android.adbkeyboard/.AdbIME"

        private const val READBACK_POLL_MS = 200L
        private const val READBACK_BUDGET_MS = 1500L

        // 批九：原版的 CLIPBOARD_LABELS / PASTE_LABELS / SCROLL_TAG 在本文件内
        // 本就未被使用（是 Andee 其他模块的常量），移植时移除。
        private const val SETTLE_POLL_MS = 250L
        private const val SETTLE_BUDGET_MS = 1500L

        /**
         * 第七批·取树重试：最多尝试次数（首次 + 2 次重试）与每次重试前的
         * 退避（首次重试前 400ms、第二次 800ms，共 ≤1200ms）。DroidRun 是
         * 7 次、退避 [1,2,3,5,8,10] 秒；手机内单步观察不能这么拖，单次
         * 尝试里已有 1500ms settle 轮询——3 次尝试已相当于 18 次以上的
         * dumpOnce 轮询，再多只是拖慢每一步。退避数组长度须是次数减一。
         */
        private const val DUMP_RETRY_ATTEMPTS = 3
        private val DUMP_RETRY_BACKOFF_MS = longArrayOf(400L, 800L)

        /**
         * Stability pass knobs — see the second loop in [dumpUiTree].
         * STABLE_POLL_MS is above a frame pair (2×16ms) but below a typical
         * list-settle ripple, so a still screen agrees on the first retry;
         * STABLE_MAX_TRIES caps the worst case (endless animation — carousels
         * never settle) at ~1s extra, after which `still_moving` tells the
         * brain the truth rather than pretending the tree is final.
         */
        private const val STABLE_POLL_MS = 300L
        private const val STABLE_MAX_TRIES = 3
        private const val QUIET_WINDOW_MS = 1200L

        /** See [childOf]. Compile-time ints, only ever passed on API 33+. */
        @android.annotation.SuppressLint("InlinedApi")
        private const val PREFETCH_WALK = AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or
            AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE

        /**
         * Walk caps — see [WalkBudget]. A whole `get_screen_element`, re-dumps
         * included, fits in [TREE_BUDGET_MS]; a first pass slower than
         * [STABLE_SKIP_MS] skips the stability re-dumps, which on a slow app
         * would cost a full walk each to learn what the screenshot shows anyway.
         */
        private const val TREE_BUDGET_MS = 8_000L
        private const val STABLE_SKIP_MS = 2_000L
        private const val ATTACHED_TREE_MS = 4_000L
        private const val FINGERPRINT_BUDGET_MS = 1_500L
        private const val SEARCH_BUDGET_MS = 1_500L

        /** 选择器一次最多返回多少个匹配：够模型挑第 N 个，又不至于把整棵树都搬回来。 */
        private const val SELECTOR_MAX_MATCHES = 50

        /** A withheld root is 1 node; a bare canvas a handful. */
        private const val BLIND_TREE_NODES = 3
        /** tapByEid 重定位的最大容忍漂移（S4）：活节点中心离 dump 时中心超过它即视为已不在原处。 */
        private const val LIVE_MAX_DIST_PX = 150
        /** 第十一批·定位回退：文字回退时最近两个同文字候选离 dump 中心的距离差 ≤ 它即视为分不清，不乱点。 */
        private const val FALLBACK_TEXT_TIE_PX = 40
        /** 第十批·tapBlockers：遮挡 walk 的最大递归深度（防畸形深树栈溢出；正常树远不到 30 层）。 */
        private const val OCCLUSION_MAX_DEPTH = 64
        private const val PX_COLS = 20
        private const val PX_ROWS = 32
        private const val PX_SAMPLE = 6
        /** Mean-luma delta (0-255) for a cell to count; a blinking text cursor stays under it. */
        private const val PX_CELL_DELTA = 10f
        private const val PX_MIN_CELLS = 2
        private const val STATUS_BAR_FRACTION = 0.035f
        private const val CAPTURE_GAP_MS = 350L

        /** See [snapToShape]. ~50 px on this tablet; a miss further than that was not a near miss. */
        private const val SNAP_RADIUS_DP = 24
        private const val SNAP_MIN_DP = 2
        /** Bigger than an icon is content, not a button the tap narrowly missed. */
        private const val SNAP_MAX_DP = 56
        /** Max per-channel difference from the background; WeChat's f7 button plate on ff is 8. */
        private const val SNAP_FG_DELTA = 6
        private const val SNAP_BLOCK = 4
        /** Blocks of gap bridged, so the two dots of "··" are one shape. */
        private const val SNAP_GAP_BLOCKS = 2

        /** See [awaitHelper]. Measured: the helper binds in about a second. */
        private const val HELPER_WAIT_MS = 3_000L
        private const val HELPER_POLL_MS = 300L

        /** The relative coordinate space the brain speaks: 0..1000 both axes. */
        private const val NORM_MAX = 1000

        /** How long after blind typing the zoom-before-send rule holds — see [requireAimedSend]. */
        private const val SEND_AIM_WINDOW_MS = 120_000L

        /** How long a non-fling swipe holds at its end point — see [swipe]. */
        private const val SWIPE_HOLD_MS = 150L

        /** 手势时长钳制（L17）：swipe 50..5000ms；长按 50..10000ms。 */
        private const val SWIPE_DURATION_MIN_MS = 50L
        private const val SWIPE_DURATION_MAX_MS = 5000L
        private const val LONG_PRESS_MIN_MS = 50L
        private const val LONG_PRESS_MAX_MS = 10000L

        /** SystemUI 包名：前台校验与窗口筛选里排除它。 */
        private const val SYSTEM_UI_PKG = "com.android.systemui"

        /**
         * H6: 交接面——前台是这些系统包时不拦手势。分享面板/系统选择器/权限弹窗/
         * 安装器都住在系统包里，不属于任何目标应用，前台变成它们是交接的正常形态，
         * 不是手势会落错应用的证据。
         */
        private val HANDOVER_SYSTEM_PKGS = setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
        )

        /**
         * M15: 默认 ownArea——在服务窗口列表里找 Jarvis 自家悬浮窗的 bounds。
         * 只认包名是 Jarvis 自己、类型是无障碍覆盖层（悬浮球/标记/气泡/接管边框
         * 都是这个类型）的窗口；其中全屏的那张（接管边框，整屏大）不是球，
         * 面积 ≥ 屏幕 45% 的排除掉，剩下的取面积最小的一张——球是 100dp 见方，
         * 比 144dp 的落点标记小，是最小的那张常驻覆盖层。球被收起/藏起时窗口
         * 还在，bounds 照常可用；球没起来时返回 null，等同于旧的默认行为。
         * 像素比对（[signatureOf]/[snapToShape]）与手势让位都按这块区域遮/让，
         * 对应 Andee `wireScreen` 的 gestureGuard/shotGuard（见 [tap] 里的
         * dodgeIfNeeded 与 [screenshot] 里的 setVisibleForCapture）。
         */
        private fun ownOverlayBounds(service: AccessibilityService): Rect? {
            val windows = runCatching { service.windows }.getOrNull() ?: return null
            val size = displaySize(service)
            val screenArea = size.x.toLong() * size.y
            val r = Rect()
            var best: Rect? = null
            var bestArea = Long.MAX_VALUE
            try {
                for (w in windows) {
                    val type = runCatching { w.type }.getOrNull() ?: continue
                    if (type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                    val root = runCatching { w.root }.getOrNull()
                    val pkg = runCatching { root?.packageName?.toString() }.getOrNull().orEmpty()
                    runCatching { root?.recycle() }
                    if (pkg != service.packageName) continue
                    if (!runCatching { w.getBoundsInScreen(r); true }.getOrDefault(false)) continue
                    if (r.width() <= 0 || r.height() <= 0) continue
                    val area = r.width().toLong() * r.height()
                    if (area >= screenArea * 45 / 100) continue // 全屏覆盖层（接管边框）不是球
                    if (area < bestArea) {
                        bestArea = area
                        best = Rect(r)
                    }
                }
            } finally {
                windows.forEach { runCatching { it.recycle() } }
            }
            return best
        }

        /**
         * Screenshot long side after downscale (generic_bridge value).
         *
         * 批九：原版此值读自 ScreenCapture.SHOT_MAX_SIDE（=1280），ScreenCapture
         * 本身未移植，这里直接写字面量 1280，保持与原版相同的出图尺寸。
         */
        private const val SHOT_MAX_SIDE = 1280

        /** Aim-grid columns. Letters run A.. — keep it ≤ 26. */
        private const val GRID_COLS = 8

        /** Aim-grid rows; rows are numbered from 1, so the last is [GRID_ROWS]. */
        private const val GRID_ROWS = 12

        /** How long a zoom image stays usable as an aiming reference. */
        private const val ZOOM_STALE_MS = 180_000L

        /**
         * How long a zoom keeps the popup guard unlocked.
         *
         * Shorter than [ZOOM_STALE_MS] on purpose: a menu can be dismissed and
         * another one opened in the same place, and the zoom that proved this
         * one said "评论" is not evidence about the next. A minute is long
         * enough to look, read and tap; long enough to outlive the popup it
         * describes is the failure this is meant to stop.
         */
        private const val ZOOM_CONFIRM_MS = 60_000L

        private const val POPUP_SETTLE_MS = 300L

        /**
         * Colour of the zoom crosshair. Magenta on purpose — see [drawCrosshair].
         *
         * The candidates it is avoiding are all taken: red is every unread
         * badge in every Chinese app, green is WeChat's own accent, and blue /
         * cyan is what [net.kuafuai.andee.ui.TapMarkerUi]'s ring and the ball
         * already are (the brain is told to ignore anything round and blue).
         * Magenta appears in none of them.
         */
        private val CROSSHAIR = Color.rgb(224, 0, 255)

        /**
         * The cell's subdivisions a tap may name, in reading order: 3 columns by
         * **5 rows**.
         *
         * Both numbers are a measurement, not a taste. On a 1200x2664 screen a
         * cell is 150x222 px, so one *third* of it vertically is 74 px — taller
         * than the things these taps are aimed at. Measured 2026-10-20 in
         * WeChat's 视频号 点赞 list: the 「谢谢」 button is ~100x50 px, its row's
         * name is 200 px above it and holds 20x the tappable area, so a single
         * step of 74 px was enough to miss the button and hit the name — which
         * opens a profile card. A fifth is 44 px, inside the button with room.
         *
         * Columns stay at three: 50 px per step against a target ~100 px wide
         * was never the problem, and going to five columns there would double
         * the vocabulary for nothing.
         */
        private const val PART_COLS = 3
        private const val PART_ROWS = 5

        /** The middle row of the subdivision, used when `part` is omitted. */
        private const val MID_ROW = PART_ROWS / 2

        /** Every subdivision of a cell a tap may name, in reading order. */
        val PARTS = listOf(
            "top-left", "top", "top-right",
            "upper-left", "upper", "upper-right",
            "center-left", "center", "center-right",
            "lower-left", "lower", "lower-right",
            "bottom-left", "bottom", "bottom-right",
        )

        // Aliases are the names a model reaches for when it is not looking at
        // the schema — a Chinese answer says 右下, not bottom-right. The
        // nine-name grid the vocabulary used before still parses: 上/中/下 and
        // their corners map to the outer rows, so an old payload aims where it
        // used to.
        private val PART_THIRDS: Map<String, IntArray> = buildMap {
            PARTS.forEachIndexed { i, p -> put(p, intArrayOf(i % PART_COLS, i / PART_COLS)) }
            // Row 0 / middle / last, as the old thirds meant them.
            put("left", intArrayOf(0, MID_ROW)); put("right", intArrayOf(2, MID_ROW))
            listOf("左上", "上", "右上", "左", "中", "右", "左下", "下", "右下")
                .forEachIndexed { i, p ->
                    val fy = when (i / 3) {
                        0 -> 0
                        1 -> MID_ROW
                        else -> PART_ROWS - 1
                    }
                    put(p, intArrayOf(i % 3, fy))
                }
            put("centre", intArrayOf(1, MID_ROW)); put("middle", intArrayOf(1, MID_ROW))
            put("中间", intArrayOf(1, MID_ROW)); put("中心", intArrayOf(1, MID_ROW))
            put("top-center", intArrayOf(1, 0)); put("bottom-center", intArrayOf(1, PART_ROWS - 1))
            put("center-left", intArrayOf(0, MID_ROW)); put("center-right", intArrayOf(2, MID_ROW))
            put("left-top", intArrayOf(0, 0)); put("right-top", intArrayOf(2, 0))
            put("left-bottom", intArrayOf(0, PART_ROWS - 1))
            put("right-bottom", intArrayOf(2, PART_ROWS - 1))
        }

        /**
         * Hard ceiling on outline lines. Generous — it is a safety valve for a
         * pathological list view, not a budget. Below it nothing is dropped,
         * because on this screen the model cannot tell "there is no such
         * control" from "we did not print it".
         */
        private const val MAX_ELEMENT_LINES = 300
        // 紧凑表上限：只列可操作元素，40 行够一屏用，超了记 compact_dropped。
        private const val MAX_COMPACT_LINES = 40
        private const val ICON_MIN_DP = 16f
        private const val ICON_MAX_DP = 120f
        private const val ICON_MAX_ASPECT = 2f

        /**
         * Deepest indent level rendered; beyond it lines stay at this column.
         * Hoisting already collapses wrapper chains, so real depth is small —
         * this only stops a pathological hierarchy from spending more
         * characters on leading spaces than on content.
         */
        private const val MAX_INDENT = 10

        /**
         * Gestures closer than this count as "the same spot".
         *
         * 120 px, not 40: at 120 the bucket was WIDER than the thing being
         * aimed at, so a deliberate "20 px left of where I last aimed" landed in
         * the same bucket as the miss it was correcting — and the correction
         * that is supposed to converge got refused as a loop. Measured
         * 2026-10-20 in WeChat's 视频号 点赞 list: a 50 px-tall 「谢谢」 button
         * cannot contain two 120 px buckets, so the guard fired 10 times in one
         * task while the brain was doing exactly the right thing. 40 px keeps
         * "same spot" meaning the same spot, and still catches a jittered
         * retry of one target.
         */
        private const val LOOP_RADIUS_PX = 40

        /**
         * Allowed same-spot attempts before the guard fires. 6 means the 7th is
         * refused — enough room for aim, observe, adjust, which is the loop a
         * blind tap needs; a real dead loop still trips it well inside the
         * window.
         */
        private const val LOOP_MAX_TRIES = 6

        /** Same-spot counter window; older than this is a fresh task. */
        private const val LOOP_WINDOW_MS = 300_000L

        /** Consecutive same-direction flings allowed before the streak is cut. */
        private const val FLING_MAX_STREAK = 6

        /** Streak window; longer than a browse, shorter than a task. */
        private const val FLING_STREAK_WINDOW_MS = 120_000L

        /** How long a fling is given to coast before the screen is fingerprinted. */
        private const val FLING_SETTLE_MS = 900L

        /** Ceiling on `screens` — one call moving more than this is a runaway. */
        private const val MAX_SCREENS = 10

        /** Beat between the determinate swipes of one [swipeScreens] call. */
        private const val SCREEN_SETTLE_MS = 380L

        private const val LOOP_HELP =
            "Stuck-loop guard: this exact spot has been touched more than " +
                "6 times within 5 minutes. The gesture was NOT executed. " +
                "Do not retry the same tap. Take a fresh screenshot or get_screen_element, " +
                "pick a genuinely different approach, and if the goal still cannot " +
                "be reached, stop and ask the user for help instead of continuing."
    }
}
