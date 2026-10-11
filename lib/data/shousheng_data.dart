import 'dart:convert';

import 'package:flutter/services.dart' show rootBundle;

/// 禄库受生经查询结果（生肖 → 库曹官 + 本命元辰）。
class LukuResult {
  final Map<String, dynamic> warehouse; // twelve_warehouses 条目
  final Map<String, dynamic> yuanchen; // twelve_yuanchen 条目
  const LukuResult({required this.warehouse, required this.yuanchen});
}

/// 五斗金章受生经查询结果（天干 → 五斗 + 地支 → 库神）。
class WudouResult {
  final Map<String, dynamic> fiveDou; // five_dou 条目
  final Map<String, dynamic> kushen; // twelve_kushen 条目
  const WudouResult({required this.fiveDou, required this.kushen});
}

/// 受生债查询数据访问层（禄库受生经 + 五斗金章受生经）。
///
/// 数据源：
/// - assets/data/luku_shousheng_debt.json（灵宝天尊说禄库受生经）
/// - assets/data/wudou_shousheng_debt.json（太上老君说五斗金章受生经）
class ShoushengData {
  static Map<String, dynamic>? _luku;
  static Map<String, dynamic>? _wudou;
  static String lukuDisclaimer = '';
  static String wudouDisclaimer = '';
  static String wudouNote = ''; // 天干/地支口径说明（经文 vs 民间）

  static Future<void> ensureLoaded() async {
    if (_luku != null && _wudou != null) return;
    final l =
        await rootBundle.loadString('assets/data/luku_shousheng_debt.json');
    final w =
        await rootBundle.loadString('assets/data/wudou_shousheng_debt.json');
    _luku = jsonDecode(l) as Map<String, dynamic>;
    _wudou = jsonDecode(w) as Map<String, dynamic>;
    lukuDisclaimer = (_luku!['meta']?['disclaimer'] as String?) ?? '';
    wudouDisclaimer = (_wudou!['meta']?['disclaimer'] as String?) ?? '';
    final notes = _wudou!['rules']?['interpretation_notes'];
    if (notes is List) {
      wudouNote = notes.whereType<String>().join('\n');
    }
  }

  /// 禄库受生经：按出生年地支（生肖）查询。
  static LukuResult computeLuku(String yearZhi) {
    final tables = _luku!['tables'] as Map<String, dynamic>? ?? {};
    final warehouses = tables['twelve_warehouses'] as Map<String, dynamic>? ?? {};
    final yuanchen = tables['twelve_yuanchen'] as Map<String, dynamic>? ?? {};
    return LukuResult(
      warehouse: warehouses[yearZhi] as Map<String, dynamic>? ??
          (throw ArgumentError('受生债：生肖$yearZhi 无禄库数据')),
      yuanchen: yuanchen[yearZhi] as Map<String, dynamic>? ??
          (throw ArgumentError('受生债：生肖$yearZhi 无元辰数据')),
    );
  }

  /// 五斗金章受生经（经文原文口径）：天干=出生日天干分组，地支=出生时辰地支。
  static WudouResult computeWudou(String dayGan, String timeZhi) {
    final tables = _wudou!['tables'] as Map<String, dynamic>? ?? {};
    final group = _stemGroup(dayGan);
    final fiveDou = tables['five_dou'] as Map<String, dynamic>? ?? {};
    final kushen = tables['twelve_kushen'] as Map<String, dynamic>? ?? {};
    return WudouResult(
      fiveDou: fiveDou[group] as Map<String, dynamic>? ??
          (throw ArgumentError('受生债：天干组$group 无五斗数据')),
      kushen: kushen[timeZhi] as Map<String, dynamic>? ??
          (throw ArgumentError('受生债：时支$timeZhi 无库神数据')),
    );
  }

  static String _stemGroup(String gan) {
    if (gan == '甲' || gan == '乙') return '甲乙';
    if (gan == '丙' || gan == '丁') return '丙丁';
    if (gan == '戊' || gan == '己') return '戊己';
    if (gan == '庚' || gan == '辛') return '庚辛';
    return '壬癸'; // 壬 or 癸
  }
}
