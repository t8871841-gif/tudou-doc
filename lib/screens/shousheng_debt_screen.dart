import 'package:flutter/material.dart';

import 'package:nihaisha_app/data/settings_repository.dart';
import 'package:nihaisha_app/data/shousheng_data.dart';
import 'package:nihaisha_app/services/bazi_service.dart' show computeBaZiPaipan;

/// 受生债查询（禄库受生经 / 五斗金章受生经）。
///
/// 禄库：按出生年地支（生肖）查十二库曹官 + 本命元辰。
/// 五斗金章：按经文口径（出生日天干分组 → 五斗；出生时辰地支 → 库神）。
/// 均属道教文献 / 民俗文化参考，非科学或宗教实践指导。
class ShoushengDebtScreen extends StatefulWidget {
  const ShoushengDebtScreen({super.key});

  @override
  State<ShoushengDebtScreen> createState() => _ShoushengDebtScreenState();
}

class _ShoushengDebtScreenState extends State<ShoushengDebtScreen> {
  DateTime _birthDate = DateTime(1990, 1, 1);
  int _birthHour = 12;
  int _birthMinute = 0;
  bool _useTrueSolarTime = true;
  bool _distinguishZiShi = false;
  bool _ready = false;
  bool _isLuku = true; // true=禄库受生经，false=五斗金章受生经

  LukuResult? _luku;
  WudouResult? _wudou;
  String? _error;

  @override
  void initState() {
    super.initState();
    _useTrueSolarTime = SettingsRepository.instance.useTrueSolarTime;
    _distinguishZiShi = SettingsRepository.instance.distinguishZiShiEnabled;
    ShoushengData.ensureLoaded().then((_) {
      if (mounted) setState(() => _ready = true);
    });
  }

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
      _luku = null;
      _wudou = null;
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
      final yearZhi = p.zhis[0]; // 年地支（生肖）
      final dayGan = p.gans[2]; // 日天干
      final timeZhi = p.zhis[3]; // 时辰地支
      if (!mounted) return;
      setState(() {
        if (_isLuku) {
          _luku = ShoushengData.computeLuku(yearZhi);
        } else {
          _wudou = ShoushengData.computeWudou(dayGan, timeZhi);
        }
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
        appBar: AppBar(title: const Text('受生债查询')),
        body: const Center(child: CircularProgressIndicator()),
      );
    }
    return Scaffold(
      appBar: AppBar(title: const Text('受生债查询')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _disclaimer(
              _isLuku
                  ? ShoushengData.lukuDisclaimer
                  : ShoushengData.wudouDisclaimer,
              cs),
          const SizedBox(height: 12),
          SegmentedButton<bool>(
            segments: const [
              ButtonSegment(value: true, label: Text('禄库受生经')),
              ButtonSegment(value: false, label: Text('五斗金章受生经')),
            ],
            selected: {_isLuku},
            onSelectionChanged: (s) =>
                setState(() => _isLuku = s.first),
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
                  const SizedBox(height: 8),
                  SwitchListTile(
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    title: const Text('真太阳时校准', style: TextStyle(fontSize: 12)),
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
                      label: const Text('查询受生债'),
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
          if (_luku != null) _buildLuku(cs),
          if (_wudou != null) _buildWudou(cs),
        ],
      ),
    );
  }

  Widget _buildLuku(ColorScheme cs) {
    final w = _luku!.warehouse;
    final y = _luku!.yuanchen;
    return Column(
      children: [
        const SizedBox(height: 12),
        _resultCard('禄库曹官', [
          _row('生肖', '${y['zodiac_name']}（${y['zodiac']}）'),
          _row('所属库', '第${w['ku_no']}库'),
          _row('曹官姓氏', '${w['official_surname']}'),
          _row('欠受生钱', '${w['guan']} 贯'),
          if (w['label'] != null) _row('经文', '${w['label']}'),
        ], cs),
        const SizedBox(height: 12),
        _resultCard('本命元辰', [
          _row('元辰姓名', '${y['yuanchen']}'),
          _row('所许钱', '${y['guan']} 贯'),
          if (y['label'] != null) _row('经文', '${y['label']}'),
        ], cs),
      ],
    );
  }

  Widget _buildWudou(ColorScheme cs) {
    final f = _wudou!.fiveDou;
    final k = _wudou!.kushen;
    return Column(
      children: [
        const SizedBox(height: 12),
        _resultCard('五斗本命钱', [
          _row('归属', '${f['dou']}（${f['stem_group']}）'),
          _row('气数', '${f['qi']}'),
          _row('曾许本命银钱', '${f['guan']} 贯'),
          if (f['label'] != null) _row('经文', '${f['label']}'),
        ], cs),
        const SizedBox(height: 12),
        _resultCard('十二库神', [
          _row('地支', '${k['branch']}（${k['zodiac_name']}）'),
          _row('库序', '第${k['ku_no']}库'),
          _row('三合局', '${k['sanhe_group']}'),
          if (k['label'] != null) _row('经文', '${k['label']}'),
        ], cs),
        if (ShoushengData.wudouNote.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: _disclaimer(ShoushengData.wudouNote, cs),
          ),
      ],
    );
  }

  Widget _resultCard(String title, List<Widget> rows, ColorScheme cs) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title,
                style:
                    TextStyle(fontSize: 15, fontWeight: FontWeight.bold, color: cs.primary)),
            const SizedBox(height: 8),
            ...rows,
          ],
        ),
      ),
    );
  }

  Widget _row(String k, String v) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 96,
            child: Text(k,
                style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant)),
          ),
          Expanded(child: Text(v, style: const TextStyle(fontSize: 13))),
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
