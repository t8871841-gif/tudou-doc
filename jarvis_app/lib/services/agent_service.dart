import 'dart:convert';
import 'action_service.dart';
import 'agent_loop.dart';
import 'llm_service.dart';
import 'settings_store.dart';
import 'skill_store.dart';
import 'task_board.dart';
import 'web_search_service.dart';

class AgentStep {
  final int stepId;
  final String description;
  final String tool; // 'device_action', 'web_search', 或 'llm_reasoning'
  final String argument;
  String? result;

  AgentStep({
    required this.stepId,
    required this.description,
    required this.tool,
    required this.argument,
    this.result,
  });

  factory AgentStep.fromJson(Map<String, dynamic> json) {
    return AgentStep(
      stepId: json['step_id'] ?? 1,
      description: json['description'] ?? '',
      tool: json['tool'] ?? 'llm_reasoning',
      argument: json['argument'] ?? '',
    );
  }
}

/// 单步执行结果：text 给用户 / 汇总看；ok=false 表示这步没跑通，黑板记 failed。
class _StepOutcome {
  final String text;
  final bool ok;
  const _StepOutcome(this.text, this.ok);
}

class AgentService {
  /// 1. 核心规划与执行入口
  ///
  ///  规划 → 建黑板（持久化，App 重启可续跑）→ 逐条执行 → 汇总。
  ///  规划来源：技能库命中（同类任务的熟练流程）优先，否则大模型规划。
  static Future<String> runAgent({
    required JarvisSettings settings,
    required String userGoal,
    required List<Map<String, String>> history,
    required Function(String statusMessage) onProgress,
  }) async {
    onProgress('正在分析目标并规划任务...');

    List<AgentStep> steps;
    final matched = await SkillStore.matchSkill(userGoal);
    if (matched != null) {
      onProgress('命中技能库，使用熟练流程…');
      steps = matched.skill
          .instantiate(matched.parameters)
          .asMap()
          .entries
          .map((e) => AgentStep(
                stepId: e.key + 1,
                description: e.value.description,
                tool: e.value.tool,
                argument: e.value.argument,
              ))
          .toList();
    } else {
      try {
        steps = await _planSteps(settings, userGoal, history);
      } catch (e) {
        onProgress('任务规划失败，转为常规问答...');
        return await LlmService.ask(settings: settings, question: userGoal, history: history);
      }
    }

    if (steps.isEmpty) {
      return await LlmService.ask(settings: settings, question: userGoal, history: history);
    }

    // 2. 建黑板并执行（黑板每步落盘；中途被打断 / App 重启都能续跑）。
    final board = TaskBoard(
      goal: userGoal,
      steps: steps
          .map((s) => TaskStep(
                description: s.description,
                tool: s.tool,
                argument: s.argument,
              ))
          .toList(),
    );
    await board.save();
    await _executeBoard(
      board: board,
      steps: steps,
      settings: settings,
      onProgress: onProgress,
    );

    // 3. 收尾：挂起确认时原样返回；单步设备动作原样返回；其余走汇总。
    //    （收尾逻辑与上一版逐字一致——确认问句不许进大模型汇总。）
    return _finishBoard(
      settings: settings,
      userGoal: userGoal,
      history: history,
      steps: steps,
      onProgress: onProgress,
    );
  }

  /// “刚才那件事继续”：从黑板当前步接着跑（App 重启后也行）。
  static Future<String> resumeBoard({
    required JarvisSettings settings,
    required TaskBoard board,
    required List<Map<String, String>> history,
    required Function(String statusMessage) onProgress,
  }) async {
    if (!board.hasRemaining) return '这个任务已经做完了。';
    // 从黑板恢复步骤（已完成步骤的 result 记“已完成”，汇总时不再重跑）。
    final steps = board.steps.asMap().entries.map((e) {
      final ts = e.value;
      return AgentStep(
        stepId: e.key + 1,
        description: ts.description,
        tool: ts.tool,
        argument: ts.argument,
      )..result = ts.isCompleted ? '已完成' : null;
    }).toList();
    board.status = TaskStatus.ongoing;
    await board.save();
    await _executeBoard(
      board: board,
      steps: steps,
      settings: settings,
      onProgress: onProgress,
    );
    return _finishBoard(
      settings: settings,
      userGoal: board.goal,
      history: history,
      steps: steps,
      onProgress: onProgress,
    );
  }

  /// 逐条执行黑板步骤。
  ///  全部跑完 → 记 skill、清黑板；某步失败 → 黑板记 failed（不清板，可续跑）；
  ///  某步留下待确认 → 黑板记 waitingConfirmation，停下等用户答复。
  static Future<void> _executeBoard({
    required TaskBoard board,
    required List<AgentStep> steps,
    required JarvisSettings settings,
    required Function(String statusMessage) onProgress,
  }) async {
    final startedAt = DateTime.now();
    while (board.currentStepIndex < steps.length &&
        board.status == TaskStatus.ongoing) {
      if (DateTime.now().difference(startedAt).inSeconds > 180) {
        final tStep = board.currentStep;
        if (tStep != null) tStep.error = '任务整体超时（180 秒）';
        board.status = TaskStatus.failed;
        await SkillStore.recordFailure(board.goal);
        await board.save();
        break;
      }
      final i = board.currentStepIndex;
      final step = steps[i];
      final tStep = board.steps[i];
      onProgress('执行步骤 [${i + 1}/${steps.length}]:${step.description}');

      _StepOutcome outcome;
      try {
        outcome = await _executeStep(step, settings: settings);
      } catch (e) {
        outcome = _StepOutcome('执行出错: $e', false);
      }
      step.result = outcome.text;
      tStep.attempts++;

      // 某步留下了“等用户确认”（确认发送 / 选联系人）：停下，
      // 后续步骤等用户答复后再说——别让下一步把确认当成新指令吃掉。
      if (DeviceActions.hasPending) {
        board.status = TaskStatus.waitingConfirmation;
        board.pendingQuestion = outcome.text;
        await board.save();
        break;
      }
      if (!outcome.ok) {
        tStep.error = outcome.text;
        board.status = TaskStatus.failed;
        await SkillStore.recordFailure(board.goal);
        await board.save();
        break;
      }
      tStep.isCompleted = true;
      board.currentStepIndex++;
      await board.save();
    }
    if (board.status == TaskStatus.ongoing &&
        board.currentStepIndex >= steps.length) {
      board.status = TaskStatus.completed;
      await board.save();
      // 全部步骤跑完：沉淀 skill；黑板清掉（已完成，无需续跑）。
      await SkillStore.recordSuccess(
          board.goal,
          board.steps
              .map((s) => TaskStep(
                    description: s.description,
                    tool: s.tool,
                    argument: s.argument,
                  ))
              .toList());
      await TaskBoard.clear();
    }
  }

  /// 收尾：以下三段与上一版逐字一致。
  /// 设备动作的结果原样返回，跳过大模型汇总：
  ///  - 有挂起确认时，确认问句必须原样到用户；
  ///  - 只有一步设备动作时，结果本身就是完整回复，汇总只会改写甚至软化“没发成”。
  /// 多步混合任务仍走汇总。
  static Future<String> _finishBoard({
    required JarvisSettings settings,
    required String userGoal,
    required List<Map<String, String>> history,
    required List<AgentStep> steps,
    required Function(String statusMessage) onProgress,
  }) async {
    if (DeviceActions.hasPending) {
      for (var i = steps.length - 1; i >= 0; i--) {
        if (steps[i].tool == 'device_action' && steps[i].result != null) {
          return steps[i].result!;
        }
      }
    }
    if (steps.length == 1 &&
        steps.first.tool == 'device_action' &&
        steps.first.result != null) {
      return steps.first.result!;
    }

    // 汇总所有步骤的结果给用户
    onProgress('正在汇总执行结果...');
    final summaryPrompt = _buildSummaryPrompt(userGoal, steps);
    final finalAnswer = await LlmService.ask(
      settings: settings,
      question: summaryPrompt,
      history: history,
    );

    return finalAnswer;
  }

  /// 慢路径看屏决策：把节点树给大模型，拿回下一步动作。
  /// 走用户配置的接口 / 模型 / Key（与 LlmService.ask 同路，不写死任何地址或模型）。
  /// 返回 {action: 'click'|'input'|'back', target, inputText?, expect?}。
  /// 大模型不可用 / 返回非法时抛异常——调用方记步骤失败，绝不用启发式 stub 假装决策。
  static Future<Map<String, dynamic>> decideNextAction({
    required JarvisSettings settings,
    required String goal,
    required String currentStep,
    required String screenTree,
  }) async {
    // 节点树可能很大：截断到 6000 字符，避免 token 爆掉。
    final tree = screenTree.length > 6000
        ? '${screenTree.substring(0, 6000)}\n...[已截断]'
        : screenTree;
    const systemPrompt = '''
你是一个手机 Agent 的看屏决策引擎。根据用户目标、当前步骤和当前屏幕的无障碍节点树，输出下一步动作。
只输出一个 JSON 对象，不要任何多余文字、不要 Markdown 代码块：
{"action": "click|input|back", "target": "要操作的节点文字（与节点树里的 text/desc 一致）", "inputText": "input 时要输入的文字（其余动作可省略）", "expect": "动作成功后屏幕上应该出现的新文字（用于核验，不可省略；实在没有就写目标 App 的关键界面文字）"}
规则：
- 只点屏幕上确实存在的节点；不要编造文字；
- 绝不要点“转账、红包、付款、密码、验证码、删除、点赞、评论、关注”类控件；
- 绝不要向密码 / 验证码输入框输入；
- 不知道该点什么时，把 action 写成 click、target 写成空字符串（调用方会判失败），不要乱猜。
''';
    final response = await LlmService.ask(
      settings: settings,
      question: '目标：$goal\n当前步骤：$currentStep\n屏幕节点树：\n$tree',
      history: [
        {'role': 'system', 'content': systemPrompt},
      ],
      withMemory: false,
    );
    final cleaned = response.replaceAll(RegExp(r'```json|```'), '').trim();
    final start = cleaned.indexOf('{');
    final end = cleaned.lastIndexOf('}');
    if (start < 0 || end < 0 || end <= start) {
      throw Exception('大模型决策返回的不是 JSON：$response');
    }
    final decoded = jsonDecode(cleaned.substring(start, end + 1));
    if (decoded is! Map<String, dynamic>) {
      throw Exception('大模型决策返回的不是 JSON 对象');
    }
    final action = decoded['action']?.toString() ?? '';
    if (action != 'click' && action != 'input' && action != 'back') {
      throw Exception('大模型决策的 action 非法：$action');
    }
    return {
      'action': action,
      'target': decoded['target']?.toString() ?? '',
      'inputText': decoded['inputText']?.toString(),
      'expect': decoded['expect']?.toString() ?? '',
    };
  }

  /// 让大模型生成任务计划（JSON）
  static Future<List<AgentStep>> _planSteps(
    JarvisSettings settings,
    String goal,
    List<Map<String, String>> history,
  ) async {
    final systemPrompt = '''
你是一个高级AI智能体(Agent)。用户将给你一个复杂目标。请将该目标拆解为1到4个连续的执行步骤。
你必须严格以 JSON 数组格式返回，不要包含任何 Markdown 标记或多余废话。
每个步骤包含：
"step_id": 数字序号
"description": 步骤的中文描述
"tool": 可选 "device_action"（用于发短信、打电话、打开地图等跨应用操作）、"web_search"（用于联网查天气、查新闻等）、或 "llm_reasoning"（纯思考写作）
"argument": 传递给工具的具体指令或关键词
例如：
[
{"step_id": 1, "description": "联网查询北京最新天气", "tool": "web_search", "argument": "北京天气"},
{"step_id": 2, "description": "给儿子发信息说今晚回家吃饭", "tool": "device_action", "argument": "给儿子发信息说今晚回家吃饭"}
]
''';

    final response = await LlmService.ask(
      settings: settings,
      question: "用户目标：$goal",
      history: [
        {'role': 'system', 'content': systemPrompt},
        ...history,
      ],
    );

    String cleanJson = response.replaceAll('json', '').replaceAll('', '').trim();
    int start = cleanJson.indexOf('[');
    int end = cleanJson.lastIndexOf(']');
    if (start != -1 && end != -1) {
      cleanJson = cleanJson.substring(start, end + 1);
    }

    final List<dynamic> decoded = jsonDecode(cleanJson);
    return decoded.map((item) => AgentStep.fromJson(item)).toList();
  }

  /// 执行单步任务调度。返回 (结果文本，是否跑通)。
  /// device_action 先走现有 DeviceActions 快路径；快路径不认识（返回 null）
  /// 才降级到 Agent 看屏慢路径——绝不谎称“设备指令已尝试执行”。
  static Future<_StepOutcome> _executeStep(
    AgentStep step, {
    required JarvisSettings settings,
  }) async {
    switch (step.tool) {
      case 'device_action':
        final result = await DeviceActions.tryHandle(step.argument);
        if (result != null) return _StepOutcome(result, true);
        try {
          // 慢路径启动前：先快路径打开目标 App（直接拉起，秒开，不进慢循环）。
          // 这样第一步看屏时前台已经是目标 App（白名单内），不会空数据失败。
          // 找不到目标 App（如纯思考任务）时返回 false，慢路径照常跑。
          await DeviceActions.openAppForTask(step.argument);
          final slow = await AgentLoop.runSlowStep(
            settings: settings,
            goal: step.argument,
            stepDescription: step.description,
            decide: decideNextAction,
          );
          return _StepOutcome(slow, true);
        } catch (e) {
          return _StepOutcome('慢路径执行失败：$e', false);
        }
      case 'web_search':
        try {
          return _StepOutcome(
              await WebSearchService.search(step.argument), true);
        } catch (e) {
          return _StepOutcome('联网查询失败：$e', false);
        }
      case 'llm_reasoning':
      default:
        return _StepOutcome('已完成思考与文本处理：${step.argument}', true);
    }
  }

  /// 构建汇总 Prompt
  static String _buildSummaryPrompt(String goal, List<AgentStep> steps) {
    final sb = StringBuffer();
    sb.writeln('用户的原始目标是：$goal\n');
    sb.writeln('智能体执行了以下步骤及结果：');
    for (var s in steps) {
      sb.writeln('- 步骤 ${s.stepId} (${s.description}):${s.result}');
    }
    sb.writeln('\n请根据以上执行结果，给用户写一段流畅、专业、符合贾维斯管家风格的最终回复。');
    return sb.toString();
  }
}
