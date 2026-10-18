import 'dart:async';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:speech_to_text/speech_to_text.dart';

import '../services/edge_tts_service.dart';
import '../services/llm_service.dart';
import '../services/local_commands.dart';
import '../services/settings_store.dart';
import 'settings_screen.dart';

enum CarState { idle, listening, thinking, speaking }

const _bg = Color(0xFF050811);
const _cyan = Color(0xFF8AB4F8);
const _aqua = Color(0xFF7FE7D2);
const _rose = Color(0xFFE8788A);
const _violet = Color(0xFFA98BE0);
const _ice = Color(0xFFF1F4FF);

/// 车机版主界面：横屏 Jarvis HUD。
/// 流程：点麦克风 → 嘀一声 → 系统语音识别 → 本地车机指令（秒回）
/// 或云端 DeepSeek 闲聊 → Edge 语音播报 + 文字显示。
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen>
    with SingleTickerProviderStateMixin {
  final _settings = CarSettings();
  final _stt = SpeechToText();
  final _player = AudioPlayer();

  CarState _state = CarState.idle;
  String _statusText = '待命';
  String _timeText = '';
  String _partial = '';
  String _reply = '你好，我是 Jarvis。点麦克风说话：导航、音乐、电话都行，闲聊也可以。';
  double _micLevel = 0;
  bool _sttReady = false;
  bool _sttInitTried = false;
  int _speakSeq = 0;
  Timer? _clock;
  late final AnimationController _anim;

  @override
  void initState() {
    super.initState();
    _anim = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 3),
    )..repeat();
    _settings.load().then((_) {
      if (mounted) setState(() {});
    });
    _tickClock();
    _clock = Timer.periodic(const Duration(seconds: 1), (_) => _tickClock());
    // 后台预热系统识别，首次点麦克风不用等。
    unawaited(_initStt());
  }

  @override
  void dispose() {
    _clock?.cancel();
    _anim.dispose();
    unawaited(_stt.cancel());
    unawaited(_player.dispose());
    super.dispose();
  }

  void _tickClock() {
    final now = DateTime.now();
    final s =
        '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}:${now.second.toString().padLeft(2, '0')}';
    if (s != _timeText && mounted) setState(() => _timeText = s);
  }

  void _set(CarState state, String status) {
    if (!mounted) return;
    setState(() {
      _state = state;
      _statusText = status;
    });
  }

  Future<void> _initStt() async {
    if (_sttInitTried) return;
    _sttInitTried = true;
    try {
      _sttReady = await _stt.initialize(
        onStatus: (_) {},
        onError: (_) {},
      );
    } catch (_) {
      _sttReady = false;
    }
    if (mounted) setState(() {});
  }

  /// 嘀一声：代码合成 880Hz 正弦短音，不用任何音频文件。
  Future<void> _beep() async {
    try {
      final bytes = _beepWav();
      await _player.stop();
      await _player.play(BytesSource(bytes));
    } catch (_) {}
  }

  static Uint8List _beepWav() {
    const sampleRate = 16000;
    const ms = 140;
    const freq = 880.0;
    final n = sampleRate * ms ~/ 1000;
    final pcm = BytesBuilder();
    for (var i = 0; i < n; i++) {
      final v = (math.sin(2 * math.pi * freq * i / sampleRate) * 0.5 * 32767)
          .toInt();
      pcm.add([v & 0xFF, (v >> 8) & 0xFF]);
    }
    final data = pcm.toBytes();
    final header = BytesBuilder();
    void tag(String s) => header.add(s.codeUnits);
    void u32(int v) => header.add([
          v & 0xFF,
          (v >> 8) & 0xFF,
          (v >> 16) & 0xFF,
          (v >> 24) & 0xFF,
        ]);
    void u16(int v) => header.add([v & 0xFF, (v >> 8) & 0xFF]);
    tag('RIFF');
    u32(36 + data.length);
    tag('WAVE');
    tag('fmt ');
    u32(16);
    u16(1);
    u16(1);
    u32(sampleRate);
    u32(sampleRate * 2);
    u16(2);
    u16(16);
    tag('data');
    u32(data.length);
    final out = BytesBuilder();
    out.add(header.toBytes());
    out.add(data);
    return out.toBytes();
  }

  Future<void> _onMicTap() async {
    if (_state == CarState.listening) {
      await _stt.stop();
      _set(CarState.idle, '待命');
      return;
    }
    if (_state == CarState.thinking || _state == CarState.speaking) return;
    await _initStt();
    if (!_sttReady) {
      _showAssistant('语音识别不可用：车机没有系统语音识别服务。');
      return;
    }
    await _beep();
    _set(CarState.listening, '正在聆听');
    _micLevel = 0;
    _partial = '';
    String? localeId;
    try {
      final locales = await _stt.locales();
      final zh = locales.where((l) => l.localeId.startsWith('zh')).toList();
      if (zh.isNotEmpty) localeId = zh.first.localeId;
    } catch (_) {}
    try {
      await _stt.listen(
        onResult: (result) {
          if (result.recognizedWords.isNotEmpty && mounted) {
            setState(() => _partial = result.recognizedWords);
          }
          if (result.finalResult) {
            final text = result.recognizedWords.trim();
            unawaited(_handleHeard(text));
          }
        },
        localeId: localeId,
        listenFor: const Duration(seconds: 30),
        pauseFor: const Duration(seconds: 3),
        partialResults: true,
        onSoundLevelChange: (level) {
          if (mounted) {
            setState(() {
              _micLevel = ((level + 20) / 20).clamp(0.0, 1.0);
            });
          }
        },
      );
    } catch (_) {
      _set(CarState.idle, '待命');
      _showAssistant('语音识别启动失败。');
    }
  }

  Future<void> _handleHeard(String text) async {
    if (!mounted) return;
    if (text.isEmpty) {
      _set(CarState.idle, '待命');
      return;
    }
    _set(CarState.thinking, '思考中');
    _partial = '';
    String reply;
    try {
      final local = await LocalCommands.tryHandle(text);
      if (local != null) {
        reply = local;
      } else if (!_settings.configured) {
        reply =
            '请先在设置里填写 DeepSeek Key，才能闲聊；车机指令（导航、音乐、电话、时间）不用 Key 也能用。';
      } else {
        reply = await LlmService.ask(
          settings: _settings,
          question: text,
          history: const [],
        );
      }
    } catch (e) {
      reply = '连接失败：$e';
    }
    if (!mounted) return;
    _showAssistant(reply);
    await _speak(reply);
    if (mounted) _set(CarState.idle, '待命');
  }

  void _showAssistant(String text) {
    if (!mounted) return;
    setState(() => _reply = text);
  }

  Future<void> _speak(String text) async {
    final clean = text.trim();
    if (clean.isEmpty || !_settings.speakReplies) return;
    final seq = ++_speakSeq;
    _set(CarState.speaking, '回应中');
    try {
      await _player.stop();
      final short =
          clean.length > 600 ? '${clean.substring(0, 600)}…' : clean;
      // Edge 联网语音：无网络时静默，只显示文字，不崩流程。
      final mp3 = await EdgeTtsService.synthesize(
        short,
        voice: _settings.edgeVoice,
        rate: _settings.rate,
        pitch: _settings.pitch,
      );
      if (seq != _speakSeq || !mounted) return;
      final done = _player.onPlayerComplete.first;
      await _player.play(BytesSource(mp3));
      await done.timeout(Duration(seconds: 20 + short.length ~/ 2));
    } catch (_) {
      try {
        await _player.stop();
      } catch (_) {}
    }
  }

  Future<void> _openSettings() async {
    final changed = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) => SettingsScreen(settings: _settings),
      ),
    );
    if (changed == true) {
      await _settings.load();
      if (mounted) setState(() {});
    }
  }

  Color get _themeColor => switch (_state) {
        CarState.thinking => _violet,
        CarState.speaking => _aqua,
        CarState.listening => _rose,
        CarState.idle => _cyan,
      };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _bg,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
          child: Column(
            children: [
              _topBar(),
              const SizedBox(height: 8),
              Expanded(
                child: Row(
                  children: [
                    Expanded(flex: 5, child: _reactorStage()),
                    const SizedBox(width: 16),
                    Expanded(flex: 6, child: _replyPanel()),
                  ],
                ),
              ),
              const SizedBox(height: 8),
              _bottomBar(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _topBar() {
    return Row(
      children: [
        const Text(
          'J.A.R.V.I.S',
          style: TextStyle(
            color: _ice,
            fontSize: 20,
            fontWeight: FontWeight.bold,
            letterSpacing: 3,
          ),
        ),
        const SizedBox(width: 12),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
          decoration: BoxDecoration(
            border: Border.all(color: _themeColor),
            borderRadius: BorderRadius.circular(12),
          ),
          child: Text(
            _statusText,
            style: TextStyle(color: _themeColor, fontSize: 13),
          ),
        ),
        const Spacer(),
        Text(
          _timeText,
          style: const TextStyle(color: _ice, fontSize: 18),
        ),
        const SizedBox(width: 12),
        IconButton(
          onPressed: _openSettings,
          icon: const Icon(Icons.settings, color: _ice, size: 26),
          tooltip: '设置',
        ),
      ],
    );
  }

  Widget _reactorStage() {
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Expanded(
          child: AnimatedBuilder(
            animation: _anim,
            builder: (_, _) => CustomPaint(
              painter: _ReactorPainter(
                progress: _anim.value,
                level: _state == CarState.listening ? _micLevel : 0,
                color: _themeColor,
                listening: _state == CarState.listening,
              ),
              child: Center(
                child: Text(
                  switch (_state) {
                    CarState.listening => '聆听',
                    CarState.thinking => '思考',
                    CarState.speaking => '回应',
                    CarState.idle => 'JARVIS',
                  },
                  style: const TextStyle(
                    color: _ice,
                    fontSize: 22,
                    fontWeight: FontWeight.bold,
                    letterSpacing: 4,
                  ),
                ),
              ),
            ),
          ),
        ),
        const SizedBox(height: 8),
        SizedBox(
          width: double.infinity,
          height: 56,
          child: ElevatedButton.icon(
            onPressed: _onMicTap,
            style: ElevatedButton.styleFrom(
              backgroundColor: _state == CarState.listening
                  ? const Color(0xFF7B2424)
                  : _themeColor.withValues(alpha: 0.25),
              foregroundColor: _ice,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(28),
                side: BorderSide(color: _themeColor),
              ),
            ),
            icon: Icon(
              _state == CarState.listening ? Icons.stop : Icons.mic,
              size: 28,
            ),
            label: Text(
              _state == CarState.listening ? '点按停止' : '点按说话',
              style: const TextStyle(fontSize: 18),
            ),
          ),
        ),
        const SizedBox(height: 6),
        Text(
          _partial.isEmpty ? '点“点按说话”，嘀一声后开始说' : _partial,
          style: TextStyle(
            color: _partial.isEmpty ? Colors.grey : _ice,
            fontSize: 14,
          ),
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          textAlign: TextAlign.center,
        ),
      ],
    );
  }

  Widget _replyPanel() {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.04),
        border: Border.all(color: _themeColor.withValues(alpha: 0.5)),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.smart_toy, color: _themeColor, size: 20),
              const SizedBox(width: 8),
              const Text(
                'JARVIS',
                style: TextStyle(
                  color: _ice,
                  fontSize: 15,
                  fontWeight: FontWeight.bold,
                  letterSpacing: 2,
                ),
              ),
              const Spacer(),
              if (_state == CarState.thinking)
                const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
            ],
          ),
          const SizedBox(height: 12),
          Expanded(
            child: SingleChildScrollView(
              child: Text(
                _reply,
                style: const TextStyle(
                  color: _ice,
                  fontSize: 20,
                  height: 1.5,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _bottomBar() {
    return Text(
      '说“导航去火车站”“放音乐”“打电话”“现在几点”试试 · 闲聊需在设置里填 DeepSeek Key',
      style: TextStyle(color: Colors.grey.shade500, fontSize: 12),
      textAlign: TextAlign.center,
    );
  }
}

/// 简化版反应堆圆环：霓虹外环 + 刻度 + 旋转弧 + 呼吸内圈 + 声波条。
/// 纯代码绘制，无图片资源，低配车机也轻。
class _ReactorPainter extends CustomPainter {
  final double progress;
  final double level;
  final Color color;
  final bool listening;

  const _ReactorPainter({
    required this.progress,
    required this.level,
    required this.color,
    required this.listening,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final r = math.min(size.width, size.height) * 0.42;
    // 呼吸：半径随时间微胀微缩。
    final breathe = 1 + 0.02 * math.sin(progress * math.pi * 2);
    final rr = r * breathe;

    // 霓虹外环
    final ring = Paint()
      ..color = color.withValues(alpha: 0.8)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3;
    canvas.drawCircle(center, rr * 1.15, ring);
    final ring2 = Paint()
      ..color = color.withValues(alpha: 0.25)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1;
    canvas.drawCircle(center, rr * 1.22, ring2);

    // 刻度
    final tick = Paint()
      ..color = color.withValues(alpha: 0.6)
      ..strokeWidth = 2;
    for (var i = 0; i < 60; i++) {
      final a = i * math.pi * 2 / 60;
      final long = i % 5 == 0;
      final p1 = center + Offset(math.cos(a), math.sin(a)) * rr * 1.05;
      final p2 = center +
          Offset(math.cos(a), math.sin(a)) * rr * (long ? 1.12 : 1.09);
      canvas.drawLine(p1, p2, tick);
    }

    // 旋转弧
    final arc = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 5
      ..strokeCap = StrokeCap.round;
    canvas.drawArc(
      Rect.fromCircle(center: center, radius: rr),
      progress * math.pi * 2,
      math.pi * 0.9,
      false,
      arc,
    );
    canvas.drawArc(
      Rect.fromCircle(center: center, radius: rr * 0.82),
      -progress * math.pi * 2,
      math.pi * 0.6,
      false,
      arc,
    );

    // 内圈渐变填充
    final fill = Paint()
      ..shader = RadialGradient(
        colors: [color.withValues(alpha: 0.35), Colors.transparent],
      ).createShader(Rect.fromCircle(center: center, radius: rr * 0.8));
    canvas.drawCircle(center, rr * 0.8, fill);

    // 声波条：聆听时随音量跳动，否则平静微动。
    final bars = Paint()
      ..color = color.withValues(alpha: 0.9)
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.round;
    for (var i = 0; i < 24; i++) {
      final a = i * math.pi * 2 / 24;
      final wave = listening
          ? level * 22 * (0.5 + 0.5 * math.sin(progress * math.pi * 4 + i))
          : 3 + 2 * math.sin(progress * math.pi * 2 + i);
      final p1 = center + Offset(math.cos(a), math.sin(a)) * rr * 0.92;
      final p2 = center +
          Offset(math.cos(a), math.sin(a)) * (rr * 0.92 + wave);
      canvas.drawLine(p1, p2, bars);
    }
  }

  @override
  bool shouldRepaint(covariant _ReactorPainter old) =>
      progress != old.progress ||
      level != old.level ||
      color != old.color ||
      listening != old.listening;
}
