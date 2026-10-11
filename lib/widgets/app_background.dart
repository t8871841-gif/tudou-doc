import 'dart:io';

import 'package:flutter/material.dart';

import '../data/settings_repository.dart';

/// App 全局背景：用户在设置中选择自定义图片后，所有页面底部显示该图片。
/// 为保证文字可读，图片上会叠一层半透明遮罩。
class AppBackground extends StatelessWidget {
  final Widget? child;
  const AppBackground({super.key, this.child});

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: SettingsRepository.instance,
      builder: (context, _) {
        final path = SettingsRepository.instance.backgroundImagePath;
        final file = path != null ? File(path) : null;
        if (file == null || !file.existsSync()) {
          return child ?? const SizedBox.shrink();
        }
        final dark = Theme.of(context).brightness == Brightness.dark;
        return Stack(
          fit: StackFit.expand,
          children: [
            Image.file(file, fit: BoxFit.cover),
            // 遮罩保证内容可读：深色模式压暗，浅色模式提亮
            Container(
              color: dark
                  ? Colors.black.withValues(alpha: 0.55)
                  : Colors.white.withValues(alpha: 0.45),
            ),
            if (child != null) child!,
          ],
        );
      },
    );
  }
}
