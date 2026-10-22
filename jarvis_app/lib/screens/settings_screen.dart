import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';

import '../services/edge_tts_service.dart';
import '../services/skill_store.dart';
import 'audit_screen.dart';
import 'diagnostics_screen.dart';
import '../services/local_llm_service.dart';
import '../services/native_bridge.dart';
import '../services/memory_service.dart';
import '../services/settings_store.dart';

/// Settings: base URL, model, API key, persona prompt, voice, rate, pitch.
/// The API key field starts empty; nothing is hard-coded.
///
/// Theme: dark is kept (HUD style), but contrast is lifted — the page
/// background, cards and inputs are all visibly lighter than before
/// (#00060B / #00121A / #000D14 were almost indistinguishable),
/// so the form no longer looks like one flat black slab.
/// Single save entry: only the bottom "保存设置" button. The AppBar
/// "保存" was removed (it duplicated the bottom button).
class SettingsScreen extends StatefulWidget {
  final JarvisSettings settings;
  const SettingsScreen({super.key, required this.settings});
  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late final TextEditingController _base =
      TextEditingController(text: widget.settings.baseUrl);
  late final TextEditingController _model =
      TextEditingController(text: widget.settings.model);
  late final TextEditingController _key =
      TextEditingController(text: widget.settings.apiKey);
  late final TextEditingController _sys =
      TextEditingController(text: widget.settings.systemPrompt);
  late String _voice = EdgeTtsService.voices
          .any((v) => v['id'] == widget.settings.edgeVoice)
      ? widget.settings.edgeVoice
      : EdgeTtsService.defaultVoice;
  late double _rate = widget.settings.rate;
  late double _pitch = widget.settings.pitch;
  late bool _speak = widget.settings.speakReplies;
  late bool _particles = widget.settings.particles;
  late bool _autoMem = widget.settings.autoMemory;
  late final TextEditingController _searchKey =
      TextEditingController(text: widget.settings.searchApiKey);
  late final TextEditingController _searchUrl =
      TextEditingController(text: widget.settings.searchEndpoint);
  late final TextEditingController _city =
      TextEditingController(text: widget.settings.homeCity);
  late final TextEditingController _whitelist =
      TextEditingController(text: widget.settings.userWhitelist);
  late bool _keepAlive = widget.settings.keepAlive;
  late bool _continuous = widget.settings.continuousChat;
  late bool _brief = widget.settings.morningBrief;
  late String _briefTime = widget.settings.morningBriefTime;
  late bool _orb = widget.settings.floatingOrb;
  late bool _wakeOn = widget.settings.wakeEnabled;
  late bool _annOn = widget.settings.announceOn;
  late bool _annSms = widget.settings.announceSms;
  late bool _annCall = widget.settings.announceCall;
  late bool _annWx = widget.settings.announceWeChat;
  late final TextEditingController _annPkgs = TextEditingController(text: widget.settings.announceExtraPkgs);
  late final TextEditingController _annSenders = TextEditingController(text: widget.settings.announceSenders);
  late String _quietS = widget.settings.quietStart;
  late String _quietE = widget.settings.quietEnd;
  late bool _depart = widget.settings.departRemind;
  late int _departLead = widget.settings.departLeadMin;
  late bool _barge = widget.settings.bargeIn;
  late int _wakeSens = widget.settings.wakeSensitivity;
  late bool _vOn = widget.settings.visionEnabled;
  late final TextEditingController _vModel = TextEditingController(text: widget.settings.visionModel);
  late final TextEditingController _vBase = TextEditingController(text: widget.settings.visionBaseUrl);
  late final TextEditingController _vKey = TextEditingController(text: widget.settings.visionApiKey);
  late String _vCoord = widget.settings.visionCoord;
  late bool _localOn = widget.settings.localEnabled;
  late String _localPath = widget.settings.localModelPath;
  bool _importing = false;

  final _player = AudioPlayer();
  bool _previewing = false;

  // Lifted-contrast dark palette (still dark, no longer flat black).
  static const _accent = Color(0xFF8AB4F8);
  static const _pageBg = Color(0xFF111319);
  static const _card = Color(0xFF1A1D26);
  static const _inputBg = Color(0xFF252936);
  static const _dropdownBg = Color(0xFF252936);

  @override
  void dispose() {
    _base.dispose();
    _model.dispose();
    _key.dispose();
    _sys.dispose();
    _searchKey.dispose();
    _searchUrl.dispose();
    _city.dispose();
    _whitelist.dispose();
    _player.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    widget.settings.baseUrl = _base.text.trim();
    widget.settings.model = _model.text.trim();
    widget.settings.apiKey = _key.text.trim();
    widget.settings.systemPrompt = _sys.text.trim();
    widget.settings.ttsEngine = JarvisSettings.engineEdge;
    widget.settings.edgeVoice = _voice;
    widget.settings.rate = _rate;
    widget.settings.pitch = _pitch;
    widget.settings.speakReplies = _speak;
    widget.settings.particles = _particles;
    widget.settings.autoMemory = _autoMem;
    widget.settings.searchApiKey = _searchKey.text.trim();
    widget.settings.searchEndpoint = _searchUrl.text.trim();
    widget.settings.homeCity = _city.text.trim();
    widget.settings.userWhitelist = _whitelist.text;
    widget.settings.keepAlive = _keepAlive;
    widget.settings.continuousChat = _continuous;
    widget.settings.morningBrief = _brief;
    widget.settings.morningBriefTime = _briefTime;
    widget.settings.floatingOrb = _orb;
    widget.settings.wakeEnabled = _wakeOn;
    widget.settings.announceOn = _annOn;
    widget.settings.announceSms = _annSms;
    widget.settings.announceCall = _annCall;
    widget.settings.announceWeChat = _annWx;
    widget.settings.announceExtraPkgs = _annPkgs.text;
    widget.settings.announceSenders = _annSenders.text;
    widget.settings.quietStart = _quietS;
    widget.settings.quietEnd = _quietE;
    widget.settings.departRemind = _depart;
    widget.settings.departLeadMin = _departLead;
    widget.settings.bargeIn = _barge;
    widget.settings.wakeSensitivity = _wakeSens;
    widget.settings.visionEnabled = _vOn;
    widget.settings.visionModel = _vModel.text.trim();
    widget.settings.visionBaseUrl = _vBase.text.trim();
    widget.settings.visionApiKey = _vKey.text.trim();
    widget.settings.visionCoord = _vCoord;
    widget.settings.localEnabled = _localOn && _localPath.isNotEmpty;
    widget.settings.localModelPath = _localPath;
    try {
      await widget.settings.save();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('保存失败：$e')));
      return;
    }
    if (!mounted) return;
    // Pop with true; Home shows the confirmation SnackBar. Showing one
    // here would vanish instantly on pop.
    Navigator.pop(context, true);
  }

  /// Preview the selected Edge voice / rate / pitch in place.
  /// Failures show the real error instead of a generic "check
  /// network", so a broken engine can be diagnosed.
  Future<void> _previewVoice() async {
    if (_previewing) {
      await _player.stop();
      if (mounted) setState(() => _previewing = false);
      return;
    }
    setState(() => _previewing = true);
    try {
      await _player.stop();
      final label = EdgeTtsService.voices
          .firstWhere((v) => v['id'] == _voice,
              orElse: () => EdgeTtsService.voices.first)['label'];
      final mp3 = await EdgeTtsService.synthesize(
        '您好，我是 Jarvis，这是$label，现在的声音就是这样。',
        voice: _voice,
        rate: _rate,
        pitch: _pitch,
      );
      await _player.play(BytesSource(mp3));
      await _player.onPlayerComplete.first
          .timeout(const Duration(seconds: 15));
    } catch (e) {
      if (mounted) {
        final msg = e.toString().replaceFirst('StateError: ', '');
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('试听失败：$msg')));
      }
    } finally {
      if (mounted) setState(() => _previewing = false);
    }
  }

  InputDecoration _dec(String label, {String? hint, String? helper}) {
    return InputDecoration(
      labelText: label,
      hintText: hint,
      helperText: helper,
      filled: true,
      fillColor: _inputBg,
      labelStyle: const TextStyle(color: Colors.white),
      hintStyle: const TextStyle(color: Colors.white54),
      helperStyle: const TextStyle(color: Colors.white60),
      enabledBorder: const OutlineInputBorder(
          borderSide: BorderSide(color: Colors.white38)),
      focusedBorder: const OutlineInputBorder(
          borderSide: BorderSide(color: _accent, width: 1.5)),
    );
  }

  Widget _section({required String title, required List<Widget> children}) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: _card,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: Colors.white12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(title,
              style: const TextStyle(
                  color: _accent, fontWeight: FontWeight.bold, fontSize: 15)),
          const SizedBox(height: 12),
          ...children,
        ],
      ),
    );
  }

  Future<bool> _confirm(String title, String body) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: _card,
        title: Text(title, style: const TextStyle(color: Colors.white)),
        content: Text(body, style: const TextStyle(color: Colors.white70)),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          TextButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('确定')),
        ],
      ),
    );
    return ok == true;
  }

  Widget _switch(String title, String sub, bool v, ValueChanged<bool> f) =>
      SwitchListTile(
          title: Text(title, style: const TextStyle(color: Colors.white)),
          subtitle: Text(sub,
              style: const TextStyle(color: Colors.white60, fontSize: 12)),
          value: v,
          activeThumbColor: _accent,
          onChanged: f);

  Future<void> _pickBriefTime() async {
    final parts = _briefTime.split(':');
    final t = await showTimePicker(
        context: context,
        initialTime: TimeOfDay(
            hour: int.tryParse(parts.first) ?? 7,
            minute: parts.length > 1 ? (int.tryParse(parts[1]) ?? 30) : 30));
    if (t != null) {
      setState(() => _briefTime =
          '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}');
    }
  }

  Future<void> _importLocalModel() async {
    setState(() => _importing = true);
    final p = await LocalLlmService.importModel();
    if (!mounted) return;
    setState(() {
      _importing = false;
      if (p != null) {
        _localPath = p;
        _localOn = true;
      }
    });
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(p != null ? '模型已导入，保存设置后生效。' : '没有导入模型（已取消或复制失败）。')));
  }

  Future<void> _pickQuiet(bool start) async {
    final cur = (start ? _quietS : _quietE).split(':');
    final t = await showTimePicker(
        context: context,
        initialTime: TimeOfDay(
            hour: int.tryParse(cur.first) ?? 22, minute: cur.length > 1 ? (int.tryParse(cur[1]) ?? 0) : 0));
    if (t == null) return;
    final v = '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
    setState(() => start ? _quietS = v : _quietE = v);
  }

  /// 主动性（v3 第 7 条）：来电 / 短信 / 通知播报 + 日程出发提醒。
  Widget _proactiveSection() {
    return _section(title: '主动播报与出发提醒', children: [
      _switch('来电 / 短信 / 通知播报', '需要“通知读取”授权；要在后台播报需同时开启“后台常驻待命”。验证码类只提示、不读内容。勿扰时段内不播报',
          _annOn, (v) => setState(() => _annOn = v)),
      if (_annOn) ...[
        _switch('短信', '', _annSms, (v) => setState(() => _annSms = v)),
        _switch('来电', '靠拨号器的来电通知识别；不同 ROM 的拨号器包名不同，没播的话在下面补包名', _annCall, (v) => setState(() => _annCall = v)),
        _switch('微信', '', _annWx, (v) => setState(() => _annWx = v)),
        TextField(
            controller: _annPkgs,
            maxLines: 2,
            style: const TextStyle(color: Colors.white),
            decoration: _dec('额外播报的 App 包名，每行一个')),
        const SizedBox(height: 10),
        TextField(
            controller: _annSenders,
            style: const TextStyle(color: Colors.white),
            decoration: _dec('只播这些联系人 / 发件人（逗号分隔，留空 = 全部）')),
        const SizedBox(height: 10),
        Wrap(spacing: 8, children: [
          OutlinedButton(onPressed: () => _pickQuiet(true), child: Text('勿扰开始 $_quietS')),
          OutlinedButton(onPressed: () => _pickQuiet(false), child: Text('勿扰结束 $_quietE')),
        ]),
      ],
      _switch('日程出发提醒', '读未来 24 小时有地点的日程，提前提醒“该出发了”：有定位且能查到地点时按直线距离粗估路程，否则按固定提前量并在提醒里标明。每天需要打开过 Jarvis（点晨报通知即可）才会排程',
          _depart, (v) => setState(() => _depart = v)),
      if (_depart)
        Row(children: [
          const Text('固定提前量', style: TextStyle(color: Colors.white70)),
          Expanded(
            child: Slider(
              value: _departLead.toDouble(),
              min: 10,
              max: 120,
              divisions: 22,
              label: '$_departLead 分钟',
              onChanged: (v) => setState(() => _departLead = v.round()),
            ),
          ),
          Text('$_departLead 分钟', style: const TextStyle(color: Colors.white70)),
        ]),
    ]);
  }

  /// 语音唤醒（v3 第 5 条）。
  Widget _wakeSection() {
    return _section(title: '语音：唤醒与打断', children: [
      _switch('语音唤醒待命', '开着时持续离线监听“贾维斯”，喊一声就开始聆听。要在熄屏 / 其他 App 前台时也能唤醒，需同时开启上面的“后台常驻待命”',
          _wakeOn, (v) => setState(() => _wakeOn = v)),
      const SizedBox(height: 4),
      const Text('唤醒灵敏度', style: TextStyle(color: Colors.white70, fontSize: 13)),
      const SizedBox(height: 6),
      SegmentedButton<int>(
        segments: const [
          ButtonSegment(value: 0, label: Text('严格')),
          ButtonSegment(value: 1, label: Text('标准')),
          ButtonSegment(value: 2, label: Text('宽松')),
        ],
        selected: {_wakeSens},
        onSelectionChanged: (v) => setState(() => _wakeSens = v.first),
      ),
      const SizedBox(height: 6),
      const Text('严格：“贾维斯”须在句首且整句较短；标准：在句首附近；宽松：句中任意位置，并容忍更多误听。'
          '唤醒后约 5 秒没有有效指令会自动回到待命；10 分钟内误唤醒 3 次会临时调到严格。'
          '点悬浮球、电源键助手入口与唤醒词互不影响。',
          style: TextStyle(color: Colors.white60, fontSize: 12)),
      _switch('打断说话（降级版）', '播报时开口说话（或说“停”）即停播并聆听。不是完整回声消除：用平台回声消除 + 文本比对过滤自己的声音，喇叭很响或机型不支持时可能误触发 / 漏触发',
          _barge, (v) => setState(() => _barge = v)),
      const SizedBox(height: 6),
      const Text('实现方式：离线语音识别持续解码并匹配“贾维斯”，不是专用唤醒词模型，待命耗电会高于专用方案。真实耗电请在 功能自检 > 唤醒监听 里看实测折算值。',
          style: TextStyle(color: Colors.white38, fontSize: 12)),
    ]);
  }

  /// 视觉看屏（v3 第 4 项）：截图 + 多模态模型。Key 默认复用上面的模型配置。
  Widget _visionSection() {
    return _section(title: '视觉看屏（截图 + 多模态模型）', children: [
      _switch('启用视觉看屏', '界面读不到文字、或树里点不到目标时，截图发给下面配置的模型兜底；也支持“看一眼屏幕，这上面写了什么”。银行 / 支付 / 钱包 / 系统设置类界面永不截图',
          _vOn, (v) => setState(() => _vOn = v)),
      TextField(
          controller: _vModel,
          style: const TextStyle(color: Colors.white),
          decoration: _dec('视觉模型（留空 = 同上面的模型）', helper: '所配模型必须支持图片输入')),
      const SizedBox(height: 10),
      TextField(
          controller: _vBase,
          style: const TextStyle(color: Colors.white),
          decoration: _dec('视觉接口地址（留空 = 同上）')),
      const SizedBox(height: 10),
      TextField(
          controller: _vKey,
          obscureText: true,
          style: const TextStyle(color: Colors.white),
          decoration: _dec('视觉 Key（留空 = 同上）')),
      const SizedBox(height: 10),
      DropdownButtonFormField<String>(
        value: _vCoord,
        dropdownColor: const Color(0xFF141B33),
        decoration: _dec('坐标格式'),
        style: const TextStyle(color: Colors.white),
        items: const [
          DropdownMenuItem(value: 'pixel', child: Text('像素坐标（多数模型）')),
          DropdownMenuItem(value: 'norm1000', child: Text('0~1000 归一化（Qwen-VL 系）')),
        ],
        onChanged: (v) => setState(() => _vCoord = v ?? 'pixel'),
      ),
      const SizedBox(height: 6),
      const Text('坐标点击只在“不在黑名单 且 在看屏白名单内”的 App 里允许；短视频 / 游戏类 App 需先把包名加到“看屏白名单”。升级后要把无障碍服务关掉再打开一次，截图和手势权限才会生效。',
          style: TextStyle(color: Colors.white60, fontSize: 12)),
    ]);
  }

  /// 已学会的看屏流程（v3 第 8 条）：查看、删除。
  Future<void> _skillsDialog() async {
    final l = await SkillStore.uiSkills();
    if (!mounted) return;
    await showDialog<void>(
        context: context,
        builder: (c) => StatefulBuilder(
            builder: (c, setD) => AlertDialog(
                  title: const Text('已学会的看屏流程'),
                  content: SizedBox(
                    width: double.maxFinite,
                    child: l.isEmpty
                        ? const Text('还没有。做完一个多步看屏任务后说“记住这个流程”即可保存。')
                        : ListView(shrinkWrap: true, children: [
                            for (final sk in List.of(l))
                              ListTile(
                                dense: true,
                                title: Text(sk.goalPattern.isNotEmpty ? sk.goalPattern : sk.goalKey,
                                    style: const TextStyle(fontSize: 13)),
                                subtitle: Text('${sk.uiSteps.length} 步${sk.isActive ? '' : ' · 已停用（连续失败）'}'),
                                trailing: IconButton(
                                    tooltip: '删除这条流程（保留规划步骤）',
                                    icon: const Icon(Icons.close, size: 18),
                                    onPressed: () async {
                                      await SkillStore.invalidateUiSteps(sk);
                                      l.remove(sk);
                                      setD(() {});
                                    }),
                              ),
                          ]),
                  ),
                  actions: [TextButton(onPressed: () => Navigator.pop(c), child: const Text('关闭'))],
                )));
  }

  /// 功能自检 / 操作日志入口（v3）。
  Widget _toolsSection() {
    return _section(title: '自检与记录', children: [
      Wrap(spacing: 8, runSpacing: 8, children: [
        OutlinedButton(
            onPressed: () => Navigator.push(context,
                MaterialPageRoute(builder: (_) => DiagnosticsScreen(settings: widget.settings))),
            child: const Text('功能自检')),
        OutlinedButton(
            onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const AuditScreen())),
            child: const Text('操作日志')),
        OutlinedButton(onPressed: _skillsDialog, child: const Text('已学会的流程')),
      ]),
      const SizedBox(height: 6),
      const Text('急停：双击悬浮球，或同时按音量 + 和 −，会立即中断当前任务、看屏动作和播报。',
          style: TextStyle(color: Colors.white60, fontSize: 12)),
    ]);
  }

  Widget _extraSections() {
    final modelName = _localPath.isEmpty ? '未导入' : _localPath.split('/').last;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      _section(title: '端侧小模型（简单任务本地答）', children: [
        _switch('启用本地小模型', '寒暄闲聊、晨报润色走本地，零流量零延迟；规划、看屏、联网综合、记忆整理一律走云端；断网时聊天也能兜底',
            _localOn, (v) => setState(() => _localOn = v)),
        Text('当前模型：$modelName',
            style: const TextStyle(color: Colors.white70, fontSize: 13)),
        const SizedBox(height: 8),
        OutlinedButton(
            onPressed: _importing ? null : _importLocalModel,
            child: Text(_importing ? '正在复制模型…（文件较大，请稍等）' : '选择模型文件（.task / .bin）')),
        const SizedBox(height: 6),
        const Text(
            '需要 MediaPipe LLM 格式的模型（如 Qwen2.5 0.5B/1.5B、Gemma 3 1B 的 .task 文件，约 0.5~1.5GB）。'
            '文件名含 qwen 按 ChatML 模板，其余按 Gemma 模板。模型只存在本机私有目录。',
            style: TextStyle(color: Colors.white60, fontSize: 12)),
      ]),
      const SizedBox(height: 16),
      _section(title: '联网搜索与天气', children: [
        TextField(
            controller: _searchKey,
            obscureText: true,
            style: const TextStyle(color: Colors.white),
            decoration: _dec('搜索 API Key（可选）',
                helper: '填博查(Bocha)等兼容接口的 Key，中文搜索更准；留空只能查百科')),
        const SizedBox(height: 12),
        TextField(
            controller: _searchUrl,
            style: const TextStyle(color: Colors.white),
            decoration: _dec('搜索接口地址', hint: 'https://api.bochaai.com/v1/web-search')),
        const SizedBox(height: 12),
        TextField(
            controller: _city,
            style: const TextStyle(color: Colors.white),
            decoration: _dec('默认城市', helper: '定位不可用时用它查天气，如：北京')),
      ]),
      const SizedBox(height: 16),
      _section(title: '常驻与入口', children: [
        _switch('后台常驻待命', '前台服务 + 常驻通知，熄屏也能听“贾维斯”（耗电会增加）', _keepAlive,
            (v) => setState(() => _keepAlive = v)),
        _switch('悬浮球', 'Jarvis 在后台时，在任意界面显示一个小球：点一下开始听，长按打开，可拖动吸边（需先开启无障碍服务；建议同时开启“后台常驻待命”）',
            _orb, (v) async {
          if (v) {
            final r = await NativeBridge.orbSetEnabled(true);
            if (r == 'need_service') {
              await NativeBridge.orbSetEnabled(false);
              if (mounted) {
                ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                    content: Text('请先在系统无障碍里开启“Jarvis 跨应用全自动引擎”，再回来打开悬浮球。')));
              }
              await NativeBridge.openAccessibilitySettings();
              return;
            }
          } else {
            await NativeBridge.orbSetEnabled(false);
          }
          if (mounted) setState(() => _orb = v);
        }),
        _switch('连续对话', '语音提问后自动继续听，不用再喊唤醒词', _continuous,
            (v) => setState(() => _continuous = v)),
        const SizedBox(height: 4),
        Wrap(spacing: 8, children: [
          OutlinedButton(
              onPressed: NativeBridge.openAssistantSettings,
              child: const Text('设为默认助手（电源键唤起）')),
          OutlinedButton(
              onPressed: NativeBridge.requestIgnoreBattery,
              child: const Text('忽略电池优化')),
          OutlinedButton(
              onPressed: NativeBridge.openNotificationAccess,
              child: const Text('授权读取通知')),
        ]),
        const SizedBox(height: 6),
        const Text('“默认助手”需在系统页里把 Jarvis 选为数字助理；快捷设置里也可以添加 Jarvis 磁贴。',
            style: TextStyle(color: Colors.white60, fontSize: 12)),
      ]),
      const SizedBox(height: 16),
      _section(title: '主动提醒', children: [
        _switch('每日晨报', '每天定时在通知栏提醒，点开后播报天气、日程和待办', _brief,
            (v) => setState(() => _brief = v)),
        if (_brief)
          OutlinedButton(
              onPressed: _pickBriefTime, child: Text('晨报时间：$_briefTime')),
      ]),
      const SizedBox(height: 16),
      _section(title: '看屏白名单（高级）', children: [
        TextField(
            controller: _whitelist,
            maxLines: 3,
            style: const TextStyle(color: Colors.white),
            decoration: _dec('额外允许看屏的 App 包名，每行一个',
                helper: '内置名单之外的 App。含 bank/pay/wallet/settings 的包名会被原生层拒绝；点击黑名单（转账、付款、验证码等）始终生效。')),
      ]),
    ]);
  }

  /// 长期记忆：查看 / 删除单条 / 清空；对话记录清空。修改立即生效，不需要点“保存设置”。
  Widget _memorySection() {
    final mem = MemoryService.instance;
    return _section(title: '记忆', children: [
      SwitchListTile(
          title: const Text('自动整理长期记忆',
              style: TextStyle(color: Colors.white)),
          subtitle: const Text('每聊几轮，后台让大模型提炼一次关于您的信息（会多消耗少量额度）',
              style: TextStyle(color: Colors.white60, fontSize: 12)),
          value: _autoMem,
          activeThumbColor: _accent,
          onChanged: (v) => setState(() => _autoMem = v)),
      const SizedBox(height: 4),
      Text('长期记忆（${mem.facts.length} 条）· 也可以直接对 Jarvis 说“记住……”“忘记……”',
          style: const TextStyle(color: Colors.white70, fontSize: 13)),
      const SizedBox(height: 6),
      if (mem.facts.isEmpty)
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 8),
          child: Text('还没有记忆。',
              style: TextStyle(color: Colors.white54, fontSize: 13)),
        )
      else
        for (var i = 0; i < mem.facts.length; i++)
          Row(children: [
            Expanded(
                child: Text(mem.facts[i],
                    style:
                        const TextStyle(color: Colors.white, fontSize: 14))),
            IconButton(
              tooltip: '删除这条',
              visualDensity: VisualDensity.compact,
              icon: const Icon(Icons.close, size: 18, color: Colors.white54),
              onPressed: () async {
                await mem.removeAt(i);
                widget.settings.memoryContext = mem.contextBlock();
                if (mounted) setState(() {});
              },
            ),
          ]),
      const SizedBox(height: 8),
      Row(children: [
        Expanded(
          child: OutlinedButton(
            onPressed: mem.facts.isEmpty
                ? null
                : () async {
                    if (!await _confirm('清空长期记忆？', 'Jarvis 将忘记所有关于您的长期信息。')) {
                      return;
                    }
                    await mem.clearFacts();
                    widget.settings.memoryContext = mem.contextBlock();
                    if (mounted) setState(() {});
                  },
            child: const Text('清空长期记忆'),
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: OutlinedButton(
            onPressed: () async {
              if (!await _confirm('清空对话记录？', '主页的历史对话会被清除（长期记忆不受影响）。')) {
                return;
              }
              await mem.clearChat();
              if (mounted) setState(() {});
            },
            child: const Text('清空对话记录'),
          ),
        ),
      ]),
      const SizedBox(height: 8),
      const Text('记忆只保存在本机应用的私有目录；长期记忆会随每次提问发给上面配置的大模型服务。请不要让 Jarvis 记密码等敏感信息。',
          style: TextStyle(color: Colors.white60, fontSize: 12)),
    ]);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _pageBg,
      appBar: AppBar(
        backgroundColor: _card,
        title: const Text('大脑设置',
            style: TextStyle(color: Colors.white)),
        iconTheme: const IconThemeData(color: Colors.white),
        // No AppBar "保存" here: the bottom "保存设置" is the single save.
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _section(title: '大模型服务', children: [
            TextField(
                controller: _base,
                style: const TextStyle(color: Colors.white),
                decoration: _dec('接口地址',
                    hint: 'https://apihub.agnes-ai.com/v1')),
            const SizedBox(height: 12),
            TextField(
                controller: _model,
                style: const TextStyle(color: Colors.white),
                decoration:
                    _dec('模型名称', hint: 'agnes-3.0-flash')),
            const SizedBox(height: 12),
            TextField(
                controller: _key,
                obscureText: true,
                style: const TextStyle(color: Colors.white),
                decoration: _dec('API Key',
                    helper: '仅保存在本机安全存储，不会写进代码。留空则无法联网问答。')),
            const SizedBox(height: 12),
            TextField(
                controller: _sys,
                maxLines: 4,
                style: const TextStyle(color: Colors.white),
                decoration: _dec('人设提示词',
                    hint: '你是钢铁侠的AI管家贾维斯……')),
          ]),
          const SizedBox(height: 16),
          _section(title: '语音播报', children: [
            const Text('语音引擎：Edge 在线（唯一）。断网时无语音播报。',
                style: TextStyle(color: Colors.white60, fontSize: 12)),
            const SizedBox(height: 12),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  // Plain DropdownButton with controlled `value` (not
                  // DropdownButtonFormField). The FormField variant
                  // manages its value via initialValue/FormFieldState,
                  // which made selections stick unreliably; a plain
                  // DropdownButton always reflects _voice, the source
                  // of truth. (`value` on the FormField variant is
                  // deprecated in Flutter 3.47.)
                  child: InputDecorator(
                    decoration: _dec('播报声音'),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<String>(
                        value: _voice,
                        dropdownColor: _dropdownBg,
                        style: const TextStyle(color: Colors.white),
                        isExpanded: true,
                        isDense: true,
                        items: EdgeTtsService.voices
                            .map((v) => DropdownMenuItem<String>(
                                value: v['id'],
                                child: Text(v['label']!)))
                            .toList(),
                        onChanged: (v) {
                          if (v != null) setState(() => _voice = v);
                        },
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                OutlinedButton(
                  onPressed: _previewVoice,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: _accent,
                    side: const BorderSide(color: _accent),
                    padding: const EdgeInsets.symmetric(
                        horizontal: 14, vertical: 16),
                  ),
                  child: Text(_previewing ? '停止' : '试听'),
                ),
              ],
            ),
            const SizedBox(height: 4),
            const Text('先点“试听”确认声音，再点底部“保存设置”。',
                style: TextStyle(color: Colors.white60, fontSize: 12)),
            const SizedBox(height: 12),
            Row(children: [
              const SizedBox(
                  width: 72,
                  child: Text('语速', style: TextStyle(color: Colors.white))),
              Expanded(
                  child: Slider(
                      value: _rate,
                      min: 0.1,
                      max: 1.0,
                      divisions: 18,
                      label: _rate.toStringAsFixed(2),
                      activeColor: _accent,
                      inactiveColor: Colors.white24,
                      onChanged: (v) => setState(() => _rate = v))),
              Text(_rate.toStringAsFixed(2),
                  style: const TextStyle(color: Colors.white)),
            ]),
            Row(children: [
              const SizedBox(
                  width: 72,
                  child: Text('音调', style: TextStyle(color: Colors.white))),
              Expanded(
                  child: Slider(
                      value: _pitch,
                      min: 0.5,
                      max: 2.0,
                      divisions: 15,
                      label: _pitch.toStringAsFixed(2),
                      activeColor: _accent,
                      inactiveColor: Colors.white24,
                      onChanged: (v) => setState(() => _pitch = v))),
              Text(_pitch.toStringAsFixed(2),
                  style: const TextStyle(color: Colors.white)),
            ]),
            SwitchListTile(
                title: const Text('语音播报回答',
                    style: TextStyle(color: Colors.white)),
                value: _speak,
                activeThumbColor: _accent,
                onChanged: (v) => setState(() => _speak = v)),
            SwitchListTile(
                title: const Text('粒子特效',
                    style: TextStyle(color: Colors.white)),
                value: _particles,
                activeThumbColor: _accent,
                onChanged: (v) => setState(() => _particles = v)),
            const SizedBox(height: 4),
            const Text('默认语速已调慢；速度和声音都可以在这里改。语音识别由系统服务处理。',
                style: TextStyle(color: Colors.white60, fontSize: 12)),
          ]),
          const SizedBox(height: 16),
          _extraSections(),
          const SizedBox(height: 16),
          _wakeSection(),
          const SizedBox(height: 16),
          _proactiveSection(),
          const SizedBox(height: 16),
          _visionSection(),
          const SizedBox(height: 16),
          _toolsSection(),
          const SizedBox(height: 16),
          _memorySection(),
          const SizedBox(height: 20),
          FilledButton(
              onPressed: _save,
              style: FilledButton.styleFrom(
                backgroundColor: _accent,
                foregroundColor: Colors.black,
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
              child: const Text('保存设置',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold))),
        ],
      ),
    );
  }
}
