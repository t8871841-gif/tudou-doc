import 'package:flutter_test/flutter_test.dart';
import 'package:sxwnl_spa_dart/sxwnl_spa_dart.dart';
import 'package:nihaisha_app/screens/festival_lookup_screen.dart';

void main() {
  group('农历节日检索', () {
    test('合并两表：按农历月日升序，同一农历日合并、来源合并', () {
      final all = buildFestivalEntries();
      expect(all, isNotEmpty, reason: '两张表非空，合并后不应为空');

      for (var i = 1; i < all.length; i++) {
        final p = all[i - 1], c = all[i];
        expect(p.month < c.month || (p.month == c.month && p.day <= c.day), isTrue,
            reason: '应按农历月日升序排列');
      }

      final mazu = all.where((e) => e.month == 3 && e.day == 23).toList();
      expect(mazu, hasLength(1), reason: '同一农历月日必须合并为一条');
      expect(mazu.single.chaoshan && mazu.single.deity, isTrue,
          reason: '三月廿三 潮汕与玉匣记两表都有 → 双来源');
      expect(mazu.single.names.any((n) => n.contains('妈祖')), isTrue);
      expect(mazu.single.lunarLabel, '三月廿三');
      expect(mazu.single.sourceLabel, '潮汕 · 玉匣记');
    });

    test('按名称过滤「妈祖」→ 命中且每条都含妈祖', () {
      final hits = filterFestivalEntries(buildFestivalEntries(), '妈祖');
      expect(hits, isNotEmpty, reason: '表中确有妈祖条目');
      for (final e in hits) {
        expect(e.names.any((n) => n.contains('妈祖')), isTrue,
            reason: '过滤结果每条都应命中关键字');
      }
    });

    test('农历→公历 可逆：三月廿三 → 公历 → 回推仍是三月廿三', () {
      final solar = lunarToSolar(3, 23, 2026);
      expect(solar, isNotNull, reason: '2026 农历三月廿三应存在');
      final back = LunarDate.fromSolar(
        AstroDateTime(solar!.year, solar.month, solar.day, 12, 0, 0),
      );
      expect(back.month, 3, reason: '回推月份一致');
      expect(back.day, 23, reason: '回推日一致');
      expect(back.isLeap, isFalse, reason: '表键为普通月，不应落在闰月');
    });

    test('越界入参返回 null 而非抛异常', () {
      expect(lunarToSolar(0, 1, 2026), isNull);
      expect(lunarToSolar(13, 1, 2026), isNull);
      expect(lunarToSolar(3, 31, 2026), isNull);
    });
  });
}
