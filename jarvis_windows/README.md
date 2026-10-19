# Jarvis Desktop (Windows)
1. 安装 Node.js 18+
2. 在本目录运行：`npm install`
3. 下载手势模型到 models 目录（PowerShell）：
   `Invoke-WebRequest https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task -OutFile models/hand_landmarker.task`
4. `npm start`

按键：Esc 退出，空格/→ 翻面，M 静音。手势：挥手翻面，握拳静音，helmet 页面手左右移动转头盔。
自定义：天气坐标在 index.html 顶部 CFG；日程改 schedule.json；真头盔模型放 models/helmet.glb（自动替换内置头盔）。

## 语音对话与记忆
- 首次使用：复制 `config.example.json` 为 `config.json`，填入 apiKey（密钥只在主进程使用，页面和本地服务都读不到；`config.json` 已进 .gitignore，不会提交）。
- 大模型走免费 Agnes 接口（`https://apihub.agnes-ai.com/v1/chat/completions`，OpenAI 格式），回答与记忆提取默认都用 `agnes-3.0-flash`。
- 唤醒：说“贾维斯 …”，之后 15 秒内可直接继续说；也可按 / 键打字。握拳静音时不响应语音。
- 识别用 Windows 自带语音识别（需安装中文语音识别：设置 > 时间和语言 > 语音）。识别不准可以按 / 键打字，或之后换成 Whisper。
- 记忆：每轮对话后由记忆模型提取持久事实，存在 data/memory.json（本机），每次对话注入提示词；说“忘掉……”即可删除对应条目。也可直接编辑该文件。
- 注意：对话内容会发送到 Agnes API；记忆提取默认不记密码、证件号等敏感信息。
