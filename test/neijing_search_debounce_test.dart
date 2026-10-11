import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nihaisha_app/screens/neijing_search_screen.dart';
import 'package:nihaisha_app/theme/app_colors.dart';

/// P1 修复验证：内经全文搜索的 250ms 防抖。
///
/// 断言：输入关键词后，结果在 250ms 防抖窗口内【缺席】，跨过阈值后【出现】。
/// 复用 `test/neijing_library_search_test.dart` 已验证可行的
/// runAsync 真实 IO 加载套路（rootBundle.loadString 73 篇）。
void main() {
  testWidgets('输入关键词：250ms 内无结果，超过 250ms 后出现结果', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(useMaterial3: true, extensions: [AppColors.light]),
        home: const NeijingSearchScreen(),
      ),
    );
    await tester.pump();

    // onTap 触发 _ensureLoaded：tap 后立即 pump，让回调在 runAsync 真实 zone 执行。
    await tester.runAsync(() async {
      await tester.tap(find.byType(TextField));
      await tester.pump();
      await Future.delayed(const Duration(seconds: 3));
    });
    await tester.pump();

    // 输入关键词 → _onChanged 启动 250ms 防抖定时器。
    await tester.enterText(find.byType(TextField), '阴阳');
    await tester.pump(); // 应用 _query（此时 _searchResults 仍为空）

    // 防抖窗口内（已过 200ms < 250ms）：结果应尚未计算 → 命中篇目不可见。
    await tester.pump(const Duration(milliseconds: 200));
    expect(
      find.text('阴阳应象大论'),
      findsNothing,
      reason: '250ms 防抖窗口内不应出现搜索结果',
    );
    expect(find.textContaining('未找到'), findsWidgets,
        reason: '防抖未触发时应显示空态占位');

    // 跨过 250ms 阈值（累计 300ms）→ 定时器触发、结果计算并渲染。
    await tester.pump(const Duration(milliseconds: 100));
    await tester.pump();
    expect(
      find.text('阴阳应象大论'),
      findsOneWidget,
      reason: '超过 250ms 后应渲染防抖计算出的结果',
    );
    expect(find.text('阴阳别论'), findsOneWidget);
  });
}
