import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sxwnl_spa_dart/sxwnl_spa_dart.dart' show LunarDate, AstroDateTime;
import 'package:nihaisha_app/data/chenggu_data.dart';
import 'package:nihaisha_app/services/bazi_service.dart' show computeBaZiPaipan;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('chenggu full _compute path across many dates', () async {
    await ChengguData.ensureLoaded();
    final years = [1984, 1990, 2000, 2023, 2024];
    int fail = 0;
    for (final y in years) {
      for (int m = 1; m <= 12; m++) {
        for (int d = 1; d <= 28; d++) {
          for (final h in [0, 6, 12, 18, 23]) {
            final solar = DateTime(y, m, d, h, 0);
            try {
              final p = computeBaZiPaipan(
                solar,
                useTrueSolarTime: true,
                ratHourMode: false,
              );
              final yearGz = p.gans[0] + p.zhis[0];
              final timeZhi = p.zhis[3];
              final lunar = LunarDate.fromSolar(
                  AstroDateTime(y, m, d, 12, 0, 0));
              final lunarMonth = lunar.month;
              final lunarDay = lunar.day;
              if (lunarMonth < 1 || lunarMonth > 12) {
                print('OUT-OF-RANGE lunar.month=$lunarMonth for $solar');
              }
              final r = ChengguData.compute(
                  yearGz, lunarMonth, lunarDay, timeZhi);
              // sanity: result must be non-null with a poem
              if (r.poem.isEmpty) {
                print('EMPTY poem for $solar yearGz=$yearGz lm=$lunarMonth ld=$lunarDay tz=$timeZhi');
              }
            } catch (e) {
              fail++;
              print('THROW for $solar (${y}-${m}-${d} ${h}:00): $e');
            }
          }
        }
      }
    }
    print('TOTAL THROWS = $fail');
    expect(fail, 0);
  });
}
