import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:nihaisha_app/screens/ziwei_chart_screen.dart';
import 'package:nihaisha_app/theme/app_colors.dart';

/// P2 返修复验：紫微排盘「选择出生城市」弹窗（`ziwei_chart_screen.dart` 的
/// 私有 `_CitySearchDialog`；`TextEditingController` 由弹窗自身 State 持有、
/// 只在 `State.dispose()` 销毁）端到端行为。
///
/// 验证：打开 → 默认列出国内城市 → 输入关键字可过滤 → 点选后 `showDialog`
/// 返回正确的 `CityLocation`（宿主按钮标签随之更新），且开合全程无异常。
void main() {
  setUpAll(() {
    // 选中城市会经 SettingsRepository 落库，需 ffi 版 sqflite 工厂。
    databaseFactory = databaseFactoryFfi;
  });

  testWidgets('城市搜索弹窗：打开→默认国内→过滤→选中返回正确城市', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(useMaterial3: true, extensions: [AppColors.light]),
        home: const ZiweiChartScreen(),
      ),
    );
    await tester.pump();

    // 定位「选择出生城市（中文搜索）」按钮（可能在视口外）。
    final entry = find.text('选择出生城市（中文搜索）');
    if (entry.evaluate().isEmpty) {
      await tester.scrollUntilVisible(
        entry,
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.pumpAndSettle();
    }
    expect(entry, findsOneWidget);

    // tap → _openCityPicker() await CityLocationService.load(domestic)（真实 asset IO）。
    await tester.runAsync(() async {
      await tester.tap(entry);
      await tester.pump();
      await Future<void>.delayed(const Duration(seconds: 2));
    });
    await tester.pumpAndSettle();

    // 弹窗打开，默认列出国内城市（首屏可见北京相关条目）。
    expect(find.text('选择出生城市'), findsOneWidget);
    final dialog = find.byType(AlertDialog);
    // 仅匹配结果条目（ListTile 标题），排除搜索框 hintText 中的「北京」。
    Finder tileContaining(String s) => find.descendant(
          of: find.byType(ListTile),
          matching: find.textContaining(s),
        );
    expect(tileContaining('北京'), findsWidgets, reason: '默认应列出国内城市（含北京）');

    // 输入关键字过滤（国内为同步搜索）。
    final dialogField =
        find.descendant(of: dialog, matching: find.byType(TextField));
    await tester.enterText(dialogField, '杭州');
    await tester.pump();

    expect(tileContaining('杭州'), findsWidgets, reason: '关键字应命中杭州');
    expect(tileContaining('北京'), findsNothing, reason: '过滤后不匹配的北京条目应消失');

    // 点选杭州市 → Navigator.pop(context, city)。
    await tester.tap(find.text('杭州市（浙江省）'));
    await tester.pumpAndSettle();
    // 宿主恢复后异步 setLastLocation 落库（真实 IO），放行真实事件循环。
    await tester.runAsync(() async {
      await Future<void>.delayed(const Duration(milliseconds: 500));
    });
    await tester.pump();

    // 弹窗关闭，宿主按钮标签更新为所选城市（证明返回的 CityLocation 正确）。
    expect(find.text('选择出生城市'), findsNothing, reason: '弹窗应已关闭');
    expect(find.text('杭州市（浙江省）'), findsWidgets,
        reason: '宿主按钮应显示所选城市');
    expect(tester.takeException(), isNull, reason: '开合全程不应抛异常');
  });
}
