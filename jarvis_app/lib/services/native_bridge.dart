import 'package:flutter/services.dart';

/// 原生系统能力桥（Kotlin：SystemBridge.kt）。所有调用失败都返回 null/false，不抛异常。
class NativeBridge {
  static const _ch = MethodChannel('jarvis/system');

  /// 注册原生 → Dart 的入口事件（默认助手唤起 / 磁贴 / 提醒点击 / 晨报点击）。
  static void init({
    required void Function(Map<String, dynamic>) onLaunch,
    void Function()? onOrbTap,
  }) {
    _ch.setMethodCallHandler((call) async {
      if (call.method == 'onLaunchAction' && call.arguments is Map) {
        onLaunch(Map<String, dynamic>.from(call.arguments as Map));
      } else if (call.method == 'onOrbEvent' && call.arguments is Map) {
        if ((call.arguments as Map)['type'] == 'tap') onOrbTap?.call();
      }
      return null;
    });
  }

  /// Dart 侧就绪后调用：原生会把启动时暂存的入口事件投递过来。
  static Future<void> ready() => _call<bool>('ready');

  static Future<T?> _call<T>(String m, [Map<String, dynamic>? a]) async {
    try {
      return await _ch.invokeMethod<T>(m, a);
    } catch (_) {
      return null;
    }
  }

  // 常驻
  static Future<bool> startKeepAlive() async => (await _call<bool>('startKeepAlive')) ?? false;
  static Future<void> stopKeepAlive() => _call<bool>('stopKeepAlive');
  static Future<bool> isIgnoringBattery() async => (await _call<bool>('isIgnoringBattery')) ?? false;
  static Future<void> requestIgnoreBattery() => _call<bool>('requestIgnoreBattery');

  // 提醒 / 晨报（原生 AlarmManager.setAlarmClock，熄屏、Doze 都准点）
  static Future<bool> scheduleReminder(int id, int atMs, String title, String body) async =>
      (await _call<bool>('scheduleReminder', {'id': id, 'atMs': atMs, 'title': title, 'body': body})) ?? false;
  static Future<void> cancelAllReminders() => _call<bool>('cancelAllReminders');
  static Future<List<Map<String, dynamic>>> listReminders() async {
    final r = await _call<List<dynamic>>('listReminders');
    return [for (final x in r ?? const []) Map<String, dynamic>.from(x as Map)];
  }

  static Future<bool> scheduleDailyBrief(int hour, int minute) async =>
      (await _call<bool>('scheduleDailyBrief', {'hour': hour, 'minute': minute})) ?? false;
  static Future<void> cancelDailyBrief() => _call<bool>('cancelDailyBrief');

  // 系统控制
  static Future<bool> setTimer(int seconds, String label) async =>
      (await _call<bool>('setTimer', {'seconds': seconds, 'label': label})) ?? false;
  /// action: up | down | max | min | mute | unmute | set | get；返回当前音量百分比，失败 -1。
  static Future<int> volume(String action, [int percent = 0]) async =>
      (await _call<int>('volume', {'action': action, 'percent': percent})) ?? -1;
  /// action: play | pause | next | prev
  static Future<bool> mediaKey(String action) async =>
      (await _call<bool>('mediaKey', {'action': action})) ?? false;
  /// kind: wifi | airplane | bluetooth | location
  static Future<bool> openPanel(String kind) async =>
      (await _call<bool>('openPanel', {'kind': kind})) ?? false;
  /// 返回 ok | need_access | error
  static Future<String> setDnd(bool on) async =>
      (await _call<String>('setDnd', {'on': on})) ?? 'error';
  static Future<void> openDndAccess() => _call<bool>('openDndAccess');

  // 感知
  static Future<Map<String, dynamic>?> getLocation() async {
    final r = await _call<Map<dynamic, dynamic>>('getLocation');
    return r == null ? null : Map<String, dynamic>.from(r);
  }

  static Future<List<Map<String, dynamic>>?> queryCalendar(int startMs, int endMs) async {
    final r = await _call<List<dynamic>>('queryCalendar', {'startMs': startMs, 'endMs': endMs});
    if (r == null) return null;
    return [for (final x in r) Map<String, dynamic>.from(x as Map)];
  }

  static Future<bool> notificationAccessEnabled() async =>
      (await _call<bool>('notificationAccessEnabled')) ?? false;
  static Future<void> openNotificationAccess() => _call<bool>('openNotificationAccess');
  static Future<List<Map<String, dynamic>>> notifications([int limit = 20]) async {
    final r = await _call<List<dynamic>>('getNotifications', {'limit': limit});
    return [for (final x in r ?? const []) Map<String, dynamic>.from(x as Map)];
  }

  // 系统 TTS 兜底（Edge 不可用时）
  static Future<bool> ttsSpeak(String text, double rate, double pitch) async =>
      (await _call<bool>('ttsSpeak', {'text': text, 'rate': rate, 'pitch': pitch})) ?? false;
  static Future<void> ttsStop() => _call<bool>('ttsStop');

  // 悬浮球（无障碍覆盖层）。orbSetEnabled 返回 ok | need_service（无障碍服务没开）
  static Future<String> orbSetEnabled(bool on) async =>
      (await _call<String>('orbSetEnabled', {'on': on})) ?? 'error';
  static Future<void> orbState(String state) => _call<bool>('orbState', {'state': state});
  static Future<void> orbBubble(String text) => _call<bool>('orbBubble', {'text': text});
  static Future<void> openAccessibilitySettings() => _call<bool>('openAccessibilitySettings');

  // 入口
  static Future<void> openAssistantSettings() => _call<bool>('openAssistantSettings');
  static Future<void> setUserWhitelist(List<String> pkgs) =>
      _call<bool>('setUserWhitelist', {'packages': pkgs});
}
