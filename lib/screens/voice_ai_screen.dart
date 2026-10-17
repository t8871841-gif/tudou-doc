import 'dart:async';
import 'dart:convert';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;

import '../services/asr_service.dart';
import '../services/edge_tts_service.dart';

/// Voice-first Chinese medicine reference assistant.
/// API credentials are stored in Android Keystore-backed secure storage.
class VoiceAiScreen extends StatefulWidget {
  const VoiceAiScreen({super.key});

  @override
  State<VoiceAiScreen> createState() => _VoiceAiScreenState();
}

class _VoiceAiScreenState extends State<VoiceAiScreen> {
  static const _gold = Color(0xFFE2C36B);
  static const _storage = FlutterSecureStorage();
  static const _knowledgeAssets = <String>[
    'assets/SKILL.md',
    'assets/offline_knowledge/skill_main.md',
    'assets/offline_knowledge/case_00_merged_table.md',
    'assets/offline_knowledge/case_01_cancer.md',
    'assets/offline_knowledge/case_02_cardiovascular.md',
    'assets/offline_knowledge/case_03_metabolic.md',
    'assets/offline_knowledge/case_04_autoimmune.md',
    'assets/offline_knowledge/case_05_neurological.md',
    'assets/offline_knowledge/case_06_other.md',
    'assets/offline_knowledge/case_distilled_cases.md',
    'assets/offline_knowledge/case_expression_style.md',
    'assets/offline_knowledge/module_01_shanghan_sun.md',
    'assets/offline_knowledge/module_02_shanghan_other.md',
    'assets/offline_knowledge/module_03_yian.md',
    'assets/offline_knowledge/module_04_jingui.md',
    'assets/offline_knowledge/module_05_huangdi_neijing.md',
    'assets/offline_knowledge/module_06_liangdong.md',
    'assets/offline_knowledge/module_07_bimen_hantang.md',
    'assets/offline_knowledge/module_08_huangdi_detail.md',
    'assets/offline_knowledge/module_09_zhenjiu_bencao.md',
    'assets/offline_knowledge/module_10_fuyang_luntan.md',
    'assets/offline_knowledge/module_11_zhongjing_xinfa.md',
    'assets/offline_knowledge/module_12_stanford_jingfang.md',
    'assets/offline_knowledge/module_13_shanghan_quebing.md',
    'assets/offline_knowledge/module_14_yijinjing_bidu.md',
  ];
  final _input = TextEditingController();
  final _scroll = ScrollController();
  final _asr = AsrService();
  final _player = AudioPlayer();
  String _edgeVoice = EdgeTtsService.defaultVoice;
  final List<_VoiceMessage> _messages = [
    _VoiceMessage(
      role: 'assistant',
      text: '欢迎来到汉方唐鉴语音助手。你可以点击麦克风提问，也可以输入文字。\n\n首次使用联网问答，请先在右上角设置兼容 OpenAI API 的服务地址、模型名称和 API Key。离线典籍检索无需配置。',
    ),
  ];
  bool _listening = false;
  bool _busy = false;
  bool _speakReplies = true;

  /// 主题自适应：深色模式保持黑金，浅色模式使用浅白系
  bool get _isDark => Theme.of(context).brightness == Brightness.dark;
  Color get _cardBg => _isDark ? const Color(0xFF171611) : Colors.white;
  Color get _userBubbleBg => _isDark ? const Color(0xFF3D3217) : const Color(0xFFF5EAD3);
  Color get _barBg => _isDark ? const Color(0xFF11110D) : const Color(0xFFFAF6EE);
  Color get _border => _isDark ? const Color(0xFF494126) : const Color(0xFFE2D6BE);
  Color get _userBorder => _isDark ? const Color(0xFF8D7C43) : const Color(0xFFD4C49A);
  Color get _mutedText => _isDark ? Colors.white54 : Colors.black54;
  Color get _faintText => _isDark ? Colors.white38 : Colors.black38;
  /// 金色文字在浅色下的可读替代（深金棕）
  Color get _accentText => _isDark ? _gold : const Color(0xFF8B6914);
  String _recognized = '';
  int _emptyEndpoints = 0;
  Timer? _listenTimer;
  String _offlineKnowledge = '';
  String _baseUrl = 'https://apihub.agnes-ai.com/v1';
  String _model = 'agnes-3.0-flash';
  double _ttsRate = 0.48;
  double _ttsPitch = 1.0;

  @override
  void initState() {
    super.initState();
    _loadKnowledge();
    _loadConfig();
    _loadVoiceConfig();
    _warmUpAsr();
  }

  /// 加载已保存的语音设置（Edge 音色 / 语速 / 音调）。
  Future<void> _loadVoiceConfig() async {
    _edgeVoice = await _storage.read(key: 'voice_ai_edge_voice') ?? EdgeTtsService.defaultVoice;
    _ttsRate = double.tryParse(await _storage.read(key: 'voice_ai_tts_rate') ?? '') ?? 0.48;
    _ttsPitch = double.tryParse(await _storage.read(key: 'voice_ai_tts_pitch') ?? '') ?? 1.0;
    if (mounted) setState(() {});
  }

  /// 后台预加载离线识别模型，首次点击麦克风不用等（Jarvis 同款做法）。
  Future<void> _warmUpAsr() async {
    try {
      await _asr.init();
    } catch (e) {
      _notice('离线语音识别模型加载失败：$e');
    }
  }

  /// 播报入口：Edge 神经网络语音（Jarvis 同款，无系统语音回落）。
  /// Edge 失败时提示一次（与 Jarvis 静默吞错不同：汉方已有失败提示习惯）。
  Future<void> _speak(String text) async {
    final clean = text.trim();
    if (clean.isEmpty) return;
    try {
      await _player.stop();
      final mp3 = await EdgeTtsService.synthesize(
        clean,
        voice: _edgeVoice,
        rate: _ttsRate,
        pitch: _ttsPitch,
      );
      await _player.play(BytesSource(mp3));
    } catch (_) {
      _notice('Edge 语音失败，请检查网络');
    }
  }

  Future<void> _loadKnowledge() async {
    try {
      final parts = <String>[];
      for (final asset in _knowledgeAssets) {
        try {
          parts.add(await rootBundle.loadString(asset));
        } catch (_) {
          // Optional knowledge files are skipped; the main skill remains usable.
        }
      }
      if (mounted) setState(() => _offlineKnowledge = parts.join('\n\n'));
    } catch (_) {
      if (mounted) setState(() => _offlineKnowledge = '本地典籍索引暂不可用。');
    }
  }

  Future<void> _loadConfig() async {
    _baseUrl = await _storage.read(key: 'voice_ai_base_url') ?? _baseUrl;
    _model = await _storage.read(key: 'voice_ai_model') ?? _model;
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _input.dispose();
    _scroll.dispose();
    _listenTimer?.cancel();
    _asr.dispose();
    _player.dispose();
    super.dispose();
  }

  Future<void> _configure() async {
    final base = TextEditingController(text: _baseUrl);
    final model = TextEditingController(text: _model);
    final key = TextEditingController(
      text: await _storage.read(key: 'voice_ai_api_key') ?? '',
    );
    String edgeVoice = _edgeVoice;
    double rate = _ttsRate;
    double pitch = _ttsPitch;
    if (!mounted) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) {
          return AlertDialog(
            backgroundColor: _cardBg,
            title: Text('模型服务设置', style: TextStyle(color: _accentText)),
            content: SingleChildScrollView(
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                TextField(controller: base, decoration: const InputDecoration(labelText: 'API Base URL', hintText: 'https://apihub.agnes-ai.com/v1')),
                const SizedBox(height: 10),
                TextField(controller: model, decoration: const InputDecoration(labelText: '模型名称', hintText: 'agnes-3.0-flash')),
                const SizedBox(height: 10),
                TextField(controller: key, obscureText: true, decoration: const InputDecoration(labelText: 'API Key', helperText: '仅保存在本机安全存储中，不要提交到代码仓库。')),
                const Divider(height: 28),
                Align(
                  alignment: Alignment.centerLeft,
                  child: Text('语音播报设置', style: TextStyle(color: _accentText, fontWeight: FontWeight.bold, fontSize: 15)),
                ),
                const SizedBox(height: 8),
                // 播报引擎：Edge 神经网络语音（Jarvis 同款，无系统语音选项）。
                DropdownButtonFormField<String>(
                  value: EdgeTtsService.voices.any((v) => v['id'] == edgeVoice) ? edgeVoice : EdgeTtsService.defaultVoice,
                  decoration: const InputDecoration(labelText: '播报音色', hintText: '云扬'),
                  isExpanded: true,
                  items: EdgeTtsService.voices
                      .map((v) => DropdownMenuItem<String>(
                            value: v['id'],
                            child: Text(v['label']!, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 13)),
                          ))
                      .toList(),
                  onChanged: (nv) => setDialogState(() => edgeVoice = nv ?? EdgeTtsService.defaultVoice),
                ),
                const SizedBox(height: 6),
                Row(children: [
                  const SizedBox(width: 72, child: Text('语速', style: TextStyle(fontSize: 13))),
                  Expanded(
                    child: Slider(value: rate, min: 0.1, max: 1.0, divisions: 18, label: rate.toStringAsFixed(2), onChanged: (nv) => setDialogState(() => rate = nv)),
                  ),
                ]),
                Row(children: [
                  const SizedBox(width: 72, child: Text('音调', style: TextStyle(fontSize: 13))),
                  Expanded(
                    child: Slider(value: pitch, min: 0.5, max: 2.0, divisions: 15, label: pitch.toStringAsFixed(2), onChanged: (nv) => setDialogState(() => pitch = nv)),
                  ),
                ]),
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton(
                    onPressed: () async {
                      // 试听：按对话框当前选择试播（Edge 神经网络语音，Jarvis 同款）。
                      try {
                        final mp3 = await EdgeTtsService.synthesize(
                          '你好，我是汉方唐鉴语音助手。',
                          voice: edgeVoice,
                          rate: rate,
                          pitch: pitch,
                        );
                        await _player.stop();
                        await _player.play(BytesSource(mp3));
                      } catch (e) {
                        _notice('Edge 语音试听失败：$e');
                      }
                    },
                    child: const Text('试听语音'),
                  ),
                ),
              ]),
            ),
            actions: [
              TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('取消')),
              FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('保存')),
            ],
          );
        },
      ),
    );
    if (ok == true) {
      final normalizedBase = base.text.trim().replaceFirst(RegExp(r'/+$'), '');
      await _storage.write(key: 'voice_ai_base_url', value: normalizedBase.isEmpty ? _baseUrl : normalizedBase);
      await _storage.write(key: 'voice_ai_model', value: model.text.trim().isEmpty ? _model : model.text.trim());
      if (key.text.trim().isNotEmpty) {
        await _storage.write(key: 'voice_ai_api_key', value: key.text.trim());
      } else {
        await _storage.delete(key: 'voice_ai_api_key');
      }
      _edgeVoice = edgeVoice;
      _ttsRate = rate;
      _ttsPitch = pitch;
      await _storage.write(key: 'voice_ai_edge_voice', value: edgeVoice);
      await _storage.write(key: 'voice_ai_tts_rate', value: rate.toString());
      await _storage.write(key: 'voice_ai_tts_pitch', value: pitch.toString());
      await _loadConfig();
      if (mounted) _notice('设置已安全保存');
    }
    base.dispose(); model.dispose(); key.dispose();
  }

  /// 麦克风开关：离线识别（Jarvis 同款）。
  /// 点麦克风开始聆听，说完停顿约 1 秒自动发送；再点一次手动结束并发送。
  Future<void> _toggleListening() async {
    if (_busy) return;
    if (_listening) {
      // 先退出聆听态，再停识别，避免迟到的识别结果被处理。
      _listenTimer?.cancel();
      setState(() => _listening = false);
      await _asr.stop();
      if (_recognized.trim().isNotEmpty) {
        _input.text = _recognized.trim();
        await _send();
      }
      return;
    }
    // 停止正在播报的语音，避免自己的声音被识别进去（Jarvis 同款做法）。
    await _player.stop();
    try {
      await _asr.init();
    } catch (e) {
      _notice('离线语音识别不可用：$e');
      return;
    }
    _recognized = '';
    _emptyEndpoints = 0;
    setState(() => _listening = true);
    final ok = await _asr.start(
      onPartial: (t) {
        if (!mounted || !_listening) return;
        setState(() {
          _recognized = t;
          _input.text = t;
          _input.selection = TextSelection.collapsed(offset: _input.text.length);
        });
      },
      onEndpoint: (t) {
        if (!mounted || !_listening) return;
        final q = t.trim();
        if (q.isEmpty) {
          // 约 2.4 秒无语音的空端点：连续三次则结束聆听（Jarvis 同款做法）。
          if (++_emptyEndpoints >= 3) {
            _listenTimer?.cancel();
            setState(() => _listening = false);
            unawaited(_asr.stop());
          }
          return;
        }
        _listenTimer?.cancel();
        setState(() => _listening = false);
        _recognized = q;
        _input.text = q;
        unawaited(_asr.stop());
        unawaited(_send());
      },
    );
    if (!ok) {
      if (mounted) setState(() => _listening = false);
      _notice('无法使用麦克风，请检查录音权限。');
      return;
    }
    // 60 秒上限（旧版系统识别 listenFor 60 秒的同款限制）。
    _listenTimer?.cancel();
    _listenTimer = Timer(const Duration(seconds: 60), () {
      if (!_listening) return;
      setState(() => _listening = false);
      unawaited(_asr.stop());
    });
  }

  List<String> _retrieve(String query) {
    if (_offlineKnowledge.isEmpty) return const [];
    final terms = RegExp(r'[\u4e00-\u9fff]{2,}|[A-Za-z]{3,}')
        .allMatches(query.toLowerCase()).map((m) => m.group(0)!).toSet();
    if (terms.isEmpty) return const [];
    final chunks = _offlineKnowledge
        .split(RegExp(r'\n\s*\n'))
        .map((s) => s.trim())
        .where((s) => s.length > 35)
        .toList();
    final scored = <({int score, String text})>[];
    for (final chunk in chunks) {
      final lower = chunk.toLowerCase();
      final score = terms.fold<int>(0, (sum, term) => sum + (lower.contains(term) ? (term.length > 3 ? 2 : 1) : 0));
      if (score > 0) scored.add((score: score, text: chunk));
    }
    scored.sort((a, b) => b.score.compareTo(a.score));
    return scored.take(4).map((e) => e.text.length > 1600 ? '${e.text.substring(0, 1600)}…' : e.text).toList();
  }

  Future<void> _send() async {
    final question = _input.text.trim();
    if (question.isEmpty || _busy) return;
    FocusScope.of(context).unfocus();
    setState(() {
      _messages.add(_VoiceMessage(role: 'user', text: question));
      _input.clear();
      _busy = true;
    });
    _scrollToBottom();
    try {
      final key = await _storage.read(key: 'voice_ai_api_key');
      final references = _retrieve(question);
      String answer;
      if (key == null || key.isEmpty) {
        answer = references.isEmpty
            ? '目前未配置 API Key，且没有找到匹配的本地典籍片段。请点右上角设置联网模型；你也可以尝试输入更具体的典籍、方剂或术语。'
            : '【离线知识库检索】\n\n${references.join('\n\n---\n\n')}\n\n当前尚未配置 API Key，因此以上为本地资料检索结果，并非 AI 诊断。';
      } else {
        final uri = Uri.parse('$_baseUrl/chat/completions');
        final history = _messages.take(_messages.length - 1).toList().reversed.take(8).toList().reversed.map((m) => {'role': m.role, 'content': m.text}).toList();
        final system = '你是汉方唐鉴的中医典籍学习助手。以中文回答，回答使用纯文本，不要使用 Markdown 格式符号（如 **、#）。优先区分经典原文、传统理论与现代医学证据，不将传统理论包装成已证实疗效。不得仅凭聊天作出确定诊断，不建议用户自行停用处方药。遇到急症提醒立即就医。以下是本地知识库检索片段，若无关请明确说明，不要编造引用：\n${references.join('\n\n---\n\n')}';
        final response = await http.post(
          uri,
          headers: {'Authorization': 'Bearer $key', 'Content-Type': 'application/json'},
          body: jsonEncode({
            'model': _model,
            'messages': [ {'role': 'system', 'content': system}, ...history, {'role': 'user', 'content': question} ],
            'temperature': 0.4,
          }),
        ).timeout(const Duration(seconds: 90));
        if (response.statusCode < 200 || response.statusCode >= 300) {
          throw Exception('服务返回 HTTP ${response.statusCode}：${_short(response.body, 240)}');
        }
        final data = jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>;
        answer = _plainText((((data['choices'] as List).first as Map)['message'] as Map)['content']?.toString().trim() ?? '模型未返回文字内容。');
      }
      if (!mounted) return;
      setState(() => _messages.add(_VoiceMessage(role: 'assistant', text: answer)));
      _scrollToBottom();
      if (_speakReplies) await _speak(answer);
    } catch (e) {
      if (mounted) {
        setState(() => _messages.add(_VoiceMessage(role: 'assistant', text: '连接失败：$e\n\n请检查 API Base URL、模型名称、Key、网络连接及服务商是否支持 /chat/completions。')));
        _scrollToBottom();
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String _short(String value, int max) => value.length <= max ? value : '${value.substring(0, max)}…';

  /// 去掉模型回答中的 Markdown 格式符号（界面为纯文本显示，播报不念符号）
  String _plainText(String value) => value
      .replaceAll('**', '')
      .replaceAll(RegExp(r'^#{1,6}\s?', multiLine: true), '')
      .replaceAll(RegExp(r'^[-*]\s+', multiLine: true), '· ')
      .replaceAll('*', '');

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) _scroll.animateTo(_scroll.position.maxScrollExtent, duration: const Duration(milliseconds: 250), curve: Curves.easeOut);
    });
  }

  void _notice(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('汉方唐鉴 · 语音 AI', style: TextStyle(fontWeight: FontWeight.w700)),
          Text('典籍检索 · 语音问答', style: TextStyle(fontSize: 11, color: _accentText)),
        ]),
        actions: [
          IconButton(tooltip: _speakReplies ? '关闭语音回复' : '开启语音回复', onPressed: () => setState(() => _speakReplies = !_speakReplies), icon: Icon(_speakReplies ? Icons.volume_up : Icons.volume_off)),
          IconButton(tooltip: '模型设置', onPressed: _configure, icon: const Icon(Icons.tune)),
        ],
      ),
      body: Column(children: [
        Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: BoxDecoration(color: _cardBg, border: Border(bottom: BorderSide(color: _border))),
          child: Row(children: [Icon(Icons.auto_awesome, color: _accentText, size: 18), const SizedBox(width: 8), Expanded(child: Text(_baseUrl, style: TextStyle(color: _accentText, fontSize: 12), overflow: TextOverflow.ellipsis)), Text(_offlineKnowledge.isEmpty ? '索引加载中' : '离线典籍已就绪', style: TextStyle(color: _accentText, fontSize: 11))]),
        ),
        Expanded(child: ListView.builder(
          controller: _scroll,
          padding: const EdgeInsets.fromLTRB(12, 16, 12, 12),
          itemCount: _messages.length + (_busy ? 1 : 0),
          itemBuilder: (context, index) {
            if (_busy && index == _messages.length) return Align(alignment: Alignment.centerLeft, child: Padding(padding: const EdgeInsets.all(12), child: Row(mainAxisSize: MainAxisSize.min, children: [SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2, color: _accentText)), const SizedBox(width: 10), const Text('正在整理典籍与生成回答…')])));
            final message = _messages[index];
            final user = message.role == 'user';
            return Align(alignment: user ? Alignment.centerRight : Alignment.centerLeft, child: Container(
              constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * .88),
              margin: const EdgeInsets.only(bottom: 12), padding: const EdgeInsets.all(13),
              decoration: BoxDecoration(color: user ? _userBubbleBg : _cardBg, borderRadius: BorderRadius.circular(14), border: Border.all(color: user ? _userBorder : _border)),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(user ? '你' : '汉方唐鉴', style: TextStyle(color: _accentText, fontWeight: FontWeight.bold, fontSize: 12)),
                const SizedBox(height: 6), SelectableText(message.text, style: const TextStyle(height: 1.5)),
                if (!user) Align(alignment: Alignment.centerRight, child: IconButton(visualDensity: VisualDensity.compact, tooltip: '朗读回答', onPressed: () => _speak(message.text), icon: Icon(Icons.volume_up_outlined, size: 19, color: _accentText))),
              ]),
            ));
          },
        )),
        SafeArea(top: false, child: Container(
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
          decoration: BoxDecoration(color: _barBg, border: Border(top: BorderSide(color: _border))),
          child: Column(children: [
            if (_listening) Padding(padding: const EdgeInsets.only(bottom: 8), child: Row(children: [Icon(Icons.graphic_eq, color: _accentText), const SizedBox(width: 8), Expanded(child: Text(_recognized.isEmpty ? '正在聆听，请开始说话…' : _recognized, style: TextStyle(color: _accentText))), Text('点击麦克风结束', style: TextStyle(fontSize: 11, color: _mutedText))])),
            Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
              IconButton.filled(onPressed: _busy ? null : _toggleListening, style: IconButton.styleFrom(backgroundColor: _listening ? const Color(0xFF7B2424) : _userBubbleBg, foregroundColor: _accentText), icon: Icon(_listening ? Icons.stop : Icons.mic, size: 26)),
              const SizedBox(width: 8),
              Expanded(child: TextField(controller: _input, minLines: 1, maxLines: 4, textInputAction: TextInputAction.send, onSubmitted: (_) => _send(), decoration: InputDecoration(hintText: _listening ? '正在识别语音…' : '输入问题，或点击麦克风说话', filled: true, fillColor: _cardBg, border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide(color: _border)), enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide(color: _border)), contentPadding: const EdgeInsets.symmetric(horizontal: 13, vertical: 12)))),
              const SizedBox(width: 8),
              IconButton.filled(onPressed: _busy ? null : _send, style: IconButton.styleFrom(backgroundColor: _gold, foregroundColor: const Color(0xFF201806)), icon: const Icon(Icons.arrow_upward)),
            ]),
            const SizedBox(height: 6),
            Text('典籍学习工具，不替代执业医师诊断；语音识别为本地离线识别，无需联网。', style: TextStyle(fontSize: 10, color: _faintText)),
          ]),
        )),
      ]),
    );
  }
}

class _VoiceMessage {
  final String role;
  final String text;
  const _VoiceMessage({required this.role, required this.text});
}
