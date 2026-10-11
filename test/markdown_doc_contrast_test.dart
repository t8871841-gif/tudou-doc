import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:nihaisha_app/screens/markdown_doc_screen.dart';

/// WCAG 2.1 相对亮度。
double _luminance(Color c) {
  double ch(double v) {
    final s = v / 255.0;
    return s <= 0.03928 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4).toDouble();
  }

  return 0.2126 * ch(c.r * 255) + 0.7152 * ch(c.g * 255) + 0.0722 * ch(c.b * 255);
}

/// WCAG 2.1 对比度。
double contrast(Color a, Color b) {
  final la = _luminance(a);
  final lb = _luminance(b);
  final hi = max(la, lb);
  final lo = min(la, lb);
  return (hi + 0.05) / (lo + 0.05);
}

String hex(Color c) =>
    '0x${c.toARGB32().toRadixString(16).toUpperCase().padLeft(8, '0')}';

ThemeData _theme(Brightness b) => ThemeData(
      colorScheme: ColorScheme.fromSeed(
        seedColor: const Color(0xFF8B4513),
        brightness: b,
      ),
      useMaterial3: true,
    );

/// 正文（14px）要求 ≥4.5:1；大号标题（≥18.66px bold 或 ≥24px）要求 ≥3:1。
void _expectReadable(
  String label,
  Color text,
  Color background, {
  double min = 4.5,
}) {
  final r = contrast(text, background);
  // ignore: avoid_print
  print('  $label: ${hex(text)} on ${hex(background)} = ${r.toStringAsFixed(2)}:1');
  expect(r, greaterThanOrEqualTo(min),
      reason: '$label 对比度 ${r.toStringAsFixed(2)} < $min');
}

void main() {
  group('MarkdownDocScreen 样式表 · 深色/浅色对比度', () {
    for (final brightness in [Brightness.light, Brightness.dark]) {
      test('${brightness.name} 主题下全部文字达 WCAG AA', () {
        final theme = _theme(brightness);
        final cs = theme.colorScheme;
        final ss = MarkdownDocScreen.styleSheetFor(theme);
        final quoteBg = (ss.blockquoteDecoration! as BoxDecoration).color!;

        // ignore: avoid_print
        print('== ${brightness.name} ==');

        // ① 引用块（闭门课正文主体）：文字必须压得住块底色
        _expectReadable('blockquote 正文', ss.blockquote!.color!, quoteBg);

        // ② 普通段落 / 列表 / 表格
        _expectReadable('p 正文', ss.p!.color!, cs.surface);
        _expectReadable('listBullet 项目符号', ss.listBullet!.color!, cs.surface);
        _expectReadable('tableBody 表格正文', ss.tableBody!.color!, cs.surface);
        _expectReadable('tableHead 表头', ss.tableHead!.color!, cs.surface);

        // ③ 行内代码 / 代码块
        _expectReadable(
          'code 行内代码',
          ss.code!.color!,
          ss.code!.backgroundColor!,
        );
        _expectReadable(
          'codeblock 代码块',
          ss.code!.color!,
          (ss.codeblockDecoration! as BoxDecoration).color!,
        );

        // ④ 链接（14px 正文级，按 4.5 要求）
        _expectReadable('a 链接', ss.a!.color!, cs.surface);

        // ⑤ 标题：h1/h2 属大号（≥3），h3~h6 按正文 4.5 要求
        _expectReadable('h1 大标题', ss.h1!.color!, cs.surface, min: 3.0);
        _expectReadable('h2 大标题', ss.h2!.color!, cs.surface, min: 3.0);
        _expectReadable('h3 小标题', ss.h3!.color!, cs.surface);
        _expectReadable('h4 小标题（primary 强调）', ss.h4!.color!, cs.surface);
        _expectReadable('h5 小标题', ss.h5!.color!, cs.surface);
        _expectReadable('h6 小标题', ss.h6!.color!, cs.surface);
      });
    }

    test('引用块不再使用 flutter_markdown 硬编码的浅蓝底 #BBDEFB', () {
      for (final b in [Brightness.light, Brightness.dark]) {
        final ss = MarkdownDocScreen.styleSheetFor(_theme(b));
        final bg = (ss.blockquoteDecoration! as BoxDecoration).color!;
        // ignore: avoid_print
        print('${b.name} blockquote 底色 = ${hex(bg)}');
        expect(bg, isNot(const Color(0xFFBBDEFB)));
        expect(bg, _theme(b).colorScheme.surfaceContainerHighest);
      }
    });

    test('引用块保留 primary 色左侧竖线做引用标识', () {
      final ss = MarkdownDocScreen.styleSheetFor(_theme(Brightness.dark));
      final deco = ss.blockquoteDecoration! as BoxDecoration;
      final border = deco.border as Border;
      expect(border.left.color, _theme(Brightness.dark).colorScheme.primary);
      expect(border.left.width, 3);
    });

    test('样式表颜色全部取自 ColorScheme（无硬编码字面色）', () {
      final theme = _theme(Brightness.dark);
      final cs = theme.colorScheme;
      final ss = MarkdownDocScreen.styleSheetFor(theme);
      expect(ss.p!.color, cs.onSurface);
      expect(ss.blockquote!.color, cs.onSurface);
      expect(ss.a!.color, cs.primary);
      expect(ss.h1!.color, cs.onSurface);
      expect(ss.listBullet!.color, cs.onSurface);
      expect(ss.tableBody!.color, cs.onSurface);
    });
  });
}
