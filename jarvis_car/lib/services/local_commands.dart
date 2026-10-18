import 'package:flutter/services.dart';

/// 车机本地指令：不联网，秒回。匹配不上时返回 null，交给云端闲聊。
/// 打开 App / 导航 / 拨号走原生端（jarvis_car/launcher 通道），
/// 用 PackageManager.getLaunchIntentForPackage 按包名启动，不走隐式意图。
class LocalCommands {
  static const _launcher = MethodChannel('jarvis_car/launcher');

  /// 音乐 App 包名，按顺序尝试，装了哪个开哪个。
  static const _musicPkgs = [
    'com.android.music',
    'com.netease.cloudmusic',
    'com.tencent.qqmusic',
    'com.kugou.android',
    'com.tencent.qqmusiccar',
  ];

  /// Returns reply text if the command was handled locally,
  /// null to fall through to cloud chat.
  static Future<String?> tryHandle(String text) async {
    final t = text.trim();
    if (t.isEmpty) return null;

    // 时间：现在几点 / 几点了 / 时间
    if (t.contains('现在几点') ||
        t.contains('几点了') ||
        t == '时间' ||
        t.contains('现在时间')) {
      final now = DateTime.now();
      return '现在是${now.hour}点${now.minute.toString().padLeft(2, '0')}分。';
    }

    // 播放音乐
    if (t.contains('播放音乐') ||
        t.contains('放音乐') ||
        t.contains('来首歌') ||
        t.contains('放首歌') ||
        t.contains('放点音乐') ||
        t.contains('播放歌曲')) {
      for (final pkg in _musicPkgs) {
        if (await _openApp(pkg)) return '已打开音乐。';
      }
      return '没找到音乐 App。';
    }

    // 打开导航（无目的地）
    if (t.contains('打开导航') || t == '导航') {
      if (await _openMapApp()) return '已打开导航。';
      return '没装导航 App。';
    }

    // 导航去 X
    final navMatch = RegExp(r'导航(?:去|到|往)?(.+)').firstMatch(t);
    if (navMatch != null) {
      final dest = _cleanDest(navMatch.group(1) ?? '');
      if (dest.isNotEmpty) {
        if (await _openNavigation(dest)) return '正在导航去$dest。';
        return '没装导航 App。';
      }
    }

    // 去 X（“去火车站”）
    if (t.startsWith('去') && t.length > 1 && t.length <= 14) {
      final dest = _cleanDest(t.substring(1));
      if (dest.isNotEmpty &&
          !dest.contains('吗') &&
          !dest.contains('哪') &&
          !dest.contains('怎么')) {
        if (await _openNavigation(dest)) return '正在导航去$dest。';
        return '没装导航 App。';
      }
    }

    // 打电话：车机一般没有通讯录，按打开拨号盘处理。
    if (t.contains('打电话')) {
      if (await _dial()) return '已打开拨号盘。';
      return '打不开拨号盘。';
    }

    // 打开 X
    final openMatch = RegExp(r'^打开(.+?)(应用|App|app)?$').firstMatch(t);
    if (openMatch != null) {
      final name = openMatch.group(1)!.trim();
      final reply = await _openNamedApp(name);
      if (reply != null) return reply;
    }

    return null;
  }

  static String _cleanDest(String s) =>
      s.trim().replaceAll(RegExp(r'[。！？!?\s]+$'), '');

  static Future<bool> _openApp(String pkg) async {
    try {
      final ok = await _launcher
          .invokeMethod<bool>('openApp', {'package': pkg});
      return ok == true;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> _openMapApp() async =>
      await _openApp('com.autonavi.minimap') ||
      await _openApp('com.baidu.BaiduMap');

  static Future<bool> _openNavigation(String dest) async {
    try {
      final ok = await _launcher
          .invokeMethod<bool>('openNavigation', {'destination': dest});
      return ok == true;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> _dial() async {
    try {
      final ok = await _launcher.invokeMethod<bool>('dial');
      return ok == true;
    } catch (_) {
      return false;
    }
  }

  static Future<String?> _openNamedApp(String name) async {
    switch (name) {
      case '音乐':
        for (final pkg in _musicPkgs) {
          if (await _openApp(pkg)) return '已打开音乐。';
        }
        return '没找到音乐 App。';
      case '导航':
      case '高德':
      case '高德地图':
        if (await _openApp('com.autonavi.minimap')) return '已打开导航。';
        return '没装导航 App。';
      case '百度地图':
        if (await _openApp('com.baidu.BaiduMap')) return '已打开百度地图。';
        return '没装百度地图。';
      case '电话':
      case '拨号':
      case '拨号盘':
        if (await _dial()) return '已打开拨号盘。';
        return '打不开拨号盘。';
      case '设置':
        if (await _openApp('com.android.settings')) return '已打开设置。';
        return '打不开设置。';
      default:
        return null; // 不认识的名字，交给云端闲聊
    }
  }
}
