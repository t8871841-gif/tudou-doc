/// 《黄帝内经》结构化条目检索页（十维度 + 标签交叉检索）。
///
/// 设计目的：
/// 1. **原文出处清晰**——每条内容分四个字段呈现：**经典原文 / 出处 / 白话译解 /
///    倪师解读**，原文与解读不再混作一段。
/// 2. **解析维度可扩展**——顶部按十维度（脏象、望诊、脉诊……误治宜忌）筛选，
///    下方按标签交叉检索（标签候选从当前结果中按出现次数降序生成）。
///
/// 配色一律取自 `Theme.of(context).colorScheme`，深色模式自动生效。
/// 内容属传统文化参考，非医疗建议；倪师解读中非原文直引处一律标注【推断】。
library;

import 'package:flutter/material.dart';

import '../data/neijing_entries.dart';

/// 「全部维度」的筛选键（不等于任何 [NeijingDimension.key]）。
const String kNeijingAllDimensionKey = '__all__';

/// 倪师解读中「非原文直引」的显著标记。
const String kNeijingInferenceMark = '【推断】';

// ---------------------------------------------------------------------------
// 纯函数层（不依赖 Flutter widget，便于单元测试）
// ---------------------------------------------------------------------------

/// 按「搜索词 + 维度 + 标签」过滤条目。
///
/// - [query]：不区分大小写；按空白切分为多个关键词，关键词之间为「与」关系；
///   匹配范围 = title + original + **source（篇名，如「热论」「灵枢」）**
///   + vernacular + nishi + tags。
/// - [dimension]：为 `null` 或 [kNeijingAllDimensionKey] 时不限维度。
/// - [tags]：多选，标签之间为「或」关系（命中任一即可）。为空集时不限标签。
/// - [source]：数据源，默认全量 [kNeijingEntries]。
List<NeijingEntry> filterNeijingEntries({
  String query = '',
  String? dimension,
  Set<String> tags = const <String>{},
  List<NeijingEntry> source = kNeijingEntries,
}) {
  final dim = (dimension == null || dimension == kNeijingAllDimensionKey)
      ? null
      : dimension;
  final keywords = query
      .trim()
      .toLowerCase()
      .split(RegExp(r'\s+'))
      .where((w) => w.isNotEmpty)
      .toList(growable: false);
  final effectiveTags = tags.where((t) => t.isNotEmpty).toSet();

  return source.where((entry) {
    if (dim != null && entry.dimension != dim) return false;
    if (effectiveTags.isNotEmpty &&
        !entry.tags.any(effectiveTags.contains)) {
      return false;
    }
    if (keywords.isEmpty) return true;
    final haystack = <String>[
      entry.title,
      entry.original,
      entry.source, // 篇名检索：输「热论」「灵枢」可直接定位该篇条目
      entry.vernacular,
      entry.nishi,
      entry.tags.join(' '),
    ].join('\n').toLowerCase();
    for (final word in keywords) {
      if (!haystack.contains(word)) return false;
    }
    return true;
  }).toList(growable: false);
}

/// 统计 [pool] 中出现过的标签，按出现次数降序（次数相同按字典序），最多 [limit] 个。
List<String> rankNeijingTags(List<NeijingEntry> pool, {int limit = 12}) {
  final counts = <String, int>{};
  for (final entry in pool) {
    for (final tag in entry.tags) {
      final t = tag.trim();
      if (t.isEmpty) continue;
      counts[t] = (counts[t] ?? 0) + 1;
    }
  }
  final ranked = counts.entries.toList()
    ..sort((a, b) {
      final byCount = b.value.compareTo(a.value);
      return byCount != 0 ? byCount : a.key.compareTo(b.key);
    });
  return ranked.take(limit).map((e) => e.key).toList(growable: false);
}

/// 取维度中文名；未知 key 返回 `未分类`。
String neijingDimensionLabel(String key) {
  for (final d in kNeijingDimensions) {
    if (d.key == key) return d.label;
  }
  return '未分类';
}

/// 取维度一句话说明；未知 key 返回空串。
String neijingDimensionDesc(String key) {
  for (final d in kNeijingDimensions) {
    if (d.key == key) return d.desc;
  }
  return '';
}

/// 把 [text] 中出现的 [mark]（默认【推断】）高亮为 [accent] 加粗，其余按 [base] 渲染。
List<TextSpan> highlightNeijingMark(
  String text,
  TextStyle base,
  Color accent, {
  String mark = kNeijingInferenceMark,
}) {
  final spans = <TextSpan>[];
  final parts = text.split(mark);
  for (var i = 0; i < parts.length; i++) {
    if (i > 0) {
      spans.add(TextSpan(
        text: mark,
        style: base.copyWith(color: accent, fontWeight: FontWeight.bold),
      ));
    }
    if (parts[i].isNotEmpty) {
      spans.add(TextSpan(text: parts[i], style: base));
    }
  }
  return spans;
}

// ---------------------------------------------------------------------------
// 页面
// ---------------------------------------------------------------------------

/// 十维度结构化条目检索页。
class NeijingEntriesScreen extends StatefulWidget {
  const NeijingEntriesScreen({super.key});

  @override
  State<NeijingEntriesScreen> createState() => _NeijingEntriesScreenState();
}

class _NeijingEntriesScreenState extends State<NeijingEntriesScreen> {
  final TextEditingController _searchController = TextEditingController();

  String _query = '';
  String _dimension = kNeijingAllDimensionKey;
  final Set<String> _selectedTags = <String>{};

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  void _onQueryChanged(String value) {
    setState(() => _query = value);
  }

  void _clearQuery() {
    _searchController.clear();
    setState(() => _query = '');
  }

  void _onDimensionSelected(String key) {
    setState(() {
      _dimension = key;
      // 切换维度保留搜索词，但清空标签选择（原标签可能不属于新维度）。
      _selectedTags.clear();
    });
  }

  void _onTagToggled(String tag) {
    setState(() {
      if (_selectedTags.contains(tag)) {
        _selectedTags.remove(tag);
      } else {
        _selectedTags.add(tag);
      }
    });
  }

  void _clearTags() => setState(_selectedTags.clear);

  void _clearAllFilters() {
    _searchController.clear();
    setState(() {
      _query = '';
      _dimension = kNeijingAllDimensionKey;
      _selectedTags.clear();
    });
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    // 标签候选池：应用「搜索 + 维度」，不应用标签自身，避免选中后其余标签消失。
    final base = filterNeijingEntries(query: _query, dimension: _dimension);
    final tagPool = rankNeijingTags(base);
    // 候选全集（不限前 12）：卡片上点击的冷门标签也要能真正生效。
    final allTags = <String>{for (final e in base) ...e.tags};
    final effectiveTags = _selectedTags.where(allTags.contains).toSet();
    // 展示列表 = 高频前 12 + 已选但不在前 12 的（保证用户能取消）。
    final visibleTags = <String>[
      ...tagPool,
      for (final t in effectiveTags)
        if (!tagPool.contains(t)) t,
    ];
    final results = filterNeijingEntries(
        query: _query, dimension: _dimension, tags: effectiveTags);

    return Scaffold(
      appBar: AppBar(
        title: const Text('内经条目 · 十维度'),
        actions: [
          IconButton(
            tooltip: '清空全部筛选',
            icon: const Icon(Icons.filter_alt_off_outlined),
            onPressed: _clearAllFilters,
          ),
        ],
      ),
      body: Column(
        children: [
          _buildSearchField(cs),
          _buildDimensionRow(cs),
          _buildTagsSection(cs, visibleTags, effectiveTags),
          _buildCountBar(cs, results.length, base.length),
          Expanded(
            child: results.isEmpty
                ? _buildEmptyState(cs)
                : ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 4, 12, 16),
                    itemCount: results.length + 1,
                    itemBuilder: (context, index) {
                      if (index == results.length) return _buildFooter(cs);
                      return _NeijingEntryCard(
                        key: ValueKey(results[index].id),
                        entry: results[index],
                        selectedTags: effectiveTags,
                        onTagTap: _onTagToggled,
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }

  Widget _buildSearchField(ColorScheme cs) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 2),
      child: TextField(
        controller: _searchController,
        onChanged: _onQueryChanged,
        textInputAction: TextInputAction.search,
        decoration: InputDecoration(
          isDense: true,
          filled: true,
          fillColor: cs.surfaceContainerHighest,
          hintText: '搜索标题 / 原文 / 出处 / 白话 / 倪师解读 / 标签',
          hintStyle: TextStyle(fontSize: 13, color: cs.onSurfaceVariant),
          prefixIcon: Icon(Icons.search, size: 20, color: cs.onSurfaceVariant),
          suffixIcon: _query.isEmpty
              ? null
              : IconButton(
                  tooltip: '清空搜索',
                  icon: Icon(Icons.close, size: 18, color: cs.onSurfaceVariant),
                  onPressed: _clearQuery,
                ),
          contentPadding:
              const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: BorderSide(color: cs.outlineVariant),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: BorderSide(color: cs.outlineVariant),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: BorderSide(color: cs.primary, width: 1.5),
          ),
        ),
        style: TextStyle(fontSize: 14, color: cs.onSurface),
      ),
    );
  }

  Widget _buildDimensionRow(ColorScheme cs) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: Row(
        children: [
          _buildChip(
            cs,
            label: '全部',
            selected: _dimension == kNeijingAllDimensionKey,
            tooltip: '不限维度',
            onTap: () => _onDimensionSelected(kNeijingAllDimensionKey),
          ),
          for (final dim in kNeijingDimensions)
            Padding(
              padding: const EdgeInsets.only(left: 8),
              child: _buildChip(
                cs,
                label: dim.label,
                selected: _dimension == dim.key,
                tooltip: dim.desc,
                onTap: () => _onDimensionSelected(dim.key),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildTagsSection(
      ColorScheme cs, List<String> tagPool, Set<String> selected) {
    final countText = '标签：命中任一（共 ${tagPool.length} 个'
        '${tagPool.length >= 12 ? '+' : ''}）';
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 0, 12, 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.sell_outlined, size: 13, color: cs.onSurfaceVariant),
              const SizedBox(width: 4),
              Expanded(
                child: Text(
                  countText,
                  style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
                ),
              ),
              if (selected.isNotEmpty)
                SizedBox(
                  height: 26,
                  child: TextButton.icon(
                    onPressed: _clearTags,
                    icon: Icon(Icons.close, size: 14, color: cs.primary),
                    label: Text('清空', style: TextStyle(fontSize: 11, color: cs.primary)),
                    style: TextButton.styleFrom(
                      padding: const EdgeInsets.symmetric(horizontal: 8),
                      minimumSize: Size.zero,
                      tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 4),
          if (tagPool.isEmpty)
            Text(
              '无可用标签',
              style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
            )
          else
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  for (var i = 0; i < tagPool.length; i++)
                    Padding(
                      padding: EdgeInsets.only(left: i == 0 ? 0 : 6),
                      child: _buildChip(
                        cs,
                        label: '#${tagPool[i]}',
                        selected: selected.contains(tagPool[i]),
                        tooltip: '按标签「${tagPool[i]}」筛选（可多选，命中任一）',
                        onTap: () => _onTagToggled(tagPool[i]),
                        small: true,
                      ),
                    ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildCountBar(ColorScheme cs, int count, int poolCount) {
    final filtering =
        _query.isNotEmpty || _dimension != kNeijingAllDimensionKey;
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 6, 14, 4),
      child: Row(
        children: [
          Text(
            '共 $count 条',
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: cs.onSurface,
            ),
          ),
          if (filtering) ...[
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                '（候选 $poolCount 条 · ${neijingDimensionLabel(_dimension)}）',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildEmptyState(ColorScheme cs) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.search_off_outlined, size: 44, color: cs.onSurfaceVariant),
            const SizedBox(height: 12),
            Text(
              '没有匹配的条目',
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w600,
                color: cs.onSurface,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              '试试换个关键词，或清空维度 / 标签筛选。',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
            ),
            const SizedBox(height: 16),
            OutlinedButton.icon(
              onPressed: _clearAllFilters,
              icon: const Icon(Icons.filter_alt_off_outlined, size: 18),
              label: const Text('清空筛选'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildFooter(ColorScheme cs) {
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Text(
        '原文引自《素问》《灵枢》· 倪师解读中「$kNeijingInferenceMark」标记为非原文直引'
        ' · 传统文化参考，非医疗建议',
        textAlign: TextAlign.center,
        style: TextStyle(fontSize: 10, color: cs.outline),
      ),
    );
  }

  /// 统一的筛选 chip（维度 / 标签共用）。
  Widget _buildChip(
    ColorScheme cs, {
    required String label,
    required bool selected,
    required String tooltip,
    required VoidCallback onTap,
    bool small = false,
  }) {
    return Tooltip(
      message: tooltip,
      child: FilterChip(
        label: Text(
          label,
          style: TextStyle(
            fontSize: small ? 11 : 12.5,
            color: selected ? cs.onSecondaryContainer : cs.onSurfaceVariant,
            fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
          ),
        ),
        selected: selected,
        showCheckmark: false,
        materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
        visualDensity: VisualDensity.compact,
        backgroundColor: cs.surfaceContainerHighest,
        selectedColor: cs.secondaryContainer,
        side: BorderSide(color: selected ? cs.secondary : cs.outlineVariant),
        padding: const EdgeInsets.symmetric(horizontal: 4),
        onSelected: (_) => onTap(),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 条目卡片
// ---------------------------------------------------------------------------

/// 单条条目卡片：折叠态显示「标题 / 维度+出处 / 标签」，点击头部展开四段内容。
class _NeijingEntryCard extends StatefulWidget {
  final NeijingEntry entry;
  final Set<String> selectedTags;
  final ValueChanged<String> onTagTap;

  const _NeijingEntryCard({
    super.key,
    required this.entry,
    required this.selectedTags,
    required this.onTagTap,
  });

  @override
  State<_NeijingEntryCard> createState() => _NeijingEntryCardState();
}

class _NeijingEntryCardState extends State<_NeijingEntryCard> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final entry = widget.entry;
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: cs.outlineVariant),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          InkWell(
            borderRadius: const BorderRadius.vertical(top: Radius.circular(12)),
            onTap: () => setState(() => _expanded = !_expanded),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 12, 12, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: Text(
                          entry.title,
                          style: TextStyle(
                            fontSize: 14.5,
                            height: 1.4,
                            fontWeight: FontWeight.bold,
                            color: cs.onSurface,
                          ),
                        ),
                      ),
                      const SizedBox(width: 6),
                      Padding(
                        padding: const EdgeInsets.only(top: 2),
                        child: Icon(
                          _expanded
                              ? Icons.keyboard_arrow_up
                              : Icons.keyboard_arrow_down,
                          size: 18,
                          color: cs.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(
                          color: cs.secondaryContainer,
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(
                          neijingDimensionLabel(entry.dimension),
                          style: TextStyle(
                            fontSize: 10.5,
                            color: cs.onSecondaryContainer,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Icon(Icons.menu_book, size: 13, color: cs.primary),
                      const SizedBox(width: 3),
                      Expanded(
                        child: Text(
                          entry.source,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 11.5,
                            fontStyle: FontStyle.italic,
                            color: cs.primary,
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          if (entry.tags.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 10),
              child: Wrap(
                spacing: 6,
                runSpacing: 4,
                children: [
                  for (final tag in entry.tags)
                    _buildTagChip(cs, tag, widget.selectedTags.contains(tag)),
                ],
              ),
            ),
          AnimatedSize(
            duration: const Duration(milliseconds: 200),
            curve: Curves.easeInOut,
            alignment: Alignment.topCenter,
            clipBehavior: Clip.hardEdge,
            child: _expanded
                ? Padding(
                    padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Divider(height: 1, color: cs.outlineVariant),
                        const SizedBox(height: 10),
                        _buildSection(
                          cs,
                          title: '经典原文',
                          icon: Icons.format_quote,
                          child: _buildQuote(cs, entry.original),
                        ),
                        const SizedBox(height: 12),
                        _buildSection(
                          cs,
                          title: '出处',
                          icon: Icons.menu_book_outlined,
                          child: Text(
                            entry.source,
                            style: TextStyle(
                              fontSize: 13,
                              height: 1.6,
                              color: cs.primary,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                        const SizedBox(height: 12),
                        _buildSection(
                          cs,
                          title: '白话译解',
                          icon: Icons.translate,
                          child: Text(
                            entry.vernacular,
                            style: TextStyle(
                              fontSize: 13.5,
                              height: 1.7,
                              color: cs.onSurface,
                            ),
                          ),
                        ),
                        const SizedBox(height: 12),
                        _buildSection(
                          cs,
                          title: '倪师解读',
                          icon: Icons.record_voice_over_outlined,
                          child: RichText(
                            text: TextSpan(
                              children: highlightNeijingMark(
                                entry.nishi,
                                TextStyle(
                                  fontSize: 13.5,
                                  height: 1.7,
                                  color: cs.onSurfaceVariant,
                                ),
                                cs.error,
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  )
                : const SizedBox(width: double.infinity),
          ),
        ],
      ),
    );
  }

  Widget _buildTagChip(ColorScheme cs, String tag, bool selected) {
    return GestureDetector(
      onTap: () => widget.onTagTap(tag),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
        decoration: BoxDecoration(
          color: selected ? cs.secondaryContainer : cs.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(
            color: selected ? cs.secondary : cs.outlineVariant,
            width: selected ? 1.2 : 1,
          ),
        ),
        child: Text(
          '#$tag',
          style: TextStyle(
            fontSize: 10.5,
            color: selected ? cs.onSecondaryContainer : cs.onSurfaceVariant,
          ),
        ),
      ),
    );
  }

  Widget _buildSection(
    ColorScheme cs, {
    required String title,
    required IconData icon,
    required Widget child,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(icon, size: 14, color: cs.primary),
            const SizedBox(width: 5),
            Text(
              title,
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.5,
                color: cs.primary,
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        child,
      ],
    );
  }

  /// 原文引用块：左竖线 + `surfaceContainerHighest` 底，与 Markdown 引用块风格一致。
  Widget _buildQuote(ColorScheme cs, String text) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(12, 10, 10, 10),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius:
            const BorderRadius.horizontal(right: Radius.circular(4)),
        border: Border(left: BorderSide(color: cs.primary, width: 3)),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 14,
          height: 1.75,
          color: cs.onSurface,
        ),
      ),
    );
  }
}
