import 'dart:convert';

import 'package:http/http.dart' as http;

import 'audit_log.dart';
import 'native_bridge.dart';
import 'settings_store.dart';

/// 视觉看屏（v3 第 4 项）：截图 + 多模态模型。
///
/// 与节点树感知的关系：**节点树优先**（AgentLoop 先读无障碍树、先按文字点击）；
/// 树读不到 / 读不全 / 找不到目标时才用这里兜底。
/// 隐私：截图只发给用户在设置里配置的视觉模型接口（默认复用主模型配置）；
/// 前台是银行 / 支付 / 钱包 / 系统设置类 App、Jarvis 自己、或无法确认前台时，原生层直接拒绝截图。
/// 降级：没开视觉 / 没配 Key → 全部返回“未启用”，调用方回到纯节点树行为，不影响现有功能。
class VisionBlocked implements Exception {
  final String reason;
  final String pkg;
  VisionBlocked(this.reason, this.pkg);
  @override
  String toString() => message;

  String get message {
    switch (reason) {
      case 'self':
        return 'Jarvis 自己在前台，截到的只会是我自己的界面。请切到要看的界面，再点悬浮球或按电源键叫我。';
      case 'blocked_app':
        return '当前界面（$pkg）属于银行 / 支付 / 钱包 / 系统设置类，为保护你的隐私，Jarvis 不会截图，也不会上传。';
      case 'unknown_app':
        return '无法确认前台是哪个应用，为安全起见不截图。';
      case 'secure_window':
        return '这个界面设置了防截屏保护，系统不允许截图。';
      case 'unsupported':
        return '截图需要 Android 11 及以上。';
      case 'service_off':
        return '无障碍服务没开，没法截图。请在设置里开启“Jarvis 跨应用全自动引擎”。';
      default:
        return '截图失败了，请稍后再试。';
    }
  }
}

class ScreenShot {
  final String b64;
  final int w, h; // 发给模型的图尺寸
  final int sw, sh; // 真实屏幕像素
  final String pkg;
  final String sig;
  ScreenShot(this.b64, this.w, this.h, this.sw, this.sh, this.pkg, this.sig);
}

class VisionPoint {
  final double x, y; // 屏幕像素
  final String desc;
  VisionPoint(this.x, this.y, this.desc);
}

class VisionDecision {
  final String action; // tap | scroll_down | scroll_up | back | done
  final VisionPoint? point;
  final String expect;
  final String reason;
  VisionDecision(this.action, this.point, this.expect, this.reason);
}

class VisionService {
  static bool usable(JarvisSettings s) => s.visionConfigured;

  static Future<ScreenShot> capture() async {
    final m = await NativeBridge.captureScreen();
    final err = m['error']?.toString();
    if (err != null) throw VisionBlocked(err, m['pkg']?.toString() ?? '');
    return ScreenShot(
      m['b64'] as String,
      (m['w'] as num).toInt(),
      (m['h'] as num).toInt(),
      (m['sw'] as num).toInt(),
      (m['sh'] as num).toInt(),
      m['pkg']?.toString() ?? '',
      m['sig']?.toString() ?? '',
    );
  }

  static Future<String> _ask(JarvisSettings s, ScreenShot shot, String prompt,
      {int maxTokens = 500, String purpose = ''}) async {
    unawaitedAudit('截图已上传到视觉模型', '应用 ${shot.pkg}，用途：$purpose');
    final base = s.visionBase.trim().replaceAll(RegExp(r'/+$'), '');
    final resp = await http
        .post(
          Uri.parse('$base/chat/completions'),
          headers: {
            'Authorization': 'Bearer ${s.visionKey.trim()}',
            'Content-Type': 'application/json',
          },
          body: jsonEncode({
            'model': s.visionModelName,
            'temperature': 0.1,
            'max_tokens': maxTokens,
            'messages': [
              {
                'role': 'user',
                'content': [
                  {'type': 'text', 'text': prompt},
                  {
                    'type': 'image_url',
                    'image_url': {'url': 'data:image/jpeg;base64,${shot.b64}'}
                  },
                ],
              }
            ],
          }),
        )
        .timeout(const Duration(seconds: 60));
    if (resp.statusCode < 200 || resp.statusCode >= 300) {
      final b = utf8.decode(resp.bodyBytes, allowMalformed: true);
      throw Exception('视觉模型返回 HTTP ${resp.statusCode}：${b.length > 200 ? b.substring(0, 200) : b}。'
          '请确认所配模型支持图片输入。');
    }
    final data = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    final content = (((data['choices'] as List).first as Map)['message'] as Map)['content'];
    final t = content?.toString().trim() ?? '';
    if (t.isEmpty) throw StateError('视觉模型没有返回内容。');
    return t;
  }

  static void unawaitedAudit(String a, String d) {
    AuditLog.log(a, detail: d);
  }

  static Map<String, dynamic>? _json(String t) {
    final i = t.indexOf('{');
    final j = t.lastIndexOf('}');
    if (i < 0 || j <= i) return null;
    try {
      final d = jsonDecode(t.substring(i, j + 1));
      return d is Map<String, dynamic> ? d : null;
    } catch (_) {
      return null;
    }
  }

  static String _coordHint(JarvisSettings s, ScreenShot shot) => s.visionCoord == 'norm1000'
      ? '坐标一律用 0~1000 的归一化值（x 向右、y 向下，左上角为 0,0）。'
      : '截图尺寸是 ${shot.w}x${shot.h} 像素，坐标一律用该截图的像素坐标（左上角为 0,0）。';

  static VisionPoint? _toScreen(JarvisSettings s, ScreenShot shot, dynamic x, dynamic y, String desc) {
    if (x is! num || y is! num) return null;
    double sx, sy;
    if (s.visionCoord == 'norm1000') {
      sx = x / 1000 * shot.sw;
      sy = y / 1000 * shot.sh;
    } else {
      if (x < 0 || y < 0 || x > shot.w || y > shot.h) return null; // 越界 = 模型胡报
      sx = x * shot.sw / shot.w;
      sy = y * shot.sh / shot.h;
    }
    if (sx < 0 || sy < 0 || sx > shot.sw || sy > shot.sh) return null;
    return VisionPoint(sx, sy, desc);
  }

  /// “看一眼屏幕回答问题”。
  static Future<String> ask(JarvisSettings s, String question) async {
    final shot = await capture();
    final r = await _ask(
      s,
      shot,
      '这是用户手机当前屏幕的截图。请用简短、口语化的中文回答用户的问题，不要用 Markdown。'
      '如果问的是某个按钮 / 元素在哪，请描述它在屏幕的位置（如“右下角”“顶部中间”）。\n用户的问题：$question',
      purpose: '回答屏幕问题',
    );
    return LlmPlain.strip(r);
  }

  /// 在截图里找目标元素，返回屏幕坐标。没找到 / 坐标越界返回 null。
  static Future<VisionPoint?> locate(JarvisSettings s, ScreenShot shot, String target) async {
    final r = await _ask(
      s,
      shot,
      '这是手机屏幕截图。找出“$target”这个元素的可点击中心位置。${_coordHint(s, shot)}'
      '只返回 JSON：{"found":true或false,"x":数字,"y":数字,"desc":"你看到的元素描述"}，不要其他文字。',
      maxTokens: 200,
      purpose: '定位“$target”',
    );
    final j = _json(r);
    if (j == null || j['found'] != true) return null;
    return _toScreen(s, shot, j['x'], j['y'], j['desc']?.toString() ?? target);
  }

  /// 纯视觉模式（节点树读不到）：看图决定下一步。
  static Future<VisionDecision> decideNext(JarvisSettings s, ScreenShot shot, String goal,
      String step, String progress) async {
    final r = await _ask(
      s,
      shot,
      '你在帮用户操作手机。总目标：$goal\n当前步骤：$step\n已做动作：${progress.isEmpty ? '（无）' : progress}\n'
      '${_coordHint(s, shot)}看这张截图，决定下一步。只返回 JSON：'
      '{"action":"tap|scroll_down|scroll_up|back|done","x":数字,"y":数字,"target_desc":"点的是什么","expect":"这一步做完后屏幕上应看到什么（简短）","reason":"一句话"}。'
      'tap 必须给坐标；目标已经达成时 action=done，并在 expect 写能证明目标已达成的画面。不要涉及转账、付款、输入密码或验证码。',
      maxTokens: 300,
      purpose: '决定下一步操作',
    );
    final j = _json(r);
    if (j == null) throw StateError('视觉模型的决策不是有效 JSON。');
    final a = j['action']?.toString() ?? '';
    if (!const {'tap', 'scroll_down', 'scroll_up', 'back', 'done'}.contains(a)) {
      throw StateError('视觉决策无效：$a');
    }
    final pt = a == 'tap' ? _toScreen(s, shot, j['x'], j['y'], j['target_desc']?.toString() ?? '') : null;
    if (a == 'tap' && pt == null) throw StateError('视觉决策的坐标无效。');
    return VisionDecision(a, pt, j['expect']?.toString() ?? '', j['reason']?.toString() ?? '');
  }

  /// 核验：屏幕上是否已经出现 / 满足某个描述。
  static Future<bool> verify(JarvisSettings s, ScreenShot shot, String expectDesc) async {
    final r = await _ask(
      s,
      shot,
      '看这张手机截图：屏幕上是否已经出现或满足“$expectDesc”？只返回 JSON：{"ok":true或false}。',
      maxTokens: 60,
      purpose: '核验“$expectDesc”',
    );
    return _json(r)?['ok'] == true;
  }
}

/// 去 Markdown 符号（避免在这里依赖 LlmService 造成循环引用）。
class LlmPlain {
  static String strip(String s) => s
      .replaceAll('**', '')
      .replaceAll(RegExp(r'^#{1,6}\s?', multiLine: true), '')
      .replaceAll(RegExp(r'^[-*]\s+', multiLine: true), '· ');
}
