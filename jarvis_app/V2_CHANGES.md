# Jarvis v2 改动说明（框架未动：Flutter + Kotlin 分层、MethodChannel、TaskBoard/SkillStore 全部保留）

## 一、修掉的 bug
1. LlmService 全局删 `*`：规划/计算走同一路，`128*3+45` 变 `1283+45` → 现在只对展示文本去 Markdown，机器消费的调用 `plain:false`。
2. `_planSteps` 的 `replaceAll('json','')` 会删正文里的 json 字样 → 只去代码围栏。
3. 系统提示词没有日期时间 → 每次请求注入当前日期/星期/时间。
4. `llm_reasoning` 是假步骤 → 真的调用模型，并带上前面步骤结果；未知工具名按 device_action 处理。
5. 联网搜索（DuckDuckGo 即时答案，中文几乎空）→ 博查兼容接口（设置里填 Key）→ 中文维基 → DDG 兜底；全部网络失败才抛错。

## 二、新增能力
- 端侧/云端分流：`LlmPurpose` + `LlmService.shouldUseLocal`。本地：寒暄闲聊、晨报润色；云端：规划、看屏决策、汇总、推理、记忆整理、一切知识/实时问题。云端失败或没配 Key 时聊天自动本地兜底。本地模型 = MediaPipe LLM（`LocalLlmBridge.kt`），设置里“选择模型文件”导入。
- 快路径优先（不再先过一次大模型规划）+ 聊天/任务分流 + 流式输出 + 按句边出字边播（预合成下一句）；Edge TTS 失败自动用系统 TTS 兜底。
- 常驻待命：前台服务（microphone）+ 通知；连续对话；默认助手（VoiceInteractionService + ASSIST）；快捷设置磁贴。
- 新工具（ToolService，本地正则，不调大模型）：提醒（含取消/查看）、计时器、音量、媒体控制、勿扰、Wi-Fi/飞行模式（只能打开系统面板）、天气（定位/城市）、定位、日程查询、读通知（验证码类自动隐藏）、剪贴板、晨报。
- 主动提醒：AlarmManager.setAlarmClock，提醒与每日晨报，开机后自动恢复。
- 记忆：条目带分类与时间，按当前问题检索 top-k（不再全量塞提示词）；整理时可记“近期事件”。
- 看屏代理：单步内最多 6 轮 看屏→决策→动手→核验；新增 scroll/done；“做完了”必须给出可核验文字并真的出现在屏幕上。看屏白名单可在设置里加（含 bank/pay/wallet/settings 的包名被原生层拒绝，点击黑名单照旧）。

## 三、必须知道的事
- 本次没有在含 Flutter/Android 工具链的环境里编译或运行过（沙箱无网络、无 Flutter），只做了括号平衡检查和逐行复核。请先跑一次 CI，把编译报错贴给我。
- `minSdk` 由 23 提到 24（MediaPipe 要求）。依赖 `com.google.mediapipe:tasks-genai:0.10.24`，解析不到就改成最新 0.10.x。
- 默认助手入口（VoiceInteractionService）必须真机验证；不同 ROM（Flyme）行为不一。
- 本地模型需自备（Qwen2.5 0.5B/1.5B、Gemma 3 1B 的 MediaPipe `.task`），1B 级模型答不了知识题，所以只做闲聊/兜底。
- 没做：打断说话（需回声消除）、截图+视觉模型看屏、来电/短信主动播报。

## 四、悬浮球（v2.1）
- `OrbController.kt`：无障碍覆盖层（TYPE_ACCESSIBILITY_OVERLAY），不需要“悬浮窗”权限；仅在 Jarvis 不在前台时显示。
- 点一下：常驻服务在跑 → 直接开始听；否则拉起 Jarvis 再听。长按打开 Jarvis。拖动吸附左右边缘并记住位置。
- 球色随状态变化（待命/聆听/思考/播报），回答在球旁弹出气泡。
- 开关：设置 > 常驻与入口 > 悬浮球（需先开无障碍服务）。需真机验证：Flyme 对覆盖层的后台策略。
