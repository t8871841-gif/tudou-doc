import 'dart:convert';
import 'action_service.dart';
import 'llm_service.dart';
import 'settings_store.dart';
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

class AgentService {
  /// 1. 核心规划与执行入口
  static Future<String> runAgent({
    required JarvisSettings settings,
    required String userGoal,
    required List<Map<String, String>> history,
    required Function(String statusMessage) onProgress,
  }) async {
    onProgress('正在分析目标并规划任务...');

    List<AgentStep> steps;
    try {
      steps = await _planSteps(settings, userGoal, history);
    } catch (e) {
      onProgress('任务规划失败，转为常规问答...');
      return await LlmService.ask(settings: settings, question: userGoal, history: history);
    }

    if (steps.isEmpty) {
      return await LlmService.ask(settings: settings, question: userGoal, history: history);
    }

    // 2. 循环执行每个子步骤
    for (var i = 0; i < steps.length; i++) {
      final step = steps[i];
      onProgress('执行步骤 [${i + 1}/${steps.length}]:${step.description}');

      try {
        step.result = await _executeStep(step);
      } catch (e) {
        step.result = '执行出错: $e';
      }

      // 某步留下了“等用户确认”（确认发送 / 选联系人）：停下，
      // 后续步骤等用户答复后再说——别让下一步把确认当成新指令吃掉。
      if (DeviceActions.hasPending) break;
    }

    // 设备动作的结果原样返回，跳过大模型汇总：
    //  - 有挂起确认时，确认问句必须原样到用户；
    //  - 只有一步设备动作时，结果本身就是完整回复，汇总只会改写甚至软化“没发成”。
    // 多步混合任务仍走汇总。
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

    // 3. 汇总所有步骤的结果给用户
    onProgress('正在汇总执行结果...');
    final summaryPrompt = _buildSummaryPrompt(userGoal, steps);
    final finalAnswer = await LlmService.ask(
      settings: settings,
      question: summaryPrompt,
      history: history,
    );

    return finalAnswer;
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

  /// 执行单步任务调度
  static Future<String> _executeStep(AgentStep step) async {
    switch (step.tool) {
      case 'device_action':
        final result = await DeviceActions.tryHandle(step.argument);
        return result ?? '设备指令已尝试执行：${step.argument}';
      case 'web_search':
        return await WebSearchService.search(step.argument);
      case 'llm_reasoning':
      default:
        return '已完成思考与文本处理：${step.argument}';
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
