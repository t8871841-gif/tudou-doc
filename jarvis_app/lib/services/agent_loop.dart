import 'dart:convert';

import 'package:flutter/services.dart';
import 'settings_store.dart';

/// Agent 看屏慢路径：现有 DeviceActions 快路径不认识的指令才走这里。
///
/// v2：一个步骤内可以多轮“看屏 → 决策 → 动手 → 核验”（最多 [maxRounds] 轮），
/// 不再依赖规划器把界面操作拆得恰到好处。铁律不变：
///  - 感知失败 = 步骤失败，绝不拿空屏幕继续决策，更不把步骤记完成；
///  - 核验只认“预期结果”：expect 文本出现在新屏幕上才算数；
///  - “做完了”必须由模型给出 done + 可核验的 expect，且 expect 真的出现在屏幕上，
///    否则如实报告“不能确认完成”；
///  - 连续 2 轮没进展（动手失败 / 屏幕没变 / 决策无效）就停，如实说卡在哪。
class AgentLoop {
  static const MethodChannel _channel =
      MethodChannel('com.jarvis.jarvis_app/agent');
  static const int maxRounds = 6;
  static const int maxFailStreak = 2;

  /// 看屏：返回无障碍节点树 JSON。失败 / 空数据直接抛，调用方记步骤失败。
  static Future<String> perceiveScreen() async {
    String text;
    try {
      final String? result = await _channel.invokeMethod('perceiveScreen');
      text = (result ?? '').trim();
    } catch (e) {
      throw Exception('感知屏幕失败：$e');
    }
    if (text.isEmpty || text == '{}') {
      throw Exception('看屏失败：当前界面没有可读的屏幕内容。');
    }
    String? errorKind;
    String? errorPkg;
    try {
      final decoded = jsonDecode(text);
      if (decoded is Map<String, dynamic> && decoded['error'] is String) {
        errorKind = decoded['error'] as String;
        errorPkg = decoded['pkg']?.toString();
      }
    } catch (_) {}
    if (errorKind == 'service_off') {
      throw Exception('无障碍服务未开启：请在系统设置 > 无障碍里打开“Jarvis 跨应用全自动引擎”。');
    }
    if (errorKind == 'not_whitelisted') {
      throw Exception(
          '前台 App 不在白名单里，无法看屏${(errorPkg ?? '').isEmpty ? '' : '（当前：$errorPkg）'}。'
          '可在设置里把它加入“看屏白名单”。');
    }
    return text;
  }

  /// 动手一步。actionType: click | input | back | scroll（target: up/down）。
  static Future<bool> performAction(
    String actionType,
    String targetText, {
    String? inputText,
  }) async {
    try {
      final bool? result = await _channel.invokeMethod('performAction', {
        'actionType': actionType,
        'targetText': targetText,
        if (inputText != null) 'inputText': inputText,
      });
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  /// 执行一个通用慢路径步骤。成功返回一句人话结果；失败抛异常说明卡在哪。
  static Future<String> runSlowStep({
    required JarvisSettings settings,
    required String goal,
    required String stepDescription,
    required Future<Map<String, dynamic>> Function({
      required JarvisSettings settings,
      required String goal,
      required String currentStep,
      required String screenTree,
      required String progress,
    }) decide,
  }) async {
    final done = <String>[]; // 已做过的动作（给模型看，避免重复）
    var failStreak = 0;
    String lastErr = '';
    var screen = await perceiveScreen(); // 首次看屏失败直接抛

    for (var round = 1; round <= maxRounds; round++) {
      Map<String, dynamic> d;
      try {
        d = await decide(
          settings: settings,
          goal: goal,
          currentStep: stepDescription,
          screenTree: screen,
          progress: done.isEmpty ? '（还没做任何动作）' : done.join('；'),
        );
      } catch (e) {
        lastErr = '决策失败：$e';
        if (++failStreak >= maxFailStreak) break;
        screen = await perceiveScreen();
        continue;
      }
      final action = d['action']?.toString() ?? '';
      final target = d['target']?.toString() ?? '';
      final expect = d['expect']?.toString() ?? '';
      final inputText = d['inputText']?.toString();
      final finishAfter = d['finish_after'] == true;

      if (action == 'done') {
        if (expect.isEmpty) {
          throw Exception('模型说已完成，但没给出可核验的界面文字，不能确认完成：$stepDescription');
        }
        final cur = await perceiveScreen();
        if (cur.contains(expect)) return '已完成：$stepDescription（核验到“$expect”）';
        lastErr = '模型认为已完成，但屏幕上没有出现“$expect”';
        if (++failStreak >= maxFailStreak) break;
        screen = cur;
        continue;
      }
      if (action.isEmpty || (action != 'back' && target.isEmpty)) {
        lastErr = '决策无效：action/target 缺失';
        if (++failStreak >= maxFailStreak) break;
        continue;
      }

      final acted = await performAction(action, target, inputText: inputText);
      if (!acted) {
        lastErr = '动手失败：在屏幕上没找到“$target”或动作被安全规则拒绝';
        if (++failStreak >= maxFailStreak) break;
        screen = await perceiveScreen();
        continue;
      }

      await Future.delayed(const Duration(milliseconds: 800));
      final post = await perceiveScreen(); // 离开白名单 / 服务掉线会在这里抛，如实报错
      final expectOk = expect.isNotEmpty && post.contains(expect);
      final changed = post != screen;
      if (expectOk || (expect.isEmpty && changed) || (action == 'scroll' && changed)) {
        failStreak = 0;
        done.add('第$round步 $action「${target.isEmpty ? '-' : target}」');
        if (finishAfter && expectOk) {
          return '已完成：$stepDescription（核验到“$expect”）';
        }
      } else if (changed) {
        // 屏幕变了但没出现预期文字：可能是中间页，继续让模型看新屏幕，但不算成功。
        done.add('第$round步 $action「$target」（界面变化，未见“$expect”）');
        failStreak = 0;
      } else {
        lastErr = '动作后屏幕没有变化${expect.isNotEmpty ? '（未出现“$expect”）' : ''}';
        if (++failStreak >= maxFailStreak) break;
      }
      screen = post;
    }
    throw Exception(
        '看屏操作没能完成「$stepDescription」：${lastErr.isEmpty ? '超过 $maxRounds 轮仍未达成目标' : lastErr}'
        '${done.isEmpty ? '' : '（已做：${done.join('；')}）'}');
  }
}
