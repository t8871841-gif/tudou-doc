import 'package:flutter_test/flutter_test.dart';
import 'package:nihaisha_app/services/city_location_service.dart';

/// P1 修复验证：`CityLocationService.parseJson` 对缺失 lng/lat 的条目
/// 不得抛异常，坐标应降级为 0.0（原实现 `(m['lng'] as num).toDouble()` 会抛
/// TypeError，导致整份 JSON 解析失败）。
void main() {
  group('CityLocationService.parseJson — 缺失经纬度稳健性（P1）', () {
    const jsonWithMissingCoords = '''
[
  {"name":"北京市","province":"北京市","lng":116.407526,"lat":39.904030},
  {"name":"神秘城","province":"未知省"}
]
''';

    test('解析含缺失 lng/lat 的列表不抛异常', () {
      expect(
        () => CityLocationService.parseJson(jsonWithMissingCoords),
        returnsNormally,
      );
    });

    test('缺失 lng/lat 的条目坐标降级为 0.0，正常条目不受影响', () {
      final list = CityLocationService.parseJson(jsonWithMissingCoords);
      expect(list.length, 2);

      final normal = list[0];
      expect(normal.name, '北京市');
      expect(normal.lng, closeTo(116.407526, 1e-6));
      expect(normal.lat, closeTo(39.904030, 1e-6));

      final broken = list[1];
      expect(broken.name, '神秘城');
      expect(broken.lng, 0.0);
      expect(broken.lat, 0.0);
    });

    test('缺失 coords 的条目仍可参与搜索与展示', () {
      final list = CityLocationService.parseJson(jsonWithMissingCoords);
      final hit = CityLocationService.searchIn(list, '神秘');
      expect(hit.single.name, '神秘城');
      expect(hit.single.displayName, '神秘城（未知省）');
    });

    test('lat 存在而 lng 缺失时，仅缺失项降级为 0.0', () {
      const json = '[{"name":"半边城","province":"","lat":30.0}]';
      final c = CityLocationService.parseJson(json).single;
      expect(c.lng, 0.0);
      expect(c.lat, 30.0);
    });
  });
}
