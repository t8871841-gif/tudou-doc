# Jarvis

Android APK：Jarvis 语音助手。深色底 + 蓝色全息 HUD，原创矢量绘制（开机方舟反应堆、全息粒子球、左右面板、波形、思考扫描），电影感但不用任何电影剧照或版权图。

- 主界面：HUD + 对话
- 设置页：接口地址、模型、API Key、语音、语速
- 大模型：默认 `https://apihub.agnes-ai.com/v1` / `agnes-3.0-flash`，Key 留空，用户在 App 设置里自己填
- 语音：微软 Edge 神经网络语音，男声默认（云扬），默认语速偏慢，可调；语音输入 + 语音播报 + 打字
- 交互：说“贾维斯”唤醒、点麦克风、打字；手势交互已取消
- 构建：`flutter build apk --release`，或用 `.github/workflows/build-apk.yml` 云端构建
