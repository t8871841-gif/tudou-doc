import 'llm_service.dart';
import 'settings_store.dart';

/// 来电 / 短信 / 通知播报（v3 第 7 项）。原生通知监听把通知推给 Dart，这里决定播不播、播什么。
///
/// 规则：总开关 → 来源开关（短信 / 电话 / 微信 / 自定义包名）→ 勿扰时段不播 → 联系人白名单（可选）
/// → 验证码类只提示“有验证码通知”，**不读内容**（沿用 v2）→ 过长的用 LLM（优先本地）压成一句。
/// 局限：通知只有在 Jarvis 进程活着（常驻开启）时才能被播报；来电靠拨号器的来电通知识别，
/// 不同 ROM 的拨号器包名 / 通知格式不同，未适配的需要在“额外包名”里补。
class AnnounceService {
  static const smsPkgs = {
    'com.android.mms', 'com.android.messaging', 'com.google.android.apps.messaging',
    'com.meizu.mms', 'com.samsung.android.messaging',
  };
  static const callPkgs = {
    'com.android.incallui', 'com.android.dialer', 'com.google.android.dialer',
    'com.meizu.flyme.incallui', 'com.samsung.android.incallui', 'com.android.server.telecom',
  };
  static const wxPkg = 'com.tencent.mm';
  static final _secret = RegExp('验证码|校验码|动态码|动态密码|code', caseSensitive: false);

  static String _lastKey = '';
  static DateTime _lastAt = DateTime.fromMillisecondsSinceEpoch(0);

  static int _min(String hhmm, int d) {
    final p = hhmm.split(':');
    if (p.length != 2) return d;
    return (int.tryParse(p[0]) ?? 0) * 60 + (int.tryParse(p[1]) ?? 0);
  }

  /// 勿扰时段（支持跨午夜，如 22:00–07:00）。
  static bool inQuietHours(JarvisSettings s, [DateTime? now]) {
    final n = now ?? DateTime.now();
    final cur = n.hour * 60 + n.minute;
    final a = _min(s.quietStart, 22 * 60), b = _min(s.quietEnd, 7 * 60);
    if (a == b) return false;
    return a < b ? (cur >= a && cur < b) : (cur >= a || cur < b);
  }

  static Set<String> _allowedPkgs(JarvisSettings s) => {
        if (s.announceSms) ...smsPkgs,
        if (s.announceCall) ...callPkgs,
        if (s.announceWeChat) wxPkg,
        ...s.announceExtraPkgs.split(RegExp(r'[\s,;]+')).where((e) => e.contains('.')),
      };

  /// 返回要播的文字；null = 不播。
  static Future<String?> decide(JarvisSettings s, Map<String, dynamic> ev) async {
    if (!s.announceOn) return null;
    final pkg = ev['pkg']?.toString() ?? '';
    if (!_allowedPkgs(s).contains(pkg)) return null;
    if (inQuietHours(s)) return null;
    final app = ev['app']?.toString() ?? '';
    final title = (ev['title']?.toString() ?? '').trim();
    final text = (ev['text']?.toString() ?? '').trim();
    final key = '$pkg|$title|$text';
    final now = DateTime.now();
    if (key == _lastKey && now.difference(_lastAt).inSeconds < 8) return null; // 去重
    _lastKey = key;
    _lastAt = now;

    final senders = s.announceSenders
        .split(RegExp(r'[，,;；\s]+'))
        .where((e) => e.isNotEmpty)
        .toList();
    if (senders.isNotEmpty && !senders.any((n) => title.contains(n))) return null;

    if (callPkgs.contains(pkg)) {
      return '来电：${title.isEmpty ? '未知号码' : title}。';
    }
    if (_secret.hasMatch('$title $text')) {
      return '${app.isEmpty ? '' : '$app '}收到一条验证码类通知，内容已隐藏。';
    }
    var body = text;
    if (body.length > 50) {
      try {
        body = await LlmService.ask(
          settings: s,
          question: '用一句 25 字以内的话概括这条消息，只输出概括：$text',
          history: const [],
          withMemory: false,
          purpose: LlmPurpose.brief,
        ).timeout(const Duration(seconds: 6));
      } catch (_) {
        body = '${body.substring(0, 40)}…';
      }
    }
    return '${title.isEmpty ? app : title}${title.isEmpty ? '' : '（$app）'}：$body';
  }
}
