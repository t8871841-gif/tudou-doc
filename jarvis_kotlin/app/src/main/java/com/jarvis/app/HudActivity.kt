package com.jarvis.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
// 注意三点：
// 1. 不能用 file:///android_asset/ 直接加载——hud 用 ES 模块 + importmap，
//    file:// 下模块加载会被跨域拦截、页面全黑。所以用 WebViewAssetLoader
//    把 assets 以 https://appassets.androidplatform.net 域名形式喂给 WebView。
// 2. hud 会连 ws://127.0.0.1:8765 找桌面语音服务，手机上没有，它自己 3 秒重试、
//    静默失败，不影响演示；“开始说话”走系统语音合成，WebView 里可能不出声，
//    但听/说状态切换的画面变化照常发生。
// 3. 四个全息演示在桌面版里只认键盘快捷键和语音服务消息，手机触屏点不出来，
//    所以本页在页面自己的菜单（#menuPop）里注入四个演示按钮（只改运行时的页面，
//    不改 assets 里的文件）；猛禽拆解要用的图随包带在 figure-raptor.html。
//    语音状态不同步会打架：只在原生状态变化时推一次，HUD 自己的按钮流程不动。
class HudActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    private val stateHandler = Handler(Looper.getMainLooper())
    private var pageLoaded = false
    private var lastPushedState: String? = null

    // 原生语音状态（idle/listening/thinking/speaking）与 HUD 的 setState 是同一套词，
    // 变化时推一次；500ms 轮询只在本页打开时跑，退出即停。
    private val statePoll = object : Runnable {
        override fun run() {
            val s = JarvisAgent.currentState()
            if (pageLoaded && s != lastPushedState) {
                lastPushedState = s
                webView.evaluateJavascript(
                    "try{if(typeof setState==='function')setState('$s')}catch(e){}", null
                )
            }
            stateHandler.postDelayed(this, 500)
        }
    }

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

                override fun onPageFinished(view: WebView, url: String) {
                    pageLoaded = true
                    injectDemoMenu()
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

    // 在 HUD 自己的菜单里加四个演示按钮（点“菜单”可见，与“语音播报”同款式）。
    // 再点一次同一个演示即收起（与桌面键盘快捷键同款切换逻辑）。
    // 猛禽拆解的中文标签抄自桌面语音服务的 hologram.py（ZH['raptor']）。
    private fun injectDemoMenu() {
        webView.evaluateJavascript(
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

    override fun onResume() {
        super.onResume()
        webView.onResume()
        stateHandler.post(statePoll)
    }

    override fun onPause() {
        stateHandler.removeCallbacks(statePoll)
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stateHandler.removeCallbacks(statePoll)
        // WebView 必须从父布局摘掉再 destroy，否则下次进本页可能白屏。
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
