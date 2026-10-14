import 'dart:async';
import 'dart:math' as math;

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:speech_to_text/speech_to_text.dart' as stt;

import '../services/action_service.dart';
import '../services/edge_tts_service.dart';
import '../services/kokoro_tts_service.dart';
import '../services/llm_service.dart';
import '../services/settings_store.dart';
import '../widgets/hud_painters.dart';
import 'settings_screen.dart';

enum JarvisState { booting, idle, listening, thinking, speaking }

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
  final _speech = stt.SpeechToText();
  final _tts = FlutterTts();
  final _player = AudioPlayer();

  JarvisState _state = JarvisState.booting;
  final _tickNotifier = ValueNotifier<double>(0);
  double _tick = 0;
  Duration? _lastTickElapsed;
  Ticker? _ticker;
  Timer? _clockTimer;
  Timer? _hudTimer;
  bool _speechInit = false;
  bool _wakeMode = false;
  bool _muted = false;
  bool _micDown = false;
  String _recognized = '';
  String _statusLine = '正在初始化核心…';
  String _clock = '--:--:--';
  double _micLevel = 0; // 0..1 from speech_to_text sound-level callback
  final List<double> _hudLevels = [0.7, 0.6, 0.8, 0.75, 0.65, 0.7];
  final _rnd = math.Random();
  final List<_Msg> _messages = [];
  late final ReactorPainter _reactor;
  late final FxPainter _fx;

  HudMode get _hudMode => switch (_state) {
        JarvisState.listening => HudMode.listening,
        JarvisState.thinking => HudMode.thinking,
        JarvisState.speaking => HudMode.speaking,
        JarvisState.booting => HudMode.idle,
        JarvisState.idle => HudMode.idle,
      };

  @override
  void initState() {
    super.initState();
    _reactor = ReactorPainter(
      tick: _tickNotifier,
      mode: () => _hudMode,
      wakeMode: () => _wakeMode,
      micLevel: () => _micLevel,
    );
    _fx = FxPainter(
      tick: _tickNotifier,
      mode: () => _hudMode,
      wakeMode: () => _wakeMode,
      micLevel: () => _micLevel,
    );
    // Display-rate tick: drives the painters and per-frame widgets
    // without rebuilding the chat list.
    _ticker = createTicker((elapsed) {
      final dt = _lastTickElapsed == null
          ? 0.016
          : (elapsed - _lastTickElapsed!).inMicroseconds / 1e6;
      _lastTickElapsed = elapsed;
      _tick += dt.clamp(0.0, 0.1);
      _tickNotifier.value = _tick;
    });
    _ticker!.start();
    _clockTimer = Timer.periodic(const Duration(milliseconds: 500), (_) {
      if (!mounted) return;
      final now = DateTime.now();
      setState(() {
        _clock =
            '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}:${now.second.toString().padLeft(2, '0')}';
      });
    });
    // HUD readout bars drift like the HTML setInterval (700ms).
    _hudTimer = Timer.periodic(const Duration(milliseconds: 700), (_) {
      if (!mounted) return;
      setState(() {
        for (var i = 0; i < _hudLevels.length; i++) {
          _hudLevels[i] =
              (_hudLevels[i] * 0.6 + _rnd.nextDouble() * 0.4 + _micLevel * 0.1)
                  .clamp(0.0, 1.0);
        }
      });
    });
    _init();
  }

  Future<void> _init() async {
    await _settings.load();
    if (!mounted) return;
    setState(() {});
  }

  @override
  void dispose() {
    _ticker?.dispose();
    _tickNotifier.dispose();
    _clockTimer?.cancel();
    _hudTimer?.cancel();
    _input.dispose();
    _scroll.dispose();
    _speech.stop();
    _tts.stop();
    _player.dispose();
    super.dispose();
  }

  // ---------- Voice ----------

  Future<void> _speak(String text) async {
    final clean = text.trim();
    if (clean.isEmpty || !_settings.speakReplies || _muted) return;
    if (mounted) setState(() => _state = JarvisState.speaking);
    try {
      await _player.stop();
      final short =
          clean.length > 1200 ? '${clean.substring(0, 1200)}…' : clean;
      final spoken = await _speakByEngine(short);
      if (!spoken) {
        // Last resort: system TTS, honouring the selected voice as far
        // as the device allows.
        await _speakSystem(clean);
      }
    } catch (_) {
      try {
        await _speakSystem(clean);
      } catch (_) {}
    } finally {
      if (mounted && _state == JarvisState.speaking) {
        setState(() => _state = JarvisState.idle);
      }
    }
  }

  /// Tries the configured engine first, then the other online/offline
  /// engine. Order: Kokoro (offline) -> Edge (online) -> system TTS.
  /// Returns true when audio was played. System TTS is handled
  /// by the caller as the final fallback, so it is not tried here.
  Future<bool> _speakByEngine(String text) async {
    final engine = _settings.ttsEngine;
    if (engine == JarvisSettings.engineSystem) return false;
    final firstKokoro = engine != JarvisSettings.engineEdge;
    final attempts = firstKokoro
        ? ['kokoro', 'edge']
        : ['edge', 'kokoro'];
    for (final e in attempts) {
      try {
        if (e == 'kokoro') {
          final wav = await KokoroTtsService.synthesize(
            text,
            voiceId: _settings.kokoroVoice,
            rate: _settings.rate,
            pitch: _settings.pitch,
          );
          await _player.play(BytesSource(wav));
          await _player.onPlayerComplete.first.timeout(
              Duration(seconds: (text.length / 8).clamp(3, 30).toInt()));
          return true;
        } else {
          final mp3 = await EdgeTtsService.synthesize(
            text,
            voice: _settings.edgeVoice,
            rate: _settings.rate,
            pitch: _settings.pitch,
          );
          await _player.play(BytesSource(mp3));
          await _player.onPlayerComplete.first.timeout(
              Duration(seconds: (text.length / 8).clamp(3, 30).toInt()));
          return true;
        }
      } catch (_) {
        // Try the next engine.
        try {
          await _player.stop();
        } catch (_) {}
      }
    }
    return false;
  }

  Future<void> _speakSystem(String clean) async {
    await _tts.setLanguage('zh-CN');
    await _tts.setSpeechRate(_settings.rate);
    await _tts.setPitch(_settings.pitch);
    await _applySystemVoiceFallback();
    await _tts.speak(clean);
  }

  /// Best-effort voice match for the system-TTS fallback.
  /// Gender comes from the selected voice of the active engine
  /// (Kokoro zf_* = female, Edge Xiao* = female). System voices vary
  /// by device, so match on locale + a gender hint in the name, and
  /// silently keep the device default when nothing matches.
  Future<void> _applySystemVoiceFallback() async {
    try {
      final voices = await _tts.getVoices;
      if (voices is! List || voices.isEmpty) return;
      final wantFemale = _settings.ttsEngine == JarvisSettings.engineKokoro
          ? _settings.kokoroVoice.startsWith('zf_')
          : _settings.edgeVoice.contains('Xiao');
      Map? best;
      for (final v in voices) {
        if (v is! Map) continue;
        final locale = (v['locale'] ?? '').toString();
        if (!locale.toLowerCase().startsWith('zh')) continue;
        final name = (v['name'] ?? '').toString().toLowerCase();
        final looksFemale = name.contains('female') ||
            name.contains('女') ||
            name.contains('xiaoxiao') ||
            name.contains('xiao');
        final looksMale = name.contains('male') ||
            name.contains('男') ||
            name.contains('yunyang') ||
            name.contains('yun');
        if (wantFemale ? looksFemale : looksMale) {
          best = v;
          break;
        }
        best ??= v;
      }
      if (best != null) {
        await _tts.setVoice({
          'name': best['name'].toString(),
          'locale': best['locale'].toString(),
        });
      }
    } catch (_) {}
  }

  Future<bool> _ensureSpeech() async {
    if (_speechInit) return true;
    try {
      _speechInit = await _speech.initialize(
        onStatus: (s) {
          if ((s == 'done' || s == 'notListening') && mounted) {
            if (_state == JarvisState.listening) {
              setState(() => _state = JarvisState.idle);
            }
            // Wake-word standby: restart listening quietly.
            if (_wakeMode && mounted && !_muted) _startWakeListening();
          }
        },
        onError: (_) {},
      );
    } catch (_) {
      _speechInit = false;
    }
    return _speechInit;
  }

  /// Feeds the reactor sound bars with the real mic level.
  void _onSoundLevel(double level) {
    // Android reports RMS-ish values (roughly -2..10); normalise to 0..1.
    _micLevel = ((level + 2) / 12).clamp(0.0, 1.0);
  }

  /// Standby: listen only for the wake word “贾维斯”.
  Future<void> _startWakeListening() async {
    if (_muted) return;
    if (!await _ensureSpeech()) return;
    _wakeMode = true;
    if (mounted) setState(() {});
    try {
      await _speech.listen(
        listenOptions: stt.SpeechListenOptions(
          localeId: 'zh_CN',
          listenFor: const Duration(seconds: 30),
          pauseFor: const Duration(seconds: 3),
        ),
        onSoundLevelChange: _onSoundLevel,
        onResult: (r) {
          final words = r.recognizedWords;
          if (words.contains('贾维斯')) {
            _wakeMode = false;
            _speech.stop();
            _notice('已唤醒，Jarvis 在听。');
            _startActiveListening();
          }
        },
      );
    } catch (_) {}
  }

  /// Wake-word capsule toggle (HTML chip): on -> start standby, on -> stop.
  Future<void> _toggleWake() async {
    if (_wakeMode) {
      _wakeMode = false;
      await _speech.stop();
      if (mounted) setState(() {});
      return;
    }
    await _startWakeListening();
    if (mounted) setState(() {});
  }

  Future<void> _startActiveListening() async {
    if (_muted) return;
    if (!await _ensureSpeech()) {
      _notice('系统语音识别不可用，请检查麦克风权限。');
      return;
    }
    _wakeMode = false;
    _recognized = '';
    if (mounted) setState(() => _state = JarvisState.listening);
    try {
      await _speech.listen(
        listenOptions: stt.SpeechListenOptions(
          localeId: 'zh_CN',
          listenFor: const Duration(seconds: 60),
          pauseFor: const Duration(seconds: 4),
        ),
        onSoundLevelChange: _onSoundLevel,
        onResult: (r) {
          if (!mounted) return;
          setState(() {
            _recognized = r.recognizedWords;
            _input.text = _recognized;
          });
          if (r.finalResult && _recognized.trim().isNotEmpty) {
            final q = _recognized.trim();
            _recognized = '';
            _send(q);
          }
        },
      );
    } catch (_) {
      if (mounted) setState(() => _state = JarvisState.idle);
    }
  }

  Future<void> _toggleMic() async {
    if (_muted) {
      setState(() => _muted = false);
      _notice('已取消静音。');
      return;
    }
    if (_state == JarvisState.listening) {
      await _speech.stop();
      setState(() => _state = JarvisState.idle);
      if (_input.text.trim().isNotEmpty) _send(_input.text.trim());
      return;
    }
    await _startActiveListening();
  }

  void _toggleMute() {
    setState(() => _muted = !_muted);
    if (_muted) {
      _speech.stop();
      _player.stop();
      _tts.stop();
      _state = JarvisState.idle;
      _notice('已静音。静音时语音唤醒不生效，请点麦克风恢复。');
    }
  }

  // ---------- Chat ----------

  Future<void> _send([String? override]) async {
    final q = (override ?? _input.text).trim();
    if (q.isEmpty || _state == JarvisState.thinking) return;
    FocusScope.of(context).unfocus();
    setState(() {
      _messages.add(_Msg('user', q));
      _input.clear();
      _recognized = '';
      _state = JarvisState.thinking;
      _statusLine = '正在思考…';
    });
    _scrollDown();
    // Device actions first (call / message / alarm / open app).
    // Returns null when the text is not an action command.
    try {
      final actionReply = await DeviceActions.tryHandle(q);
      if (actionReply != null) {
        if (!mounted) return;
        setState(() {
          _messages.add(_Msg('assistant', actionReply));
          _state = JarvisState.idle;
          _statusLine = '已执行设备指令。';
        });
        _scrollDown();
        await _speak(actionReply);
        return;
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _messages.add(_Msg('assistant', '设备指令执行失败：$e'));
        _state = JarvisState.idle;
        _statusLine = '设备指令失败。';
      });
      _scrollDown();
      return;
    }
    try {
      final history = _messages
          .where((m) => m.role != 'system')
          .toList()
          .reversed
          .take(8)
          .toList()
          .reversed
          .map((m) => {'role': m.role, 'content': m.text})
          .toList();
      // Drop the just-added user message from history (it is `q`).
      final hist = history.length > 1
          ? history.sublist(0, history.length - 1)
          : <Map<String, String>>[];
      final answer = await LlmService.ask(
          settings: _settings, question: q, history: hist);
      if (!mounted) return;
      setState(() {
        _messages.add(_Msg('assistant', answer));
        _state = JarvisState.idle;
        _statusLine = '已回答。';
      });
      _scrollDown();
      await _speak(answer);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _messages.add(_Msg('assistant',
            '连接失败：$e\n\n请检查设置里的接口地址、模型、Key 和网络。未填 Key 时无法联网问答。'));
        _state = JarvisState.idle;
        _statusLine = '联网问答失败，请检查设置。';
      });
      _scrollDown();
    }
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
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(m)));
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
        '您好，我是 Jarvis。您可以直接说话、点麦克风，或者打字提问。我可以回答各种各样的问题，还能打电话、发消息、读短信、设闹钟、开 App。';
    setState(() {
      _state = JarvisState.idle;
      _statusLine = '核心已上线，随时可以开始。';
      _messages.add(const _Msg('assistant', welcome));
    });
    await _speak(welcome);
  }

  @override
  Widget build(BuildContext context) {
    if (_state == JarvisState.booting) return _bootScreen();
    return Scaffold(
      backgroundColor: hudDeep,
      body: Stack(
        children: [
          Positioned.fill(
              child: CustomPaint(painter: GridBackgroundPainter())),
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
                  ..._cornerBrackets(),
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
      backgroundColor: const Color(0xFF00060B),
      body: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: _enterApp,
        child: Center(
          child: AnimatedBuilder(
            animation: _tickNotifier,
            builder: (_, _) {
              final t = _tickNotifier.value;
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
                          border: Border.all(
                              color: const Color(0xFF19D3FF), width: 2),
                          gradient: RadialGradient(colors: [
                            const Color(0xFF19D3FF).withValues(alpha: 0.22),
                            Colors.transparent,
                          ]),
                          boxShadow: [
                            BoxShadow(
                                color: const Color(0xFF19D3FF)
                                    .withValues(alpha: 0.55),
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
                            color: const Color(0xFFEAFFFF),
                            fontSize: 34,
                            fontWeight: FontWeight.w500,
                            letterSpacing: spacing,
                            shadows: const [
                              Shadow(
                                  color: Color(0xFF19D3FF), blurRadius: 18),
                              Shadow(
                                  color: Color(0xFF19D3FF), blurRadius: 40),
                            ],
                          )),
                    ),
                  ),
                  const SizedBox(height: 18),
                  Opacity(
                    opacity: blink,
                    child: const Text('轻触屏幕 · 唤醒系统',
                        style: TextStyle(
                            color: Color(0xFF19D3FF),
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

  // ---------- Top bar (HTML .top) ----------

  Widget _topBar() {
    return AnimatedBuilder(
      animation: _tickNotifier,
      builder: (_, _) {
        final color = hueColor(_reactor.smoothedHue, 1);
        return Padding(
          padding: const EdgeInsets.fromLTRB(18, 14, 18, 0),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _glitchTitle(color),
                  Text('STARK INDUSTRIES',
                      style: TextStyle(
                          color: color,
                          fontSize: 12,
                          letterSpacing: 2.2,
                          shadows: [
                            Shadow(color: color, blurRadius: 8),
                          ])),
                ],
              ),
              const Spacer(),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(_clock,
                      style: TextStyle(
                          color: color,
                          fontSize: 12,
                          letterSpacing: 2.2,
                          shadows: [
                            Shadow(color: color, blurRadius: 8),
                          ])),
                  const SizedBox(height: 2),
                  // No battery plugin in the dependency set (pubspec and
                  // android/ are off-limits): same "PWR --" fallback the
                  // HTML shows when navigator.getBattery is unavailable.
                  Text('PWR --',
                      style: TextStyle(
                          color: color,
                          fontSize: 12,
                          letterSpacing: 2.2,
                          shadows: [
                            Shadow(color: color, blurRadius: 8),
                          ])),
                ],
              ),
            ],
          ),
        );
      },
    );
  }

  /// Title glitch (HTML `@keyframes gl` on a 7s loop): brief RGB-split
  /// shift at 94-98% of the cycle. Kept subtle.
  Widget _glitchTitle(Color color) {
    final phase = _tick % 7;
    final glitchA = phase >= 6.58 && phase < 6.72;
    final glitchB = phase >= 6.72 && phase < 6.86;
    final skew = phase >= 6.86;
    final shadows = <Shadow>[
      Shadow(color: color, blurRadius: 8),
      if (glitchA) ...[
        const Shadow(offset: Offset(-2, 0), color: Color(0xFFFF00FF)),
        const Shadow(offset: Offset(2, 0), color: Color(0xFF00FFFF)),
      ],
      if (glitchB) ...[
        const Shadow(offset: Offset(2, 0), color: Color(0xFFFF00FF)),
        const Shadow(offset: Offset(-2, 0), color: Color(0xFF00FFFF)),
      ],
    ];
    Widget title = Text('J.A.R.V.I.S',
        style: TextStyle(
            color: color,
            fontSize: 20,
            fontWeight: FontWeight.w600,
            letterSpacing: 6,
            shadows: shadows));
    if (glitchA) {
      title = Transform.translate(offset: const Offset(2, 0), child: title);
    } else if (glitchB) {
      title = Transform.translate(offset: const Offset(-2, 0), child: title);
    } else if (skew) {
      title = Transform(
          transform: Matrix4.skewX(0.14),
          alignment: Alignment.center,
          child: title);
    }
    return title;
  }

  // ---------- Reactor stage (HTML .stage) ----------

  Widget _stage() {
    return SizedBox(
      height: 330,
      child: Stack(
        children: [
          // FX layer honours the "粒子特效" setting (as the old
          // particle sphere did); the reactor itself always draws.
          if (_settings.particles)
            Positioned.fill(child: CustomPaint(painter: _fx)),
          Positioned.fill(child: CustomPaint(painter: _reactor)),
          Center(
            child: AnimatedBuilder(
              animation: _tickNotifier,
              builder: (_, _) => _statusText(),
            ),
          ),
          Positioned(
            left: 0,
            top: 0,
            child: AnimatedBuilder(
              animation: _tickNotifier,
              builder: (_, _) => _gearButton(),
            ),
          ),
          Positioned(
            left: 0,
            right: 0,
            bottom: 4,
            child: Center(
              child: AnimatedBuilder(
                animation: _tickNotifier,
                builder: (_, _) => _wakeChip(),
              ),
            ),
          ),
          Positioned(
            right: 0,
            top: 0,
            child: AnimatedBuilder(
              animation: _tickNotifier,
              builder: (_, _) => _readout(
                const ['POWER', 'LATENCY', 'SIGNAL'],
                _hudLevels.sublist(3),
                alignRight: true,
              ),
            ),
          ),
          Positioned(
            left: 0,
            bottom: 6,
            child: AnimatedBuilder(
              animation: _tickNotifier,
              builder: (_, _) => _readout(
                const ['ARC REACTOR', 'NEURAL LINK', 'VOICE CORE'],
                _hudLevels.sublist(0, 3),
                alignRight: false,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _statusText() {
    final color = hueColor(_reactor.smoothedHue, 1);
    final (en, cn) = switch (_state) {
      JarvisState.listening => ('LISTENING', '正在聆听'),
      JarvisState.thinking => ('PROCESSING', '思考中'),
      JarvisState.speaking => ('RESPONDING', '回应中'),
      _ => _wakeMode ? ('WAKE WORD', '等待唤醒') : ('STANDBY', '待命'),
    };
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(en,
            style: TextStyle(
                color: color,
                fontSize: 13,
                letterSpacing: 5.2,
                shadows: [Shadow(color: color, blurRadius: 10)])),
        const SizedBox(height: 6),
        Text(cn,
            style: TextStyle(
                color: const Color(0xFFEAFFFF),
                fontSize: 30,
                letterSpacing: 7.5,
                shadows: [Shadow(color: color, blurRadius: 12)])),
      ],
    );
  }

  Widget _gearButton() {
    final color = hueColor(_reactor.smoothedHue, 1);
    return GestureDetector(
      onTap: _openSettings,
      child: Container(
        width: 38,
        height: 38,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          border:
              Border.all(color: hueColor(_reactor.smoothedHue, 0.25)),
        ),
        child: Center(
          child: Text('⚙︎',
              style: TextStyle(color: color, fontSize: 18)),
        ),
      ),
    );
  }

  Widget _wakeChip() {
    final hue = _reactor.smoothedHue;
    final color = hueColor(hue, 1);
    final on = _wakeMode;
    return GestureDetector(
      onTap: _toggleWake,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
              color: on ? color : hueColor(hue, 0.25)),
          color: on ? hueColor(hue, 0.25) : Colors.transparent,
          boxShadow: on
              ? [
                  BoxShadow(
                      color: hueColor(hue, 0.25), blurRadius: 14),
                ]
              : null,
        ),
        child: Text('唤醒词 · ${on ? '开' : '关'}',
            style: TextStyle(
                color: color, fontSize: 13, letterSpacing: 2.6)),
      ),
    );
  }

  /// HUD readout bars (HTML `.rd`): label + glowing 2px bar.
  Widget _readout(List<String> labels, List<double> values,
      {required bool alignRight}) {
    final hue = _reactor.smoothedHue;
    final color = hueColor(hue, 1);
    return IgnorePointer(
      child: SizedBox(
        width: 92,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: alignRight
              ? CrossAxisAlignment.end
              : CrossAxisAlignment.start,
          children: [
            for (var i = 0; i < labels.length; i++) ...[
              Text(labels[i],
                  style: TextStyle(
                      color: color.withValues(alpha: 0.8),
                      fontSize: 9,
                      letterSpacing: 1.6,
                      shadows: [Shadow(color: color, blurRadius: 6)])),
              const SizedBox(height: 3),
              Container(
                height: 2,
                width: 92,
                color: hueColor(hue, 0.25),
                alignment: Alignment.centerLeft,
                child: Container(
                  width: 92 * values[i].clamp(0.0, 1.0),
                  color: color,
                  height: 2,
                ),
              ),
              const SizedBox(height: 7),
            ],
          ],
        ),
      ),
    );
  }

  List<Widget> _cornerBrackets() {
    const s = 22.0;
    Widget brk({
      double? left,
      double? top,
      double? right,
      double? bottom,
      required Border border,
    }) {
      return Positioned(
        left: left,
        top: top,
        right: right,
        bottom: bottom,
        child: AnimatedBuilder(
          animation: _tickNotifier,
          builder: (_, _) {
            final color =
                hueColor(_reactor.smoothedHue, 0.85);
            return Container(
              width: s,
              height: s,
              decoration: BoxDecoration(
                border: Border(
                  left: border.left.width == 0
                      ? BorderSide.none
                      : BorderSide(color: color, width: 2),
                  top: border.top.width == 0
                      ? BorderSide.none
                      : BorderSide(color: color, width: 2),
                  right: border.right.width == 0
                      ? BorderSide.none
                      : BorderSide(color: color, width: 2),
                  bottom: border.bottom.width == 0
                      ? BorderSide.none
                      : BorderSide(color: color, width: 2),
                ),
              ),
            );
          },
        ),
      );
    }

    const side = BorderSide(width: 2);
    const none = BorderSide.none;
    return [
      brk(
          left: 8,
          top: 6,
          border: const Border(left: side, top: side, right: none, bottom: none)),
      brk(
          right: 8,
          top: 6,
          border:
              const Border(left: none, top: side, right: side, bottom: none)),
      brk(
          left: 8,
          bottom: 8,
          border:
              const Border(left: side, top: none, right: none, bottom: side)),
      brk(
          right: 8,
          bottom: 8,
          border:
              const Border(left: none, top: none, right: side, bottom: side)),
    ];
  }

  // ---------- Dialogue (HTML .sub colours) ----------

  Widget _chatArea() {
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
          itemCount: _messages.length + (_state == JarvisState.thinking ? 1 : 0),
          itemBuilder: (context, i) {
            if (_state == JarvisState.thinking && i == _messages.length) {
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
            final user = m.role == 'user';
            return Align(
              alignment: user ? Alignment.centerRight : Alignment.centerLeft,
              child: Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // HTML .sub: user line dim, Jarvis reply bright + glow.
                    SelectableText(m.text,
                        style: TextStyle(
                            color: user
                                ? userDimColor
                                : const Color(0xFFEAFFFF),
                            height: 1.5,
                            fontSize: 14,
                            shadows: user
                                ? null
                                : const [
                                    Shadow(
                                        color: hudCyan, blurRadius: 12),
                                  ])),
                    if (!user)
                      Align(
                        alignment: Alignment.centerRight,
                        child: IconButton(
                          visualDensity: VisualDensity.compact,
                          tooltip: '朗读回答',
                          onPressed: () => _speak(m.text),
                          icon: const Icon(Icons.volume_up_outlined,
                              size: 19, color: hudCyan),
                        ),
                      ),
                  ],
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  // ---------- Bottom: mic + input (HTML .mic) ----------

  Widget _bottomArea() {
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
                        _recognized.isEmpty
                            ? '正在聆听，请开始说话…'
                            : _recognized,
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
                    border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(14),
                        borderSide:
                            BorderSide(color: hudCyan.withValues(alpha: 0.35))),
                    enabledBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(14),
                        borderSide:
                            BorderSide(color: hudCyan.withValues(alpha: 0.35))),
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
    return AnimatedBuilder(
      animation: _tickNotifier,
      builder: (_, _) {
        final hue = _reactor.smoothedHue;
        final color = hueColor(hue, 1);
        final listening = _state == JarvisState.listening;
        final ripplePhase = _tick % 1;
        return GestureDetector(
          onTap: _toggleMic,
          onTapDown: (_) => setState(() => _micDown = true),
          onTapUp: (_) => setState(() => _micDown = false),
          onTapCancel: () => setState(() => _micDown = false),
          child: Transform.scale(
            scale: _micDown ? 0.92 : 1.0,
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
                  Transform.rotate(
                    angle: _tick * math.pi * 2 / 8,
                    child: CustomPaint(
                      size: const Size(102, 102),
                      painter: _DashedRingPainter(
                          color: color.withValues(alpha: 0.6)),
                    ),
                  ),
                  // Gradient disc (HTML radial gradient + glow).
                  Container(
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
                  if (listening)
                    CustomPaint(
                      size: const Size(32, 32),
                      painter: _BarsIconPainter(
                          tick: _tick, color: color),
                    )
                  else
                    Icon(Icons.mic, size: 32, color: color),
                ],
              ),
            ),
          ),
        );
      },
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
    final p = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1;
    const n = 24;
    for (var i = 0; i < n; i++) {
      final a0 = i * math.pi * 2 / n;
      canvas.drawArc(Rect.fromCircle(center: c, radius: r), a0,
          math.pi * 2 / n * 0.5, false, p);
    }
  }

  @override
  bool shouldRepaint(covariant _DashedRingPainter old) =>
      old.color != color;
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
    for (var i = 0; i < n; i++) {
      final h =
          (0.3 + 0.7 * (math.sin(tick * 6 + i * 1.2)).abs()) * size.height;
      final rect = RRect.fromRectAndRadius(
          Rect.fromCenter(
              center: Offset(i * bw + bw / 2, size.height / 2),
              width: bw * 0.5,
              height: h),
          const Radius.circular(2));
      canvas.drawRRect(
          rect, Paint()..color = color..style = PaintingStyle.fill);
    }
  }

  @override
  bool shouldRepaint(covariant _BarsIconPainter old) =>
      old.tick != tick || old.color != color;
}
