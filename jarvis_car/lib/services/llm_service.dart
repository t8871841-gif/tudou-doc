import 'dart:convert';

import 'package:http/http.dart' as http;

import 'settings_store.dart';

/// OpenAI-compatible chat completions client.
/// Talks to whatever base URL / model the user configured in Settings.
/// Default: DeepSeek, deepseek-chat. No key is ever hard-coded.
class LlmService {
  static Future<String> ask({
    required CarSettings settings,
    required String question,
    required List<Map<String, String>> history,
  }) async {
    if (!settings.configured) {
      throw StateError('未配置 API Key，请先在设置里填写 DeepSeek Key。');
    }
    final base = settings.baseUrl.trim().replaceAll(RegExp(r'/+$'), '');
    final uri = Uri.parse('$base/chat/completions');
    final persona = settings.systemPrompt.trim().isEmpty
        ? CarSettings.defaultSystemPrompt
        : settings.systemPrompt.trim();
    final messages = <Map<String, String>>[
      {'role': 'system', 'content': persona},
      ...history,
      {'role': 'user', 'content': question},
    ];
    final resp = await http
        .post(
          uri,
          headers: {
            'Authorization': 'Bearer ${settings.apiKey.trim()}',
            'Content-Type': 'application/json',
          },
          body: jsonEncode({
            'model': settings.model.trim().isEmpty
                ? CarSettings.defaultModel
                : settings.model.trim(),
            'messages': messages,
            'temperature': 0.7,
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
    final content =
        ((choices.first as Map)['message'] as Map)['content']?.toString().trim() ?? '';
    if (content.isEmpty) throw StateError('模型未返回文字内容。');
    return content
        .replaceAll('**', '')
        .replaceAll(RegExp(r'^#{1,6}\s?', multiLine: true), '')
        .replaceAll(RegExp(r'^[-*]\s+', multiLine: true), '· ')
        .replaceAll('*', '');
  }
}
