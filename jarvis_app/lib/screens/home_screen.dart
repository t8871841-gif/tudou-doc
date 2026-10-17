import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' show ImageFilter;

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../services/agent_service.dart';
import '../services/asr_service.dart';
import '../services/edge_tts_service.dart';
import '../services/memory_service.dart';
import '../services/settings_store.dart';
import '../widgets/hud_painters.dart';
import 'settings_screen.dart';

enum JarvisState { booting, idle, listening, thinking, speaking }

const _ice = Color(0xFFF1F4FF); // 近白文字
const _bootCyan = Color(0xFF8AB4F8); // 蓝
const _bootBg = Color(0xFF0B0C12);
const _bootViolet = Color(0xFFA98BE0); // 紫
const _bootPink = Color(0xFFE8788A); // 玫瑰

// 配色（参考 Gemini：蓝 → 紫 → 玫瑰），每个状态一个主色，辅助色在 hud_painters.dart 的 hudPaletteFor。
Color getActiveThemeColor(JarvisState state, double _hue) {
  switch (state) {
    case JarvisState.thinking:
      return hudPurple; // 思考：薰衣草紫
    case JarvisState.speaking:
      return hudAqua; // 回答：浅青
    case JarvisState.listening:
      return hudRose; // 聆听：玫瑰
    case JarvisState.booting:
    case JarvisState.idle:
      return hudBlue2; // 待命：天蓝
  }
}

class _Msg {
  final String role; // user | assistant | system
  final String text;
  const _Msg(this.role, this.text);
}

/// Home screen. Layout follows the single-file web Jarvis design
/// (index_0_jeoy.html): boot screen -> top bar -> reactor stage ->
/// dialogue -> mic -> input. All behaviour (Edge TTS, speech
/// recognition, wake word, device actions, LLM chat) is unchanged;
/// only the presentation layer was rewritten.
///
/// v21: rebuild / repaint cost reduced (see "Notifiers" below) and a
/// few voice-flow races fixed.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen>
    with TickerProviderStateMixin {
  final _settings = JarvisSettings();
  final _input = TextEditingController();
  final _scroll = ScrollController();
  final _asr = AsrService();
  final _player = AudioPlayer();
  final _grid = GridBackgroundPainter();

  JarvisState _state = JarvisState.booting;
  bool _wakeEnabled = false; // the chip: user wants wake-word standby
  bool _wakeMode = false; // standby is actually listening right now
  bool _muted = false;
  int _speakSeq = 0; // bumped whenever speech is (re)started or cancelled
  Completer<void>? _speakAbort;
  int _emptyEndpoints = 0;
  Timer? _listenTimer;
  String _recognized = '';
  String _statusLine = '正在初始化核心…';
  double _micLevel = 0; // 0..1 from speech_to_text sound-level callback
  final _rnd = math.Random();
  final List<_Msg> _messages = [];
  final _memory = MemoryService.instance;
  int _turnsSinceMemory = 0; // 距离上次整理长期记忆已过几轮
  bool _consolidating = false;
  late final ReactorPainter _reactor;
  late final FxPainter _fx;
  late final AuroraPainter _aurora;

  // ---- Notifiers -------------------------------------------------------
  // Only the painters, the mic button and the boot screen need a per-frame
  // tick. Everything else listens to a low-rate notifier, so a clock tick or
  // a HUD drift no longer rebuilds the whole screen via setState.
  final _tick = ValueNotifier<double>(0); // seconds, every frame
  final _glitch = ValueNotifier<int>(0); // 0 none, 1/2 RGB split, 3 skew
  final _clock = ValueNotifier<String>('--:--:--');
  final _hudLevels = ValueNotifier<List<double>>(
      const <double>[0.7, 0.6, 0.8, 0.75, 0.65, 0.7]);
  final _micDown = ValueNotifier<bool>(false);

  late final Listenable _topAnim = Listenable.merge([_glitch, _clock]);
  late final Listenable _hudAnim = _hudLevels;
  late final Listenable _micAnim = Listenable.merge([_tick, _micDown]);

  late final Ticker _ticker;
  Duration? _lastElapsed;
  Timer? _clockTimer;
  Timer? _hudTimer;

  HudMode get _hudMode => switch (_state) {
        JarvisState.listening => HudMode.listening,
        JarvisState.thinking => HudMode.thinking,
        JarvisState.speaking => HudMode.speaking,
        JarvisState.booting || JarvisState.idle => HudMode.idle,
      };

  @override
  void initState() {
    super.initState();
    _reactor = ReactorPainter(
      tick: _tick,
      mode: () => _hudMode,
      wakeMode: () => _wakeMode,
      micLevel: () => _micLevel,
      themeColor: () => getActiveThemeColor(_state, 0),
    );
    _fx = FxPainter(
      tick: _tick,
      mode: () => _hudMode,
      wakeMode: () => _wakeMode,
      micLevel: () => _micLevel,
      themeColor: () => getActiveThemeColor(_state, 0),
    );

    _aurora = AuroraPainter(
      tick: _tick,
      mode: () => _hudMode,
      wakeMode: () => _wakeMode,
      micLevel: () => _micLevel,
      themeColor: () => getActiveThemeColor(_state, 0),
    );

    // Display-rate tick: drives the painters and per-frame widgets
    // without rebuilding the chat list.
    _ticker = createTicker(_onFrame)..start();

    _updateClock();
    _clockTimer = Timer.periodic(
        const Duration(milliseconds: 500), (_) => _updateClock());
    // HUD readout bars drift like the HTML setInterval (700ms).
    _hudTimer = Timer.periodic(
        const Duration(milliseconds: 700), (_) => _driftHud());
    _init();
  }

  Future<void> _init() async {
    await _settings.load();
    await _memory.load();
    _settings.memoryContext = _memory.contextBlock();
    _set(() {
      // 恢复上次的对话（插到最前面，避免和开机问候冲突）。
      _messages.insertAll(0, [
        for (final m in _memory.chat) _Msg(m['role']!, m['text']!),
      ]);
    });
    unawaited(_warmUpAsr());
  }

  @override
  void dispose() {
    _ticker.dispose();
    _clockTimer?.cancel();
    _hudTimer?.cancel();
    _tick.dispose();
    _glitch.dispose();
    _clock.dispose();
    _hudLevels.dispose();
    _micDown.dispose();
    _input.dispose();
    _scroll.dispose();
    _listenTimer?.cancel();
    unawaited(_asr.dispose());
    _player.dispose();
    super.dispose();
  }

  /// setState that is safe to call after an `await`.
  void _set(VoidCallback fn) {
    if (mounted) setState(fn);
  }

  // ---------- Frame / timers ----------

  void _onFrame(Duration elapsed) {
    final last = _lastElapsed;
    // Cap animation at ~60 fps: a 120 Hz screen would otherwise do double
    // the work for no visible gain.
    if (last != null && (elapsed - last) < const Duration(milliseconds: 15)) {
      return;
    }
    _lastElapsed = elapsed;
    final dt = last == null ? 0.016 : (elapsed - last).inMicroseconds / 1e6;
    final t = _tick.value + dt.clamp(0.0, 0.1);
    _tick.value = t;

    // The title glitch is a handful of discrete stages per 7s loop.
    final g = _glitchStage(t);
    if (g != _glitch.value) _glitch.value = g;
  }

  /// Title glitch (HTML `@keyframes gl` on a 7s loop): brief RGB-split
  /// shift at 94-98% of the cycle, then a skew until the loop ends.
  static int _glitchStage(double t) {
    final p = t % 7;
    if (p >= 6.86) return 3;
    if (p >= 6.72) return 2;
    if (p >= 6.58) return 1;
    return 0;
  }

  void _updateClock() {
    final n = DateTime.now();
    String two(int v) => v.toString().padLeft(2, '0');
    _clock.value = '${two(n.hour)}:${two(n.minute)}:${two(n.second)}';
  }

  void _driftHud() {
    _hudLevels.value = <double>[
      for (final v in _hudLevels.value)
        (v * 0.6 + _rnd.nextDouble() * 0.4 + _micLevel * 0.1).clamp(0.0, 1.0),
    ];
  }

  // ---------- Voice ----------

  /// Loads the offline model in the background so the first tap is instant.
  Future<void> _warmUpAsr() async {
    try {
      await _asr.init();
    } catch (e) {
      _notice('离线语音识别模型加载失败：$e');
    }
  }

  Future<bool> _ensureAsr() async {
    try {
      await _asr.init();
      return true;
    } catch (e) {
      _notice('离线语音识别不可用：$e');
      return false;
    }
  }

  /// Interrupts any TTS playback / in-flight synthesis.
  void _stopSpeaking() {
    _speakSeq++;
    final abort = _speakAbort;
    if (abort != null && !abort.isCompleted) abort.complete();
    unawaited(_player.stop().catchError((_) {}));
  }

  Future<void> _speak(String text) async {
    final clean = text.trim();
    if (clean.isEmpty || !_settings.speakReplies || _muted) return;

    final seq = ++_speakSeq;
    final prev = _speakAbort;
    if (prev != null && !prev.isCompleted) prev.complete();
    final abort = _speakAbort = Completer<void>();
    // Only own the status when nothing else (thinking / listening) does.
    final ownsState =
        _state == JarvisState.idle || _state == JarvisState.speaking;
    if (ownsState) _set(() => _state = JarvisState.speaking);
    try {
      await _player.stop();
      final short =
          clean.length > 1200 ? '${clean.substring(0, 1200)}…' : clean;
      // Edge only: no system-TTS fallback. If Edge fails (e.g. no
      // network), the reply stays silent but visible in chat.
      final mp3 = await EdgeTtsService.synthesize(
        short,
        voice: _settings.edgeVoice,
        rate: _settings.rate,
        pitch: _settings.pitch,
      );
      if (seq != _speakSeq || !mounted) return; // superseded / cancelled
      // Subscribe before play() so a short clip can't finish unnoticed.
      final done = _player.onPlayerComplete.first..ignore();
      await _player.play(BytesSource(mp3));
      // Safety net only (the old text.length/8 limit cut long replies off).
      // _stopSpeaking() ends the wait immediately via [abort].
      await Future.any<void>([
        done.timeout(Duration(seconds: 20 + short.length ~/ 2)),
        abort.future,
      ]);
    } catch (_) {
      // Swallow: Edge failure must never crash the chat flow.
      if (seq == _speakSeq) {
        try {
          await _player.stop();
        } catch (_) {}
      }
    } finally {
      if (ownsState &&
          seq == _speakSeq &&
          mounted &&
          _state == JarvisState.speaking) {
        setState(() => _state = JarvisState.idle);
      }
    }
  }

  /// Mic level (already 0..1 from AsrService) for the reactor bars.
  void _onSoundLevel(double level) => _micLevel = level;

  /// “贾维斯” / “Jarvis”, tolerant of spaces and common mis-hearings.
  static final _wakeRe = RegExp(
      r'(贾|嘉|加)\s*维\s*(斯|思)|j\s*a\s*r\s*v\s*i\s*s',
      caseSensitive: false);

  /// Back to wake-word standby once nothing else is going on.
  void _resumeWake() {
    if (!mounted ||
        !_wakeEnabled ||
        _muted ||
        _state != JarvisState.idle ||
        _asr.isRunning) {
      return;
    }
    unawaited(_startWakeListening());
  }

  /// Standby: the offline recogniser keeps running (no restarts, no beeps,
  /// no network) and reacts when an utterance contains the wake word.
  Future<void> _startWakeListening() async {
    if (_muted || _state != JarvisState.idle) return;
    if (!await _ensureAsr()) {
      _set(() {
        _wakeEnabled = false;
        _wakeMode = false;
      });
      return;
    }
    // State may have changed while the model was loading.
    if (_muted || _state != JarvisState.idle || !_wakeEnabled) return;
    final ok = await _asr.start(
      onLevel: _onSoundLevel,
      onEndpoint: _onWakeUtterance,
    );
    if (!ok) {
      _set(() {
        _wakeEnabled = false;
        _wakeMode = false;
      });
      _notice('无法使用麦克风，请检查录音权限。');
      return;
    }
    if (_state == JarvisState.idle && _wakeEnabled) {
      _set(() => _wakeMode = true);
    }
  }

  void _onWakeUtterance(String text) {
    if (!mounted || !_wakeMode) return;
    final m = _wakeRe.firstMatch(text);
    if (m == null) return;
    // "贾维斯，打开微信" in one breath: what follows is the question.
    final rest =
        text.substring(m.end).replaceFirst(RegExp(r'^[\s，,。.、！!？?]+'), '');
    _wakeMode = false;
    unawaited(_asr.stop());
    if (rest.length >= 2) {
      _set(() {});
      unawaited(_send(rest));
    } else {
      unawaited(_onWakeWord());
    }
  }

  Future<void> _onWakeWord() async {
    _set(() {}); // refresh status (_wakeMode is already false)
    _notice('已唤醒，Jarvis 在听。');
    await _startActiveListening();
  }

  /// Wake-word capsule toggle (HTML chip): on -> standby, off -> stop.
  Future<void> _toggleWake() async {
    if (_wakeEnabled) {
      final wasListening = _wakeMode;
      _set(() {
        _wakeEnabled = false;
        _wakeMode = false;
      });
      if (wasListening) await _asr.stop();
      return;
    }
    _set(() => _wakeEnabled = true);
    if (_muted) {
      _notice('当前已静音，取消静音后语音唤醒才会生效。');
      return;
    }
    _resumeWake(); // no-op while busy; resumes automatically afterwards
  }

  Future<void> _startActiveListening() async {
    if (_muted) return;
    if (!await _ensureAsr()) return;
    _stopSpeaking(); // don't let our own TTS leak into the recogniser
    _emptyEndpoints = 0;
    _set(() {
      _wakeMode = false;
      _recognized = '';
      _state = JarvisState.listening;
    });
    final ok = await _asr.start(
      onLevel: _onSoundLevel,
      onPartial: (t) {
        // Late results (mic tapped again, muted, already sent) are ignored.
        if (!mounted || _state != JarvisState.listening) return;
        setState(() {
          _recognized = t;
          _input.text = t;
        });
      },
      onEndpoint: _onActiveEndpoint,
    );
    if (!ok) {
      _set(() => _state = JarvisState.idle);
      _notice('无法使用麦克风，请检查录音权限。');
      return;
    }
    _listenTimer?.cancel();
    _listenTimer = Timer(const Duration(seconds: 60), () {
      if (_state == JarvisState.listening) _endListening();
    });
  }

  void _onActiveEndpoint(String text) {
    if (!mounted || _state != JarvisState.listening) return;
    final q = text.trim();
    if (q.isEmpty) {
      // ~2.4 s of silence per empty endpoint: give up after three.
      if (++_emptyEndpoints >= 3) _endListening();
      return;
    }
    _listenTimer?.cancel();
    unawaited(_asr.stop());
    unawaited(_send(q));
  }

  /// Listening ended without a question (silence / timeout).
  void _endListening() {
    _listenTimer?.cancel();
    unawaited(_asr.stop());
    _set(() => _state = JarvisState.idle);
    _resumeWake();
  }

  Future<void> _toggleMic() async {
    if (_muted) {
      _set(() => _muted = false);
      _notice('已取消静音。');
      _resumeWake();
      return;
    }
    if (_state == JarvisState.listening) {
      final text = _input.text.trim();
      _listenTimer?.cancel();
      // Leave "listening" first so late recogniser results are ignored.
      _set(() => _state = JarvisState.idle);
      await _asr.stop();
      if (text.isNotEmpty) {
        unawaited(_send(text));
      } else {
        _resumeWake();
      }
      return;
    }
    await _startActiveListening();
  }

  void _toggleMute() {
    final mute = !_muted;
    _set(() {
      _muted = mute;
      if (mute) {
        _wakeMode = false;
        // Don't clobber "thinking": a request may still be in flight.
        if (_state == JarvisState.listening ||
            _state == JarvisState.speaking) {
          _state = JarvisState.idle;
        }
      }
    });
    if (mute) {
      _listenTimer?.cancel();
      unawaited(_asr.stop());
      _stopSpeaking();
      _notice('已静音。静音时语音唤醒不生效，取消静音后自动恢复。');
    } else {
      _resumeWake();
    }
  }

  // ---------- Chat ----------

  /// Last 12 non-system messages (call before adding the new question).
  List<Map<String, String>> _buildHistory() {
    final prior = _messages.where((m) => m.role != 'system').toList();
    final start = math.max(0, prior.length - 12);
    return [
      for (final m in prior.skip(start)) {'role': m.role, 'content': m.text},
    ];
  }

  void _reply(String text, String status) {
    if (!mounted) return;
    setState(() {
      _messages.add(_Msg('assistant', text));
      _state = JarvisState.idle;
      _statusLine = status;
    });
    _scrollDown();
    _persistChat();
  }

  /// 把对话记录保存到本机（后台，失败不影响使用）。
  void _persistChat() {
    unawaited(_memory.saveChat([
      for (final m in _messages)
        if (m.role != 'system') {'role': m.role, 'text': m.text},
    ]));
  }

  /// 每几轮对话后，后台让大模型整理一次长期记忆。
  void _maybeConsolidateMemory() {
    if (!_settings.autoMemory || !_settings.configured || _consolidating) {
      return;
    }
    if (++_turnsSinceMemory < 4) return;
    _turnsSinceMemory = 0;
    _consolidating = true;
    final recent = _buildHistory();
    unawaited(() async {
      try {
        await _memory.consolidate(_settings, recent);
        _settings.memoryContext = _memory.contextBlock();
      } finally {
        _consolidating = false;
      }
    }());
  }

  /// 本地处理“记住… / 忘记… / 你记得什么”等口令，不走大模型。
  Future<String> _handleMemoryCommand(MemoryCommand c) async {
    String reply;
    switch (c.kind) {
      case 'remember':
        await _memory.addFact(c.arg);
        reply = '好的，我记住了：${c.arg}';
      case 'forgetAll':
        await _memory.clearFacts();
        reply = '好的，长期记忆已全部清空。';
      case 'forget':
        final n = await _memory.removeMatching(c.arg);
        reply = n > 0 ? '好的，已忘记 $n 条和“${c.arg}”有关的记忆。' : '我没有找到和“${c.arg}”有关的记忆。';
      default:
        if (_memory.facts.isEmpty) {
          reply = '我目前还没有记住关于您的长期信息。您可以对我说“记住……”。';
        } else {
          final shown = _memory.facts.reversed.take(15).toList();
          final sb = StringBuffer('我记得这些：\n');
          for (var i = 0; i < shown.length; i++) {
            sb.writeln('${i + 1}. ${shown[i]}');
          }
          if (_memory.facts.length > shown.length) {
            sb.writeln('……共 ${_memory.facts.length} 条，更多可在设置里查看。');
          }
          reply = sb.toString().trimRight();
        }
    }
    _settings.memoryContext = _memory.contextBlock();
    return reply;
  }

  Future<void> _send([String? override]) async {
    final q = (override ?? _input.text).trim();
    if (q.isEmpty || _state == JarvisState.thinking) return;
    try {
      await _ask(q);
    } finally {
      _resumeWake(); // back to wake-word standby once the exchange is over
    }
  }

  /// 使用 Agent 智能体调度系统处理用户指令（支持联网、跨应用、任务拆解）
  Future<void> _ask(String q) async {
    FocusScope.of(context).unfocus();
    var memCmd = MemoryService.parseCommand(q);
    // “忘记密码怎么办”这类普通提问：没有对应记忆就不拦截，交给大模型。
    if (memCmd != null &&
        memCmd.kind == 'forget' &&
        !_memory.hasMatch(memCmd.arg)) {
      memCmd = null;
    }
    if (memCmd != null) {
      _stopSpeaking();
      _listenTimer?.cancel();
      unawaited(_asr.stop());
      setState(() {
        _messages.add(_Msg('user', q));
        _input.clear();
        _recognized = '';
        _wakeMode = false;
      });
      final reply = await _handleMemoryCommand(memCmd);
      _reply(reply, '记忆已更新。');
      if (mounted) await _speak(reply);
      return;
    }
    final hist = _buildHistory();
    _stopSpeaking();
    _listenTimer?.cancel();
    unawaited(_asr.stop());
    setState(() {
      _messages.add(_Msg('user', q));
      _input.clear();
      _recognized = '';
      _wakeMode = false;
      _state = JarvisState.thinking;
      _statusLine = '正在分析目标并规划任务…';
    });
    _scrollDown();

    final String answer;
    try {
      // 启用 Agent 智能体服务
      answer = await AgentService.runAgent(
        settings: _settings,
        userGoal: q,
        history: hist,
        onProgress: (status) {
          if (mounted) {
            setState(() {
              _statusLine = status; // 实时显示底层子任务执行步骤
            });
          }
        },
      );
    } catch (e) {
      _reply('执行失败：$e\n\n请检查设置里的接口地址、模型、Key 和网络。',
          '智能体执行失败。');
      return;
    }
    _reply(answer, '任务执行完毕。');
    _maybeConsolidateMemory();
    if (mounted) await _speak(answer);
  }

  void _scrollDown() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.animateTo(_scroll.position.maxScrollExtent,
            duration: const Duration(milliseconds: 250), curve: Curves.easeOut);
      }
    });
  }

  void _notice(String m) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(m)));
  }

  Future<void> _openSettings() async {
    final saved = await Navigator.push<bool>(
        context,
        MaterialPageRoute(
            builder: (_) => SettingsScreen(settings: _settings)));
    await _settings.load();
    _settings.memoryContext = _memory.contextBlock();
    if (mounted) {
      setState(() {
        // 在设置里清空了对话记录：主页同步清屏。
        if (_memory.consumeChatCleared()) _messages.clear();
      });
      // Settings pops with true only after a real save, so this
      // confirmation is shown on Home (a SnackBar shown in Settings
      // would vanish instantly on pop).
      if (saved == true) _notice('设置已保存，声音已切换。');
    }
  }

  // ---------- Boot (HTML: tap to wake) ----------

  Future<void> _enterApp() async {
    if (_state != JarvisState.booting) return;
    // 有历史对话 = 老朋友回来了：恢复记录，只说一句简短问候。
    final returning = _messages.isNotEmpty;
    final welcome = returning
        ? '欢迎回来，我在。'
        : '您好，我是 Jarvis。已为您装配智能体与联网跨应用能力。您可以直接说话、点麦克风，或者打字提问。';
    setState(() {
      _state = JarvisState.idle;
      _statusLine = returning ? '核心已上线，已恢复上次的对话。' : '核心已上线，随时可以开始。';
      if (!returning) _messages.add(_Msg('assistant', welcome));
    });
    if (returning) _scrollDown();
    await _speak(welcome);
  }

  // ---------- Build ----------

// 2. 升级后的 build 方法（深空径向渐变背景）
  @override
  Widget build(BuildContext context) {
    if (_state == JarvisState.booting) return _bootScreen();
    final themeColor = getActiveThemeColor(_state, 0);
    final keyboardOpen = MediaQuery.viewInsetsOf(context).bottom > 0;

    final page = Scaffold(
      backgroundColor: const Color(0xFF0B0C12),
      body: Stack(
        children: [
          const Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: RadialGradient(
                  center: Alignment(0.0, -0.2),
                  radius: 1.2,
                  colors: [
                    Color(0xFF14161F),
                    Color(0xFF0B0C12),
                    Color(0xFF07080C),
                  ],
                  stops: [0.0, 0.5, 1.0],
                ),
              ),
            ),
          ),
          // 极光层：彩色光晕缓慢漂移，颜色随状态平滑过渡。
          Positioned.fill(
              child: RepaintBoundary(child: CustomPaint(painter: _aurora))),
          Positioned.fill(
              child: RepaintBoundary(child: CustomPaint(painter: _grid))),
          Positioned.fill(
            child: SafeArea(
              child: Stack(
                children: [
                  Column(
                    children: [
                      _topBar(themeColor),
                      // Keyboard up: the 330px stage would push the input
                      // row off-screen (Column overflow), so show a slim
                      // status row instead.
                      if (keyboardOpen)
                        _compactStatus(themeColor)
                      else
                        _stage(themeColor),
                      _chatArea(themeColor),
                      _bottomArea(themeColor),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
    // Fixed-size HUD layout: large system fonts are allowed, but the scale
    // is capped at 1.3x so nothing overflows.
    return MediaQuery(
      data: MediaQuery.of(context).copyWith(
        textScaler: MediaQuery.textScalerOf(context)
            .clamp(minScaleFactor: 1.0, maxScaleFactor: 1.3),
      ),
      child: page,
    );
  }

  Widget _bootScreen() {
    // 开机页：旋转的三色渐变光环 + 渐变标题 + 极光背景。
    return Scaffold(
      backgroundColor: _bootBg,
      body: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: _enterApp,
        child: Stack(
          children: [
            Positioned.fill(
                child: RepaintBoundary(
                    child: CustomPaint(painter: _aurora))),
            Positioned.fill(
              child: Center(
                child: ValueListenableBuilder<double>(
                  valueListenable: _tick,
                  builder: (_, t, _) {
                    // 2.2s breathing pulse: scale 1 -> 1.12, opacity 1 -> .55.
                    final phase = (t % 2.2) / 2.2;
                    final ease = 0.5 - 0.5 * math.cos(phase * math.pi * 2);
                    final scale = 1 + 0.12 * ease;
                    final opacity = 1 - 0.35 * ease;
                    // One-shot 2.4s letter-spacing intro (HTML `ls` keyframe).
                    final intro = (t / 2.4).clamp(0.0, 1.0);
                    final spacing = 34 * (0.5 + 0.9 * (1 - intro));
                    final blink = (t % 1.6) < 0.8 ? 1.0 : 0.25;
                    return Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Opacity(
                          opacity: opacity,
                          child: Transform.scale(
                            scale: scale,
                            child: SizedBox(
                              width: 170,
                              height: 170,
                              child: Stack(
                                alignment: Alignment.center,
                                children: [
                                  Transform.rotate(
                                    angle: t * math.pi * 2 / 5,
                                    child: Container(
                                      width: 170,
                                      height: 170,
                                      decoration: BoxDecoration(
                                        shape: BoxShape.circle,
                                        gradient: const SweepGradient(colors: [
                                          _bootCyan,
                                          _bootViolet,
                                          _bootPink,
                                          _bootCyan,
                                        ]),
                                        boxShadow: [
                                          BoxShadow(
                                              color: _bootCyan
                                                  .withValues(alpha: 0.35),
                                              blurRadius: 40,
                                              spreadRadius: 2),
                                          BoxShadow(
                                              color: _bootPink
                                                  .withValues(alpha: 0.22),
                                              blurRadius: 64),
                                        ],
                                      ),
                                    ),
                                  ),
                                  Container(
                                    width: 164,
                                    height: 164,
                                    decoration: const BoxDecoration(
                                        shape: BoxShape.circle,
                                        color: _bootBg),
                                  ),
                                  Container(
                                    width: 164,
                                    height: 164,
                                    decoration: BoxDecoration(
                                      shape: BoxShape.circle,
                                      gradient: RadialGradient(colors: [
                                        _bootViolet.withValues(alpha: 0.4),
                                        Colors.transparent,
                                      ]),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(height: 18),
                        Opacity(
                          opacity: intro,
                          child: Transform.translate(
                            offset: Offset(spacing / 2, 0),
                            child: ShaderMask(
                              blendMode: BlendMode.srcIn,
                              shaderCallback: (r) => const LinearGradient(
                                      colors: [
                                    _bootCyan,
                                    _bootViolet,
                                    _bootPink
                                  ]).createShader(r),
                              child: Text('JARVIS',
                                  style: TextStyle(
                                    color: Colors.white,
                                    fontSize: 34,
                                    fontWeight: FontWeight.w300,
                                    letterSpacing: spacing,
                                    shadows: const [
                                      Shadow(
                                          color: _bootViolet, blurRadius: 24),
                                    ],
                                  )),
                            ),
                          ),
                        ),
                        const SizedBox(height: 18),
                        Opacity(
                          opacity: blink,
                          child: const Text('轻触屏幕 · 唤醒系统',
                              style: TextStyle(
                                  color: Colors.white54,
                                  fontSize: 13,
                                  letterSpacing: 4.5)),
                        ),
                      ],
                    );
                  },
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// Glowing HUD text: [glow] is the shadow colour, [color] the fill
  /// (defaults to the glow colour).
  TextStyle _glow(Color glow,
      {required double size,
      required double spacing,
      double blur = 8,
      Color? color}) {
    return TextStyle(
      color: color ?? glow,
      fontSize: size,
      letterSpacing: spacing,
      shadows: [Shadow(color: glow.withValues(alpha: 0.4), blurRadius: blur * 0.6)],
    );
  }

  // ---------- Top bar (HTML .top) ----------

  Widget _topBar(Color themeColor) {
    const dim = TextStyle(color: Colors.white38, fontSize: 11, letterSpacing: 2.2);
    return RepaintBoundary(
      child: ListenableBuilder(
        listenable: _topAnim,
        builder: (_, _) {
          return Padding(
            padding: const EdgeInsets.fromLTRB(20, 14, 20, 0),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _glitchTitle(themeColor),
                    const SizedBox(height: 2),
                    const Text('STARK INDUSTRIES', style: dim),
                  ],
                ),
                const Spacer(),
                Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text(_clock.value,
                        style: dim.copyWith(color: Colors.white54)),
                    const SizedBox(height: 2),
                    const Text('PWR --', style: dim),
                  ],
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  /// 标题：蓝 → 紫 → 玫瑰 的柔和渐变（不再有 RGB 故障闪烁）。
  Widget _glitchTitle(Color color) {
    final pal = hudPaletteFor(color);
    return ShaderMask(
      blendMode: BlendMode.srcIn,
      shaderCallback: (r) =>
          LinearGradient(colors: [color, pal.$1, pal.$2]).createShader(r),
      child: const Text('J.A.R.V.I.S',
          style: TextStyle(
              color: Colors.white,
              fontSize: 20,
              fontWeight: FontWeight.w500,
              letterSpacing: 6)),
    );
  }

  // ---------- Reactor stage (HTML .stage) ----------

  Widget _stage(Color themeColor) {
    return SizedBox(
      height: 330,
      child: Stack(
        children: [
          // Per-frame painters live in their own layer, so they never
          // repaint the chat / text below.
          Positioned.fill(
            child: RepaintBoundary(
              child: Stack(
                children: [
                  // FX layer honours the "粒子特效" setting (as the old
                  // particle sphere did); the reactor itself always draws.
                  if (_settings.particles &&
                      !MediaQuery.disableAnimationsOf(context))
                    Positioned.fill(child: CustomPaint(painter: _fx)),
                  Positioned.fill(child: CustomPaint(painter: _reactor)),
                ],
              ),
            ),
          ),
          // HUD text overlay: rebuilt only on hue / readout changes.
          Positioned.fill(
            child: RepaintBoundary(
              child: ListenableBuilder(
                listenable: _hudAnim,
                builder: (_, _) => _stageOverlay(themeColor),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _stageOverlay(Color themeColor) {
    final levels = _hudLevels.value;
    return Stack(
      children: [
        Center(child: _statusText(themeColor)),
        Positioned(left: 0, top: 0, child: _gearButton(themeColor)),
        Positioned(
          left: 0,
          right: 0,
          bottom: 4,
          child: Center(child: _wakeChip(themeColor)),
        ),
        Positioned(
          right: 0,
          top: 0,
          child: _readout(
            themeColor,
            const ['POWER', 'LATENCY', 'SIGNAL'],
            levels.sublist(3),
            alignRight: true,
          ),
        ),
        Positioned(
          left: 0,
          bottom: 6,
          child: _readout(
            themeColor,
            const ['ARC REACTOR', 'NEURAL LINK', 'VOICE CORE'],
            levels.sublist(0, 3),
            alignRight: false,
          ),
        ),
      ],
    );
  }

  (String, String) _statusLabels() => switch (_state) {
        JarvisState.listening => ('LISTENING', '正在聆听'),
        JarvisState.thinking => ('PROCESSING', '思考中'),
        JarvisState.speaking => ('RESPONDING', '回应中'),
        _ => _wakeMode ? ('WAKE WORD', '等待唤醒') : ('STANDBY', '待命'),
      };

  Widget _statusText(Color themeColor) {
    final color = themeColor;
    final pal = hudPaletteFor(color);
    final (en, cn) = _statusLabels();
    // liveRegion: TalkBack announces state changes (聆听 / 思考 / 回应).
    return Semantics(
      liveRegion: true,
      label: 'Jarvis $cn',
      excludeSemantics: true,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(en,
              style: const TextStyle(
                  color: Colors.white54, fontSize: 12, letterSpacing: 6)),
          const SizedBox(height: 8),
          ShaderMask(
            blendMode: BlendMode.srcIn,
            shaderCallback: (r) => LinearGradient(
                colors: [color, pal.$1, pal.$2]).createShader(r),
            child: Text(cn,
                style: const TextStyle(
                    color: Colors.white,
                    fontSize: 34,
                    fontWeight: FontWeight.w300,
                    letterSpacing: 9)),
          ),
        ],
      ),
    );
  }

  /// Slim status row shown instead of the stage while the keyboard is up.
  Widget _compactStatus(Color themeColor) {
    final (en, cn) = _statusLabels();
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
      child: Semantics(
        liveRegion: true,
        label: 'Jarvis $cn',
        excludeSemantics: true,
        child: Row(
          children: [
            Icon(Icons.circle, size: 10, color: themeColor),
            const SizedBox(width: 10),
            Text(cn,
                style: _glow(themeColor,
                    size: 16, spacing: 3, blur: 8, color: _ice)),
            const SizedBox(width: 10),
            Text(en, style: _glow(themeColor, size: 10, spacing: 3)),
          ],
        ),
      ),
    );
  }

  Widget _gearButton(Color themeColor) {
    return Semantics(
      button: true,
      label: '设置',
      onTap: _openSettings,
      excludeSemantics: true,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: _openSettings,
        // 48dp touch target around the 38dp visual.
        child: SizedBox(
          width: 48,
          height: 48,
          child: Center(
            child: Container(
              width: 38,
              height: 38,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: Colors.white.withValues(alpha: 0.05),
                border: Border.all(color: Colors.white.withValues(alpha: 0.16)),
              ),
              child: const Center(
                child: Icon(Icons.settings_outlined,
                    size: 18, color: Colors.white70),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _wakeChip(Color themeColor) {
    final color = themeColor;
    final pal = hudPaletteFor(color);
    final on = _wakeEnabled;
    return Semantics(
      button: true,
      toggled: on,
      label: '语音唤醒词，说“贾维斯”唤醒',
      value: on ? '已开启' : '已关闭',
      onTap: _toggleWake,
      excludeSemantics: true,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: _toggleWake,
        // At least 48dp tall, still hugging the capsule horizontally.
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 48),
          child: Center(
            widthFactor: 1,
            heightFactor: 1,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 250),
              padding:
                  const EdgeInsets.symmetric(horizontal: 16, vertical: 7),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(20),
                border: Border.all(
                    color: on
                        ? color.withValues(alpha: 0.8)
                        : Colors.white.withValues(alpha: 0.18)),
                gradient: on
                    ? LinearGradient(colors: [
                        color.withValues(alpha: 0.30),
                        pal.$1.withValues(alpha: 0.30),
                      ])
                    : null,
                boxShadow: on
                    ? [
                        BoxShadow(
                            color: color.withValues(alpha: 0.28),
                            blurRadius: 14)
                      ]
                    : null,
              ),
              child: Text('唤醒词 · ${on ? '开' : '关'}',
                  style: TextStyle(
                      color: on ? Colors.white : Colors.white60,
                      fontSize: 13,
                      letterSpacing: 2.6)),
            ),
          ),
        ),
      ),
    );
  }

  /// 状态读数条：低调的单色细条，只作点缀。
  Widget _readout(Color themeColor, List<String> labels, List<double> values,
      {required bool alignRight}) {
    return ExcludeSemantics(child: IgnorePointer(
      child: SizedBox(
        width: 92,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment:
              alignRight ? CrossAxisAlignment.end : CrossAxisAlignment.start,
          children: [
            for (var i = 0; i < labels.length; i++) ...[
              Text(labels[i],
                  style: const TextStyle(
                      color: Colors.white38, fontSize: 9, letterSpacing: 1.6)),
              const SizedBox(height: 3),
              Container(
                height: 2,
                width: 92,
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.08),
                  borderRadius: BorderRadius.circular(1),
                ),
                alignment: Alignment.centerLeft,
                child: Container(
                  width: 92 * values[i].clamp(0.0, 1.0),
                  height: 2,
                  decoration: BoxDecoration(
                    gradient: LinearGradient(colors: [
                      themeColor.withValues(alpha: 0.25),
                      themeColor.withValues(alpha: 0.9),
                    ]),
                    borderRadius: BorderRadius.circular(1),
                  ),
                ),
              ),
              const SizedBox(height: 7),
            ],
          ],
        ),
      ),
    ));
  }

  // ---------- Dialogue (HTML .sub colours) ----------

// 3. 安静的毛玻璃对话卡片
  Widget _chatArea(Color themeColor) {
    final thinking = _state == JarvisState.thinking;
    final pal = hudPaletteFor(themeColor);
    return Expanded(
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(24),
          border: Border.all(color: Colors.white.withValues(alpha: 0.10)),
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(23),
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
            child: Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [
                    Colors.white.withValues(alpha: 0.08),
                    Colors.white.withValues(alpha: 0.03),
                  ],
                ),
              ),
              child: ListView.builder(
                controller: _scroll,
                itemCount: _messages.length + (thinking ? 1 : 0),
                itemBuilder: (context, i) {
                  if (i == _messages.length) {
                    return Semantics(
                        liveRegion: true,
                        label: 'Jarvis 正在思考',
                        excludeSemantics: true,
                        child: Padding(
                          padding: const EdgeInsets.all(10),
                          child: Row(mainAxisSize: MainAxisSize.min, children: [
                            SizedBox(
                                width: 16,
                                height: 16,
                                child: CircularProgressIndicator(
                                    strokeWidth: 2, color: pal.$1)),
                            const SizedBox(width: 12),
                            const Text('Jarvis 正在思考…',
                                style: TextStyle(
                                    color: Colors.white60,
                                    fontSize: 13,
                                    letterSpacing: 1.2)),
                          ]),
                        ));
                  }
                  final m = _messages[i];
                  return _MessageTile(
                      msg: m,
                      themeColor: themeColor,
                      onSpeak: () => _speak(m.text));
                },
              ),
            ),
          ),
        ),
      ),
    );
  }

  // ---------- Bottom: mic + input (HTML .mic) ----------

  Widget _bottomArea(Color themeColor) {
    final pal = hudPaletteFor(themeColor);
    final outline = OutlineInputBorder(
        borderRadius: BorderRadius.circular(26),
        borderSide: BorderSide(color: Colors.white.withValues(alpha: 0.14)));
    final focused = OutlineInputBorder(
        borderRadius: BorderRadius.circular(26),
        borderSide:
            BorderSide(color: themeColor.withValues(alpha: 0.8), width: 1.4));
    final sendDisabled = _state == JarvisState.thinking;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
      child: Column(
        children: [
          if (_state == JarvisState.listening)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(children: [
                Icon(Icons.graphic_eq, color: themeColor),
                const SizedBox(width: 8),
                Expanded(
                    child: Text(
                        _recognized.isEmpty ? '正在聆听，请开始说话…' : _recognized,
                        style: const TextStyle(color: _ice))),
                const Text('点麦克风结束',
                    style: TextStyle(color: Colors.white54, fontSize: 11)),
              ]),
            ),
          _micButton(themeColor),
          const SizedBox(height: 10),
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              IconButton(
                tooltip: _muted ? '取消静音' : '静音',
                onPressed: _toggleMute,
                icon: Icon(_muted ? Icons.volume_off : Icons.volume_up,
                    color: Colors.white60),
              ),
              Expanded(
                child: TextField(
                  controller: _input,
                  minLines: 1,
                  maxLines: 4,
                  textInputAction: TextInputAction.send,
                  onSubmitted: (_) => _send(),
                  style: const TextStyle(color: Colors.white),
                  cursorColor: themeColor,
                  decoration: InputDecoration(
                    hintText: '输入问题，或点麦克风说话，也可说“贾维斯”唤醒',
                    hintStyle: const TextStyle(color: Colors.white38),
                    filled: true,
                    fillColor: Colors.white.withValues(alpha: 0.06),
                    border: outline,
                    enabledBorder: outline,
                    focusedBorder: focused,
                    contentPadding: const EdgeInsets.symmetric(
                        horizontal: 18, vertical: 12),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Opacity(
                opacity: sendDisabled ? 0.4 : 1,
                child: Container(
                  width: 46,
                  height: 46,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [themeColor, pal.$1],
                    ),
                    boxShadow: [
                      BoxShadow(
                          color: themeColor.withValues(alpha: 0.30),
                          blurRadius: 12),
                    ],
                  ),
                  child: IconButton(
                    tooltip: '发送',
                    onPressed: sendDisabled ? null : () => _send(),
                    icon: const Icon(Icons.arrow_upward, color: Colors.white),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(_statusLine,
              style: const TextStyle(color: Colors.white38, fontSize: 11)),
        ],
      ),
    );
  }

  Widget _micButton(Color themeColor) {
    // Own layer: the per-frame ripple / rim repaint only this button.
    final pal = hudPaletteFor(themeColor);
    return RepaintBoundary(
      child: ListenableBuilder(
        listenable: _micAnim,
        builder: (_, _) {
          final t = _tick.value;
          final color = themeColor;
          final listening = _state == JarvisState.listening;
          final ripplePhase = t % 1.4 / 1.4;
          final animate = !MediaQuery.disableAnimationsOf(context);
          return Semantics(button: true, label: _muted ? '取消静音' : listening ? '停止聆听并发送' : '开始语音输入', onTap: _toggleMic, excludeSemantics: true, child: GestureDetector(
            onTap: _toggleMic,
            onTapDown: (_) => _micDown.value = true,
            onTapUp: (_) => _micDown.value = false,
            onTapCancel: () => _micDown.value = false,
            child: Transform.scale(
              scale: _micDown.value ? 0.94 : 1.0,
              child: SizedBox(
                width: 78,
                height: 78,
                child: Stack(
                  alignment: Alignment.center,
                  clipBehavior: Clip.none,
                  children: [
                    // 聆听时向外扩散的柔和波纹。
                    if (listening && animate)
                      Opacity(
                        opacity: (1 - ripplePhase) * 0.7,
                        child: Transform.scale(
                          scale: 1 + 0.7 * ripplePhase,
                          child: Container(
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              border: Border.all(color: color, width: 1.6),
                            ),
                          ),
                        ),
                      ),
                    // 缓慢旋转的三色渐变边缘 + 柔光。
                    RepaintBoundary(
                      child: Transform.rotate(
                        angle: t * math.pi * 2 / 10,
                        child: Container(
                          width: 78,
                          height: 78,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            gradient: SweepGradient(
                                colors: [color, pal.$1, pal.$2, color]),
                            boxShadow: [
                              BoxShadow(
                                  color: color.withValues(alpha: 0.30),
                                  blurRadius: 26,
                                  spreadRadius: 1),
                              BoxShadow(
                                  color: pal.$2.withValues(alpha: 0.18),
                                  blurRadius: 36),
                            ],
                          ),
                        ),
                      ),
                    ),
                    // 深色玻璃中心。
                    Container(
                      width: 72,
                      height: 72,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        gradient: RadialGradient(
                          center: const Alignment(-0.3, -0.4),
                          colors: [
                            color.withValues(alpha: 0.30),
                            const Color(0xFF0E1018),
                          ],
                        ),
                      ),
                    ),
                    if (listening)
                      CustomPaint(
                        size: const Size(30, 30),
                        painter:
                            _BarsIconPainter(tick: t, color: Colors.white),
                      )
                    else
                      const Icon(Icons.mic, size: 30, color: Colors.white),
                  ],
                ),
              ),
            ),
          ));
        },
      ),
    );
  }
}

/// One chat line：用户 = 渐变气泡靠右，Jarvis = 安静的玻璃气泡靠左。
class _MessageTile extends StatelessWidget {
  const _MessageTile(
      {required this.msg, required this.themeColor, required this.onSpeak});
  final _Msg msg;
  final Color themeColor;
  final VoidCallback onSpeak;

  @override
  Widget build(BuildContext context) {
    final user = msg.role == 'user';
    final pal = hudPaletteFor(themeColor);
    return Semantics(container: true, label: user ? '你说：' : 'Jarvis 回答：', child: Align(
      alignment: user ? Alignment.centerRight : Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: BoxConstraints(
            maxWidth: MediaQuery.sizeOf(context).width * 0.82),
        child: Container(
          margin: const EdgeInsets.only(bottom: 10),
          padding: EdgeInsets.fromLTRB(14, 10, 14, user ? 10 : 2),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.only(
              topLeft: Radius.circular(user ? 18 : 4),
              topRight: Radius.circular(user ? 4 : 18),
              bottomLeft: const Radius.circular(18),
              bottomRight: const Radius.circular(18),
            ),
            gradient: user
                ? LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [
                      themeColor.withValues(alpha: 0.38),
                      pal.$1.withValues(alpha: 0.30),
                    ],
                  )
                : null,
            color: user ? null : Colors.white.withValues(alpha: 0.06),
            border: user
                ? null
                : Border.all(color: Colors.white.withValues(alpha: 0.08)),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SelectableText(msg.text,
                  style: const TextStyle(
                      color: _ice, height: 1.55, fontSize: 14)),
              if (!user)
                Align(
                  alignment: Alignment.centerRight,
                  child: IconButton(
                    visualDensity: VisualDensity.compact,
                    tooltip: '朗读回答',
                    onPressed: onSpeak,
                    icon: const Icon(Icons.volume_up_outlined,
                        size: 19, color: Colors.white54),
                  ),
                ),
            ],
          ),
        ),
      ),
    ));
  }
}

/// Sound-bar icon shown on the mic button while listening.
class _BarsIconPainter extends CustomPainter {
  final double tick;
  final Color color;
  _BarsIconPainter({required this.tick, required this.color});

  @override
  void paint(Canvas canvas, Size size) {
    const n = 4;
    final bw = size.width / n;
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.fill;
    for (var i = 0; i < n; i++) {
      final h =
          (0.3 + 0.7 * (math.sin(tick * 6 + i * 1.2)).abs()) * size.height;
      final rect = RRect.fromRectAndRadius(
          Rect.fromCenter(
              center: Offset(i * bw + bw / 2, size.height / 2),
              width: bw * 0.5,
              height: h),
          const Radius.circular(2));
      canvas.drawRRect(rect, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _BarsIconPainter old) =>
      old.tick != tick || old.color != color;
}
