import 'package:flutter/material.dart';

import '../services/edge_tts_service.dart';
import '../services/settings_store.dart';

/// Settings: base URL, model, API key, voice, rate, pitch.
/// The API key field starts empty; nothing is hard-coded.
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
  late String _voice = widget.settings.edgeVoice;
  late double _rate = widget.settings.rate;
  late double _pitch = widget.settings.pitch;
  late bool _speak = widget.settings.speakReplies;
  late bool _particles = widget.settings.particles;

  @override
  void dispose() {
    _base.dispose();
    _model.dispose();
    _key.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    widget.settings.baseUrl = _base.text.trim();
    widget.settings.model = _model.text.trim();
    widget.settings.apiKey = _key.text.trim();
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF020A14),
      appBar: AppBar(
        backgroundColor: const Color(0xFF041525),
        title: const Text('Jarvis 设置',
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
                  color: Color(0xFF00E5FF), fontWeight: FontWeight.bold)),
          const SizedBox(height: 10),
          TextField(
              controller: _base,
              style: const TextStyle(color: Colors.white),
              decoration: const InputDecoration(
                  labelText: '接口地址',
                  hintText: 'https://apihub.agnes-ai.com/v1',
                  labelStyle: TextStyle(color: Colors.white70),
                  hintStyle: TextStyle(color: Colors.white30),
                  enabledBorder: OutlineInputBorder(
                      borderSide: BorderSide(color: Colors.white24)),
                  focusedBorder: OutlineInputBorder(
                      borderSide: BorderSide(color: Color(0xFF00E5FF))))),
          const SizedBox(height: 12),
          TextField(
              controller: _model,
              style: const TextStyle(color: Colors.white),
              decoration: const InputDecoration(
                  labelText: '模型名称',
                  hintText: 'agnes-3.0-flash',
                  labelStyle: TextStyle(color: Colors.white70),
                  hintStyle: TextStyle(color: Colors.white30),
                  enabledBorder: OutlineInputBorder(
                      borderSide: BorderSide(color: Colors.white24)),
                  focusedBorder: OutlineInputBorder(
                      borderSide: BorderSide(color: Color(0xFF00E5FF))))),
          const SizedBox(height: 12),
          TextField(
              controller: _key,
              obscureText: true,
              style: const TextStyle(color: Colors.white),
              decoration: const InputDecoration(
                  labelText: 'API Key',
                  helperText: '仅保存在本机安全存储，不会写进代码。留空则无法联网问答。',
                  helperStyle: TextStyle(color: Colors.white38),
                  labelStyle: TextStyle(color: Colors.white70),
                  enabledBorder: OutlineInputBorder(
                      borderSide: BorderSide(color: Colors.white24)),
                  focusedBorder: OutlineInputBorder(
                      borderSide: BorderSide(color: Color(0xFF00E5FF))))),
          const SizedBox(height: 24),
          const Text('微软 Edge 语音',
              style: TextStyle(
                  color: Color(0xFF00E5FF), fontWeight: FontWeight.bold)),
          const SizedBox(height: 10),
          DropdownButtonFormField<String>(
            value: EdgeTtsService.voices.any((v) => v['id'] == _voice)
                ? _voice
                : EdgeTtsService.defaultVoice,
            dropdownColor: const Color(0xFF0B2236),
            style: const TextStyle(color: Colors.white),
            decoration: const InputDecoration(
                labelText: '播报声音',
                labelStyle: TextStyle(color: Colors.white70),
                enabledBorder: OutlineInputBorder(
                    borderSide: BorderSide(color: Colors.white24))),
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
