package com.example.jarvis

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Jarvis 跨应用自动发送引擎。
 *
 * 安全设计：
 *  1. 平时不做任何事：只有 [arm] 之后的一小段时间窗口（默认 20 秒）内才会扫描界面；
 *  2. 只在白名单应用（默认短信 App / WhatsApp）里工作，别的应用一律忽略；
 *  3. 只点“发送”按钮：排除输入框，要求按钮可见且可用，不会盲点右下角；
 *  4. 点中一次就自动解除武装，超时也会自动解除。
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisA11y"
        const val DEFAULT_WINDOW_MS = 20_000L
        private const val SCAN_INTERVAL_MS = 250L // 窗口内容事件很密集，节流
        private const val MAX_NODES = 600 // 单次遍历的节点上限，防止卡顿

        @Volatile private var instance: JarvisAccessibilityService? = null
        @Volatile private var armedUntil = 0L
        @Volatile private var allowedPackages: Set<String> = emptySet()
        @Volatile private var state = "idle" // idle | armed | clicked | timeout

        // 按钮文字：整词匹配，避免把“发送人”“我发送了文件”之类的文字当成按钮
        private val SEND_TEXT = Regex("^(发送|发 送|send)$", RegexOption.IGNORE_CASE)
        // contentDescription：图标按钮常见写法，如“发送”“发送短信”“Send message”
        private val SEND_DESC = Regex("发送|send", RegexOption.IGNORE_CASE)
        // viewId：如 com.whatsapp:id/send、send_message_button_icon、btn_send
        private val SEND_ID =
            Regex("(/send$|send\\w*(btn|button)|(btn|button)\\w*send)", RegexOption.IGNORE_CASE)

        /** 武装：[windowMs] 毫秒内，只在 [packages] 里自动点“发送”。 */
        fun arm(packages: Set<String>, windowMs: Long = DEFAULT_WINDOW_MS) {
            allowedPackages = packages
            armedUntil = SystemClock.elapsedRealtime() + windowMs
            state = "armed"
            instance?.requestScan() // 目标界面可能已经在前台
        }

        fun disarm() {
            armedUntil = 0L
            if (state == "armed") state = "idle"
        }

        fun isArmed(): Boolean = SystemClock.elapsedRealtime() < armedUntil

        /** idle / armed / clicked / timeout，供 Flutter 查询上一次结果。 */
        fun status(): String {
            if (state == "armed" && !isArmed()) state = "timeout"
            return state
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastScan = 0L
    private var rescanPending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        disarm()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isArmed()) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in allowedPackages) return
        requestScan()
    }

    /** 节流：窗口内容事件很多，过密的请求合并成一次稍后的重扫，避免漏掉最后一次变化。 */
    private fun requestScan() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScan >= SCAN_INTERVAL_MS) {
            scanOnce()
        } else if (!rescanPending) {
            rescanPending = true
            handler.postDelayed({
                rescanPending = false
                if (isArmed()) scanOnce()
            }, SCAN_INTERVAL_MS)
        }
    }

    private fun scanOnce() {
        lastScan = SystemClock.elapsedRealtime()
        if (!isArmed()) return
        val root = rootInActiveWindow ?: return
        try {
            val pkg = root.packageName?.toString() ?: return
            if (pkg !in allowedPackages) return
            if (findAndClick(root)) {
                state = "clicked"
                armedUntil = 0L
                Log.d(TAG, "clicked send in $pkg")
            }
        } catch (e: Exception) {
            Log.w(TAG, "scan failed", e)
        } finally {
            recycleCompat(root)
        }
    }

    /** 广度优先找“发送”按钮并点击；所有中途取得的节点都在 finally 里统一回收。 */
    private fun findAndClick(root: AccessibilityNodeInfo): Boolean {
        val obtained = ArrayList<AccessibilityNodeInfo>()
        try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                if (isSendCandidate(node) && clickNodeOrParent(node)) return true
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    obtained.add(child)
                    queue.add(child)
                }
            }
            return false
        } finally {
            obtained.forEach { recycleCompat(it) }
        }
    }

    private fun isSendCandidate(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled || !node.isVisibleToUser) return false // 没填内容时按钮是灰的，等下次事件
        if (node.isEditable) return false
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText")) return false

        val text = node.text?.toString()?.trim().orEmpty()
        if (text.length <= 6 && SEND_TEXT.matches(text)) return true

        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (desc.length in 1..12 && SEND_DESC.containsMatchIn(desc)) return true

        // 需要 accessibility_service_config 里的 flagReportViewIds
        val id = node.viewIdResourceName.orEmpty()
        return id.isNotEmpty() && SEND_ID.containsMatchIn(id)
    }

    /** 点击节点本身，否则向上找可点击的父节点。 */
    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        val climbed = ArrayList<AccessibilityNodeInfo>()
        try {
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 4) { // 最多往上 4 层，别点到整行/整页
                climbed.add(parent)
                if (parent.isClickable) {
                    return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                parent = parent.parent
                depth++
            }
            return false
        } finally {
            climbed.forEach { recycleCompat(it) }
        }
    }

    /** API 33 起 recycle() 是空操作并已废弃；旧系统仍需要回收。 */
    @Suppress("DEPRECATION")
    private fun recycleCompat(node: AccessibilityNodeInfo?) {
        if (node == null || Build.VERSION.SDK_INT >= 33) return
        try {
            node.recycle()
        } catch (_: Exception) {
        }
    }
}
