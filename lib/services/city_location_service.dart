import 'dart:convert';

import 'package:flutter/services.dart' show rootBundle;

/// 中文城市经纬度（WGS-84）数据模型。
///
/// 数据源：PyGeoCN（地级市多边形质心，CGCS2000≈WGS-84）为主 +
/// liaorui/geo-city（BD-09 已转 WGS-84）补充。用于紫微排盘「真太阳时」地点选择。
class CityLocation {
  final String name; // 中文名（如 北京市 / 澄海区 / 东京）
  final String province; // 省份 / 国家（如 广东省、日本），可能为空
  final double lng; // 经度（东经为正，WGS-84）
  final double lat; // 纬度（北纬为正，WGS-84）
  final String? city; // 所属地级市 / 一级行政区（如 汕头市、东京都），市级条目为 null
  final String? en; // 英文名（国外数据才有，如 Tokyo），国内条目为 null

  const CityLocation({
    required this.name,
    required this.province,
    required this.lng,
    required this.lat,
    this.city,
    this.en,
  });

  /// 展示名：
  /// - 国外（en 非空）：国家·省/州·市，如 阿布维尔（美国·阿拉巴马州）；
  /// - 国内区/县条目带所属市括号（如 澄海区（汕头市））；
  /// - 国内市级条目带省份括号（如 北京市（北京市））；否则仅名称。
  String get displayName {
    if (en != null) return '$name（$province·$city）';
    if (city != null && city!.isNotEmpty) return '$name（$city）';
    return province.isNotEmpty ? '$name（$province）' : name;
  }

  /// 出生地存储名（写入 SettingsRepository.lastLocation）：
  /// 国外同 displayName（国家·省/州·市），国内区带上所属市、市保持原名。
  String get label {
    if (en != null) return '$name（$province·$city）';
    return city != null ? '$name（$city）' : name;
  }

  @override
  String toString() => displayName;
}

/// 出生地检索范围：国内（市/区 + 港澳台）/ 国外。
///
/// 两套数据必须**分开加载、分开搜索**，绝不合并进同一列表。
enum PlaceScope { domestic, world }

/// 中文城市经纬度检索服务（按 [PlaceScope] 各自独立缓存）。
class CityLocationService {
  CityLocationService._();

  /// 各 scope 的独立缓存（null 表示尚未加载）。
  static final Map<PlaceScope, List<CityLocation>?> _cache = {
    PlaceScope.domestic: null,
    PlaceScope.world: null,
  };

  /// 各 scope 对应的资源路径。
  static const Map<PlaceScope, String> _assetPath = {
    PlaceScope.domestic: 'assets/data/china_cities.json',
    PlaceScope.world: 'assets/data/world_cities.json',
  };

  /// 从内置 JSON 资源按 [scope] 加载并缓存（幂等，可重复调用）。
  /// 默认 [PlaceScope.domestic]，与旧行为一致。
  static Future<List<CityLocation>> load([
    PlaceScope scope = PlaceScope.domestic,
  ]) async {
    final cached = _cache[scope];
    if (cached != null) return cached;
    final raw = await rootBundle.loadString(_assetPath[scope]!);
    final list = parseJson(raw);
    _cache[scope] = list;
    return list;
  }

  /// 纯解析（不依赖资源系统，便于测试注入）。
  static List<CityLocation> parseJson(String raw) {
    final list = jsonDecode(raw) as List<dynamic>;
    return list.map((e) {
      final m = e as Map<String, dynamic>;
      return CityLocation(
        name: m['name'] as String,
        province: (m['province'] as String?) ?? '',
        lng: (m['lng'] as num?)?.toDouble() ?? 0.0,
        lat: (m['lat'] as num?)?.toDouble() ?? 0.0,
        city: m['city'] as String?,
        en: m['en'] as String?,
      );
    }).toList();
  }

  /// 在已加载列表内按中文名/省份/所属市/英文名模糊搜索。
  /// [query] 为空返回前 [limit] 条（便于浏览全部）。
  /// 搜「汕头」可带出澄海区等（区条目含 city 所属市）；
  /// 英文名（[CityLocation.en]）大小写不敏感匹配（如 tokyo / Tokyo 均可）。
  static List<CityLocation> searchIn(
    List<CityLocation> all,
    String query, [
    int limit = 80,
  ]) {
    final q = query.trim();
    if (q.isEmpty) return all.take(limit).toList();
    final qLower = q.toLowerCase();
    return all
        .where((c) =>
            c.name.contains(q) ||
            c.province.contains(q) ||
            (c.city?.contains(q) ?? false) ||
            (c.en?.toLowerCase().contains(qLower) ?? false))
        .take(limit)
        .toList();
  }

  /// 便捷封装：基于已加载缓存按 [scope] 搜索（未加载时返回空）。
  static List<CityLocation> search(
    PlaceScope scope,
    String query, [
    int limit = 80,
  ]) =>
      searchIn(_cache[scope] ?? const [], query, limit);
}

