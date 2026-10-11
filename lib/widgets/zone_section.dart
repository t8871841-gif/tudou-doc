import 'package:flutter/material.dart';
import '../theme/app_colors.dart';

/// 工具页分区枚举：通用 / 中医 / 玄学。
enum Zone { general, tcm, metaphysics }

/// 工具页（ToolsScreen）中一组功能卡片的可视化分区容器。
///
/// 每个分区带一个彩色标题（图标 + 文案）与一层轻微着色背景，
/// 使「通用区 / 中医区 / 玄学区」在视觉上彼此独立、层次清晰。
class ZoneSection extends StatelessWidget {
  final Zone zone;
  final String title;
  final IconData icon;
  final List<Widget> children;

  const ZoneSection({
    super.key,
    required this.zone,
    required this.title,
    required this.icon,
    required this.children,
  });

  /// 根据分区类型返回对应的主题色。
  Color _color(BuildContext context) {
    switch (zone) {
      case Zone.general:
        return Theme.of(context).colorScheme.primary;
      case Zone.tcm:
        return context.colors.tcmZone;
      case Zone.metaphysics:
        return context.colors.metaphysicsZone;
    }
  }

  @override
  Widget build(BuildContext context) {
    final color = _color(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(4, 10, 4, 6),
          child: Row(
            children: [
              Icon(icon, color: color, size: 20),
              const SizedBox(width: 8),
              Text(
                title,
                style: TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.bold,
                  color: color,
                ),
              ),
            ],
          ),
        ),
        Container(
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.07),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: color.withValues(alpha: 0.22)),
          ),
          padding: const EdgeInsets.all(8),
          child: Column(children: children),
        ),
        const SizedBox(height: 12),
      ],
    );
  }
}
