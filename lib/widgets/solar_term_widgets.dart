import 'package:flutter/material.dart';

/// 节气三候展示（横向 chip 列表，前置绿叶图标）。
Widget phenologyChips(ColorScheme cs, List<String> items) {
  return Wrap(
    spacing: 6,
    runSpacing: 6,
    crossAxisAlignment: WrapCrossAlignment.center,
    children: [
      Icon(Icons.eco_outlined, size: 14, color: cs.primary),
      const SizedBox(width: 4),
      ...items.map(
        (p) => Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
          decoration: BoxDecoration(
            color: cs.primaryContainer,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Text(
            p,
            style: TextStyle(fontSize: 11, color: cs.onPrimaryContainer),
          ),
        ),
      ),
    ],
  );
}

/// 节气详情区块（图标 + 标题 + 正文，tertiaryContainer 背景）。
Widget solarTermBlock(ColorScheme cs, IconData icon, String label, String text) {
  return Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: cs.tertiaryContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 16, color: cs.onTertiaryContainer),
              const SizedBox(width: 6),
              Text(
                label,
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.bold,
                  color: cs.onTertiaryContainer,
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            text,
            style: TextStyle(
              fontSize: 12,
              height: 1.6,
              color: cs.onTertiaryContainer,
            ),
          ),
        ],
      ),
    ),
  );
}
