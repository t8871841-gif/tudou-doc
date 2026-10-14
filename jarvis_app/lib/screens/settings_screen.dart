import 'package:flutter/material.dart';

import '../services/edge_tts_service.dart';
import '../services/settings_store.dart';

/// Settings: base URL, model, API key, persona prompt, voice, rate, pitch.
/// The API key field starts empty; nothing is hard-coded.
/// Styled after the HTML settings card (#set .card): dark #00121a card,
/// #000d14 inputs, cyan accent borders.
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
  late String _voice = widget.settings.edgeVoice;
  late double _rate = widget.settings.rate;
  late double _pitch = widget.settings.pitch;
  late bool _speak = widget.settings.speakReplies;
  late bool _particles = widget.settings.particles;

  static const _accent = Color(0xFF33DDFF);
  static const _card = Color(0xFF00121A);
  static const _inputBg = Color(0xFF000D14);

  @override
  void dispose() {
    _base.dispose();
    _model.dispose();
    _key.dispose();
    _sys.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    widget.settings.baseUrl = _base.text.trim();
    widget.settings.model = _model.text.trim();
    widget.settings.apiKey = _key.text.trim();
    widget.settings.systemPrompt = _sys.text.trim();
    widget.settings.edgeVoice = _voice;
    widget.settings.rate = _rate;
    widget.settings.pitch = _pitch;
    widget.settings.speakReplies = _speak;
    widget.settings.particles = _particles;
    await widget.settings.save();
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(const SnackBar(content: Text('设置已安全保存到本机')));
    Navigator.pop(context);
  }

  InputDecoration _dec(String label, {String? hint, String? helper}) {
    return InputDecoration(
      labelText: label,
      hintText: hint,
      helperText: helper,
      filled: true,
      fillColor: _inputBg,
      labelStyle: const TextStyle(color: Colors.white70),
      hintStyle: const TextStyle(color: Colors.white30),
      helperStyle: const TextStyle(color: Colors.white38),
      enabledBorder: const OutlineInputBorder(
          borderSide: BorderSide(color: Colors.white24)),
      focusedBorder: const OutlineInputBorder(
          borderSide: BorderSide(color: _accent)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF00060B),
      appBar: AppBar(
        backgroundColor: _card,
        title: const Text('大脑设置',
            style: TextStyle(color: Colors.white)),
        iconTheme: const IconThemeData(color: Colors.white),
        actions: [
          TextButton(
              onPressed: _save,
              child: const Text('保存', style: TextStyle(color: Colors.white)))
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const Text('大模型服务',
              style: TextStyle(
                  color: _accent, fontWeight: FontWeight.bold)),
          const SizedBox(height: 10),
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
          const SizedBox(height: 24),
          const Text('微软 Edge 语音',
              style: TextStyle(
                  color: _accent, fontWeight: FontWeight.bold)),
          const SizedBox(height: 10),
          DropdownButtonFormField<String>(
            initialValue: EdgeTtsService.voices.any((v) => v['id'] == _voice)
                ? _voice
                : EdgeTtsService.defaultVoice,
            dropdownColor: const Color(0xFF0B2236),
            style: const TextStyle(color: Colors.white),
            decoration: _dec('播报声音'),
            items: EdgeTtsService.voices
                .map((v) => DropdownMenuItem<String>(
                    value: v['id'], child: Text(v['label']!)))
                .toList(),
            onChanged: (v) => setState(() => _voice = v ?? _voice),
          ),
          const SizedBox(height: 12),
          Row(children: [
            const SizedBox(
                width: 72,
                child: Text('语速', style: TextStyle(color: Colors.white70))),
            Expanded(
                child: Slider(
                    value: _rate,
                    min: 0.1,
                    max: 1.0,
                    divisions: 18,
                    label: _rate.toStringAsFixed(2),
                    onChanged: (v) => setState(() => _rate = v))),
            Text(_rate.toStringAsFixed(2),
                style: const TextStyle(color: Colors.white70)),
          ]),
          Row(children: [
            const SizedBox(
                width: 72,
                child: Text('音调', style: TextStyle(color: Colors.white70))),
            Expanded(
                child: Slider(
                    value: _pitch,
                    min: 0.5,
                    max: 2.0,
                    divisions: 15,
                    label: _pitch.toStringAsFixed(2),
                    onChanged: (v) => setState(() => _pitch = v))),
            Text(_pitch.toStringAsFixed(2),
                style: const TextStyle(color: Colors.white70)),
          ]),
          SwitchListTile(
              title: const Text('语音播报回答',
                  style: TextStyle(color: Colors.white)),
              value: _speak,
              onChanged: (v) => setState(() => _speak = v)),
          SwitchListTile(
              title: const Text('粒子特效',
                  style: TextStyle(color: Colors.white)),
              value: _particles,
              onChanged: (v) => setState(() => _particles = v)),
          const SizedBox(height: 12),
          const Text('默认语速已调慢；速度和声音都可以在这里改。语音识别由系统服务处理。',
              style: TextStyle(color: Colors.white38, fontSize: 12)),
          const SizedBox(height: 20),
          FilledButton(onPressed: _save, child: const Text('保存设置')),
        ],
      ),
    );
  }
}
