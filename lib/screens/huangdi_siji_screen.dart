import 'package:flutter/material.dart';
import 'package:sxwnl_spa_dart/sxwnl_spa_dart.dart' show LunarDate, AstroDateTime;

import 'package:nihaisha_app/data/huangdi_siji_data.dart';

/// 轩辕黄帝四季歌（四季诗）查询。
///
/// 输入公历生辰 → 取农历月定季节、按时辰定黄帝身体部位 → 查部位诗与释义断一生运程。
/// 属民间通胜简易命法（托名轩辕黄帝），民俗文化参考，非科学或医学定论。
class HuangdiSijiScreen extends StatefulWidget {
  const HuangdiSijiScreen({super.key});

  @override
  State<HuangdiSijiScreen> createState() => _HuangdiSijiScreenState();
}

class _HuangdiSijiScreenState extends State<HuangdiSijiScreen> {
  static const List<String> _shiChenNames = [
    '子', '丑', '寅', '卯', '辰', '巳', '午', '未', '申', '酉', '戌', '亥'
  ];

  static const Map<String, IconData> _partIcons = {
    '头': Icons.face,
    '手': Icons.back_hand,
    '肩': Icons.accessibility_new,
    '腹': Icons.cookie,
    '腰': Icons.accessibility,
    '膝': Icons.directions_walk,
    '足': Icons.directions_walk,
  };

  DateTime _birthDate = DateTime(1990, 1, 1);
  int _birthHour = 12;
  int _birthMinute = 0;
  bool _ready = false;

  HuangdiPart? _result;
  String? _season;
  String? _shiChen;
  String? _error;

  @override
  void initState() {
    super.initState();
    HuangdiSijiData.ensureLoaded().then((_) {
      if (mounted) setState(() => _ready = true);
    });
  }

  int _shiChenIndexOfHour(int hour) => ((hour.clamp(0, 23) + 1) ~/ 2) % 12;

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _birthDate,
      firstDate: DateTime(1920),
      lastDate: DateTime.now(),
    );
    if (picked != null) setState(() => _birthDate = picked);
  }

  void _compute() {
    setState(() {
      _result = null;
      _season = null;
      _shiChen = null;
      _error = null;
    });
    try {
      // 农历月（与命理屏一致：取出生日正午）
      final lunar = LunarDate.fromSolar(AstroDateTime(
          _birthDate.year, _birthDate.month, _birthDate.day, 12, 0, 0));
      final lunarMonth = lunar.month;
      final shiChen = _shiChenNames[_shiChenIndexOfHour(_birthHour)];
      if (!mounted) return;
      setState(() {
        _result = HuangdiSijiData.compute(lunarMonth, shiChen);
        _season = HuangdiSijiData.seasonOfLunarMonth(lunarMonth);
        _shiChen = shiChen;
      });
    } catch (e) {
      if (mounted) setState(() => _error = '计算失败：$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    if (!_ready) {
      return Scaffold(
        appBar: AppBar(title: const Text('轩辕黄帝四季歌')),
        body: const Center(child: CircularProgressIndicator()),
      );
    }
    final shiChen = _shiChenNames[_shiChenIndexOfHour(_birthHour)];
    return Scaffold(
      appBar: AppBar(title: const Text('轩辕黄帝四季歌')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _disclaimer(HuangdiSijiData.disclaimer, cs),
          const SizedBox(height: 12),
          Card(
            clipBehavior: Clip.antiAlias,
            child: ExpansionTile(
              leading: Icon(Icons.image_outlined, color: cs.primary),
              title: const Text(
                '查看四季时辰定位图',
                style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
              ),
              subtitle: const Text(
                '春/夏/秋/冬 · 十二时辰与黄帝身体部位对应',
                style: TextStyle(fontSize: 11),
              ),
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(12),
                    child: Image.asset(
                      'assets/images/huangdi_siji_diagram.jpg',
                      fit: BoxFit.contain,
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  OutlinedButton.icon(
                    onPressed: _pickDate,
                    icon: const Icon(Icons.calendar_today, size: 18),
                    label: Text(
                      '${_birthDate.year}-${_birthDate.month.toString().padLeft(2, '0')}'
                      '-${_birthDate.day.toString().padLeft(2, '0')}',
                    ),
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Expanded(
                          child: _intDropdown('时', _birthHour, 0, 23,
                              (v) => setState(() => _birthHour = v), (h) => '$h')),
                      const SizedBox(width: 8),
                      Expanded(
                          child: _intDropdown(
                              '分',
                              _birthMinute,
                              0,
                              59,
                              (v) => setState(() => _birthMinute = v),
                              (m) => m.toString().padLeft(2, '0'))),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Wrap(
                    spacing: 6,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      _chip('时辰', '$shiChen 时',
                          Theme.of(context).colorScheme.primary),
                    ],
                  ),
                  const SizedBox(height: 8),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: _compute,
                      icon: const Icon(Icons.auto_awesome),
                      label: const Text('查询四季歌'),
                    ),
                  ),
                ],
              ),
            ),
          ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(_error!, style: TextStyle(color: cs.error)),
            ),
          if (_result != null) ...[
            const SizedBox(height: 12),
            Card(
              color: cs.primaryContainer,
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  children: [
                    Icon(
                      _partIcons[_result!.part] ?? Icons.accessibility_new,
                      size: 40,
                      color: cs.onPrimaryContainer,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      '生在黄帝${_result!.part}',
                      style: TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.bold,
                        color: cs.onPrimaryContainer,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      _result!.title,
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        color: cs.onPrimaryContainer.withValues(alpha: 0.85),
                      ),
                    ),
                    if (_season != null && _shiChen != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 6),
                        child: Text(
                          '（$_season · $_shiChen时）',
                          style: TextStyle(
                            fontSize: 12,
                            color:
                                cs.onPrimaryContainer.withValues(alpha: 0.7),
                          ),
                        ),
                      ),
                    const SizedBox(height: 12),
                    Text(
                      _result!.poem,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 14,
                        height: 1.6,
                        color: cs.onPrimaryContainer,
                      ),
                    ),
                    if (_result!.interpretation.isNotEmpty) ...[
                      const SizedBox(height: 12),
                      Text(
                        _result!.interpretation,
                        textAlign: TextAlign.left,
                        style: TextStyle(
                          fontSize: 12,
                          height: 1.6,
                          color:
                              cs.onPrimaryContainer.withValues(alpha: 0.8),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _intDropdown(
    String label,
    int value,
    int min,
    int max,
    ValueChanged<int> onChanged,
    String Function(int) text,
  ) {
    return InputDecorator(
      decoration: InputDecoration(labelText: label, isDense: true),
      child: DropdownButton<int>(
        value: value,
        isExpanded: true,
        underline: const SizedBox.shrink(),
        items: [
          for (int i = min; i <= max; i++)
            DropdownMenuItem(value: i, child: Text(text(i))),
        ],
        onChanged: (v) => onChanged(v ?? value),
      ),
    );
  }

  Widget _chip(String label, String value, Color color) {
    return Chip(
      label: Text('$label：$value', style: const TextStyle(fontSize: 12)),
      backgroundColor: color.withValues(alpha: 0.12),
      visualDensity: VisualDensity.compact,
    );
  }

  Widget _disclaimer(String text, ColorScheme cs) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.info_outline, size: 16, color: cs.onSurfaceVariant),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              text,
              style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
            ),
          ),
        ],
      ),
    );
  }
}
