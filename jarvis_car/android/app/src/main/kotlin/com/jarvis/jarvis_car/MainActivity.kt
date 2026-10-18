package com.jarvis.jarvis_car

import android.content.Intent
import android.net.Uri
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/// 车机版原生通道：只做三件事——按包名开 App、导航、拨号。
/// 均用显式意图，绝不走隐式意图（隐式意图会在车机上弹系统选择器）。
class MainActivity : FlutterActivity() {
    private val launcherChannel = "jarvis_car/launcher"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, launcherChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "openApp" -> {
                        val pkg = call.argument<String>("package") ?: ""
                        result.success(openApp(pkg))
                    }
                    "openNavigation" -> {
                        val dest = call.argument<String>("destination") ?: ""
                        result.success(openNavigation(dest))
                    }
                    "dial" -> result.success(dial())
                    else -> result.notImplemented()
                }
            }
    }

    /// Launch an app by package name, the canonical way.
    /// getLaunchIntentForPackage returns the app's real launcher intent
    /// (explicit component). Never build an implicit ACTION_MAIN +
    /// CATEGORY_LAUNCHER intent: it pops a system chooser instead of
    /// opening the app.
    /// NOTE: Android 11+ package-visibility rules need QUERY_ALL_PACKAGES
    /// or <queries> for this to see other apps; the target head unit is
    /// Android 8.1, where those rules don't apply.
    private fun openApp(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /// 高德 amapuri 路由（显式指定高德包名）；失败则直接打开地图 App。
    private fun openNavigation(dest: String): Boolean {
        if (dest.isNotEmpty()) {
            val uri = Uri.parse(
                "amapuri://route/plan/?sourceApplication=jarvis_car" +
                    "&dname=${Uri.encode(dest)}&dev=0&t=0"
            )
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.autonavi.minimap")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
                return true
            } catch (_: Exception) {
                // fall through to opening the map app
            }
        }
        return openApp("com.autonavi.minimap") || openApp("com.baidu.BaiduMap")
    }

    private fun dial(): Boolean {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
