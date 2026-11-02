package com.jarvis.app.task

import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * 多条件选择器：按描述找元素，而不是按坐标猜。
 *
 * 思路只借了“多条件 AND + 第 N 个 + 爬到可点父节点”这个模式（mcp-android
 * 的 UiSelector 形状；它无许可证，不能抄代码），每一行都是对着本工程自己
 * 的 AccessibilityNodeInfo 现写的 Kotlin。
 *
 * 选择器是一个 JSONObject，字段全是可选的，给了的条件全部要命中（AND）：
 * - text / description：精确相等（节点文字 / contentDescription）
 * - textContains / descriptionContains：包含
 * - viewId：资源 id，精确相等或按后缀（"foo" 命中 "com.app:id/foo"）
 * - className：类名精确相等，或简名相等（"Button" 命中 "android.widget.Button"）
 * - packageName：包名精确相等
 * - clickable / editable / enabled / visible：布尔值，必须与节点属性一致
 * - caseSensitive：默认 false，文字类匹配不区分大小写
 *
 * 一个条件都没给的选择器不命中任何节点（不许空选全屏）。
 */
object UiSelector {

    /** 选择器命中的一个节点：稳定编号（与紧凑表同一套算法得出）、类型、文字、中心坐标（0–1000）。 */
    data class SelMatch(
        val sid: String,
        val type: String,
        val text: String,
        val cx: Int,
        val cy: Int,
    )

    /** 节点显示文字：text 优先，contentDescription 次之，资源 id 末段兜底（与紧凑表 label 同款）。 */
    fun label(node: AccessibilityNodeInfo): String {
        node.text?.toString()?.takeIf { it.isNotEmpty() }?.let { return oneLine(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { return oneLine(it) }
        node.viewIdResourceName?.takeIf { it.isNotEmpty() }?.let { return "#" + oneLine(it.substringAfterLast('/')) }
        return ""
    }

    private fun oneLine(s: String): String =
        s.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim()

    /** 节点是否命中选择器：给了的条件全要命中；一个条件都没给返回 false。 */
    fun matches(node: AccessibilityNodeInfo, sel: JSONObject): Boolean {
        val caseSensitive = sel.optBoolean("caseSensitive", false)
        var any = false
        fun eq(a: String, b: String) = if (caseSensitive) a == b else a.equals(b, ignoreCase = true)
        fun has(a: String, b: String) = if (caseSensitive) a.contains(b) else a.contains(b, ignoreCase = true)
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (sel.has("text")) {
            any = true
            if (!eq(text, sel.optString("text"))) return false
        }
        if (sel.has("textContains")) {
            any = true
            if (!has(text, sel.optString("textContains"))) return false
        }
        if (sel.has("description")) {
            any = true
            if (!eq(desc, sel.optString("description"))) return false
        }
        if (sel.has("descriptionContains")) {
            any = true
            if (!has(desc, sel.optString("descriptionContains"))) return false
        }
        if (sel.has("viewId")) {
            any = true
            val v = sel.optString("viewId")
            val id = node.viewIdResourceName.orEmpty()
            if (!(id == v || id.endsWith("/$v"))) return false
        }
        if (sel.has("className")) {
            any = true
            val v = sel.optString("className")
            val cls = node.className?.toString().orEmpty()
            if (!(cls == v || cls.substringAfterLast('.') == v)) return false
        }
        if (sel.has("packageName")) {
            any = true
            if (node.packageName?.toString().orEmpty() != sel.optString("packageName")) return false
        }
        if (sel.has("clickable")) {
            any = true
            if (node.isClickable != sel.optBoolean("clickable")) return false
        }
        if (sel.has("editable")) {
            any = true
            if (node.isEditable != sel.optBoolean("editable")) return false
        }
        if (sel.has("enabled")) {
            any = true
            if (node.isEnabled != sel.optBoolean("enabled")) return false
        }
        if (sel.has("visible")) {
            any = true
            if (node.isVisibleToUser != sel.optBoolean("visible")) return false
        }
        return any
    }

    /** 选择器一句话摘要（历史/签名用）：优先取最能认出元素的那个条件。 */
    fun shortSummary(sel: JSONObject?): String {
        if (sel == null) return ""
        for (k in listOf("textContains", "text", "descriptionContains", "description", "viewId", "className")) {
            val v = sel.optString(k)
            if (v.isNotEmpty()) return v.take(12)
        }
        return ""
    }
}
