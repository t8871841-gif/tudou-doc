import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import '../models/acupuncture.dart';

class AcupunctureRepository {
  static List<AcupunctureCategory> _categories = [];
  static List<PenetrationEntry> _penetrations = [];
  static bool _loaded = false;

  static Future<void> load() async {
    if (_loaded) return;
    try {
      final jsonStr = await rootBundle.loadString('assets/data/acupuncture.json');
      final data = json.decode(jsonStr) as Map<String, dynamic>;

      final acupunctureData = data['acupuncture'] as Map<String, dynamic>? ?? {};
      final categoriesList =
          acupunctureData['categories'] as List<dynamic>? ?? const [];
      _categories = categoriesList
          .map((e) => AcupunctureCategory.fromJson(e as Map<String, dynamic>))
          .toList();

      final penetrationList = data['penetration'] as List<dynamic>? ?? const [];
      _penetrations = penetrationList
          .map((e) => PenetrationEntry.fromJson(e as Map<String, dynamic>))
          .toList();
    } catch (e, st) {
      debugPrint('[AcupunctureRepository] 加载 acupuncture.json 失败，降级为空：$e\n$st');
      _categories = const [];
      _penetrations = const [];
    }
    _loaded = true;
  }

  static List<AcupunctureCategory> getAll() => _categories;

  static List<AcupointEntry> getEntries() {
    return _categories.expand((c) => c.entries).toList();
  }

  static List<AcupointEntry> getByCategory(String categoryName) {
    if (categoryName == '全部') return getEntries();
    final cat = _categories.where((c) => c.name == categoryName);
    return cat.isNotEmpty ? cat.first.entries : [];
  }

  static List<String> getCategories() {
    return _categories.map((c) => c.name).toList();
  }

  static List<PenetrationEntry> getPenetrations() => _penetrations;

  static List<AcupointEntry> search(String query) {
    if (query.isEmpty) return getEntries();
    final q = query.toLowerCase();
    return getEntries().where((e) {
      if (e.symptom.toLowerCase().contains(q)) return true;
      if (e.aliases.any((a) => a.toLowerCase().contains(q))) return true;
      if (e.acupoints.any((a) => a.name.toLowerCase().contains(q))) return true;
      return false;
    }).toList();
  }

  static List<PenetrationEntry> searchPenetrations(String query) {
    if (query.isEmpty) return _penetrations;
    final q = query.toLowerCase();
    return _penetrations.where((p) {
      if (p.name.toLowerCase().contains(q)) return true;
      if (p.indications.any((i) => i.toLowerCase().contains(q))) return true;
      return false;
    }).toList();
  }
}
