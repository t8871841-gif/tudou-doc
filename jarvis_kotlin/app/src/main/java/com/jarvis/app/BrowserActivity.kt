package com.jarvis.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.app.task.BrowserController

/**
 * 内置浏览器（Mika 内置浏览器的思路）：全屏 WebView + 顶部小栏（返回/地址/关闭）。
 * 任务循环经 BrowserController 驱动它：开网址、读页面、点链接、后退。
 * 登录态（Cookie）由系统 WebView 自己存，关掉重开还在；挡路的登录/验证码页
 * 由模型自己判断，搞不定的用 Take_over 交给用户（不在这里硬拦）。
 */
class BrowserActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private lateinit var webView: WebView
    private lateinit var urlText: android.widget.EditText

    /** 用户在地址栏输的内容转成网址：带点无空格当网址（缺协议补 https），否则当搜索词走 Bing。 */
    private fun toUrl(q: String): String {
        val t = q.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (!t.contains(' ') && t.contains('.')) return "https://$t"
        return "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(8, 8, 8, 8)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val backBtn = Button(this).apply {
            text = "‹ 返回"
            setOnClickListener { if (webView.canGoBack()) webView.goBack() else finish() }
        }
        urlText = android.widget.EditText(this).apply {
            hint = "输入网址或搜索"
            setTextColor(Color.parseColor("#333333"))
            textSize = 13f
            maxLines = 1
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 12
                rightMargin = 12
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                    val q = text.toString().trim()
                    if (q.isNotEmpty()) webView.loadUrl(toUrl(q))
                    // 收起键盘
                    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(windowToken, 0)
                    true
                } else false
            }
        }
        val closeBtn = Button(this).apply {
            text = "关闭"
            setOnClickListener { finish() }
        }
        bar.addView(backBtn)
        bar.addView(urlText)
        bar.addView(closeBtn)
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    // 站内/站外链接都在本浏览器里打开，不跳到别的 App。
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // 地址栏只在没聚焦时才同步当前网址，不打断用户正在输入。
                    if (!urlText.hasFocus()) urlText.setText(url ?: "")
                    // 页面加载完刷新快照，模型下一轮就能看到新页面。
                    Thread { BrowserController.refreshSnapshot(2000) }.start()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    // 标题只更新 Activity 标题，不动地址栏（地址栏留给网址/搜索）。
                }
            }
        }
        root.addView(bar)
        root.addView(webView)
        setContentView(root)
        BrowserController.attach(webView)
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        // 手动从演示按钮打开时没带网址，给个 Bing 首页，不留白页。
        webView.loadUrl(if (url.isNotEmpty()) url else "https://www.bing.com")
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isNotEmpty()) webView.loadUrl(url)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        BrowserController.detach(webView)
        super.onDestroy()
    }
}
