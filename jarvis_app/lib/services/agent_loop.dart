import 'dart:convert';

import 'package:flutter/services.dart';

import 'agent_control.dart';
import 'audit_log.dart';
import 'native_bridge.dart';
import 'settings_store.dart';
import 'skill_store.dart';
import 'vision_service.dart';

/// v3：每轮动作前检查急停信号（AgentControl），收到就抛 AgentStopped，绝不再执行下一步；
/// 每个动作与核验结果写入审计日志（输入内容不落盘，只记字数）。
///
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

  /// 状态提示回调（runAgent 设置）。
  static void Function(String)? notice;

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

  static String _why(dynamic r) {
    switch (r?.toString()) {
      case 'blocked_app':
        return '当前界面属于银行 / 支付 / 系统设置类，禁止坐标点击';
      case 'not_whitelisted':
        return '当前 App 不在看屏白名单，请到设置里把它加入白名单后再试';
      case 'service_off':
        return '无障碍服务没开';
      default:
        return r?.toString() ?? '未知原因';
    }
  }

  /// 视觉兜底点击：截图 → 视觉模型定位 → 坐标点击。黑名单 App 直接拒绝并提示。
  static Future<bool> _visionClick(
      JarvisSettings settings, String target, String stepDescription) async {
    try {
      final shot = await VisionService.capture();
      final p = await VisionService.locate(settings, shot, target);
      if (p == null) {
        AuditLog.log('视觉定位「$target」', ok: false, detail: '模型没找到');
        return false;
      }
      AgentControl.throwIfStopped();
      final r = await NativeBridge.tapXY(p.x, p.y);
      final ok = r['ok'] == true;
      AuditLog.log('视觉点击「$target」', ok: ok, detail: ok ? p.desc : _why(r['reason']));
      if (!ok && (r['reason'] == 'blocked_app' || r['reason'] == 'not_whitelisted')) {
        throw Exception(_why(r['reason'])); // 明确告诉用户为什么不点，不静默失败
      }
      return ok;
    } on VisionBlocked catch (e) {
      AuditLog.log('视觉截图被拒绝', ok: false, detail: e.reason);
      throw Exception(e.message); // 黑名单 / 自身 / 防截屏：明确告诉用户，不静默失败
    }
  }

  /// 技能回放（v3 第 8 条）：按已学会的界面步骤逐步执行，**每一步都核验**：
  /// 动作必须成功；有核验点就必须在新屏幕上看到，没有核验点就必须看到屏幕变化。
  /// 任何一步对不上就抛异常——调用方会告知用户并回退到看屏代理，绝不继续盲点。
  static Future<String> _replay(
      JarvisSettings settings, List<UiStep> steps, String stepDescription) async {
    notice?.call('按已学会的流程执行（${steps.length} 步）…');
    var prev = await perceiveScreen();
    var i = 0;
    for (final st in steps) {
      i++;
      AgentControl.throwIfStopped();
      var ok = await performAction(st.action, st.target,
          inputText: st.input.isEmpty ? null : st.input);
      if (!ok && st.action == 'click' && VisionService.usable(settings)) {
        ok = await _visionClick(settings, st.target, stepDescription);
      }
      if (!ok) throw Exception('第$i步找不到「${st.target}」');
      await Future.delayed(const Duration(milliseconds: 800));
      final post = await perceiveScreen();
      if (st.expect.isNotEmpty) {
        if (!post.contains(st.expect)) throw Exception('第$i步之后没看到「${st.expect}」');
      } else if (post == prev) {
        throw Exception('第$i步之后屏幕没有变化');
      }
      AuditLog.log('回放第$i步 ${st.action}「${st.action == 'input' ? '…' : st.target}」',
          ok: true, detail: st.expect.isEmpty ? '屏幕已变化' : '核验到“${st.expect}”');
      prev = post;
    }
    return '已按学会的流程完成：$stepDescription（共 ${steps.length} 步，逐步核验通过）';
  }

  /// 节点树“读不全”：有意义的文字 / 描述节点少于 2 个（自绘 / 游戏 / 短视频界面）。
  static bool _thin(String tree) =>
      RegExp(r'"(text|desc)":"[^"]+"').allMatches(tree).length < 2;

  /// 纯视觉循环：节点树读不到时，看图决定 → 坐标动手 → 本地签名判断屏幕是否变化 → 视觉核验完成。
  static Future<String> _runVisionStep(
      JarvisSettings settings, String goal, String stepDescription) async {
    notice?.call('界面读不到文字，改用视觉看屏…');
    final done = <String>[];
    var fail = 0;
    var lastErr = '';
    Future<ScreenShot> cap() async {
      try {
        return await VisionService.capture();
      } on VisionBlocked catch (e) {
        throw Exception(e.message);
      }
    }

    var shot = await cap();
    for (var round = 1; round <= maxRounds; round++) {
      AgentControl.throwIfStopped();
      VisionDecision d;
      try {
        d = await VisionService.decideNext(
            settings, shot, goal, stepDescription, done.join('；'));
      } catch (e) {
        lastErr = '$e';
        if (++fail >= maxFailStreak) break;
        shot = await cap();
        continue;
      }
      if (d.action == 'done') {
        final ok = d.expect.isNotEmpty && await VisionService.verify(settings, shot, d.expect);
        AuditLog.log('视觉核验“${d.expect}”', ok: ok);
        if (ok) return '已完成：$stepDescription（视觉核验到“${d.expect}”）';
        lastErr = '模型认为已完成，但核验没看到“${d.expect}”';
        if (++fail >= maxFailStreak) break;
        continue;
      }
      AgentControl.throwIfStopped();
      Map<String, dynamic> r;
      String what;
      if (d.action == 'tap') {
        r = await NativeBridge.tapXY(d.point!.x, d.point!.y);
        what = '视觉点击「${d.point!.desc}」';
      } else if (d.action == 'back') {
        r = {'ok': await performAction('back', '')};
        what = '返回';
      } else {
        r = await NativeBridge.swipeScreen(d.action == 'scroll_down');
        what = d.action == 'scroll_down' ? '向下滑动' : '向上滑动';
      }
      final ok = r['ok'] == true;
      AuditLog.log(what, ok: ok, detail: ok ? d.reason : _why(r['reason']));
      if (!ok) {
        final reason = r['reason']?.toString();
        if (reason == 'blocked_app' || reason == 'not_whitelisted' || reason == 'service_off') {
          throw Exception('$what 被拒绝：${_why(reason)}');
        }
        lastErr = '$what 失败：${_why(reason)}';
        if (++fail >= maxFailStreak) break;
        continue;
      }
      await Future.delayed(const Duration(milliseconds: 900));
      final next = await cap();
      if (next.sig == shot.sig) {
        lastErr = '动作后屏幕没有变化';
        if (++fail >= maxFailStreak) break;
      } else {
        fail = 0;
        done.add('第$round步 $what');
        UiTrace.add(UiStep(d.action == 'tap' ? 'click' : 'scroll',
            d.point?.desc ?? (d.action == 'scroll_up' ? 'up' : 'down'), '', d.expect));
      }
      shot = next;
    }
    throw Exception('视觉看屏没能完成「$stepDescription」：$lastErr'
        '${done.isEmpty ? '' : '（已做：${done.join('；')}）'}');
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
    AgentControl.throwIfStopped();
    UiTrace.slowSteps++;

    // v3 技能回放：只在整个任务的第一个看屏步骤上尝试；失败不静默——告知、作废过期步骤、回退看屏代理。
    final ms = UiTrace.matched;
    if (UiTrace.slowSteps == 1 && ms != null && ms.skill.uiSteps.isNotEmpty) {
      try {
        final r = await _replay(settings, ms.skill.instantiateUi(ms.parameters), stepDescription);
        UiTrace.replayed = true;
        AuditLog.log('技能回放成功', ok: true, detail: stepDescription);
        return r;
      } on AgentStopped {
        rethrow;
      } catch (e) {
        await SkillStore.invalidateUiSteps(ms.skill);
        notice?.call('已学会的流程回放没对上（$e），已停下，改用看屏代理重新做这一步。');
        AuditLog.log('技能回放失败，已停下并回退看屏代理', ok: false, detail: '$e');
        UiTrace.steps.clear();
      }
    }
    final done = <String>[]; // 已做过的动作（给模型看，避免重复）
    var failStreak = 0;
    String lastErr = '';
    // v3 视觉融合：节点树优先；树读不到 / 读不全时才用截图 + 多模态模型兜底。
    // 视觉未启用时行为与 v2 完全一致（读不到就如实失败）。
    String screen;
    try {
      screen = await perceiveScreen(); // 首次看屏失败直接抛
    } catch (e) {
      if (VisionService.usable(settings) && e.toString().contains('没有可读的屏幕内容')) {
        AuditLog.log('节点树读不到，改用视觉看屏', detail: stepDescription);
        return _runVisionStep(settings, goal, stepDescription);
      }
      rethrow;
    }
    if (VisionService.usable(settings) && _thin(screen)) {
      AuditLog.log('节点树读不全，改用视觉看屏', detail: stepDescription);
      return _runVisionStep(settings, goal, stepDescription);
    }

    for (var round = 1; round <= maxRounds; round++) {
      AgentControl.throwIfStopped(); // 急停：下一轮不再开始
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
      AgentControl.throwIfStopped(); // 决策返回后、动手前再查一次：急停后这一步动作不会发生
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
        final doneOk = cur.contains(expect);
        AuditLog.log('核验“$expect”', ok: doneOk);
        if (doneOk) return '已完成：$stepDescription（核验到“$expect”）';
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

      var acted = await performAction(action, target, inputText: inputText);
      var viaVision = false;
      if (!acted && action == 'click' && VisionService.usable(settings)) {
        // 树里点不到：截图 → 视觉模型定位 → 坐标点击（受黑名单 / 白名单约束）
        acted = await _visionClick(settings, target, stepDescription);
        viaVision = acted;
      }
      final what = action == 'input'
          ? '在「$target」输入了 ${inputText?.length ?? 0} 个字'
          : action == 'click'
              ? '点击「$target」${viaVision ? '（视觉定位）' : ''}'
              : action == 'scroll'
                  ? '滚动${target == 'up' ? '向上' : '向下'}'
                  : '返回';
      if (!acted) {
        AuditLog.log(what, ok: false, detail: '没找到目标或被安全规则拒绝');
        lastErr = '动手失败：在屏幕上没找到“$target”或动作被安全规则拒绝';
        if (++failStreak >= maxFailStreak) break;
        screen = await perceiveScreen();
        continue;
      }

      await Future.delayed(const Duration(milliseconds: 800));
      final post = await perceiveScreen(); // 离开白名单 / 服务掉线会在这里抛，如实报错
      final expectOk = expect.isNotEmpty && post.contains(expect);
      final changed = post != screen;
      AuditLog.log(what,
          ok: expectOk || changed,
          detail: expect.isEmpty ? '' : (expectOk ? '核验到“$expect”' : '未见“$expect”'));
      if (expectOk || (expect.isEmpty && changed) || (action == 'scroll' && changed)) {
        failStreak = 0;
        done.add('第$round步 $action「${target.isEmpty ? '-' : target}」');
        UiTrace.add(UiStep(action, target, inputText ?? '', expect)); // 记下成功的一步（技能固化用）
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
