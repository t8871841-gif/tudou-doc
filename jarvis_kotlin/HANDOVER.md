# 交接说明（给继续开发的 AI）

## 项目
Jarvis 纯 Kotlin 版。包名 `com.jarvis.app`，目录 `jarvis_kotlin/`。
背景：原 v3 是 Flutter 版（仓库 `jarvis_app/`），真机体验差（悬浮球不灵、无障碍不稳），
用户拍板改纯 Kotlin 原生重写。功能与人格是 Jarvis 自己的，只借鉴 Andee 的工程做法。

## 已完成（一期，代码已入库，未经构建验证——本机无 Android 工具链）
- Gradle 脚手架：AGP 8.3.1 / Kotlin 1.9.22 / Gradle 8.4 / JDK 17，阿里云 Maven 镜像
  （沿用仓库 `andee/` 已验证的版本组合）。
- 从 Flutter 版 `jarvis_app/android` 搬运的原生层（已去 Flutter 化、统一包名）：
  - `JarvisAccessibilityService.kt` 无障碍服务（节点树感知 perceiveScreenTree、
    截图 captureScreen、手势 tapXY/swipeScreen、任务引擎 startTask/cancelTask、黑名单）。
    改动一处：onUnbind 返回 true，允许被系统回收后自动重绑。
  - `OrbController.kt` 悬浮球（三层渐变球、短按回执+1.5s 兜底拉起、双击急停、气泡）。
    原生版未接 eventSink，短按走其内置兜底 launchApp 拉起 MainActivity(extra jarvis_action=assist)。
  - `JarvisForegroundService.kt` 常驻保活服务（通知带停止动作）。
  - `JarvisNotificationListener.kt`、`JarvisTileService.kt`（磁贴唤起）、
    `JarvisVoiceService.kt`（语音交互服务声明类）、`ReminderScheduler.kt`（含两个 Receiver）。
- 新增原生 `MainActivity.kt`：连接自检屏。无障碍/悬浮窗/电池豁免/常驻服务/悬浮球
  五项状态一屏看全，每项有按钮直接跳系统设置开启；含"读取当前界面"测试按钮。
- 构建：GitHub Actions workflow `build-jarvis-kotlin-apk.yml`（放在仓库 .github/workflows/，
  本压缩包根目录附了一份），推 `jk-v*` 标签触发，产物 Release `jarvis-kotlin-apk-<run号>`；
  本地 `./gradlew :app:assembleDebug`（需先 sdkmanager 装 platform 34 / build-tools 34）。

## 未完成需求（用户会另发详细需求，以下是路线图）
- 二期：对话 + 语音。对话走 Agnes（https://apihub.agnes-ai.com/v1，模型 agnes-3.0-flash，
  Key 由用户在设置里填，不要写死）。语音：说=微软 Edge 云扬 TTS（免费无 Key），
  听=sherpa-onnx 流式 zipformer 中英双语离线识别。这两件已有通过 CI 编译的 Kotlin 实现：
  仓库 git 历史提交 ce22c12 的 `jarvis_andee/.../tts/EdgeTts.kt` 与 ae04c76 的
  `jarvis_andee/.../asr/OfflineAsr.kt`（该目录后被删除，用 git show 取回），直接复用。
- 三期：看屏跨应用借鉴 Andee（仓库 `andee/` 的 `screen/ScreenController.kt`）：
  节点树优先、截图网格标定（A1–H12 格号+格内分位）、zoom_screen_region 放大准星、
  同位置反复点击防呆、接管时彩光边框。再把 Flutter 版 v3 的急停/审计日志/自检页/
  技能固化逐项搬回（逻辑参考 `jarvis_app/lib/` 的 Dart 实现）。

## 约定
- UI 原则：每屏只做一件事、少堆砌（用户明确要求，参考 Andee 的克制）。
- 人格：自称 Jarvis，称呼用户"先生"，默认中文，语气克制干练，失败直说。
- 分期推进：每期做完先向用户汇报，等用户发话再进入下一期，不要连轴构建。
