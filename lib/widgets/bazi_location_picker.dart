import 'package:flutter/material.dart';

import 'package:nihaisha_app/services/city_location_service.dart';

/// 八字排盘的出生地点行：展示当前生效地点，点击弹出中文城市搜索对话框。
///
/// 选定后经 [onSelected] 回调给调用方（写入 SettingsRepository.lastLocation，
/// 供紫微 / 八字共用真太阳时校正）。
class BaZiLocationRow extends StatelessWidget {
  final String? cityName;
  final double? lng;
  final double? lat;
  final ValueChanged<CityLocation> onSelected;

  const BaZiLocationRow({
    super.key,
    required this.cityName,
    required this.lng,
    required this.lat,
    required this.onSelected,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final hasLoc = cityName != null && lng != null;
    return InkWell(
      borderRadius: BorderRadius.circular(10),
      onTap: () async {
        final city = await showDialog<CityLocation>(
          context: context,
          builder: (_) => const _CitySearchDialog(),
        );
        if (city != null) onSelected(city);
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 4),
        child: Row(
          children: [
            Icon(Icons.location_city_outlined,
                size: 18, color: cs.primary),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                hasLoc
                    ? '真太阳时地点：$cityName（${lng!.toStringAsFixed(1)}°E）'
                    : '未设置出生地点，按默认东经 120° 校正（点击选择城市）',
                style: TextStyle(
                  fontSize: 12,
                  color: hasLoc ? cs.onSurface : cs.onSurfaceVariant,
                ),
              ),
            ),
            Icon(Icons.chevron_right, size: 18, color: cs.onSurfaceVariant),
          ],
        ),
      ),
    );
  }
}

class _CitySearchDialog extends StatefulWidget {
  const _CitySearchDialog();

  @override
  State<_CitySearchDialog> createState() => _CitySearchDialogState();
}

class _CitySearchDialogState extends State<_CitySearchDialog> {
  List<CityLocation> _results = const [];
  bool _domestic = true; // true=国内，false=国外；默认国内，不持久化
  bool _loading = false;
  String _query = '';
  int _reqToken = 0; // 丢弃过期（乱序到达）的搜索结果

  PlaceScope get _scope =>
      _domestic ? PlaceScope.domestic : PlaceScope.world;

  @override
  void initState() {
    super.initState();
    _search('');
  }

  Future<void> _search(String q) async {
    _query = q;
    final scope = _scope;
    final token = ++_reqToken;
    setState(() => _loading = true);
    final list = await CityLocationService.load(scope);
    if (!mounted || token != _reqToken) return; // 已切换 scope / 已关闭：丢弃过期结果
    setState(() {
      _results = CityLocationService.searchIn(list, q, 60);
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('选择出生城市'),
      content: SizedBox(
        width: double.maxFinite,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            SegmentedButton<bool>(
              segments: const [
                ButtonSegment(value: true, label: Text('国内')),
                ButtonSegment(value: false, label: Text('国外')),
              ],
              selected: {_domestic},
              onSelectionChanged: (s) {
                setState(() => _domestic = s.first);
                _search(_query); // 切换后按当前关键字重新搜索
              },
            ),
            const SizedBox(height: 8),
            TextField(
              autofocus: true,
              decoration: const InputDecoration(
                hintText: '中文 / 英文搜索城市 / 省份',
                prefixIcon: Icon(Icons.search),
                isDense: true,
              ),
              onChanged: _search,
            ),
            const SizedBox(height: 8),
            Flexible(
              child: _loading
                  ? const Padding(
                      padding: EdgeInsets.all(24),
                      child: Center(child: CircularProgressIndicator()),
                    )
                  : ListView.builder(
                      shrinkWrap: true,
                      itemCount: _results.length,
                      itemBuilder: (context, i) {
                        final c = _results[i];
                        return ListTile(
                          dense: true,
                          title: Text(c.displayName,
                              style: const TextStyle(fontSize: 14)),
                          subtitle: Text(
                            '${c.lng.toStringAsFixed(1)}°E, ${c.lat.toStringAsFixed(1)}°N',
                            style: const TextStyle(fontSize: 11),
                          ),
                          onTap: () => Navigator.pop(context, c),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
      ],
    );
  }
}
