package com.jarvis.app.task

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.jarvis.app.BrowserActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 内置浏览器智能体（Mika 内置浏览器的思路）：Jarvis 自带一个 WebView 浏览器，
 * 任务循环可以直接开网页、读页面、点链接，不用切到 Chrome 再走无障碍。
 * WebView 的登录态（Cookie）由系统 WebView 自己存，关掉重开还在。
 *
 * 线程：WebView 的一切操作必须在主线程；TaskLoop 在后台线程调这里时，
 * 用 Handler 发到主线程、CountDownLatch 等结果（带超时，不会卡死循环）。
 * 页面快照（URL/标题/正文/链接）由 BrowserActivity 在页面加载完后刷新进
 * 缓存，TaskLoop 读缓存不阻塞；browse/clickLink/goBack 做完也会等一等再
 * 刷新一次快照，好让模型立刻看到新页面。
 */
object BrowserController {

    data class Link(val index: Int, val text: String, val href: String)
    data class Snapshot(
        val url: String, val title: String, val text: String,
        val links: List<Link>, val updatedAt: Long
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var lastSnapshot: Snapshot? = null

    /** BrowserActivity 创建时登记 WebView，销毁时注销。 */
    fun attach(wv: WebView) {
        webView = wv
    }

    fun detach(wv: WebView) {
        if (webView === wv) {
            webView = null
            lastSnapshot = null
        }
    }

    /** 浏览器是否正开着（WebView 已登记）。 */
    fun isOpen(): Boolean = webView != null

    /** 最近一次页面快照（可能为空）；TaskLoop 读这个，不阻塞。 */
    fun lastSnapshot(): Snapshot? = lastSnapshot

    /** 打开内置浏览器并加载 URL（已开着就切过去加载）。 */
    fun open(ctx: Context, url: String): String {
        val fixed = fixUrl(url)
        val intent = Intent(ctx, BrowserActivity::class.java).apply {
            putExtra(BrowserActivity.EXTRA_URL, fixed)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        ctx.startActivity(intent)
        return "正在内置浏览器打开 $fixed"
    }

    /** 浏览器里后退一步；没得退返回 false。 */
    fun goBack(): Boolean {
        val wv = webView ?: return false
        val latch = CountDownLatch(1)
        var wentBack = false
        mainHandler.post {
            try {
                if (wv.canGoBack()) {
                    wv.goBack()
                    wentBack = true
                }
            } catch (_: Exception) {
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        if (wentBack) refreshSnapshot(3000)
        return wentBack
    }

    /** 点页面里第 index 个链接（按快照里的编号）；点完刷新快照。 */
    fun clickLink(index: Int): String {
        val wv = webView ?: return "内置浏览器没开着"
        val snap = lastSnapshot ?: return "还没有页面快照，先等页面加载"
        val link = snap.links.getOrNull(index) ?: return "没有第 $index 个链接（共 ${snap.links.size} 个）"
        val latch = CountDownLatch(1)
        var result = ""
        mainHandler.post {
            try {
                wv.evaluateJavascript(
                    "(function(){var as=document.querySelectorAll('a[href]');" +
                        "if($index<as.length){as[$index].click();return 'clicked';} return 'no such link';})()"
                ) { v -> result = v ?: ""; latch.countDown() }
            } catch (e: Exception) {
                result = "error: ${e.message}"
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        refreshSnapshot(3000)
        return "已点链接「${link.text}」"
    }

    /** 刷新页面快照（主线程上跑 JS 取页面内容）。BrowserActivity 页面加载完后调。 */
    fun refreshSnapshot(waitMs: Long = 2000) {
        val wv = webView ?: return
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                wv.evaluateJavascript(SNAPSHOT_JS) { v ->
                    try {
                        lastSnapshot = parseSnapshot(v)
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        latch.await(waitMs, TimeUnit.MILLISECONDS)
    }

    private fun fixUrl(raw: String): String {
        val u = raw.trim()
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        return "https://$u"
    }

    private fun parseSnapshot(jsResult: String?): Snapshot? {
        if (jsResult.isNullOrBlank() || jsResult == "null") return null
        // evaluateJavascript 回的是 JSON 编码后的字符串（外层带双引号、内有转义）。
        val decoded = try {
            JSONObject("{\"v\":$jsResult}").optString("v")
        } catch (_: Exception) {
            jsResult
        }
        if (decoded.isBlank()) return null
        val obj = JSONObject(decoded)
        val links = mutableListOf<Link>()
        val arr: JSONArray = obj.optJSONArray("links") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            links += Link(o.optInt("i"), o.optString("text"), o.optString("href"))
        }
        return Snapshot(
            url = obj.optString("url"),
            title = obj.optString("title"),
            text = obj.optString("text"),
            links = links,
            updatedAt = System.currentTimeMillis()
        )
    }

    private const val SNAPSHOT_JS =
        "(function(){var text=document.body?document.body.innerText:'';" +
            "var links=[];var as=document.querySelectorAll('a[href]');" +
            "for(var i=0;i<Math.min(as.length,40);i++){var a=as[i];" +
            "var t=(a.innerText||'').trim().replace(/\\s+/g,' ');" +
            "if(t)links.push({i:i,text:t.slice(0,60),href:a.href});}" +
            "return JSON.stringify({url:location.href,title:document.title," +
            "text:text.slice(0,2000),links:links});})()"
}
