// 用户验证任务回归测试：早晚子时 + 真太阳时逻辑（1990-01-01 00:00 澄海 E116.77）。
//
// 本文件仅守卫「用户实际报告的场景」：真太阳开启时，1990-01-01 00:00 @ 澄海区（东经 116.77°）
// 两种开关取值下的八字 / 紫微排盘与农历口径。农历比对取「年」之后的后缀
// （如「己巳（1990）年十二月初五」→「十二月初五」），因 app 的 lunarText 含干支年 + 括号公历年前缀。
//
// 期望值对照（用户确认，2026-09-10）：
//   不开早晚子时：日柱 丙寅、时柱 戊子、农历 己巳(1990)年十二月初五（出生日锚定，不随真太阳回拨）。
//   开早晚子时  ：日柱 乙丑、时柱 戊子、农历 己巳(1989)年十二月初四（晚子时，农历取校正后时刻所在日）。
//
// 注：app 将农历十二月渲染为「十二月」（腊月为同义俗称），故此处用「十二月初五 / 十二月初四」。
import 'package:flutter_test/flutter_test.dart';
import 'package:ziwei_core/ziwei_core.dart' show Gender, Location;

import 'package:nihaisha_app/services/bazi_service.dart';
import 'package:nihaisha_app/services/ziwei_engine.dart';

void main() {
  ({String day, String time, String lunar}) baziOf(
    DateTime solar, {
    required double lon,
    required bool rat,
    required bool tst,
  }) {
    final p = computeBaZiPaipan(
      solar,
      isMale: true,
      useTrueSolarTime: tst,
      ratHourMode: rat,
      location: Location(lon, 23.47),
    );
    return (day: p.bazi.day, time: p.bazi.time, lunar: p.lunarText);
  }

  ({String day, String time, String lunar}) ziweiOf(
    DateTime solar, {
    required double lon,
    required bool rat,
    required bool tst,
  }) {
    final c = calculateZiweiChart(
      solar: solar,
      gender: Gender.male,
      location: Location(lon, 23.47),
      useTrueSolarTime: tst,
      ratHourMode: rat,
    );
    return (day: c.baziDay, time: c.baziTime, lunar: c.lunarText);
  }

  String lunarSuffix(String lunar) =>
      lunar.contains('年') ? lunar.split('年').last : lunar;

  bool runCase(
    String id,
    DateTime s, {
    required double lon,
    required bool rat,
    required bool tst,
    required String expDay,
    required String expTime,
    required String expLunarSuffix,
    bool isZiwei = false,
  }) {
    final r = isZiwei
        ? ziweiOf(s, lon: lon, rat: rat, tst: tst)
        : baziOf(s, lon: lon, rat: rat, tst: tst);
    final dayOk = r.day == expDay;
    final timeOk = r.time == expTime;
    final lunarOk = lunarSuffix(r.lunar) == expLunarSuffix;
    final pass = dayOk && timeOk && lunarOk;
    print('[$id] lon=E$lon rat=$rat tst=$tst');
    print('    实际: 日柱=${r.day} 时柱=${r.time} 农历=${r.lunar}');
    print('    预期: 日柱=$expDay 时柱=$expTime 农历=…$expLunarSuffix');
    if (!pass) {
      print('    ❌ FAIL: ' +
          [
            if (!dayOk) '日柱(实=${r.day})',
            if (!timeOk) '时柱(实=${r.time})',
            if (!lunarOk) '农历(实=${lunarSuffix(r.lunar)})',
          ].join(' '));
    } else {
      print('    ✅ PASS');
    }
    return pass;
  }

  group('用户专有场景(1990-01-01 00:00 澄海 E116.77, 真太阳开)', () {
    final s = DateTime(1990, 1, 1, 0, 0);
    test('八字·不开早晚子时 → 丙寅/戊子/十二月初五', () {
      expect(
          runCase('C1', s,
              lon: 116.77, rat: false, tst: true,
              expDay: '丙寅', expTime: '戊子', expLunarSuffix: '十二月初五'),
          isTrue);
    });
    test('八字·开早晚子时 → 乙丑/戊子/十二月初四', () {
      expect(
          runCase('C2', s,
              lon: 116.77, rat: true, tst: true,
              expDay: '乙丑', expTime: '戊子', expLunarSuffix: '十二月初四'),
          isTrue);
    });
    test('紫微·不开早晚子时 → 丙寅/戊子/十二月初五', () {
      expect(
          runCase('C3', s,
              lon: 116.77, rat: false, tst: true,
              expDay: '丙寅', expTime: '戊子', expLunarSuffix: '十二月初五',
              isZiwei: true),
          isTrue);
    });
    test('紫微·开早晚子时 → 乙丑/戊子/十二月初四', () {
      expect(
          runCase('C4', s,
              lon: 116.77, rat: true, tst: true,
              expDay: '乙丑', expTime: '戊子', expLunarSuffix: '十二月初四',
              isZiwei: true),
          isTrue);
    });
    test('跨屏一致·不开', () {
      final bz = baziOf(s, lon: 116.77, rat: false, tst: true);
      final zw = ziweiOf(s, lon: 116.77, rat: false, tst: true);
      expect(bz.day, zw.day);
      expect(bz.time, zw.time);
      expect(bz.lunar, zw.lunar);
    });
    test('跨屏一致·开', () {
      final bz = baziOf(s, lon: 116.77, rat: true, tst: true);
      final zw = ziweiOf(s, lon: 116.77, rat: true, tst: true);
      expect(bz.day, zw.day);
      expect(bz.time, zw.time);
      expect(bz.lunar, zw.lunar);
    });
  });

  // 1996-04-23 汕头（app 城市库 汕头市 = 东经 116.63626°），真太阳开。
  // 真太阳时校正 ≈ 11.7 分钟 → 00:10 被推回前一日 23:58（晚子时，日柱己丑），
  // 00:12 落在当日 00:00（日柱庚寅）。早子时（00:12+）日柱不顺延次日。
  group('用户专有场景(1996-04-23 汕头 E116.636, 真太阳开)', () {
    const lon = 116.63626;
    test('八字·00:30 关/开 均为 庚寅/丙子（早子时，不顺延）', () {
      final s = DateTime(1996, 4, 23, 0, 30);
      final off = baziOf(s, lon: lon, rat: false, tst: true);
      final on = baziOf(s, lon: lon, rat: true, tst: true);
      expect(off.day, '庚寅');
      expect(off.time, '丙子');
      expect(on.day, '庚寅');
      expect(on.time, '丙子');
      final zwOn = ziweiOf(s, lon: lon, rat: true, tst: true);
      expect(zwOn.day, '庚寅');
      expect(zwOn.time, '丙子');
    });
    test('八字·00:10 关=庚寅/丙子，开=己丑/丙子（真太阳推回前日晚子时）', () {
      final s = DateTime(1996, 4, 23, 0, 10);
      final off = baziOf(s, lon: lon, rat: false, tst: true);
      final on = baziOf(s, lon: lon, rat: true, tst: true);
      expect(off.day, '庚寅');
      expect(off.time, '丙子');
      expect(on.day, '己丑');
      expect(on.time, '丙子');
      final zwOn = ziweiOf(s, lon: lon, rat: true, tst: true);
      expect(zwOn.day, '己丑');
    });
    test('八字·00:12 开=庚寅/丙子（早子时边界）', () {
      final on = baziOf(DateTime(1996, 4, 23, 0, 12),
          lon: lon, rat: true, tst: true);
      expect(on.day, '庚寅');
      expect(on.time, '丙子');
    });
    test('紫微·00:30 关/开 一致（庚寅/丙子）', () {
      final s = DateTime(1996, 4, 23, 0, 30);
      final zwOff = ziweiOf(s, lon: lon, rat: false, tst: true);
      final zwOn = ziweiOf(s, lon: lon, rat: true, tst: true);
      expect(zwOff.day, zwOn.day);
      expect(zwOn.day, '庚寅');
    });
  });

  // 2000-01-01 汕头（东经 116.63626°），真太阳开。
  // 校正 ≈ 12 分钟 → 00:00 起点被推回前一日 23:48（晚子时）。
  group('用户专有场景(2000-01-01 汕头 E116.636, 真太阳开)', () {
    const lon = 116.63626;
    final s = DateTime(2000, 1, 1, 0, 0);
    test('八字·关=戊午/壬子，开=丁巳/壬子（晚子时推回前一日）', () {
      final off = baziOf(s, lon: lon, rat: false, tst: true);
      final on = baziOf(s, lon: lon, rat: true, tst: true);
      expect(off.day, '戊午');
      expect(off.time, '壬子');
      expect(on.day, '丁巳');
      expect(on.time, '壬子');
    });
    test('紫微·关/开 与八字一致', () {
      final bzOn = baziOf(s, lon: lon, rat: true, tst: true);
      final zwOn = ziweiOf(s, lon: lon, rat: true, tst: true);
      expect(zwOn.day, bzOn.day);
      expect(zwOn.time, bzOn.time);
      expect(zwOn.lunar, bzOn.lunar);
    });
  });

  // 2003-04-23 00:11 北京（app 城市库 北京市 = 东经 116.43585°），真太阳开。
  // 校正 ≈ 12.5 分钟 → 00:11 被推回前一日 23:58（晚子时）：开 日柱乙丑、关 日柱丙寅，时柱均戊子。
  group('用户专有场景(2003-04-23 00:11 北京 E116.436, 真太阳开)', () {
    const lon = 116.43585;
    final s = DateTime(2003, 4, 23, 0, 11);
    test('八字·关=丙寅/戊子，开=乙丑/戊子', () {
      final off = baziOf(s, lon: lon, rat: false, tst: true);
      final on = baziOf(s, lon: lon, rat: true, tst: true);
      expect(off.day, '丙寅');
      expect(off.time, '戊子');
      expect(on.day, '乙丑');
      expect(on.time, '戊子');
    });
    test('紫微·关=丙寅/戊子，开=乙丑/戊子（与八字一致）', () {
      final zwOff = ziweiOf(s, lon: lon, rat: false, tst: true);
      final zwOn = ziweiOf(s, lon: lon, rat: true, tst: true);
      expect(zwOff.day, '丙寅');
      expect(zwOff.time, '戊子');
      expect(zwOn.day, '乙丑');
      expect(zwOn.time, '戊子');
    });
  });

  // 2003-04-23 00:11 杭州（app 城市库 杭州市 = 东经 119.53776°），真太阳开。
  // 杭州在 120° 以东 → 校正为正（≈ +3.6 分钟）→ 00:11 前推到 00:14，仍在当日：
  // 开/关 均为 丙寅/戊子（无跨日，与北京相反）。
  group('用户专有场景(2003-04-23 00:11 杭州 E119.538, 真太阳开)', () {
    const lon = 119.53776;
    final s = DateTime(2003, 4, 23, 0, 11);
    test('八字·关/开 均为 丙寅/戊子（不跨日）', () {
      final off = baziOf(s, lon: lon, rat: false, tst: true);
      final on = baziOf(s, lon: lon, rat: true, tst: true);
      for (final r in [off, on]) {
        expect(r.day, '丙寅');
        expect(r.time, '戊子');
      }
    });
    test('紫微·关/开 均为 丙寅/戊子（与八字一致）', () {
      final zwOff = ziweiOf(s, lon: lon, rat: false, tst: true);
      final zwOn = ziweiOf(s, lon: lon, rat: true, tst: true);
      for (final r in [zwOff, zwOn]) {
        expect(r.day, '丙寅');
        expect(r.time, '戊子');
      }
    });
  });

  // 2003-04-23 00:11 太原（app 城市库 太原市 = 东经 112.40106°），真太阳开。
  // 太原偏西较多 → 校正 ≈ −28.6 分钟 → 00:11 推回前一日 23:42（晚子时）：
  // 开 日柱乙丑、关 日柱丙寅，时柱均戊子（与北京同型，跨日幅度更大）。
  group('用户专有场景(2003-04-23 00:11 太原 E112.401, 真太阳开)', () {
    const lon = 112.40106;
    final s = DateTime(2003, 4, 23, 0, 11);
    test('八字·关=丙寅/戊子，开=乙丑/戊子', () {
      final off = baziOf(s, lon: lon, rat: false, tst: true);
      final on = baziOf(s, lon: lon, rat: true, tst: true);
      expect(off.day, '丙寅');
      expect(off.time, '戊子');
      expect(on.day, '乙丑');
      expect(on.time, '戊子');
    });
    test('紫微·关=丙寅/戊子，开=乙丑/戊子（与八字一致）', () {
      final zwOff = ziweiOf(s, lon: lon, rat: false, tst: true);
      final zwOn = ziweiOf(s, lon: lon, rat: true, tst: true);
      expect(zwOff.day, '丙寅');
      expect(zwOff.time, '戊子');
      expect(zwOn.day, '乙丑');
      expect(zwOn.time, '戊子');
    });
  });

  // 2003-04-23 00:11 拉萨（app 城市库 拉萨市 = 东经 91.26811°），真太阳开。
  // 拉萨极西 → 校正 ≈ −113 分钟 → 00:11 推回前一日 22:17（**亥时，非子时**）：
  // 关（子时归自然日）此时不生效（校正后非子时）→ 开/关 均为 乙丑/丁亥。
  group('用户专有场景(2003-04-23 00:11 拉萨 E91.268, 真太阳开)', () {
    const lon = 91.26811;
    final s = DateTime(2003, 4, 23, 0, 11);
    test('八字·关/开 均为 乙丑/丁亥（校正后亥时，非子时→开关无差异）', () {
      final off = baziOf(s, lon: lon, rat: false, tst: true);
      final on = baziOf(s, lon: lon, rat: true, tst: true);
      for (final r in [off, on]) {
        expect(r.day, '乙丑');
        expect(r.time, '丁亥');
      }
    });
    test('紫微·关/开 均为 乙丑/丁亥（与八字一致）', () {
      final zwOff = ziweiOf(s, lon: lon, rat: false, tst: true);
      final zwOn = ziweiOf(s, lon: lon, rat: true, tst: true);
      for (final r in [zwOff, zwOn]) {
        expect(r.day, '乙丑');
        expect(r.time, '丁亥');
      }
    });
  });

  // 2003-04-23 00:11 乌鲁木齐（app 城市库 乌鲁木齐市 = 东经 87.77529°），真太阳开。
  // 乌鲁木齐极西 → 校正 ≈ −127 分钟 → 00:11 推回前一日 22:03（亥时，非子时）→ 开/关 均 乙丑/丁亥。
  group('用户专有场景(2003-04-23 00:11 乌鲁木齐 E87.775, 真太阳开)', () {
    const lon = 87.77529;
    final s = DateTime(2003, 4, 23, 0, 11);
    test('八字·关/开 均为 乙丑/丁亥（校正后亥时→开关无差异）', () {
      final off = baziOf(s, lon: lon, rat: false, tst: true);
      final on = baziOf(s, lon: lon, rat: true, tst: true);
      for (final r in [off, on]) {
        expect(r.day, '乙丑');
        expect(r.time, '丁亥');
      }
    });
    test('紫微·关/开 均为 乙丑/丁亥（与八字一致）', () {
      final zwOff = ziweiOf(s, lon: lon, rat: false, tst: true);
      final zwOn = ziweiOf(s, lon: lon, rat: true, tst: true);
      for (final r in [zwOff, zwOn]) {
        expect(r.day, '乙丑');
        expect(r.time, '丁亥');
      }
    });
  });

  // 2003-04-23 00:11 呼和浩特（app 城市库 呼和浩特市 = 东经 111.40007°），真太阳开。
  // 校正 ≈ −32.6 分钟 → 00:11 推回前一日 23:38（**晚子时**）→ 开关分叉：
  // 关（子时归自然日）回拨出生日 = 丙寅/戊子；开 = 乙丑/戊子。
  group('用户专有场景(2003-04-23 00:11 呼和浩特 E111.400, 真太阳开)', () {
    const lon = 111.40007;
    final s = DateTime(2003, 4, 23, 0, 11);
    test('八字·关=丙寅/戊子，开=乙丑/戊子（校正后晚子时→分叉）', () {
      final off = baziOf(s, lon: lon, rat: false, tst: true);
      final on = baziOf(s, lon: lon, rat: true, tst: true);
      expect(off.day, '丙寅');
      expect(off.time, '戊子');
      expect(on.day, '乙丑');
      expect(on.time, '戊子');
    });
    test('紫微·关=丙寅/戊子，开=乙丑/戊子（与八字一致）', () {
      final zwOff = ziweiOf(s, lon: lon, rat: false, tst: true);
      final zwOn = ziweiOf(s, lon: lon, rat: true, tst: true);
      expect(zwOff.day, '丙寅');
      expect(zwOff.time, '戊子');
      expect(zwOn.day, '乙丑');
      expect(zwOn.time, '戊子');
    });
  });
}
