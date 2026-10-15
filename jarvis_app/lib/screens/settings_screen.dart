import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';

import '../services/edge_tts_service.dart';
import '../services/settings_store.dart';

/// Settings: base URL, model, API key, persona prompt, voice, rate, pitch.
/// The API key field starts empty; nothing is hard-coded.
///
/// Theme: dark is kept (HUD style), but contrast is lifted — the page
/// background, cards and inputs are all visibly lighter than before
/// (#00060B / #00121A / #000D14 were almost indistinguishable),
/// so the form no longer looks like one flat black slab.
/// Single save entry: only the bottom "保存设置" button. The AppBar
/// "保存" was removed (it duplicated the bottom button).
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
  late String _voice = EdgeTtsService.voices
          .any((v) => v['id'] == widget.settings.edgeVoice)
      ? widget.settings.edgeVoice
      : EdgeTtsService.defaultVoice;
  late double _rate = widget.settings.rate;
  late double _pitch = widget.settings.pitch;
  late bool _speak = widget.settings.speakReplies;
  late bool _particles = widget.settings.particles;

  final _player = AudioPlayer();
  bool _previewing = false;

  // Lifted-contrast dark palette (still dark, no longer flat black).
  static const _accent = Color(0xFF33DDFF);
  static const _pageBg = Color(0xFF0A1622);
  static const _card = Color(0xFF14273A);
  static const _inputBg = Color(0xFF1D3852);
  static const _dropdownBg = Color(0xFF1E3A52);

  @override
  void dispose() {
    _base.dispose();
    _model.dispose();
    _key.dispose();
    _sys.dispose();
    _player.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    widget.settings.baseUrl = _base.text.trim();
    widget.settings.model = _model.text.trim();
    widget.settings.apiKey = _key.text.trim();
    widget.settings.systemPrompt = _sys.text.trim();
    widget.settings.ttsEngine = JarvisSettings.engineEdge;
    widget.settings.edgeVoice = _voice;
    widget.settings.rate = _rate;
    widget.settings.pitch = _pitch;
    widget.settings.speakReplies = _speak;
    widget.settings.particles = _particles;
    try {
      await widget.settings.save();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('保存失败：$e')));
      return;
    }
    if (!mounted) return;
    // Pop with true; Home shows the confirmation SnackBar. Showing one
    // here would vanish instantly on pop.
    Navigator.pop(context, true);
  }

  /// Preview the selected Edge voice / rate / pitch in place.
  /// Failures show the real error instead of a generic "check
  /// network", so a broken engine can be diagnosed.
  Future<void> _previewVoice() async {
    if (_previewing) {
      await _player.stop();
      if (mounted) setState(() => _previewing = false);
      return;
    }
    setState(() => _previewing = true);
    try {
      await _player.stop();
      final label = EdgeTtsService.voices
          .firstWhere((v) => v['id'] == _voice,
              orElse: () => EdgeTtsService.voices.first)['label'];
      final mp3 = await EdgeTtsService.synthesize(
        '您好，我是 Jarvis，这是$label，现在的声音就是这样。',
        voice: _voice,
        rate: _rate,
        pitch: _pitch,
      );
      await _player.play(BytesSource(mp3));
      await _player.onPlayerComplete.first
          .timeout(const Duration(seconds: 15));
    } catch (e) {
      if (mounted) {
        final msg = e.toString().replaceFirst('StateError: ', '');
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('试听失败：$msg')));
      }
    } finally {
      if (mounted) setState(() => _previewing = false);
    }
  }

  InputDecoration _dec(String label, {String? hint, String? helper}) {
    return InputDecoration(
      labelText: label,
      hintText: hint,
      helperText: helper,
      filled: true,
      fillColor: _inputBg,
      labelStyle: const TextStyle(color: Colors.white),
      hintStyle: const TextStyle(color: Colors.white54),
      helperStyle: const TextStyle(color: Colors.white60),
      enabledBorder: const OutlineInputBorder(
          borderSide: BorderSide(color: Colors.white38)),
      focusedBorder: const OutlineInputBorder(
          borderSide: BorderSide(color: _accent, width: 1.5)),
    );
  }

  Widget _section({required String title, required List<Widget> children}) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: _card,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: Colors.white12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(title,
              style: const TextStyle(
                  color: _accent, fontWeight: FontWeight.bold, fontSize: 15)),
          const SizedBox(height: 12),
          ...children,
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _pageBg,
      appBar: AppBar(
        backgroundColor: _card,
        title: const Text('大脑设置',
            style: TextStyle(color: Colors.white)),
        iconTheme: const IconThemeData(color: Colors.white),
        // No AppBar "保存" here: the bottom "保存设置" is the single save.
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _section(title: '大模型服务', children: [
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
          ]),
          const SizedBox(height: 16),
          _section(title: '语音播报', children: [
            const Text('语音引擎：Edge 在线（唯一）。断网时无语音播报。',
                style: TextStyle(color: Colors.white60, fontSize: 12)),
            const SizedBox(height: 12),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  // Plain DropdownButton with controlled `value` (not
                  // DropdownButtonFormField). The FormField variant
                  // manages its value via initialValue/FormFieldState,
                  // which made selections stick unreliably; a plain
                  // DropdownButton always reflects _voice, the source
                  // of truth. (`value` on the FormField variant is
                  // deprecated in Flutter 3.47.)
                  child: InputDecorator(
                    decoration: _dec('播报声音'),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<String>(
                        value: _voice,
                        dropdownColor: _dropdownBg,
                        style: const TextStyle(color: Colors.white),
                        isExpanded: true,
                        isDense: true,
                        items: EdgeTtsService.voices
                            .map((v) => DropdownMenuItem<String>(
                                value: v['id'],
                                child: Text(v['label']!)))
                            .toList(),
                        onChanged: (v) {
                          if (v != null) setState(() => _voice = v);
                        },
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                OutlinedButton(
                  onPressed: _previewVoice,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: _accent,
                    side: const BorderSide(color: _accent),
                    padding: const EdgeInsets.symmetric(
                        horizontal: 14, vertical: 16),
                  ),
                  child: Text(_previewing ? '停止' : '试听'),
                ),
              ],
            ),
            const SizedBox(height: 4),
            const Text('先点“试听”确认声音，再点底部“保存设置”。',
                style: TextStyle(color: Colors.white60, fontSize: 12)),
            const SizedBox(height: 12),
            Row(children: [
              const SizedBox(
                  width: 72,
                  child: Text('语速', style: TextStyle(color: Colors.white))),
              Expanded(
                  child: Slider(
                      value: _rate,
                      min: 0.1,
                      max: 1.0,
                      divisions: 18,
                      label: _rate.toStringAsFixed(2),
                      activeColor: _accent,
                      inactiveColor: Colors.white24,
                      onChanged: (v) => setState(() => _rate = v))),
              Text(_rate.toStringAsFixed(2),
                  style: const TextStyle(color: Colors.white)),
            ]),
            Row(children: [
              const SizedBox(
                  width: 72,
                  child: Text('音调', style: TextStyle(color: Colors.white))),
              Expanded(
                  child: Slider(
                      value: _pitch,
                      min: 0.5,
                      max: 2.0,
                      divisions: 15,
                      label: _pitch.toStringAsFixed(2),
                      activeColor: _accent,
                      inactiveColor: Colors.white24,
                      onChanged: (v) => setState(() => _pitch = v))),
              Text(_pitch.toStringAsFixed(2),
                  style: const TextStyle(color: Colors.white)),
            ]),
            SwitchListTile(
                title: const Text('语音播报回答',
                    style: TextStyle(color: Colors.white)),
                value: _speak,
                activeThumbColor: _accent,
                onChanged: (v) => setState(() => _speak = v)),
            SwitchListTile(
                title: const Text('粒子特效',
                    style: TextStyle(color: Colors.white)),
                value: _particles,
                activeThumbColor: _accent,
                onChanged: (v) => setState(() => _particles = v)),
            const SizedBox(height: 4),
            const Text('默认语速已调慢；速度和声音都可以在这里改。语音识别由系统服务处理。',
                style: TextStyle(color: Colors.white60, fontSize: 12)),
          ]),
          const SizedBox(height: 20),
          FilledButton(
              onPressed: _save,
              style: FilledButton.styleFrom(
                backgroundColor: _accent,
                foregroundColor: Colors.black,
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
              child: const Text('保存设置',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold))),
        ],
      ),
    );
  }
}
