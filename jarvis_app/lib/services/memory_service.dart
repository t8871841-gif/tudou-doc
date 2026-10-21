import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';

import 'llm_service.dart';
import 'settings_store.dart';

/// 命令解析结果：kind = remember | forget | forgetAll | list
class MemoryCommand {
  final String kind;
  final String arg;
  const MemoryCommand(this.kind, this.arg);
}

/// 一条长期记忆：text=内容，cat=profile(稳定信息)|pref(对Jarvis的偏好)|plan(计划日期)|event(近期事件)，ts=写入时间。
class MemoryItem {
  String text;
  String cat;
  int ts;
  MemoryItem(this.text, this.cat, this.ts);
  Map<String, dynamic> toJson() => {'t': text, 'c': cat, 'ts': ts};
}

/// Jarvis 的记忆：
///  1. 对话记录：每次回答后保存到本机，下次打开自动恢复；
///  2. 长期记忆：关于用户的稳定信息（名字、偏好、家人、习惯、计划……），
///     每次提问都会放进系统提示词，所以 Jarvis 跨会话也认识你。
/// 数据只存在应用私有目录（jarvis_memory/*.json），不会上传到别处；
/// 但长期记忆会随每次提问发给你在设置里配置的大模型服务。
class MemoryService {
  MemoryService._();
  static final MemoryService instance = MemoryService._();

  static const maxFacts = 60;
  static const maxFactLength = 80;
  static const maxChat = 200;

  static const cats = {'profile', 'pref', 'plan', 'event'};
  final List<MemoryItem> _items = [];

  /// 只读文本视图（设置页 / 口令按下标删除，顺序与内部一致）。
  List<String> get facts =>
      List<String>.unmodifiable(_items.map((e) => e.text));
  List<Map<String, String>> chat = [];
  bool _chatCleared = false;

  // ---------- 文件 ----------

  Future<Directory> _dir() async {
    final base = await getApplicationDocumentsDirectory();
    final d = Directory('${base.path}/jarvis_memory');
    if (!await d.exists()) await d.create(recursive: true);
    return d;
  }

  Future<void> _write(String name, Object data) async {
    final d = await _dir();
    final tmp = File('${d.path}/$name.tmp');
    await tmp.writeAsString(jsonEncode(data), flush: true);
    await tmp.rename('${d.path}/$name');
  }

  Future<dynamic> _read(String name) async {
    try {
      final f = File('${(await _dir()).path}/$name');
      if (!await f.exists()) return null;
      return jsonDecode(await f.readAsString());
    } catch (_) {
      return null; // 文件损坏就当没有，不影响启动
    }
  }

  Future<void> load() async {
    final f = await _read('facts.json');
    _items.clear();
    final now = DateTime.now().millisecondsSinceEpoch;
    if (f is List) {
      for (final x in f) {
        if (x is String) {
          _items.add(MemoryItem(x, 'profile', now)); // 旧格式迁移
        } else if (x is Map && x['t'] is String) {
          final c = x['c']?.toString() ?? 'profile';
          _items.add(MemoryItem(x['t'] as String, cats.contains(c) ? c : 'profile',
              (x['ts'] is num) ? (x['ts'] as num).toInt() : now));
        }
      }
    }
    // 近期事件只留 90 天，其余长期保留。
    _items.removeWhere(
        (e) => e.cat == 'event' && now - e.ts > 90 * 86400000);
    final c = await _read('chat.json');
    chat = [
      if (c is List)
        for (final m in c)
          if (m is Map && m['role'] is String && m['text'] is String)
            {'role': m['role'] as String, 'text': m['text'] as String},
    ];
  }

  Future<void> _saveFacts() async {
    try {
      await _write('facts.json', _items.map((e) => e.toJson()).toList());
    } catch (_) {}
  }

  Future<void> saveChat(List<Map<String, String>> messages) async {
    final start =
        messages.length > maxChat ? messages.length - maxChat : 0;
    chat = messages.sublist(start);
    try {
      await _write('chat.json', chat);
    } catch (_) {}
  }

  // ---------- 对话记录 ----------

  Future<void> clearChat() async {
    chat = [];
    _chatCleared = true;
    try {
      await _write('chat.json', chat);
    } catch (_) {}
  }

  /// 设置页清空了对话记录后，主页读取一次这个标记来清屏。
  bool consumeChatCleared() {
    final v = _chatCleared;
    _chatCleared = false;
    return v;
  }

  // ---------- 长期记忆 ----------

  static const _header = '【长期记忆】以下是你已经了解的关于用户的信息。'
      '回答时自然地用上，不要逐条复述，也不要说“根据记忆”；'
      '与用户当前说的不一致时，以用户当前说的为准。';

  /// 全量记忆块（兼容旧调用；没有记忆时返回空串）。
  String contextBlock() => contextFor('', maxItems: 24);

  static Set<String> _bigrams(String s) {
    final t = s.toLowerCase().replaceAll(RegExp(r'[\s，。！？、,.!?;；:：]'), '');
    final out = <String>{};
    for (var i = 0; i + 2 <= t.length; i++) {
      out.add(t.substring(i, i + 2));
    }
    return out;
  }

  /// 按当前问题检索：稳定信息（profile/pref）优先全带，
  /// 计划/事件按“和问题相关 + 越近越优先”取 top-k，其余只在相关时带上。
  String contextFor(String query, {int maxItems = 18}) {
    if (_items.isEmpty) return '';
    final now = DateTime.now().millisecondsSinceEpoch;
    final core = _items
        .where((e) => e.cat == 'profile' || e.cat == 'pref')
        .toList()
      ..sort((a, b) => b.ts.compareTo(a.ts));
    final picked = <MemoryItem>[...core.take(maxItems > 12 ? 10 : 6)];
    final q = _bigrams(query);
    final rest = _items.where((e) => !picked.contains(e)).toList();
    final scored = <MapEntry<MemoryItem, double>>[];
    for (final e in rest) {
      final overlap = q.isEmpty ? 0 : _bigrams(e.text).intersection(q).length;
      final ageDays = (now - e.ts) / 86400000;
      final recent = (e.cat == 'plan' || e.cat == 'event')
          ? 0.5 / (1 + ageDays / 7)
          : 0.0;
      if (query.isEmpty || overlap > 0 || recent > 0.25) {
        scored.add(MapEntry(e, (overlap > 6 ? 6 : overlap) * 2 + recent));
      }
    }
    scored.sort((a, b) => b.value.compareTo(a.value));
    for (final e in scored) {
      if (picked.length >= maxItems) break;
      picked.add(e.key);
    }
    if (picked.isEmpty) return '';
    final sb = StringBuffer()..writeln(_header);
    for (final f in picked) {
      sb.writeln('- ${f.text}');
    }
    return sb.toString().trimRight();
  }

  static String _clean(String s) {
    var t = s.replaceAll(RegExp(r'\s+'), ' ').trim();
    if (t.length > maxFactLength) t = '${t.substring(0, maxFactLength)}…';
    return t;
  }

  Future<void> addFact(String text) async {
    final t = _clean(text);
    if (t.isEmpty) return;
    _items.removeWhere((f) => f.text == t);
    final isPlan = RegExp(r'明天|后天|下周|\d+月\d+|周[一二三四五六日]|生日|截止|考试|会议').hasMatch(t);
    _items.add(MemoryItem(t, isPlan ? 'plan' : 'profile',
        DateTime.now().millisecondsSinceEpoch));
    while (_items.length > maxFacts) {
      _items.removeAt(0);
    }
    await _saveFacts();
  }

  Future<void> removeAt(int i) async {
    if (i < 0 || i >= _items.length) return;
    _items.removeAt(i);
    await _saveFacts();
  }

  /// 删除包含关键词的记忆，返回删除条数。
  Future<int> removeMatching(String keyword) async {
    final k = keyword.trim();
    if (k.isEmpty) return 0;
    final before = _items.length;
    _items.removeWhere((f) => f.text.contains(k));
    final n = before - _items.length;
    if (n > 0) await _saveFacts();
    return n;
  }

  bool hasMatch(String keyword) {
    final k = keyword.trim();
    return k.isNotEmpty && _items.any((f) => f.text.contains(k));
  }

  Future<void> clearFacts() async {
    _items.clear();
    await _saveFacts();
  }

  // ---------- 自动整理 ----------

  /// 让大模型根据最近的对话，更新长期记忆（后台静默执行，失败就算了）。
  Future<void> consolidate(
      JarvisSettings settings, List<Map<String, String>> recent) async {
    if (!settings.configured || recent.isEmpty) return;
    final dialog = StringBuffer();
    for (final m in recent) {
      dialog.writeln('${m['role'] == 'user' ? '用户' : 'Jarvis'}：${m['content']}');
    }
    final existing = jsonEncode(_items.map((e) => {'t': e.text, 'c': e.cat}).toList());
    final system = '你是记忆整理器。根据“已有记忆”和“最近对话”，输出更新后的完整长期记忆列表。\n'
        '规则：\n'
        '1. 只保留关于用户本人的、长期稳定的信息：称呼/名字、家人朋友、居住地、职业、'
        '喜好与忌口、习惯、重要的计划和日期、对 Jarvis 的使用偏好。\n'
        '2. 不要记录：一次性的问题、闲聊、天气新闻等时效信息、Jarvis 自己说的话。\n'
        '3. 绝对不要记录密码、验证码、银行卡号、身份证号等敏感信息。\n'
        '4. 新信息与旧记忆冲突时，用新的替换旧的；重复的合并。\n'
        '5. 每条不超过 $maxFactLength 个字，用第三人称短句，例如“用户叫小明”“用户不吃辣”。最多 $maxFacts 条。\n'
        '6. 每条带分类 c：profile=稳定个人信息；pref=对 Jarvis 的使用偏好；plan=带日期的计划；'
        'event=近期发生的值得记住的事（文字里写上日期，如“10月20日用户说在准备面试”）。\n'
        '7. 只返回 JSON 数组，元素形如 {\"t\":\"用户不吃辣\",\"c\":\"profile\"}，不要任何解释、不要 Markdown。'
        '没有变化就原样返回已有记忆。';
    final String resp;
    try {
      resp = await LlmService.ask(
        settings: settings,
        question: '已有记忆：$existing\n\n最近对话：\n$dialog\n请输出更新后的 JSON 数组。',
        history: [
          {'role': 'system', 'content': system},
        ],
        withMemory: false,
        plain: false,
        temperature: 0.2,
        purpose: LlmPurpose.consolidate,
      );
    } catch (_) {
      return;
    }
    final s = resp.indexOf('[');
    final e = resp.lastIndexOf(']');
    if (s < 0 || e <= s) return;
    try {
      final decoded = jsonDecode(resp.substring(s, e + 1));
      if (decoded is! List) return;
      final now = DateTime.now().millisecondsSinceEpoch;
      final old = {for (final e in _items) e.text: e};
      final next = <MemoryItem>[];
      for (final x in decoded) {
        String? raw;
        var c = 'profile';
        if (x is String) {
          raw = x;
        } else if (x is Map) {
          raw = (x['t'] ?? x['text'])?.toString();
          final cc = x['c']?.toString() ?? 'profile';
          if (cats.contains(cc)) c = cc;
        }
        if (raw == null) continue;
        final t = _clean(raw);
        if (t.isEmpty || next.any((e) => e.text == t)) continue;
        // 没变的条目保留原时间戳（时间戳决定“近期”权重）。
        next.add(MemoryItem(t, c, old[t]?.ts ?? now));
      }
      // 防御：模型突然返回空列表时不清空已有记忆（清空只能由用户明确操作）。
      if (next.isEmpty && _items.isNotEmpty) return;
      final trimmed =
          next.length > maxFacts ? next.sublist(next.length - maxFacts) : next;
      _items
        ..clear()
        ..addAll(trimmed);
      await _saveFacts();
    } catch (_) {}
  }

  // ---------- 口令（本地处理，不走大模型） ----------

  static final _prefix = RegExp(r'^(贾维斯|jarvis)[，,、 ]*', caseSensitive: false);
  static final _forgetAll = RegExp(
      r'^(请|帮我|麻烦)?(你)?(清空|清除|删除|忘掉|忘记)(你的|所有的?|全部的?|我的)*(长期)?记忆[。.!！]?$');
  static final _forgetOne =
      RegExp(r'^(请|帮我|麻烦)?(你)?(忘记|忘掉)[：:，, ]*(.+)$');
  static final _remember = RegExp(
      r'^(请|帮我|麻烦)?(你)?(记住|记一下|记下|牢记)[：:，, ]*(.+)$');
  static final _list = RegExp(
      r'^(你)?(都|还)?(记得|记住了)(我|关于我的?)?(哪些|什么|多少)|'
      r'^(请|帮我)?(查看|看看|列出|显示|说说)(一下)?(你的|我的)?(长期)?记忆');

  static MemoryCommand? parseCommand(String input) {
    final q = input.trim().replaceFirst(_prefix, '').trim();
    if (q.isEmpty) return null;
    if (_forgetAll.hasMatch(q)) return const MemoryCommand('forgetAll', '');
    if (_list.hasMatch(q)) return const MemoryCommand('list', '');
    final f = _forgetOne.firstMatch(q);
    if (f != null) {
      var k = f.group(4)!.trim();
      k = k.replaceFirst(RegExp(r'^(关于|有关)'), '').replaceFirst(RegExp(r'^我的'), '');
      k = k.replaceFirst(RegExp(r'[。.!！]+$'), '').trim();
      if (k.isNotEmpty) return MemoryCommand('forget', k);
    }
    final r = _remember.firstMatch(q);
    if (r != null) {
      final t = r.group(4)!.replaceFirst(RegExp(r'[。.!！]+$'), '').trim();
      if (t.isNotEmpty) return MemoryCommand('remember', t);
    }
    return null;
  }
}
