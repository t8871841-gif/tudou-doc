import 'dart:async';
import 'dart:math' as math;

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:speech_to_text/speech_to_text.dart' as stt;

import '../services/action_service.dart';
import '../services/edge_tts_service.dart';
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
  double _boot = 0;
  double _tick = 0;
  Timer? _ticker;
  Timer? _streamTimer;
  bool _speechInit = false;
  bool _wakeMode = false;
  bool _muted = false;
  String _recognized = '';
  String _statusLine = '正在初始化核心…';
  final List<_Msg> _messages = [];
  final List<String> _dataStream = [];
  final _spherePoints = makeSpherePoints(280, 42);
  final _rnd = math.Random(7);

  @override
  void initState() {
    super.initState();
    _ticker = Timer.periodic(const Duration(milliseconds: 50), (_) {
      if (!mounted) return;
      setState(() {
        _tick += 0.05;
        if (_state == JarvisState.booting) {
          _boot += 0.05 / 2.4;
          if (_boot >= 1) {
            _boot = 1;
            _state = JarvisState.idle;
            _statusLine = '核心已上线，随时可以开始。';
            _messages.add(const _Msg('assistant',
                '您好，我是 Jarvis。您可以直接说话、点麦克风，或者打字提问。我可以回答各种各样的问题，还能打电话、发消息、设闹钟、开 App。')); 
          } else if (_boot > 0.7) {
            _statusLine = '神经链接建立中…';
          } else if (_boot > 0.35) {
            _statusLine = '全息面板展开中…';
          }
        }
      });
    });
    _streamTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      if (!mounted) return;
      final now = DateTime.now();
      final hh = now.hour.toString().padLeft(2, '0');
      final mm = now.minute.toString().padLeft(2, '0');
      final ss = now.second.toString().padLeft(2, '0');
      final lines = [
        '[$hh:$mm:$ss] 神经链接 稳定 · 延迟 ${_rnd.nextInt(30) + 18}ms',
        '[$hh:$mm:$ss] 全息渲染 60fps · 粒子 ${_rnd.nextInt(400) + 7600}',
        '[$hh:$mm:$ss] 语音链路 就绪 · 噪声抑制 开',
        '[$hh:$mm:$ss] 对话轮次 ${_messages.length} · 内存 正常',
      ];
      setState(() {
        _dataStream.insert(0, lines[_rnd.nextInt(lines.length)]);
        if (_dataStream.length > 6) _dataStream.removeLast();
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
    _ticker?.cancel();
    _streamTimer?.cancel();
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
      final mp3 = await EdgeTtsService.synthesize(
        clean.length > 1200 ? '${clean.substring(0, 1200)}…' : clean,
        voice: _settings.edgeVoice,
        rate: _settings.rate,
        pitch: _settings.pitch,
      );
      await _player.play(BytesSource(mp3));
      // Estimate speaking duration; fall back to idle when done.
      await _player.onPlayerComplete.first
          .timeout(Duration(seconds: (clean.length / 8).clamp(3, 30).toInt()));
    } catch (_) {
      try {
        await _tts.setLanguage('zh-CN');
        await _tts.setSpeechRate(_settings.rate);
        await _tts.setPitch(_settings.pitch);
        await _tts.speak(clean);
      } catch (_) {}
    } finally {
      if (mounted && _state == JarvisState.speaking) {
        setState(() => _state = JarvisState.idle);
      }
    }
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

  /// Standby: listen only for the wake word “贾维斯”.
  Future<void> _startWakeListening() async {
    if (_muted) return;
    if (!await _ensureSpeech()) return;
    _wakeMode = true;
    try {
      await _speech.listen(
        localeId: 'zh_CN',
        listenFor: const Duration(seconds: 30),
        pauseFor: const Duration(seconds: 3),
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
        localeId: 'zh_CN',
        listenFor: const Duration(seconds: 60),
        pauseFor: const Duration(seconds: 4),
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
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(m)));
  }

  Future<void> _openSettings() async {
    await Navigator.push(
        context, MaterialPageRoute(builder: (_) => SettingsScreen(settings: _settings)));
    await _settings.load();
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    if (_state == JarvisState.booting) return _bootScreen();
    final w = MediaQuery.of(context).size.width;
    final wide = w > 640;
    return Scaffold(
      backgroundColor: hudDeep,
      body: Container(
        decoration: const BoxDecoration(
          gradient: RadialGradient(
            center: Alignment.topCenter,
            radius: 1.4,
            colors: [Color(0xFF06233A), hudDeep],
          ),
        ),
        child: SafeArea(
          child: Column(
            children: [
              _topBar(),
              Expanded(
                child: wide
                    ? Row(
                        children: [
                          Expanded(child: _leftPanel()),
                          Expanded(flex: 2, child: _centerColumn()),
                          Expanded(child: _rightPanel()),
                        ],
                      )
                    : ListView(
                        children: [
                          SizedBox(height: 300, child: _centerColumn()),
                          _leftPanel(),
                          _rightPanel(),
                        ],
                      ),
              ),
              _chatArea(),
              _inputBar(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _bootScreen() {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            SizedBox(
              width: 220,
              height: 220,
              child: CustomPaint(
                  painter: ReactorPainter(
                      progress: _boot, tick: _tick, thinking: true)),
            ),
            const SizedBox(height: 24),
            const Text('JARVIS',
                style: TextStyle(
                    color: hudCyan,
                    fontSize: 34,
                    fontWeight: FontWeight.w900,
                    letterSpacing: 10)),
            const SizedBox(height: 8),
            Text('核心启动中  ${(_boot * 100).toInt()}%',
                style: const TextStyle(color: Colors.white70)),
            const SizedBox(height: 12),
            Text(_statusLine,
                style: const TextStyle(color: hudCyan, fontSize: 13)),
            const SizedBox(height: 20),
            const SizedBox(
                width: 200,
                child: LinearProgressIndicator(
                    color: hudCyan, backgroundColor: Colors.white12)),
          ],
        ),
      ),
    );
  }

  Widget _topBar() {
    final now = DateTime.now();
    final time =
        '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}';
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
      child: Row(
        children: [
          const Icon(Icons.hub_outlined, color: hudCyan, size: 22),
          const SizedBox(width: 8),
          const Text('JARVIS',
              style: TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.w900,
                  letterSpacing: 4,
                  fontSize: 18)),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
            decoration: BoxDecoration(
                border: Border.all(color: hudCyan.withOpacity(0.5)),
                borderRadius: BorderRadius.circular(10)),
            child: Text(
                _muted
                    ? '已静音'
                    : _state == JarvisState.listening
                        ? '聆听中'
                        : _state == JarvisState.thinking
                            ? '思考中'
                            : _state == JarvisState.speaking
                                ? '播报中'
                                : '待命',
                style: const TextStyle(color: hudCyan, fontSize: 11)),
          ),
          const Spacer(),
          Text(time,
              style: const TextStyle(color: Colors.white70, fontSize: 14)),
          IconButton(
              tooltip: '设置',
              onPressed: _openSettings,
              icon: const Icon(Icons.tune, color: hudCyan)),
        ],
      ),
    );
  }

  Widget _hudBox({required String title, required Widget child}) {
    return Container(
      margin: const EdgeInsets.all(8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFF041525).withOpacity(0.82),
        border: Border.all(color: hudCyan.withOpacity(0.35)),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            const Icon(Icons.crop_free, color: hudCyan, size: 14),
            const SizedBox(width: 6),
            Text(title,
                style: const TextStyle(
                    color: hudCyan,
                    fontWeight: FontWeight.bold,
                    letterSpacing: 2,
                    fontSize: 12)),
          ]),
          const SizedBox(height: 10),
          child,
        ],
      ),
    );
  }

  Widget _leftPanel() {
    return _hudBox(
      title: '系统状态',
      child: Column(
        children: [
          _meter('核心', 0.92),
          _meter('神经链接', 0.86),
          _meter('语音链路', _muted ? 0.0 : 0.78),
          _meter('全息渲染', 0.94),
          const SizedBox(height: 8),
          _kv('语音', _muted ? '已静音' : 'Edge 男声'),
          _kv('语速', _settings.rate.toStringAsFixed(2)),
          _kv('模型', _settings.model),
          _kv('Key', _settings.configured ? '已填写' : '未填写，去设置'),
        ],
      ),
    );
  }

  Widget _rightPanel() {
    return _hudBox(
      title: '数据流',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('界面模拟遥测，非真实设备传感器数据。',
              style: TextStyle(color: Colors.white38, fontSize: 10)),
          const SizedBox(height: 8),
          ..._dataStream.map((l) => Padding(
                padding: const EdgeInsets.only(bottom: 5),
                child: Text(l,
                    style:
                        const TextStyle(color: Colors.white70, fontSize: 11)),
              )),
          if (_dataStream.isEmpty)
            const Text('数据流初始化中…',
                style: TextStyle(color: Colors.white70, fontSize: 11)),
        ],
      ),
    );
  }

  Widget _meter(String label, double v) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 7),
      child: Row(children: [
        SizedBox(
            width: 64,
            child: Text(label,
                style: const TextStyle(color: Colors.white70, fontSize: 12))),
        Expanded(
            child: LinearProgressIndicator(
                value: v.clamp(0.0, 1.0),
                color: hudCyan,
                backgroundColor: Colors.white12)),
        const SizedBox(width: 8),
        Text('${(v * 100).toInt()}%',
            style: const TextStyle(color: hudCyan, fontSize: 11)),
      ]),
    );
  }

  Widget _kv(String k, String v) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Row(children: [
        Text('$k：', style: const TextStyle(color: Colors.white38, fontSize: 12)),
        Expanded(
            child: Text(v,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: Colors.white, fontSize: 12))),
      ]),
    );
  }

  Widget _centerColumn() {
    final thinking = _state == JarvisState.thinking;
    final speaking = _state == JarvisState.speaking;
    final listening = _state == JarvisState.listening;
    return Stack(
      children: [
        Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            SizedBox(
              height: 210,
              child: Stack(
                alignment: Alignment.center,
                children: [
                  if (_settings.particles)
                    CustomPaint(
                        size: const Size(300, 210),
                        painter: ParticleSpherePainter(
                            tick: _tick,
                            active: listening || speaking,
                            points: _spherePoints)),
                  SizedBox(
                    width: 170,
                    height: 170,
                    child: CustomPaint(
                        painter: ReactorPainter(
                            progress: 1,
                            tick: _tick,
                            speaking: speaking || listening,
                            thinking: thinking)),
                  ),
                  if (thinking)
                    const Positioned(
                        bottom: 0,
                        child: Text('思考中…',
                            style: TextStyle(color: hudCyan, fontSize: 12))),
                ],
              ),
            ),
            const SizedBox(height: 6),
            SizedBox(
              height: 36,
              width: 300,
              child: CustomPaint(
                  painter: WaveformPainter(
                      tick: _tick,
                      amplitude: speaking
                          ? 0.9
                          : listening
                              ? 0.7
                              : thinking
                                  ? 0.45
                                  : 0.12)),
            ),
            Text(_statusLine,
                style: const TextStyle(color: Colors.white70, fontSize: 12)),
            const SizedBox(height: 6),
            Wrap(
              spacing: 8,
              alignment: WrapAlignment.center,
              children: [
                _chip('说“贾维斯”唤醒', () => _startWakeListening()),
                _chip(_muted ? '取消静音' : '静音', _toggleMute),
                _chip(_settings.particles ? '关粒子' : '开粒子', () {
                  setState(() => _settings.particles = !_settings.particles);
                  _settings.save();
                }),
              ],
            ),
          ],
        ),
        if (thinking)
          Positioned.fill(
            child: IgnorePointer(
              child: CustomPaint(
                  painter: _ScanPainter(_tick % 1)),
            ),
          ),
      ],
    );
  }

  Widget _chip(String label, VoidCallback onTap) {
    return ActionChip(
      label: Text(label, style: const TextStyle(color: hudCyan, fontSize: 12)),
      backgroundColor: const Color(0xFF06233A),
      side: BorderSide(color: hudCyan.withOpacity(0.4)),
      onPressed: onTap,
    );
  }

  Widget _chatArea() {
    return Expanded(
      flex: 2,
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 10),
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: Colors.black.withOpacity(0.32),
          border: Border.all(color: hudCyan.withOpacity(0.22)),
          borderRadius: BorderRadius.circular(12),
        ),
        child: ListView.builder(
          controller: _scroll,
          itemCount: _messages.length + (_state == JarvisState.thinking ? 1 : 0),
          itemBuilder: (context, i) {
            if (_state == JarvisState.thinking && i == _messages.length) {
              return const Align(
                alignment: Alignment.centerLeft,
                child: Padding(
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
                ),
              );
            }
            final m = _messages[i];
            final user = m.role == 'user';
            return Align(
              alignment: user ? Alignment.centerRight : Alignment.centerLeft,
              child: Container(
                constraints: BoxConstraints(
                    maxWidth: MediaQuery.of(context).size.width * 0.82),
                margin: const EdgeInsets.only(bottom: 10),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: user
                      ? const Color(0xFF0A3A52)
                      : const Color(0xFF0B2236),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: hudCyan.withOpacity(0.25)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(user ? '你' : 'Jarvis',
                        style: const TextStyle(
                            color: hudCyan,
                            fontWeight: FontWeight.bold,
                            fontSize: 12)),
                    const SizedBox(height: 4),
                    SelectableText(m.text,
                        style: const TextStyle(
                            color: Colors.white, height: 1.5, fontSize: 14)),
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

  Widget _inputBar() {
    return SafeArea(
      top: false,
      child: Container(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
        decoration: BoxDecoration(
            color: const Color(0xFF041525),
            border: Border(top: BorderSide(color: hudCyan.withOpacity(0.25)))),
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
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                IconButton.filled(
                  onPressed: _toggleMic,
                  style: IconButton.styleFrom(
                      backgroundColor: _state == JarvisState.listening
                          ? const Color(0xFF7B2424)
                          : const Color(0xFF0A3A52),
                      foregroundColor: hudCyan),
                  icon: Icon(
                      _state == JarvisState.listening ? Icons.stop : Icons.mic,
                      size: 26),
                ),
                const SizedBox(width: 8),
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
                      fillColor: Colors.black.withOpacity(0.35),
                      border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(14),
                          borderSide:
                              BorderSide(color: hudCyan.withOpacity(0.35))),
                      enabledBorder: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(14),
                          borderSide:
                              BorderSide(color: hudCyan.withOpacity(0.35))),
                      contentPadding: const EdgeInsets.symmetric(
                          horizontal: 13, vertical: 12),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  onPressed: _state == JarvisState.thinking ? null : () => _send(),
                  style: IconButton.styleFrom(
                      backgroundColor: hudCyan, foregroundColor: Colors.black),
                  icon: const Icon(Icons.arrow_upward),
                ),
              ],
            ),
            const SizedBox(height: 6),
            const Text('语音识别由系统服务处理；联网问答需在设置里填写 Key。',
                style: TextStyle(color: Colors.white38, fontSize: 10)),
          ],
        ),
      ),
    );
  }
}

class _ScanPainter extends CustomPainter {
  final double t;
  _ScanPainter(this.t);
  @override
  void paint(Canvas canvas, Size size) {
    final y = size.height * t;
    final p = Paint()
      ..color = hudCyan.withOpacity(0.5)
      ..strokeWidth = 2;
    canvas.drawLine(Offset(0, y), Offset(size.width, y), p);
  }

  @override
  bool shouldRepaint(covariant _ScanPainter old) => old.t != t;
}
