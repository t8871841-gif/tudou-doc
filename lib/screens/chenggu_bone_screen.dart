import 'package:flutter/material.dart';
import 'package:sxwnl_spa_dart/sxwnl_spa_dart.dart' show LunarDate, AstroDateTime;

import 'package:nihaisha_app/data/chenggu_data.dart';
import 'package:nihaisha_app/data/settings_repository.dart';
import 'package:nihaisha_app/services/bazi_service.dart' show computeBaZiPaipan;

/// 袁天罡称骨算命。
///
/// 输入公历生辰 → 复用八字引擎算四柱（年/月/日/时干支）→ 取农历年干支、农历月日数、
/// 时辰名 → 按骨重求和查称骨歌诀。属传统民俗文化参考，非医学或科学定论。
class ChengguBoneScreen extends StatefulWidget {
  const ChengguBoneScreen({super.key});

  @override
  State<ChengguBoneScreen> createState() => _ChengguBoneScreenState();
}

class _ChengguBoneScreenState extends State<ChengguBoneScreen> {
  static const List<String> _shiChenNames = [
    '子', '丑', '寅', '卯', '辰', '巳', '午', '未', '申', '酉', '戌', '亥'
  ];

  DateTime _birthDate = DateTime(1990, 1, 1);
  int _birthHour = 12;
  int _birthMinute = 0;
  bool _useTrueSolarTime = true;
  bool _distinguishZiShi = false;
  bool _ready = false;

  ChengguResult? _result;
  String? _error;

  @override
  void initState() {
    super.initState();
    _useTrueSolarTime = SettingsRepository.instance.useTrueSolarTime;
    _distinguishZiShi = SettingsRepository.instance.distinguishZiShiEnabled;
    ChengguData.ensureLoaded().then((_) {
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
      _error = null;
    });
    try {
      final solar = DateTime(_birthDate.year, _birthDate.month, _birthDate.day,
          _birthHour, _birthMinute);
      final p = computeBaZiPaipan(
        solar,
        useTrueSolarTime: _useTrueSolarTime,
        ratHourMode: _distinguishZiShi,
      );
      final yearGz = p.gans[0] + p.zhis[0]; // 甲子
      final timeZhi = p.zhis[3]; // 时辰地支：子丑…
      // 农历月/日数（与八字农历口径一致：取出生日正午）
      final lunar = LunarDate.fromSolar(AstroDateTime(
          _birthDate.year, _birthDate.month, _birthDate.day, 12, 0, 0));
      final lunarMonth = lunar.month;
      final lunarDay = lunar.day;
      if (!mounted) return;
      setState(() {
        _result =
            ChengguData.compute(yearGz, lunarMonth, lunarDay, timeZhi);
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
        appBar: AppBar(title: const Text('袁天罡称骨算命')),
        body: const Center(child: CircularProgressIndicator()),
      );
    }
    final shiChen = _shiChenNames[_shiChenIndexOfHour(_birthHour)];
    return Scaffold(
      appBar: AppBar(title: const Text('袁天罡称骨算命')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _disclaimer(ChengguData.disclaimer, cs),
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
                      Expanded(child: _intDropdown('时', _birthHour, 0, 23,
                          (v) => setState(() => _birthHour = v), (h) {
                        if (h == 23) {
                          return _distinguishZiShi ? '23(晚子时)' : '23(子时)';
                        }
                        return '$h';
                      })),
                      const SizedBox(width: 8),
                      Expanded(child: _intDropdown('分', _birthMinute, 0, 59,
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
                  SwitchListTile(
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    title: const Text('真太阳时校准', style: TextStyle(fontSize: 12)),
                    subtitle: const Text('按出生地经度校正平太阳时时差',
                        style: TextStyle(fontSize: 10)),
                    value: _useTrueSolarTime,
                    onChanged: (v) {
                      setState(() => _useTrueSolarTime = v);
                      SettingsRepository.instance.setUseTrueSolarTime(v);
                    },
                  ),
                  SwitchListTile(
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    title:
                        const Text('区分早晚子时', style: TextStyle(fontSize: 12)),
                    subtitle: const Text('默认关：子时归自然日', style: TextStyle(fontSize: 10)),
                    value: _distinguishZiShi,
                    onChanged: (v) {
                      setState(() => _distinguishZiShi = v);
                      SettingsRepository.instance.setDistinguishZiShiEnabled(v);
                    },
                  ),
                  const SizedBox(height: 8),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: _compute,
                      icon: const Icon(Icons.auto_awesome),
                      label: const Text('称骨测算'),
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
                    Text(
                      '${_result!.liang}两${_result!.qian}钱',
                      style: TextStyle(
                        fontSize: 32,
                        fontWeight: FontWeight.bold,
                        color: cs.onPrimaryContainer,
                      ),
                    ),
                    const SizedBox(height: 8),
                    if (_result!.title.isNotEmpty)
                      Text(
                        _result!.title,
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                          color: cs.onPrimaryContainer,
                        ),
                      ),
                    if (_result!.title.isNotEmpty) const SizedBox(height: 12),
                    Text(
                      _result!.poem,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 14,
                        height: 1.6,
                        color: cs.onPrimaryContainer,
                      ),
                    ),
                    if (_result!.detail.isNotEmpty) ...[
                      const SizedBox(height: 12),
                      Text(
                        _result!.detail,
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          fontSize: 12,
                          color: cs.onPrimaryContainer.withValues(alpha: 0.8),
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
