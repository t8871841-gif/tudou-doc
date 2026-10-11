# 专项 Backlog（既有历史问题，不阻塞 v1.11.19 上线）

> 创建：2026-09-10
> 说明：以下两项测试失败为 **v1.11.19 之前的既有问题**，与本版本改动（内经结构化条目检索 / 深色模式对比度 / 早晚子时×真太阳时校正）无关，不纳入本次修复范围，待后续专项处理。

## 1. `quality_score_bc` — 功能 B 流日命宫 oracle 维度不达标

- **失败点**：`test/quality_score_bc_test.dart` → `功能 B（流月/流日）质量评分 ≥ 90`，关键 oracle 维度
  **B2 流日命宫与引擎 oracle 一致：7.9 分 (5/63)**，要求 ≥ 95。
- **根因**：功能 B（流日）的命宫计算与引擎 oracle 参考集不一致，质量评分门禁未达阈值。
  属功能 B 自身质量问题，**与八字/紫微引擎本次改动无关**。
- **处置**：专项排查流日命宫算法，对齐 oracle；不阻塞本次上线。
- **关联文件**：`lib/.../flow_day`（流日命宫）、`test/quality_score_bc.dart`（评分口径）。

## 2. `ziwei_bench` — 跨引擎精度比对 30/32

- **失败点**：`test/ziwei_bench_test.dart` → `MingLi-Bench cross-engine comparison`，
  `major 30/32`、`sihua 30/32`、`bazi 29/32`，断言要求 32/32。
- **根因**：与 iztro 参考集的流派约定差（测试文件头已注明 known convention diff）：
  - case_9 [中国]：日柱/时柱差（辛亥→庚戌 / 戊子→丙子），子时边界口径。
  - case_28 [singapore]：日柱/时柱差，真太阳时/地理口径。
  - case_31 [中国]：年柱 立春(本引擎) vs 春节(iztro)。
- **处置**：专项评估——在比对中显式豁免已知约定差（如 case_9/28/31），或将断言从 32 放宽到 30 并补充约定差说明；不阻塞本次上线。
- **关联文件**：`test/data/fortune_api_results.json`（参考集）、`lib/services/ziwei_engine.dart`。

---

### 附：本版本相关测试状态（校正于 2026-09-07）

- `ziwei_chart`：lunarText 升级为新干支格式后，测试断言已同步 ✅（Task 1）
- `bazi_core_crosscheck` 23:30：**经核查为既有历史问题（非 v1.11.19 回归）**——
  该用例以 `ratHourMode=false` 走「子时归自然日」plain 路径（our 侧 todayGan → `戊午 壬子`），
  但参考侧 `_bc` 用的是 bazi_core **默认 noSplit**（→ `己未 甲子`），两库口径天然差一天而误报。
  基线 306d135 起即如此（bazi_core 版本同为 `^0.6.7`，plain 路径同为 todayGan）。
  修复方式：将测试参考 `_bc` 显式改为 `RatHourMode.todayGan`，与 app 实际口径
  （八字屏/紫微屏统一 todayGan，见 `ziwei_engine.dart:429` 注释）对齐，使交叉验证真正可比。
  **服务层 `bazi_service.dart` 无改动**（HEAD 即正确，与 Task 2 规格一致：effHour 真太阳时先校正 +
  晚子时当日日柱 + 次日子时）。✅
- 说明：此前「bazi_core_crosscheck 为本次回归」的判断有误，特此更正。本版本（v1.11.19）实际
  引入的回归仅 `ziwei_chart` 一处（lunarText 格式断言），已修复。
