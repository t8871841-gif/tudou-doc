import 'dart:convert';

import 'package:http/http.dart' as http;

import 'settings_store.dart';

/// 联网搜索。优先级：
///  1. 设置里填了搜索 Key → 博查（Bocha）兼容接口，中文结果好；
///  2. 免 Key → 中文维基百科（百科类问题）；
///  3. 最后兜底 DuckDuckGo 即时答案。
/// 全部网络都不通才抛异常（让任务黑板如实记失败）；查到 0 条是正常结果，返回说明文字。
class WebSearchService {
  static const _ua = {'User-Agent': 'Mozilla/5.0 (Linux; Android 13) Jarvis/2'};

  static Future<String> search(String query, {JarvisSettings? settings}) async {
    final errors = <String>[];
    final key = settings?.searchApiKey.trim() ?? '';
    if (key.isNotEmpty) {
      try {
        final r = await _bocha(query, key, settings!.searchEndpoint);
        if (r.isNotEmpty) return r;
      } catch (e) {
        errors.add('搜索接口：$e');
      }
    }
    try {
      final r = await _wikipedia(query);
      if (r.isNotEmpty) return r;
    } catch (e) {
      errors.add('维基百科：$e');
    }
    try {
      final r = await _duck(query);
      if (r.isNotEmpty) return r;
    } catch (e) {
      errors.add('DuckDuckGo：$e');
    }
    if (errors.length >= 2 && errors.length >= (key.isNotEmpty ? 3 : 2)) {
      throw Exception(errors.join('；'));
    }
    return '联网检索完成，没有找到关于“$query”的可靠结果'
        '${key.isEmpty ? '（未配置搜索 Key，只能查百科类内容；可在设置里填写）' : ''}。';
  }

  static Future<String> _bocha(String q, String key, String endpoint) async {
    final url = endpoint.trim().isEmpty
        ? JarvisSettings.defaultSearchEndpoint
        : endpoint.trim();
    final resp = await http
        .post(Uri.parse(url),
            headers: {
              'Authorization': 'Bearer $key',
              'Content-Type': 'application/json',
            },
            body: jsonEncode(
                {'query': q, 'freshness': 'noLimit', 'summary': true, 'count': 5}))
        .timeout(const Duration(seconds: 20));
    if (resp.statusCode != 200) throw Exception('HTTP ${resp.statusCode}');
    final j = jsonDecode(utf8.decode(resp.bodyBytes));
    final data = (j is Map && j['data'] is Map) ? j['data'] as Map : j as Map;
    final pages = ((data['webPages'] as Map?)?['value'] as List?) ?? const [];
    final sb = StringBuffer();
    var n = 0;
    for (final p in pages) {
      if (p is! Map) continue;
      final body = (p['summary'] ?? p['snippet'] ?? '').toString().trim();
      if (body.isEmpty) continue;
      n++;
      sb.writeln('[$n] ${p['name'] ?? ''}：${_cut(body, 220)}（${p['url'] ?? ''}）');
      if (n >= 4) break;
    }
    return sb.toString().trim();
  }

  static Future<String> _wikipedia(String q) async {
    final u = Uri.parse('https://zh.wikipedia.org/w/api.php?action=query&list=search'
        '&srsearch=${Uri.encodeQueryComponent(q)}&format=json&srlimit=3&utf8=1');
    final resp = await http.get(u, headers: _ua).timeout(const Duration(seconds: 12));
    if (resp.statusCode != 200) throw Exception('HTTP ${resp.statusCode}');
    final j = jsonDecode(utf8.decode(resp.bodyBytes)) as Map;
    final list = ((j['query'] as Map?)?['search'] as List?) ?? const [];
    final sb = StringBuffer();
    var n = 0;
    for (final x in list) {
      if (x is! Map) continue;
      final snip = _strip((x['snippet'] ?? '').toString());
      if (snip.isEmpty) continue;
      n++;
      sb.writeln('[$n] ${x['title']}：${_cut(snip, 200)}');
    }
    return sb.toString().trim();
  }

  static Future<String> _duck(String q) async {
    final u = Uri.parse('https://api.duckduckgo.com/?q=${Uri.encodeComponent(q)}'
        '&format=json&no_html=1&skip_disambig=1');
    final resp = await http.get(u, headers: _ua).timeout(const Duration(seconds: 12));
    if (resp.statusCode != 200) throw Exception('HTTP ${resp.statusCode}');
    final j = jsonDecode(utf8.decode(resp.bodyBytes)) as Map;
    final a = (j['AbstractText'] ?? '').toString();
    if (a.isNotEmpty) return a;
    for (final t in (j['RelatedTopics'] as List?) ?? const []) {
      if (t is Map && t['Text'] != null) return t['Text'].toString();
    }
    return '';
  }

  /// 读网页正文（去标签，截断），给大模型做摘要用。
  static Future<String> readPage(String url) async {
    final u = Uri.tryParse(url.trim());
    if (u == null || !(u.scheme == 'http' || u.scheme == 'https')) {
      throw Exception('网址无效：$url');
    }
    final resp = await http.get(u, headers: _ua).timeout(const Duration(seconds: 20));
    if (resp.statusCode != 200) throw Exception('HTTP ${resp.statusCode}');
    var h = utf8.decode(resp.bodyBytes, allowMalformed: true);
    h = h
        .replaceAll(RegExp(r'<(script|style|noscript)[\s\S]*?</\1>', caseSensitive: false), ' ')
        .replaceAll(RegExp(r'<[^>]+>'), ' ');
    final t = _strip(h).replaceAll(RegExp(r'\s+'), ' ').trim();
    if (t.isEmpty) throw Exception('网页没有可读正文');
    return _cut(t, 2500);
  }

  static String _strip(String s) => s
      .replaceAll(RegExp(r'<[^>]+>'), '')
      .replaceAll('&quot;', '"')
      .replaceAll('&amp;', '&')
      .replaceAll('&nbsp;', ' ')
      .replaceAll('&lt;', '<')
      .replaceAll('&gt;', '>');

  static String _cut(String s, int n) => s.length > n ? '${s.substring(0, n)}…' : s;
}
