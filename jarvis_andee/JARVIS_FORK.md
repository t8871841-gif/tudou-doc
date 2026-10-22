# Jarvis 版（基于 Andee 的个人定制分支）

本目录基于开源项目 **kuafuai/andee**（版本 0.5.2）复制修改，仅供机主个人在自己的设备上使用，不对外分发成品、不作为产品发布。

- 原项目许可证：见同目录 `LICENSE`（Apache License 2.0 + KUAFUAI 附加条件）。
- 按许可证 A.1(c) 要求，修改过的文件在此声明：
  - `app/build.gradle`：applicationId 改为 `com.jarvis.andee`（与原版并排安装）、versionName 加后缀 `0.5.2-j1`。
  - `app/src/main/res/values/strings.xml`、`values-en/strings.xml`：应用显示名改为 Jarvis（个人自用场景）。
  - `app/src/main/res/mipmap-*`：启动图标替换为 Jarvis 图标，删除原自适应图标 XML 与 webp。
  - `app/src/main/java/net/kuafuai/andee/config/VoiceConfig.kt`：默认大模型端点/模型改为机主自用的 Agnes 配置（不含任何 Key）。
  - `app/src/main/java/net/kuafuai/andee/brain/LocalPrompt.kt`：系统提示词中的自称由 Andee 改为 Jarvis，其余规则未动。
- 二期（语音与人格）修改文件：
  - 新增 `app/src/main/java/net/kuafuai/andee/tts/EdgeTts.kt`：微软 Edge 神经网络 TTS 引擎（云扬 zh-CN-YunyangNeural，免费、无需 Key），协议移植自机主 Flutter 版 Jarvis 已真机验证的实现（api.msedgeservices.com + Sec-MS-GEC），MP3 经 MediaCodec 解码为 PCM。
  - `config/VoiceConfig.kt`：新增语音提供方 `edge`，并设为本分支默认（设置里可切换回火山/谷歌）。
  - `tts/TtsController.kt`：引擎工厂新增 Edge 分支。
  - `ui/SettingsUi.kt`：语音服务商选择器新增“微软 Edge（云扬 · 免费）”一项。
  - `device/SelfCheck.kt`：自检对 Edge 提供方按“无需 Key”判定，连通性探测指向 Edge 主机。
  - `res/values/strings.xml`、`values-en/strings.xml`：新增提供方名称字符串。
  - `brain/LocalPrompt.kt`：人格补充——称呼用户为“先生”、默认中文交流、语气克制干练。
- 注意：Edge 提供方只负责“说”（TTS）；“听”（ASR）仍沿用火山/谷歌路径，无火山 Key 时用打字交互。
- 三期（离线语音识别）修改文件：
  - 新增 `app/src/main/java/net/kuafuai/andee/asr/OfflineAsr.kt`：sherpa-onnx 流式 zipformer 中英双语离线识别引擎（与机主 Flutter 版 Jarvis 同模型同运行时版本 1.12.23）；模型首次运行时从 APK assets 复制到私有目录后以文件路径加载，识别器进程内单例、每轮新建流。
  - `asr/AsrController.kt`、`meeting/MeetingRecorder.kt`：提供方为 edge 时听走 OfflineAsr（说仍走 Edge 云扬）。
  - `app/build.gradle`：新增本地 AAR 依赖 `libs/sherpa-onnx-1.12.23.aar`（AAR 不进 git，CI 构建时下载）。
  - `.github/workflows/build-jarvis-andee-apk.yml`：构建前下载 AAR 与 4 个模型文件到 `app/src/main/assets/asr/`（模型同样不进 git，见 `.gitignore`）。
  - `res/values/strings.xml`、`values-en/strings.xml`：edge 提供方标签改为“云扬 + 离线识别（免费）”。
  - 至此默认组合为：说 = Edge 云扬、听 = 离线识别，均无需任何 Key；APK 体积因模型增至约 220MB。
- 仓库根目录 `andee/` 保留上游原样副本，本目录的改动均在 git 历史中可查。
