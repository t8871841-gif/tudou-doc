import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import 'task_board.dart';

/// 技能库：跑通的任务沉淀为“意图 + 参数化步骤模板”，下次同类任务直接复用，不用再走大模型规划。
///
/// 关键设计（上一版的教训）：
///  - 不按整句话匹配：按“意图”分桶（打电话 / 发消息 / 导航 / 其他），
///    每次从当前用户原话里重新提取参数（联系人 / 正文 / 目的地），填进步骤模板的 {占位符}；
///    这样“给张三打电话”存的 skill，“给李四打电话”也能用，而且不会拿错人。
///  - 参数提取失败 → 不匹配（宁可不复用，也不拿错参数乱执行）；
///  - 只存步骤模板和核实标准，不存密码、私聊内容、验证码；
///  - 失败两次自动停用（App 改版导致步骤失效时，不会一直拿旧步骤撞墙）。
class Skill {
  /// 意图：'call' | 'message' | 'navigate' | 'generic'。
  String intent;

  /// 原始目标（generic 意图按整句匹配时用；其余意图仅作记录）。
  String goalKey;

  /// 步骤模板：argument 里的具体参数值已换成 {contact} / {body} / {destination} 占位符。
  List<TaskStep> steps;

  /// 核实标准：执行完后怎样才算成功（给执行器和用户看，不自动判）。
  String verificationStandard;

  int failureCount;
  bool isActive;
  DateTime updatedAt;

  Skill({
    required this.intent,
    required this.goalKey,
    required this.steps,
    this.verificationStandard = '',
    this.failureCount = 0,
    this.isActive = true,
    DateTime? updatedAt,
  }) : updatedAt = updatedAt ?? DateTime.now();

  Map<String, dynamic> toJson() => {
        'intent': intent,
        'goalKey': goalKey,
        'steps': steps.map((s) => s.toJson()).toList(),
        'verificationStandard': verificationStandard,
        'failureCount': failureCount,
        'isActive': isActive,
        'updatedAt': updatedAt.toIso8601String(),
      };

  factory Skill.fromJson(Map<String, dynamic> json) {
    DateTime parseTime(dynamic v) {
      if (v is String) return DateTime.tryParse(v) ?? DateTime.now();
      return DateTime.now();
    }

    return Skill(
      intent: json['intent']?.toString() ?? 'generic',
      goalKey: json['goalKey']?.toString() ?? '',
      steps: (json['steps'] as List?)
              ?.map((s) => TaskStep.fromJson(
                  s is Map<String, dynamic> ? s : <String, dynamic>{}))
              .toList() ??
          [],
      verificationStandard: json['verificationStandard']?.toString() ?? '',
      failureCount:
          (json['failureCount'] is num) ? (json['failureCount'] as num).toInt() : 0,
      isActive: json['isActive'] != false,
      updatedAt: parseTime(json['updatedAt']),
    );
  }

  /// 把模板里的 {占位符} 换成这次任务的真实参数，生成可执行步骤。
  List<TaskStep> instantiate(Map<String, String> params) {
    return steps.map((s) {
      var arg = s.argument;
      params.forEach((key, value) {
        arg = arg.replaceAll('{$key}', value);
      });
      return TaskStep(
        description: s.description,
        tool: s.tool,
        argument: arg,
      );
    }).toList();
  }
}

/// 一次匹配的结果：skill + 这次从用户原话里提取的参数。
class MatchedSkill {
  final Skill skill;
  final Map<String, String> parameters;
  MatchedSkill(this.skill, this.parameters);
}

class SkillStore {
  static const String _storageKey = 'jarvis_skills_store';

  /// 占位符名：调用方（AgentService）按这套名字填模板。
  static const paramContact = 'contact';
  static const paramBody = 'body';
  static const paramDestination = 'destination';

  static const Map<String, String> _standards = {
    'call': '拨出成功，且通话记录可核实（或权限未授予时如实说明无法核实）。',
    'message': '发送动作完成，且界面核实到 Jarvis 自己刚发的那句话出现。',
    'navigate': '地图 App 已进入导航界面且目的地正确。',
    'generic': '各步执行完成，且最后一步的回复不含失败表述。',
  };

  static Future<List<Skill>> loadSkills() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final jsonStr = prefs.getString(_storageKey);
      if (jsonStr == null) return [];
      final decoded = jsonDecode(jsonStr);
      if (decoded is! List) return [];
      return decoded
          .map((s) => Skill.fromJson(
              s is Map<String, dynamic> ? s : <String, dynamic>{}))
          .toList();
    } catch (_) {
      return [];
    }
  }

  static Future<void> saveSkills(List<Skill> skills) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(
          _storageKey, jsonEncode(skills.map((s) => s.toJson()).toList()));
    } catch (_) {}
  }

  /// 按意图匹配。参数必须能从当前原话里提取出来，否则不匹配。
  static Future<MatchedSkill?> matchSkill(String goal) async {
    final intent = _extractIntent(goal);
    final params = _extractParams(goal, intent);
    if (params == null) return null;
    final skills = await loadSkills();
    for (final skill in skills) {
      if (!skill.isActive || skill.intent != intent) continue;
      if (intent == 'generic' && skill.goalKey != goal.trim()) continue;
      return MatchedSkill(skill, params);
    }
    return null;
  }

  /// 任务跑完（黑板全部步骤执行完毕）后调用：把步骤存成模板。
  static Future<void> recordSuccess(
      String goal, List<TaskStep> steps) async {
    final intent = _extractIntent(goal);
    final params = _extractParams(goal, intent);
    final templates = params == null
        ? steps // 参数提不出来：原样存（generic 整句匹配时用）
        : steps.map((s) => _templatize(s, params)).toList();
    final skills = await loadSkills();
    final index = skills.indexWhere((s) =>
        s.intent == intent &&
        (intent != 'generic' || s.goalKey == goal.trim()));
    if (index >= 0) {
      skills[index]
        ..steps = templates
        ..failureCount = 0
        ..isActive = true
        ..updatedAt = DateTime.now();
    } else {
      skills.add(Skill(
        intent: intent,
        goalKey: goal.trim(),
        steps: templates,
        verificationStandard: _standards[intent] ?? _standards['generic']!,
      ));
    }
    await saveSkills(skills);
  }

  /// 任务失败后调用：同意图 skill 失败计数 +1，满 2 次自动停用。
  static Future<void> recordFailure(String goal) async {
    final intent = _extractIntent(goal);
    final skills = await loadSkills();
    final index = skills.indexWhere((s) =>
        s.intent == intent &&
        (intent != 'generic' || s.goalKey == goal.trim()));
    if (index >= 0) {
      skills[index].failureCount++;
      if (skills[index].failureCount >= 2) {
        skills[index].isActive = false;
      }
      skills[index].updatedAt = DateTime.now();
      await saveSkills(skills);
    }
  }

  /// 供 App 内查看 / 删除（用户可手动清理错学的 skill）。
  static Future<void> deleteSkill(String intent, {String? goalKey}) async {
    final skills = await loadSkills();
    skills.removeWhere((s) =>
        s.intent == intent && (goalKey == null || s.goalKey == goalKey));
    await saveSkills(skills);
  }

  // ---------------- 意图与参数提取（best-effort，提不出就返回 null） ----------------

  static String _extractIntent(String goal) {
    if (RegExp(r'打个?电话|拨个?电话|拨打|打给|呼叫|拨个?号').hasMatch(goal)) {
      return 'call';
    }
    if (RegExp(r'发(信息|消息|短信)|告诉.+说').hasMatch(goal)) return 'message';
    if (goal.contains('导航')) return 'navigate';
    return 'generic';
  }

  /// 从原话提取参数。提不出 → null（调用方不得用空参数执行）。
  static Map<String, String>? _extractParams(String goal, String intent) {
    switch (intent) {
      case 'call': {
        final phone =
            RegExp(r'1[3-9]\d{9}').firstMatch(goal.replaceAll(RegExp(r'[\s\-]'), ''))?.group(0);
        if (phone != null) return {paramContact: phone};
        var q = RegExp(r'给(.{1,12}?)[打拨]个?电话').firstMatch(goal)?.group(1);
        q ??= RegExp(r'(?:打个?电话|拨个?电话|拨打|打给|呼叫|拨个?号)(?:给|到)?(.*)$')
                .firstMatch(goal)
                ?.group(1) ??
            '';
        q = q
            .replaceAll(RegExp(r'^(请|帮我|麻烦|我要|我想|帮忙)+'), '')
            .replaceAll(RegExp(r'(的电话|的号码|的手机|电话|一下|吧|啊|谢谢|好吗)+$'), '')
            .replaceAll(RegExp(r'[\s，。,.！？!?、：:“”"]'), '')
            .replaceAll(RegExp(r'的$'), '');
        if (q.isEmpty) return null;
        return {paramContact: q};
      }
      case 'message': {
        // “在微信上给张三发消息说晚上回家” → {contact: 张三, body: 晚上回家}
        final m = RegExp(r'给(.{1,12}?)(?:发信息|发消息|发短信).*?[说:：](.+)$')
            .firstMatch(goal);
        if (m == null) return null;
        final contact = m
            .group(1)!
            .replaceAll(RegExp(r'[\s，。,.！？!?、：:“”"]'), '');
        final body = m.group(2)!.trim();
        if (contact.isEmpty || body.isEmpty) return null;
        return {paramContact: contact, paramBody: body};
      }
      case 'navigate': {
        final m = RegExp(r'导航(?:到|去)?(.+)$').firstMatch(goal);
        final dest = (m?.group(1) ?? '')
            .replaceAll(RegExp(r'(的导航|导航|一下|吧|啊|谢谢|好吗)+$'), '')
            .trim();
        if (dest.isEmpty) return null;
        return {paramDestination: dest};
      }
      default:
        return <String, String>{};
    }
  }

  /// 把步骤 argument 里的具体参数值换成 {占位符}（只换长度 ≥2 的值，避免单字串改其他词）。
  static TaskStep _templatize(TaskStep step, Map<String, String> params) {
    var arg = step.argument;
    params.forEach((key, value) {
      if (value.length >= 2) arg = arg.replaceAll(value, '{$key}');
    });
    return TaskStep(
      description: step.description,
      tool: step.tool,
      argument: arg,
    );
  }
}
