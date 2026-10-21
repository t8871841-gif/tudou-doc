import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import 'local_llm_service.dart';
import 'memory_service.dart';
import 'settings_store.dart';

/// 调用目的：决定走端侧小模型还是云端大模型。
enum LlmPurpose { chat, plan, decide, reason, summary, consolidate, brief }

/// OpenAI 兼容的 chat completions 客户端 + 端侧/云端分流。
///
/// 分流规则（保守，宁可上云也不让小模型胡说）：
///  - 本地：寒暄闲聊类的简单对话（isSimpleChat）、晨报润色；
///  - 云端：规划、看屏决策、联网综合、推理、汇总、记忆整理、一切有知识性/实时性的问题；
///  - 云端失败（断网等）或没配 Key 时，聊天类请求用本地模型兜底。
class LlmService {
  static const _weekdays = ['一', '二', '三', '四', '五', '六', '日'];

  /// 当前时间行：注入每次请求，模型才知道“今天/明天/下周”是哪天。
  static String nowLine([DateTime? t]) {
    final n = t ?? DateTime.now();
    String two(int v) => v.toString().padLeft(2, '0');
    return '当前时间：${n.year}年${n.month}月${n.day}日 星期${_weekdays[n.weekday - 1]} '
        '${two(n.hour)}:${two(n.minute)}（设备本地时间）。涉及“今天/明天/下周”等相对时间时以此为准。';
  }

  /// 去 Markdown 符号，方便语音播报。只处理展示给用户的文本——
  /// 规划 / 决策 / 计算等机器消费的调用必须 plain:false，否则 128*3 会变成 1283。
  static String toPlain(String s) => s
      .replaceAll('**', '')
      .replaceAll(RegExp(r'^#{1,6}\s?', multiLine: true), '')
      .replaceAll(RegExp(r'^[-*]\s+', multiLine: true), '· ');

  static final _knowledge = RegExp(
      r'为什么|怎么|如何|原理|区别|是什么|是谁|多少|哪个|哪里|哪一|历史|公式|代码|编程|写一|分析|总结|对比|推荐|计划|方案|翻译|查|搜|最新|新闻|股|价格|汇率|比分|\d\s*[*+/×÷-]\s*\d');

  /// 简单对话：短、不带知识/实时/任务特征，小模型答得了。
  static bool isSimpleChat(String q) {
    final t = q.trim();
    if (t.isEmpty || t.length > 30) return false;
    if (_knowledge.hasMatch(t)) return false;
    return true;
  }

  static bool shouldUseLocal(
      JarvisSettings s, LlmPurpose p, String question) {
    if (!LocalLlmService.usable(s)) return false;
    switch (p) {
      case LlmPurpose.chat:
        return isSimpleChat(question);
      case LlmPurpose.brief:
        return question.length < 900;
      default:
        return false;
    }
  }

  static String _system(JarvisSettings s, String question, bool withMemory,
      List<Map<String, String>> history) {
    final custom = history.isNotEmpty && history.first['role'] == 'system';
    final persona = s.systemPrompt.trim().isEmpty
        ? JarvisSettings.defaultSystemPrompt
        : s.systemPrompt.trim();
    final mem = withMemory ? MemoryService.instance.contextFor(question) : '';
    return [
      if (!custom) persona,
      nowLine(),
      if (mem.isNotEmpty) mem,
    ].join('\n\n');
  }

  static List<Map<String, String>> _messages(JarvisSettings s, String question,
      List<Map<String, String>> history, bool withMemory) {
    return [
      {'role': 'system', 'content': _system(s, question, withMemory, history)},
      ...history,
      {'role': 'user', 'content': question},
    ];
  }

  static Future<String?> _local(JarvisSettings s, String question,
      List<Map<String, String>> history, bool withMemory) {
    final turns = history.where((m) => m['role'] != 'system').toList();
    return LocalLlmService.generate(s,
        system: _system(s, question, withMemory, history),
        history: turns,
        user: question);
  }

  static Map<String, String> _headers(JarvisSettings s) => {
        'Authorization': 'Bearer ${s.apiKey.trim()}',
        'Content-Type': 'application/json',
      };

  static Uri _uri(JarvisSettings s) {
    final base = s.baseUrl.trim().replaceAll(RegExp(r'/+$'), '');
    return Uri.parse('$base/chat/completions');
  }

  static String _model(JarvisSettings s) =>
      s.model.trim().isEmpty ? JarvisSettings.defaultModel : s.model.trim();

  static Future<String> ask({
    required JarvisSettings settings,
    required String question,
    required List<Map<String, String>> history,
    bool withMemory = true,
    bool plain = true,
    double temperature = 0.7,
    LlmPurpose purpose = LlmPurpose.chat,
  }) async {
    // 1) 简单任务：本地小模型；失败静默回退云端。
    if (shouldUseLocal(settings, purpose, question)) {
      final t = await _local(settings, question, history, withMemory);
      if (t != null) return plain ? toPlain(t) : t;
    }
    // 2) 没配云端 Key：能本地兜底的聊天就本地答。
    if (!settings.configured) {
      if (purpose == LlmPurpose.chat || purpose == LlmPurpose.brief) {
        final t = await _local(settings, question, history, withMemory);
        if (t != null) return toPlain(t);
      }
      throw StateError('未配置 API Key，请先在设置里填写 Key。');
    }
    try {
      final resp = await http
          .post(
            _uri(settings),
            headers: _headers(settings),
            body: jsonEncode({
              'model': _model(settings),
              'messages': _messages(settings, question, history, withMemory),
              'temperature': temperature,
            }),
          )
          .timeout(const Duration(seconds: 90));
      if (resp.statusCode < 200 || resp.statusCode >= 300) {
        final body = utf8.decode(resp.bodyBytes, allowMalformed: true);
        final short = body.length > 240 ? '${body.substring(0, 240)}…' : body;
        throw Exception('服务返回 HTTP ${resp.statusCode}：$short');
      }
      final data =
          jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
      final choices = data['choices'] as List?;
      if (choices == null || choices.isEmpty) {
        throw StateError('模型未返回内容。');
      }
      final content = ((choices.first as Map)['message'] as Map)['content']
              ?.toString()
              .trim() ??
          '';
      if (content.isEmpty) throw StateError('模型未返回文字内容。');
      return plain ? toPlain(content) : content;
    } catch (e) {
      // 3) 云端失败（断网等）：聊天类用本地模型兜底，并如实标注。
      if (purpose == LlmPurpose.chat || purpose == LlmPurpose.brief) {
        final t = await _local(settings, question, history, withMemory);
        if (t != null) return '（云端暂不可用，以下由本地小模型回答）${toPlain(t)}';
      }
      rethrow;
    }
  }

  /// 流式回答：按增量吐文字（已去 Markdown 符号）。
  /// 简单任务走本地（整段一次吐出）；云端不支持流式 / 失败时自动回退。
  static Stream<String> askStream({
    required JarvisSettings settings,
    required String question,
    required List<Map<String, String>> history,
    bool withMemory = true,
    LlmPurpose purpose = LlmPurpose.chat,
  }) async* {
    if (shouldUseLocal(settings, purpose, question)) {
      final t = await _local(settings, question, history, withMemory);
      if (t != null) {
        yield toPlain(t);
        return;
      }
    }
    if (!settings.configured) {
      yield await ask(
          settings: settings,
          question: question,
          history: history,
          withMemory: withMemory,
          purpose: purpose);
      return;
    }
    final client = http.Client();
    var raw = '';
    var emitted = '';
    var got = false;
    Object? failure;
    try {
      final req = http.Request('POST', _uri(settings))
        ..headers.addAll({..._headers(settings), 'Accept': 'text/event-stream'})
        ..body = jsonEncode({
          'model': _model(settings),
          'messages': _messages(settings, question, history, withMemory),
          'temperature': 0.7,
          'stream': true,
        });
      final resp = await client.send(req).timeout(const Duration(seconds: 30));
      if (resp.statusCode < 200 || resp.statusCode >= 300) {
        final body = await resp.stream.bytesToString();
        final short = body.length > 240 ? '${body.substring(0, 240)}…' : body;
        throw Exception('服务返回 HTTP ${resp.statusCode}：$short');
      }
      final lines = resp.stream
          .transform(utf8.decoder)
          .transform(const LineSplitter())
          .timeout(const Duration(seconds: 60));
      await for (final line in lines) {
        final l = line.trim();
        if (!l.startsWith('data:')) continue;
        final payload = l.substring(5).trim();
        if (payload == '[DONE]') break;
        String? delta;
        try {
          final j = jsonDecode(payload) as Map<String, dynamic>;
          final ch = (j['choices'] as List?)?.firstOrNull as Map?;
          delta = ((ch?['delta'] as Map?)?['content'] ??
                  (ch?['message'] as Map?)?['content'])
              ?.toString();
        } catch (_) {
          continue;
        }
        if (delta == null || delta.isEmpty) continue;
        got = true;
        raw += delta;
        // 去 Markdown 符号后的“稳定前缀”才吐出（末尾留 2 字符防 ** 被切开）。
        final clean = toPlain(raw);
        final safeEnd = clean.length > 2 ? clean.length - 2 : 0;
        if (safeEnd > emitted.length && clean.startsWith(emitted)) {
          final out = clean.substring(emitted.length, safeEnd);
          emitted = clean.substring(0, safeEnd);
          yield out;
        }
      }
    } catch (e) {
      failure = e;
    } finally {
      client.close();
    }
    if (got) {
      final clean = toPlain(raw);
      var i = 0;
      while (i < emitted.length && i < clean.length && emitted[i] == clean[i]) {
        i++;
      }
      if (i < clean.length) yield clean.substring(i);
      if (failure != null && clean.trim().isEmpty) throw failure;
      return;
    }
    // 没拿到任何增量（服务不支持流式 / 失败）：回退一次性请求。
    yield await ask(
        settings: settings,
        question: question,
        history: history,
        withMemory: withMemory,
        purpose: purpose);
  }
}
