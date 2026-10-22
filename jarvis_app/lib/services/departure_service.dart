import 'dart:math' as math;

import 'package:permission_handler/permission_handler.dart';

import 'native_bridge.dart';
import 'settings_store.dart';
import 'weather_service.dart';

/// 日程出发提醒（v3 第 7 项，简化版 / 降级）。
///
/// 读未来 24 小时内“有地点”的日程，在开始前提前提醒“该出发了”：
///  - 有定位权限且地点能被地理编码 → 按直线距离 × 1.3 绕行系数 ÷ 30km/h 粗估路程，再加 10 分钟缓冲；
///  - 否则 → 按设置里的固定提前量，并在提醒文字里标明“按固定提前量”。
/// 没有路径规划接口，估算只是粗估，不是导航级 ETA。
/// 排程发生在 Jarvis 运行时（启动 / 晨报 / 改设置）：当天一次都没打开过 Jarvis 就不会排。
class DepartureService {
  static Future<int> schedule(JarvisSettings s) async {
    if (!s.departRemind) return 0;
    if (!(await Permission.calendar.status).isGranted) return 0;
    final now = DateTime.now();
    final ev = await NativeBridge.queryCalendar(
            now.millisecondsSinceEpoch, now.add(const Duration(hours: 24)).millisecondsSinceEpoch) ??
        const [];
    (double, double)? here;
    if ((await Permission.locationWhenInUse.status).isGranted) {
      final r = await NativeBridge.getLocation();
      if (r != null && r['lat'] != null) {
        here = ((r['lat'] as num).toDouble(), (r['lon'] as num).toDouble());
      }
    }
    var n = 0;
    for (final e in ev) {
      final loc = (e['location'] ?? '').toString().trim();
      if (loc.isEmpty || e['allDay'] == true) continue;
      final begin = DateTime.fromMillisecondsSinceEpoch((e['begin'] as num).toInt());
      var lead = s.departLeadMin;
      var how = '按固定提前量';
      if (here != null) {
        final dest = await WeatherService.geocode(loc);
        if (dest != null) {
          final km = _haversineKm(here.$1, here.$2, dest.$1, dest.$2);
          lead = (km * 1.3 / 30 * 60).round().clamp(10, 120) + 10;
          how = '按直线距离粗估路程';
        }
      }
      final at = begin.subtract(Duration(minutes: lead));
      if (!at.isAfter(now.add(const Duration(minutes: 1)))) continue;
      final id = 3000000 + ('${e['title']}${e['begin']}'.hashCode.abs() % 800000);
      final hh = begin.hour.toString().padLeft(2, '0');
      final mm = begin.minute.toString().padLeft(2, '0');
      if (await NativeBridge.scheduleReminder(
          id, at.millisecondsSinceEpoch, '该出发了', '${e['title']} $hh:$mm 开始，地点：$loc（提前 $lead 分钟，$how）')) {
        n++;
      }
    }
    return n;
  }

  static double _haversineKm(double la1, double lo1, double la2, double lo2) {
    const r = 6371.0;
    double rad(double d) => d * math.pi / 180;
    final dLa = rad(la2 - la1), dLo = rad(lo2 - lo1);
    final a = math.pow(math.sin(dLa / 2), 2) +
        math.cos(rad(la1)) * math.cos(rad(la2)) * math.pow(math.sin(dLo / 2), 2);
    return 2 * r * math.asin(math.sqrt(a));
  }
}
