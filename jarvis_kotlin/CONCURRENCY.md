# 并发与生命周期治理（框架第 3 步，2026-11-13）

全仓 @Volatile 共 135 处（36 个文件，jarvis_kotlin 模块内）。计数只数注解行
（@Volatile 单独成行）；注释/KDoc 里提到这个词的不算——复审第 2 批订正：
之前 OfflineAsrSession、EdgeTts 的数就是这么多算 1 处的。这一步不宣称全查完：
热路径模块逐项核对过，其余模块只列数量、留待以后改动时按规则补核对。规则先
立，清单后补。

## 规则（三条，不能错）

1. **每个共享可变状态只能有一个 owner**。谁建、谁改、谁清，写进该状态旁边
   的注释；不许出现“不知道谁会清”的状态。
2. **每种死法都要走同一套清理**。解码器死、被顶掉、用户喊停、来电、服务
   掉线——不管哪条路，最后都到同一个清理点（关 socket、停 future、放轨道、
   弃焦点、清缓存），不许各处零散清。
3. **新状态先写 owner 再写代码**。新加 @Volatile 或共享 var 时，同一提交
   里写明 owner、谁读写、清理点在哪；只加状态不加清理点的提交不合规。

## 已核对的模块（2026-11-13，逐项读过）

### tts/EdgeTts.kt（18 处）——owner：EdgeTts 自己；统一清理点 stop()
- `mySeq`：代际计数，stop() 里自增；`cancelled(seq)` 是唯一的“还有效吗”判据。
- `socket` / `prefetchSocket`：各归各的线程；stop() 全关；解码器死时也关
  prefetchSocket（排雷 A 批：阻塞的 SSLSocket 读不响应 interrupt，只 cancel
  叫不停僵尸预取）。
- `track`：runSynthesis 建，finally 里关轨/释焦点/发 onDone(false)；stop()
  也释。建轨本身包 try/catch（排雷 D 批），失败报 onDone(false) 并关看门狗。
- `focusRequest` / `focusManager` / `focusHeld` / `focusSeq` / `focusPaused`：
  焦点状态，acquire/abandon 成对；finally 里释焦点。
- `lastProgressAt` / `stallFired` / `stallWatchdog` / `stallWatchdogSeq`：失速看门狗，arm/disarm
  成对；20 秒无进展调 stop()。stallWatchdog 与 stallWatchdogSeq 的 @Volatile 是
  复审修法批补的（原来没有——arm/disarm 跑 io 线程，Runnable 触发时跑主线程）。
- `handshakeRetryAfterMs` / `earconQuietUntil` / `earconLastPlayAt`：时间戳，
  自己过期，不用清。
- `callListenerAttached`：一次性挂接守卫，监听器活到进程结束，设计如此。
- `lastWriteDiag`：诊断串，每次写失败重写，不用清。

### task/TaskLoop.kt（5 处）——owner：TaskLoop；清理点 run() 的 finally
- `running`：三处清（run() 的 finally、runBlocking 末尾、服务为空的早退路；
  早退路的注释写明了为什么——不然 running  forever true 会挡住后面的任务）。
- `currentAlive` / `currentAliveSeq`：随 running 一起清。
- `cachedImePackages`：每任务开始时清（排雷 B 批），查已启用的输入法列表。
- `lastProjectionUnavailableLogAt`：日志节流时间戳，不用清。

### screen/ScreenController.kt（10 处）——owner：ScreenController
- `cachedImePackages`：同 TaskLoop，每任务由 runBlocking 调 clearImeCache() 清。
- `lastDumpPkg`：每次 dump 重写；`eidEpoch`：每次 dump 自增（s-id 的 epoch）。
- `zoomRegion` / `zoomAtMs` / `zoomSize`：zoom 状态，带时间戳，手势/回退
  即失效（ZOOM_CONFIRM_MS）。
- `observedSize` / `observedSizeAtMs`：屏幕尺寸缓存，带时间戳。
- `typedBlindAt` / `screenMovedAtMs`：时间戳，不用清。
- `shotGuard` / `gestureGuard`：**已删**（2026-11-13 复审修法批：全仓无赋值，
  球的躲避与截图隐藏走 OrbController 直接调用；字段与 `?:` 包装、7 处调用点一并清理）。

### JarvisAgent.kt（11 处）——owner：JarvisAgent
- `genSeq`：代际计数，状态切换/急停自增，老的异步回调靠它失效。
- `callActive`：通话状态，由 CallState 回调置；10 分钟超时复位在
  device/CallState.kt（jk-v67）。
- `state` / `lastPartialShown` / `lastUserText`：主线程状态与回显缓存。
- `onStateChanged` 等回调：MainActivity 接线时一次性赋值。

### JarvisAccessibilityService.kt（9 处）——owner：服务自己
- `instance` / `screenController`：onServiceConnected 建，onUnbind 与
  onDestroy 两处都清（服务重建不会拿到死服务的控制器）。
- `armedUntil` / `state` / `awaiters`（武装点击）：显式解除武装、超时转态、
  回调取走即清（synchronized）。
- `allowedPackages` / `userWhitelist` / `lastCaptureError`：配置与报错串。

### tts/TtsRouter.kt（1 处） / device/CallState.kt（4 处）
- `TtsRouter.stopSeq`：代际计数，stop() 自增。
- `CallState.inCall` / `started` / `inCallSinceMs` / `permissionMissing`：
  通话状态旗，10 分钟超时复位（jk-v67），权限缺失只记不抛。

## 未逐项核对的模块（只列数量，改动时按规则补核对）

- asr/OfflineAsrSession.kt 13、asr/OfflineAsr.kt 4、wake/WakeWord.kt 6、
  tts/SystemTts.kt 6、screen/GroundingLog.kt 5、task/ShizukuHelper.kt 4、
  net/DeepSeekLlmClient.kt 4、command/DeviceCommands.kt 3、
  task/TaskResumeState.kt 3、wake/WakeWordEngine.kt 2、wake/WakeTemplates.kt 2、
  task/BrowserController.kt 2、screen/TapMarkerUi.kt 2、net/CustomClient.kt 2、
  net/AutoGLMClient.kt 2、config/ScenePresets.kt 2、OrbController.kt 2、
  JarvisNotificationListener.kt 2，其余 1 处的文件：wake/WakeMic.kt、
  vision/ScreenVisionAgent.kt、task/TripLoop.kt、skill/SkillResumeState.kt、
  skill/SkillDatabase.kt、screen/TreeHelper.kt、screen/ScreenCapture.kt、
  memory/MemoryDatabase.kt、command/PendingClarification.kt、MainActivity.kt、
  JarvisForegroundService.kt。

## 历史教训（这套规则就是从这里长出来的）

- **预取僵尸（2026-11-13，排雷 A）**：解码器死时只 cancel(true)，阻塞的
  SSLSocket 读不响应 interrupt，单线程 prefetchIo 被占到 45 秒 soTimeout。
  违反规则 2：清理点没覆盖“解码器死”这条死法。
- **输入法缓存从不清（2026-11-13，排雷 B）**：两处 cachedImePackages 只写
  不清，注释“下次跑任务重查”是空话。违反规则 1：没人是清理的 owner。
- **建轨没包 try/catch（2026-11-13，排雷 D）**：build() 抛异常绕开所有
  诊断、onDone 也不回。违反规则 2：失败路径没进统一清理。
- **计数方法（2026-11-13，复审第 2 批）**：之前是简单 grep 文本数 "@Volatile"
  出现次数，注释里提到这个词的也被算进去（OfflineAsrSession 88 行注释、
  EdgeTts 复审批注释各 1 次）。现改为只数注解行；EdgeTts 16→18（复审批
  F8 补的 2 处）、ScreenController 12→10（复审批 F7 删的 2 处）、
  OfflineAsrSession 13（G3 把 gotAnyFinalOrError 提成对象级 @Volatile 后是
  13 处真注解，之前是 12 处 + 1 次注释提及）。

## 开放项

- ~~`ScreenController.shotGuard` / `gestureGuard`~~：已删（2026-11-13 复审修法批）。
- 未逐项核对的模块：下次改到哪个文件，就把那个文件的清单补进上面。
