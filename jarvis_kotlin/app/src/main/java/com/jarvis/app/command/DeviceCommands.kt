package com.jarvis.app.command

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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

    // 第二梯队批五新增：WiFi/蓝牙/飞行模式/系统勿扰。
    private val WIFI_PANEL = setOf("打开wifi", "打开WiFi", "打开无线网", "开wifi", "开WiFi", "wifi设置", "WiFi设置")
    private val BT_PANEL = setOf("打开蓝牙", "开蓝牙", "蓝牙设置")
    private val AIRPLANE_PANEL = setOf("打开飞行模式", "开飞行模式", "飞行模式", "关闭飞行模式", "关飞行模式")
    private val SYS_DND_ON = setOf("打开勿扰模式", "开勿扰模式", "开启勿扰", "打开免打扰")
    private val SYS_DND_OFF = setOf("关闭勿扰模式", "关勿扰模式", "关闭免打扰", "取消勿扰")

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
            t in WIFI_PANEL -> { openWifiPanel(ctx, speak); return true }
            t in BT_PANEL -> { openBluetoothPanel(ctx, speak); return true }
            t in AIRPLANE_PANEL -> { openAirplaneModeSettings(ctx, speak); return true }
            t in SYS_DND_ON -> { setSystemDnd(ctx, true, speak); return true }
            t in SYS_DND_OFF -> { setSystemDnd(ctx, false, speak); return true }
        }
        return false
    }

    // ---------- WiFi / 蓝牙：Android 10（API 29）起，普通 App 不再能直接调用
    // WifiManager.setWifiEnabled()/BluetoothAdapter.enable() 悄悄把开关拨了——系统认为
    // 这类全局网络开关必须由用户亲手点，第三方 App 最多只能把"那个面板"带到用户眼前。
    // 这里用 Settings.Panel（系统提供的快捷切换浮层，不需要额外权限），比带用户
    // 深挖"设置 > 网络和互联网"要快得多，但最后一下"点开/关"仍然需要用户自己点——
    // 如实告知，不说"已经帮你打开了"。----------

    private fun openWifiPanel(ctx: Context, speak: (String) -> Unit) {
        try {
            // Settings.Panel（快捷切换浮层）要 API 29+；minSdk 是 24，低版本退回传统设置页
            // （不是浮层，是整页，但好歹到得了开关跟前）。
            val intent = if (Build.VERSION.SDK_INT >= 29) {
                Intent(Settings.Panel.ACTION_WIFI)
            } else {
                @Suppress("DEPRECATION") Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AuditLog.append(ctx, "系统功能", "WiFi", "已跳转系统设置（需用户自己点一下）")
            speak("WiFi 设置打开了，Android 10 起这个开关系统不让 App 代点，你自己点一下")
        } catch (e: Exception) {
            AuditLog.append(ctx, "系统功能", "WiFi", "跳转失败：${e.message}")
            speak("WiFi 设置没打开成：${e.message}")
        }
    }

    private fun openBluetoothPanel(ctx: Context, speak: (String) -> Unit) {
        try {
            // Settings.Panel 没有蓝牙项（只有 WIFI/NFC/INTERNET/VOLUME），别按 WiFi 对称硬编——
            // jk-v38 即因此行编译失败。蓝牙直接走整页设置，所有版本通用。
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AuditLog.append(ctx, "系统功能", "蓝牙", "已跳转系统设置（需用户自己点一下）")
            speak("蓝牙设置打开了，同样这个开关系统不让 App 代点，你自己点一下")
        } catch (e: Exception) {
            AuditLog.append(ctx, "系统功能", "蓝牙", "跳转失败：${e.message}")
            speak("蓝牙设置没打开成：${e.message}")
        }
    }

    // ---------- 飞行模式：比 WiFi/蓝牙限制更死——普通 App 完全没有切换它的 API，
    // 连"面板"级别的半自动都没有（没有 Settings.Panel.ACTION_AIRPLANE_MODE 这种东西），
    // 唯一能做的只有带用户跳到系统设置里飞行模式那一页，让用户自己点，如实说明限制
    // 而不是假装能办。----------

    private fun openAirplaneModeSettings(ctx: Context, speak: (String) -> Unit) {
        try {
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            AuditLog.append(ctx, "系统功能", "飞行模式", "已跳转设置页（系统限制，无法代为切换）")
            speak("飞行模式这个开关，普通 App 没有权限直接切，系统设置页帮你打开了，自己点一下那个开关")
        } catch (e: Exception) {
            AuditLog.append(ctx, "系统功能", "飞行模式", "跳转失败：${e.message}")
            speak("设置页没打开成：${e.message}")
        }
    }

    // ---------- 系统级勿扰模式：和 JarvisPrefs 里 Announcer 自用的"时间段播报开关"是两回事——
    // 这里是真正的 Android 系统 DND（NotificationManager.setInterruptionFilter），切了之后
    // 全系统的通知声音/振动都会按 DND 规则走，不只是 Jarvis 自己安静。需要用户单独授予
    // "勿扰权限访问权"（ACCESS_NOTIFICATION_POLICY 是普通权限但这个具体操作需要特殊访问，
    // 和 NotificationListenerService 的监听权限是两个不同的开关，即使两个都在同一个
    // 设置入口附近，也必须分别申请）。----------

    private fun setSystemDnd(ctx: Context, on: Boolean, speak: (String) -> Unit) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } catch (_: Exception) {
            }
            AuditLog.append(ctx, "系统功能", "系统勿扰", "未授权，已跳转授权页")
            speak("系统勿扰模式还没给 Jarvis 授权，设置页帮你打开了，找到 Jarvis 把权限开一下，再说一遍就行")
            return
        }
        try {
            nm.setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            AuditLog.append(ctx, "系统功能", "系统勿扰", if (on) "已开启" else "已关闭")
            speak(if (on) "系统勿扰模式开了，只有重要通知会提醒" else "系统勿扰模式关了")
        } catch (e: Exception) {
            AuditLog.append(ctx, "系统功能", "系统勿扰", "失败：${e.message}")
            speak("勿扰模式没切成：${e.message}")
        }
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

    // 第二梯队批六新增：场景联动——技能的 sys 步骤（见 skill/SkillRunner.kt）直接调这个，
    // 不走上面那套语音触发词匹配。[action] 用固定英文关键字（写技能脚本用），不是中文触发词，
    // 两边刻意分开：触发词要照顾"人话怎么说"，技能脚本里的 action 要照顾"好不好手写不出错"。
    private val SCENE_ACTIONS = setOf(
        "dnd_on", "dnd_off", "torch_on", "torch_off",
        "volume_up", "volume_down", "wifi_panel", "bt_panel", "airplane_settings"
    )

    /** 返回 true 表示 [action] 是认识的场景动作、已经执行过了（成功与否、详情都经 [speak] 报告）。 */
    fun runSceneAction(ctx: Context, action: String, speak: (String) -> Unit): Boolean {
        when (action) {
            "dnd_on" -> setSystemDnd(ctx, true, speak)
            "dnd_off" -> setSystemDnd(ctx, false, speak)
            "torch_on" -> setTorch(ctx, true, speak)
            "torch_off" -> setTorch(ctx, false, speak)
            "volume_up" -> adjustVolume(ctx, AudioManager.ADJUST_RAISE, "音量调大了", speak)
            "volume_down" -> adjustVolume(ctx, AudioManager.ADJUST_LOWER, "音量调小了", speak)
            "wifi_panel" -> openWifiPanel(ctx, speak)
            "bt_panel" -> openBluetoothPanel(ctx, speak)
            "airplane_settings" -> openAirplaneModeSettings(ctx, speak)
            else -> return false
        }
        return true
    }

    /** 场景动作的合法关键字列表，给设置页"新建技能"做提示用。 */
    fun sceneActionHints(): String = SCENE_ACTIONS.joinToString("/")

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
