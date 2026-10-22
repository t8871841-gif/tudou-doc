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
/// v3：看屏流程里的一步——界面动作 + 这一步的核验点（动作后屏幕上应出现的文字）。
/// 注意：不存密码 / 验证码；target / input / expect 里的变量已换成 {占位符}。
class UiStep {
  String action; // click | input | scroll | back
  String target;
  String input;
  String expect;
  UiStep(this.action, this.target, this.input, this.expect);

  UiStep copy() => UiStep(action, target, input, expect);

  Map<String, dynamic> toJson() => {'a': action, 't': target, 'i': input, 'e': expect};

  factory UiStep.fromJson(Map<String, dynamic> j) => UiStep(j['a']?.toString() ?? '',
      j['t']?.toString() ?? '', j['i']?.toString() ?? '', j['e']?.toString() ?? '');
}

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

  /// v3：成功的看屏流程（界面步骤 + 每步核验点）。空 = 这个 skill 只有规划步骤（v2 行为）。
  List<UiStep> uiSteps;

  /// v3：带变量的目标模板，如“在X里给{p1}发{p2}”。仅 generic 意图使用；为空时仍按整句 goalKey 匹配。
  String goalPattern;

  Skill({
    required this.intent,
    required this.goalKey,
    required this.steps,
    this.verificationStandard = '',
    this.failureCount = 0,
    this.isActive = true,
    DateTime? updatedAt,
    List<UiStep>? uiSteps,
    this.goalPattern = '',
  })  : updatedAt = updatedAt ?? DateTime.now(),
        uiSteps = uiSteps ?? [];

  Map<String, dynamic> toJson() => {
        'intent': intent,
        'goalKey': goalKey,
        'steps': steps.map((s) => s.toJson()).toList(),
        'verificationStandard': verificationStandard,
        'failureCount': failureCount,
        'isActive': isActive,
        'updatedAt': updatedAt.toIso8601String(),
        'uiSteps': uiSteps.map((s) => s.toJson()).toList(),
        'goalPattern': goalPattern,
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
      uiSteps: (json['uiSteps'] as List?)
              ?.map((s) => UiStep.fromJson(
                  s is Map ? Map<String, dynamic>.from(s) : <String, dynamic>{}))
              .toList() ??
          [],
      goalPattern: json['goalPattern']?.toString() ?? '',
    );
  }

  /// 把看屏步骤里的 {占位符} 换成这次的真实参数（回放前调用）。
  List<UiStep> instantiateUi(Map<String, String> params) {
    String fill(String t) {
      var r = t;
      params.forEach((k, v) => r = r.replaceAll('{$k}', v));
      return r;
    }

    return uiSteps.map((s) => UiStep(s.action, fill(s.target), fill(s.input), fill(s.expect))).toList();
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
      if (intent == 'generic') {
        if (skill.goalPattern.isNotEmpty) {
          // v3：带变量的模板——从当前原话里取变量值；命不中就退回“整句相同”才复用
          final pm = _matchPattern(skill.goalPattern, goal.trim());
          if (pm != null) return MatchedSkill(skill, pm);
        }
        if (skill.goalKey != goal.trim()) continue;
      }
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
    final index = _findIndex(skills, intent, goal);
    if (index >= 0) {
      final byPattern = skills[index].goalPattern.isNotEmpty &&
          skills[index].goalKey != goal.trim() &&
          _matchPattern(skills[index].goalPattern, goal.trim()) != null;
      if (byPattern) {
        // 这次是用变量模板复用成功的：只复位失败计数，不拿这次的具体值覆盖模板。
        skills[index]
          ..failureCount = 0
          ..isActive = true
          ..updatedAt = DateTime.now();
      } else {
        skills[index]
          ..steps = templates
          ..failureCount = 0
          ..isActive = true
          ..updatedAt = DateTime.now();
      }
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
    final index = _findIndex(skills, intent, goal);
    if (index >= 0) {
      skills[index].failureCount++;
      if (skills[index].failureCount >= 2) {
        skills[index].isActive = false;
      }
      skills[index].updatedAt = DateTime.now();
      await saveSkills(skills);
    }
  }

  // ---------------- v3：看屏流程固化（在现有 SkillStore 上扩展） ----------------

  static int _findIndex(List<Skill> skills, String intent, String goal) {
    return skills.indexWhere((s) =>
        s.intent == intent &&
        (intent != 'generic' ||
            s.goalKey == goal.trim() ||
            (s.goalPattern.isNotEmpty && _matchPattern(s.goalPattern, goal.trim()) != null)));
  }

  /// 模板（含 {p1}…）→ 正则，从原话里取变量值；对不上返回 null。
  static Map<String, String>? _matchPattern(String pattern, String goal) {
    final ph = RegExp(r'\{(\w+)\}');
    final names = ph.allMatches(pattern).map((m) => m.group(1)!).toList();
    if (names.isEmpty) return pattern == goal ? <String, String>{} : null;
    final parts = pattern.split(ph);
    final re = RegExp('^${parts.map(RegExp.escape).join('(.+?)')}\$');
    final m = re.firstMatch(goal);
    if (m == null) return null;
    final out = <String, String>{};
    for (var i = 0; i < names.length && i + 1 <= m.groupCount; i++) {
      out.putIfAbsent(names[i], () => m.group(i + 1)!.trim());
    }
    return out;
  }

  static final _secret = RegExp(r'验证码|密码|口令|\d{6,}');

  /// 任务成功后，判断这次的看屏轨迹值不值得询问用户保存：
  /// 至少 2 步、整个任务只有 1 个看屏步骤、不是回放出来的、不含密码 / 验证码类内容。
  static List<UiStep>? buildUiOffer(List<UiStep> trace,
      {required int slowSteps, required bool replayed}) {
    if (trace.length < 2 || slowSteps != 1 || replayed) return null;
    if (trace.any((s) => _secret.hasMatch(s.input) || _secret.hasMatch(s.target))) return null;
    return trace.map((s) => s.copy()).toList();
  }

  /// 把看屏流程挂到已存在的 skill 上（recordSuccess 已在任务成功时建好）。
  /// 参数化：call / message / navigate 沿用既有参数名（{contact}{body}{destination}）；
  /// generic：流程里“打进去的文字”且原话里也有的词（≥2 字）变成 {p1}{p2}…，目标句式同步模板化，
  /// 规划步骤里的同一批词也换成占位符，保证复用时整条链路用的是新值。
  /// 返回变量说明文字（给用户确认）；找不到对应 skill 返回 null。
  static Future<String?> attachUiSteps(String goal, List<UiStep> trace) async {
    final intent = _extractIntent(goal);
    final skills = await loadSkills();
    final index = _findIndex(skills, intent, goal);
    if (index < 0) return null;
    final sk = skills[index];
    final tokens = <String, String>{};
    final params = _extractParams(goal, intent);
    if (intent != 'generic' && params != null) {
      tokens.addAll(params);
    } else {
      // 保守：只把“打进去的文字（input）”当变量。点击的目标（按钮 / 列表项名）保持字面量写进模板，
      // 这样换了别的值就匹配不上、不会复用，而不是拿旧的点击目标去乱点。
      var n = 0;
      for (final s in trace) {
        final v = s.input;
        if (v.length >= 2 && goal.contains(v) && !tokens.containsValue(v)) {
          tokens['p${++n}'] = v;
        }
      }
    }
    String sub(String t) {
      var r = t;
      tokens.forEach((k, v) {
        if (v.length >= 2) r = r.replaceAll(v, '{$k}');
      });
      return r;
    }

    sk.uiSteps = trace.map((s) => UiStep(s.action, sub(s.target), sub(s.input), sub(s.expect))).toList();
    if (intent == 'generic' && tokens.isNotEmpty) {
      sk.goalPattern = sub(goal.trim());
      sk.steps = sk.steps
          .map((st) => TaskStep(description: sub(st.description), tool: st.tool, argument: sub(st.argument)))
          .toList();
    }
    sk.failureCount = 0;
    sk.isActive = true;
    sk.updatedAt = DateTime.now();
    await saveSkills(skills);
    return tokens.isEmpty
        ? '没有变量：只有原话完全相同才会复用'
        : '变量 ${tokens.entries.map((e) => '{${e.key}}=${e.value}').join('，')}';
  }

  /// 回放失败：丢掉过期的界面步骤（规划步骤保留），下次任务成功后会重新询问是否保存。
  static Future<void> invalidateUiSteps(Skill skill) async {
    final skills = await loadSkills();
    final i = skills.indexWhere((s) => s.intent == skill.intent && s.goalKey == skill.goalKey);
    if (i >= 0) {
      skills[i].uiSteps = [];
      skills[i].updatedAt = DateTime.now();
      await saveSkills(skills);
    }
  }

  /// 设置页“已学会的流程”：带界面步骤的 skill。
  static Future<List<Skill>> uiSkills() async =>
      (await loadSkills()).where((s) => s.uiSteps.isNotEmpty).toList();

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


/// 一次任务期间的看屏轨迹（AgentLoop 写，任务成功后决定是否询问保存）。纯内存，不持久化。
class UiTrace {
  static String goal = '';
  static final List<UiStep> steps = [];
  static int slowSteps = 0; // 本次任务里走看屏慢路径的步骤数
  static bool replayed = false; // 本次是技能回放（回放成功不再提示保存）
  static MatchedSkill? matched; // 本次命中的 skill（可能带界面步骤）
  static List<UiStep>? offer; // 等用户确认保存的候选
  static String offerGoal = '';

  static void begin(String g) {
    goal = g;
    steps.clear();
    slowSteps = 0;
    replayed = false;
    matched = null;
    offer = null; // 新任务开始：旧的保存询问作废
  }

  static void add(UiStep s) => steps.add(s);

  /// 用户确认保存。返回变量说明；没有候选 / 找不到 skill 返回 null。
  static Future<String?> saveOffer() async {
    final o = offer;
    if (o == null) return null;
    final r = await SkillStore.attachUiSteps(offerGoal, o);
    offer = null;
    return r;
  }
}
