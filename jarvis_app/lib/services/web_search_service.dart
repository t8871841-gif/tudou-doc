import 'dart:convert';
import 'package:http/http.dart' as http;

class WebSearchService {
  /// 使用 DuckDuckGo API 进行实时联网搜索
  static Future<String> search(String query) async {
    try {
      final encoded = Uri.encodeComponent(query);
      final url = Uri.parse('https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1');

      final response = await http.get(url, headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'
      });

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);

        // 尝试获取官方摘要
        String abstractText = data['AbstractText'] ?? '';
        if (abstractText.isNotEmpty) {
          return abstractText;
        }

        // 如果没有直接摘要，从相关主题中提取
        final topics = data['RelatedTopics'] as List<dynamic>?;
        if (topics != null && topics.isNotEmpty) {
          for (var topic in topics) {
            if (topic is Map && topic.containsKey('Text')) {
              return topic['Text'];
            }
          }
        }

        return '联网检索完成，未找到关于 "$query" 的结构化摘要。';
      } else {
        return '联网搜索请求失败（状态码：${response.statusCode}）';
      }
    } catch (e) {
      return '联网搜索出错：$e';
    }
  }
}
