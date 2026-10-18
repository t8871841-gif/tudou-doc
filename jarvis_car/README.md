# Jarvis 车机版（jarvis_car）

Jarvis 的车机轻量版，目标设备：纽曼 Newsmy 后装安卓大屏（Android 8.1、32 位 ARM、1GB 内存、横屏 1024×600）。

## 设计

- **横屏 Jarvis HUD**：反应堆圆环 + 回答面板 + 波形，纯代码绘制，无图片资源。
- **流程**：点麦克风 → 嘀一声 → 说话 → 回答（文字 + 语音播报）。
- **语音输入**：系统语音识别（`speech_to_text` 插件），不用 199MB 离线识别模型。车机若无系统识别服务，界面会明说不可用。
- **本地指令（秒回，不联网）**：播放音乐、打开导航、导航去 X、去 X、打电话、现在几点、打开 X。
- **闲聊**：云端大模型（OpenAI 兼容接口），默认 DeepSeek（`https://api.deepseek.com/v1` + `deepseek-chat`），Key 默认空，用户在设置里填自己的。
- **语音播报**：Edge 神经网络语音（云扬男声），需经手机热点联网。
- **轻量**：无无障碍服务、无记忆服务、无通讯录/短信/闹钟、无离线识别模型。

## 构建（32 位）

```sh
flutter build apk --release --target-platform android-arm --split-per-abi
```

GitHub Actions 工作流（`build-jarvis-car-apk.yml`）负责构建并上传工件。

## 注意

- 包名 `com.jarvis.jarvis_car`，与手机版 `com.jarvis.jarvis_app` 不同，可共存。
- `openApp` 用 `getLaunchIntentForPackage` 按包名启动；目标车机是 Android 8.1，Android 11+ 的包可见性规则不适用。
- API Key 只存在本机安全存储，绝不写进代码。
