import 'dart:convert';

import 'package:flutter/services.dart' show rootBundle;

/// 轩辕黄帝四季歌查询结果（黄帝身体部位 + 命格诗句 + 释义）。
class HuangdiPart {
  final String part; // 头/手/肩/腹/腰/膝/足
  final String title; // 命格标题（如「衣食无忧」）
  final String poem; // 部位诗
  final String interpretation; // 释义

  const HuangdiPart({
    required this.part,
    required this.title,
    required this.poem,
    required this.interpretation,
  });
}

/// 轩辕黄帝四季歌（四季诗）查询数据访问层。
///
/// 口径：按农历月份定季节（春=正月~三月，夏=四~六月，秋=七~九月，冬=十~腊月），
/// 按时辰在季节定位表中查「黄帝」身体部位，再查部位诗断一生运程。
/// 数据源：assets/data/huangdi_siji_ge.json
/// （民间通胜简易命法，托名轩辕黄帝，属民俗文化参考）
class HuangdiSijiData {
  static Map<String, dynamic>? _raw;
  static String disclaimer = '';
  static String seasonBasisNote = ''; // 农历月份 / 阳历节气 两套口径说明

  static Future<void> ensureLoaded() async {
    if (_raw != null) return;
    final s =
        await rootBundle.loadString('assets/data/huangdi_siji_ge.json');
    _raw = jsonDecode(s) as Map<String, dynamic>;
    disclaimer = (_raw!['meta']?['disclaimer'] as String?) ?? '';
    seasonBasisNote =
        (_raw!['rules']?['season_boundary_note'] as String?) ?? '';
  }

  /// 农历月(1-12) → 季节。
  static String seasonOfLunarMonth(int lunarMonth) {
    if (lunarMonth >= 1 && lunarMonth <= 3) return '春';
    if (lunarMonth >= 4 && lunarMonth <= 6) return '夏';
    if (lunarMonth >= 7 && lunarMonth <= 9) return '秋';
    return '冬';
  }

  /// [lunarMonth] 农历月（1-12），[shiChen] 时辰名（子丑寅…亥）。
  static HuangdiPart compute(int lunarMonth, String shiChen) {
    final tables = _raw!['tables'] as Map<String, dynamic>? ?? {};
    final lookup = tables['part_lookup'] as Map<String, dynamic>? ?? {};
    final poems = tables['part_poems'] as Map<String, dynamic>? ?? {};
    final season = seasonOfLunarMonth(lunarMonth);
    final part = lookup[season]?[shiChen] as String? ??
        (throw ArgumentError('黄帝四季歌：季节$season 时辰$shiChen 无对应部位'));
    final p = poems[part] as Map<String, dynamic>? ??
        (throw ArgumentError('黄帝四季歌：部位$part 无诗句数据'));
    return HuangdiPart(
      part: p['part'] as String? ?? part,
      title: p['title'] as String? ?? '',
      poem: p['poem'] as String? ?? '',
      interpretation: (p['interpretation'] as String?) ?? '',
    );
  }
}
