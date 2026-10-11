// 实证农历转换正确性：computeBaZiPaipan / calculateZiweiChart 必须给出正确的出生农历展示串。
//
// 锚点均为公开常识（春节 / 端午），期望串为「干支年（公历年）月日」样式：
//   '{干支}({公历年})年{农历月名}{农历日名}'  （如「甲辰（2024）年正月初一」）
// 来源于 lib/services/bazi_service.dart / lib/services/ziwei_engine.dart 中的：
//   final lunar = LunarDate.fromSolar(AstroDateTime(y, m, d, 12, 0, 0));
//   final lunarText =
//       '${_lunarYearGanZhi(lunar.lunarYear)}（${solar.year}）年 '
//       '${lunar.monthNameStr}${lunar.dayName}';
//
// 农历日（初一/十五…）由日历日决定（与出生时刻、真太阳时开关无关），
// 故直接传 DateTime(y, m, d) 即可。
import 'package:flutter_test/flutter_test.dart';

import 'package:nihaisha_app/services/bazi_service.dart';
import 'package:nihaisha_app/services/ziwei_engine.dart';
import 'package:ziwei_core/ziwei_core.dart' show Gender;

/// 期望 lunarText 形如「甲辰（2024）年正月初一」的干支格式。
final RegExp _ganZhiDate = RegExp(
    r'^[甲乙丙丁戊己庚辛壬癸][子丑寅卯辰巳午未申酉戌亥]（\d{4}）年.+月.+$');

void main() {
  group('出生农历 lunarText 锚点验证', () {
    test('2024-02-10 → 甲辰（2024）年正月初一（甲辰年春节）', () {
      final r = computeBaZiPaipan(DateTime(2024, 2, 10));
      expect(r.lunarText, '甲辰（2024）年正月初一',
          reason: '2024-02-10 应为甲辰（2024）年正月初一');
    });

    test('2023-01-22 → 癸卯（2023）年正月初一（癸卯年春节）', () {
      final r = computeBaZiPaipan(DateTime(2023, 1, 22));
      expect(r.lunarText, '癸卯（2023）年正月初一',
          reason: '2023-01-22 应为癸卯（2023）年正月初一');
    });

    test('2024-06-10 → 甲辰（2024）年五月初五（端午）', () {
      final r = computeBaZiPaipan(DateTime(2024, 6, 10));
      expect(r.lunarText, '甲辰（2024）年五月初五',
          reason: '2024-06-10 应为甲辰（2024）年五月初五');
    });
  });

  group('lunarText 防御性断言（防回归）', () {
    test('任一日期 lunarText 非空且为干支「年 月 日」格式', () {
      for (final d in [
        DateTime(2024, 2, 10),
        DateTime(2023, 1, 22),
        DateTime(2024, 6, 10),
        DateTime(2000, 12, 31),
        DateTime(1990, 8, 8),
      ]) {
        final r = computeBaZiPaipan(d);
        expect(r.lunarText, isNotEmpty, reason: 'lunarText 不应为空: $d');
        expect(r.lunarText, matches(_ganZhiDate),
            reason: 'lunarText 应为干支「年月日」格式: $d');
      }
    });

    test('bazi / guansha 同源 computeBaZiPaipan：lunarText 非空且稳定', () {
      // 两条调用链均直接消费 computeBaZiPaipan().lunarText，
      // 只要本函数产出非空即证明两条链路都非空。
      final r = computeBaZiPaipan(DateTime(2024, 2, 10));
      expect(r.lunarText, isNotNull);
      expect(r.lunarText, isNotEmpty);
      expect(r.lunarText, matches(_ganZhiDate));

      // 日支现已与时刻无关（日历日干支），真太阳时开关不应改变农历展示串。
      final r2 = computeBaZiPaipan(DateTime(2024, 2, 10),
          useTrueSolarTime: false);
      expect(r2.lunarText, r.lunarText, reason: '真太阳时开关不应改变农历展示');
    });
  });

  group('lunarText 时辰无关性（日支由日历日决定，与出生时辰/真太阳时无关）', () {
    // 锚点：日支现由日历日（Julian-Day）推算，与出生时刻、真太阳时开关无关。
    // 同一公历日的 0:00/6:00/12:00/18:00/23:00，且 useTrueSolarTime 取 true/false，
    // lunarText 必须全部等于期望串；任一不一致即代表日支重新耦合到出生时辰（回归）。
    const List<int> _hours = [0, 6, 12, 18, 23];
    const List<bool> _solarFlags = [true, false];

    test('2024-02-10 全天任意时辰 → 甲辰（2024）年正月初一', () {
      const expected = '甲辰（2024）年正月初一';
      for (final hh in _hours) {
        for (final useTst in _solarFlags) {
          final r = computeBaZiPaipan(DateTime(2024, 2, 10, hh, 0),
              useTrueSolarTime: useTst);
          expect(r.lunarText, expected,
              reason: '2024-02-10 $hh:00 (useTrueSolarTime=$useTst) 应为$expected');
        }
      }
    });

    test('2023-01-22 全天任意时辰 → 癸卯（2023）年正月初一', () {
      const expected = '癸卯（2023）年正月初一';
      for (final hh in _hours) {
        for (final useTst in _solarFlags) {
          final r = computeBaZiPaipan(DateTime(2023, 1, 22, hh, 0),
              useTrueSolarTime: useTst);
          expect(r.lunarText, expected,
              reason: '2023-01-22 $hh:00 (useTrueSolarTime=$useTst) 应为$expected');
        }
      }
    });

    test('2024-06-10 全天任意时辰 → 甲辰（2024）年五月初五', () {
      const expected = '甲辰（2024）年五月初五';
      for (final hh in _hours) {
        for (final useTst in _solarFlags) {
          final r = computeBaZiPaipan(DateTime(2024, 6, 10, hh, 0),
              useTrueSolarTime: useTst);
          expect(r.lunarText, expected,
              reason: '2024-06-10 $hh:00 (useTrueSolarTime=$useTst) 应为$expected');
        }
      }
    });
  });

  group('紫微屏 ZiweiChart.lunarText（与八字屏同源新格式）', () {
    test('2024-02-10 → 甲辰（2024）年正月初一', () {
      final chart = calculateZiweiChart(
        solar: DateTime(2024, 2, 10),
        gender: Gender.male,
      );
      expect(chart.lunarText, '甲辰（2024）年正月初一',
          reason: '2024-02-10 紫微屏应为 甲辰（2024）年正月初一');
    });

    test('2023-01-22 → 癸卯（2023）年正月初一', () {
      final chart = calculateZiweiChart(
        solar: DateTime(2023, 1, 22),
        gender: Gender.male,
      );
      expect(chart.lunarText, '癸卯（2023）年正月初一',
          reason: '2023-01-22 紫微屏应为 癸卯（2023）年正月初一');
    });

    test('2024-06-10 → 甲辰（2024）年五月初五', () {
      final chart = calculateZiweiChart(
        solar: DateTime(2024, 6, 10),
        gender: Gender.male,
      );
      expect(chart.lunarText, '甲辰（2024）年五月初五',
          reason: '2024-06-10 紫微屏应为 甲辰（2024）年五月初五');
    });
  });
}
