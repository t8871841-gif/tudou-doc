/// 八字排盘聚合计算：四柱 + 十神 + 旬空 + 刑冲合害 + 长生十二神 + 八字详批。
///
/// 四柱（年/月/日/时干支）取自 [bazi_core] 的 [bazi.BaziChart.createBySolarDate]，
/// 与 [computeBaZiFortune] 大运同源，确保四柱与大运口径一致（年柱按立春、
/// 日柱用权威基准日、子时归属按 [ratHourMode]）。其余关系检测复用既有纯函数引擎，
/// 本文件只做编排，不重复实现历法 / 五行算法。
library;

import 'package:ziwei_core/ziwei_core.dart' show Gender, Location, RatHourMode;

import 'package:bazi_core/bazi_core.dart' as bazi;

import 'package:nihaisha_app/engine/bazi_analysis.dart'
    show analyzeBaZi, BaZiAnalysis;
import 'package:nihaisha_app/engine/bazi_relations.dart'
    show detectBaziRelations;
import 'package:nihaisha_app/engine/bazi_ten_gods.dart'
    show kongWang, tenGodsPerPillar;
import 'package:nihaisha_app/engine/bazi_twelve_stages.dart'
    show TwelveStageMode, twelveStagesForPillars;
import 'package:nihaisha_app/services/ziwei_engine.dart' show ZiweiBaZi;
import 'package:sxwnl_spa_dart/sxwnl_spa_dart.dart' show LunarDate, AstroDateTime;
import 'package:nihaisha_app/engine/bazi_extra.dart'
    show taiYuanOf, taiXiOf, kongWangPerPillar, selfTwelveStages, hiddenTenGods;

/// 单柱干支（如「甲辰」）拆成 (干, 支)。
(String, String) _splitGanZhi(String gz) =>
    (gz.substring(0, 1), gz.substring(1, 2));

/// 由农历年份（天文纪年，如 2024）求对应干支（如 甲辰）。
///
/// 干支 60 年一循环，约定甲子为第 0 年，公历 4 年为甲子年基准。
const List<String> _tiangan = ['甲', '乙', '丙', '丁', '戊', '己', '庚', '辛', '壬', '癸'];
const List<String> _dizhi = ['子', '丑', '寅', '卯', '辰', '巳', '午', '未', '申', '酉', '戌', '亥'];
String _lunarYearGanZhi(int year) {
  var i = (year - 4) % 60;
  if (i < 0) i += 60;
  return _tiangan[i % 10] + _dizhi[i % 12];
}

/// 八字排盘结果聚合。
class BaZiPaipan {
  final ZiweiBaZi bazi;
  final List<String> gans;
  final List<String> zhis;
  final List<String> tenGods; // 十神（日主位固定为『日主』）
  final List<String> kongWang; // 旬空（空亡）地支
  final List<String> relations; // 刑冲合害 / 合会标签
  final List<String> twelveStages; // 长生十二神（年/月/日/时）
  final BaZiAnalysis analysis; // 格局 / 日主强弱 / 神煞 / 五行 / 用神忌神
  final String taiYuan; // 胎元
  final String taiXi; // 胎息
  final List<String> kongWangPillars; // 各柱空亡（按各柱旬）
  final List<String> selfStages; // 自坐十二神
  final List<List<String>> hiddenTenGods; // 副星（各柱藏干十神）
  final String lunarText; // 出生农历（如「甲辰（2024）年正月初一」）

  const BaZiPaipan({
    required this.bazi,
    required this.gans,
    required this.zhis,
    required this.tenGods,
    required this.kongWang,
    required this.relations,
    required this.twelveStages,
    required this.analysis,
    required this.taiYuan,
    required this.taiXi,
    required this.kongWangPillars,
    required this.selfStages,
    required this.hiddenTenGods,
    required this.lunarText,
  });
}

/// 构造 bazi_core 命盘，并按 [ratHourMode] 处理早晚子时。
///
/// 返回 [chart]（用于大运 / 一般四柱）与可选的 [timeOverride]：晚子时场景下，
/// 「日柱取当天 + 时柱取次日子时」属于混合口径，bazi_core 单一 [RatHourMode] 无法
/// 直接表达，故日柱取自 `todayGan` 盘、时柱取自 `noSplit` 盘并覆盖。
class _BaziChartBuild {
  final bazi.BaziChart chart;
  final String? timeOverride;
  /// 农历基准日覆盖：开早晚子时时，农历按「校正后（真太阳时）时刻所在日」而非出生日计。
  final DateTime? lunarBase;
  _BaziChartBuild(this.chart, this.timeOverride, {this.lunarBase});
}

_BaziChartBuild _buildBaziChart(
  DateTime solar,
  bool ratHourMode,
  Location? location,
  bool useTrueSolarTime,
  Gender gender,
) {
  final hour = solar.hour;
  final loc = location ?? Location(120, 30);
  final astro = bazi.AstroDateTime(
      solar.year, solar.month, solar.day, solar.hour, solar.minute);
  // 早晚子时必须以「真太阳时校正后」的小时判定：校正在引擎内部发生，
  // 若先用原始小时判子时再让引擎校正（旧逻辑），跨子时场景会出现
  // 「先 +1 天、再被校正回前一天」的顺序颠倒，日柱差一天、时柱落到亥时。
  var effHour = hour;
  if (useTrueSolarTime) {
    final probe = bazi.BaziChart.createBySolarDate(
      clockTime: astro,
      location: loc,
      ratHourMode: RatHourMode.todayGan,
      useTrueSolarTime: true,
      gender: gender,
    );
    // virtualTime = 引擎排盘基准时间（真太阳时开启时即校正后时刻）。
    effHour = probe.time.virtualTime.hour;
  }

  // 不开早晚子时（子时归自然日）：真太阳时照常校正；但**仅当校正后落在子时**
  // （23:00–24:00 或 00:00–01:00）时，该子时归「出生时钟日（自然日）」——
  // 日柱 / 时柱 / 农历 全取出生日（不随真太阳时跨日回拨）。
  // 校正后为非子时（如 22:xx 亥时），农历仍按校正后当日（与开一致）。
  if (!ratHourMode) {
    final bool ziShi = useTrueSolarTime && (effHour >= 23 || effHour < 1);
    final chart = bazi.BaziChart.createBySolarDate(
      clockTime: astro,
      location: loc,
      ratHourMode: RatHourMode.todayGan,
      useTrueSolarTime: ziShi ? false : useTrueSolarTime,
      gender: gender,
    );
    if (ziShi) return _BaziChartBuild(chart, null); // 出生日
    final vt0 = chart.time.virtualTime;
    return _BaziChartBuild(chart, null,
        lunarBase: DateTime(vt0.year, vt0.month, vt0.day));
  }

  if (ratHourMode && effHour >= 23) {
    // 晚子时：日柱用当天（todayGan），时柱用次日子时（noSplit）。
    final dayChart = bazi.BaziChart.createBySolarDate(
      clockTime: astro,
      location: loc,
      ratHourMode: RatHourMode.todayGan,
      useTrueSolarTime: useTrueSolarTime,
      gender: gender,
    );
    // 时柱取「校正后次日」的子时：noSplit 的换日判定基于原始 clockTime，
    // 真太阳时开启时拿不到次日子时，故直接以次日 00:30（恒在子时、关闭二次
    // 校正避免再偏移）排一盘取时柱。
    final vt = dayChart.time.virtualTime;
    final nextZi = bazi.AstroDateTime(vt.year, vt.month, vt.day, 0, 30)
        .add(const Duration(days: 1));
    final timeChart = bazi.BaziChart.createBySolarDate(
      clockTime: nextZi,
      location: const Location(120, 30),
      ratHourMode: RatHourMode.todayGan,
      useTrueSolarTime: false,
      gender: gender,
    );
    // 晚子时农历按「校正后时刻所在日」计（vt 当日），而非出生日。
    final vtDate = DateTime(vt.year, vt.month, vt.day);
    return _BaziChartBuild(dayChart, '${timeChart.bazi.time}',
        lunarBase: vtDate);
  }
  // 开早晚子时、00:00–23:00（含早子时）：子时归自然日（todayGan），
  // 日柱 / 农历锚定「校正后（真太阳时）当日」。
  // 早子时（00:00–01:00）本就属「当日」之子时，**不再顺延次日**（顺延会重复 +1 天）。
  final chart = bazi.BaziChart.createBySolarDate(
    clockTime: astro,
    location: loc,
    ratHourMode: RatHourMode.todayGan,
    useTrueSolarTime: useTrueSolarTime,
    gender: gender,
  );
  final vt = chart.time.virtualTime;
  return _BaziChartBuild(chart, null, lunarBase: DateTime(vt.year, vt.month, vt.day));
}

/// 计算八字排盘。
///
/// [solar] 为公历生辰（含时辰）；[isMale] 性别（不影响四柱，仅为构造所需）；
/// [useTrueSolarTime] 真太阳时开关，与命盘页语义一致；
/// [location] 出生地经纬度：`null` 时按 bazi_core 默认 `Location(120, 30)`（东经 120°）。
/// 为保证时柱正确，调用方应显式传入真实出生地。
/// [twelveStageMode] 长生十二神口径（火土同宫 / 水土同宫）；
/// [ratHourMode] 区分早晚子时开关（含义同设置项「区分早晚子时」）：
///   - `false`（默认）：不区分，23:00–01:00 全部「子时归自然日」——日柱取当天、时柱取当日子时，不做偏移。
///   - `true`：区分早晚子时，日柱 / 农历一律锚定「校正后（真太阳时）当日」：
///     · 晚子时（23:00 ≤ t < 24:00）：日柱取校正后当日，时柱取**次日**子时干支；
///     · 早子时（00:00 ≤ t < 01:00）：日柱取校正后当日（**不顺延次日**），时柱取当日子时干支；
///     · 01:00–23:00：日柱取校正后当日，开关与 `false` 在无跨日校正时结果一致。
/// 四柱由 [bazi.BaziChart.createBySolarDate] 算出（与大运同源），再包装为 [ZiweiBaZi]。
BaZiPaipan computeBaZiPaipan(
  DateTime solar, {
  bool isMale = true,
  bool useTrueSolarTime = true,
  TwelveStageMode twelveStageMode = TwelveStageMode.fireEarthSame,
  bool ratHourMode = false,
  Location? location,
}) {
  final built = _buildBaziChart(
    solar,
    ratHourMode,
    location,
    useTrueSolarTime,
    isMale ? Gender.male : Gender.female,
  );
  final four = built.chart.bazi;
  // 包装为 ZiweiBaZi（仍为 String 四柱），保持下游屏 / 组件读取方式不变。
  // 晚子时需要把「当日日柱」与「次日子时」混合，故时柱以 timeOverride 覆盖。
  final ziweiBazi = ZiweiBaZi(
    year: '${four.year}',
    month: '${four.month}',
    day: '${four.day}',
    time: built.timeOverride ?? '${four.time}',
  );
  final gans = <String>[
    _splitGanZhi(ziweiBazi.year).$1,
    _splitGanZhi(ziweiBazi.month).$1,
    _splitGanZhi(ziweiBazi.day).$1,
    _splitGanZhi(ziweiBazi.time).$1,
  ];
  final zhis = <String>[
    _splitGanZhi(ziweiBazi.year).$2,
    _splitGanZhi(ziweiBazi.month).$2,
    _splitGanZhi(ziweiBazi.day).$2,
    _splitGanZhi(ziweiBazi.time).$2,
  ];
  final dayGan = gans[2];
  final tenGods = tenGodsPerPillar(dayGan, gans);
  final kong = kongWang(dayGan, zhis[2]);
  final relations = detectBaziRelations(zhis);
  final stages = twelveStagesForPillars(dayGan, zhis, mode: twelveStageMode);
  final analysis = analyzeBaZi(gans: gans, zhis: zhis);
  // 出生农历：取基准日正午，与黄历 / 紫微排盘同口径（见 lunar_almanac_service / ziwei_engine）。
  // 基准日：开早晚子时 = 校正后（真太阳时）当日（built.lunarBase）；不开 = 出生日。
  final lunarBase = built.lunarBase ?? solar;
  final lunar = LunarDate.fromSolar(
    AstroDateTime(lunarBase.year, lunarBase.month, lunarBase.day, 12, 0, 0),
  );
  final lunarText =
      '${_lunarYearGanZhi(lunar.lunarYear)}（${lunarBase.year}）年${lunar.monthNameStr}月${lunar.dayName}';
  return BaZiPaipan(
    bazi: ziweiBazi,
    gans: gans,
    zhis: zhis,
    tenGods: tenGods,
    kongWang: kong,
    relations: relations,
    twelveStages: stages,
    analysis: analysis,
    taiYuan: taiYuanOf(ziweiBazi.month),
    taiXi: taiXiOf(ziweiBazi.day),
    kongWangPillars:
        kongWangPerPillar([ziweiBazi.year, ziweiBazi.month, ziweiBazi.day, ziweiBazi.time]),
    selfStages: selfTwelveStages(gans, zhis),
    hiddenTenGods: hiddenTenGods(gans, zhis),
    lunarText: lunarText,
  );
}

// ---- 大运 / 流年（编排 bazi_core Fortune，UI 不直接依赖引擎类型）----

/// 单步大运（含其辖下 10 流年）的轻量视图。
class BaZiDecade {
  final int index; // 第几步大运（1 起）
  final String ganZhi; // 大运干支（如 癸未）
  final int startAge; // 起步虚岁
  final int endAge; // 结束虚岁
  final List<({int year, String ganZhi})> years; // 10 个流年

  const BaZiDecade({
    required this.index,
    required this.ganZhi,
    required this.startAge,
    required this.endAge,
    required this.years,
  });
}

/// 大运 / 流年汇总。
class BaZiFortune {
  final double startAge; // 起运虚岁（精确值，如 2.72）
  final DateTime qiYunTime; // 精确交运钟表时间
  final List<BaZiDecade> decades; // N 步大运

  const BaZiFortune({
    required this.startAge,
    required this.qiYunTime,
    required this.decades,
  });
}

/// 计算大运 / 流年。
///
/// 顺逆由 bazi_core 按传统规则内部判定：方向 = 年干阴阳 × 性别
/// （阳男阴女顺排、阴男阳女逆排，见 bazi_core fortune.dart）。
/// [location] 语义同 [computeBaZiPaipan]；`null` 时按默认东经 120°。
/// [ratHourMode] 区分早晚子时开关，与四柱排盘保持一致（含义见 [computeBaZiPaipan]）。
/// [useTrueSolarTime] 真太阳时校准开关，与四柱排盘保持一致
/// （含义见 [computeBaZiPaipan]）；默认 true，不影响既有调用点行为。
BaZiFortune computeBaZiFortune(
  DateTime solar, {
  required bool isMale,
  Location? location,
  bool ratHourMode = false,
  bool useTrueSolarTime = true,
  int decadeCount = 8,
}) {
  final chart = _buildBaziChart(
    solar,
    ratHourMode,
    location,
    useTrueSolarTime,
    isMale ? Gender.male : Gender.female,
  ).chart;
  final fortune = bazi.Fortune.createByBaziChart(chart);
  final decades = <BaZiDecade>[];
  for (var i = 1; i <= decadeCount; i++) {
    final d = fortune.getDecadeByIndex(i);
    decades.add(BaZiDecade(
      index: d.index,
      ganZhi: '${d.ganZhi}',
      startAge: d.startAge,
      endAge: d.endAge,
      years: [
        for (final y in d.flowYears) (year: y.year, ganZhi: '${y.ganZhi}'),
      ],
    ));
  }
  final qt = fortune.qiYunTime;
  return BaZiFortune(
    startAge: fortune.startAge,
    qiYunTime: DateTime(qt.year, qt.month, qt.day, qt.hour, qt.minute),
    decades: decades,
  );
}

/// 四柱纳音（如 甲子 → 海中金）。基于 sxwnl 权威纳音表，无需自维护。
String nayinOfPillar(String ganzhi) {
  assert(ganzhi.length == 2);
  final gz = bazi.GanZhi(
    bazi.TianGan.fromName(ganzhi.substring(0, 1)),
    bazi.DiZhi.fromName(ganzhi.substring(1, 2)),
  );
  return gz.naYin;
}
