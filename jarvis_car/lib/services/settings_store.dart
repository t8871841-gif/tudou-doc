import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// 车机版设置。API Key 只存在平台安全存储里，代码里绝不硬编码，
/// 默认也是空的，由用户在设置里填写自己的 DeepSeek Key。
class CarSettings {
  static const _storage = FlutterSecureStorage();

  // 云端大脑默认：DeepSeek（OpenAI 兼容接口）。
  static const defaultBaseUrl = 'https://api.deepseek.com/v1';
  static const defaultModel = 'deepseek-chat';
  static const defaultEdgeVoice = 'zh-CN-YunyangNeural';
  static const defaultRate = 0.35;
  static const defaultPitch = 1.0;
  static const defaultSpeakReplies = true;

  static const defaultSystemPrompt =
      '你是 Jarvis 车机版，一位中文智能语音助手，运行在汽车中控屏上。'
      '说话简洁、直接、有礼貌，用中文回答。'
      '不知道就直说不知道，不要编造。回答不要使用 Markdown 格式符号（如 **、#），用纯文本，方便语音播报。'
      '开车场景下回答尽量简短。';

  String baseUrl = defaultBaseUrl;
  String model = defaultModel;
  String apiKey = ''; // 默认空，用户在设置里填自己的 Key
  String edgeVoice = defaultEdgeVoice;
  double rate = defaultRate;
  double pitch = defaultPitch;
  bool speakReplies = defaultSpeakReplies;
  String systemPrompt = defaultSystemPrompt;

  Future<void> load() async {
    baseUrl =
        await _storage.read(key: 'jarvis_car_base_url') ?? defaultBaseUrl;
    model = await _storage.read(key: 'jarvis_car_model') ?? defaultModel;
    apiKey = await _storage.read(key: 'jarvis_car_api_key') ?? '';
    edgeVoice =
        await _storage.read(key: 'jarvis_car_edge_voice') ?? defaultEdgeVoice;
    rate = double.tryParse(
            await _storage.read(key: 'jarvis_car_tts_rate') ?? '') ??
        defaultRate;
    pitch = double.tryParse(
            await _storage.read(key: 'jarvis_car_tts_pitch') ?? '') ??
        defaultPitch;
    speakReplies =
        (await _storage.read(key: 'jarvis_car_speak_replies')) != 'false';
    systemPrompt = await _storage.read(key: 'jarvis_car_system_prompt') ??
        defaultSystemPrompt;
  }

  Future<void> save() async {
    final base = baseUrl.trim().replaceAll(RegExp(r'/+$'), '');
    await _storage.write(
        key: 'jarvis_car_base_url', value: base.isEmpty ? defaultBaseUrl : base);
    await _storage.write(
        key: 'jarvis_car_model',
        value: model.trim().isEmpty ? defaultModel : model.trim());
    if (apiKey.trim().isEmpty) {
      await _storage.delete(key: 'jarvis_car_api_key');
    } else {
      await _storage.write(key: 'jarvis_car_api_key', value: apiKey.trim());
    }
    await _storage.write(key: 'jarvis_car_edge_voice', value: edgeVoice);
    await _storage.write(key: 'jarvis_car_tts_rate', value: rate.toString());
    await _storage.write(key: 'jarvis_car_tts_pitch', value: pitch.toString());
    await _storage.write(
        key: 'jarvis_car_speak_replies', value: speakReplies ? 'true' : 'false');
    await _storage.write(key: 'jarvis_car_system_prompt', value: systemPrompt);
  }

  bool get configured => apiKey.trim().isNotEmpty;
}
