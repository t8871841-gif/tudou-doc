import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nihaisha_app/screens/minggua_calculator_screen.dart';
import 'package:nihaisha_app/screens/minggua_library_screen.dart';
import 'package:nihaisha_app/theme/app_colors.dart';

/// 与真实 App 一致：MaterialApp 须注册 AppColors 主题扩展，
/// 否则屏幕内 `context.colors`（`Theme.of(ctx).extension<AppColors>()!`）会因扩展缺失抛 null。
Widget _app(Widget home) => MaterialApp(
      theme: ThemeData(useMaterial3: true, extensions: [AppColors.light]),
      home: home,
    );

void main() {
  testWidgets('四柱命卦计算器：输入生辰排盘出先天/后天卦', (tester) async {
    await tester.pumpWidget(_app(const MingGuaCalculatorScreen()));

    expect(find.text('四柱命卦'), findsOneWidget);
    expect(find.text('生辰信息'), findsOneWidget);

    await tester.ensureVisible(find.text('排四柱命卦'));
    await tester.tap(find.text('排四柱命卦'));
    await tester.pumpAndSettle();

    // 八字 + 先天/后天卦（默认 1995-08-16 巳时 男 → 山雷颐/雷山小过）
    await tester.scrollUntilVisible(find.text('先天卦（前半生）'), 300);
    expect(find.text('先天卦（前半生）'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('后天卦（后半生·天旋地转）'), 300);
    expect(find.text('后天卦（后半生·天旋地转）'), findsOneWidget);
    expect(find.textContaining('山雷颐'), findsWidgets);
  });

  testWidgets('生辰输入改为「时+分」（与八字/紫微同口径）', (tester) async {
    await tester.pumpWidget(_app(const MingGuaCalculatorScreen()));

    // 旧版 4 个下拉（年/月/日/时辰）；新版 5 个（年/月/日/时/分）。
    expect(find.byType(DropdownButton<int>), findsNWidgets(5));

    // 默认 10:00 → 巳时，与改动前默认（_shiChenIndex=5 即 10:00）等价。
    expect(find.text('时辰：巳时'), findsOneWidget);

    // 把「时」改成 12 → 午时（证明下拉真的驱动了时辰推导）。
    final hourDropdown = find.byType(DropdownButton<int>).at(3); // 年/月/日/时/分
    await tester.ensureVisible(hourDropdown);
    await tester.tap(hourDropdown);
    await tester.pumpAndSettle();
    await tester.tap(find.text('12').last);
    await tester.pumpAndSettle();

    expect(find.text('时辰：午时'), findsOneWidget);
  });

  testWidgets('四柱命卦讲义库渲染三区块', (tester) async {
    await tester.pumpWidget(_app(const MingGuaLibraryScreen()));

    expect(find.text('四柱命卦讲义'), findsOneWidget);
    expect(find.text('排法（算法源）'), findsOneWidget);
    // 批卦补充 同时出现在区块标题与条目
    expect(find.text('批卦补充'), findsAtLeastNWidgets(1));
    expect(find.text('八字排列方法（四柱命卦算法）'), findsOneWidget);

    await tester.scrollUntilVisible(
        find.text('64 卦 · 先天/后天/值年卦批解'), 400);
    expect(find.text('64 卦 · 先天/后天/值年卦批解'), findsOneWidget);
  });
}
