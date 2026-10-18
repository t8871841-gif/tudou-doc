import 'package:flutter/material.dart';

import '../services/edge_tts_service.dart';
import '../services/settings_store.dart';

/// 车机版设置：云端大脑（DeepSeek 默认）+ Edge 语音。
/// Key 只存在本机安全存储，默认空，用户填自己的。
class SettingsScreen extends StatefulWidget {
  final CarSettings settings;
  const SettingsScreen({super.key, required this.settings});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late final _base = TextEditingController(text: widget.settings.baseUrl);
  late final _model = TextEditingController(text: widget.settings.model);
  late final _key = TextEditingController(text: widget.settings.apiKey);
  late String _voice = widget.settings.edgeVoice;
  late double _rate = widget.settings.rate;
  late bool _speak = widget.settings.speakReplies;

  @override
  void dispose() {
    _base.dispose();
    _model.dispose();
    _key.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    widget.settings
      ..baseUrl = _base.text.trim()
      ..model = _model.text.trim()
      ..apiKey = _key.text.trim()
      ..edgeVoice = _voice
      ..rate = _rate
      ..speakReplies = _speak;
    await widget.settings.save();
    if (mounted) Navigator.of(context).pop(true);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const Text('云端大脑（OpenAI 兼容接口）',
              style: TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          TextField(
            controller: _base,
            decoration: const InputDecoration(
              labelText: 'API Base URL',
              hintText: 'https://api.deepseek.com/v1',
            ),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _model,
            decoration: const InputDecoration(
              labelText: '模型名称',
              hintText: 'deepseek-chat',
            ),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _key,
            obscureText: true,
            decoration: const InputDecoration(
              labelText: 'API Key',
              helperText: '仅保存在本机安全存储中，不要提交到代码仓库。',
            ),
          ),
          const SizedBox(height: 24),
          const Text('语音播报', style: TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          DropdownButtonFormField<String>(
            value: EdgeTtsService.voices.any((v) => v['id'] == _voice)
                ? _voice
                : EdgeTtsService.defaultVoice,
            decoration: const InputDecoration(labelText: '播报音色'),
            items: EdgeTtsService.voices
                .map((v) => DropdownMenuItem(
                      value: v['id']!,
                      child: Text(v['label']!),
                    ))
                .toList(),
            onChanged: (v) {
              if (v != null) setState(() => _voice = v);
            },
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              const Text('语速'),
              Expanded(
                child: Slider(
                  value: _rate,
                  min: 0.1,
                  max: 1.0,
                  divisions: 9,
                  label: _rate.toStringAsFixed(2),
                  onChanged: (v) => setState(() => _rate = v),
                ),
              ),
              Text(_rate.toStringAsFixed(2)),
            ],
          ),
          SwitchListTile(
            title: const Text('语音播报回答'),
            value: _speak,
            onChanged: (v) => setState(() => _speak = v),
          ),
          const SizedBox(height: 16),
          FilledButton(onPressed: _save, child: const Text('保存设置')),
        ],
      ),
    );
  }
}
