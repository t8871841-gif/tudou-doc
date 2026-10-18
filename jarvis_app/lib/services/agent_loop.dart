import 'dart:convert';

import 'package:flutter/services.dart';
import 'settings_store.dart';

/// Agent 看屏慢路径：现有 DeviceActions 快路径不认识的指令才走这里。
///
/// 流程：看屏（无障碍节点树）→ 大模型决策 → 动手一步 → 核验。
/// 铁律：
///  - 感知失败 = 步骤失败，绝不拿空屏幕继续决策，更不把步骤记完成；
///  - 核验只认“预期结果”：决策返回的 expect 文本出现在新屏幕上才算成功；
///    expect 为空时才退而求其次认“屏幕发生了变化”；
///  - 最多尝试 2 次（第一次决策失败后，重新看屏、让大模型换招再试一次），
///    两次都失败就如实返回卡在哪一步，不无限烧 token。
class AgentLoop {
  static const MethodChannel _channel =
      MethodChannel('com.jarvis.jarvis_app/agent');
  static const int maxAttempts = 2;

  /// 看屏：返回无障碍节点树 JSON。失败 / 空数据直接抛，调用方记步骤失败。
  /// 原生侧的结构化错误分开说，不混成一句：“服务未开启”和“前台 App 不在白名单”是两回事。
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
    // 结构化错误（{"error": ...}）才按错误处理；正常节点树只是恰好也是 JSON。
    String? errorKind;
    String? errorPkg;
    try {
      final decoded = jsonDecode(text);
      if (decoded is Map<String, dynamic> && decoded['error'] is String) {
        errorKind = decoded['error'] as String;
        errorPkg = decoded['pkg']?.toString();
      }
    } catch (_) {
      // 解析失败 = 正常节点树，不是错误。
    }
    if (errorKind == 'service_off') {
      throw Exception('无障碍服务未开启：请在系统设置 > 无障碍里打开“Jarvis 跨应用全自动引擎”。');
    }
    if (errorKind == 'not_whitelisted') {
      throw Exception(
          '前台 App 不在白名单里，无法看屏${(errorPkg ?? '').isEmpty ? '' : '（当前：$errorPkg）'}。');
    }
    return text;
  }

  /// 动手一步。actionType: 'click' | 'input' | 'back'。
  /// 'input' 需要 inputText；'back' 不用 target。
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
  ///
  /// [decide] 由调用方注入（AgentService.decideNextAction，走用户配置的大模型），
  /// 这样本文件不反向依赖 AgentService，避免循环导入。
  static Future<String> runSlowStep({
    required JarvisSettings settings,
    required String goal,
    required String stepDescription,
    required Future<Map<String, dynamic>> Function({
      required JarvisSettings settings,
      required String goal,
      required String currentStep,
      required String screenTree,
    }) decide,
  }) async {
    Exception? lastError;
    for (var attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        final preScreen = await perceiveScreen();
        final decision = await decide(
          settings: settings,
          goal: goal,
          currentStep: stepDescription,
          screenTree: preScreen,
        );
        final action = decision['action']?.toString() ?? '';
        final target = decision['target']?.toString() ?? '';
        final expect = decision['expect']?.toString() ?? '';
        final inputText = decision['inputText']?.toString();

        if (action.isEmpty || (action != 'back' && target.isEmpty)) {
          throw Exception('大模型决策无效（第 $attempt 次）：action/target 缺失');
        }

        final acted = await performAction(
          action,
          target,
          inputText: inputText,
        );
        if (!acted) {
          throw Exception('动手失败（第 $attempt 次）：在屏幕上没找到“$target”或动作被安全规则拒绝');
        }

        // 真核验：动作后重新看屏。
        await Future.delayed(const Duration(milliseconds: 800));
        final postScreen = await perceiveScreen();
        final verified = expect.isNotEmpty
            ? postScreen.contains(expect)
            : postScreen != preScreen;
        if (!verified) {
          throw Exception(
              '核验未通过（第 $attempt 次）：动作后屏幕未达到预期状态${expect.isNotEmpty ? '（未出现“$expect”）' : ''}');
        }
        return '已完成：$stepDescription';
      } catch (e) {
        lastError = e is Exception ? e : Exception(e.toString());
        // 换招：下一次循环重新看屏、重新决策；2 次都失败则抛。
      }
    }
    throw lastError ?? Exception('慢路径执行失败：$stepDescription');
  }
}
