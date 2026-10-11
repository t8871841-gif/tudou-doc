import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nihaisha_app/data/neijing_entries.dart';
import 'package:nihaisha_app/screens/neijing_entries_screen.dart';

/// 《黄帝内经》结构化条目检索页：过滤纯函数 + 页面交互冒烟（浅色 / 深色）。
void main() {
  // -------------------------------------------------------------------------
  // 数据与纯函数层
  // -------------------------------------------------------------------------
  group('条目数据完整性', () {
    test('52 条条目，id 唯一，十维度全覆盖', () {
      expect(kNeijingEntries.length, 52);
      expect(kNeijingDimensions.length, 10);

      final ids = kNeijingEntries.map((e) => e.id).toList();
      expect(ids.toSet().length, ids.length, reason: 'id 必须唯一');

      final dimKeys = kNeijingDimensions.map((d) => d.key).toSet();
      for (final e in kNeijingEntries) {
        expect(dimKeys, contains(e.dimension), reason: '${e.id} 维度非法');
        expect(e.title, isNotEmpty, reason: '${e.id} 缺标题');
        expect(e.original, isNotEmpty, reason: '${e.id} 缺原文');
        expect(e.source, isNotEmpty, reason: '${e.id} 缺出处');
        expect(e.vernacular, isNotEmpty, reason: '${e.id} 缺白话');
        expect(e.nishi, isNotEmpty, reason: '${e.id} 缺倪师解读');
      }
      // 每个维度至少 3 条
      for (final d in kNeijingDimensions) {
        final n = kNeijingEntries.where((e) => e.dimension == d.key).length;
        expect(n, greaterThanOrEqualTo(3), reason: '${d.label} 条目过少：$n');
      }
    });
  });

  group('filterNeijingEntries 过滤逻辑', () {
    test('默认不过滤时返回全量 52 条', () {
      expect(filterNeijingEntries().length, 52);
    });

    test('搜索「白虎」命中且全部字段可命中', () {
      final hit = filterNeijingEntries(query: '白虎');
      expect(hit, isNotEmpty);
      for (final e in hit) {
        final hay = <String>[
          e.title,
          e.original,
          e.source,
          e.vernacular,
          e.nishi,
          e.tags.join(' '),
        ].join('\n');
        expect(hay, contains('白虎'), reason: e.id);
      }
    });

    test('按篇名检索「热论」命中《素问·热论》各条且不误命中他篇', () {
      final hit = filterNeijingEntries(query: '热论');
      final ids = hit.map((e) => e.id).toSet();
      // 数据侧：source 为《素问·热论》的条目
      final bySource = kNeijingEntries
          .where((e) => e.source.contains('热论'))
          .map((e) => e.id)
          .toSet();
      expect(bySource, isNotEmpty);
      expect(ids, bySource);
      for (final id in ['bg-01', 'bg-02', 'bg-03', 'wy-04']) {
        expect(ids, contains(id), reason: '$id 应出自《素问·热论》');
      }
      for (final e in hit) {
        expect(e.source, contains('热论'), reason: e.id);
      }
    });

    test('按篇名检索「灵枢」只命中灵枢系条目', () {
      final hit = filterNeijingEntries(query: '灵枢');
      expect(hit, isNotEmpty);
      for (final e in hit) {
        expect(e.source, contains('灵枢'), reason: e.id);
      }
    });

    test('搜索不区分大小写且多关键词为「与」', () {
      final byTag = filterNeijingEntries(query: '心');
      expect(byTag, isNotEmpty);
      // 多关键词：同时含「心」「阳」
      final both = filterNeijingEntries(query: '心 阳');
      for (final e in both) {
        final hay = '${e.title}\n${e.original}\n${e.source}\n${e.vernacular}\n'
            '${e.nishi}\n${e.tags.join(' ')}'
            .toLowerCase();
        expect(hay, contains('心'));
        expect(hay, contains('阳'));
      }
      expect(both.length, lessThanOrEqualTo(byTag.length));
    });

    test('维度筛选只返回该维度条目', () {
      for (final d in kNeijingDimensions) {
        final r = filterNeijingEntries(dimension: d.key);
        expect(r, isNotEmpty, reason: d.label);
        for (final e in r) {
          expect(e.dimension, d.key);
        }
      }
      // '全部' 与 null 等价
      expect(filterNeijingEntries(dimension: kNeijingAllDimensionKey).length,
          filterNeijingEntries().length);
    });

    test('标签多选为「或」关系', () {
      final t1 = filterNeijingEntries(tags: {'心'});
      final t2 = filterNeijingEntries(tags: {'肾'});
      final or = filterNeijingEntries(tags: {'心', '肾'});
      expect(or.length, greaterThanOrEqualTo(t1.length));
      expect(or.length, greaterThanOrEqualTo(t2.length));
      for (final e in or) {
        expect(e.tags.any((t) => t == '心' || t == '肾'), isTrue, reason: e.id);
      }
    });

    test('维度 + 搜索 + 标签三者叠加生效', () {
      final r = filterNeijingEntries(
          query: '心', dimension: 'zangxiang', tags: {'心'});
      expect(r, isNotEmpty);
      for (final e in r) {
        expect(e.dimension, 'zangxiang');
        expect(e.tags, contains('心'));
      }
    });

    test('无结果时返回空列表', () {
      expect(filterNeijingEntries(query: '绝无此词zzz'), isEmpty);
    });
  });

  group('rankNeijingTags 标签排序', () {
    test('按出现次数降序且上限 12', () {
      final tags = rankNeijingTags(kNeijingEntries);
      expect(tags.length, lessThanOrEqualTo(12));
      expect(tags.toSet().length, tags.length);

      final counts = <String, int>{};
      for (final e in kNeijingEntries) {
        for (final t in e.tags) {
          counts[t] = (counts[t] ?? 0) + 1;
        }
      }
      for (var i = 1; i < tags.length; i++) {
        expect(counts[tags[i - 1]]!, greaterThanOrEqualTo(counts[tags[i]]!));
      }
    });
  });

  group('highlightNeijingMark 高亮', () {
    test('【推断】被单独切成高亮 span', () {
      final spans = highlightNeijingMark(
        '前文【推断】后文',
        const TextStyle(),
        const Color(0xFFB3261E),
      );
      final text = spans.map((s) => s.text ?? '').join();
      expect(text, '前文【推断】后文');
      expect(spans.any((s) => s.text == kNeijingInferenceMark), isTrue);
      expect(spans.firstWhere((s) => s.text == kNeijingInferenceMark).style
          ?.fontWeight, FontWeight.bold);
    });
  });

  // -------------------------------------------------------------------------
  // 页面交互
  // -------------------------------------------------------------------------
  Widget buildApp({Brightness brightness = Brightness.light}) {
    return MaterialApp(
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF8B4513),
          brightness: brightness,
        ),
      ),
      home: const NeijingEntriesScreen(),
    );
  }

  group('条目检索页交互', () {
    testWidgets('默认渲染全部 52 条并显示计数', (tester) async {
      await tester.pumpWidget(buildApp());
      await tester.pumpAndSettle();

      expect(find.text('内经条目 · 十维度'), findsOneWidget);
      expect(find.text('共 52 条'), findsOneWidget);
      // 首条（脏象）
      expect(find.text('十二官相使（脏腑如朝廷）'), findsOneWidget);
      // 维度 chip 齐全（全部 + 十维度）
      for (final d in kNeijingDimensions) {
        expect(find.text(d.label), findsWidgets);
      }
      expect(tester.takeException(), isNull);
    });

    testWidgets('搜索「白虎」实时过滤并更新计数', (tester) async {
      await tester.pumpWidget(buildApp());
      await tester.pumpAndSettle();

      final expected = filterNeijingEntries(query: '白虎');
      expect(expected, isNotEmpty);

      await tester.enterText(find.byType(TextField), '白虎');
      await tester.pumpAndSettle();

      expect(find.text('共 ${expected.length} 条'), findsOneWidget);
      // 清空按钮出现
      expect(find.byTooltip('清空搜索'), findsOneWidget);

      // 点清空后恢复全量
      await tester.tap(find.byTooltip('清空搜索'));
      await tester.pumpAndSettle();
      expect(find.text('共 52 条'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('搜索「热论」按篇名定位条目', (tester) async {
      await tester.pumpWidget(buildApp());
      await tester.pumpAndSettle();

      final expected = filterNeijingEntries(query: '热论');
      expect(expected, isNotEmpty);
      await tester.enterText(find.byType(TextField), '热论');
      await tester.pumpAndSettle();

      expect(find.text('共 ${expected.length} 条'), findsOneWidget);
      // 首条属于《素问·热论》
      expect(find.text(expected.first.title), findsOneWidget);
      expect(find.text(expected.first.source), findsWidgets);
      expect(tester.takeException(), isNull);
    });

    testWidgets('点击维度 chip「治则方药」只显示该维度条目', (tester) async {
      await tester.pumpWidget(buildApp());
      await tester.pumpAndSettle();

      await tester.ensureVisible(find.widgetWithText(FilterChip, '治则方药'));
      await tester.tap(find.widgetWithText(FilterChip, '治则方药'));
      await tester.pumpAndSettle();

      final expected = filterNeijingEntries(dimension: 'zhize');
      expect(expected, isNotEmpty);
      expect(find.text('共 ${expected.length} 条'), findsOneWidget);

      // 列表首条属于该维度
      final first = kNeijingEntries
          .firstWhere((e) => e.dimension == 'zhize');
      expect(find.text(first.title), findsOneWidget);
      // 脏象条目已被过滤掉（滚动到可见后仍不在树中）
      expect(find.text('十二官相使（脏腑如朝廷）'), findsNothing);
      expect(tester.takeException(), isNull);
    });

    testWidgets('切换维度保留搜索词并清空标签选择', (tester) async {
      await tester.pumpWidget(buildApp());
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), '心');
      await tester.pumpAndSettle();
      final withQuery = filterNeijingEntries(query: '心');

      // 选一个标签
      final pool = rankNeijingTags(withQuery);
      expect(pool, isNotEmpty);
      await tester.ensureVisible(find.widgetWithText(FilterChip, '#${pool.first}').first);
      await tester.tap(find.widgetWithText(FilterChip, '#${pool.first}').first);
      await tester.pumpAndSettle();
      expect(find.text('清空'), findsOneWidget);

      // 切维度：搜索词保留，标签清空
      await tester.ensureVisible(find.widgetWithText(FilterChip, '脏象').first);
      await tester.tap(find.widgetWithText(FilterChip, '脏象').first);
      await tester.pumpAndSettle();
      // 搜索词保留：输入框内容仍是「心」
      final field = tester.widget<TextField>(find.byType(TextField));
      expect(field.controller?.text, '心');
      expect(find.text('清空'), findsNothing); // 标签清空按钮消失

      final expected = filterNeijingEntries(query: '心', dimension: 'zangxiang');
      expect(find.text('共 ${expected.length} 条'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('卡片展开显示原文/出处/白话/倪师解读并高亮【推断】', (tester) async {
      // 用含【推断】的条目：脏象首条
      final entry = kNeijingEntries.firstWhere(
        (e) => e.id == 'zx-01' && e.nishi.contains(kNeijingInferenceMark),
      );
      await tester.pumpWidget(buildApp());
      await tester.pumpAndSettle();

      expect(find.text('经典原文'), findsNothing);
      await tester.ensureVisible(find.text(entry.title));
      await tester.tap(find.text(entry.title));
      await tester.pumpAndSettle();

      expect(find.text('经典原文'), findsOneWidget);
      expect(find.text('出处'), findsOneWidget);
      expect(find.text('白话译解'), findsOneWidget);
      expect(find.text('倪师解读'), findsOneWidget);
      expect(find.text(entry.source), findsWidgets);
      // 【推断】在 RichText 中以高亮 span 呈现
      expect(
        find.byWidgetPredicate((w) =>
            w is RichText &&
            w.text.toPlainText().contains(kNeijingInferenceMark)),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    });

    testWidgets('点击卡片内标签 chip 加入筛选', (tester) async {
      await tester.pumpWidget(buildApp());
      await tester.pumpAndSettle();

      const tag = '心';
      final expected = filterNeijingEntries(tags: {tag});
      expect(expected, isNotEmpty);

      // 卡片内标签为 GestureDetector 包裹的 chip（区别于顶部 FilterChip 标签行）
      final cardTag = find.widgetWithText(GestureDetector, '#$tag').first;
      await tester.ensureVisible(cardTag);
      await tester.tap(cardTag);
      await tester.pumpAndSettle();

      expect(find.text('共 ${expected.length} 条'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('无结果时显示空状态并可清空筛选', (tester) async {
      await tester.pumpWidget(buildApp());
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), '绝无此词zzz');
      await tester.pumpAndSettle();

      expect(find.text('没有匹配的条目'), findsOneWidget);
      await tester.tap(find.text('清空筛选'));
      await tester.pumpAndSettle();
      expect(find.text('共 52 条'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('深色模式下渲染无异常（无硬编码浅色底）', (tester) async {
      await tester.pumpWidget(buildApp(brightness: Brightness.dark));
      await tester.pumpAndSettle();

      expect(find.text('共 52 条'), findsOneWidget);
      // 展开一条，确认深色下四段内容正常构建
      await tester.ensureVisible(find.text('十二官相使（脏腑如朝廷）'));
      await tester.tap(find.text('十二官相使（脏腑如朝廷）'));
      await tester.pumpAndSettle();
      expect(find.text('倪师解读'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });
}
