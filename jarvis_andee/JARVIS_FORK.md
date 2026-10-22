# Jarvis 版（基于 Andee 的个人定制分支）

本目录基于开源项目 **kuafuai/andee**（版本 0.5.2）复制修改，仅供机主个人在自己的设备上使用，不对外分发成品、不作为产品发布。

- 原项目许可证：见同目录 `LICENSE`（Apache License 2.0 + KUAFUAI 附加条件）。
- 按许可证 A.1(c) 要求，修改过的文件在此声明：
  - `app/build.gradle`：applicationId 改为 `com.jarvis.andee`（与原版并排安装）、versionName 加后缀 `0.5.2-j1`。
  - `app/src/main/res/values/strings.xml`、`values-en/strings.xml`：应用显示名改为 Jarvis（个人自用场景）。
  - `app/src/main/res/mipmap-*`：启动图标替换为 Jarvis 图标，删除原自适应图标 XML 与 webp。
  - `app/src/main/java/net/kuafuai/andee/config/VoiceConfig.kt`：默认大模型端点/模型改为机主自用的 Agnes 配置（不含任何 Key）。
  - `app/src/main/java/net/kuafuai/andee/brain/LocalPrompt.kt`：系统提示词中的自称由 Andee 改为 Jarvis，其余规则未动。
- 仓库根目录 `andee/` 保留上游原样副本，本目录的改动均在 git 历史中可查。
