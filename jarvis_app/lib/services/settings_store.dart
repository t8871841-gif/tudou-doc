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
  /// 运行时注入的长期记忆（不落盘；记忆现在由 MemoryService 按问题检索，此字段仅为兼容保留）。
  String memoryContext = '';

  // ---- v2 新增 ----
  static const defaultSearchEndpoint = 'https://api.bochaai.com/v1/web-search';
  String searchEndpoint = defaultSearchEndpoint; // 联网搜索接口（博查兼容）
  String searchApiKey = ''; // 空 = 退回免 Key 的维基百科 / DuckDuckGo
  String homeCity = ''; // 定位不可用时的天气默认城市
  bool keepAlive = false; // 前台服务常驻待命（熄屏也能听唤醒词）
  bool continuousChat = false; // 语音提问后自动继续听（免唤醒连续对话）
  bool morningBrief = false; // 每天定时晨报
  String morningBriefTime = '07:30';
  String userWhitelist = ''; // 额外允许“看屏”的 App 包名，每行一个
  // ---- v3 视觉看屏 ----
  bool visionEnabled = false; // 截图 + 多模态模型
  String visionModel = ''; // 空 = 复用上面的模型
  String visionBaseUrl = ''; // 空 = 复用上面的接口地址
  String visionApiKey = ''; // 空 = 复用上面的 Key
  String visionCoord = 'pixel'; // pixel | norm1000（Qwen-VL 系用 0~1000 归一化坐标）

  bool get visionConfigured =>
      visionEnabled && (visionApiKey.trim().isNotEmpty || apiKey.trim().isNotEmpty);
  String get visionBase => visionBaseUrl.trim().isEmpty ? baseUrl : visionBaseUrl;
  String get visionKey => visionApiKey.trim().isEmpty ? apiKey : visionApiKey;
  String get visionModelName => visionModel.trim().isEmpty ? model : visionModel.trim();

  // ---- v3 语音唤醒 ----
  bool bargeIn = true; // 打断说话（降级版）：播报时开口即停
  // ---- v3 主动性 ----
  bool announceOn = false; // 来电 / 短信 / 通知播报
  bool announceSms = true;
  bool announceCall = true;
  bool announceWeChat = false;
  String announceExtraPkgs = ''; // 额外播报的 App 包名，每行一个
  String announceSenders = ''; // 只播这些联系人 / 发件人（逗号分隔，空 = 全部）
  String quietStart = '22:00'; // 勿扰时段内不播报
  String quietEnd = '07:00';
  bool departRemind = false; // 日程出发提醒
  int departLeadMin = 30; // 固定提前量（分钟）

  bool wakeEnabled = false; // 开着唤醒词待命（主界面唤醒词胶囊同步）
  int wakeSensitivity = 1; // 0 严格 / 1 标准 / 2 宽松

  bool floatingOrb = false; // 悬浮球（需要无障碍服务）
  bool localEnabled = false; // 端侧小模型：简单任务本地答，复杂任务上云
  String localModelPath = ''; // 端侧模型文件（App 私有目录内）

  List<String> get userWhitelistPackages => userWhitelist
      .split(RegExp(r'[\s,;]+'))
      .map((e) => e.trim())
      .where((e) => RegExp(r'^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$').hasMatch(e))
      .toList();

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
    searchEndpoint = await _storage.read(key: 'jarvis_search_endpoint') ??
        defaultSearchEndpoint;
    searchApiKey = await _storage.read(key: 'jarvis_search_key') ?? '';
    homeCity = await _storage.read(key: 'jarvis_home_city') ?? '';
    keepAlive = (await _storage.read(key: 'jarvis_keep_alive')) == 'true';
    continuousChat =
        (await _storage.read(key: 'jarvis_continuous')) == 'true';
    morningBrief = (await _storage.read(key: 'jarvis_brief')) == 'true';
    morningBriefTime =
        await _storage.read(key: 'jarvis_brief_time') ?? '07:30';
    userWhitelist = await _storage.read(key: 'jarvis_user_whitelist') ?? '';
    floatingOrb = (await _storage.read(key: 'jarvis_orb')) == 'true';
    wakeEnabled = (await _storage.read(key: 'jarvis_wake_on')) == 'true';
    Future<String> r(String k, String d) async => await _storage.read(key: k) ?? d;
    announceOn = (await r('jarvis_ann_on', 'false')) == 'true';
    announceSms = (await r('jarvis_ann_sms', 'true')) == 'true';
    announceCall = (await r('jarvis_ann_call', 'true')) == 'true';
    announceWeChat = (await r('jarvis_ann_wx', 'false')) == 'true';
    announceExtraPkgs = await r('jarvis_ann_pkgs', '');
    announceSenders = await r('jarvis_ann_senders', '');
    quietStart = await r('jarvis_quiet_s', '22:00');
    quietEnd = await r('jarvis_quiet_e', '07:00');
    departRemind = (await r('jarvis_depart', 'false')) == 'true';
    departLeadMin = (int.tryParse(await r('jarvis_depart_lead', '30')) ?? 30).clamp(10, 120);
    bargeIn = (await _storage.read(key: 'jarvis_barge')) != 'false';
    wakeSensitivity =
        (int.tryParse(await _storage.read(key: 'jarvis_wake_sens') ?? '1') ?? 1).clamp(0, 2);
    visionEnabled = (await _storage.read(key: 'jarvis_v_on')) == 'true';
    visionModel = await _storage.read(key: 'jarvis_v_model') ?? '';
    visionBaseUrl = await _storage.read(key: 'jarvis_v_base') ?? '';
    visionApiKey = await _storage.read(key: 'jarvis_v_key') ?? '';
    visionCoord = await _storage.read(key: 'jarvis_v_coord') ?? 'pixel';
    localEnabled = (await _storage.read(key: 'jarvis_local_on')) == 'true';
    localModelPath = await _storage.read(key: 'jarvis_local_path') ?? '';
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
    await _storage.write(
        key: 'jarvis_search_endpoint',
        value: searchEndpoint.trim().isEmpty
            ? defaultSearchEndpoint
            : searchEndpoint.trim());
    await _storage.write(key: 'jarvis_search_key', value: searchApiKey.trim());
    await _storage.write(key: 'jarvis_home_city', value: homeCity.trim());
    await _storage.write(
        key: 'jarvis_keep_alive', value: keepAlive ? 'true' : 'false');
    await _storage.write(
        key: 'jarvis_continuous', value: continuousChat ? 'true' : 'false');
    await _storage.write(
        key: 'jarvis_brief', value: morningBrief ? 'true' : 'false');
    await _storage.write(key: 'jarvis_brief_time', value: morningBriefTime);
    await _storage.write(key: 'jarvis_user_whitelist', value: userWhitelist);
    await _storage.write(
        key: 'jarvis_orb', value: floatingOrb ? 'true' : 'false');
    await _storage.write(key: 'jarvis_wake_on', value: wakeEnabled ? 'true' : 'false');
    Future<void> w(String k, String v) => _storage.write(key: k, value: v);
    String b(bool v) => v ? 'true' : 'false';
    await w('jarvis_ann_on', b(announceOn));
    await w('jarvis_ann_sms', b(announceSms));
    await w('jarvis_ann_call', b(announceCall));
    await w('jarvis_ann_wx', b(announceWeChat));
    await w('jarvis_ann_pkgs', announceExtraPkgs);
    await w('jarvis_ann_senders', announceSenders);
    await w('jarvis_quiet_s', quietStart);
    await w('jarvis_quiet_e', quietEnd);
    await w('jarvis_depart', b(departRemind));
    await w('jarvis_depart_lead', '$departLeadMin');
    await _storage.write(key: 'jarvis_barge', value: bargeIn ? 'true' : 'false');
    await _storage.write(key: 'jarvis_wake_sens', value: '$wakeSensitivity');
    await _storage.write(key: 'jarvis_v_on', value: visionEnabled ? 'true' : 'false');
    await _storage.write(key: 'jarvis_v_model', value: visionModel.trim());
    await _storage.write(key: 'jarvis_v_base', value: visionBaseUrl.trim());
    await _storage.write(key: 'jarvis_v_key', value: visionApiKey.trim());
    await _storage.write(key: 'jarvis_v_coord', value: visionCoord);
    await _storage.write(
        key: 'jarvis_local_on', value: localEnabled ? 'true' : 'false');
    await _storage.write(key: 'jarvis_local_path', value: localModelPath);
  }

  bool get configured => apiKey.trim().isNotEmpty;
}
