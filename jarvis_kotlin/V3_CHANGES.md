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
另：桌面图标改用用户 2026-10-24 14:44 发来的 Jarvis 金属字圆角图标（mipmap 五档全换）；
悬浮球仍用图 1 能量球，不随图标改动。
另：桌面图标换成用户 2026-10-24 14:46 发来的玻璃 J 字母图标（居中正方形裁切，五档全换）；
上一张 Jarvis 金属字图标不再做启动图标，改放新加的设置二级页「关于」顶部大图展示
（drawable-nodpi/about_jarvis.png，配一行名称与版本号）。

## 批十六：截图通道失败留痕（2026-10-24，Claude 按截图通道追查修复，Muse 核查后合入，未构建）
TaskLoop 两条截图通道不再静默 null：accessibilityShot 空 png_base64 与异常
（含 ScreenController.screenshot() 的 errCode 原文）各记一条审计「截图通道」；
projectionShot 未授权（60 秒限流一条）/grab() 超时/异常分别记一条。
核查时纠正了 Claude 注释里一处事实错误并同步改了审计文案：ScreenCapture.start()
的调用端批十四就已接进 MainActivity（设置-视觉看屏"录屏授权"按钮 +
onActivityResult 转 start()，MainActivity:913/1287），并非"从来没有授权入口"；
isAvailable() 为 false 只说明本次进程里还没授权过（MediaProjection 授权不随
进程重启保留）。其余逻辑原样合入。

## 批十七：主界面极简重构 + 录屏 Android 14 前台服务修复（2026-10-24 用户定，未构建）
主界面（用户连下三条：舞台全删、顶部只留齿轮、徽章居中放大 10%、SYSTEM ACTIVE
与状态文字全不要）：
- 顶栏只剩齿轮设置图标；徽章从顶部移到中央，176dp（160dp 放大 10%），虚线环
  194dp 慢转（60 秒一圈）不变；徽章上不压任何字，SYSTEM ACTIVE 整行删除。
- 舞台整块删除：HudStageView.kt 文件已删，主界面不再有光晕、光球、光环、点阵、
  虚线环、霓虹环、取景框；状态文字（STANDBY/待命）随舞台一起撤，状态只靠
  徽章/悬浮球颜色与动效表达；onAgentStateChanged 只剩刷新唤醒词胶囊。
- 保留不动：对话玻璃框、底部麦克风按钮、朗读/编辑图标、提示文字、唤醒词胶囊
  （徽章下方，仍可点开关）。
录屏（用户截图 toast“录屏没启动起来，再试一次”的后续）：
- 新增 CaptureForegroundService（mediaProjection 类型前台服务，通知 ID 7002），
  清单补 FOREGROUND_SERVICE_MEDIA_PROJECTION 权限与服务声明；Android 14 起
  getMediaProjection() 前必须先跑该类型服务，否则系统直接拒绝——此前只有
  microphone 类型服务，是 start() 失败的最可能原因（注：用户机系统版本未核实，
  若实际是 Android 13 则此规则不适用，见下条）。
- ScreenCapture 新增 lastError：授权拒绝/拿不到服务/前台服务启动失败/
  getMediaProjection 被拒（含系统原文）/虚拟屏创建失败各记一句话，回调 toast
  直接报出来，不再含糊说“再试一次”；投影释放时服务随之停掉（通知消失）。
  另顺手修正了 ScreenCapture 头注释里“授权尚未接进 MainActivity”的旧话
  （批十四就已接线）。

## 批十八：语音截断修复 + 悬浮球长按 + 徽章反应堆动效 + 锁屏藏球（2026-10-24 用户定，除跨应用外全部做，未构建）
第 1 项·语音播不完整（用户确认：气泡文字全，只有声音断）：
- EdgeTts 真 bug：AudioTrack.write() 只保证数据进缓冲区，旧代码写完最后一句
  立刻 stop()，把缓冲区里还没播的尾巴（约 0.3~1.3 秒）直接丢掉——每句回复
  都被截尾。现累计写入帧数，播完后等放音头追上总帧数（超时为音频时长+3 秒）
  再 stop；中间一句取音/解码抛异常时旧代码直接 abort 全句，现只跳过该句
  继续播后面（第一句失败才当整体失败），并记一条审计。
- SystemTts：长文本一次 speak() 在部分 ROM 引擎上会被内部截断；改按句切分、
  首句 QUEUE_FLUSH、其余 QUEUE_ADD 排队，全部播完才回调完成；任一分句
  出错记失败（末句出错立即回失败，中间句出错则末句回失败）。
- 打断监听默认就是关（JarvisPrefs 默认 false），排除回声误触发为主因；
  若用户手动开着打断，喇叭回声仍可能中途停播（已知局限，未动）。
第 2 项·悬浮球长按进主界面：旧 handleTap 先走 Dart eventSink，桥没起来就兜底
launchApp("assist") 打开主界面——纯 Kotlin 版里 agent 没启动时桥永远没起来，
于是每次短按都变成“进主界面”。现短按直接走原生 JarvisAgent.triggerListenFromAssist
（内部先 start 再 beginListening），全程不打开主界面；长按（600ms，移动则转拖动）
仍进主界面；350ms 内连点两次仍是急停。
第 3 项·徽章反应堆动效（方案已由用户批准）：新增 hud/EmblemMotionView，
衬在徽章后面：中环刻度环反向转、4 个轨道粒子、思考态亮段弧光快扫、
聆听/说话时声波环扩散（幅度跟能量走）、状态切换瞬间 12 粒子 0.6 秒爆发、
点麦克风/点徽章涟漪；徽章与虚线环另加 ±2% 三秒呼吸缩放。只用细线圆点亮段
弧光，不恢复大面积雾状光晕、蜂巢、扫描扇；对话玻璃框仍是唯一半透明。
第 5 项·锁屏息屏藏球：OrbController.attach() 注册亮灭屏广播——灭屏或亮屏仍
锁着 → 藏球，解锁（USER_PRESENT）→ 按 enabled 与 appForeground 门控恢复；
只藏视觉，不停常驻服务与唤醒词监听。
跨应用（第 4 项）不在本批：等用户提供 v22 微信任务审计里「截图通道」条目
与任务收尾状态后再定位。

## 批十九：跨应用四件修复（2026-10-24 用户定方案，未构建）
依据：用户 21:56 微信任务审计（openApp 微信/WeChat 两次没找到、第 4 步问模型
timeout 判死整轮、审计里无截图通道记录）+ 真机描述（进微信后无操作、只有
四周跑马灯、Andee 有可见点击动作）。
第 1 件·openApp 查不到应用：LocalCommands 先按包名直查（微信 com.tencent.mm、
QQ、支付宝、淘宝、京东、抖音、快手、B站、高德、百度地图、设置、电话、短信、
相机、相册），命中直接拉起；直查失败回落枚举。枚举异常不再静默吞成“没找到”，
失败文案带上异常原因。直查失败一律回落，所以个别 ROM 包名不同的条目也不会
开错应用。
第 2 件·模型 timeout 直接判死：Agnes/DeepSeek 读超时 30 秒→90 秒（任务提示词带
树带历史本身就长；askOnce 只有任务循环在用，日常聊天不受影响）；循环闩锁
45 秒→120 秒（须大于 90 秒，否则闩锁先成瓶颈）。问模型失败不再直接 return：
连着失败 2 次才结束，第 1 次记一步失败、下一轮重新观察再问，不计入无进展熔断。
第 3 件·进微信后模型不动作：截图策略放宽——可用节点 < 3（树“有但没用”）或
连续 2 轮无真交互时强制带图，打破 wait 循环；截图成功也留一条审计（旧逻辑只
记失败，审计没记录时分不清“没拍”和“拍了没用”）。
第 4 件·点击看得见：TapMarkerUi 绘制层补上（批九注释里写明未移植、revealNow
空操作）。tap/longPress 后在落点画 600ms 扩散圈（20dp→64dp 同步淡出）；
覆盖层全屏透明、NOT_TOUCHABLE 直通、importantForAccessibility=NO 不污染树；
svc 为空时只记录不画图；TaskLoop 改传 TapMarkerUi(svc)。
自查（用户要求逐行复查）：全量 git diff 逐项核过——括号平衡 5 文件全过；
问模型失败重试第一版写成 break 会误入“输出无法解析”分支并计入熔断，已改
成 continue@stepLoop 直进下一轮；LocalCommands 直查路径的审计与返回值统一
用 trim 后的名字；TapMarkerUi 的 WindowManager 操作与 hide 调度都在主线程，
FIFO 顺序无竞态；无参 TapMarkerUi() 调用点已清零。

## 批二十：参数与 Andee 全面对齐（2026-10-25 用户令“直接照搬”，未构建）
先回答悬浮球问题：Andee 任务期间球不隐藏——只在截图瞬间空白约 200ms，
点按落点在球下时球让开；球默认 168dp 右下角 16dp 边距（Jarvis 用户此前嫌
168 太大改定 100dp，尺寸与位置按用户旧决定不动，只对齐任务期行为）。
超时：Agnes/DeepSeek 连接 10→15 秒、读 90→180 秒（Andee LlmClient 原值，
注释：思考模型按分钟回答）；循环闩锁 120→200 秒（须大于 180 秒读超时）。
点击标记按 Andee 重写（批十九全屏窗口废弃——Andee 文档警告全屏覆盖层占满
0.80 遮挡上限会静默吃触摸）：144dp 小盒定位在落点中心、NO_LIMITS +
IN_SCREEN + fitInsetsTypes=0，冲击两圈（480ms、间隔 110ms）+ 停留 6000ms +
淡出 400ms，环 + 圆点 + TAP/HOLD 与 0-1000 坐标标；手势发出前先藏上一圈，
截图时可重亮（时间线不重启）。
熔断：无进展熔断 abort 删除（Andee 没有这层，只有重复守卫 + 25 步上限兜底），
计数保留供连续 2 轮无真交互时强制带图；重复守卫 3 次、步数 25、网格 8x12、
子格 3x5 与 Andee 原本一致，不动。
树空等待：nodes_useful==0 时按 Andee SETTLE_BUDGET_MS=1500 轮询等树长出来
（300ms 一次），而不是立刻按“看不见”处理。
wait 纪律写进提示词（Andee wait_for_screen 原规则）：每次等待要比上一次久
（1500→3000→5000→10000），绝不连续发相同等待时长，否则被重复守卫拦停；
wait 上限 20 秒与 Andee 一致，不动。点按手势超时 2000→3000ms（Andee 手势
闩锁 3 秒）；截图超时 5 秒、点按行程 150ms 与 Andee 一致，不动。
悬浮球任务期行为：screenshot() 每次截图前后空白球与气泡（提示词里一直写着
“每次截图球都会藏起来”，代码此前没做，本次补齐）；tap/longPress/swipe 落点
在球框内（含 8dp 裕量）时球临时移到对侧边缘、手势完恢复（只改布局不写偏好）。
树/历史字符预算（5000/6000）与步结果截断（300）在 Andee 无对应值，保留不动。
自查：6 文件括号平衡全过；MAX_NO_PROGRESS_STEPS 引用清零；dodge 的恢复包
在 try/finally 里（手势抛异常也不丢原位）；截图空白的恢复放在既有 finally 里，
截图抛异常也能恢复；setVisibleForCapture/dodge 的主线程闩锁都有 Looper 守卫，
主线程调用不死锁。

## 补丁：修复批十八 MainActivity 同行双声明（jk-v23 构建失败原因，2026-10-25）
jk-v23（8f16713）CI 失败，唯一错误 MainActivity.kt:75:83 "Property getter
or setter expected"：批十八新增 emblemMotionView 字段时与下一行 muteButton
写在了同一行，Kotlin 不允许一行两个属性声明（无分号），自批十八起三批
只入仓未构建，此错一直没被编译器抓到；jk-v22 成功是因为它不含批十八。
修复：拆成两行。全仓 grep 确认仅此一处同行双声明。

## 批二十一：Claude 查问题两件修复（2026-10-25 用户转发 Claude 输出，Muse 核查后入仓，未构建）
1）服务连接时能力位自检（JarvisAccessibilityService）：onServiceConnected()
里新增 logCapabilitiesOnce()，读 serviceInfo.capabilities，看
CAPABILITY_CAN_TAKE_SCREENSHOT 到底有没有置位，记 Log 与审计日志；未置位时
审计里给提示：多半是改过无障碍服务 XML 元数据后系统还在用旧的能力缓存，
去系统设置里把本服务关掉再开一次（不是重启 App）通常能刷新。注意这只是
诊断、不是修复本身：能力位是系统侧算的，代码伪造不了。
2）截图空白等一帧（OrbController.setVisibleForCapture）：原来 hide 分支的闩锁
只等到赋值语句在主线程跑完就放行，但 GONE 赋值不等于 SurfaceFlinger 已把
球消失那帧合成上屏，截图恰好最容易撞上这个窗口；现改成赋值后等一次真正的
vsync 回调（View.postOnAnimation）再放行，闩锁超时 300→400ms（多 100ms
留给这一帧）。GONE 的 View 仍在窗口树里，postOnAnimation 有效；回调迟迟
不来时 400ms 超时兜底。
Muse 核查：两文件 diff 均干净——服务文件仅 1 处纯新增（零删行），Orb 文件
仅 2 处（注释 + 该函数重构），无其他改动；另修正 Claude 原版一处：hide 分支
主线程也会闩锁等 400ms，但主线程被堵住时 vsync 回调跑不出来，等于白等还卡
主线程，现主线程只隐藏不等帧（当前截图调用方都在后台线程，此条只防以后）。
自查：两文件括号平衡全过。

## 批二十二：主界面反应堆放大（2026-10-25 用户定方案 A 修订版，未构建）

用户定：主界面效果不够炫，参考老版反应堆舞台；方案 A 但去掉三团漂移光晕、
格纹、大面积雾；设计稿经用户点头后实施，代码未构建。
改动（EmblemMotionView + MainActivity.buildEmblem + HudPalette）：
1）容器 280dp→420dp（点阵外沿 380dp + 唤醒词胶囊区 40dp），对话区为
weight=1 弹性高度，只被压缩、不被顶出屏；动效层、徽章、虚线环共用
bottomMargin 40dp，圆心对齐在容器 190dp 处。
2）徽章本体保持 176dp 不动；虚线环 drawable 194dp→330dp，60 秒慢转与
±2% 呼吸不变；刻度环与轨道粒子 108dp→118dp。
3）新增霓虹渐变环（半径 150dp、300dp 直径）：电光青→霓虹紫→翡翠绿三段
固定色，从顶部起顺时针；翡翠绿为 HudPalette 新增常量（旧调色板无绿色）。
4）新增 72 根声波点阵：起点半径 170dp（虚线环之外），长度随能量变化
（6dp + 能量×14dp）且每根长短有起伏，颜色主/辅/第三色循环；外端不超出
本 View 半径，窄屏自动截短；窄到起点半径都超界时整组不画。
5）思考扫描弧（102dp）、声波环、12 粒子爆发、点按涟漪、状态跟随变色均
保持批十八原样；背景仍是实色深蓝，未动；对话玻璃框仍是唯一半透明元素。
自查：两文件全量复查一遍（见本次复查记录）；括号平衡全过；未构建。

## 批二十三：打断说话回声过滤（2026-10-25 用户报语音未播完就停，Muse 查因后入仓，未构建）

用户原话"悬浮球回复出的文字，声音没有播放完就停止的老毛病没有整改"。
核查结论：批十八修的是 Edge 缓冲区尾巴与系统长文本分句，没动真正会半路
掐断的打断监听。播报中途能停播的只有三处：用户点球/急停（主动行为）、
打断监听自切、引擎自己断队；用户确认切断时气泡正是"打断了，在听…"，定性
为打断监听自切——批八收紧的阈值（1200ms 缓冲、连续两次 ≥4 字）拦不住长回
复：扬声器自己的声音会源源不断地产生 ≥4 字的中间结果，等于必误判。
改动（JarvisAgent.startBargeInWatch，调用方 speakReply 传回复文本）：
1）监听拿到正在播的回复文本：识别结果去标点去空白后，只要是回复文本的
子串、或以回复文本开头（回声后拖了尾巴），都当回声直接忽略，既不计数也
不清零；只有回复里没有的话才计入打断，连续两次非回声结果才真打断。
2）replyNorm 为空（回复全是标点，正常回复不可能出现）时不过滤，避免
startsWith("") 恒真导致打断功能整体失效。
3）1200ms 起播缓冲、两次 ≥4 字判定、打断后转聆听均保持原样；打断开关默认
仍是关（只影响开着的用户）。
自查：唯一调用点已同步改签名；改动函数全量复读一遍；括号平衡全过。
验收：长回复播报时不再被自己的声音打断；真插话（说一句回复里没有的话）
仍能打断；未构建。

## 批二十四：空识别丢弃 + 最近任务关闭应用（2026-10-25 用户令“所有问题都修好打包进仓”，未构建）

用户原话："你要把所有的问题都修复打包进仓库，我要构建的时候是最新修复版"。
本批补上待解决问题收集中最后两件未入仓的代码修复（此前 6、7 两条）：
1）没说话却回"你说什么，我没听清楚"（JarvisAgent.onUserUtterance 入口）：
唤醒词被环境噪声误触发后，空识别结果发给模型会换来这句礼貌反问。现入口
处先归一化（去空白/标点）：纯空白、纯标点、单个语气词（啊/嗯/呃/哦/额）
直接丢弃，不发模型、不回复、不留气泡；丢弃后直接回 idle 并恢复唤醒轮询，
别卡在 listening 状态。单个字以上的正常指令不受影响。
2）应用能开不能关（LocalCommands 新增「关闭 X」＋无障碍服务新增划卡片）：
说法覆盖「关闭/关掉/退出 X」与「把 X 关了/关掉/关闭」；与「打开应用」对称
走 io 线程，名字先包名直查、再按标签精确匹配（关闭错应用比关不成严重，
所以不用打开那边的模糊匹配）；Jarvis 自己拒绝关；无障碍未连接如实说。
流程：开最近任务 → 最多等 2 秒确认前台是 SystemUI/启动器（没等到就不乱划，
在原应用里上滑会变成翻页）→ 按卡片标题文本找卡片上滑划掉 → 划后复查卡片
是否还在，还在就如实说"划了一下但卡片还在"，不装成功。划卡片是系统界面
操作、调用方只能是用户原话，不经过模型任务循环，所以不走通用点按白名单
（通用 tap/swipe 仍只在白名单 App 内放行，原约束不动）。
自查：三文件括号平衡全过；改动函数复读一遍；唯一调用点（关闭）与入口
守卫（空识别）均已核对；未构建。
未入本批、仍在等输入的一件：跨应用主线（问题 4）——根因未定，仓库里现有
批十九/二十修复 + 批二十一诊断（能力位自检），待下一轮真机审计与 Claude
后续（用户说等后续发过来，尚未收到）到后再核查入仓，本批不硬编修复。

---

## 第一梯队批一：记忆语义化升级（Claude 实施，未构建未真机）

背景：用户定的功能提升第一梯队第 1 项——记忆从"显式前缀"升级到"语义记忆"。

### 做了什么
1. **自然语言隐式提取**：`MemoryService.extractImplicit(ctx, userText, replyText)`，在
   问答类聊天回复（Agnes 云端 / 本地模型，云端失败本地兜底）落地之后异步触发一次，
   不阻塞朗读/显示。内部走 `AgnesClient.askOnce` 单轮请求，提示词要求模型只在真有
   "值得长期记的用户信息"时才输出 JSON 数组，否则输出 `[]`；显式操作指令（点击/
   打开应用/天气）与临时情绪不提取。失败（网络/解析）全部吞掉，只记日志，不影响主流程。
2. **相关性检索**：`getInjectableMemories(ctx, query)` 从"最近 15 条全量拼"改成按
   当前这句话（`query`）做关键词重叠打分 + 重要度加权（用户显式"记住："的条目固定加分）
   + 新鲜度小幅加分，取 Top-8。注意：这是字符级重叠打分，不是向量语义检索（工程里
   没有引入 embedding 模型），"我讨厌榴莲"和"不喜欢吃这种水果"这种语义相近但字面不重叠
   的场景搜不到——如实标注，不是真正的语义检索，只是比"全量拼"更聚焦一些。
3. **过期机制**：`MemoryItem` 新增 `source`（STATED/INFERRED）、`importance`（1/2）、
   `expiresAt`（null=永久）三个字段。显式记忆与模型判断为"长期信息"（`permanent:true`）
   的隐式记忆永久保留；其余隐式记忆默认 30 天后过期（`IMPLICIT_DEFAULT_TTL_DAYS`）。
   注入时自动过滤已过期条目，并顺手清理过期行，不单独起定时任务。
4. **设置页**：记忆分区新增"自动从聊天中学习"独立开关（默认开，关了只保留显式
   "记住："那条路）；记忆列表每条标出来源（手动/自动）与有效期（永久/至某天）。
5. **数据库**：`jarvis_memory.db` version 1→2，写了正式迁移 `MIGRATION_1_2`
  （三条 `ALTER TABLE ADD COLUMN`，旧行补默认值，保留数据）。原稿用
   `fallbackToDestructiveMigration()` 直接清空重建、理由是"工程没有真机跑过，不存在
   真实用户数据"——合入时已改掉：jk-v28/v37 已在真机跑过，记忆不能按不存在处理。
   实体字段的 `@ColumnInfo(defaultValue)` 与迁移 SQL 的 `DEFAULT` 逐字一致，Room 校验
   才能通过。另注意：CI 的 debug 包每轮签名不同，装机历来先卸载旧版，卸载会清库，
   迁移只在同签名升级时起作用，升级前仍建议先抄一份记忆列表。

### 已知局限（如实标注）
- 隐式提取每轮问答多打一次模型请求，会增加延迟和 Agnes 调用量；云端失败走本地兜底
  成功时不会跳过提取（本地模型答出来的内容同样会被喂去 Agnes 做提取，这一步仍然
  要联网——如果断网，extractImplicit 内部请求失败会被吞掉，静默跳过，不会报错打扰用户）。
- 相关性打分是字符重叠法，不是语义检索，效果上限有限；真要做到"同义不同字也能搜到"
  需要引入本地 embedding 模型或接云端 embedding 接口，这超出本批范围。
- 去重只做"内容完全一样"的字符串比对，"用户喜欢喝咖啡"和"用户爱喝咖啡"这种会被当成
  两条不同记忆分别插入，没有做语义去重。
- 技能/本地指令/天气/看屏这几类结构化回复没有接入隐式提取（本来就不是该记的"用户信息"，
  是刻意不接，不是漏做）。

### 自查
`memory/*.kt`、`JarvisAgent.kt`、`MainActivity.kt`、`JarvisPrefs.kt` 五个改动文件
括号配平全过；专项扫描了`$变量`紧贴中文这类历史编译雷（零命中）；`AgnesClient.askOnce`
调用参数与其真实签名逐一核对。**未构建、未真机**——环境无 Android 工具链，这是本批
最大的风险点，建议构建后优先验证："记住：我不吃香菜"后清空 App 重进，问"中午吃什么"
看会不会提到忌口；正常聊一句包含长期信息的话（不加"记住"），隔一会儿去设置页记忆列表
看有没有自动多出一条"自动"标签的记忆。

### 未排期 / 本批没碰
第一梯队第 2 项（跨应用任务循环增强）、第 3 项（技能固化升级成真自动化）下一批继续。

---

## 第一梯队批二：跨应用任务循环增强（Claude 实施，未构建未真机）

背景：用户定的功能提升第一梯队第 2 项——跨应用任务循环增强。

### 做了什么
1. **失败后换策略**（`task/TaskLoop.kt`）：原有的"相同动作签名连续重复守卫"
   （`MAX_REPEATS=3`）只会在第 4 次重复时硬停，中间没有给模型任何"换个做法"的
   机会。现在当 `repeats` 达到 `MAX_REPEATS - 1`（也就是再重复一次就要被拦停）时，
   在下一步提示词里插入一段强提示，明确列出几种具体的替代思路（滚动找别的入口、
   用搜索图标、先返回换路线、zoom 放大看是否点偏），逼模型在真正被拦停之前先试一次
   不一样的做法。这不是新增一层判定，是在既有重复守卫触发前"多给一次申辩机会"。
2. **多轮澄清对话**（新增 `command/PendingClarification.kt`，接入
   `command/LocalCommands.kt` 的 `callContactBg`）：原来"通讯录里有好几个叫 X 的"
   是直接用第一个、顺带告知"有 N 个，用了第一个"；现在改成真的反问——列出每个匹配
   （姓名+号码尾号+号码类型），等用户下一句话用"第几个"或"报号码后几位"来选，选中
   才真正拨号。`PendingClarification` 是个很薄的单例状态机，只支持一轮追问（不支持
   "没听清再问一次"），60 秒内没等到有效回答就自动放弃、交还给正常对话流程，不会
   卡住后续所有操作。`LocalCommands.tryHandle` 入口第一件事就是看有没有待澄清状态，
   命中就在这里把"选号"消费掉，不会被其他指令匹配规则截胡。
3. **视觉链路验证**（设置页"视觉看屏"分区新增按钮）：之前"Agnes 是否真支持识图"
   全程只是假设，从没有验证过。现在"测试视觉模型是否真的能识图"按钮会现画一张
   白底黑字的随机数字图发给 `AgnesClient.askVision`，模型答对数字才判定为真正通过；
   只是"接口没报 HTTP 错误"但答案文不对题会被明确判定为"不可靠"，不会误报成功。
   结果写一条审计日志，方便事后查。

### 已知局限（如实标注）
- "失败后换策略"只是在提示词里加强提示，**不能保证模型真的会换招**——它仍然可能
  再重复一次、照样被拦停，这批只是提高破局概率，不是把破局变成确定事件。
- 多轮澄清目前只覆盖"打电话给同名多个联系人"这一个场景；"打开应用"等其他可能有
  歧义的地方（比如系统里真有两个名字很像的 App）没有接入，`PendingClarification`
  设计上支持扩展，但这批没有顺手把其他场景都接上。
- 视觉链路自测是"问能不能看懂一个数字"这种最基础的探测，通过了也只能说明"模型
  至少会看图里的文字内容"，不代表它在真实、复杂的手机界面截图上也能准确理解布局、
  找到正确的点击位置——真正的看屏可靠性仍然要靠后续真机任务来检验。

### 自查
`task/TaskLoop.kt`、`command/LocalCommands.kt`、`command/PendingClarification.kt`（新增）、
`MainActivity.kt` 四个改动/新增文件括号配平全过；专项扫描 `$变量` 紧贴中文零命中；
`AgnesClient.askVision` 调用参数与其真实签名核对过。**未构建、未真机**——建议验证顺序：
①通讯录里存两个同名联系人，说"打电话给X"，确认会反问而不是直接拨出，说"第一个"
后确认选中第一个号码；②设置页点"测试视觉模型是否真的能识图"，看结果是通过还是
"不可靠"（如果不可靠，说明 `agnes-3.0-flash` 这个模型本身可能不支持识图，看屏/
截图兜底整条链路需要换一个确定支持视觉的模型）；③故意让任务循环卡在同一个动作上
（比如让它反复点一个点不到的东西），观察第 3 次同类动作前是否真的在播报/气泡里
体现出"换了思路"而不是原样重复。

### 未排期 / 本批没碰
第一梯队第 3 项（技能固化升级成真自动化）下一批继续。

---

## 第一梯队批三：技能固化升级成真自动化（Claude 实施，未构建未真机）

背景：用户定的功能提升第一梯队第 3 项，也是第一梯队最后一批。

### 做了什么
1. **新增 input（输入文字）步骤**（`skill/SkillItem.kt` 文档更新 + `skill/SkillRunner.kt`）：
   新步骤类型 `{"t":"input","text":"要打的字"}`，假设紧跟在成功点开了输入框的
   `tap` 步骤之后，直接对"当前焦点编辑框"调用 `ScreenController.typeText(text, null)`
   （和任务循环同一套输入通道：无障碍输入法优先、ACTION_SET_TEXT 兜底，密码框不填）。
   只在技能真的含 `input` 步骤时才按需建一个 `ScreenController` 实例，纯 open/tap/wait/swipe
   的技能不受影响。设置页"手动新建技能"的逐行语法加了 `输：要打的字` 这一行写法。
2. **匹配逻辑从纯包含升级成词块重叠模糊匹配**（`skill/SkillService.kt`）：原来必须
   触发句整句被用户的话包含、或话里含技能名，才算命中；现在整句包含仍然是最高优先级，
   没有整句包含时退到词块重叠度打分（重叠比例 ≥ 60% 才算候选），比如"刷一下朋友圈"
   录的技能，用户说"去看看朋友圈"也能命中了。**如实说明**：这不是真正的语义匹配（没
   接入向量/embedding 模型），本质仍是字面重叠，同义词不同字的场景（"发消息"vs"发微信"）
   搜不到。
3. **失败后"从失败点继续"**（新增 `skill/SkillResumeState.kt` + `SkillRunner.run` 加
   `fromIndex` 参数 + `JarvisAgent.kt` 接线）：`SkillRunner.run` 签名改为
   `(ctx, skill, fromIndex=0, onDone: (ok, detail, failedIndex) -> Unit)`，失败时把
   停在第几步通过 `onDone` 的第三个参数带出来；`JarvisAgent` 据此登记进
   `SkillResumeState`（和批二的 `PendingClarification` 同款"很薄的单轮状态机"设计，
   120 秒内有效，说"继续"类的话才会触发续播，没命中就清状态交还正常流程）。续播时
   `SkillRunner` 跳过已经执行成功的步骤，直接从失败那一步重新开始，不再整条重放——
   避免"已经打开了 App"这种已经生效的步骤被重复执行一遍。

### 已知局限（如实标注）
- `input` 步骤不做"上一步是不是真的点进了输入框"这种上下文校验，手动写技能脚本时
  如果 `input` 没有紧跟在成功的 `tap` 后面，大概率会因为"没有焦点编辑框"直接失败，
  如实报错，不会假装打上了字。
- 模糊匹配阈值（60%）是拍脑袋定的经验值，没有做过真机调参；阈值太低会导致随口一句话
  被技能"抢答"（比如技能名两个字恰好出现在无关的话里），阈值太高又会退化回原来的
  纯包含。真机测试后大概率需要回头调这个数字。
- 断点续播只支持"从这一步重新开始"，不支持"把这一步跳过直接做下一步"——如果失败是
  因为这一步本身的目标描述写得不准（比如 `tap` 的 `target` 文字和界面对不上），续播
  只会在同一步再失败一次，需要用户手动去设置页改这个技能的步骤文字，这批没做"编辑
  已存技能某一步"的 UI，只有新建和删除。
- 多个技能并发失败等"继续"的场景不支持——`SkillResumeState` 全应用同一时刻只认一个
  待续播技能，后一个失败会覆盖前一个的待续播登记。目前应用本身也是单任务串行执行，
  理论上不会真的并发出现这个场景，先不处理。

### 自查
`skill/SkillItem.kt`、`skill/SkillService.kt`、`skill/SkillRunner.kt`、
`skill/SkillResumeState.kt`（新增）、`JarvisAgent.kt`、`MainActivity.kt` 六个改动/
新增文件括号配平全过；专项扫描 `$变量` 紧贴中文零命中；`ScreenController`/`TapMarkerUi`
的构造参数、`JarvisAccessibilityService.instance` 的类型，都和既有 `task/TaskLoop.kt`
里的同款用法逐一核对过，没有另起一套不一致的接线方式。`SkillRunner.run` 改了签名
（多了 `fromIndex` 参数、`onDone` 从两参数变三参数），已确认全工程只有
`JarvisAgent.kt` 一处调用点，已同步改掉，没有遗漏的旧签名调用。

**未构建、未真机**——建议验证顺序：①先把现有技能走一遍"手动新建"加一行 `输：测试文字`
在某个点开了输入框的 `点：` 后面，重播确认真的打上字了；②给一个技能录"刷一下朋友圈"
这类触发句，换种说法（"去看看朋友圈"）试命中率，同时注意有没有被平时闲聊误触发；
③故意让某个技能在中间一步失败（比如改错一个包名），确认播报里提示"说继续"，说"继续"
后确认是从失败那一步接着做、不是从头来。

### 第一梯队三批全部完成
批一（记忆语义化）、批二（跨应用任务循环增强）、批三（技能固化升级）都已交付，均未
构建未真机。建议先把这三批合起来编译一次、装机跑一轮完整验证，再决定要不要进入
第二梯队（主动提醒、系统控制能力补齐、端侧模型真正发挥作用）。

---

## 第二梯队批四+批五：真正的主动提醒 + 系统控制能力补齐（Claude 实施，未构建未真机）

背景：用户跳过第二梯队第 4 项（端侧模型真正发挥作用），直接点了第 5、6 项。

### 批四：真正的主动提醒
1. **晨报接天气**（新增 `net/WeatherClient.kt` + `proactive/BriefComposer.kt` 改 suspend）：
   接 Open-Meteo（地理编码 + 当前天气两个免 Key 接口），设置页"晨报与出发提醒"分区
   新增"晨报天气城市"输入框，留空就不播天气（不瞎猜用户在哪，没用定位权限图省事）。
   查询失败（网络问题/城市名没匹配上）如实说"天气没查到"，不编数值——这条红线和
   `AgnesClient` 系统提示词里"天气没数据源不许编"的约束是一致的，只是晨报这条走的是
   独立的本地组稿通道。`BriefComposer.compose` 从同步函数改成 `suspend fun`，`ReminderReceiver`
   （BroadcastReceiver）原来同步调用会有 ANR 风险，改成 `goAsync()` + 后台线程
   `runBlocking` 的标准写法；`MainActivity` 的"现在试听一份晨报"按钮改成 `uiScope.launch`。
2. **日程冲突提醒**（`proactive/DeparturePlanner.kt` 新增 `scanConflicts`）：相邻两个
   日程间隔短于 15 分钟就排一条提醒，复用"出发提醒"总开关和扫描按钮，不单独加开关。
   简化点如实说明：只比较日程"开始时间"的间隔，没有读日程时长/结束时间（当前的日历
   查询 `BriefComposer.todaysEvents` 没取 `DTEND` 字段），真正的"时间重叠"判断需要
   顺带把这个字段加上，这批没做。
3. **出发提醒按实际路程算**——**本批没做**。真要做需要：用户自备地图服务 Key（高德/
   百度 Web 服务）、申请定位权限拿当前坐标、对日程地点文字做地理编码、调驾车路线
   接口估算时长，涉及的 Key 管理 UI + 权限引导 UI + 失败兜底链路比天气复杂一整圈，
   本批没塞进去，继续用固定提前量。如果这项对你来说优先级够高，可以单独开一批做。

### 批五：系统控制能力补齐
1. **WiFi / 蓝牙开关**（`command/DeviceCommands.kt`）：Android 10（API 29）起，
   普通 App 调 `WifiManager.setWifiEnabled()`/`BluetoothAdapter.enable()` 已经不生效了——
   系统认为这类全局开关必须用户亲手点。能做到的最好程度是用 `Settings.Panel`
   （系统提供的快捷切换浮层）把开关带到用户眼前，minSdk 24 低于浮层要求的 API 29 时
   退回传统设置页。**如实播报"你自己点一下"，不说"已经帮你打开了"**。
2. **飞行模式**（`command/DeviceCommands.kt`）：比 WiFi/蓝牙限制更死——连 `Settings.Panel`
   级别的半自动都没有，普通 App 完全没有切换它的 API。只能带用户跳到系统设置里飞行
   模式那一页，如实说明"系统限制，办不到"，不假装能办。
3. **系统级勿扰模式真开关**（`command/DeviceCommands.kt`）：注意这和 `JarvisPrefs` 里
   `Announcer` 自用的"时间段播报静音"是两回事——这里接的是真正的 Android 系统 DND
   （`NotificationManager.setInterruptionFilter`），切了之后全系统通知声音都会按 DND
   规则走。需要用户单独给 Jarvis 授予"勿扰权限访问权"（`ACCESS_NOTIFICATION_POLICY`
   清单里早就声明了，但这是运行时的特殊访问，不是普通权限弹窗），没授权时自动跳转
   授权页并如实说明，不会在未授权状态下假装切换成功。
4. **定时任务 / 自然语言提醒**（新增 `command/ReminderCommands.kt`，接入
   `command/LocalCommands.kt`）：支持"10分钟后提醒我喝水""晚上10点提醒我吃药"这两类
   句式，本地正则直接解析排进 `ReminderScheduler`，不用掉进大模型反过来调用工具。
   只覆盖"相对时间"和"今天/明天内的钟点时间"这两种最常见说法，"三天后""下周一"这类
   更复杂的相对表达没覆盖，如实不命中、交还聊天流程，不瞎解析出一个错误时间。
   相对时间设了 48 小时上限（防止口误说成"999分钟后"悄悄排一个离谱远的提醒）。
   `ReminderScheduler` 新增 `nextAdHocId` 持久化自增计数器，避免这类临时提醒和出发
   提醒（700000 段）、冲突提醒（710000 段）的 id 撞车。
5. **"场景联动"（比如"睡觉模式"一句话触发多个系统动作）**——**本批没做**。原计划
   给 `SkillRunner` 加一种 `{"t":"sys","action":"..."}` 步骤类型，复用已有的技能机制
   让用户自己编排"开勿扰+调小音量"这类组合，这样比另起一套场景系统更省代码也更
   一致；但这批优先把批四/批五做扎实，场景联动留到下一批，`skill/SkillRunner.kt`
   目前仍然只有 open/tap/wait/swipe/input 五种步骤。

### 已知局限（如实标注，合并写）
- 天气/日程冲突/定时提醒都是新增的本地正则 + 网络请求代码，**没有一处真机验证过**，
  尤其是钟点时间解析（`ReminderCommands.CLOCK`）这类正则，中文口语的时间表达方式
  五花八门，这版覆盖的只是最常见的几种写法，真机测试大概率会发现没覆盖到的说法。
- WiFi/蓝牙/飞行模式这三项是 Android 平台本身的限制，不是这版代码能力不够——**任何
  第三方 App 在现代 Android 上都做不到"一句话真正切换"这几个开关**，这不是未来
  某一批能解决掉的事，除非用户愿意走 root/系统签名这类非常规方案。
- 系统勿扰模式的播报文案假设用户一次性把权限开对，没有做"开了权限但选错了 App"
  这类边缘情况的进一步引导——如果真机测试发现这个授权流程不顺，可能需要加一步
  "打开后看看列表里有没有 Jarvis，没有就下拉刷新"这类更具体的提示。

### 自查
`JarvisPrefs.kt`、`net/WeatherClient.kt`（新增）、`proactive/BriefComposer.kt`、
`proactive/DeparturePlanner.kt`、`ReminderScheduler.kt`、`command/DeviceCommands.kt`、
`command/ReminderCommands.kt`（新增）、`command/LocalCommands.kt`、`MainActivity.kt`
九个改动/新增文件括号配平全过；专项扫描 `$变量` 紧贴中文命中一处（`WeatherClient.kt`
注释里，非编译代码，已顺手改掉）。`BriefComposer.compose` 改签名后确认全工程只有
`ReminderReceiver`/`MainActivity` 两处调用点，均已同步改成异步调用，没有遗漏的
旧同步调用。

**未构建、未真机**——建议验证顺序：①设置页填一个真实存在的城市名，点"现在试听一份
晨报"，确认天气那句话听着是真实的、不是模板空话；②说"10分钟后提醒我测试一下"，
等 10 分钟看通知有没有准时弹出；③说"打开WiFi"/"打开勿扰模式"，确认播报说的是
"需要你自己点"而不是谎称已经办成；④如果系统勿扰没授权，确认真的跳转到了那个
专门的授权页面（不是普通的应用详情页）。

---

## 第二梯队批六：场景联动补课 + 端侧模型真正发挥作用（Claude 实施，未构建未真机）

背景：补上批五说"留到下一批"的场景联动，再做第二梯队第 4 项。

### ⚠️ 先自己纠了一个错：本批开工时发现的重复/冲突
动手前先读了一遍已有代码，发现**上一批（批四/批五）新增的天气功能和工程里已有的
东西撞了**，而且其中一处是会直接编译报错的程度，这里如实记录：
- `JarvisPrefs.kt` 当时新增了一份 `getWeatherCity`/`setWeatherCity`（默认空），但文件里
  "批十"那段早就有同名同签名的 `getWeatherCity`/`setWeatherCity`（默认"新乡"）——
  **两份完全同名的函数在同一个 object 里会直接编译不过**，不是运行时才暴露的问题。
  已删掉批四新增那份，统一用批十那份。
- `net/WeatherClient.kt`（批四新增）和已有的 `data/WeatherTool.kt`（批十）功能重复，
  且 `WeatherTool` 明显更成熟（Nominatim 优先、中文地名消歧更准、带今明两日高低温/
  降水概率），`BriefComposer` 改接 `WeatherTool`，删掉了 `WeatherClient.kt`。
- `MainActivity.kt` 的"晨报与出发提醒"分区当时新加了一个重复的"晨报天气城市"输入框，
  其实和"聊天"分区里批十那份"天气默认城市"是同一个设置项——删掉重复输入框，改成
  一行文字说明"同一个设置，在上面聊天分区改"。

**教训**：之前几批在改一个模块前，主要靠搜索该模块内的关键字来确认有没有已有实现，
这次没有先搜一遍"天气"相关的既有代码就直接新建了一套，这是流程疏漏，不是能力问题——
以后涉及新增能力前，会先在整个工程里搜一遍相关中文关键词（这次是"天气"）确认没有
已有实现，而不只是看当前要改的那个文件/模块。

### 场景联动（补上批五的坑）
`skill/SkillRunner.kt` 新增 `sys` 步骤类型（`{"t":"sys","action":"dnd_on"}`），复用
`command/DeviceCommands.kt` 新增的 `runSceneAction`/`sceneActionHints` 执行既有的系统
动作（勿扰开关/手电筒/音量/WiFi 蓝牙飞行模式面板），不是另起一套场景系统——用户
现在可以用"新建技能"把几个系统动作编排成一个"睡觉模式"：比如两行步骤
`场：dnd_on` + `场：volume_down`，一句话触发多个系统动作。`MainActivity.kt` 手动建
技能的提示文字和解析逻辑都加了 `场：` 这行语法。

**已知简化（如实标注）**：`sys` 步骤只要求 action 关键字被认识就算这一步"成功"，
不校验系统动作是否真的生效——比如系统勿扰没授权时，`runSceneAction` 仍然返回
"识别成功"（只是实际会提示去授权），技能重播因此会继续往下走，不会在这一步真正
卡住。这是因为 `DeviceCommands` 现有的函数设计成"尽力而为 + 播报结果"，不返回
布尔值告诉调用方"真的办成没有"——要改成能区分"识别了但没办成"需要把这些函数的
签名也一起改掉，本批没有动这个更大的改动。

### 端侧模型真正发挥作用
1. **本地模型接上检索增强**（`local/LocalLlm.kt` + `memory/MemoryService.kt` +
   `JarvisAgent.kt`/`MainActivity.kt` 的本地回答分支）：本地模型原来完全不带记忆注入
   （代码注释原话"gemma3 上限 1024 塞不下"，直接整个跳过不给），现在
   `MemoryService.getInjectableMemories` 加了 `topN` 参数，本地问答路径要 Top-2（云端
   仍然是 Top-8），`LocalLlm.buildPrompt` 再加一道 150 字符硬截断兜底，确保不会把
   1024 token 的上下文预算大半吃掉在记忆片段上——本地模型第一次真正用上检索到的
   记忆，不再是空着问。
2. **路由策略更细**（`net/ChatRouter.kt`）：原来只看"这句话是不是问答形态"，现在加了
   两个优先级更高的信号——①提到密码/账号/病情这类字眼，不管是不是问答，直接本地
   优先（如实说明：这是关键词匹配，不是真正的隐私风险识别，覆盖不到委婉说法，本地
   答不出来兜底云端时原话仍会发出去，这只是调整"先试哪条路"，不是硬限制）；②当前
   没有可用网络时直接本地优先，省掉一趟必定超时的云端请求；③电量低于 15% 且未充电
   时云端优先（端侧推理比一次网络请求更耗电）。保留了纯文本重载 `cloudFirst(text)`
   兼容不方便传 Context 的调用场景。

### 自查
`command/DeviceCommands.kt`、`skill/SkillRunner.kt`、`skill/SkillItem.kt`、
`MainActivity.kt`、`memory/MemoryService.kt`、`local/LocalLlm.kt`、`JarvisAgent.kt`、
`net/ChatRouter.kt`（重写）、`proactive/BriefComposer.kt`、`JarvisPrefs.kt` 十个改动
文件括号配平全过；专项扫描 `$变量` 紧贴中文零命中；额外跑了一遍全工程范围的"同一个
文件里是否有同名函数重复声明"扫描（就是这次发现 `JarvisPrefs` 问题用的方法），确认
扫出来的另外三处（`ChatRouter.cloudFirst`、`ScreenController.typeText`、
`OrbController.setState`）都是参数不同的正常重载，不是重复声明。`ChatRouter.cloudFirst`
改了签名（加了 `Context?` 参数），已确认全工程两处调用点都同步改了，没有遗漏的
旧签名调用。

**未构建、未真机**——建议验证顺序：①设置页用"新建技能"建一个两步的"场：dnd_on"+
"场：volume_down"技能，说技能名触发，确认两个系统动作都执行了（如果勿扰没授权，
确认播报里说清楚了需要去哪里开权限）；②关掉 WiFi 和移动数据（完全断网），开着
"智能调配"问一句话，确认走的是本地模型而不是先卡几秒等云端超时；③设置里"记住：
我不吃香菜"，然后在确保走本地模型的条件下（断网或改成"闲聊优先本地"）问"中午吃
什么"，看本地模型的回答有没有体现出这条记忆（如果本地模型实在太小、不太会用上
下文里塞的这段记忆，这是模型能力限制，不是接线没通——需要看真机输出判断是哪种）。

### 第二梯队进度
已完成第 5、6 项（主动提醒、系统控制）、补上场景联动、完成第 4 项（端侧模型）。
第二梯队清单里剩下主动性那条里"长时间未回复消息提醒"没做——评估下来技术可行性
存疑（NotificationListenerService 只能看到有没有新通知进来，没有可靠手段判断用户
是否已经在聊天 App 里亲自回复了对方，容易做出一个"自以为是"但经常误判的功能），
建议除非你有具体思路，否则先不做这项，以免交付一个看着像智能、实际上经常瞎提醒
的功能。

---

## 第二梯队批七：主界面视觉重做——银河系尺寸/密度 + 聆听思考应答的 3D 效果（Claude 实施，未构建未真机）

背景：用户反馈"主界面银河系星团太小，太密集""聆听、思考、回复的3D效果太简陋"，要求认真改一版。

### 先说清楚"太小太密集"的真正原因
不是粒子数太多——是对数螺旋的参数算出来的问题：原版 `a=3.2 b=0.26 maxTheta=4.3`，
算出来螺旋臂的外缘半径只有约 9.8（逻辑坐标系是 0..260，舞台半径对应 130），而核心
发光球画的是半径 13 个单位——**核心的发光光斑比整条螺旋臂本身还大**，等于整个星系
被挤在发光核心的光斑里糊成一团，这才是"又小又密"的根本原因，不是粒子数量的问题。

### 做了什么（`hud/GalaxyHoloStageView.kt`，全文件范围的调整）
1. **星系重新定标**（`generateGalaxy()`）：螺旋臂参数改成 `a=6 b=0.47 maxTheta=6.5`，
   外缘半径从约 9.8 拉到约 120，跟舞台半径匹配；螺旋形态从"不到一圈的紧臂"变成
   "一圈多的舒展臂形"。抖动幅度从定值改成跟着半径等比例缩放，外圈粒子不会被甩得
   过散、臂形轮廓在外围也还认得出来。粒子总数从约 190 提到约 360（三条臂各加了
   约 30 个 + 一层新增的"弥散盘"130 个 + 外层光晕从 50 个增到 55 个且半径跟着新的
   臂展走，不再是写死的 70-128），但因为摊开的面积是原来的十几倍，视觉密度反而
   明显降下来了——弥散盘粒子还做了半径平方分布（越靠外越稀疏），更接近真实星系盘
   的密度衰减观感，不是均匀糊一层。
2. **思考状态**（新增 `drawThinkingOrb`）：原来是 5 根横向滚动的虚线，纯 2D 效果，
   平面上几条线在跑，看不出立体感。换成一圈绕轴旋转的全息粒子环——每颗点算出
   x/y 坐标和一个"深度"值（简易参数化 3D 投影：环面相对视线倾斜 58°，深度决定
   缩放和透明度），按深度从远到近排序绘制，近处的点更大更亮、远处的点更小更暗，
   近处还叠一层柔光强化体积感；环下方画一条淡化的椭圆轨迹线作为"轨道"参照，
   中心留一个脉动核心点呼应"正在运算"。
3. **聆听 / 应答状态**（新增 `drawVoiceBars3D`）：原来是单纯的描边竖线（经典但
   平面的声波条）。换成带"顶面高光 + 柱体侧面 + 地面倒影"的伪 3D 柱体——侧面用
   偏暗的平行四边形模拟柱体厚度，正面用垂直渐变（亮顶→暗底）模拟打光，柱顶一层
   更亮的圆角高光帽模拟顶面受光，基线下方加一段淡化镜像模拟地面反光；整排柱子
   沿一条浅拱形排布（中间略抬高、两侧略压低）而不是死板的直线，叠加面板本身
   已有的 16°/-10° Camera 倾斜，组合起来进深感明显比之前强。

### 已知局限（如实标注）
- 这两处"3D"效果都是简易参数化投影/等轴测画法，不是真正的三维渲染管线——旋转角度
  或柱子高度差太大时，透视关系会有肉眼可察的失真（比如思考环如果把 `ringR` 调得
  很大，近远粒子的缩放差会显得夸张）；这版用的参数是凭观感调的，没有真机逐帧对比
  验证过观感是否符合预期，大概率需要装机看一眼实际效果再微调一两个数字（比如环的
  倾角、柱子的拱形幅度）。
- 星系粒子总数从约 190 提到约 360，每帧仍然是普通 `Canvas.drawCircle` 逐个画，
  没有改用更高效的批量绘制方式（比如 `drawPoints` 一次性画一批点）——数量级上
  对现代手机应该完全不是问题，但如果真机测出掉帧，这是可以优化的方向。
- 核心发光球的半径（13 个单位）这次没有跟着螺旋臂放大同比例调整，只是因为螺旋臂
  放大后核心显得合适了（之前是核心比臂还大，现在核心变成一个与整个星系匹配的
  小亮核），这是坐视觉效果判断的调整，没有一个精确的"按比例换算"依据。

### 自查
`hud/GalaxyHoloStageView.kt` 括号配平通过；专项扫描 `$变量` 紧贴中文零命中；移除了
不再使用的 `DashPathEffect` 导入（思考状态换成粒子环，不再用虚线效果）；新增代码里
用到的 `android.graphics.Path`/`android.graphics.LinearGradient` 都用完全限定名，
没有新增 import、不会和已有的 `Camera`/`Canvas`/`Color`/`Matrix`/`Paint`/
`RadialGradient`/`Shader` 这几个已导入的类冲突。

**未构建、未真机**——这批是纯视觉调整，建议直接装机对着实际效果看，大概率需要现场
微调一两个数字（比如觉得星系还是偏小/偏密，直接调 `generateGalaxy()` 里的 `a`/`b`
或粒子数量；思考环如果转速/倾角不顺眼，调 `drawThinkingOrb` 里的 `spin`/`tiltRad`；
声波柱的拱形幅度在 `drawVoiceBars3D` 的 `arcLift` 那一行）。这批没有触碰过渡动画
（坍缩/冲击波/展开）、面板本身的倾斜角度、配色，只改了星系粒子分布和面板内容两块。
