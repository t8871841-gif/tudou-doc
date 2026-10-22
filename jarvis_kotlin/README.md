# Jarvis 纯 Kotlin 版

v3 版 Jarvis 从 Flutter 改为纯 Kotlin 原生实现的新工程（2026-10-22 立项）。

- 一期：工程骨架 + v3 原生层搬运（无障碍/悬浮球/常驻服务/通知监听/磁贴/提醒）
  + 原生主界面（连接状态自检屏）；无障碍 onUnbind 允许自动重绑。
- 二期：对话（Agnes）+ 云扬 TTS + 离线识别，语音组件直接复用已验证的实现/下载件。
- 三期：看屏跨应用借鉴 Andee 的做法（节点树优先、网格标定、准星放大、
  同位置点击防呆），慢慢打磨；v3 的急停/审计/自检/技能库逐项搬回。

包名 com.jarvis.native，可与 Flutter 版 Jarvis、原版 Andee 并排安装。
构建：推 jk-v* 标签触发 GitHub Actions，产物发 Release jarvis-kotlin-apk-<run号>。
