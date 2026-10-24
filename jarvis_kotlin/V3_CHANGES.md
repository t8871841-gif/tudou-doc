# Jarvis v3（纯 Kotlin）改动记录

> 每批开始前先读这份文件确认进度，不重做已完成的部分。

## 一期（已完成，随基线代码一起入库）
内容见 `HANDOVER.md`：工程骨架 + 从 Flutter 版搬运的无障碍服务 / 悬浮球（含第12、13条修复）/
常驻服务 / 通知监听 / 磁贴 / 语音服务声明 / 提醒调度 + 原生自检主界面。未经构建验证。

---

## 二期（本次，对话 + 语音）

### 做了什么
1. **对话**：新增 `net/AgnesClient.kt`，走 Agnes 的 `/chat/completions`（OpenAI 兼容格式），
   模型固定 `agnes-3.0-flash`，Key 从设置读取（`JarvisPrefs`，SharedPreferences，不写死）。
   维护进程内滚动对话历史（最多 12 轮，不落盘——持久化记忆是需求第 9 条，未排期）。
2. **语音合成**：新增 `tts/EdgeTts.kt`，微软 Edge Read Aloud 的免费 WebSocket 协议重新实现
   （不需要 Key）。详见下面「与需求文档的偏差」。
3. **语音识别**：新增 `asr/SystemAsr.kt`，**用 Android 系统自带 `SpeechRecognizer` 代替需求文档
   指定的 sherpa-onnx 离线识别**——这是本批最大的降级项，原因和影响见下方偏差说明。
4. **总调度**：新增 `JarvisAgent.kt`，接管 `OrbController` 的 `eventSink`/`dartAlive`
   （原来这是 Dart 侧接的口子，现在纯 Kotlin 直接接）。状态机：
   `idle → listening → thinking → speaking → idle`，悬浮球短按 / 磁贴 / 数字助理入口 / 唤醒词
   命中，都走同一套"听→问→答→播"流程；双击急停（`OrbController.emergencyStop`）会取消在飞的
   网络请求、停止 TTS、停止识别，回到 idle。
5. **第 6 条·打断说话**：降级版，见下方偏差说明。设置里有开关可以关掉。
6. **第 5 条·唤醒词**：降级版，见下方偏差说明。设置里可开关、可调"灵敏度"
   （目前灵敏度只影响关键词匹配的宽松度，还没做到真正的 VAD 参数联动）。
7. **第 7 条·主动性**：本批**没有做**——按文档"每期只做指定条目"的约定，对话+语音打通优先，
   来电/短信播报摘要、出发提醒留到下一次主动性专项再做，没有顺手做掉。
8. 设置界面：新增 Agnes Key 输入框、唤醒词开关 + 灵敏度滑条、打断说话开关；自检页新增
   "Agnes 对话 Key / 系统语音识别 / 语音合成" 三行状态。
9. 新增依赖：`com.squareup.okhttp3:okhttp:4.12.0`（Maven Central，经阿里云镜像可解析），
   用于 Agnes 的 REST 请求和 Edge TTS 的 WebSocket 连接。

### 与需求文档的偏差（如实标注，没有假装做到）

| 项目 | 文档要求 | 实际交付 | 原因 |
|---|---|---|---|
| 语音合成 | 直接复用仓库 `tudou-doc` 历史提交 `ce22c12` 的 `tts/EdgeTts.kt` | 按 Edge Read Aloud 公开协议**重新实现**了一份功能等价的 | 本次交付环境没有那个仓库的访问权限（无 git 凭据/无法 `git show`），取不到原文件 |
| 语音识别 | 直接复用提交 `ae04c76` 的 sherpa-onnx `asr/OfflineAsr.kt`（流式 zipformer，真离线） | 用 Android 系统自带 `SpeechRecognizer` 顶上，**不是真离线**，多数机型走联网识别 | 同上没有仓库权限；另外 sherpa-onnx 的 Android AAR 官方是从 GitHub Releases 下载预编译包，不在 Maven Central/阿里云镜像里，本次环境又没有公网访问，没法下载模型文件和 AAR 来验证集成 |
| 打断说话 | 第 6 条，完整做不到可交降级版 | 降级版：播报期间开一路系统识别监听，非空中途识别文字即判定"在说话"→停播转听；**没有回声消除**，设置里有开关可关 | 完整 AEC 需要额外的音频处理能力，超出本批范围 |
| 唤醒词 | 第 5 条，离线 KWS 做不到可交降级版 | 降级版：idle 时反复跑 `SystemAsr` 短轮询，命中"贾维斯"等关键词即唤醒；耗电未实测 | 同语音识别的原因，没有离线 KWS 方案可用 |

**这两处识别相关的降级，是本批影响最大的地方**，如果你们能把下面任一样东西发过来，我下一批就去替换
成真正的方案（`JarvisAgent`/`OrbController` 只认 `SystemAsr` 暴露的
`listenOnce()`/`stop()`/`isAvailable()` 这几个接口，换成 sherpa-onnx 实现时上层代码基本不用动）：
- 仓库 `tudou-doc` 提交 `ce22c12`/`ae04c76` 的文件内容（直接贴文本即可，不需要仓库访问权限）；或
- sherpa-onnx 对应版本的 Android AAR + 中英双语流式 zipformer 的 onnx 模型文件（或者告诉我确切的
  下载直链，我在有网络的环境里取）。

### 验收自测结果
**没有在真机上跑过**——本次交付环境没有 Android 工具链也没有公网访问，连编译都没法做，更不用说真机验收。
按需求文档的"不许假装完成"原则，这里不编造测试结果。首次在魅族 20 Pro 上跑起来后，建议按这个顺序测：
1. 设置里填 Agnes Key → 悬浮球短按 → 说一句话 → 能不能听到播报；
2. 播报过程中开口说话 → 是否会被自己的播报声误打断（这是已知风险点，见上表）；
3. 开启唤醒词开关、把手机晾在一边，喊"贾维斯" → 能不能被唤醒；同时观察半小时到一小时的掉电速度，
   如果发热/掉电明显，先把唤醒词关掉。

### 新增权限
无。一期已经申请了 `RECORD_AUDIO`、`INTERNET`，二期复用，没有再加权限。

### 未排期 / 本批没碰
第 9、10、11 条（记忆第二代、本地模型兜底路由、入口补全）按约定不顺手做。

---

## 三期-A（本次，屏幕理解 + 审计日志 + 急停确认）

### 做了什么
1. **第 4 条·屏幕理解（视觉看屏）**：新增 `vision/GridOverlay.kt` + `vision/ScreenVisionAgent.kt`。
   节点树（`perceiveScreenTree`/现有任务引擎）优先，读不到/读不全（图片类、自绘控件类界面）时才
   走这条视觉兜底通道：
   - **网格标定法**：截图叠加 8 行(A-H)×12 列(1-12) 标号网格发给视觉模型，模型只许回答
     "格子号 + 格内三分位置(左/中/右)"，不许直接报像素坐标——报原始像素坐标的视觉模型普遍不准，
     报"大概在哪个格子的哪一块"反而稳；
   - **区域放大准星**：模型自己觉得不确定时可以要求"再给我一张放大图"，会把目标格子连同上下
     左右各扩一格裁出来放大 3 倍、画上红色十字准星，再问一次相对比例坐标，减少小控件点不准；
   - **同位置连点防呆**：同一次定位任务里，换算出的点击坐标连续第 4 次落在同一片小区域（按屏宽
     3% 判定），直接拒绝继续点击，停下来告诉用户"可能找错了"，不让模型原地打转瞎点；
   - **接管状态可见**：`TakeoverBorder.kt`，看屏代理在跑的时候，屏幕四周显示跑马灯式动态彩光
     边框（不拦截触摸），任务结束/急停立即消失；
   - 语音入口接了两种问法：「点一下/点击/帮我点 XX」→ 视觉定位并点击；
     「这上面/屏幕上/这个按钮是什么…」→ 截图问视觉模型、直接用自然语言回答后朗读。
   - 复用 Agnes 的同一个 Key/模型配置发多模态请求（`AgnesClient.askVision`），**没有新增 Key 配置项**；
     如果 `agnes-3.0-flash` 实际不支持图片输入，这条链路会从 HTTP 响应直接报错，不会假装能用。
2. **第 2 条·操作审计日志**：新增 `AuditLog.kt`，本地明文 JSON Lines（`filesDir/jarvis_audit.log`），
   记录"任务描述 + 做了什么 + 核验结果"三元组；验证码(连续4-8位数字)和任何带"密码/验证码"字样的
   内容落盘前整段脱敏。接入点：视觉看屏的每一步、以及现有跨应用任务引擎(`startTask`/`finishTask`)
   的每次任务结束。主界面新增"操作审计日志"折叠区块，可查看/可清空，默认收起不占屏幕。
3. **第 3 条·完整急停**：确认现有实现已经满足验收标准，**本批没有新增代码**，只是把它接进了
   新功能——`cancelTask()`（悬浮球双击 / 音量上下键 600ms 内同按）现在同时会：取消视觉看屏的
   在飞请求、隐藏接管边框、取消跨应用任务引擎当前任务；`JarvisAgent.hardResetToIdle()`
   （对话流程里的急停）也会同步调用这几个取消点。

### 与需求文档的偏差 / 简化
- **技能固化（第 8 条）本批没做**：需求文档里写的是"排在第 4 条之后做"，这批先把第 4 条落地，
  技能固化放下一批，没有顺手做掉。
- **视觉模型只是"复用 Agnes 配置"，没有专门的多模态模型选型**：如果联调发现 `agnes-3.0-flash`
  不支持图片输入，看屏相关功能会直接报错，需要你们确认 Agnes 那边是否有专门的多模态模型名，
  或者告诉我要不要新增一个单独的"视觉模型"配置项。
- **审计日志只做了"一行摘要"粒度**，没有做成每一步操作单独一条、也没做本地加密存储（文档没明确
  要求加密，先按"明文但脱敏"交付；如果需要加密存储，下一批再加）。
- **接管彩光边框**用的是对角线平移的线性渐变模拟跑马灯效果，不是真正的逐像素跑马灯动画，
  没有真机验证观感，如果效果不对味（比如太晃眼/不够显眼），告诉我改。
- **同位置连点防呆的"同一片区域"判定**是按屏宽 3% 简化的，没有换算成文档例子里的"dp"单位，
  不同分辨率设备上的实际容差会有差异，先用这个简化版本，有问题再调。

### 验收自测结果
同样**没有在真机上跑过**——环境限制同二期。真机验收建议：
1. 打开一个图片类/自绘控件类 App（比如某个小程序界面），说"帮我点一下 XX" → 看是否真能定位点击；
2. 故意多问几次让它一直点不中同一个地方 → 看第 4 次是否会自己停下来；
3. 看屏任务跑起来的时候看屏幕边缘有没有彩光边框，任务结束/急停后是否立刻消失；
4. 主界面展开"操作审计日志"，确认跨应用任务和看屏动作都有记录，且验证码类内容显示的是脱敏文字。

### 新增权限
无。截图能力复用一期已有的 `takeScreenshot`（API 30+，已在 `JarvisAccessibilityService` 里）；
悬浮边框用 `TYPE_ACCESSIBILITY_OVERLAY`，走无障碍服务权限，不需要额外申请悬浮窗权限。

### 未排期 / 本批没碰
第 8 条技能固化（下一批）；第 9、10、11 条仍未排期。

---

## 补丁批次（本次）：换上真离线识别，修正 Edge TTS 协议

你们发来了 `for_claude_reference.zip`（`tudou-doc` 提交 `ce22c12`/`ae04c76` 的原文件 + README），
这一批把二期/三期-A 里悬着的两个降级项处理掉了：

### 1. 语音识别：从系统 SpeechRecognizer 换成真·sherpa-onnx 离线识别
- 新增 `asr/AsrEngine.kt`（移植，只改包名）、`asr/OfflineAsr.kt`（移植，去掉没用到的
  `VoiceConfig` 构造参数，其余逻辑原样）。
- 新增 `asr/OfflineAsrSession.kt`：原参考实现的 `AsrEngine` 是"调用方喂 PCM、自己管麦克风"的
  形状，而 `JarvisAgent` 这边要的是"喊一声就返回文字"的 `listenOnce`/`stop`/`isAvailable`——
  这个新文件就是两者之间的适配层：开 `AudioRecord`、读 16kHz/16bit PCM 喂给引擎、自己做
  "安静多久算说完"的判断（简单能量阈值，**沙盒里写的，没有真机调过**，阈值在
  `OfflineAsrSession.kt` 顶部，真机如果"该停不停"或"没说完就被打断"，先调这三个常量：
  `VOICE_RMS_THRESHOLD` / `SILENCE_MS` / `MAX_WAIT_FOR_SPEECH_MS`）。
- `JarvisAgent.kt`、`MainActivity.kt` 里的调用从 `SystemAsr` 换成 `OfflineAsrSession`，
  方法名和调用方式没变，上层代码基本没改动（只改了 import 和类名）。
- **编译前提**（二进制文件，不在这次的 zip 里，需要你们自己放，放哪里 / 文件名必须是什么都有
  README 放在对应目录里了）：
  - `app/libs/sherpa-onnx-1.12.23.aar`（见 `app/libs/README.md`）
  - `app/src/main/assets/asr/` 下 4 个模型文件（见该目录下的 `README.md`）
  缺任何一样：缺 aar → Gradle 直接配置失败，编译不过；缺模型文件 → 能编译能装，但
  `OfflineAsrSession.isAvailable()` 返回 false，自检页如实显示"缺模型文件"，识别请求会报
  "模型加载失败"，不会假装能用。
- 唤醒词（第 5 条）现在也是跑这套真离线识别做轮询，比之前砸在线识别省电一些，但本质上还是
  "反复完整识别一遍、文字里找关键词"，不是专门的低功耗 KWS，耗电仍未实测。

### 2. 语音合成：Edge TTS 协议端点/认证方式修正
- `tts/EdgeTts.kt` 整个换成参考实现的版本：端点从 `speech.platform.bing.com` 改成
  `api.msedgeservices.com`，认证加上 `Sec-MS-GEC`（Windows ticks 向下取整 5 分钟 + 公开令牌的
  SHA-256 大写 hex），手写 TLS socket + WebSocket 帧（不用 OkHttp——握手成功后服务端带
  `Connection: close`，OkHttp 可能直接判定握手失败）。
- 播放方式也跟着参考实现改了：MP3 用 `MediaCodec` 解码成 PCM，`AudioTrack` 流式播放
  （边解码边播），不再是"等整段下载完写成 mp3 文件再丢给 MediaPlayer"——首字延迟更低，
  也省了一次磁盘读写。
- 默认音色从我瞎猜的 `zh-CN-XiaoxiaoNeural`（女声）改成参考实现指定的
  `zh-CN-YunyangNeural`（"云扬"，男声，需求文档点名的那个）。
- 对外调用方式（`EdgeTts.speak/stop/isSpeaking`）没变，`JarvisAgent` 不用改。
- **这版协议本身在 Flutter 版上真机验证过**（参考实现 README 原话），比我第一版瞎重写的
  bing.com 旧端点可信得多，但 Kotlin 这边的移植本身**仍然没有真机跑过**，手搓 WebSocket 帧
  这种东西差一个字节都可能全挂，真机第一次联调如果听不到声音，优先去对日志里
  `fetchMp3 failed` / `Edge 语音握手失败` 这类报错，大概率是帧格式或者 `Sec-MS-GEC` 计算
  哪里跟服务端当前实现对不上。

### 验收自测结果
依然**没有在真机上跑过**——环境限制同前几批，且这次连 aar/模型文件都没有，本地也没法做
"编译能不能过"这一层验证。真机验收顺序建议：
1. 先把 aar 和模型文件放好，跑一次编译，能过再说别的；
2. 设置里跑一次语音对话，确认能听到"云扬"的声音（不再是之前的小夏女声）；
3. 对着手机说一句话，看屏幕上（或者日志里）走的是本地识别而不是弹系统识别框；
4. 开着唤醒词待命一段时间，感受一下耗电/发热，跟之前系统识别版本比一比。

### 新增权限
无。AudioRecord 复用已有的 RECORD_AUDIO 权限。

### 未排期 / 本批没碰
第 8 条技能固化仍然排在下一批；第 9、10、11 条仍未排期。

---

## 对齐轮次 · 批一：主页 HUD 复刻

参照 `flutter_reference/home_screen.dart` + `hud_painters.dart`，把原来那个"自检表单 + 简易聊天"的
主页换成深夜蓝 HUD 风格，和 Flutter 原版同一套视觉语言。

### 做了什么
1. 新增 `hud/` 包：
   - `HudPalette.kt`：配色表（天蓝/玫瑰/薰衣草紫/浅青）+ 状态→主色映射，数值照抄
     `hud_painters.dart` 的常量；
   - `GradientLayerView.kt`：静态线性渐变层（背景对角三色渐变 + 顶/底压暗叠层），
     `GradientDrawable` 在 minSdk 24 上不支持自定义渐变 stop，改用 Canvas+Shader 自己画；
   - `HudBackgroundView.kt`：移植 `GridBackgroundPainter`（极淡点阵）+ `AuroraPainter`
     （三团随状态变色、缓慢漂移的大光晕），合并成一层，用 `Choreographer` 按帧驱动；
   - `HudStageView.kt`：移植 `ReactorPainter`（中央光球核心逻辑）——大光晕、三团柔焦星云
     光球、高光核心、细光环+渐隐彩弧、虚线外环、霓虹扫光环、72 根随能量伸长的声波点阵、
     状态切换涟漪，取值基本照抄 Dart 版的 `step()`/`paint()`。
2. `JarvisAgent.kt` 加了 `onStateChanged` 回调 + `currentState()`，`OfflineAsrSession.kt` 加了
   `currentLevel()`（监听时的实时电平，缩放系数是猜的，没有真机校过响度）——HUD 舞台靠这两个
   读当前状态和"聆听时声波跳动"。
3. `MainActivity.kt` 整个重写：背景三层（渐变→极光点阵→压暗叠层）+ 内容列（顶栏 + 330dp 舞台 +
   对话气泡流 + 底部静音/麦克风/键盘切换）。气泡样式照抄 `_MessageTile`：用户气泡是主题色
   斜向渐变、右上角直角；Jarvis 气泡是半透明玻璃感、左上角直角。自检/设置挪到舞台左上角齿轮
   图标后面的二级页，内容和之前交付的一字不差，只是入口从顶栏文字按钮换成了齿轮图标（更贴近
   原版"点齿轮开设置"的交互）。
4. 顺手加了静音按钮实接（`JarvisPrefs.isMuted`，开着的时候 `JarvisAgent` 直接跳过 EdgeTts，
   只留文字气泡）——原版有这个按钮图标，之前的简易对话页没有真正接上开关逻辑，这次顺手接了。

### 与 Flutter 原版的偏差（有意简化，如实列出）
- **没有开机页**：原版有一个"旋转光环 Logo + 呼吸 + 点击进入"的 boot screen，这边直接进主页，
  没有做这个一次性动画。
- **没有地球贴图**：原版舞台圆环里嵌了一张会公转的地球图片（`assets/images/earth_bg.jpg`），
  这边没有这张图，直接不画这一层，舞台只保留光球/光环本身。
- **没有 FxPainter 的粒子轨迹和两颗卫星**：这是视觉上最次要的点缀层，先略过，不影响状态可读性。
- **没有状态读数条**（POWER/LATENCY/SIGNAL 等装饰性数字滚动条）：纯装饰，没做。
- **没有打字机逐字输出**：原版的助手回复是一个字一个字"打"出来的，这边是整条消息一次性显示。
- **舞台画法是按 Dart 源码里的数值重新实现的 Canvas 绘制，没有逐帧跟原版比对过**——
  手写 `RadialGradient`/`SweepGradient`/`BlurMaskFilter` 这类效果，Android Canvas 和
  Flutter Canvas 的渲染细节不完全一致（比如 `BlurMaskFilter` 和 Flutter 的
  `MaskFilter.blur` 模糊半径换算不是严格等价的），观感上"差不多是那个意思"，不是像素级一致。
- **唤醒灵敏度还是滑杆**：文档三批计划里批二要把它换成"严格/标准/宽松"三档，这批还没动，
  滑杆原样留着。

### 验收自测结果
依然**没有在真机上跑过**（环境限制同前几批）。真机验收建议：
1. 打开 App，看背景是不是深夜蓝渐变 + 淡点阵 + 三团缓慢漂移的光晕；
2. 看中央光球是不是在呼吸（待命态应该是天蓝色，说一句话之后应该变玫瑰色→薰衣草紫→浅青，
   跟着"听/想/答"的节奏走）；
3. 点麦克风说话，看看声波点阵是不是跟着音量伸长；
4. 点舞台左上角齿轮图标，确认能进二级设置页、回到对话页内容还在（之前聊的消息没丢）；
5. 点静音图标，确认之后的回答只出文字气泡、没有声音。

### 新增权限
无。

### 未排期 / 本批没碰
批二到批六（设置分区、记忆第二代、技能固化、端侧小模型、主动性补完）都还没开始，按约定等确认
这批没问题再继续。

---

## 对齐轮次 · 批二：设置分区补齐上

参照 `settings_screen.dart`，把音色/唤醒/视觉/常驻这几块设置项补齐。

### 做了什么
1. **语音音色选择 + 试听**：新增 `tts/EdgeVoices.kt`（6 个公开稳定的 zh-CN Edge 神经语音），
   设置页加了下拉选择 + 试听按钮（复用 `EdgeTts`，点一下就播一句"您好，我是 Jarvis……"）。
   `EdgeTts.speak()` 的语速/音调从硬编码常量改成了可传参数，`JarvisAgent` 说话时从
   `JarvisPrefs` 读用户选的音色/语速/音调。
2. **唤醒严格度三档**：原来的 0..100 滑杆换成"严格/标准/宽松"三个按钮（没找到 Android 原生的
   分段按钮控件，用三个高亮态 Button 模拟，点中的那个不透明、其余半透明）。匹配逻辑也跟着改了
   （`JarvisAgent.onWakeResult`）：严格要求"贾维斯"在句首且整句短，标准要求在句首附近，宽松不限
   位置。另外加了"10 分钟内 3 次唤醒后没等到有效指令，临时收紧到严格档"的简单计数器，这部分
   没有真机数据支撑具体阈值，是按文档描述直接实现的，参数可能需要调。
3. **视觉看屏设置区**：启用开关、模型/接口地址/Key（留空复用 Agnes 配置，`AgnesClient.askVision`
   已改成按这个优先级取值）、坐标格式下拉、看屏白名单多行输入框。白名单保存时会调
   `JarvisAccessibilityService.setUserWhitelist()`，`JarvisAgent.start()` 启动时也会同步一次
   （之前这个白名单接口在原生层已经有了，是空的没人用，这批接上）。`locateAndTap`/`describeScreen`
   现在也会先检查这个开关有没有开。
4. **常驻与入口**：
   - "后台常驻待命"从"一次性按钮"换成了可记住的开关——开着的话下次打开 App 会自动尝试拉起前台
     服务（原来是无条件每次都偷偷启动，这批改成要显式开启才会自动启动，行为上更克制）；
   - 新增"连续对话"开关：开着的话语音回答说完会直接继续听，不用再喊一次唤醒词
     （`JarvisAgent.speakReply` 的 `onDone` 里判断）；
   - 新增"设为默认助手"/"授权读取通知"两个系统设置跳转按钮（电池优化豁免之前就有）。

### 与 Flutter 原版的偏差（如实列出）
- **音色列表不是从原版抄的**：`settings_screen.dart` 引用的 `EdgeTtsService.voices` 定义不在
  这次给的参考文件里，这份列表是我按公开资料整理的 6 个常见 zh-CN 语音，如果原版列表/顺序/默认项
  不一样，把原始定义发我对齐。
- **坐标格式设置目前不生效**：原版 `visionCoord`（pixel/norm1000）对应的是"直接问模型要坐标"的
  prompt 方式；这边三期-A 做的是"网格标定法"（格子号+左中右，不要坐标），两种方式不兼容，所以
  这个下拉框现在存了值但没接到任何逻辑上——设置页里已经写清楚了，不是漏做，是暂时用不上。
- **唤醒严格度的具体判定规则（句首/句首附近/句中任意）是我按文档描述实现的**，没有真机测过
  "严格"档会不会经常漏唤醒、"宽松"档会不会经常误唤醒，这几个数字（idx<=1、idx<=6、长度<=12）
  大概率需要真机调。
- **没有做**：来电/短信/微信播报开关（`_proactiveSection` 的播报部分）、日程出发提醒、晨报、
  联网搜索与天气、记忆查看/清空、已学会的流程对话框、端侧小模型导入——这些按计划分别属于批三
  （记忆）、批四（技能固化）、批五（端侧模型）、批六（主动性补完），这批没有顺手做。
- **"连续对话"的退出条件比较朴素**：现在是"一直连续听，直到用户不说话超时或手动双击急停"，
  原版这块的细节（比如是否也有轮次上限）没有在给的参考文件里看到，没法对齐，先按最直白的方式实现。
- **语速/音调目前没有设置页滑杆**（原版有 `_rate`/`_pitch` 两个 Slider），`JarvisPrefs` 已经把
  存取接口建好了（`getRatePct`/`getPitchHz`），但这批设置页 UI 上只放了音色选择，语速音调先留
  默认值，没加滑杆控件——如果需要，下一批几行代码就能补上，这批优先级上先放了严格度和视觉设置。

### 验收自测结果
依然**没有在真机上跑过**。真机验收建议：
1. 设置页选一个音色、点"试听"，确认声音真的变了，而不是一直用默认的"云扬"；
2. 把唤醒严格度切到"严格"，试试是不是明显更难被唤醒；切到"宽松"试试是不是更容易（也更容易被
   误触发）；
3. 开"连续对话"，问一句话，等它答完，不喊唤醒词直接接着说第二句，看看是不是真的在继续听；
4. 看屏白名单填一个包名，确认视觉点击任务在这个 App 里能工作，在名单外的 App（非黑名单）会报
   "not_whitelisted"；
5. 关掉视觉看屏开关，确认"点一下 XX"/"这上面是什么"会直接报"视觉看屏没开"，而不是卡住或崩溃。

### 新增权限
无（通知监听权限的跳转按钮用的是系统已有的 `ACTION_NOTIFICATION_LISTENER_SETTINGS`，这不是新
权限声明，只是一个设置页跳转）。

### 未排期 / 本批没碰
批三到批六（记忆第二代、技能固化、端侧小模型、主动性补完）仍未开始；设置页里语速/音调滑杆、
来电短信播报、晨报、记忆查看等也还没做，按约定等确认这批没问题再继续。

---

## 对齐轮次 · 批三：记忆二代（第 9 项）——Gemini 首交退回，Muse 以基线补齐

- 首交（Gemini）：记忆四件套（MemoryItem/Dao/Database/Service）结构完整，但缺 Gradle 依赖、JarvisPrefs 接口与基线对不上（把 object 当 class 实例化、文件名写错）、Agent 注入与设置页只有文字无代码、changelog 与实物不符——整批退回。
- 整改版（Gemini）：依赖三件套（toml/根 gradle/app gradle，KSP 1.9.22-1.0.17 / Room 2.6.1 / coroutines 1.7.3）与 Prefs 风格已改对且经 Muse 逐项核实；但 JarvisAgent 后半、MainActivity 后半、AgnesClient 尾部在粘贴中丢失，未送达部分无法核查。用户拍板：由 Muse 以仓库基线 + 已核实部分补齐，不再等 Gemini。
- Muse 补齐内容：①Gradle 三件套采用 Gemini 整改版（已核实为基线超集）；②JarvisPrefs 只加记忆总开关（memory_enabled，基线风格）；③memory/ 四件套采用 Gemini 整改版原文（仅加文件头注释）；④AgnesClient.ask 增加 memoryContext 参数（默认空，老调用零影响），记忆拼在 SYSTEM_PROMPT 后；⑤JarvisAgent.onUserUtterance 前置协程：先拦截显式记忆（命中直接确认回复），未命中取记忆注入问答；原视觉/问答逻辑拆到 handleUtterance 原样保留；⑥MainActivity：打字对话（sendChat）与语音同路（拦截+注入），设置页新增「记忆」分区（总开关/列表[分域+时间]/逐条删除/清空，MainScope 加载、onDestroy 取消）；⑦顺手修复存量编译错误：sendChat 的 onError 回调参数是 String 却写了 e.message（批一遗留，此前从未成功构建故未暴露）。
- 如实标注：**自然语言隐式提取未实现**，仅显式前缀（记住：/记住 /请记住：/请记住 ）写入 + 关键词分域；注入取最近 15 条全量拼接，未做相关性筛选。本批未构建，等用户明确开始再出包验证。

---

## 对齐轮次 · 批四：技能固化（第 8 项）——Muse 按自拟规格直接补齐（Gemini 包未及转交，用户改令 Muse 做）

- 新增 skill/ 包（独立 Room 库 jarvis_skills.db，不动批三记忆库）：SkillItem（name/triggerText/stepsJson/enabled/成功失败计数）、SkillDao、SkillDatabase、SkillService（CRUD + 包含式匹配：触发句被包含或含技能名，取最长命中——非语义匹配）、SkillTrace（点按轨迹，仅内存）、SkillRunner（重播引擎）。
- 步骤只四种：open（系统启动意图）/ tap（走 ScreenVisionAgent 网格看屏原语）/ wait / swipe（无障碍现成方法）。**input 输入步骤未做**——基线无已验证输入原语，不发明。
- 轨迹采集：ScreenVisionAgent 点按成功的唯一出口加一行 SkillTrace.recordTap（其余逻辑未动）。
- Agent 集成：handleUtterance 之前（记忆注入同一协程段）先做技能匹配，命中在 TAP_INTENT 之前重播；成功播报"执行完了"，失败播报"第 N 步失败：…，已停下"并计数。**失败不自动回退现场执行**（部分步骤可能已生效，重走会重复动作——评审决定，代码注释已注明）。
- 设置页新增「技能」分区：总开关（Prefs skill_enabled）、列表（步数/触发句/成功失败计数/启停/删除）、"把最近操作存为技能"（轨迹转 tap 步骤，触发句=技能名）、手动新建（开：/点：/等：/滑： 行格式，解析失败指明行号不存半截）。
- 本批未构建、未真机验证，等用户明确开始再出包。

---

## 对齐轮次 · 批五：端侧离线小模型（第 10 项）——移植旧 Flutter 版 LocalLlmBridge，Muse 落地

- 运行时：新增依赖 com.google.mediapipe:tasks-genai:0.10.24（与旧版桥同版本，只吃 .task；minSdk 24 与本工程一致；Maven Central 解析）。新包 local/LocalLlm.kt：逻辑移植旧桥（单线程串行加载/生成、LlmInferenceOptions.setModelPath/setMaxTokens、generateResponse 同步生成、失败即清引擎状态不假装），MethodChannel 换成进程内 suspend API。
- 与旧桥的差别（有意）：maxTokens 旧桥强制 ≥1024，本版设置可调 256..1024、默认 1024——用户那颗 gemma3-tudou.task 上下文上限就是 1024，旧桥的强制下限正好顶格、换模型易爆，故钳上限（已在 LocalLlm 文件头与设置页文案注明）。
- 导入：设置页「本地模型」分区——ACTION_OPEN_DOCUMENT 选 .task（请求码 5151，同旧桥）→ 复制进 filesDir/models（文件名清洗）→ 路径存 Prefs；分区含启用开关、优先本地开关、maxTokens 保存、试答一句、删除模型、状态行（模型名+大小）。无新权限。
- 路由（语音 Agent 与打字对话同路）：本地启用且模型就绪时——"优先本地"开：先本地生成（只喂原话，不带批三记忆注入：1024 上下文塞不下），答不出回云端；关：云端优先，Agnes 失败（断网/报错）才本地兜底，兜底成功时气泡注明"已用本地模型回答"，两头都失败才走原报错收尾。
- 未做/未验：未加对话模板（与旧桥一致直接喂原话，如真机答非所问再议）；未构建、未真机验证——本地生成速度与 gemma3-tudou.task 的实际加载结果要等出包后用户导入实测。

---

## 对齐轮次 · 批六：主动性补完（第 7 项）——接一期留下的钩子，Muse 落地

- 主动播报（新包 proactive/Announcer）：接上 JarvisNotificationListener 一期就留好的 announceOn/sink 钩子（此前无人接、是死钩子）。Listener 缓存 map 加了 call/msg 类别标记（来电/消息按系统通知类别判定，不猜包名）。决策顺序：总开关→静音→勿扰时段（默认 22:00–08:00，跨夜支持）→验证码类不读（关键词命中即跳过，沿用 v2 策略）→Agent 非 idle 时不插播→来电/短信独立开关、其他应用只播包名白名单内的。走 EdgeTts 播报并记审计。JarvisAgent.start 时 attach。
- 晨报：调度本来就在 ReminderScheduler（一期，scheduleDailyBrief），本批补三件——①设置页开关/时间（新增 isBriefOn/briefHour/briefMinute 只读口）；②ReminderReceiver 到点用 proactive/BriefComposer 现组稿（日期星期+今日日历事件+待触发提醒数；没授权如实说看不到；**不编天气**，无数据源），通知改 BigText 展示全文；③点开晨报/提醒通知现在会把正文送进对话并朗读（handleAssistIntent 新增 morning_brief/reminder 消费，此前点了没反应）。
- 出发提醒（降级版，需求原文允许）：proactive/DeparturePlanner 扫今日带地点的日程，按固定提前量（默认 30 分钟，设置可改）排 ReminderScheduler 提醒，文案明说"没有算路程"。**路程估算未做**（不接路线 API）。扫描时机：晨报到点 + Agent 启动 + 设置页手动按钮；同日程同 id 幂等覆盖。
- 设置页新增「主动播报」「晨报与出发提醒」两分区。无新权限（READ_CALENDAR/通知使用权一期已备）。
- 未验：未构建、未真机验证——播报时机、晨报闹钟在 Flyme 上的准点率、出发提醒扫描都需出包实测。

---

## 真机反馈修复批（jk-v11 装机后，用户逐项确认清单后实施，未构建）

背景：jk-v11 首次真机——HUD 主页正常、Key/模型导入/晨报勿扰设置都在；用户报 6 个问题，逐项修：
1. **本地模型回复乱码**（印地语+数字串复读）：LocalLlm 直喂原话是病根。生成改套 Gemma 对话模板（<start_of_turn> 格式 + 简短中文系统指令），输出加清洗：砍模板残留、同一片段连重复 6 次截断、数字/横线占比 >60% 判垃圾返回 null 让上层回退云端。
2. **没有语音输出**：查明旧 Flutter 版输出是 Edge + 系统 TTS 两条腿（SystemBridge 原生 TTS），Kotlin 版只搬了 Edge，且 speakReply 把 Edge 的失败回调当成功吞了。新增 tts/SystemTts（移植旧做法，懒初始化+中文语言）与 tts/TtsRouter（Edge 先、失败落系统 TTS）；Agent 播报/急停/打断、主动播报、晨报试听、通知点开朗读全改走 TtsRouter；设置里"试听"仍走 Edge 本体（要验音色），但失败会 toast 明示。
3. **没有语音识别的提示看不见**：beginListening 入口加录音权限预检，没权限直接在悬浮球气泡明示去哪授权，不再默默失败（重装后权限被清是主因，自检页可逐项查）。
4. **语音问答不进对话**：Agent 新增 onUserText/onReplyText 回调，MainActivity 注册后把用户的话和回复同步成对话气泡（打字路径不经 Agent，不会重复）。
5. **悬浮球放大 3 倍**：OrbController 56dp → 168dp（用户指定）。
6. **设置一长条**：改二级页面——设置先进目录（10 类），点进一类才展开，带"← 返回设置目录"；各分区构建函数原样复用（急切构建，refresh 等跨分区引用不受影响），顶部自检块拆为「功能自检」页。
- 本批未构建，等用户明确开始再出包（jk-v12）。

---

## 批八：jk-v12 真机反馈修复（用户定稿方案后实施，未构建，等 Claude 复核）

1. **指令学舌不跳转 → 新增本地指令层** `command/LocalCommands.kt`：查实 Kotlin 版语音链只有「点一下X」和看屏问答两类本地意图，「打电话给X」「打开X」从没搬过来，话全掉进大模型被反问。新增指令层插在 handleUtterance 最前（技能/聊天之前；打字路径 sendChat 同步接入）：打电话给<名字>→查通讯录（精确名优先）→跳拨号盘填好号码；打开<应用名>→按应用名查已装应用→拉起；带提问词（吗/是什么/为什么/怎么/如何）不拦截防误伤；清单里 READ_CONTACTS/CALL_PHONE 本就有，自检页加「申请通讯录权限」按钮，没权限时指令会先报明。
2. **回复没播完就跳聆听 → 打断收紧（A 方案）**：起播缓冲 500→1200ms；判定改「连续两次中间结果 ≥4 字」才算插话；OfflineAsrSession 录音会话挂 AcousticEchoCanceler+NoiseSuppressor+AutomaticGainControl（设备支持才生效）。
3. **语音一会云扬一会系统 → 引擎三档**：JarvisPrefs 加 tts_engine（auto 默认/edge/system），设置「对话与语音」加循环切换按钮；TtsRouter 按档路由，auto 与 edge 档下 Edge 失败先重试一次再说（auto 才落系统兜底）。
4. **悬浮球**：168dp → 120dp（用户改定）。
5. **主界面**：onCreate 加全屏标志隐藏系统状态栏；顶栏时钟连同 startClock 一并删除；**撤全屏黑色半透明渐变膜**（盖在背景之上的第三层 GradientLayerView，上 65% 黑→下 80% 黑——整个界面发朦就是它）。按用户纠正：背景光晕+点阵保留、底部对话/输入区玻璃框保留，只撤整界面那层膜。
- 本批未构建：代码入仓后打包发用户转 Claude 复核，意见回来再改/再构建（jk-v13 候选）。

---

## 批九：移植 Andee 跨应用引擎 + 多步任务循环（jk-v14 真机反馈后，用户下令实施，未构建未真机）

背景：jk-v14 真机，用户说「在微信上给桑田发信息：晚上我回家吃饭」——现有意图一条没匹配上，整句掉进聊天被模型原样复述，微信没打开。查实：vision/ScreenVisionAgent 只有一次性 locateAndTap + describeScreen，没有多步任务能力；顺带发现问「今天天气如何」被模型编出「晴朗，60 度」（无任何天气数据源）。用户令：把 Andee 的无障碍跨应用代码直接复制过来。

1. **移植 screen/ 包（源自仓库内 andee/ 原版，实现逻辑原样，只动接线层，文件头有移植横幅）**：
   - `screen/ScreenController.kt`（3,722 行）：归一化坐标、网格 A1–H12 标定、zoom 瞄准、eid 定位、tap/swipe/输入/滚动/截图/弹窗与盲树防护，每步返回 JSONObject。接线改动：包名；marker 类型换本包 TapMarkerUi；reviveNote 前台包名改走 JarvisAccessibilityService.foregroundPackage()；annotate 的自家卡片分支改「dump 的 pkg 是 Jarvis 自己」判定（FullscreenCard 分支无对应设施整支删除）；模型可见文案 Andee→Jarvis；awaitHelper 删往 Andee 聊天记录加提示的一行（无此设施）；dumpUiTree 的 withShot 默认值改字面量 false（原 ToolSchemas 常量即 false）；screenshot 来源标记与 SHOT_MAX_SIDE 改字面量（"accessibility" / 1280，原 ScreenCapture 同值）。
   - `screen/A11yIme.kt`（193 行，原样）：走无障碍服务自身 InputMethod 的输入通道（API 33+），是 ScreenController 输入的首选通道。为此 `res/xml/accessibility_service_config.xml` 的 accessibilityFlags 追加 `flagInputMethodEditor`。
   - `screen/GroundingLog.kt`（384 行）：落点/截图配对记录，默认关；唯一外部依赖 VoiceConfig 换成 JarvisPrefs.isGroundingEnabled（新增 key `grounding_enabled`，默认 false，设置页暂无入口）。服务在 onServiceConnected init、onDestroy/onUnbind shutdown。
   - `screen/TreeHelper.kt`（58 行，原样）：被「扣留」界面（withheld）的树助手唤醒，无 WRITE_SECURE_SETTINGS 时自动 no-op。
   - `screen/TapMarkerUi.kt`（42 行，新写适配）：**绘制层未移植**——原版在屏幕上画落点圆环供模型核对，本类只保留同名接口与真实的落点状态记录（GroundingLog 要用），mark() 记坐标时间戳、revealNow() 为空操作。纯视觉反馈缺失，不影响动作执行与成败判定；移植绘制层时只需替换本类实现。
2. **裁剪清单（没有静默编造替代实现）**：
   - **ADBKeyboard 输入路线整链裁掉**：原版兜底通道靠第三方输入法 App 的广播 + ui/ImeSwitch 借还默认输入法，本工程没有这套设施。替换为新写 `typeViaSetText`：对焦点编辑节点 ACTION_SET_TEXT + 双路读回验证（读回纪律与原版一致，密码框拒填）。已知代价：原版记录里 SET_TEXT 进不了的少数自绘编辑器（如 CodeMirror 类），本站兜底同样进不去，会读回不一致如实报错——A11yIme 首选通道（commitText 正统路径）不受影响。ADB 三常量、CLIPBOARD_LABELS/PASTE_LABELS/SCROLL_TAG（原文件内本就未使用）一并移除。
   - **ScreenCapture 未移植**（MediaProjection 截图兜底，需 Activity 授权流）：本工程已有 JarvisAccessibilityService.captureScreen 视觉通道；任务循环在前台包命中截图黑名单时直接不带图、只喂元素大纲。
   - BodyCoreService（3,434 行）只借鉴循环结构，未整搬（它绑 Andee 的人格/配置/服务生命周期）。
3. **任务循环 `task/TaskLoop.kt`（394 行，新建）**：任务原文 → 最多 12 步循环：ScreenController.dumpUiTree 取元素大纲（文本行：编号|控件|文字|[0-1000 中心]）+ screenshot 取带网格截图（黑名单包不取图）→ AgnesClient 新增 `askOnce`（单轮、不进聊天历史、可选附图）让模型只输出一个 JSON 动作 → 映射到 ScreenController 真实 API 执行（eid→tapByEid/longPressByEid；cell/x/y→tapAim/longPressAim，on 区分 screen/zoom；swipe→swipeNorm；type→typeText；submit→submitInput；zoom→zoomAim，放大图转 JPEG 作下一轮观察图；back/home→globalAction；openApp→LocalCommands.launchApp；wait 上限 3 秒）→ 结果摘要记 AuditLog 并带进下一轮提示。模型输出解析不了时带原因重试一次，再不行 fail 收尾并如实回报卡在哪一步；步数用完同样如实回报最后进展。执行期间 TakeoverBorder 亮起；急停双通道：Agent 的 genSeq（isAlive 回调）+ TaskLoop 自有 runSeq（hardResetToIdle 已接 cancelInFlight）。任务运行期间其 ScreenController 注册在 JarvisAccessibilityService.screenController，onAccessibilityEvent 转发 noteEvent 喂其界面静稳判定。
4. **任务路由**：JarvisAgent.handleUtterance 在 LocalCommands 之后、TAP_INTENT 之前加 TaskLoop.looksLikeTask 保守判定（「在<应用>上/里给<人>发信息…」「给<人>发微信/信息…」「打开<应用>…给/发…」三组句式，需双信号、带问号直接放行）；技能匹配在上层对任务句式让行（不拿旧录像顶替现场执行）；LocalCommands 新增任务句式守卫（句中含「给…发信息/发消息/发微信/发短信/发送」时不按「打开X」吞掉）并抽出 `launchApp(ctx, name): String` 供循环复用。原句整句（含冒号后内容）作为任务交给循环。
5. **聊天防编造**：AgnesClient 的 SYSTEM_PROMPT 与 LocalLlm.buildPrompt 的系统句各加同一条硬约束：天气/气温/时间/新闻/价格等实时信息没有数据来源时必须明说查不到，禁止编造具体数值。
- 安全边界变化（如实记录）：任务循环走 ScreenController，不经旧任务引擎的 HARD_WHITELIST/FORBIDDEN_CLICK，靠 Andee 自带防护（弹窗守卫、同位置连点守卫、盲树必须先 zoom 才能点）+ 提示词硬规则（不输密码/验证码、不点付款/转账/删除）+ TaskLoop 的截图黑名单；typeViaSetText 对密码框拒填，但 A11yIme 首选通道是 commitText，若焦点恰在密码框仍可能写入——靠提示词规则拦，不做密码任务。
- 未验：**未构建、未真机**。静态自查已做：括号配平、重复 import、重复函数签名、`$变量` 紧贴中文专项扫描（本批新代码零命中）、跨文件成员引用逐项核对（构造签名、tapAim/zoomAim/swipeNorm 等真实签名与调用逐一对齐）。风险点：Agnes 的 agnes-3.0-flash 看图能力未验证（不行则循环退化为纯元素大纲模式或失败）；flagInputMethodEditor 在 Flyme 上的行为未验证；悬浮球未做 ownArea 遮罩，可能出现在任务截图里；ScreenController 的 takeScreenshot 无 API 30 守卫（minSdk 24，目标机 Android 13 不受影响，低版本机型调用会崩）；TapMarkerUi 绘制未移植（见上）。

---

## 批十：天气真数据 + 本地/云端智能调配 + 回答朗读开关（未构建）

背景：jk-v14 真机问答被指"智障"（问天气编出"晴朗 60 度"），批九的防编造提示只是治标。用户令：接天气数据源，并问本地与云端如何合理调配；另补昨晚未完的主界面朗读开关。
1. **天气数据源**（新 data/WeatherTool.kt）：直连 Open-Meteo（免费无 Key）——地理编码→实时+今日/明日预报→拼一句事实中文直答（天气状况/当前温/最高最低/体感/湿度/风速/降水概率，标注来源）。语音（handleUtterance）与打字（sendChat）双路在进模型前拦截；问句可带城市名（抠不到用默认城市，Prefs weather_city 默认新乡、设置里可改）；查不到如实说"天气没查到，可能是网络不通"，不落回模型。
2. **智能调配**（新 net/ChatRouter.kt）：替代"优先本地一刀切"——调配开时带疑问词/求解释的问答类云端优先（Agnes 答、带记忆），无疑问词的闲聊短句本地优先（快/免费/不出设备），本地答崩自动升云端；云端失败本地兜底不变。设置里原「优先本地」开关文案改为「智能调配」说明，Prefs 键不变。
3. **回答朗读开关补完**：查实现状——底部喇叭按钮本就是静音开关，但图标无说明、且打字问答的回复从来不朗读，用户等于"找不到开关"。现：按钮下加「朗读」标签、toast 改"回答朗读已开/关"；打字路径全部回复出口（记忆回执/本地答/Agnes 答/云端失败本地兜底答）统一过 speakReplyIfEnabled，未静音才出声；语音路径由 Agent 自播、不经此口防双播。
- 未构建，等用户发话（jk-v15 将与批九一并验证）。
## 批八复核修复（Claude 复核批八后动手改，未构建）

复核结论见对话记录，这里只记改了什么：

1. **`TtsRouter` 调用 `SystemTts.speak` 传了不存在的 `onStart` 参数，编译不过**：
   `SystemTts.speak` 补上 `onStart: () -> Unit = {}` 参数，真正接到
   `UtteranceProgressListener.onStart()`（之前这个回调是空实现，就算补参数也等于没接），
   统一 `main.post` 保证主线程回调（TTS 引擎的进度回调不保证在主线程）；`onDone`/`onError`/
   `stop()` 同步清掉 `currentStart`，避免悬挂引用。
2. **AEC/NS/AGC 没有持有引用，大概率被 GC 回收**：`startCapture` 里 `AcousticEchoCanceler`/
   `NoiseSuppressor`/`AutomaticGainControl` 创建后改用局部变量接住，录音线程的 `finally` 里
   跟 `recorder.release()` 放在一起显式 `.release()`，不再指望"系统自动回收"。
3. **屏幕不要模糊效果，要高清**：`HudStageView` 里星云光球那三团色块原来用的是
   `BlurMaskFilter`（像素级模糊采样，拖累清晰度、在部分设备上整块发虚）——改成
   `RadialGradient`（矢量径向渐变，衰减感来自颜色透明度渐变，不是采样模糊，任何分辨率下
   边缘都是锐利的）。排查过工程里就这一处用了 `BlurMaskFilter`，别处（光晕、核心高光）本来
   就是渐变不是模糊，没有改动。对话气泡（输出）和底部输入框的"玻璃感"（半透明填充 + 细描边）
   没有用模糊，原样保留。

### 这批没动、留着记一笔
`LocalCommands.kt` 复核时提到的三点——`OPEN_APP`/`呼叫` 正则偏泛可能误伤正常聊天句、
`getInstalledApplications`/通讯录查询在调用方线程同步跑有卡顿风险、同名多号码联系人没有消歧
——这批先不动，等你们看这版效果再决定要不要下一批处理。

### 验收自测结果
依然**没有在真机 / 没有编译环境里跑过**。这批重点建议验收顺序：
1. 先确认能编译过（这是这批最该验的，上一批直接编译不过）；
2. 断网状态下说话/发消息，确认走到系统 TTS 兜底时也能正常进入"播报中"状态、也能被打断；
3. 看中央光球的三团色块是不是清晰锐利，不再有发虚的雾状边缘；
4. 开着打断说话，确认不会被自己的播报声误触发（1200ms 缓冲 + 连续两次 ≥4 字的收紧生效）。

---

## 整体视觉钢铁侠化 + 补全"打开应用/本地指令"（本次）

### 视觉：钢铁侠 HUD 风格，不要模糊，要高清
1. **六边形网格换掉圆点阵**：`HudBackgroundView` 原来的极淡圆点阵换成蜂窝状六边形网格
   （Stark 座舱那种科技网格感），开机时按屏幕尺寸预算一次、缓存成 `Path`，之后每帧只是
   `drawPath` 一次，不会每帧重算几百个六边形。矢量描边，不存在"模糊"这回事。
2. **四角机甲取景框**：`HudStageView` 新增 `drawCornerBrackets()`，舞台四个角各画一个
   "长臂 + 斜切角"的 HUD 取景框记号，颜色跟随当前状态主题色——这是钢铁侠 HUD 最具辨识度的
   元素之一，之前完全没有。
3. **雷达扫描扇**：舞台新增一道会转圈、尾巴渐隐到亮白的扇形扫描光（`SweepGradient`，矢量
   渐变不是模糊），在声波点阵外面再叠一层，转速跟"思考中加速自转"共用同一个 `spin` 变量。
4. **顶栏状态仪表条**：新增 `[ASR OK]`/`[TTS 自动]`/`[NET OK]` 三个方括号小仪表（等宽字体，
   钢铁侠 HUD 常见的"数据窗口"观感），**是真实状态不是装饰性假数字**：ASR 对应离线识别模型
   是否就位、TTS 对应当前语音引擎档位、NET 对应设备有没有可用网络，每 3 秒刷新一次。
5. 对话气泡（输出）和底部输入框（输入）的玻璃感——半透明填充 + 细描边——**原样没动**，
   按要求保留。

### 「打开各种应用」补完 + agent 可靠性
`LocalCommands.kt` 按上次复核的三点意见整个过了一遍：
1. **正则误伤修了**：原来只用"吗/？/是什么"这几个提问词当黑名单，漏了大把不像指令的句子。
   现在改成"白名单式"判断——抓出来的那段文字必须"像"一个真人名/应用名（≤6 个字、不含
   "的/了/吧/呢/在/要/想/模式/怎么"这类虚词助词）才会被当成指令处理，不像就直接放行，
   交还给技能/视觉/聊天的正常流程。"呼叫中心电话是多少"这种句子现在不会被当成"呼叫『中心
   电话是多少』"去翻通讯录了。
2. **主线程卡顿修了**：`queryContacts`（通讯录 LIKE 查询）和 `openApp` 里的
   `pm.getInstalledApplications(0)`（国产 ROM 应用多的时候这个调用本身就慢）全部挪到后台
   线程跑，`speak` 回报和 `startActivity`/跳拨号盘统一 post 回主线程。`tryHandle` 对外的
   同步 `Boolean` 返回值没变，上层（`JarvisAgent`/`MainActivity`）完全不用改调用方式。
3. **同名/多号码联系人消歧修了**：原来按"号码行"去重，同一个人有手机/住宅/工作好几个号码时
   永远拿 ContactsProvider 吐出来的第一条，不一定是手机号。现在按 `CONTACT_ID` 真正按"人"
   去重，组内优先选手机号；如果通讯录里确实有好几个不同的人都叫这个名字，会在回报里老实说
   "通讯录里有 N 个叫 X 的，先用第一个"，不再装作没有歧义。

### 这批没做 / 有意简化
- 没有做"问用户选哪个号码"的多轮对话式消歧——只是老实报告"有几个、用了哪个"，真正的交互式
  选择（"要给工作那个号码还是手机号？"）需要在对话状态机里加一轮等待，这次范围内先不做，
  如果同名场景很常见，下一批可以加。
- HUD 的雷达扫描/取景框参数（臂长、转速、扇形角度）是按"看起来像那么回事"调的，没有逐帧
  对着某一部具体电影画面校准，追求的是"气质对"而不是"像素级复刻某个镜头"。
- `[NET OK/--]` 只判断"有没有可用网络"，不代表 Agnes/Edge TTS 这些具体服务一定连得通
  （比如连了 WiFi 但那几个域名被墙/服务器本身挂了，这个仪表还是会显示 OK）——真实请求失败
  仍然靠各自的错误提示，这个仪表只是"大方向参考"。

### 新增权限
加了一个：`ACCESS_NETWORK_STATE`（`[NET OK/--]` 仪表要判断有没有网）。这是普通权限，
装的时候系统自动给，不会弹运行时授权对话框，不影响现有的权限引导流程。

### 验收自测结果
依然**没有编译环境能跑一遍**。这批建议验收顺序：
1. 先确认编译能过（新加了一个 manifest 权限，核对一下没有拼写问题）；
2. 看主页背景是不是蜂窝网格而不是圆点，舞台四角有没有取景框，是不是有一道转圈的扫描光；
3. 顶栏那三个方括号仪表是不是显示了合理的状态（关网络试试 `[NET --]` 会不会正确变化）；
4. 说"进入梦乡模式吧"这类非指令句，确认走的是正常聊天而不是"没找到应用"；
5. 说"打开微信""打电话给X"，确认真机上不卡顿、能正确拉起/填号；如果通讯录里有重名，
   确认回报里有提到"有几个、用了哪个"。

---

## 整合批：分叉包（复核修复 + 钢铁侠 UI）与批九/批十全量合并（用户令，未构建）

背景：用户把基于批八分叉的包（Claude 复核修复 + 钢铁侠视觉改造）发回，令「全部整合在一起，UI 是我重新设计的，不行再改回来」。以批八（78f07a6）为三方基线逐文件合并，分类处置：

- **整取分叉侧**：AndroidManifest（加 ACCESS_NETWORK_STATE）、OfflineAsrSession（AEC/NS/AGC 持有引用并显式 release——修批八真 bug）、HudBackgroundView（六边形蜂窝网格换圆点阵，用户新设计，覆盖批八"点阵保留"旧口径，可从 git 回退）、HudStageView（四角取景框+雷达扫描扇+星云 BlurMaskFilter 改 RadialGradient）、SystemTts+TtsRouter（onStart 正统接法，替换 17f7a08 的手动补发绕法，引擎三档保留）。
- **保留我方侧**：批九 screen/task 引擎、批十 WeatherTool/ChatRouter/朗读开关、JarvisAgent 路由、JarvisPrefs、无障碍配置（flagInputMethodEditor）、OrbController（钢铁侠球面+100dp 是用户今早的更新指令，优先于分叉包的小熊+120dp）。
- **双改文件逐处裁决**：MainActivity 三方合并零冲突（顶栏 [ASR/TTS/NET] 仪表与批十朗读标签/天气城市设置共存）；LocalCommands 四处冲突人工裁决——TASK_SHAPED 守卫与 io/main 线程设施并存、联系人消歧取分叉版、openApp 统一走 launchAppInternal（io 线程枚举、主线程回报），TaskLoop 的 launchApp 同步口保留。
- **合并时抓到分叉侧一处同类编译雷**：LocalCommands 回报语 `"给$name打…"` 的 `$name` 紧贴汉字会被吞进变量名（与 jk-v13 四处编译错误同类），已改 `${name}`；全树同类扫描其余干净。
- 本批仍未构建：与批九、批十同候 jk-v15，用户说开始才构建。

## 批十一：麦克风点击启用 + 悬浮球没声加固（2026-10-24 用户两条反馈，未构建，随 jk-v15 出）
1. **麦克风改点击启用**：打断监听默认开→关（JarvisPrefs.isBargeInEnabled 默认值 true→false）；唤醒词常开、连续对话本来就默认关，三个会自动开麦的开关至此默认全关——点悬浮球/点麦克风才开始聆听，要哪个功能自己在设置里开。设置页打断开关文案同步改准（旧文案"无回声消除"已过时）。
2. **悬浮球回答没声**：定位为打断监听在播报期间开麦、jk-v14 的 AEC 对象被回收后回声消除失效，播报被自己的回声误判成插话掐断（只剩气泡）。AEC 持有引用修复已在整合批入仓；本批再关掉打断默认（断了误判路径），并给 speakReply 的 TTS 结果加审计——全部通道失败时写审计日志，下次真机出问题可直接查账，不再靠猜。

---

## 批十二：任务循环树文字优先 + DeepSeek 任务模型选项（未构建）

背景：jk-v17 交付后用户指正——「我用 Andee 时就填写 DeepSeek 的 Key 就可以」。查实 Andee 的跨应用循环主要吃无障碍界面树文字（元素大纲+步骤历史），纯文本模型就能跑；批九的 TaskLoop 虽然也把元素大纲发给模型，却把每步截图一并押在 Agnes 未验证的识图上。本批把循环改回树文字优先，并给任务大脑加一个可选的 DeepSeek。

改动：
1. **新增 `net/DeepSeekClient.kt`**：签名与 `AgnesClient.askOnce` 完全一致（调用方切换不用改形状）；POST `https://api.deepseek.com/v1/chat/completions`，模型 `deepseek-chat`，temperature 0.2，messages 只发 system+user 纯文本，`jpegBase64` 参数忽略（文本模型）；Key 走 `JarvisPrefs.getDeepSeekKey`，为空时报错「未配置 DeepSeek API Key，请先去设置里填写」；错误文案风格与 AgnesClient 一致（中文、带 HTTP 状态码）。
2. **JarvisPrefs 加两个设置项**：`deepseek_api_key`（get/setDeepSeekKey）与 `task_brain`（getTaskBrain 默认 `"agnes"` / setTaskBrain），放在 Agnes Key 附近。
3. **TaskLoop 路由**：`askModel` 按 `getTaskBrain` 分流——`"deepseek"` 调 DeepSeekClient 且图强制传 null；`"agnes"` 维持现状带图。另外当本步 elements 大纲为空白且最终不带图时，在发给模型的 userText 末尾追加一行提示（本屏界面树不可见、只能根据历史判断、无法推进就输出 done 说明卡点），避免模型对着截图说明文案空转。循环步数、急停、审计、动作执行一律未动。
4. **设置页（对话与语音段，Agnes Key 之后）**：加「跨应用任务模型：Agnes / DeepSeek」切换按钮（点击互换并刷新文案），以及 DeepSeek Key 输入框（hint 注明用途、预填已存值）+ 保存按钮，写法镜像同段 Agnes Key。

**MiniCPM-V 本地视觉模型降为后续可选**：本批不再把它当跨应用的前置条件。它的位置是专治两类场景——微信等应用界面树被系统扣住（withheld）看不到元素、且用户要全离线运行；需要时再单独立项（GGUF + llama.cpp 引擎 + 设置里第二个模型槽位）。

**状态：未构建、未真机。** 下一版构建后建议验收：设置里切到 DeepSeek、填 Key，说「在微信上给桑田发信息：晚上我回家吃饭」，看任务循环能否靠界面树一步步走完；界面树不可见的界面看提示与卡点回报是否如实。

## 批十三：系统功能指令层（手电筒等，2026-10-24 用户真机反馈，未构建）
背景：用户说「打开手机手电筒」打不开，并指出跨应用操作要覆盖全部手机功能、不能是半成品。查实：手电筒是系统功能不是带图标的应用，旧链路把它当应用名翻已装应用列表，找不到就死胡同。
- 新建 command/DeviceCommands：在 LocalCommands 的「打开应用」之前拦截，整句精确匹配（防吞聊天句），语音/打字双路：手电筒开/关/切换（CameraManager.setTorchMode，不开相机不需相机权限，TorchCallback 同步真实状态，相机被占用时如实回报）、截屏/锁屏/通知栏/快捷设置/回主页/最近任务（无障碍全局动作，服务没开时明示去开）、音量调大/调小（AudioManager 带系统音量条）。
- 全部动作写审计；办不成说原因，不装成功。

## 批十四：移植 Andee BodyCoreService 循环内核（未构建）
背景：用户追问「无障碍的代码都复制过了吗」并点名 BodyCoreService——批九只搬了「手」（screen/ 包），驱动它的「脑子循环」（BodyCoreService 本体 + brain/LocalBrain 的循环 + net/ToolSchemas 的屏幕操作纪律）没搬，TaskLoop 是 394 行自写简化版。本批以 Andee 的做法为准把循环内核补齐；同批按用户扩围令把 ScreenCapture 与无障碍配置也全量对齐。
- 步数上限 12 → 25（Andee VoiceConfig.DEFAULT_MAX_STEPS）。
- 重复守卫（LocalBrain MAX_REPEATS=3）：按动作签名（种类+eid/cell/坐标/text/ms 等关键参数）数连续重复，第 4 次相同动作执行前拦下中止并如实回报「在打转」；无法解析的模型输出也算一种签名。原先 TaskLoop 完全没有这层（ScreenController 内的同位置连点计数是另一层，不替代它）。
- 输出不合规的处理改 Andee 式：立即纠偏重试一次（原有）后，再不行不再整场中止——记为一步失败、把错误回喂下一步提示词让模型自纠（Andee 把工具错误当结果回喂）。
- 历史回喂加密：每步记录「动作简述 → 结果摘要」，摘要透传 ScreenController 的判定（screen_changed/now_pkg/elements_hint/snapped/输入读回）；历史段加 6000 字符预算，超了整步丢最旧的并注明（对应 LocalBrain 的 MAX_HISTORY_CHARS 截断；本工程是单轮提示词，量级按单条提示词定）。另加界面指纹比对：与上一步一字不差时在提示词里点明防原地重复。
- 系统提示词按 ToolSchemas 的屏幕纪律重写：缩进分组认领控件（列表型界面只用目标行自己组内的按钮）、# 开头是内部 id 不是可见文字、有编号绝不用坐标重算、type 必须紧跟点框（已聚焦的回复框直接 type）、挡路弹窗先清、界面没变化不许原样重发动作、wait 必须逐次加时（相同毫秒数同样判打转）、节点总数为 0（自绘屏，优先 back/home 换路线）与带名为 0（用大纲分组对位点编号）两种空树的区别、树被扣留应用先 zoom 再点的硬规矩。模型可见文案全部 Jarvis 身份、无 Andee 字样。
- 截图策略改 ToolSchemas 纪律（与批十二的大脑路由不冲突，DeepSeek 仍永不带图）：大纲是默认观察，截图只在树为空、上一步失败或界面没变化要核对、zoom 时才带——批九/十二时 Agnes 每步都带全屏图，现已按需。swipe 动作新增 screens 连滑多屏（swipeNorm 本就支持，回报实际滑了几屏）；wait 上限放宽到 20 秒配合逐次加时纪律。
- ScreenCapture 移植（扩围第 1 项）：andee/screen/ScreenCapture.kt 原样进 com.jarvis.app.screen（只改包名、虚拟屏名 jarvis-capture、PROJECTION_NOTE 的身份文案；另加 companion 单例 shared() 作为持有点——Andee 由 BodyCoreService 持有，本工程没有那层服务）。取舍按 BodyCoreService 原做法：无障碍 takeScreenshot 为主、MediaProjection 只作兜底（captureFallback 同位），TaskLoop 在无障碍截图拿不到图时才走录屏，图缩到长边 1280、无网格、提示词标明「只能看不能按图瞄准」；隐私黑名单（isShotBlocked：银行/支付/权限/设置类）两条通道都不拍。**未接通的一项（如实记录）**：录屏授权的发起端没有接——createScreenCaptureIntent 必须从 Activity 发起并把结果喂给 ScreenCapture.start()，本工程唯一 Activity 是 MainActivity，不在本批可动文件内；且 Android 14+ 调用 getMediaProjection 前要求 mediaProjection 型前台服务在运行，本工程暂无此服务（目标机 Android 13 不受此限）。因此本批未动 AndroidManifest（没有必需条目：加权限而无前台服务等于没接）。接通只差两步：MainActivity 加一个「录屏授权」入口（拉起授权框、结果转 ScreenCapture.shared().start()），以及（为 Android 14+）加 FOREGROUND_SERVICE_MEDIA_PROJECTION 权限 + 一个 mediaProjection 型前台服务。
- 无障碍配置全量对齐 Andee（扩围第 2 项）：eventTypes 补 typeWindowsChanged|typeViewFocused|typeViewScrolled；flags 补 flagDefault|flagRequestTouchExplorationMode；新增 canRequestEnhancedWebAccessibility/canRequestTouchExplorationMode。唯一偏差保留：flagRequestFilterKeyEvents + canRequestFilterKeyEvents——JarvisAccessibilityService.onKeyEvent 用它做音量键双击急停（600ms 内同按音量+/−），是真功能，故不删。
- 没搬的：BodyCoreService 里非循环的部分（悬浮窗/卡片 UI、ASR/TTS 控制器、会议录音、宠物狗链路、场景系统、WebSocket 大脑通道、笔记/话题折叠等 Andee 自家设施）——不是循环内核，且 Jarvis 有自己的对应层。LocalBrain 的原生 tool-calling 协议没搬：本工程两个 Client 都是单轮文本接口（askOnce），模型协议仍是「一步一个 JSON 动作」，循环机制按等价方式落地。工具执行超时（dispatchWithTimeout）未单独搬：ScreenController 各动作内部已有自己的等待/静稳上限，模型调用侧有 45 秒闩锁超时。
- 状态：未构建未真机。建议验收：设置任务模型为 DeepSeek 后说「在微信上给桑田发信息：晚上我回家吃饭」，看步数是否够用（25 步）、中途点错时是否会换招而不是原地重复、被守卫拦下时回报是否如实。

## UI 调整：撤蜂巢格纹（2026-10-24 用户真机看后定，未构建）
用户装 jk-v18 看了钢铁侠蜂窝界面后明确不喜欢背景蜂巢格，要纯净高清。HudBackgroundView 的六边形网格整层撤除（连同预计算 Path 的机器一并删，152→107 行），背景只剩纯净深蓝 + 三团漂移光晕，无任何格纹/点阵；舞台取景框、雷达扫描、顶栏仪表、玻璃框等其余元素按用户选择保留。要恢复格纹从 git 历史取本文件旧版。
另：顶栏仪表（[ASR]/[TTS]/[NET] 方括号小仪表）按用户同日要求一并撤除——MainActivity 的仪表行、样式、3 秒刷新与状态联动清理干净，专为它加的 ACCESS_NETWORK_STATE 权限也从清单撤回。顶栏只剩状态点 + SYSTEM ACTIVE。

## 批十五：无进展熔断（2026-10-24，Claude 按真机日志证据修复，Muse 核查后原样合入，未构建）
jk-v19 真机实证：模型看不见界面时只回不断加码的 wait + 重复 openApp/home/back，每次签名都不同，精确重复守卫被绕过，25 步白烧。新增第二层守卫：独立计数 noProgressSteps，只认 tap/longPress/type/zoom 为"真交互"（swipe 不算，注释留了口子），其余动作与输出解析失败都 +1，连续 7 步无真交互直接强制 fail 并写审计「无进展熔断触发」。顺序在签名守卫之后（真重复优先报精确原因）。diff 核查：纯新增三处、未动任何既有行；pkg 作用域、模板写法、括号均验过。截图双通道 catch{null} 静默失败的问题本批未解（熔断只保证不无限空转），Claude 提示的两个后续方向：MediaProjection 授权是否随进程重启失效、微信聊天页 FLAG_SECURE 是否连无障碍截图一起挡——待真机再触发熔断后查 ScreenController.screenshot()/ScreenCapture 源码。

## UI 重设计：清澈化 + 四处动效（2026-10-24 用户定，未构建）
- 图 1 蓝色能量球：按球体裁正方形，替换悬浮球头像（drawable-nodpi/orb_ironman.png）
  与全部五档桌面图标（mipmap-*/ic_launcher.png）；原钢铁侠头像文件被替换，
  orb_bear.png 仍在资源里未动。
- 图 2 圆环：圆形裁切成顶部徽章（hud_badge.png，160dp 居中），SYSTEM ACTIVE
  移到徽章下方；徽章图本身不动，转的是外面一圈虚线环（hud_badge_ring.png，
  含一段亮色长标记，转起来才可见，60 秒一圈）。
- 清澈化：HudBackgroundView 三团漂移光晕撤除，背景只留实色深蓝 #050B18；
  HudStageView 雷达扫描扇撤除；四角取景框细实线与状态切换涟漪保留；
  对话玻璃框按用户要求原样保留——它是界面上唯一的半透明元素。
- 动效四处（只动线条与缩放，不动大面积半透明）：①徽章外圈虚线环慢转；
  ②悬浮球待机 ±2% 呼吸缩放（3 秒周期）、思考态加转动的细虚线环、
  聆听/说话沿用原有脉冲与外环旋转（注：未接音量真幅值——OrbView 没有
  micLevel 输入，脉冲是固定节奏）；③新消息 220ms 下方滑入淡入；
  ④SYSTEM ACTIVE 状态点透明度 2 秒脉动。
