import 'package:flutter/material.dart';

import '../data/neijing_lecture_data.dart';
import 'classic_library_screen.dart';

/// 《人纪·黄帝内经》阅读库：按篇浏览 72 篇正文 + 前言。
/// 点按进入 MarkdownDocScreen 渲染原文（正文内已知方剂名自动可点跳方剂详情）。
class NeijingLibraryScreen extends StatelessWidget {
  const NeijingLibraryScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return ClassicLibraryScreen(
      title: '黄帝内经 · 阅读库',
      infoText: '《素问》72 篇 + 前言（倪师讲稿书面整理版）。原文第 25、66-74 篇原稿未收录。',
      footer: '出处：《人纪·黄帝内经》倪师讲稿 · 传统文化参考',
      lectures: kNeiJingLectures,
      seqLabel: (s) => s == 0 ? '序' : '$s',
      seqSubtitle: (s) => s == 0 ? '全书导读' : '第$s篇',
    );
  }
}
