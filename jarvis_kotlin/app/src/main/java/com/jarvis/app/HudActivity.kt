package com.jarvis.app

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

// 全息 HUD（2026-10-30，用户令 WebView 原样嵌进来）：
// 把 GDDXX-Jarvis 的 hud/ 原样放在 app/src/main/assets/hud/（GPL-3.0，见该目录 NOTICE.md），
// 本页只负责用 WebView 把它跑起来，不改它一行代码。
// 注意两点：
// 1. 不能用 file:///android_asset/ 直接加载——hud 用 ES 模块 + importmap，
//    file:// 下模块加载会被跨域拦截、页面全黑。所以用 WebViewAssetLoader
//    把 assets 以 https://appassets.androidplatform.net 域名形式喂给 WebView。
// 2. hud 会连 ws://127.0.0.1:8765 找桌面语音服务，手机上没有，它自己 3 秒重试、
//    静默失败，不影响演示；“开始说话”走系统语音合成，WebView 里可能不出声，
//    但听/说状态切换的画面变化照常发生。
class HudActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 看 HUD 时不熄屏；退出本页即恢复。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // hud 是 Three.js 全屏 WebGL，离开本页要停渲染，回来再续（见 onPause/onResume）。
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
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
        setContentView(webView)
        webView.loadUrl("https://appassets.androidplatform.net/assets/hud/index.html")
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        // WebView 必须从父布局摘掉再 destroy，否则下次进本页可能白屏。
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
