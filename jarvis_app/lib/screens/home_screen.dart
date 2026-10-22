import 'dart:async';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' show ImageFilter;

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:permission_handler/permission_handler.dart';

import '../services/action_service.dart';
import '../services/agent_service.dart';
import '../services/asr_service.dart';
import '../services/agent_control.dart';
import '../services/app_status.dart';
import '../services/announce_service.dart';
import '../services/audit_log.dart';
import '../services/departure_service.dart';
import '../services/edge_tts_service.dart';
import '../services/skill_store.dart';
import '../services/standby_meter.dart';
import '../services/local_llm_service.dart';
import '../services/native_bridge.dart';
import '../services/tool_service.dart';
import '../services/memory_service.dart';
import '../services/settings_store.dart';
import '../services/skill_store.dart';
import '../services/task_board.dart';
import '../widgets/hud_painters.dart';
import 'settings_screen.dart';

enum JarvisState { booting, idle, listening, thinking, speaking }

const _ice = Color(0xFFF1F4FF); // 近白文字
const _bootCyan = Color(0xFF8AB4F8); // 蓝
const _bootBg = Color(0xFF050811);
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
  // --- 新增：动效控制器与动画对象 ---
  late final AnimationController _pulseController;
  late final AnimationController _floatController;
  late final Animation<double> _pulseAnimation;
  late final Animation<double> _floatAnimation;

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

  // 打字机同步输出：上方输出框（_chatArea）逐字显示最新一条助手回复。
  String _displayedOutputText = '系统就绪，点击下方麦克风开始对话...';
  bool _isSpeakingWithAnimation = false;
  int _typewriterRun = 0; // 新回复开始时递增，作废旧的打字循环
  bool _showInput = false; // 输入框默认隐藏，点键盘图标展开（紧凑版）
  int _emptyEndpoints = 0;
  Timer? _listenTimer;
  String _recognized = '';
  String _statusLine = '正在初始化核心…';
  double _micLevel = 0; // 0..1 from speech_to_text sound-level callback
  final _rnd = math.Random();
  final List<_Msg> _messages = [];
  // ---- v2：流式回答 / 连续对话 / 入口事件 ----
  String _streamBuf = '';
  String _sentBuf = '';
  StreamController<String>? _sentenceCtl;
  int _streamMsgIndex = -1;
  Future<void>? _streamSpeakDone;
  int _askGen = 0; // 每次提问 +1：被打断的旧回答不许再写界面
  bool _voiceTurn = false; // 这一轮是语音提问（决定是否自动继续听）
  Map<String, dynamic>? _pendingLaunch;
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
    // --- 新增：核心按钮呼吸动效控制器（3秒周期，0.95 -> 1.05） ---
    _pulseController = AnimationController(
      duration: const Duration(seconds: 3),
      vsync: this,
    );
    _pulseAnimation = Tween<double>(begin: 0.95, end: 1.05).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );

    // --- 新增：底部面板微浮动动效控制器（4秒周期，0 -> -6px） ---
    _floatController = AnimationController(
      duration: const Duration(seconds: 4),
      vsync: this,
    );
    _floatAnimation = Tween<double>(begin: 0.0, end: -6.0).animate(
      CurvedAnimation(parent: _floatController, curve: Curves.easeInOut),
    );

    // 根据系统动效开关决定是否播放动画。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _updateAnimationsState();
    });

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

  // --- 新增：检测系统动效设置并控制播放状态 ---
  void _updateAnimationsState() {
    if (!mounted) return;
    final disableAnimations = MediaQuery.disableAnimationsOf(context);
    if (disableAnimations) {
      _pulseController.stop();
      _floatController.stop();
    } else {
      if (!_pulseController.isAnimating) _pulseController.repeat(reverse: true);
      if (!_floatController.isAnimating) _floatController.repeat(reverse: true);
    }
  }

  Future<void> _init() async {
    await _settings.load();
    await _memory.load();
    _set(() {
      // 恢复上次的对话（插到最前面，避免和开机问候冲突）。
      _messages.insertAll(0, [
        for (final m in _memory.chat) _Msg(m['role']!, m['text']!),
      ]);
    });
    // 任务黑板：App 重启后提醒未完成任务，可说“刚才那件事继续”续跑。
    final board = await TaskBoard.load();
    if (board != null && board.status != TaskStatus.completed) {
      _set(() {
        _messages.add(_Msg(
            'assistant', '发现未完成任务「${board.goal}」，说“刚才那件事继续”可以续跑。'));
      });
    }
    unawaited(_warmUpAsr());
    NativeBridge.init(
      onLaunch: _onLaunchAction,
      onOrbTap: _onOrbTap,
      onEstop: () => _emergencyStop(fromNative: true),
      onNotification: _onNotification,
    );
    unawaited(_applySettingsSideEffects(startup: true));
    await NativeBridge.ready(); // 原生会把启动时暂存的入口事件（默认助手 / 磁贴 / 提醒）投递过来
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
    // --- 新增：释放动效控制器 ---
    _pulseController.dispose();
    _floatController.dispose();
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
    _typewriterRun++; // 新提问打断旧回复：作废正在进行的打字循环
    final abort = _speakAbort;
    if (abort != null && !abort.isCompleted) abort.complete();
    unawaited(_player.stop().catchError((_) {}));
    unawaited(NativeBridge.ttsStop());
  }

  /// 逐字流式显示最新回复（与语音播报同步的打字机效果）。
  /// 由 _reply 在每次助手回复到达时调用；新回复开始时旧循环自动作废。
  void _startSyncedSpeechAnimation(String fullText) async {
    final run = ++_typewriterRun;
    setState(() {
      _isSpeakingWithAnimation = true;
      _displayedOutputText = '';
    });
    for (int i = 0; i < fullText.length; i++) {
      if (!mounted || run != _typewriterRun) return;
      await Future.delayed(const Duration(milliseconds: 60));
      if (!mounted || run != _typewriterRun) return;
      setState(() {
        _displayedOutputText += fullText[i];
      });
      // 打字机显示在上方输出框：已在底部附近时跟随到底，手动上翻阅读时不抢滚动。
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!_scroll.hasClients) return;
        final pos = _scroll.position;
        if (pos.maxScrollExtent - pos.pixels < 120) {
          _scroll.jumpTo(pos.maxScrollExtent);
        }
      });
    }
    if (mounted && run == _typewriterRun) {
      setState(() {
        _isSpeakingWithAnimation = false;
      });
    }
  }

  Future<void> _speak(String text) async {
    var clean = text.trim();
    if (clean.isEmpty) return;
    if (clean.length > 1200) clean = '${clean.substring(0, 1200)}…';
    await _speakStream(Stream<String>.fromIterable(_splitForTts(clean)));
  }

  /// 按句拆开：第一句先合成先播，后面的边播边预合成（首句延迟低）。
  List<String> _splitForTts(String t) {
    final parts = t
        .split(RegExp(r'(?<=[。！？!?；;\n])'))
        .map((e) => e.trim())
        .where((e) => e.isNotEmpty)
        .toList();
    final out = <String>[];
    for (final p in parts) {
      if (out.isNotEmpty && out.last.length < 8) {
        out[out.length - 1] = '${out.last}$p';
      } else {
        out.add(p);
      }
    }
    return out.isEmpty ? [t] : out;
  }

  /// 逐句播报：收到一句就开始合成（预取），按顺序播放；
  /// Edge 失败的那一句改用系统 TTS 兜底，不会整段静音。
  Future<void> _speakStream(Stream<String> sentences) async {
    if (!_settings.speakReplies || _muted) {
      await sentences.drain<void>();
      if (mounted && _state == JarvisState.speaking) {
        _set(() => _state = JarvisState.idle);
      }
      return;
    }
    final seq = ++_speakSeq;
    final prev = _speakAbort;
    if (prev != null && !prev.isCompleted) prev.complete();
    final abort = _speakAbort = Completer<void>();
    final ownsState =
        _state == JarvisState.idle || _state == JarvisState.speaking;
    if (ownsState) _set(() => _state = JarvisState.speaking);
    _spokenNorm = '';
    _bargeFired = false;
    _bargeSince = null;
    final monitor = ownsState && _settings.bargeIn && !_muted;
    if (monitor) unawaited(_startBargeMonitor(seq));
    final queue = StreamController<(String, Future<Uint8List?>)>();
    final sub = sentences.listen((s) {
      _spokenNorm += _norm(s);
      final audio = EdgeTtsService.synthesize(
        s,
        voice: _settings.edgeVoice,
        rate: _settings.rate,
        pitch: _settings.pitch,
      ).then<Uint8List?>((v) => v).catchError((_) => null);
      queue.add((s, audio));
    }, onDone: () {
      if (!queue.isClosed) queue.close();
    }, onError: (_) {
      if (!queue.isClosed) queue.close();
    });
    try {
      await _player.stop();
      await for (final item in queue.stream) {
        if (seq != _speakSeq || !mounted) break;
        final mp3 = await item.$2;
        if (seq != _speakSeq || !mounted) break;
        if (mp3 != null && mp3.isNotEmpty) {
          final done = _player.onPlayerComplete.first..ignore();
          await _player.play(BytesSource(mp3));
          await Future.any<void>([
            done.timeout(Duration(seconds: 20 + item.$1.length ~/ 2)),
            abort.future,
          ]);
        } else {
          await Future.any<void>([
            NativeBridge.ttsSpeak(item.$1, _settings.rate, _settings.pitch),
            abort.future,
          ]);
        }
      }
    } catch (_) {
      // Edge / 播放失败不许拖垮聊天流程。
    } finally {
      await sub.cancel();
      if (monitor && !_bargeFired) await _stopBargeMonitor();
      if (!queue.isClosed) await queue.close();
      if (seq == _speakSeq) {
        try {
          await _player.stop();
        } catch (_) {}
      }
      if (ownsState &&
          seq == _speakSeq &&
          mounted &&
          _state == JarvisState.speaking) {
        setState(() => _state = JarvisState.idle);
      }
    }
  }

  // ---------- 打断说话（v3 第 6 条，降级版，实现等级 B）----------
  // 做不到完整 AEC，所以：播报时开着识别器（record 的 echoCancel / noiseSuppress 走平台回声消除），
  // 用文本比对把“自己正在播的内容”从识别结果里过滤掉；检测到用户在说别的 → 停播并接着聆听。
  //  · 说“停 / 别说了 / 够了…”：识别到即停（目标 ≈1 秒内）；
  //  · 说别的：要连续识别到“非回声”内容约 0.35 秒以上才算持续说话，避免咳嗽 / 杂音误触发。
  // 局限：平台 AEC 是否生效取决于机型 / ROM；喇叭很响或回声被识别成与播报不相似的文字时，可能误触发或漏触发。
  String _spokenNorm = '';
  bool _bargeFired = false;
  bool _bargeMonitoring = false;
  DateTime? _bargeSince;

  static String _norm(String t) =>
      t.toLowerCase().replaceAll(RegExp(r'[^\u4e00-\u9fa5a-z0-9]'), '');

  /// 识别结果是不是我们自己正在播的内容（子串，或双字片段 ≥75% 出现在已播文本里）。
  bool _looksLikeEcho(String n) {
    if (_spokenNorm.contains(n)) return true;
    if (n.length < 4) return false;
    var hit = 0, tot = 0;
    for (var i = 0; i + 2 <= n.length; i++) {
      tot++;
      if (_spokenNorm.contains(n.substring(i, i + 2))) hit++;
    }
    return tot > 0 && hit / tot >= 0.75;
  }

  Future<void> _startBargeMonitor(int seq) async {
    if (_asr.isRunning) return;
    if (!await _ensureAsr()) return;
    if (seq != _speakSeq || _state != JarvisState.speaking) return;
    final ok = await _asr.start(
      onLevel: _onSoundLevel,
      onPartial: _onBargePartial,
      onEndpoint: _onBargeEndpoint,
    );
    if (!ok) return;
    if (!_bargeFired && (seq != _speakSeq || _state != JarvisState.speaking)) {
      await _asr.stop(); // 播报已经结束了，别留着麦克风
      return;
    }
    _bargeMonitoring = true;
  }

  Future<void> _stopBargeMonitor() async {
    if (!_bargeMonitoring) return;
    _bargeMonitoring = false;
    await _asr.stop();
  }

  void _onBargePartial(String t) {
    if (!mounted || _bargeFired || _state != JarvisState.speaking) return;
    final n = _norm(t);
    if (n.isEmpty) return;
    final stopWord = RegExp(r'^(停|停下|别说了|够了|等一下|暂停|闭嘴)$').hasMatch(n);
    if (!stopWord) {
      if (n.length < 2 || _looksLikeEcho(n)) {
        _bargeSince = null; // 回声 / 杂音：重新计时
        return;
      }
      final now = DateTime.now();
      _bargeSince ??= now;
      if (now.difference(_bargeSince!) < const Duration(milliseconds: 350)) return; // 要持续说话才算
    }
    _bargeFired = true;
    AppStatus.bargeCount++;
    _askGen++; // 作废还在生成的回答
    _stopSpeaking();
    _abortStream();
    _set(() => _state = JarvisState.listening);
    unawaited(AuditLog.log('打断播报', detail: stopWord ? '说了停止词' : '用户开口'));
  }

  void _onBargeEndpoint(String text) {
    if (!mounted || !_bargeFired) return; // 没触发打断：这是播报回声的整句，忽略
    final t = text.trim();
    _bargeFired = false;
    _bargeMonitoring = false;
    unawaited(_asr.stop());
    final stopWord = RegExp(r'^(停|停下|别说了|够了|等一下|暂停|闭嘴)[。！!，, ]*$').hasMatch(t);
    _set(() => _state = JarvisState.idle);
    if (t.isEmpty || stopWord) {
      _resumeWake();
      return;
    }
    _voiceTurn = true;
    unawaited(_send(t.replaceFirst(RegExp(r'^(贾维斯|jarvis)[，,、 ]*', caseSensitive: false), '')));
  }

  // ---------- 流式回答 ----------

  void _onStreamDelta(String d) {
    if (!mounted || d.isEmpty) return;
    if (_sentenceCtl == null) {
      _typewriterRun++; // 作废打字机：流式本身就是逐字出
      _streamBuf = '';
      _sentBuf = '';
      final ctl = _sentenceCtl = StreamController<String>();
      _set(() {
        _messages.add(const _Msg('assistant', ''));
        _streamMsgIndex = _messages.length - 1;
        _state = JarvisState.speaking;
        _isSpeakingWithAnimation = false;
      });
      _streamSpeakDone = _speakStream(ctl.stream);
    }
    _streamBuf += d;
    _sentBuf += d;
    _set(() {
      if (_streamMsgIndex >= 0 && _streamMsgIndex < _messages.length) {
        _messages[_streamMsgIndex] = _Msg('assistant', _streamBuf);
      }
      _displayedOutputText = _streamBuf;
    });
    _drainSentences();
  }

  void _drainSentences() {
    final ctl = _sentenceCtl;
    if (ctl == null || ctl.isClosed) return;
    while (true) {
      var cut = -1;
      final m = RegExp(r'[。！？!?；;\n]').firstMatch(_sentBuf);
      if (m != null) {
        cut = m.end;
      } else if (_sentBuf.length > 60) {
        final c = _sentBuf.lastIndexOf(RegExp(r'[，,、]'));
        if (c > 10) cut = c + 1;
      }
      if (cut <= 0) break;
      final sentence = _sentBuf.substring(0, cut).trim();
      _sentBuf = _sentBuf.substring(cut);
      if (sentence.length >= 2) ctl.add(sentence);
    }
  }

  /// 流结束：补播尾巴（含自检等非流式追加部分），把消息定稿，等播完。
  Future<void> _finishStream(String answer) async {
    final ctl = _sentenceCtl;
    if (ctl == null) return;
    var tail = _sentBuf;
    if (answer.length > _streamBuf.length && answer.startsWith(_streamBuf)) {
      tail += answer.substring(_streamBuf.length);
    }
    if (tail.trim().length >= 2 && !ctl.isClosed) ctl.add(tail.trim());
    _sentBuf = '';
    if (!ctl.isClosed) await ctl.close();
    final idx = _streamMsgIndex;
    final done = _streamSpeakDone;
    _sentenceCtl = null;
    _streamBuf = '';
    _streamMsgIndex = -1;
    _streamSpeakDone = null;
    _set(() {
      if (idx >= 0 && idx < _messages.length) {
        _messages[idx] = _Msg('assistant', answer);
      }
      _displayedOutputText = answer;
      _isSpeakingWithAnimation = false;
      _statusLine = '任务执行完毕。';
    });
    _scrollDown();
    _persistChat();
    unawaited(NativeBridge.orbBubble(answer));
    if (done != null) await done;
  }

  void _abortStream() {
    final ctl = _sentenceCtl;
    _sentenceCtl = null;
    _streamBuf = '';
    _sentBuf = '';
    _streamMsgIndex = -1;
    _streamSpeakDone = null;
    if (ctl != null && !ctl.isClosed) unawaited(ctl.close());
  }

  // ---------- 入口事件（默认助手 / 快捷磁贴 / 提醒 / 晨报） ----------

  String _orbSent = '';

  /// 把当前状态同步给悬浮球（只在变化时发一次）。
  void _syncOrb() {
    final s = switch (_state) {
      JarvisState.listening => 'listening',
      JarvisState.thinking => 'thinking',
      JarvisState.speaking => 'speaking',
      _ => 'idle',
    };
    AppStatus.wakeListening = _wakeMode;
    AppStatus.asrRunning = _asr.isRunning;
    if (s == _orbSent) return;
    _orbSent = s;
    unawaited(NativeBridge.orbState(s));
  }

  /// 悬浮球被点（原生要求回执）：返回 true = 已处理。每个状态都有可见反馈（球变色 / 气泡），
  /// 不许静默；返回 false 时原生会兜底拉起 Jarvis 再聆听。
  bool _onOrbTap() {
    if (!mounted) return false;
    switch (_state) {
      case JarvisState.booting:
        _pendingLaunch = {'action': 'assist', 'text': ''};
        unawaited(_enterApp(silent: true));
        unawaited(NativeBridge.orbBubble('我在听…'));
        return true;
      case JarvisState.idle:
        unawaited(NativeBridge.orbBubble('我在听…'));
        unawaited(_runLaunchAction('assist', ''));
        return true;
      case JarvisState.listening:
        _endListening();
        unawaited(NativeBridge.orbBubble('收到，处理中…'));
        return true;
      case JarvisState.speaking:
        _stopSpeaking();
        _set(() => _state = JarvisState.idle);
        unawaited(NativeBridge.orbBubble('已打断'));
        return true;
      case JarvisState.thinking:
        unawaited(NativeBridge.orbBubble('正在处理，请稍等…'));
        return true;
      default:
        return false;
    }
  }

  /// 一键急停：真停。取消 agent 循环信号、作废进行中的回答、停播报 / 聆听、清挂起确认。
  /// fromNative=true：原生侧已处理球的反馈和原生任务取消，不再回调原生。
  void _emergencyStop({bool fromNative = false}) {
    AgentControl.stopAll();
    _askGen++; // 作废正在进行的回答
    _stopSpeaking();
    _abortStream();
    _listenTimer?.cancel();
    unawaited(_asr.stop());
    DeviceActions.cancelPending();
    unawaited(AuditLog.log('用户急停', detail: fromNative ? '悬浮球双击 / 音量键组合' : '界面触发'));
    if (!fromNative) unawaited(NativeBridge.estop());
    if (!mounted) return;
    _set(() {
      _state = JarvisState.idle;
      _wakeMode = false;
      _statusLine = '已停止。';
    });
    _reply('已停止。', '已急停。');
  }

  /// 通知到达（原生通知监听推送）：规则（总开关 / 来源 / 勿扰 / 联系人 / 验证码）在 AnnounceService 里，
  /// 这里只负责：空闲时才播（不打断正在进行的对话），播之前写审计日志。
  void _onNotification(Map<String, dynamic> ev) {
    if (!mounted || _muted) return;
    unawaited(() async {
      final text = await AnnounceService.decide(_settings, ev);
      if (text == null || !mounted || _state != JarvisState.idle) return;
      unawaited(AuditLog.log('播报通知', detail: text));
      _reply(text, '来电 / 消息播报');
      await _speak(text);
    }());
  }

  void _onLaunchAction(Map<String, dynamic> m) {
    if (!mounted) return;
    if (_state == JarvisState.booting) {
      _pendingLaunch = m; // 先静默进主界面，进完再处理
      unawaited(_enterApp(silent: true));
      return;
    }
    unawaited(_runLaunchAction(
        m['action']?.toString() ?? '', m['text']?.toString() ?? ''));
  }

  Future<void> _runLaunchAction(String action, String text) async {
    switch (action) {
      case 'assist':
        if (_state == JarvisState.idle) {
          if (_asr.isRunning) await _asr.stop();
          await _startActiveListening();
        }
      case 'reminder':
        final msg = text.trim().isEmpty ? '时间到了' : text.trim();
        _reply('提醒：$msg', '提醒时间到。');
        await _speak('提醒您：$msg');
      case 'morning_brief':
        await _runBrief();
    }
  }

  Future<void> _runBrief() async {
    if (_state == JarvisState.thinking) return;
    _set(() {
      _state = JarvisState.thinking;
      _statusLine = '正在准备今天的晨报…';
    });
    try {
      final text = await ToolService.briefing(_settings);
      if (_settings.departRemind) unawaited(DepartureService.schedule(_settings));
      _reply(text, '晨报已准备好。');
      if (mounted) await _speak(text);
    } catch (e) {
      _reply('晨报生成失败：$e', '晨报失败。');
    }
  }

  /// 设置变化 / 启动时同步原生侧：看屏白名单、晨报定时、前台服务常驻。
  Future<void> _applySettingsSideEffects({bool startup = false}) async {
    await NativeBridge.setUserWhitelist(_settings.userWhitelistPackages);
    unawaited(NativeBridge.orbSetEnabled(_settings.floatingOrb));
    unawaited(NativeBridge.announceEnable(_settings.announceOn));
    if (_settings.departRemind) {
      unawaited(DepartureService.schedule(_settings).then((n) {
        if (n > 0) AuditLog.log('已排程出发提醒 $n 条', ok: true);
      }));
    }
    if (_settings.morningBrief) {
      final parts = _settings.morningBriefTime.split(':');
      final h = int.tryParse(parts.first) ?? 7;
      final m = parts.length > 1 ? (int.tryParse(parts[1]) ?? 30) : 30;
      if (!startup) await Permission.notification.request();
      await NativeBridge.scheduleDailyBrief(h, m);
    } else {
      await NativeBridge.cancelDailyBrief();
    }
    // 设置里的“语音唤醒”开关 ↔ 主界面唤醒胶囊保持一致
    if (_settings.wakeEnabled && !_wakeEnabled) {
      _set(() => _wakeEnabled = true);
      if (_state == JarvisState.idle) _resumeWake();
    } else if (!_settings.wakeEnabled && _wakeEnabled && !_settings.keepAlive) {
      final wasListening = _wakeMode;
      _set(() {
        _wakeEnabled = false;
        _wakeMode = false;
      });
      if (wasListening) await _asr.stop();
      unawaited(StandbyMeter.end());
    }
    if (_settings.keepAlive) {
      if (!startup) await Permission.notification.request();
      if (await NativeBridge.startKeepAlive()) {
        if (!_wakeEnabled) _set(() => _wakeEnabled = true);
        if (_state == JarvisState.idle) _resumeWake();
      }
    } else {
      await NativeBridge.stopKeepAlive();
    }
    if (!_settings.localEnabled) unawaited(LocalLlmService.unload());
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
      unawaited(StandbyMeter.begin()); // 开始 / 保持待命耗电样本
    }
  }

  // ---------- 唤醒词：灵敏度 + 误唤醒控制（v3 第 5 条）----------
  // 实现方式：离线识别器持续解码，命中“贾维斯”文本才唤醒（不是专用 KWS 模型，耗电高于专用方案，
  // 实测见 功能自检 > 唤醒监听）。控制策略：
  //  ① 灵敏度：严格（须在句首且整句短）/ 标准（句首附近）/ 宽松（任意位置，容忍更多误听）；
  //  ② 冷却：刚唤醒 3 秒、刚误唤醒 8 秒内忽略唤醒词；
  //  ③ 唤醒后 ~5 秒没有有效指令（空白 / 只有“嗯啊”）自动回待命，并记一次误唤醒；
  //  ④ 10 分钟内误唤醒 ≥3 次，自动临时调到“严格”10 分钟。
  static final _wakeStrict = RegExp(r'(贾维斯|jarvis)', caseSensitive: false);
  static final _wakeLoose = RegExp(
      r'(贾|嘉|加|假|甲|家)\s*(维|为|围|韦)\s*(斯|思|丝|司)|j\s*a\s*r\s*v\s*i\s*s',
      caseSensitive: false);
  static final _filler = RegExp(r'^[嗯啊呃哦额唉哎诶哈呀吧吗么，。、！!？?\s]+$');

  DateTime _wakeCooldownUntil = DateTime.fromMillisecondsSinceEpoch(0);
  DateTime _tightenUntil = DateTime.fromMillisecondsSinceEpoch(0);
  final List<DateTime> _falseWakes = [];
  bool _fromWake = false; // 本轮聆听由唤醒词触发

  int get _effWakeSens =>
      DateTime.now().isBefore(_tightenUntil) ? 0 : _settings.wakeSensitivity;

  RegExp get _activeWakeRe => switch (_effWakeSens) {
        0 => _wakeStrict,
        2 => _wakeLoose,
        _ => _wakeRe,
      };

  void _registerFalseWake() {
    _fromWake = false;
    final now = DateTime.now();
    _wakeCooldownUntil = now.add(const Duration(seconds: 8));
    _falseWakes
      ..add(now)
      ..removeWhere((t) => now.difference(t) > const Duration(minutes: 10));
    AppStatus.falseWakeCount++;
    unawaited(AuditLog.log('误唤醒：唤醒后没有有效指令，已回到待命', ok: false));
    if (_falseWakes.length >= 3 && !now.isBefore(_tightenUntil)) {
      _tightenUntil = now.add(const Duration(minutes: 10));
      AppStatus.tightenUntil = _tightenUntil;
      _falseWakes.clear();
      unawaited(AuditLog.log('误唤醒较多，临时调到严格 10 分钟'));
      _notice('误唤醒较多，已临时把唤醒灵敏度调到“严格”10 分钟。');
    }
  }

  void _onWakeUtterance(String text) {
    if (!mounted || !_wakeMode) return;
    final m = _activeWakeRe.firstMatch(text);
    if (m == null) return;
    final sens = _effWakeSens;
    if (DateTime.now().isBefore(_wakeCooldownUntil)) return;
    if (sens != 2 && m.start > (sens == 0 ? 3 : 6)) return; // 别人聊天里提到不算
    if (sens == 0 && text.length > 30) return;
    _wakeCooldownUntil = DateTime.now().add(const Duration(seconds: 3));
    AppStatus.wakeCount++;
    AppStatus.lastWakeAt = DateTime.now();
    // "贾维斯，打开微信" in one breath: what follows is the question.
    final rest =
        text.substring(m.end).replaceFirst(RegExp(r'^[\s，,。.、！!？?]+'), '');
    _wakeMode = false;
    unawaited(_asr.stop());
    if (rest.length >= 2 && !_filler.hasMatch(rest)) {
      _set(() {});
      _voiceTurn = true;
      unawaited(_send(rest));
    } else {
      unawaited(_onWakeWord());
    }
  }

  Future<void> _onWakeWord() async {
    _set(() {}); // refresh status (_wakeMode is already false)
    _notice('已唤醒，Jarvis 在听。');
    unawaited(NativeBridge.orbBubble('我在听…')); // 后台唤醒时给悬浮球一个可见反馈
    await _startActiveListening(fromWake: true);
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
      if (_settings.keepAlive) unawaited(NativeBridge.stopKeepAlive());
      _settings.wakeEnabled = false;
      unawaited(_settings.save());
      unawaited(StandbyMeter.end());
      return;
    }
    _set(() => _wakeEnabled = true);
    _settings.wakeEnabled = true;
    unawaited(_settings.save());
    if (_settings.keepAlive) unawaited(NativeBridge.startKeepAlive());
    if (_muted) {
      _notice('当前已静音，取消静音后语音唤醒才会生效。');
      return;
    }
    _resumeWake(); // no-op while busy; resumes automatically afterwards
  }

  Future<void> _startActiveListening({bool fromWake = false}) async {
    if (_muted) return;
    _fromWake = fromWake;
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
        // 唤醒后有人真的在说话：别再按“误唤醒”短超时收尾，改回正常 60 秒。
        if (_fromWake && t.trim().length >= 2 && !_filler.hasMatch(t.trim())) {
          _fromWake = false;
          _listenTimer?.cancel();
          _listenTimer = Timer(const Duration(seconds: 60), () {
            if (_state == JarvisState.listening) _endListening();
          });
        }
      },
      onEndpoint: _onActiveEndpoint,
    );
    if (!ok) {
      _set(() => _state = JarvisState.idle);
      _notice('无法使用麦克风，请检查录音权限。');
      return;
    }
    _listenTimer?.cancel();
    // 唤醒词触发的聆听：10 秒内没人说话就当误唤醒回待命；其他入口保持 60 秒。
    _listenTimer = Timer(Duration(seconds: fromWake ? 10 : 60), () {
      if (_state == JarvisState.listening) {
        if (_fromWake) _registerFalseWake();
        _endListening();
      }
    });
  }

  void _onActiveEndpoint(String text) {
    if (!mounted || _state != JarvisState.listening) return;
    final q = text.trim();
    if (q.isEmpty || _filler.hasMatch(q)) {
      // ~2.4 s of silence per empty endpoint: give up after three (after a wake word: two, ~5 s).
      if (++_emptyEndpoints >= (_fromWake ? 2 : 3)) {
        if (_fromWake) _registerFalseWake();
        _endListening();
      }
      return;
    }
    _fromWake = false;
    _listenTimer?.cancel();
    unawaited(_asr.stop());
    _voiceTurn = true;
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
        _voiceTurn = true;
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
      unawaited(StandbyMeter.end()); // 静音时待命暂停，样本作废
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
    _startSyncedSpeechAnimation(text);
    unawaited(NativeBridge.orbBubble(text));
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
      final cont = _voiceTurn && _settings.continuousChat;
      _voiceTurn = false;
      if (cont &&
          mounted &&
          !_muted &&
          _state == JarvisState.idle &&
          !_asr.isRunning) {
        // 免唤醒连续对话：说完自动继续听；静音约 7 秒没人说话会自动回到待命。
        unawaited(_startActiveListening());
      } else {
        _resumeWake(); // back to wake-word standby once the exchange is over
      }
    }
  }

  /// 使用 Agent 智能体调度系统处理用户指令（支持联网、跨应用、任务拆解）
  /// 确认链的答复是否表示这步没跑通（发送失败 / 已取消 / 没找到人…）。
  /// 只用于“确认解决后要不要继续黑板剩余步骤”的判断；误判偏向“不继续”（安全侧）。
  bool _confirmReplyFailed(String reply) {
    return RegExp(r'没能确认|没发成|发送失败|失败|没找到|没有发送|已取消|打不开|权限被拒绝|读取通讯录失败')
        .hasMatch(reply);
  }

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
    // 技能固化：成功的看屏流程，用户说“记住这个流程 / 以后都这样做”才保存。
    if (RegExp(r'^(请)?(记住|保存)(这个|这次|刚才那个)?(流程|技能|操作)[。！!]?$|^以后都(这样|这么)(做|办)[。！!]?$')
        .hasMatch(q.trim())) {
      _stopSpeaking();
      _listenTimer?.cancel();
      unawaited(_asr.stop());
      setState(() {
        _messages.add(_Msg('user', q));
        _input.clear();
        _recognized = '';
        _wakeMode = false;
      });
      final String reply;
      if (UiTrace.offer == null) {
        reply = '现在没有可以记住的看屏流程。做完一个多步看屏任务后，再说“记住这个流程”。';
      } else {
        final r = await UiTrace.saveOffer();
        if (r == null) {
          reply = '没能保存：找不到对应的技能记录。';
        } else {
          unawaited(AuditLog.log('保存看屏技能', ok: true, detail: r));
          reply = '好的，已记住。$r。下次说同样的话（只换变量），我会按步骤直接做，每一步都核验；'
              '对不上就停下告诉你，再改用看屏代理重做。';
        }
      }
      _reply(reply, '技能已更新。');
      if (mounted) await _speak(reply);
      return;
    }
    final hist = _buildHistory();
    final gen = ++_askGen;
    _stopSpeaking();
    _abortStream();
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

    // 打断 / 改指令：必须在续跑 / 挂起确认分支之前。
    // “停”只在有进行中任务（黑板 ongoing / waitingConfirmation）或挂起确认时拦截，
    // 避免正常聊天里的“算了 / 不用了”误杀；“改成”只在有任务（任何状态）或挂起确认时拦截，
    // 避免“改成英文”这类改写请求被当成换任务。
    final qNoWake = q
        .replaceFirst(
            RegExp(r'^(贾维斯|jarvis)[，,、 ]*', caseSensitive: false), '')
        .trim();
    final boardForInterrupt = await TaskBoard.load();
    final hasActiveTask = DeviceActions.hasPending ||
        (boardForInterrupt != null &&
            (boardForInterrupt.status == TaskStatus.ongoing ||
                boardForInterrupt.status == TaskStatus.waitingConfirmation));
    final stopMatch = RegExp(
            r'^(先)?(停|停止|停下|停一下|取消|不用了|算了|别做了|不要了|不用做了|stop)[。！!~，, ]*$',
            caseSensitive: false)
        .hasMatch(qNoWake);
    if (stopMatch && hasActiveTask) {
      AgentControl.stopAll();
      await TaskBoard.clear();
      DeviceActions.cancelPending();
      _reply('已停。', '任务已停止。');
      if (mounted) await _speak('已停。');
      return;
    }
    final changeEmpty = RegExp(r'^(改成|换成|改为|换为|改一下|换一下)\s*[。！!~，, ]*$')
        .hasMatch(qNoWake);
    if (changeEmpty &&
        (DeviceActions.hasPending || boardForInterrupt != null)) {
      _reply('要改成什么？请说完整，例如：改成给李四打电话。', '任务执行完毕。');
      if (mounted) await _speak('要改成什么？请说完整，例如：改成给李四打电话。');
      return;
    }
    final changeMatch =
        RegExp(r'^(改成|换成|改为|换为|改一下|换一下)\s*[：:，, ]*(.+)$')
            .firstMatch(qNoWake);
    if (changeMatch != null &&
        (DeviceActions.hasPending || boardForInterrupt != null)) {
      final newGoal = changeMatch
          .group(2)!
          .trim()
          .replaceAll(RegExp(r'[。！!~，, ]+$'), '');
      if (newGoal.isEmpty) {
        _reply('要改成什么？请说完整，例如：改成给李四打电话。', '任务执行完毕。');
        if (mounted) await _speak('要改成什么？请说完整，例如：改成给李四打电话。');
        return;
      }
      await TaskBoard.clear();
      DeviceActions.cancelPending();
      q = newGoal;
    }

    // 任务黑板续跑：“刚才那件事继续”——从黑板当前步接着跑。
    // 必须放在挂起确认分支之前：否则续跑口令会被当成确认答复吃掉。
    if (q.contains('刚才那件事继续')) {
      // 还有待确认的操作时，先解决确认，别把续跑当成新任务。
      if (DeviceActions.hasPending) {
        _reply('还有一个待确认的操作，请先回复“确认”或“取消”。', '任务执行完毕。');
        if (mounted) await _speak('还有一个待确认的操作，请先回复“确认”或“取消”。');
        return;
      }
      final board = await TaskBoard.load();
      if (board != null &&
          board.status != TaskStatus.completed &&
          board.hasRemaining) {
        final String answer;
        try {
          answer = await AgentService.resumeBoard(
            settings: _settings,
            board: board,
            history: hist,
            onProgress: (status) {
              if (mounted) {
                setState(() {
                  _statusLine = status;
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
        return;
      }
      _reply('没有未完成的任务。', '任务执行完毕。');
      if (mounted) await _speak('没有未完成的任务。');
      return;
    }

    // 有挂起确认（确认发送 / 选联系人）：用户原话直走 tryHandle，
    // 跳过大模型规划与汇总——大模型会改写确认问句，“对”也到不了确认逻辑。
    if (DeviceActions.hasPending) {
      final direct =
          await DeviceActions.tryHandle(q, settings: _settings);
      if (direct != null) {
        unawaited(AuditLog.log('确认环节：$q', ok: !_confirmReplyFailed(direct), detail: direct));
        _reply(direct, '任务执行完毕。');
        _maybeConsolidateMemory();
        if (mounted) await _speak(direct);
        // 确认链已解决（没有新的挂起）：结算黑板当前步。
        // 还有新挂起（例如选完联系人后又弹出拨号确认）时不结算，等下一句答复。
        if (!DeviceActions.hasPending) {
          final board = await TaskBoard.load();
          if (board != null &&
              board.status == TaskStatus.waitingConfirmation) {
            if (_confirmReplyFailed(direct)) {
              // 这步没跑通：黑板记 failed（不清板，可续跑），不继续剩余步骤。
              final tStep = board.currentStep;
              if (tStep != null) tStep.error = direct;
              board.status = TaskStatus.failed;
              await SkillStore.recordFailure(board.goal);
              await board.save();
            } else {
              if (board.currentStepIndex < board.steps.length) {
                board.steps[board.currentStepIndex].isCompleted = true;
                board.currentStepIndex++;
              }
              if (board.currentStepIndex >= board.steps.length) {
                // 全部步骤跑完：沉淀 skill，清黑板。
                board.status = TaskStatus.completed;
                await board.save();
                await SkillStore.recordSuccess(board.goal, board.steps);
                await TaskBoard.clear();
              } else {
                // 还有剩余步骤：继续跑，结果追加回复。
                board.status = TaskStatus.ongoing;
                await board.save();
                final String cont;
                try {
                  cont = await AgentService.resumeBoard(
                    settings: _settings,
                    board: board,
                    history: hist,
                    onProgress: (status) {
                      if (mounted) {
                        setState(() {
                          _statusLine = status;
                        });
                      }
                    },
                  );
                } catch (e) {
                  _reply('执行失败：$e\n\n请检查设置里的接口地址、模型、Key 和网络。',
                      '智能体执行失败。');
                  return;
                }
                _reply(cont, '任务执行完毕。');
                _maybeConsolidateMemory();
                if (mounted) await _speak(cont);
              }
            }
          }
        }
        return;
      }
      // tryHandle 返回 null：挂起已作废（用户说了别的事），继续走正常流程。
    }

    final String answer;
    try {
      // 启用 Agent 智能体服务
      answer = await AgentService.runAgent(
        settings: _settings,
        userGoal: q,
        history: hist,
        onProgress: (status) {
          if (mounted && gen == _askGen) {
            setState(() {
              _statusLine = status; // 实时显示底层子任务执行步骤
            });
          }
        },
        onDelta: (d) {
          if (gen == _askGen) _onStreamDelta(d);
        },
      );
    } catch (e) {
      if (gen != _askGen) return; // 已被新提问打断
      _abortStream();
      _reply('执行失败：$e\n\n请检查设置里的接口地址、模型、Key 和网络。',
          '智能体执行失败。');
      return;
    }
    if (gen != _askGen) return; // 回答期间用户又问了新问题：旧回答作废
    _maybeConsolidateMemory();
    if (_sentenceCtl != null) {
      await _finishStream(answer); // 已经边出字边播了，只做定稿和补播
      return;
    }
    _reply(answer, '任务执行完毕。');
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
    LocalLlmService.resetFailure();
    unawaited(_applySettingsSideEffects());
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

  Future<void> _enterApp({bool silent = false}) async {
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
    if (!silent) {
      _startSyncedSpeechAnimation(welcome);
      await _speak(welcome);
    }
    final launch = _pendingLaunch;
    _pendingLaunch = null;
    if (launch != null) {
      await _runLaunchAction(
          launch['action']?.toString() ?? '', launch['text']?.toString() ?? '');
    } else {
      _resumeWake(); // 常驻待命：进主界面后立刻开始听唤醒词
    }
  }

  // ---------- Build ----------

// 2. 升级后的 build 方法（深空径向渐变背景）
  @override
  Widget build(BuildContext context) {
    _syncOrb(); // 悬浮球状态跟随（只在变化时发）
    if (_state == JarvisState.booting) return _bootScreen();
    final themeColor = getActiveThemeColor(_state, 0);
    final keyboardOpen = MediaQuery.viewInsetsOf(context).bottom > 0;

    final page = Scaffold(
      backgroundColor: const Color(0xFF050811),
      body: Stack(
        children: [
          // 深邃夜蓝渐变底（#28 配色）：#050811 → #0B132B → #050811。
          // 地球已收进舞台圆环内（见 _stage），不再铺整屏背景。
          const Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [
                    Color(0xFF050811),
                    Color(0xFF0B132B),
                    Color(0xFF050811),
                  ],
                ),
              ),
            ),
          ),
          // #35：背景激光层已删（顶部青光 + 底部紫光 + 下方极光层），圆环保留，呼吸效果保留。
          // 边缘暗化：顶部黑 0.65 → 透明 → 底部黑 0.8，保证顶部与底部面板清晰。
          // 只压在背景层之上，不影响 SafeArea 内的内容。
          const Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Color(0xA6000000),
                    Colors.transparent,
                    Color(0xCC000000),
                  ],
                  stops: [0.0, 0.5, 1.0],
                ),
              ),
            ),
          ),
          Positioned.fill(
              child: RepaintBoundary(child: CustomPaint(painter: _grid))),
          Positioned.fill(
            child: SafeArea(
              child: Stack(
                children: [
                  Column(
                    children: [
                      _topBar(themeColor),
                      // 输入框默认隐藏；展开并聚焦时键盘弹起，保留键盘分支。
                      if (keyboardOpen)
                        _compactStatus(themeColor)
                      else
                        _buildMiddleHudSection(_stage(themeColor)),
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
                    // #35：顶栏品牌字已删（J.A.R.V.I.S / STARK / PWR）。
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Container(
                          width: 8,
                          height: 8,
                          decoration: const BoxDecoration(
                            color: Color(0xFF00FF9D),
                            shape: BoxShape.circle,
                            boxShadow: [
                              BoxShadow(
                                  color: Color(0xFF00FF9D), blurRadius: 6)
                            ],
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(
                            'SYSTEM ACTIVE',
                            style: dim.copyWith(
                                color: Colors.white70,
                                fontSize: 12,
                                letterSpacing: 1.5,
                                fontWeight: FontWeight.bold)),
                      ],
                    ),
                  ],
                ),
                const Spacer(),
                Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text(_clock.value,
                        style: dim.copyWith(color: Colors.white54)),
                  ],
                ),
              ],
            ),
          );
        },
      ),
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
          // 地球收进圆环：裁成圆形，与反应堆圆环同心，直径为环直径 ×1.08
          //（盖住主环 0.98，仍在霓虹环 1.15 与声波点阵 1.1 之内，霓虹外框仍可见），
          // 由呼吸包装带着与圆环一起呼吸。
          // 放在画笔层之上：光环/声波点阵（在圆外）仍可见，星云光球被地球取代。
          // 居中修正：原图 1030×1190 里地球圆心在 (515,650)，比图片中心 (515,595)
          // 低 55px；BoxFit.cover 默认居中裁切会让地球看起来偏下。
          // 注意符号（#32 曾写反为 -0.69，结果地球更偏下）：图片比框高，溢出
          // 一半为 0.0775；alignment y=+0.69 把图片上移 55/1030（≈0.053），
          // 地球圆心正好落到圆环中心。y 为负会把图片下移、地球更偏下，切勿再反。
          Positioned.fill(
            child: LayoutBuilder(
              builder: (context, constraints) {
                // 与 ReactorPainter 的 r 口径一致：min(w,h)×0.36。
                final r = math.min(
                        constraints.maxWidth, constraints.maxHeight) *
                    0.36;
                final earthD = r * 2 * 1.08;
                // 公转（#34）：不是钟表指针那种原地转动，而是地球整体绕舞台
                // 中心公转：圆心沿小圆轨道走，30 秒一圈，逆时针（与真实地球
                // 公转方向一致，屏幕 y 向下故用 -sin）。轨道半径 0.05*r，
                // 地球边缘最远到 1.13r，仍在霓虹环 1.15r 之内。
                // 用现成的每帧 _tick（秒）驱动，连续渲染，每帧位置都不同。
                return Center(
                  child: ListenableBuilder(
                    listenable: _tick,
                    builder: (context, _) {
                      final angle =
                          (_tick.value % 30) / 30 * math.pi * 2;
                      final orbitR = r * 0.05;
                      return Transform.translate(
                        offset: Offset(
                          math.cos(angle) * orbitR,
                          -math.sin(angle) * orbitR,
                        ),
                        child: ClipOval(
                          child: SizedBox(
                            width: earthD,
                            height: earthD,
                            child: Image.asset(
                              'assets/images/earth_bg.jpg',
                              fit: BoxFit.cover,
                              alignment: const Alignment(0.0, 0.69),
                              errorBuilder: (_, __, ___) =>
                                  const SizedBox.shrink(),
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                );
              },
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
  // --- 新增：中间核心按钮呼吸动效与发光文本包装组件 ---
  Widget _buildMiddleHudSection(Widget existingHudWidget) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        // 现有 HUD 舞台（330px）加上 3 秒周期呼吸动效
        ScaleTransition(
          scale: _pulseAnimation,
          child: existingHudWidget,
        ),
        const SizedBox(height: 16),
        // 底部“JARVIS GLOBAL SYSTEM”青色发光文字
        const Text(
          "JARVIS GLOBAL SYSTEM",
          style: TextStyle(
            color: Color(0xFF00F0FF),
            fontSize: 12,
            fontWeight: FontWeight.w600,
            letterSpacing: 2.0,
            shadows: [
              Shadow(
                color: Color(0xFF00F0FF),
                blurRadius: 10,
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _chatArea(Color themeColor) {
    final thinking = _state == JarvisState.thinking;
    final pal = hudPaletteFor(themeColor);
    return Expanded(
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(24),
          // 深蓝玻璃层（草稿配色 #0D1526 @ 0.6）+ 主题色弱发光边框。
          border: Border.all(color: themeColor.withValues(alpha: 0.20)),
          gradient: const LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Color(0x990D1526),
              Color(0x730D1526),
            ],
          ),
          boxShadow: [
            BoxShadow(
              color: themeColor.withValues(alpha: 0.05),
              blurRadius: 20,
              spreadRadius: 2,
            ),
          ],
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(23),
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
            child: Container(
              padding: const EdgeInsets.all(16),
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
                  // 打字机改在上方输出框显示：动画进行中时，最后一条助手消息
                  // 显示逐字增长的 _displayedOutputText，动画结束恢复全文。
                  final displayMsg =
                      (i == _messages.length - 1 &&
                              m.role == 'assistant' &&
                              _isSpeakingWithAnimation)
                          ? _Msg('assistant', _displayedOutputText)
                          : m;
                  return _MessageTile(
                      msg: displayMsg,
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

  // ---------- Bottom: voice-first control panel (Gemini) ----------

  Widget _bottomArea(Color themeColor) {
    return _buildCleanVoiceControlPanel(themeColor);
  }

  // --- 精简后的底部控制面板（纯语音为主，输入框默认隐藏、点键盘图标展开，紧凑版） ---
  // 接线说明（与草稿的不同，均已按真实文件修正）：
  // 1. 麦克风 onTap 接真实 _toggleMic（草稿的 _handleCoreTap 不存在）。
  // 2. 聆听时识别文字为纯文字行（无框无底），输入框默认隐藏，说话内容仍可见。
  // 3. 保留静音按钮与 Agent 状态行（否则静音和任务进度没有入口）。
  // 4. 面板自带 #29 的悬浮动效（原 _buildGlassControlPanel 的 float 包装已并入此处）。
  // 5. #32：下方不要框——已删底部面板的外框/毛玻璃底与下面的输出框，只剩
  //    输入行（展开时）+ 麦克风行 + 提示行；上方输出框（_chatArea）为唯一输出框，
  //    打字机改在上方输出框显示（见 _chatArea 与 _startSyncedSpeechAnimation）。
  //    输入框默认隐藏，点键盘图标展开紧凑版（单行、小字、小发送键）。
  Widget _buildCleanVoiceControlPanel(Color themeColor) {
    final listening = _state == JarvisState.listening;
    // #32 下方不要框：无外框容器、无毛玻璃底、无下面的输出框，只剩控制行。
    return AnimatedBuilder(
      animation: _floatAnimation,
      builder: (context, child) {
        return Transform.translate(
          offset: Offset(0, _floatAnimation.value),
          child: child,
        );
      },
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // 聆听时实时识别文字：纯文字行，无框无底。
            if (listening)
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: Text(
                  _recognized.isEmpty ? '正在聆听，请开始说话…' : _recognized,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                      color: Colors.white70, fontSize: 14, height: 1.4),
                ),
              ),
                  // 输入框默认隐藏，点键盘图标展开；紧凑版（单行、小字、小发送键）。
                  if (_showInput)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 14),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          Expanded(
                            child: TextField(
                              controller: _input,
                              minLines: 1,
                              maxLines: 1,
                              textInputAction: TextInputAction.send,
                              onSubmitted: (_) => _send(),
                              style: const TextStyle(
                                  color: Colors.white, fontSize: 13),
                              cursorColor: themeColor,
                              decoration: InputDecoration(
                                hintText: '输入问题',
                                hintStyle: const TextStyle(
                                    color: Colors.white38, fontSize: 13),
                                filled: true,
                                fillColor:
                                    Colors.white.withValues(alpha: 0.06),
                                border: OutlineInputBorder(
                                    borderRadius: BorderRadius.circular(20),
                                    borderSide: BorderSide(
                                        color: Colors.white
                                            .withValues(alpha: 0.14))),
                                enabledBorder: OutlineInputBorder(
                                    borderRadius: BorderRadius.circular(20),
                                    borderSide: BorderSide(
                                        color: Colors.white
                                            .withValues(alpha: 0.14))),
                                focusedBorder: OutlineInputBorder(
                                    borderRadius: BorderRadius.circular(20),
                                    borderSide: BorderSide(
                                        color: themeColor.withValues(
                                            alpha: 0.8),
                                        width: 1.4)),
                                contentPadding:
                                    const EdgeInsets.symmetric(
                                        horizontal: 14, vertical: 8),
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Opacity(
                            opacity:
                                _state == JarvisState.thinking ? 0.4 : 1,
                            child: Container(
                              width: 38,
                              height: 38,
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                gradient: LinearGradient(
                                  begin: Alignment.topLeft,
                                  end: Alignment.bottomRight,
                                  colors: [
                                    themeColor,
                                    const Color(0xFF00F0FF)
                                  ],
                                ),
                                boxShadow: [
                                  BoxShadow(
                                      color: themeColor.withValues(
                                          alpha: 0.30),
                                      blurRadius: 12),
                                ],
                              ),
                              child: IconButton(
                                tooltip: '发送',
                                onPressed: _state == JarvisState.thinking
                                    ? null
                                    : () => _send(),
                                icon: const Icon(Icons.arrow_upward,
                                    color: Colors.white, size: 18),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  // 核心大号语音图标 / 麦克风交互按钮（左静音，右键盘图标展开输入框）
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      IconButton(
                        tooltip: _muted ? '取消静音' : '静音',
                        onPressed: _toggleMute,
                        icon: Icon(_muted ? Icons.volume_off : Icons.volume_up,
                            color: Colors.white60),
                      ),
                      GestureDetector(
                        onTap: _toggleMic,
                        child: AnimatedContainer(
                          duration: const Duration(milliseconds: 300),
                          width: 64,
                          height: 64,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            gradient: const LinearGradient(
                              colors: [Color(0xFF00F0FF), Color(0xFF7000FF)],
                              begin: Alignment.topLeft,
                              end: Alignment.bottomRight,
                            ),
                            boxShadow: [
                              BoxShadow(
                                color: const Color(0xFF00F0FF)
                                    .withValues(alpha: 0.4),
                                blurRadius: 15,
                                spreadRadius: 2,
                              ),
                            ],
                          ),
                          child: const Icon(
                            Icons.mic,
                            color: Colors.white,
                            size: 30,
                          ),
                        ),
                      ),
                      IconButton(
                        tooltip: _showInput ? '隐藏输入框' : '显示输入框',
                        onPressed: () =>
                            setState(() => _showInput = !_showInput),
                        icon: Icon(
                            _showInput
                                ? Icons.keyboard_hide
                                : Icons.keyboard,
                            color: _showInput
                                ? const Color(0xFF00F0FF)
                                : Colors.white60),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    '点击麦克风开始语音交互',
                    style: TextStyle(
                      color: Colors.white60,
                      fontSize: 11,
                      letterSpacing: 1.0,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(_statusLine,
                      style: const TextStyle(
                          color: Colors.white38, fontSize: 11)),
          ],
        ),
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
          margin: const EdgeInsets.only(bottom: 12),
          padding: EdgeInsets.fromLTRB(16, 12, 16, user ? 12 : 4),
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
                      color: _ice, height: 1.6, fontSize: 16)),
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
