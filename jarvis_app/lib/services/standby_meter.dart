import 'package:shared_preferences/shared_preferences.dart';

import 'native_bridge.dart';

/// 待命耗电实测（v3 第 5 条）：沙箱里没有真机，无法给出可信的耗电数字，
/// 所以把“测量”做进 App：唤醒词待命开始时记下电量和时间，之后在功能自检里
/// 用真实电量变化折算 %/小时。没有数据时只会说“没有样本”，不编数字。
///
/// 局限：电量只有整数百分比；样本期间亮屏使用、充电都会污染结果（检测到充电会判无效）；
/// 进程被杀或关闭 / 静音唤醒会结束当前样本。
class StandbyMeter {
  static const _kStart = 'jarvis_standby_start';
  static const _kLevel = 'jarvis_standby_level';
  static const _kBad = 'jarvis_standby_bad';

  /// 待命开始监听时调用；已有样本则保留（幂等）。
  static Future<void> begin() async {
    final p = await SharedPreferences.getInstance();
    if (p.getInt(_kStart) != null) return;
    final b = await NativeBridge.battery();
    if (b == null) return;
    await p.setInt(_kStart, DateTime.now().millisecondsSinceEpoch);
    await p.setInt(_kLevel, (b['level'] as num).toInt());
    await p.setBool(_kBad, b['charging'] == true);
  }

  /// 关闭 / 静音唤醒时调用：结束当前样本。
  static Future<void> end() async {
    final p = await SharedPreferences.getInstance();
    await p.remove(_kStart);
    await p.remove(_kLevel);
    await p.remove(_kBad);
  }

  static Future<String> report() async {
    final p = await SharedPreferences.getInstance();
    final start = p.getInt(_kStart);
    if (start == null) return '还没有样本：开启唤醒词待命，放一段时间（建议熄屏 1 小时以上、不充电）再来看。';
    final b = await NativeBridge.battery();
    if (b == null) return '读不到电量。';
    final mins = (DateTime.now().millisecondsSinceEpoch - start) / 60000;
    final l0 = p.getInt(_kLevel) ?? 0;
    final l1 = (b['level'] as num).toInt();
    if (b['charging'] == true || p.getBool(_kBad) == true || l1 > l0) {
      return '样本期间充过电，数据无效。请拔掉充电线，关闭再重开唤醒词待命后重新测。';
    }
    if (mins < 20) return '样本太短（${mins.round()} 分钟），至少 20 分钟才有参考价值。';
    final drop = l0 - l1;
    final rate = drop * 60 / mins;
    return '已待命 ${mins.round()} 分钟，电量 $l0% → $l1%（下降 $drop%），折算约 ${rate.toStringAsFixed(1)}%/小时。'
        '电量是整数百分比，样本越长越准；期间亮屏使用会拉高这个数。';
  }
}
