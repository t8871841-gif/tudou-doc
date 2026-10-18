import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';

/// 任务黑板：多步任务的持久化状态。
///
///  - 任务执行中 App 被杀 / 重启后，黑板还在，下次说“刚才那件事继续”可续跑；
///  - 失败的任务不清板（只有成功完成才清），否则续跑无从谈起；
///  - 只存任务结构（目标、步骤、进度、错误），不存密码、私聊内容、验证码。
enum TaskStatus { ongoing, waitingConfirmation, completed, failed }

/// 一个执行步骤。tool 决定谁来执行它：
///  - 'device_action'：现有 DeviceActions 快路径（发消息 / 打电话 / 闹钟 / 开 App…）；
///    快路径不认识时，执行器自动降级到 'agent_loop' 慢路径，而不是谎称已执行；
///  - 'web_search'：联网查询；
///  - 'llm_reasoning'：纯思考 / 文本处理；
///  - 'agent_loop'：通用看屏慢路径（看屏 → 大模型决策 → 动手 → 核验）。
class TaskStep {
  String description;
  String tool;
  String argument;
  bool isCompleted;
  String? error;
  int attempts;

  TaskStep({
    required this.description,
    this.tool = 'device_action',
    this.argument = '',
    this.isCompleted = false,
    this.error,
    this.attempts = 0,
  });

  Map<String, dynamic> toJson() => {
        'description': description,
        'tool': tool,
        'argument': argument,
        'isCompleted': isCompleted,
        'error': error,
        'attempts': attempts,
      };

  factory TaskStep.fromJson(Map<String, dynamic> json) => TaskStep(
        description: json['description']?.toString() ?? '',
        tool: json['tool']?.toString() ?? 'device_action',
        argument: json['argument']?.toString() ?? '',
        isCompleted: json['isCompleted'] == true,
        error: json['error']?.toString(),
        attempts: (json['attempts'] is num) ? (json['attempts'] as num).toInt() : 0,
      );
}

class TaskBoard {
  String id;
  String goal;
  List<TaskStep> steps;
  int currentStepIndex;
  String? pendingQuestion;
  Map<String, dynamic> results;
  TaskStatus status;
  DateTime createdAt;
  DateTime updatedAt;

  TaskBoard({
    String? id,
    required this.goal,
    required this.steps,
    this.currentStepIndex = 0,
    this.pendingQuestion,
    Map<String, dynamic>? results,
    this.status = TaskStatus.ongoing,
    DateTime? createdAt,
    DateTime? updatedAt,
  })  : id = id ?? DateTime.now().millisecondsSinceEpoch.toString(),
        results = results ?? {},
        createdAt = createdAt ?? DateTime.now(),
        updatedAt = updatedAt ?? DateTime.now();

  /// 当前要执行的步骤；越界返回 null（调用方不得直接下标访问）。
  TaskStep? get currentStep =>
      (currentStepIndex >= 0 && currentStepIndex < steps.length)
          ? steps[currentStepIndex]
          : null;

  bool get hasRemaining => currentStepIndex < steps.length;

  Map<String, dynamic> toJson() => {
        'id': id,
        'goal': goal,
        'steps': steps.map((s) => s.toJson()).toList(),
        'currentStepIndex': currentStepIndex,
        'pendingQuestion': pendingQuestion,
        'results': results,
        'status': status.index,
        'createdAt': createdAt.toIso8601String(),
        'updatedAt': updatedAt.toIso8601String(),
      };

  factory TaskBoard.fromJson(Map<String, dynamic> json) {
    // status 越界保护：脏数据不许把 App 搞崩， fallback 到 ongoing。
    var status = TaskStatus.ongoing;
    final rawStatus = json['status'];
    if (rawStatus is int &&
        rawStatus >= 0 &&
        rawStatus < TaskStatus.values.length) {
      status = TaskStatus.values[rawStatus];
    }
    DateTime parseTime(dynamic v, DateTime fallback) {
      if (v is String) return DateTime.tryParse(v) ?? fallback;
      return fallback;
    }

    final now = DateTime.now();
    return TaskBoard(
      id: json['id']?.toString() ?? now.millisecondsSinceEpoch.toString(),
      goal: json['goal']?.toString() ?? '',
      steps: (json['steps'] as List?)
              ?.map((s) => TaskStep.fromJson(
                  s is Map<String, dynamic> ? s : <String, dynamic>{}))
              .toList() ??
          [],
      currentStepIndex:
          (json['currentStepIndex'] is num) ? (json['currentStepIndex'] as num).toInt() : 0,
      pendingQuestion: json['pendingQuestion']?.toString(),
      results: json['results'] is Map
          ? Map<String, dynamic>.from(json['results'] as Map)
          : <String, dynamic>{},
      status: status,
      createdAt: parseTime(json['createdAt'], now),
      updatedAt: parseTime(json['updatedAt'], now),
    );
  }

  static const String _storageKey = 'current_task_board';

  Future<void> save() async {
    try {
      updatedAt = DateTime.now();
      final prefs = await SharedPreferences.getInstance();
      // results 里若混进非 JSON 类型，jsonEncode 会抛：save 失败不许拖垮任务。
      await prefs.setString(_storageKey, jsonEncode(toJson()));
    } catch (_) {}
  }

  static Future<TaskBoard?> load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final jsonStr = prefs.getString(_storageKey);
      if (jsonStr == null) return null;
      final decoded = jsonDecode(jsonStr);
      if (decoded is! Map<String, dynamic>) return null;
      return TaskBoard.fromJson(decoded);
    } catch (_) {
      return null;
    }
  }

  static Future<void> clear() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove(_storageKey);
    } catch (_) {}
  }
}
