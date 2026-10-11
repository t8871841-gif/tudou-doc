import 'package:flutter/material.dart';

import '../data/classic_lecture_data.dart';
import 'markdown_doc_screen.dart';

/// 通用「经典讲稿阅读库」屏：按条目列篇目，点按进入 [MarkdownDocScreen] 渲染原文。
///
/// 正文里出现的已知方剂名 / 药材名由 [MarkdownDocScreen] 自动包成链接，
/// 点击直跳方剂 / 药材详情 —— 内经、伤寒论、金匮要略三个阅读库共用此实现。
class ClassicLibraryScreen extends StatelessWidget {
  final String title;
  final List<LectureIndex> lectures;
  final String? infoText;
  final String? footer;

  /// 左侧序号气泡文字，默认用数字化 seq。
  final String Function(int seq)? seqLabel;

  /// 副标题，默认不显示。
  final String Function(int seq)? seqSubtitle;

  const ClassicLibraryScreen({
    super.key,
    required this.title,
    required this.lectures,
    this.infoText,
    this.footer,
    this.seqLabel,
    this.seqSubtitle,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: ListView(
        padding: const EdgeInsets.all(12),
        children: [
          if (infoText != null) ...[
            Container(
              padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 12),
              decoration: BoxDecoration(
                color: cs.tertiaryContainer,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Row(
                children: [
                  Icon(Icons.menu_book_outlined,
                      size: 18, color: cs.onTertiaryContainer),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      infoText!,
                      style: TextStyle(
                        fontSize: 12,
                        color: cs.onTertiaryContainer,
                        height: 1.5,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 10),
          ],
          for (final l in lectures)
            Card(
              margin: const EdgeInsets.only(bottom: 6),
              elevation: 1,
              child: ListTile(
                dense: true,
                leading: CircleAvatar(
                  radius: 16,
                  backgroundColor: cs.primaryContainer,
                  child: Text(
                    seqLabel?.call(l.seq) ?? '${l.seq}',
                    style: TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.bold,
                      color: cs.onPrimaryContainer,
                    ),
                  ),
                ),
                title: Text(
                  l.name,
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                subtitle: seqSubtitle == null
                    ? null
                    : Text(
                        seqSubtitle!(l.seq),
                        style: TextStyle(fontSize: 10, color: cs.outline),
                      ),
                trailing: const Icon(Icons.chevron_right, size: 18),
                onTap: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (_) => MarkdownDocScreen(
                        title: l.name,
                        asset: l.asset,
                        footer: footer,
                        linkFormulas: true,
                      ),
                    ),
                  );
                },
              ),
            ),
        ],
      ),
    );
  }
}
