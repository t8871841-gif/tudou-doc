import 'dart:convert';

import 'package:http/http.dart' as http;

/// 天气：Open-Meteo（免 Key），城市名走它的地理编码；失败兜底 wttr.in。
class WeatherService {
  static const _wmo = {
    0: '晴', 1: '大部晴朗', 2: '多云', 3: '阴', 45: '有雾', 48: '冻雾',
    51: '小毛毛雨', 53: '毛毛雨', 55: '大毛毛雨', 56: '冻毛毛雨', 57: '强冻毛毛雨',
    61: '小雨', 63: '中雨', 65: '大雨', 66: '冻雨', 67: '强冻雨',
    71: '小雪', 73: '中雪', 75: '大雪', 77: '雪粒',
    80: '小阵雨', 81: '阵雨', 82: '强阵雨', 85: '小阵雪', 86: '大阵雪',
    95: '雷暴', 96: '雷暴伴小冰雹', 99: '雷暴伴大冰雹',
  };
  static String desc(num? c) => _wmo[c?.toInt()] ?? '天气未知';

  static Future<String> byCity(String city) async {
    try {
      final g = Uri.parse('https://geocoding-api.open-meteo.com/v1/search'
          '?name=${Uri.encodeQueryComponent(city)}&count=1&language=zh&format=json');
      final gr = await http.get(g).timeout(const Duration(seconds: 12));
      final res = (jsonDecode(utf8.decode(gr.bodyBytes)) as Map)['results'] as List?;
      if (res != null && res.isNotEmpty) {
        final r = res.first as Map;
        return byCoords((r['latitude'] as num).toDouble(),
            (r['longitude'] as num).toDouble(), (r['name'] ?? city).toString());
      }
    } catch (_) {}
    return _wttr(city);
  }

  static Future<String> byCoords(double lat, double lon, String label) async {
    final u = Uri.parse('https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon'
        '&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m'
        '&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max'
        '&timezone=auto&forecast_days=3');
    final r = await http.get(u).timeout(const Duration(seconds: 15));
    if (r.statusCode != 200) throw Exception('天气服务 HTTP ${r.statusCode}');
    final j = jsonDecode(utf8.decode(r.bodyBytes)) as Map;
    final c = j['current'] as Map;
    final d = j['daily'] as Map;
    final sb = StringBuffer()
      ..writeln('$label 现在 ${c['temperature_2m']}°C（体感 ${c['apparent_temperature']}°C），'
          '${desc(c['weather_code'] as num?)}，湿度 ${c['relative_humidity_2m']}%，风速 ${c['wind_speed_10m']} km/h。');
    const names = ['今天', '明天', '后天'];
    final codes = d['weather_code'] as List;
    for (var i = 0; i < codes.length && i < 3; i++) {
      sb.writeln('${names[i]}：${desc(codes[i] as num?)}，'
          '${(d['temperature_2m_min'] as List)[i]}~${(d['temperature_2m_max'] as List)[i]}°C，'
          '降水概率 ${(d['precipitation_probability_max'] as List)[i]}%。');
    }
    return sb.toString().trim();
  }

  static Future<String> _wttr(String city) async {
    final u = Uri.parse('https://wttr.in/${Uri.encodeComponent(city)}?format=j1&lang=zh');
    final r = await http.get(u).timeout(const Duration(seconds: 15));
    if (r.statusCode != 200) throw Exception('天气服务 HTTP ${r.statusCode}');
    final j = jsonDecode(utf8.decode(r.bodyBytes)) as Map;
    final c = (j['current_condition'] as List).first as Map;
    final w = (c['lang_zh'] as List?)?.first ?? (c['weatherDesc'] as List).first;
    final sb = StringBuffer()
      ..writeln('$city 现在 ${c['temp_C']}°C（体感 ${c['FeelsLikeC']}°C），'
          '${(w as Map)['value']}，湿度 ${c['humidity']}%，风速 ${c['windspeedKmph']} km/h。');
    const names = ['今天', '明天', '后天'];
    final days = j['weather'] as List;
    for (var i = 0; i < days.length && i < 3; i++) {
      final x = days[i] as Map;
      sb.writeln('${names[i]}：${x['mintempC']}~${x['maxtempC']}°C。');
    }
    return sb.toString().trim();
  }
}
