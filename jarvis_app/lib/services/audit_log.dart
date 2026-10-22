import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';

/// 操作审计日志（v3）：每次任务的关键动作、核验结果，本地持久化，可查看 / 清空。
///
/// 一行一条 JSON（audit.jsonl，App 私有目录），最多保留 [maxEntries] 条。
/// 脱敏：验证码 / 密码 / 长数字串在写盘前被替换，**绝不落盘原文**；
/// 看屏的“输入”动作只记“输入了 N 个字”，不记内容。
class AuditEntry {
  final DateTime time;
  final String task; // 任务描述（用户原话，已脱敏）
  final String action; // 做了什么
  final bool? ok; // 核验结果：true 成功 / false 失败 / null 仅记录
  final String detail;
  AuditEntry(this.time, this.task, this.action, this.ok, this.detail);

  Map<String, dynamic> toJson() => {
        't': time.toIso8601String(),
        'task': task,
        'a': action,
        'ok': ok,
        'd': detail,
      };

  factory AuditEntry.fromJson(Map<String, dynamic> j) => AuditEntry(
      DateTime.tryParse(j['t']?.toString() ?? '') ?? DateTime.now(),
      j['task']?.toString() ?? '',
      j['a']?.toString() ?? '',
      j['ok'] is bool ? j['ok'] as bool : null,
      j['d']?.toString() ?? '');
}

class AuditLog {
  static const maxEntries = 500;
  static String currentTask = '';
  static File? _file;

  /// 脱敏：验证码 / 密码 / 银行卡长数字。
  static String redact(String s) {
    var t = s;
    t = t.replaceAllMapped(
        RegExp(r'(验证码|校验码|动态码|密码|口令|pin)\s*[是为:：]?\s*\S{3,12}', caseSensitive: false),
        (m) => '${m.group(1)}[已隐藏]');
    t = t.replaceAll(RegExp(r'\d{6,}'), '[数字已隐藏]');
    return t.length > 200 ? '${t.substring(0, 200)}…' : t;
  }

  static Future<File> _f() async {
    final f = _file;
    if (f != null) return f;
    final dir = await getApplicationDocumentsDirectory();
    return _file = File('${dir.path}/audit.jsonl');
  }

  static void beginTask(String goal) => currentTask = redact(goal);

  /// 记一条动作。任何异常都吞掉：审计不能拖垮主流程。
  static Future<void> log(String action, {bool? ok, String detail = ''}) async {
    try {
      final e = AuditEntry(DateTime.now(), currentTask, redact(action), ok, redact(detail));
      final f = await _f();
      await f.writeAsString('${jsonEncode(e.toJson())}\n', mode: FileMode.append, flush: true);
      if (await f.length() > 400 * 1024) await _trim(f);
    } catch (_) {}
  }

  static Future<void> _trim(File f) async {
    final lines = await f.readAsLines();
    if (lines.length <= maxEntries) return;
    await f.writeAsString('${lines.sublist(lines.length - maxEntries).join('\n')}\n');
  }

  /// 新的在前。
  static Future<List<AuditEntry>> load() async {
    try {
      final f = await _f();
      if (!await f.exists()) return [];
      final out = <AuditEntry>[];
      for (final l in await f.readAsLines()) {
        if (l.trim().isEmpty) continue;
        try {
          out.add(AuditEntry.fromJson(jsonDecode(l) as Map<String, dynamic>));
        } catch (_) {}
      }
      return out.reversed.toList();
    } catch (_) {
      return [];
    }
  }

  static Future<void> clear() async {
    try {
      final f = await _f();
      if (await f.exists()) await f.delete();
    } catch (_) {}
  }
}
