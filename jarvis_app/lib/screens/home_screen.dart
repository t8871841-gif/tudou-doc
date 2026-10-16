import 'dart:async';
import 'dart:math' as math;

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../services/agent_service.dart';
import '../services/asr_service.dart';
import '../services/edge_tts_service.dart';
import '../services/settings_store.dart';
import '../widgets/hud_painters.dart';
import 'settings_screen.dart';

enum JarvisState { booting, idle, listening, thinking, speaking }

const _ice = Color(0xFFEAFFFF);
const _bootCyan = Color(0xFF19D3FF);
const _bootBg = Color(0xFF00060B);

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
  late final ReactorPainter _reactor;
  late final FxPainter _fx;

  // ---- Notifiers -------------------------------------------------------
  // Only the painters, the mic button and the boot screen need a per-frame
  // tick. Everything else listens to a low-rate notifier, so a clock tick or
  // a HUD drift no longer rebuilds the whole screen via setState.
  final _tick = ValueNotifier<double>(0); // seconds, every frame
  late final ValueNotifier<double> _hue; // reactor hue, synced at ~20 fps
  final _glitch = ValueNotifier<int>(0); // 0 none, 1/2 RGB split, 3 skew
  final _clock = ValueNotifier<String>('--:--:--');
  final _hudLevels = ValueNotifier<List<double>>(
      const <double>[0.7, 0.6, 0.8, 0.75, 0.65, 0.7]);
  final _micDown = ValueNotifier<bool>(false);

  late final Listenable _topAnim = Listenable.merge([_hue, _glitch, _clock]);
  late final Listenable _hudAnim = Listenable.merge([_hue, _hudLevels]);
  late final Listenable _micAnim = Listenable.merge([_tick, _micDown]);

  late final Ticker _ticker;
  Duration? _lastElapsed;
  Duration _lastHueSync = Duration.zero;
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
    );
    _fx = FxPainter(
      tick: _tick,
      mode: () => _hudMode,
      wakeMode: () => _wakeMode,
      micLevel: () => _micLevel,
    );
    _hue = ValueNotifier<double>(_reactor.smoothedHue.toDouble());

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
    _set(() {});
    unawaited(_warmUpAsr());
  }

  @override
  void dispose() {
    _ticker.dispose();
    _clockTimer?.cancel();
    _hudTimer?.cancel();
    _tick.dispose();
    _hue.dispose();
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

    // Hue only drifts slowly: sync it to the widgets at ~20 fps.
    if (elapsed - _lastHueSync >= const Duration(milliseconds: 50)) {
      _lastHueSync = elapsed;
      final h = _reactor.smoothedHue.toDouble();
      if (h != _hue.value) _hue.value = h;
    }
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

  /// Last 7 non-system messages (call before adding the new question).
  List<Map<String, String>> _buildHistory() {
    final prior = _messages.where((m) => m.role != 'system').toList();
    final start = math.max(0, prior.length - 7);
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
    if (mounted) {
      setState(() {});
      // Settings pops with true only after a real save, so this
      // confirmation is shown on Home (a SnackBar shown in Settings
      // would vanish instantly on pop).
      if (saved == true) _notice('设置已保存，声音已切换。');
    }
  }

  // ---------- Boot (HTML: tap to wake) ----------

  Future<void> _enterApp() async {
    if (_state != JarvisState.booting) return;
    const welcome =
        '您好，我是 Jarvis。已为您装配智能体与联网跨应用能力。您可以直接说话、点麦克风，或者打字提问。';
    setState(() {
      _state = JarvisState.idle;
      _statusLine = '核心已上线，随时可以开始。';
      _messages.add(const _Msg('assistant', welcome));
    });
    await _speak(welcome);
  }

  // ---------- Build ----------

  @override
  Widget build(BuildContext context) {
    if (_state == JarvisState.booting) return _bootScreen();
    return Scaffold(
      backgroundColor: hudDeep,
      body: Stack(
        children: [
          // Static background: cached in its own layer.
          Positioned.fill(
              child: RepaintBoundary(child: CustomPaint(painter: _grid))),
          Positioned.fill(
            child: SafeArea(
              child: Stack(
                children: [
                  Column(
                    children: [
                      _topBar(),
                      _stage(),
                      _chatArea(),
                      _bottomArea(),
                    ],
                  ),
                  Positioned.fill(
                    child: IgnorePointer(
                      child: RepaintBoundary(child: _cornerBrackets()),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _bootScreen() {
    // HTML #boot: breathing pulse ring + JARVIS + "轻触屏幕 · 唤醒系统".
    return Scaffold(
      backgroundColor: _bootBg,
      body: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: _enterApp,
        child: Center(
          child: ValueListenableBuilder<double>(
            valueListenable: _tick,
            builder: (_, t, _) {
              // 2.2s breathing pulse: scale 1 -> 1.12, opacity 1 -> .55.
              final phase = (t % 2.2) / 2.2;
              final ease = 0.5 - 0.5 * math.cos(phase * math.pi * 2);
              final scale = 1 + 0.12 * ease;
              final opacity = 1 - 0.45 * ease;
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
                      child: Container(
                        width: 170,
                        height: 170,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          border: Border.all(color: _bootCyan, width: 2),
                          gradient: RadialGradient(colors: [
                            _bootCyan.withValues(alpha: 0.22),
                            Colors.transparent,
                          ]),
                          boxShadow: [
                            BoxShadow(
                                color: _bootCyan.withValues(alpha: 0.55),
                                blurRadius: 40,
                                spreadRadius: 2),
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
                      child: Text('JARVIS',
                          style: TextStyle(
                            color: _ice,
                            fontSize: 34,
                            fontWeight: FontWeight.w500,
                            letterSpacing: spacing,
                            shadows: const [
                              Shadow(color: _bootCyan, blurRadius: 18),
                              Shadow(color: _bootCyan, blurRadius: 40),
                            ],
                          )),
                    ),
                  ),
                  const SizedBox(height: 18),
                  Opacity(
                    opacity: blink,
                    child: const Text('轻触屏幕 · 唤醒系统',
                        style: TextStyle(
                            color: _bootCyan,
                            fontSize: 13,
                            letterSpacing: 4.5)),
                  ),
                ],
              );
            },
          ),
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
      shadows: [Shadow(color: glow, blurRadius: blur)],
    );
  }

  // ---------- Top bar (HTML .top) ----------

  Widget _topBar() {
    return RepaintBoundary(
      child: ListenableBuilder(
        listenable: _topAnim,
        builder: (_, _) {
          final color = hueColor(_hue.value, 1);
          final style = _glow(color, size: 12, spacing: 2.2);
          return Padding(
            padding: const EdgeInsets.fromLTRB(18, 14, 18, 0),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _glitchTitle(color),
                    Text('STARK INDUSTRIES', style: style),
                  ],
                ),
                const Spacer(),
                Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text(_clock.value, style: style),
                    const SizedBox(height: 2),
                    // No battery plugin in the dependency set (pubspec and
                    // android/ are off-limits): same "PWR --" fallback the
                    // HTML shows when navigator.getBattery is unavailable.
                    Text('PWR --', style: style),
                  ],
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _glitchTitle(Color color) {
    final stage = _glitch.value;
    final shadows = <Shadow>[
      Shadow(color: color, blurRadius: 8),
      if (stage == 1) ...const [
        Shadow(offset: Offset(-2, 0), color: Color(0xFFFF00FF)),
        Shadow(offset: Offset(2, 0), color: Color(0xFF00FFFF)),
      ],
      if (stage == 2) ...const [
        Shadow(offset: Offset(2, 0), color: Color(0xFFFF00FF)),
        Shadow(offset: Offset(-2, 0), color: Color(0xFF00FFFF)),
      ],
    ];
    final Widget title = Text('J.A.R.V.I.S',
        style: TextStyle(
            color: color,
            fontSize: 20,
            fontWeight: FontWeight.w600,
            letterSpacing: 6,
            shadows: shadows));
    return switch (stage) {
      1 => Transform.translate(offset: const Offset(2, 0), child: title),
      2 => Transform.translate(offset: const Offset(-2, 0), child: title),
      3 => Transform(
          transform: Matrix4.skewX(0.14),
          alignment: Alignment.center,
          child: title),
      _ => title,
    };
  }

  // ---------- Reactor stage (HTML .stage) ----------

  Widget _stage() {
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
                  if (_settings.particles)
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
                builder: (_, _) => _stageOverlay(),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _stageOverlay() {
    final hue = _hue.value;
    final levels = _hudLevels.value;
    return Stack(
      children: [
        Center(child: _statusText(hue)),
        Positioned(left: 0, top: 0, child: _gearButton(hue)),
        Positioned(
          left: 0,
          right: 0,
          bottom: 4,
          child: Center(child: _wakeChip(hue)),
        ),
        Positioned(
          right: 0,
          top: 0,
          child: _readout(
            hue,
            const ['POWER', 'LATENCY', 'SIGNAL'],
            levels.sublist(3),
            alignRight: true,
          ),
        ),
        Positioned(
          left: 0,
          bottom: 6,
          child: _readout(
            hue,
            const ['ARC REACTOR', 'NEURAL LINK', 'VOICE CORE'],
            levels.sublist(0, 3),
            alignRight: false,
          ),
        ),
      ],
    );
  }

  Widget _statusText(double hue) {
    final color = hueColor(hue, 1);
    final (en, cn) = switch (_state) {
      JarvisState.listening => ('LISTENING', '正在聆听'),
      JarvisState.thinking => ('PROCESSING', '思考中'),
      JarvisState.speaking => ('RESPONDING', '回应中'),
      _ => _wakeMode ? ('WAKE WORD', '等待唤醒') : ('STANDBY', '待命'),
    };
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(en, style: _glow(color, size: 13, spacing: 5.2, blur: 10)),
        const SizedBox(height: 6),
        Text(cn,
            style: _glow(color,
                size: 30, spacing: 7.5, blur: 12, color: _ice)),
      ],
    );
  }

  Widget _gearButton(double hue) {
    return GestureDetector(
      onTap: _openSettings,
      child: Container(
        width: 38,
        height: 38,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          border: Border.all(color: hueColor(hue, 0.25)),
        ),
        // Icon instead of the "⚙︎" text glyph, which some devices render
        // as a colour emoji.
        child: Center(
          child: Icon(Icons.settings_outlined,
              size: 18, color: hueColor(hue, 1)),
        ),
      ),
    );
  }

  Widget _wakeChip(double hue) {
    final color = hueColor(hue, 1);
    final on = _wakeEnabled;
    return GestureDetector(
      onTap: _toggleWake,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: on ? color : hueColor(hue, 0.25)),
          color: on ? hueColor(hue, 0.25) : Colors.transparent,
          boxShadow: on
              ? [BoxShadow(color: hueColor(hue, 0.25), blurRadius: 14)]
              : null,
        ),
        child: Text('唤醒词 · ${on ? '开' : '关'}',
            style: TextStyle(color: color, fontSize: 13, letterSpacing: 2.6)),
      ),
    );
  }

  /// HUD readout bars (HTML `.rd`): label + glowing 2px bar.
  Widget _readout(double hue, List<String> labels, List<double> values,
      {required bool alignRight}) {
    final color = hueColor(hue, 1);
    final track = hueColor(hue, 0.25);
    return IgnorePointer(
      child: SizedBox(
        width: 92,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment:
              alignRight ? CrossAxisAlignment.end : CrossAxisAlignment.start,
          children: [
            for (var i = 0; i < labels.length; i++) ...[
              Text(labels[i],
                  style: _glow(color,
                      size: 9,
                      spacing: 1.6,
                      blur: 6,
                      color: color.withValues(alpha: 0.8))),
              const SizedBox(height: 3),
              Container(
                height: 2,
                width: 92,
                color: track,
                alignment: Alignment.centerLeft,
                child: Container(
                  width: 92 * values[i].clamp(0.0, 1.0),
                  height: 2,
                  color: color,
                ),
              ),
              const SizedBox(height: 7),
            ],
          ],
        ),
      ),
    );
  }

  Widget _cornerBrackets() {
    return ValueListenableBuilder<double>(
      valueListenable: _hue,
      builder: (_, hue, _) {
        final c = hueColor(hue, 0.85);
        return Stack(
          children: [
            Positioned(
                left: 8,
                top: 6,
                child: _Bracket(color: c, left: true, top: true)),
            Positioned(
                right: 8,
                top: 6,
                child: _Bracket(color: c, right: true, top: true)),
            Positioned(
                left: 8,
                bottom: 8,
                child: _Bracket(color: c, left: true, bottom: true)),
            Positioned(
                right: 8,
                bottom: 8,
                child: _Bracket(color: c, right: true, bottom: true)),
          ],
        );
      },
    );
  }

  // ---------- Dialogue (HTML .sub colours) ----------

  Widget _chatArea() {
    final thinking = _state == JarvisState.thinking;
    return Expanded(
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 10),
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: Colors.black.withValues(alpha: 0.32),
          border: Border.all(color: hudCyan.withValues(alpha: 0.22)),
          borderRadius: BorderRadius.circular(12),
        ),
        child: ListView.builder(
          controller: _scroll,
          itemCount: _messages.length + (thinking ? 1 : 0),
          itemBuilder: (context, i) {
            // The extra last row only exists while thinking.
            if (i == _messages.length) {
              return const Padding(
                padding: EdgeInsets.all(10),
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: hudCyan)),
                  SizedBox(width: 10),
                  Text('Jarvis 正在思考…',
                      style: TextStyle(color: Colors.white70)),
                ]),
              );
            }
            final m = _messages[i];
            return _MessageTile(msg: m, onSpeak: () => _speak(m.text));
          },
        ),
      ),
    );
  }

  // ---------- Bottom: mic + input (HTML .mic) ----------

  Widget _bottomArea() {
    final outline = OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide(color: hudCyan.withValues(alpha: 0.35)));
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
      child: Column(
        children: [
          if (_state == JarvisState.listening)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(children: [
                const Icon(Icons.graphic_eq, color: hudCyan),
                const SizedBox(width: 8),
                Expanded(
                    child: Text(
                        _recognized.isEmpty ? '正在聆听，请开始说话…' : _recognized,
                        style: const TextStyle(color: hudCyan))),
                const Text('点麦克风结束',
                    style: TextStyle(color: Colors.white38, fontSize: 11)),
              ]),
            ),
          _micButton(),
          const SizedBox(height: 10),
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              IconButton(
                tooltip: _muted ? '取消静音' : '静音',
                onPressed: _toggleMute,
                icon: Icon(_muted ? Icons.volume_off : Icons.volume_up,
                    color: hudCyan),
              ),
              Expanded(
                child: TextField(
                  controller: _input,
                  minLines: 1,
                  maxLines: 4,
                  textInputAction: TextInputAction.send,
                  onSubmitted: (_) => _send(),
                  style: const TextStyle(color: Colors.white),
                  decoration: InputDecoration(
                    hintText: '输入问题，或点麦克风说话，也可说“贾维斯”唤醒',
                    hintStyle: const TextStyle(color: Colors.white38),
                    filled: true,
                    fillColor: Colors.black.withValues(alpha: 0.35),
                    border: outline,
                    enabledBorder: outline,
                    contentPadding: const EdgeInsets.symmetric(
                        horizontal: 13, vertical: 12),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              IconButton.filled(
                onPressed:
                    _state == JarvisState.thinking ? null : () => _send(),
                style: IconButton.styleFrom(
                    backgroundColor: hudCyan, foregroundColor: Colors.black),
                icon: const Icon(Icons.arrow_upward),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(_statusLine,
              style: const TextStyle(color: Colors.white38, fontSize: 10)),
        ],
      ),
    );
  }

  Widget _micButton() {
    // Own layer: the per-frame ripple / ring / bars repaint only this
    // 78px button instead of the whole page.
    return RepaintBoundary(
      child: ListenableBuilder(
        listenable: _micAnim,
        builder: (_, _) {
          final t = _tick.value;
          final hue = _reactor.smoothedHue;
          final color = hueColor(hue, 1);
          final listening = _state == JarvisState.listening;
          final ripplePhase = t % 1;
          return GestureDetector(
            onTap: _toggleMic,
            onTapDown: (_) => _micDown.value = true,
            onTapUp: (_) => _micDown.value = false,
            onTapCancel: () => _micDown.value = false,
            child: Transform.scale(
              scale: _micDown.value ? 0.92 : 1.0,
              child: SizedBox(
                width: 78,
                height: 78,
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    // Listening pulse ripple (HTML `.mic:after` pulse).
                    if (listening)
                      Opacity(
                        opacity: 1 - ripplePhase,
                        child: Transform.scale(
                          scale: 1 + 0.8 * ripplePhase,
                          child: Container(
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              border: Border.all(color: color, width: 2),
                            ),
                          ),
                        ),
                      ),
                    // Rotating dashed outer ring (HTML `.mic:before`, 8s).
                    // The ring is cached; only its rotation changes.
                    Transform.rotate(
                      angle: t * math.pi * 2 / 8,
                      child: RepaintBoundary(
                        child: CustomPaint(
                          size: const Size(102, 102),
                          painter: _DashedRingPainter(
                              color: color.withValues(alpha: 0.6)),
                        ),
                      ),
                    ),
                    // Gradient disc (HTML radial gradient + glow). Static
                    // between hue changes, so it keeps its own layer.
                    RepaintBoundary(
                      child: Container(
                        width: 78,
                        height: 78,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          gradient: RadialGradient(colors: [
                            hueColor(hue, 0.25),
                            Colors.transparent,
                          ], stops: const [
                            0.0,
                            0.7
                          ]),
                          border: Border.all(color: color, width: 2),
                          boxShadow: [
                            BoxShadow(
                                color: hueColor(hue, 0.25),
                                blurRadius: 24,
                                spreadRadius: 1),
                          ],
                        ),
                      ),
                    ),
                    if (listening)
                      CustomPaint(
                        size: const Size(32, 32),
                        painter: _BarsIconPainter(tick: t, color: color),
                      )
                    else
                      Icon(Icons.mic, size: 32, color: color),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// One chat line. Split out of the State so it can be built independently.
class _MessageTile extends StatelessWidget {
  const _MessageTile({required this.msg, required this.onSpeak});
  final _Msg msg;
  final VoidCallback onSpeak;

  @override
  Widget build(BuildContext context) {
    final user = msg.role == 'user';
    return Align(
      alignment: user ? Alignment.centerRight : Alignment.centerLeft,
      child: Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // HTML .sub: user line dim, Jarvis reply bright + glow.
            SelectableText(msg.text,
                style: TextStyle(
                    color: user ? userDimColor : _ice,
                    height: 1.5,
                    fontSize: 14,
                    shadows: user
                        ? null
                        : const [Shadow(color: hudCyan, blurRadius: 12)])),
            if (!user)
              Align(
                alignment: Alignment.centerRight,
                child: IconButton(
                  visualDensity: VisualDensity.compact,
                  tooltip: '朗读回答',
                  onPressed: onSpeak,
                  icon: const Icon(Icons.volume_up_outlined,
                      size: 19, color: hudCyan),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// HUD corner bracket: draws only the requested sides.
class _Bracket extends StatelessWidget {
  const _Bracket({
    required this.color,
    this.left = false,
    this.top = false,
    this.right = false,
    this.bottom = false,
  });
  final Color color;
  final bool left, top, right, bottom;

  @override
  Widget build(BuildContext context) {
    final side = BorderSide(color: color, width: 2);
    return SizedBox(
      width: 22,
      height: 22,
      child: DecoratedBox(
        decoration: BoxDecoration(
          border: Border(
            left: left ? side : BorderSide.none,
            top: top ? side : BorderSide.none,
            right: right ? side : BorderSide.none,
            bottom: bottom ? side : BorderSide.none,
          ),
        ),
      ),
    );
  }
}

/// Dashed outer ring around the mic button (HTML `.mic:before`).
class _DashedRingPainter extends CustomPainter {
  final Color color;
  _DashedRingPainter({required this.color});

  @override
  void paint(Canvas canvas, Size size) {
    final c = Offset(size.width / 2, size.height / 2);
    final r = math.min(size.width, size.height) / 2;
    final rect = Rect.fromCircle(center: c, radius: r);
    final p = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1;
    const n = 24;
    const step = math.pi * 2 / n;
    for (var i = 0; i < n; i++) {
      canvas.drawArc(rect, i * step, step * 0.5, false, p);
    }
  }

  @override
  bool shouldRepaint(covariant _DashedRingPainter old) => old.color != color;
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
