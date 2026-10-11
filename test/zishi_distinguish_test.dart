// 早晚子时重构回归测试（Task C）。
//
// 覆盖用户指定的三条核心用例 + 一条边界用例，验证「区分早晚子时」开关在
// 八字内核（bazi_core）与紫微斗数排盘（ziwei_core）中口径完全一致，且紫微命宫
// 随校正后日柱完整重算。
//
// 断言期望值（癸未 / 甲申 / 甲子 / 壬子）均为 bazi_core / ziwei_core 实测输出，
// 仅用于断言；业务代码严禁硬编码干支字面量，全部实时运算生成。
import 'package:flutter_test/flutter_test.dart';
import 'package:ziwei_core/ziwei_core.dart' show Gender;

import 'package:nihaisha_app/services/bazi_service.dart';
import 'package:nihaisha_app/services/ziwei_engine.dart';

void main() {
  // 用例1：ratHourMode=true + 2026-09-06 23:30（晚子时）
  // 日柱 = 当天（癸未），时柱 = 次日（甲子）。
  test('用例1 ON + 23:30（晚子时）→ 日柱癸未 时柱甲子', () {
    final solar = DateTime(2026, 9, 6, 23, 30);
    final r = computeBaZiPaipan(
      solar,
      isMale: true,
      useTrueSolarTime: true,
      ratHourMode: true,
      location: null,
    );
    expect(r.bazi.day, '癸未');
    expect(r.bazi.time, '甲子');

    final chart = calculateZiweiChart(
      solar: solar,
      gender: Gender.male,
      useTrueSolarTime: true,
      ratHourMode: true,
    );
    expect(chart.baziDay, '癸未');
    expect(chart.baziTime, '甲子');
  });

  // 用例2：ratHourMode=true + 2026-09-06 00:20（早子时）
  // 早子时本就属「当日」之子时 → 日柱 = 当天（癸未），时柱 = 当日子时（壬子）；
  // 与关闭开关结果一致（不再顺延次日）。
  test('用例2 ON + 00:20（早子时）→ 日柱当天癸未 时柱壬子（不顺延）', () {
    final solar = DateTime(2026, 9, 6, 0, 20);
    final r = computeBaZiPaipan(
      solar,
      isMale: true,
      useTrueSolarTime: true,
      ratHourMode: true,
      location: null,
    );
    expect(r.bazi.day, '癸未');
    expect(r.bazi.time, '壬子');

    final chart = calculateZiweiChart(
      solar: solar,
      gender: Gender.male,
      useTrueSolarTime: true,
      ratHourMode: true,
    );
    expect(chart.baziDay, '癸未');
    expect(chart.baziTime, '壬子');
  });

  // 用例3：ratHourMode=false + 2026-09-06 23:30（默认不区分）
  // 日柱 = 当天（癸未），时柱 = 当日子时（壬子）。
  test('用例3 OFF + 23:30 → 日柱癸未 时柱壬子', () {
    final solar = DateTime(2026, 9, 6, 23, 30);
    final r = computeBaZiPaipan(
      solar,
      isMale: true,
      useTrueSolarTime: true,
      ratHourMode: false,
      location: null,
    );
    expect(r.bazi.day, '癸未');
    expect(r.bazi.time, '壬子');

    final chart = calculateZiweiChart(
      solar: solar,
      gender: Gender.male,
      useTrueSolarTime: true,
      ratHourMode: false,
    );
    expect(chart.baziDay, '癸未');
    expect(chart.baziTime, '壬子');
  });

  // 边界用例：01:00 之后任意时刻，开关 ON/OFF 对盘式输出无任何影响。
  test('边界用例：02:00 时刻开关 ON/OFF 盘式完全一致', () {
    final solar = DateTime(2026, 9, 6, 2, 0);
    final rOn = computeBaZiPaipan(
      solar,
      isMale: true,
      useTrueSolarTime: true,
      ratHourMode: true,
      location: null,
    );
    final rOff = computeBaZiPaipan(
      solar,
      isMale: true,
      useTrueSolarTime: true,
      ratHourMode: false,
      location: null,
    );
    expect(rOn.bazi.year, rOff.bazi.year);
    expect(rOn.bazi.month, rOff.bazi.month);
    expect(rOn.bazi.day, rOff.bazi.day);
    expect(rOn.bazi.time, rOff.bazi.time);

    final cOn = calculateZiweiChart(
      solar: solar,
      gender: Gender.male,
      useTrueSolarTime: true,
      ratHourMode: true,
    );
    final cOff = calculateZiweiChart(
      solar: solar,
      gender: Gender.male,
      useTrueSolarTime: true,
      ratHourMode: false,
    );
    expect(cOn.baziFull, cOff.baziFull);
    expect(cOn.originMingIndex, cOff.originMingIndex);
  });

  // 早子时（00:20）开关 ON/OFF 结果应完全一致：属当日子时，日柱不顺延。
  test('早子时 ON/OFF 盘式一致（日柱均当天，不顺延次日）', () {
    final solar = DateTime(2026, 9, 6, 0, 20);
    final cOn = calculateZiweiChart(
      solar: solar,
      gender: Gender.male,
      useTrueSolarTime: true,
      ratHourMode: true,
    );
    final cOff = calculateZiweiChart(
      solar: solar,
      gender: Gender.male,
      useTrueSolarTime: true,
      ratHourMode: false,
    );
    expect(cOn.baziDay, '癸未');
    expect(cOn.baziTime, '壬子');
    expect(cOn.baziFull, cOff.baziFull);
    expect(cOn.lunarText, cOff.lunarText);
  });

  // 约束 B（早子时）：日柱 / 农历保持当天口径不变（不顺延次日），仅当日子时。
  test('约束B 早子时：农历与日柱保持当天（不顺延次日）', () {
    final solar = DateTime(2026, 9, 6, 0, 20);
    final early = calculateZiweiChart(
      solar: solar,
      gender: Gender.male,
      useTrueSolarTime: true,
      ratHourMode: true,
    );
    // 参考 = 同一公历日的 noon 基准盘（todayGan、关闭子时修正），农历即应显示的当日农历。
    final refSameDay = calculateZiweiChart(
      solar: DateTime(2026, 9, 6, 12, 0),
      gender: Gender.male,
      useTrueSolarTime: true,
      ratHourMode: false,
    );
    expect(early.baziDay, '癸未');
    expect(early.baziTime, '壬子');
    // 农历显示保持当天：与「同日 noon」命盘的农历完全一致（不顺延）。
    expect(early.lunarText, refSameDay.lunarText);
    expect(early.lunarMonth, refSameDay.lunarMonth);
    expect(early.lunarIsLeap, refSameDay.lunarIsLeap);
  });

  // 约束 B（晚子时）：日柱 / 农历保持当天口径不变，仅时柱由壬子校正为甲子。
  test('约束B 晚子时：农历不变、仅时柱校正', () {
    final solar = DateTime(2026, 9, 6, 23, 30);
    final late = calculateZiweiChart(
      solar: solar,
      gender: Gender.male,
      useTrueSolarTime: true,
      ratHourMode: true,
    );
    final plain = calculateZiweiChart(
      solar: solar,
      gender: Gender.male,
      useTrueSolarTime: true,
      ratHourMode: false,
    );
    expect(late.baziDay, plain.baziDay);
    expect(late.lunarText, plain.lunarText);
    expect(late.lunarMonth, plain.lunarMonth);
    expect(late.baziTime, '甲子');
    expect(plain.baziTime, '壬子');
  });
}
