import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../models/formula.dart';
import '../theme/app_colors.dart';
import '../models/bookmark.dart';
import '../data/database_helper.dart';
import '../data/formula_repository.dart';
import '../data/herb_repository.dart';
import '../data/classic_lecture_data.dart';
import 'herb_detail_screen.dart';
import 'markdown_doc_screen.dart';

/// 「XX主之」——方名之后紧接主治标记（跳过收尾引号括号）。
/// 中间档精度：条文原文中**只有「主之」**才算「专门介绍」，
/// 舍去「宜/与/属/可/当」等弱信号，避免把对比/加减方条文也算进来。
final RegExp _versePrescribe = RegExp(r'^\s*[」』】\]）)]?\s*主之');

bool _maximalHas(String text, RegExp nameRe, String name) =>
    nameRe.allMatches(text).any((m) => m.group(0) == name);

/// 一条 伤寒论/金匮 资源是否**专门介绍** [name]（中间档精度）：
/// 1) 方证标题（首行且为标题行）即该方，如「条文15：桂枝汤证」；
/// 2) 条文原文（`>` 引文块，不含倪师讲解小标题）中，**方名之后紧接「主之」**。
///    舍去「宜/与/属/可/当」等弱信号，避免把对比/加减方条文也算进来。
///
/// [nameRe] 是「全部方名按长优先拼成的正则」，用于**最长优先**匹配，
/// 避免「四逆汤」误命中更长的「茯苓四逆汤」「通脉四逆汤」。
bool clauseIntroducesFormula(String text, RegExp nameRe, String name) {
  final lines = text.split('\n');
  // 1) 方证标题（首行且为标题行）即该方，如「### 条文34：四逆汤（救逆第四方）」。
  if (lines.isNotEmpty &&
      lines.first.trim().startsWith('#') &&
      _maximalHas(lines.first, nameRe, name)) {
    return true;
  }
  // 原文区 = `>` 引文块（含硬换行续行）；不含 `#` 讲解小标题。
  final b = StringBuffer();
  var inBq = false;
  for (final ln in lines) {
    final s = ln.trim();
    if (s.startsWith('>')) {
      b.writeln(s);
      inBq = true;
    } else if (inBq && s.isNotEmpty && !s.startsWith('#')) {
      b.writeln(s);
    } else {
      inBq = false;
    }
  }
  final region = b.toString();
  for (final m in nameRe.allMatches(region)) {
    if (m.group(0) != name) continue;
    final after = region.substring(m.end, (m.end + 5).clamp(0, region.length));
    if (_versePrescribe.hasMatch(after)) return true;
  }
  return false;
}

class FormulaDetailScreen extends StatefulWidget {
  final Formula formula;

  const FormulaDetailScreen({super.key, required this.formula});

  @override
  State<FormulaDetailScreen> createState() => _FormulaDetailScreenState();
}

class _FormulaDetailScreenState extends State<FormulaDetailScreen> {
  bool _isBookmarked = false;
  bool _loadingMentions = true;
  List<ClassicLecture> _shanghanHits = [];
  List<ClassicLecture> _jinguiHits = [];
  static final Map<String, String> _assetTextCache = {};
  static RegExp? _nameReCache;

  @override
  void initState() {
    super.initState();
    _checkBookmark();
    _loadMentions();
  }

  void _checkBookmark() async {
    final db = DatabaseHelper.instance;
    final bookmarked = await db.isBookmarked(widget.formula.name);
    if (!mounted) return;
    setState(() => _isBookmarked = bookmarked);
  }

  void _toggleBookmark() async {
    final db = DatabaseHelper.instance;
    if (_isBookmarked) {
      final bookmarks = await db.getAllBookmarks();
      final match = bookmarks.firstWhere(
        (b) => b.title == widget.formula.name,
        orElse: () => Bookmark(title: '', content: '', category: '', source: ''),
      );
      if (match.id != null) {
        await db.deleteBookmark(match.id!);
      }
    } else {
      await db.insertBookmark(Bookmark(
        title: widget.formula.name,
        content: _buildBookmarkContent(),
        category: '方剂',
        source: 'formula_detail',
      ));
    }
    if (mounted) setState(() => _isBookmarked = !_isBookmarked);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_isBookmarked ? '已收藏' : '已取消收藏'),
        ),
      );
    }
  }

  String _buildBookmarkContent() {
    final f = widget.formula;
    return '${f.name} (${f.meridian})\n'
        '${f.indication}\n\n'
        '组成: ${f.componentsText}\n'
        '${f.explanation}';
  }

  /// 「见于经典」：只保留**专门介绍**该方的条文（伤寒论 / 金匮 各自分组）。
  ///
  /// 两本书均已按条文拆成单条资源，故每条命中即代表「该条文专门介绍此方」。
  /// 判定见 [clauseIntroducesFormula]。
  Future<void> _loadMentions() async {
    final name = widget.formula.name;
    final sh = <ClassicLecture>[];
    final jg = <ClassicLecture>[];
    if (name.isNotEmpty) {
      final nameRe = _formulaNameRegExp();
      // 并行读取（伤寒 380 + 金匮 453 个条文资源；串行会明显拖慢首次打开）
      final shTexts = await Future.wait(
          kShangHanLectures.map((l) => _loadAssetText(l.asset)));
      for (var i = 0; i < kShangHanLectures.length; i++) {
        if (clauseIntroducesFormula(shTexts[i], nameRe, name)) {
          sh.add(kShangHanLectures[i]);
        }
      }
      final jgTexts = await Future.wait(
          kJinguiLectures.map((l) => _loadAssetText(l.asset)));
      for (var i = 0; i < kJinguiLectures.length; i++) {
        if (clauseIntroducesFormula(jgTexts[i], nameRe, name)) {
          jg.add(kJinguiLectures[i]);
        }
      }
    }
    if (!mounted) return;
    setState(() {
      _shanghanHits = sh;
      _jinguiHits = jg;
      _loadingMentions = false;
    });
  }

  Future<String> _loadAssetText(String asset) async =>
      _assetTextCache[asset] ??= await rootBundle.loadString(asset);

  /// 全部方名（长优先）拼成的正则，用于**最长优先**匹配，
  /// 避免「四逆汤」误命中「茯苓四逆汤」「通脉四逆汤」等更长方名。
  static RegExp _formulaNameRegExp() {
    return _nameReCache ??= () {
      final names = FormulaRepository.getAll()
          .map((f) => f.name)
          .where((n) => n.isNotEmpty)
          .toList()
        ..sort((a, b) => b.length.compareTo(a.length));
      return RegExp(names.map(RegExp.escape).join('|'));
    }();
  }

  Widget _buildClauseCard(ClassicLecture lec) {
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: ListTile(
        title: Text(lec.name, style: const TextStyle(fontSize: 13)),
        trailing: const Icon(Icons.chevron_right, size: 18),
        onTap: () => Navigator.push(
          context,
          MaterialPageRoute(
            builder: (_) => MarkdownDocScreen(
              title: lec.name,
              asset: lec.asset,
              linkFormulas: true,
            ),
          ),
        ),
      ),
    );
  }

  List<Widget> _buildBookGroup(String bookName, List<ClassicLecture> hits) {
    final color = Theme.of(context).colorScheme.primary;
    return [
      Padding(
        padding: const EdgeInsets.only(top: 8, bottom: 2),
        child: Text(bookName,
            style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: color)),
      ),
      ...hits.map(_buildClauseCard),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final f = widget.formula;
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: Text(f.name),
        actions: [
          IconButton(
            icon: Icon(
              _isBookmarked ? Icons.bookmark : Icons.bookmark_border,
            ),
            onPressed: _toggleBookmark,
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Header
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: cs.primaryContainer,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    f.name,
                    style: TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.bold,
                      color: cs.onPrimaryContainer,
                    ),
                  ),
                  if (f.alias.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(
                      f.alias,
                      style: TextStyle(
                        fontSize: 14,
                        color: context.colors.onSurfaceVariant,
                      ),
                    ),
                  ],
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      _Tag(label: f.meridian, color: cs.tertiary),
                      const SizedBox(width: 8),
                      _Tag(label: f.category, color: cs.secondary),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 20),

            // 组成
            _SectionTitle(title: '组成'),
            const SizedBox(height: 8),
            ...f.components.map((c) {
              final herb = HerbRepository.getByName(c.name);
              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: InkWell(
                  onTap: herb != null
                      ? () => Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (_) => HerbDetailScreen(herb: herb),
                            ),
                          )
                      : null,
                  borderRadius: BorderRadius.circular(8),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Container(
                          width: 8,
                          height: 8,
                          margin: const EdgeInsets.only(top: 6),
                          decoration: BoxDecoration(
                            color: cs.primary,
                            shape: BoxShape.circle,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: RichText(
                            text: TextSpan(
                              style: TextStyle(
                                fontSize: 14,
                                color: cs.onSurface,
                              ),
                              children: [
                                TextSpan(
                                  text: c.name,
                                  style: TextStyle(
                                    fontWeight: FontWeight.bold,
                                    color: herb != null ? cs.primary : cs.onSurface,
                                    decoration: herb != null ? TextDecoration.underline : null,
                                  ),
                                ),
                                if (c.dosage.isNotEmpty)
                                  TextSpan(text: '  ${c.dosage}'),
                              ],
                            ),
                          ),
                        ),
                        if (c.role.isNotEmpty)
                          Expanded(
                            child: Text(
                              c.role,
                              style: TextStyle(
                                fontSize: 12,
                                color: context.colors.onSurfaceVariant,
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              );
            }),
            const SizedBox(height: 20),

            // 适应证
            _SectionTitle(title: '适应证'),
            const SizedBox(height: 8),
            Text(
              f.indication,
              style: const TextStyle(fontSize: 14, height: 1.6),
            ),
            const SizedBox(height: 20),

            // 禁忌
            if (f.contraindication.isNotEmpty) ...[
              _SectionTitle(title: '禁忌'),
              const SizedBox(height: 8),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: context.colors.dangerContainer,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  f.contraindication,
                  style: TextStyle(
                    fontSize: 14,
                    color: cs.onErrorContainer,
                    height: 1.5,
                  ),
                ),
              ),
              const SizedBox(height: 20),
            ],

            // 煎服法
            if (f.dosage.isNotEmpty) ...[
              _SectionTitle(title: '煎服法'),
              const SizedBox(height: 8),
              Text(
                f.dosage,
                style: const TextStyle(fontSize: 14, height: 1.6),
              ),
              const SizedBox(height: 20),
            ],

            // 倪海厦解读
            if (f.explanation.isNotEmpty) ...[
              _SectionTitle(title: '倪海厦解读'),
              const SizedBox(height: 8),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: context.colors.primaryContainer,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  f.explanation,
                  style: TextStyle(
                    fontSize: 14,
                    height: 1.6,
                    color: cs.onTertiaryContainer,
                  ),
                ),
              ),
            ],

            // 见于经典
            const SizedBox(height: 20),
            _SectionTitle(title: '见于经典'),
            const SizedBox(height: 8),
            if (_loadingMentions)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 8),
                child: CircularProgressIndicator(),
              )
            else if (_shanghanHits.isEmpty && _jinguiHits.isEmpty)
              Text('暂未在该模块经典中找到出处',
                  style: TextStyle(fontSize: 13, color: context.colors.onSurfaceVariant))
            else ...[
              if (_shanghanHits.isNotEmpty) ..._buildBookGroup('伤寒论', _shanghanHits),
              if (_jinguiHits.isNotEmpty) ..._buildBookGroup('金匮要略', _jinguiHits),
            ],
          ],
        ),
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  final String title;
  const _SectionTitle({required this.title});

  @override
  Widget build(BuildContext context) {
    return Text(
      title,
      style: TextStyle(
        fontSize: 16,
        fontWeight: FontWeight.bold,
        color: Theme.of(context).colorScheme.primary,
      ),
    );
  }
}

class _Tag extends StatelessWidget {
  final String label;
  final Color color;
  const _Tag({required this.label, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 12,
          color: color,
          fontWeight: FontWeight.w500,
        ),
      ),
    );
  }
}
