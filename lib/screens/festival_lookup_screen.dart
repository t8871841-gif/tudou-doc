import 'package:flutter/material.dart';
import 'package:sxwnl_spa_dart/sxwnl_spa_dart.dart';

import '../data/chaoshan_festival_data.dart';
import '../data/yuxiaji_deity_data.dart';
import '../widgets/state_view.dart';

/// 农历节日 / 神诞检索页。
///
/// 把「潮汕节俗」与「《玉匣记》神仙节日」两张**农历表**合成一屏，
/// 支持按名称搜索，并反推当年公历日期（`LunarDate.fromString(..).toSolar`）。
/// 结果属民俗文化参考，非行事指令。
class FestivalLookupScreen extends StatefulWidget {
  const FestivalLookupScreen({super.key});

  @override
  State<FestivalLookupScreen> createState() => _FestivalLookupScreenState();
}

/// 一条节日（同一农历月日下、两个来源的名称合并展示）。
class FestivalEntry {
  final int month; // 农历月 1-12
  final int day; // 农历日 1-30
  final List<String> names;
  final bool chaoshan;
  final bool deity;

  const FestivalEntry({
    required this.month,
    required this.day,
    required this.names,
    required this.chaoshan,
    required this.deity,
  });

  String get lunarLabel => '${_cnMonths[month]}月${lunarDayName(day)}';

  String get sourceLabel =>
      chaoshan && deity ? '潮汕 · 玉匣记' : (chaoshan ? '潮汕' : '玉匣记');
}

const List<String> _cnMonths = [
  '', '正', '二', '三', '四', '五', '六', '七', '八', '九', '十', '十一', '十二',
];

/// 农历日 → 中文（初一…三十）。纯函数，便于单测。
String lunarDayName(int day) => const [
      '初一', '初二', '初三', '初四', '初五', '初六', '初七', '初八', '初九', '初十',
      '十一', '十二', '十三', '十四', '十五', '十六', '十七', '十八', '十九', '二十',
      '廿一', '廿二', '廿三', '廿四', '廿五', '廿六', '廿七', '廿八', '廿九', '三十',
    ][day - 1];

/// 合并两张农历表 → 条目列表（按农历月日排序）。纯函数，便于单测。
List<FestivalEntry> buildFestivalEntries() {
  final keys = <String>{
    ...kChaoshanFestivals.keys,
    ...kYuxiajiDeityFestivals.keys,
  };
  final out = <FestivalEntry>[];
  for (final k in keys) {
    if (k.length != 4) continue;
    final m = int.tryParse(k.substring(0, 2));
    final d = int.tryParse(k.substring(2));
    if (m == null || d == null || m < 1 || m > 12 || d < 1 || d > 30) continue;
    final cs = kChaoshanFestivals[k] ?? const <String>[];
    final yx = kYuxiajiDeityFestivals[k] ?? const <String>[];
    final names = <String>{...cs, ...yx}.toList();
    if (names.isEmpty) continue;
    out.add(FestivalEntry(
      month: m,
      day: d,
      names: names,
      chaoshan: cs.isNotEmpty,
      deity: yx.isNotEmpty,
    ));
  }
  out.sort((a, b) => a.month != b.month ? a.month - b.month : a.day - b.day);
  return out;
}

/// 按关键字过滤（匹配名称、农历月日名、来源）。纯函数，便于单测。
List<FestivalEntry> filterFestivalEntries(
  List<FestivalEntry> all,
  String query,
) {
  final q = query.trim();
  if (q.isEmpty) return all;
  return all
      .where((e) =>
          e.names.any((n) => n.contains(q)) ||
          e.lunarLabel.contains(q) ||
          e.sourceLabel.contains(q))
      .toList();
}

/// 农历月日 → 当年公历；该农历年若无此日（如 29 天月里的「三十」）返回 null。
DateTime? lunarToSolar(int month, int day, int year) {
  if (month < 1 || month > 12 || day < 1 || day > 30) return null;
  try {
    final s = LunarDate.fromString(year, _cnMonths[month], day).toSolar;
    return DateTime(s.year, s.month, s.day);
  } catch (_) {
    // 该农历年此月无此日（廿九/三十），或历法窗口不支持。
    return null;
  }
}

class _FestivalLookupScreenState extends State<FestivalLookupScreen> {
  final TextEditingController _ctrl = TextEditingController();
  final List<FestivalEntry> _all = buildFestivalEntries();
  String _query = '';
  final int _year = DateTime.now().year;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final list = filterFestivalEntries(_all, _query);
    return Scaffold(
      appBar: AppBar(title: const Text('农历节日 · 神诞')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 6),
            child: TextField(
              controller: _ctrl,
              onChanged: (v) => setState(() => _query = v),
              decoration: InputDecoration(
                prefixIcon: const Icon(Icons.search),
                hintText: '搜神诞 / 节俗，如：妈祖、天公、双忠',
                isDense: true,
                border: const OutlineInputBorder(),
                suffixIcon: _query.isEmpty
                    ? null
                    : IconButton(
                        tooltip: '清空',
                        icon: const Icon(Icons.clear),
                        onPressed: () {
                          _ctrl.clear();
                          setState(() => _query = '');
                        },
                      ),
              ),
            ),
          ),
          Align(
            alignment: Alignment.centerLeft,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Text(
                '共 ${list.length} 条 · 公历按 $_year 年档推算',
                style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
              ),
            ),
          ),
          Expanded(
            child: list.isEmpty
                ? StateView.empty(title: '未找到「$_query」相关的神诞 / 节俗')
                : ListView.builder(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                    itemCount: list.length,
                    itemBuilder: (_, i) => _tile(context, list[i]),
                  ),
          ),
        ],
      ),
    );
  }

  Widget _tile(BuildContext context, FestivalEntry e) {
    final cs = Theme.of(context).colorScheme;
    final solar = lunarToSolar(e.month, e.day, _year);
    return Card(
      elevation: 1,
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text(
                  e.lunarLabel,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.bold,
                    color: cs.primary,
                  ),
                ),
                const SizedBox(width: 8),
                Text(
                  e.sourceLabel,
                  style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
                ),
                const Spacer(),
                Text(
                  solar == null ? '当年无此日' : '公历 ${solar.month}月${solar.day}日',
                  style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              e.names.join(' · '),
              style: const TextStyle(fontSize: 14, height: 1.4),
            ),
          ],
        ),
      ),
    );
  }
}
