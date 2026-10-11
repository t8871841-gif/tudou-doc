# 汉唐本草定制版 APK 构建指南

本分支改造包含：
- 中式古典黑金主题（沿用应用现有深色金色主题体系）。
- 新增「语音AI」页面：中文语音输入、AI 文字回答、系统 TTS 朗读。
- OpenAI 兼容的 `/chat/completions` 接口配置（Base URL、模型名、API Key）。
- API Key 使用 `flutter_secure_storage` 保存，不写入源代码。
- 通过 `assets/SKILL.md` 做本地关键词检索；无 API Key 时仍能返回匹配的离线片段。
- 新增麦克风权限和 Android 语音/TTS 服务查询声明。
- 新增 GitHub Actions 手动构建工作流，输出通用 APK 与 ABI 分包 APK。

## 用 GitHub 手机端构建
1. 将此 ZIP 解压。
2. 在 GitHub 创建一个新仓库（建议 Private），把解压后的文件上传到仓库根目录。GitHub 网页端可通过 `Add file` 上传文件；大文件多时建议用电脑或 GitHub Codespaces。
3. 打开仓库的 **Actions** 标签页，启用 Actions。
4. 选择 **Build HanTang Custom APK** 工作流，点 **Run workflow**。
5. 等待任务完成，进入该次运行详情，在 **Artifacts** 区下载 `hantang-bencao-custom-apk`。
6. 解压 Artifact，安装 `app-release.apk`（通用版）到 Android 手机。ABI 分包仅在需要时使用。

## 应用内配置 AI
- API Base URL 示例：`https://api.openai.com/v1`
- Model 示例：`gpt-4o-mini`（需替换为你的服务商实际支持的模型名）
- API Key：在 App 的「语音AI」页右上角设置按钮中填写。
- 第三方兼容服务应提供 `POST {Base URL}/chat/completions`，并接受 Bearer Token 和 OpenAI 风格 messages。

## 注意
- 此构建流程需 GitHub Actions 能下载 Flutter、Android SDK/NDK 和 pub 依赖。
- 本环境没有 Flutter SDK，因此未在这里声称已经编译或测试通过 APK；必须以 Actions 实际构建结果为准。
- 语音识别依赖 Android 设备上可用的系统语音识别服务和麦克风权限；部分设备/地区可能需要安装或启用语音服务。
- 本项目内容属于中医典籍学习与检索工具，不替代医生诊断；对真实症状请咨询合格医疗专业人员。
