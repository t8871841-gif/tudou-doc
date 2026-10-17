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

  // TTS: Edge online only. Legacy stored values ('kokoro', 'piper',
  // 'system') are migrated to Edge on load.
  static const engineEdge = 'edge';
  static const defaultTtsEngine = engineEdge;

  // Default persona prompt (HTML settings "人设提示词"). Kept identical
  // to the previously hard-coded system prompt in LlmService.
  static const defaultSystemPrompt =
      '你是 Jarvis，一位中文智能语音助手。说话简洁、直接、有礼貌，用中文回答。'
      '你可以回答各种各样的问题：知识、日常、学习、工作、技术、生活等。'
      '不知道就直说不知道，不要编造。回答不要使用 Markdown 格式符号（如 **、#），用纯文本，方便语音播报。';

  String baseUrl = defaultBaseUrl;
  String model = defaultModel;
  String apiKey = ''; // empty by default; user fills in Settings
  String ttsEngine = defaultTtsEngine;
  String edgeVoice = defaultEdgeVoice;
  double rate = defaultRate;
  double pitch = defaultPitch;
  bool speakReplies = defaultSpeakReplies;
  bool particles = defaultParticles;
  String systemPrompt = defaultSystemPrompt;
  bool autoMemory = true; // 自动整理长期记忆（每几轮对话后台调用一次大模型）
  /// 运行时注入的长期记忆（不落盘，由 MemoryService 生成）。
  String memoryContext = '';

  Future<void> load() async {
    baseUrl =
        await _storage.read(key: 'jarvis_base_url') ?? defaultBaseUrl;
    model = await _storage.read(key: 'jarvis_model') ?? defaultModel;
    apiKey = await _storage.read(key: 'jarvis_api_key') ?? '';
    final engine = await _storage.read(key: 'jarvis_tts_engine');
    // Legacy values ('kokoro', 'piper', 'system') migrate to Edge.
    ttsEngine = (engine == engineEdge) ? engine! : defaultTtsEngine;
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
    systemPrompt = await _storage.read(key: 'jarvis_system_prompt') ??
        defaultSystemPrompt;
    autoMemory = (await _storage.read(key: 'jarvis_auto_memory')) != 'false';
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
    await _storage.write(key: 'jarvis_tts_engine', value: ttsEngine);
    await _storage.write(key: 'jarvis_edge_voice', value: edgeVoice);
    await _storage.write(key: 'jarvis_tts_rate', value: rate.toString());
    await _storage.write(key: 'jarvis_tts_pitch', value: pitch.toString());
    await _storage.write(
        key: 'jarvis_speak_replies', value: speakReplies ? 'true' : 'false');
    await _storage.write(
        key: 'jarvis_particles', value: particles ? 'true' : 'false');
    await _storage.write(key: 'jarvis_system_prompt', value: systemPrompt);
    await _storage.write(
        key: 'jarvis_auto_memory', value: autoMemory ? 'true' : 'false');
  }

  bool get configured => apiKey.trim().isNotEmpty;
}
