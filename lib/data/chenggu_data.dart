import 'dart:convert';

import 'package:flutter/services.dart' show rootBundle;

/// 袁天罡称骨算命结果。
class ChengguResult {
  final int totalQian; // 总骨重（钱，1两=10钱）
  final int liang;
  final int qian;
  final String title;
  final String poem;
  final String detail;

  const ChengguResult({
    required this.totalQian,
    required this.liang,
    required this.qian,
    required this.title,
    required this.poem,
    required this.detail,
  });
}

/// 袁天罡称骨算法数据访问层。
///
/// 数据源：assets/data/chenggu_bone_weight.json
/// （结构：根级两个并列对象：
///  tables.{year[干支],month[1-12],day[1-30],hour[时辰名]} 各含 qian；
///  poems["X_Y"] 为 tables 的同级兄弟，含 title/poem/detail）。
class ChengguData {
  static Map<String, dynamic>? _raw;
  static String disclaimer = '';

  static Future<void> ensureLoaded() async {
    if (_raw != null) return;
    final s =
        await rootBundle.loadString('assets/data/chenggu_bone_weight.json');
    _raw = jsonDecode(s) as Map<String, dynamic>;
    disclaimer = (_raw!['meta']?['disclaimer'] as String?) ?? '';
  }

  /// [yearGanZhi] 农历年干支（如 "甲子"）
  /// [lunarMonth] 农历月（1-12）
  /// [lunarDay] 农历日（1-30）
  /// [shiChen] 时辰名（子丑寅…亥）
  static ChengguResult compute(
    String yearGanZhi,
    int lunarMonth,
    int lunarDay,
    String shiChen,
  ) {
    final tables = _raw!['tables'] as Map<String, dynamic>? ?? {};
    final y = (tables['year']?[yearGanZhi]?['qian'] as num?)?.toInt() ??
        (throw ArgumentError('称骨：缺少年柱数据（$yearGanZhi）'));
    final m = (tables['month']?['$lunarMonth']?['qian'] as num?)?.toInt() ??
        (throw ArgumentError('称骨：缺少月数据（$lunarMonth）'));
    final d = (tables['day']?['$lunarDay']?['qian'] as num?)?.toInt() ??
        (throw ArgumentError('称骨：缺少日数据（$lunarDay）'));
    final h = (tables['hour']?[shiChen]?['qian'] as num?)?.toInt() ??
        (throw ArgumentError('称骨：缺少时辰数据（$shiChen）'));
    final total = y + m + d + h;
    final key = '${total ~/ 10}_${total % 10}';
    final poems = _raw!['poems'] as Map<String, dynamic>? ?? {};
    final poem = poems[key] as Map<String, dynamic>? ??
        (throw ArgumentError('称骨：缺少骨重诗（$key 钱）'));
    return ChengguResult(
      totalQian: total,
      liang: total ~/ 10,
      qian: total % 10,
      title: poem['title'] as String,
      poem: poem['poem'] as String,
      detail: (poem['detail'] as String?) ?? '',
    );
  }
}
