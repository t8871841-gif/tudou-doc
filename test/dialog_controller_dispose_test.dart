import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 回归测试：弹窗内 TextEditingController 的销毁时机。
///
/// 缺陷背景（旧写法）：在调用点 `await showDialog(...)` 之后立刻 `controller.dispose()`。
/// 由于 `showDialog` 返回的 Future 在 `pop()` 当帧即完成（早于路由退场动画），
/// 若退场期间弹窗内容因 `MediaQuery.viewInsets` 变化（软键盘收起）而重建，
/// 就会读到已销毁的 controller，抛
/// “A TextEditingController was used after being disposed.”。
///
/// 修法：controller 移入弹窗自身的 State，在 `State.dispose()` 中销毁——
/// 该 dispose 晚于退场动画，天然安全。
///
/// 本测试用 `ValueNotifier<double>` 驱动 `MediaQuery.viewInsets`（模拟键盘弹出→收起），
/// 覆盖两种情形：
///   1) 正确写法（controller 由弹窗 State 持有）→ 无异常；
///   2) 旧写法（调用点 dispose）→ 能稳定复现异常，证明本探针确实能捕获该缺陷。
void main() {
  testWidgets('正确写法：controller 由弹窗 State 销毁 → 退场期间无异常',
      (tester) async {
    final insets = ValueNotifier<double>(0);
    addTearDown(insets.dispose);

    await tester.pumpWidget(
      _ProbeApp(insets: insets, home: const _CorrectLauncher()),
    );

    await tester.tap(find.text('打开弹窗'));
    await tester.pumpAndSettle();

    // 模拟软键盘弹出。
    insets.value = 300;
    await tester.pump();

    // 点击「确定」后立刻模拟软键盘收起（真实路径：打字→确定→键盘收起）。
    await tester.tap(find.text('确定'));
    insets.value = 0;
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
  });

  testWidgets('旧写法（调用点 dispose）→ 探针能复现「使用已销毁 controller」异常',
      (tester) async {
    final insets = ValueNotifier<double>(0);
    addTearDown(insets.dispose);

    // 旧写法会连续抛多条异常（含框架断言），故用 FlutterError.onError 全量收集，
    // 避免单条 takeException 之后仍遗留 pending 异常导致测试失败。
    final errors = <FlutterErrorDetails>[];
    final previousOnError = FlutterError.onError;
    FlutterError.onError = errors.add;
    addTearDown(() => FlutterError.onError = previousOnError);

    await tester.pumpWidget(
      _ProbeApp(insets: insets, home: const _LegacyLauncher()),
    );

    await tester.tap(find.text('打开弹窗'));
    await tester.pumpAndSettle();

    insets.value = 300;
    await tester.pump();

    await tester.tap(find.text('确定'));
    insets.value = 0;
    await tester.pumpAndSettle();

    expect(
      errors.any((d) =>
          d.exception.toString().contains('used after being disposed')),
      isTrue,
      reason: '旧写法应在退场动画期间因使用已销毁 controller 而抛异常',
    );
  });
}

/// 把 `MediaQuery.viewInsets.bottom` 绑定到 [insets]，模拟软键盘弹出 / 收起。
class _ProbeApp extends StatelessWidget {
  final ValueNotifier<double> insets;
  final Widget home;

  const _ProbeApp({required this.insets, required this.home});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: home,
      builder: (context, child) => ValueListenableBuilder<double>(
        valueListenable: insets,
        builder: (context, bottom, _) {
          final base = MediaQueryData.fromView(View.of(context));
          return MediaQuery(
            data: base.copyWith(viewInsets: EdgeInsets.only(bottom: bottom)),
            child: child!,
          );
        },
      ),
    );
  }
}

/// 正确写法：controller 由弹窗 State 持有，并在 `State.dispose()` 中销毁。
class _CorrectLauncher extends StatelessWidget {
  const _CorrectLauncher();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: ElevatedButton(
          onPressed: () => showDialog<void>(
            context: context,
            builder: (_) => const _CorrectDialog(),
          ),
          child: const Text('打开弹窗'),
        ),
      ),
    );
  }
}

class _CorrectDialog extends StatefulWidget {
  const _CorrectDialog();

  @override
  State<_CorrectDialog> createState() => _CorrectDialogState();
}

class _CorrectDialogState extends State<_CorrectDialog> {
  final TextEditingController _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('新建文件夹'),
      content: TextField(
        controller: _controller,
        autofocus: true,
        decoration: const InputDecoration(hintText: '输入名称'),
      ),
      actions: [
        FilledButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('确定'),
        ),
      ],
    );
  }
}

/// 旧写法（有时间缺陷，仅用于证明探针有效）：
/// 在调用点 `await showDialog(...)` 之后立刻 dispose。
class _LegacyLauncher extends StatelessWidget {
  const _LegacyLauncher();

  Future<void> _open(BuildContext context) async {
    final controller = TextEditingController();
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('新建文件夹'),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(hintText: '输入名称'),
        ),
        actions: [
          FilledButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('确定'),
          ),
        ],
      ),
    );
    controller.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: ElevatedButton(
          onPressed: () => _open(context),
          child: const Text('打开弹窗'),
        ),
      ),
    );
  }
}
