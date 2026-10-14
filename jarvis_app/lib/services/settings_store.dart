import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// App settings. The API key is stored only in platform secure storage,
/// never hard-coded, never written to logs or code.
class JarvisSettings {
  static const _storage = FlutterSecureStorage();

  static const defaultBaseUrl = 'https://apihub.agnes-ai.com/v1';
  static const defaultModel = 'agnes-3.0-flash';
  static const defaultEdgeVoice = 'zh-CN-YunyangNeural';
  static const defaultRate = 0.35; // slower than normal, per spec
  static const defaultPitch = 1.0;
  static const defaultSpeakReplies = true;
  static const defaultParticles = true;

  String baseUrl = defaultBaseUrl;
  String model = defaultModel;
  String apiKey = ''; // empty by default; user fills in Settings
  String edgeVoice = defaultEdgeVoice;
  double rate = defaultRate;
  double pitch = defaultPitch;
  bool speakReplies = defaultSpeakReplies;
  bool particles = defaultParticles;

  Future<void> load() async {
    baseUrl =
        await _storage.read(key: 'jarvis_base_url') ?? defaultBaseUrl;
    model = await _storage.read(key: 'jarvis_model') ?? defaultModel;
    apiKey = await _storage.read(key: 'jarvis_api_key') ?? '';
    edgeVoice =
        await _storage.read(key: 'jarvis_edge_voice') ?? defaultEdgeVoice;
    rate = double.tryParse(
            await _storage.read(key: 'jarvis_tts_rate') ?? '') ??
        defaultRate;
    pitch = double.tryParse(
            await _storage.read(key: 'jarvis_tts_pitch') ?? '') ??
        defaultPitch;
    speakReplies =
        (await _storage.read(key: 'jarvis_speak_replies')) != 'false';
    particles = (await _storage.read(key: 'jarvis_particles')) != 'false';
  }

  Future<void> save() async {
    final base = baseUrl.trim().replaceAll(RegExp(r'/+$'), '');
    await _storage.write(
        key: 'jarvis_base_url', value: base.isEmpty ? defaultBaseUrl : base);
    await _storage.write(
        key: 'jarvis_model', value: model.trim().isEmpty ? defaultModel : model.trim());
    if (apiKey.trim().isEmpty) {
      await _storage.delete(key: 'jarvis_api_key');
    } else {
      await _storage.write(key: 'jarvis_api_key', value: apiKey.trim());
    }
    await _storage.write(key: 'jarvis_edge_voice', value: edgeVoice);
    await _storage.write(key: 'jarvis_tts_rate', value: rate.toString());
    await _storage.write(key: 'jarvis_tts_pitch', value: pitch.toString());
    await _storage.write(
        key: 'jarvis_speak_replies', value: speakReplies ? 'true' : 'false');
    await _storage.write(
        key: 'jarvis_particles', value: particles ? 'true' : 'false');
  }

  bool get configured => apiKey.trim().isNotEmpty;
}
