package com.jarvis.app.command

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.jarvis.app.AuditLog
import com.jarvis.app.JarvisAccessibilityService

/**
 * 批十三新增：系统功能指令层——手电筒、截屏、锁屏这类是手机的系统功能，不是带图标的
 * 应用。用户真机反馈「打开手机手电筒」打不开：旧链路把它当应用名去翻已装应用列表，
 * 找不到就死胡同。本层在 LocalCommands 的"打开应用"之前拦截这类指令，直接调系统
 * API/无障碍全局动作办掉，语音与打字双路共用（LocalCommands.tryHandle 里调用）。
 *
 * 匹配纪律：全部用归一化后的整句精确匹配（去标点/语气词），不做模糊包含，
 * 避免吞正常聊天句。办不成如实回报原因，不装成功。
 */
object DeviceCommands {

    private val TORCH_ON = setOf(
        "打开手电筒", "开手电筒", "开启手电筒", "手电筒打开", "手电筒开启",
        "打开闪光灯", "开闪光灯", "把手电筒打开", "把手电筒开一下"
    )
    private val TORCH_OFF = setOf(
        "关手电筒", "关闭手电筒", "关掉手电筒", "手电筒关闭", "手电筒关掉",
        "关闭闪光灯", "关掉闪光灯", "把手电筒关了", "把手电筒关掉", "把手电筒关闭"
    )
    private val TORCH_TOGGLE = setOf("手电筒", "闪光灯")

    private val SCREENSHOT = setOf("截屏", "截图", "截个屏", "截一下屏", "截张屏")
    private val LOCK = setOf("锁屏", "关屏", "锁一下屏")
    private val NOTIFICATIONS = setOf("打开通知栏", "看通知", "打开通知", "显示通知")
    private val QUICK_SETTINGS = setOf("打开快捷设置", "打开控制中心", "打开设置面板")
    private val HOME = setOf("回主页", "回到桌面", "回桌面", "返回桌面")
    private val RECENTS = setOf("打开最近任务", "看最近任务", "多任务", "打开多任务")
    private val VOL_UP = setOf("音量调大", "声音大一点", "音量大一点", "调大音量", "声音调大")
    private val VOL_DOWN = setOf("音量调小", "声音小一点", "音量小一点", "调小音量", "声音调小")

    fun tryHandle(ctx: Context, raw: String, speak: (String) -> Unit): Boolean {
        val t = raw.trim().trimEnd('。', '！', '!', '？', '?', ' ', '吧', '啊', '啦', '呀', '呢', '嘛')
            .removePrefix("帮我").removePrefix("请").trim()
        val bare = t.removePrefix("手机").removePrefix("打开手机").trim()

        when {
            t in TORCH_ON || bare in TORCH_ON -> { setTorch(ctx, true, speak); return true }
            t in TORCH_OFF || bare in TORCH_OFF -> { setTorch(ctx, false, speak); return true }
            t in TORCH_TOGGLE || bare in TORCH_TOGGLE -> { setTorch(ctx, !torchOn, speak); return true }
            t in SCREENSHOT -> { global(ctx, android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "截屏", "截好了，在相册里", speak); return true }
            t in LOCK -> { global(ctx, android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "锁屏", "锁好了", speak); return true }
            t in NOTIFICATIONS -> { global(ctx, android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "通知栏", "通知栏打开了", speak); return true }
            t in QUICK_SETTINGS -> { global(ctx, android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "快捷设置", "快捷设置打开了", speak); return true }
            t in HOME -> { global(ctx, android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME, "回主页", "回主页了", speak); return true }
            t in RECENTS -> { global(ctx, android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS, "最近任务", "最近任务打开了", speak); return true }
            t in VOL_UP -> { adjustVolume(ctx, AudioManager.ADJUST_RAISE, "音量调大了", speak); return true }
            t in VOL_DOWN -> { adjustVolume(ctx, AudioManager.ADJUST_LOWER, "音量调小了", speak); return true }
        }
        return false
    }

    // ---------- 手电筒：CameraManager.setTorchMode，不用打开相机、不需要相机权限 ----------

    @Volatile private var torchOn = false
    @Volatile private var torchCamId: String? = null
    @Volatile private var callbackRegistered = false

    private fun setTorch(ctx: Context, on: Boolean, speak: (String) -> Unit) {
        val app = ctx.applicationContext
        val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        registerCallback(app, cm)
        val id = torchCamId ?: findTorchCamera(cm)
        if (id == null) {
            speak("这台手机没找到可用的手电筒")
            AuditLog.append(ctx, "系统功能", "手电筒", "无可用闪光灯")
            return
        }
        try {
            cm.setTorchMode(id, on)
            torchOn = on
            AuditLog.append(ctx, "系统功能", "手电筒", if (on) "已打开" else "已关闭")
            speak(if (on) "手电筒开了" else "手电筒关了")
        } catch (e: Exception) {
            // 相机被其他应用占用时系统会拒绝开灯，如实说原因
            AuditLog.append(ctx, "系统功能", "手电筒", "失败：${e.message}")
            speak("手电筒没打开，可能相机正被别的应用占用")
        }
    }

    private fun findTorchCamera(cm: CameraManager): String? {
        try {
            for (id in cm.cameraIdList) {
                val has = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (has) {
                    torchCamId = id
                    return id
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** 跟踪手电筒真实状态（含用户在快捷设置里手动开关的情况），让"切换"说法不失灵。 */
    private fun registerCallback(ctx: Context, cm: CameraManager) {
        if (callbackRegistered) return
        callbackRegistered = true
        try {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchCamId) torchOn = enabled
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            // 注册失败不致命：切换退化为按内部记录的状态走
        }
    }

    // ---------- 无障碍全局动作 ----------

    private fun global(ctx: Context, action: Int, label: String, okText: String, speak: (String) -> Unit) {
        val svc = JarvisAccessibilityService.instance
        if (svc == null) {
            speak("${label}要用无障碍服务：去设置里把 Jarvis 的无障碍打开")
            AuditLog.append(ctx, "系统功能", label, "无障碍服务未连接")
            return
        }
        val ok = try {
            svc.performGlobalAction(action)
        } catch (_: Exception) {
            false
        }
        AuditLog.append(ctx, "系统功能", label, if (ok) "已执行" else "系统拒绝")
        speak(if (ok) okText else "${label}没成，系统拒绝了这个动作")
    }

    private fun adjustVolume(ctx: Context, dir: Int, okText: String, speak: (String) -> Unit) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            AuditLog.append(ctx, "系统功能", "音量", okText)
            speak(okText)
        } catch (e: Exception) {
            speak("音量没调成：${e.message}")
        }
    }
}
