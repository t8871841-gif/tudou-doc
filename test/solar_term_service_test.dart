import 'package:flutter_test/flutter_test.dart';
import 'package:nihaisha_app/services/solar_term_service.dart';

void main() {
  // getSolarTermKnowledge 读取资源需初始化 binding（rootBundle）
  TestWidgetsFlutterBinding.ensureInitialized();

  group('节气服务', () {
    test('2026-08-31 处于处暑、距白露为正', () {
      final info = getCurrentSolarTerm(DateTime(2026, 8, 31, 12, 0, 0));
      expect(info.currentTerm, '处暑');
      expect(info.nextTerm, '白露');
      expect(info.daysLeft, greaterThan(0));
      expect(info.daysLeft, lessThan(30));
      expect(info.daysInto, greaterThan(0));
      expect(info.healthTip, isNotEmpty);
    });

    test('冬至为冬，养生要点非空', () {
      final info = getCurrentSolarTerm(DateTime(2026, 12, 22, 12, 0, 0));
      expect(info.currentTerm, '冬至');
      expect(info.healthTip, isNotEmpty);
    });

    test('立春养生要点非空', () {
      final info = getCurrentSolarTerm(DateTime(2026, 2, 5, 12, 0, 0));
      expect(info.currentTerm, '立春');
      expect(info.healthTip, isNotEmpty);
    });

    test('getSolarTermKnowledge 返回 health + niShi', () async {
      final k = await getSolarTermKnowledge('立春');
      expect(k, isNotNull);
      expect(k!.term, '立春');
      expect(k.health, isNotEmpty);
      expect(k.niShi, contains('【推断】'));
    });

    test('getSolarTermKnowledge 未知节气返回 null', () async {
      final k = await getSolarTermKnowledge('不存在的节气');
      expect(k, isNull);
    });

    test('立春知识含三候/起居/食疗', () async {
      final k = await getSolarTermKnowledge('立春');
      expect(k, isNotNull);
      expect(k!.phenology, hasLength(3));
      expect(k.phenology.first, isNotEmpty);
      expect(k.dailyRegimen, contains('【推断】'));
      expect(k.dietRecipe, isNotEmpty);
    });

    test('24 节气天文静态表正确', () {
      expect(getTermApproxDate('立春'), '2月3-5日');
      expect(getTermSolarLongitude('立春'), 315);
      expect(getTermApproxDate('春分'), '3月20-22日');
      expect(getTermSolarLongitude('春分'), 0);
      expect(getTermApproxDate('冬至'), '12月21-23日');
      expect(getTermSolarLongitude('冬至'), 270);
    });

    test('未知节气天文查询返回兜底', () {
      expect(getTermApproxDate('不存在'), isEmpty);
      expect(getTermSolarLongitude('不存在'), 0);
    });
  });
}
