import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:sherpa_onnx/sherpa_onnx.dart' as sherpa_onnx;

/// Kokoro offline TTS (sherpa-onnx Kokoro int8 multi-lang) — the primary
/// voice engine.
///
/// Offline-first: the model ships in Flutter assets and is copied to the
/// app support directory on first use, so synthesis works with no network,
/// no VPN and no API key.
///
/// Voices: Kokoro v1.0 multi-lang ships 8 Mandarin voices. `sid` selects
/// the voice by its index in the bundle's speaker table (from sherpa-onnx's
/// Kokoro v1.0 speaker2id table):
/// zf_xiaobei=45, zf_xiaoni=46, zf_xiaoxiao=47, zf_xiaoyi=48,
/// zm_yunjian=49, zm_yunxi=50, zm_yunxia=51, zm_yunyang=52.
/// Default is zm_yunyang (云阳, male).
/// Note: Kokoro's 云阳/云析/晓艺 are NOT Microsoft's 云扬/云希/晓伊 —
/// same pinyin, different characters, different models.
class KokoroTtsService {
  /// Kokoro Mandarin voices: id -> (label, sid).
  static const voices = <Map<String, Object>>[
    {'id': 'zm_yunyang', 'label': '云阳（男声·沉稳）', 'sid': 52},
    {'id': 'zm_yunjian', 'label': '云健（男声·有力）', 'sid': 49},
    {'id': 'zm_yunxi', 'label': '云析（男声·温润）', 'sid': 50},
    {'id': 'zm_yunxia', 'label': '云夏（男声·平静）', 'sid': 51},
    {'id': 'zf_xiaobei', 'label': '晓蓓（女声·活泼）', 'sid': 45},
    {'id': 'zf_xiaoni', 'label': '晓妮（女声·亲和）', 'sid': 46},
    {'id': 'zf_xiaoxiao', 'label': '晓晓（女声·温柔）', 'sid': 47},
    {'id': 'zf_xiaoyi', 'label': '晓艺（女声·专业）', 'sid': 48},
  ];

  static const defaultVoice = 'zm_yunyang';

  static int sidFor(String voiceId) {
    for (final v in voices) {
      if (v['id'] == voiceId) return v['sid'] as int;
    }
    return 52; // zm_yunyang
  }

  static String labelFor(String voiceId) {
    for (final v in voices) {
      if (v['id'] == voiceId) return v['label'] as String;
    }
    return '云阳（男声·沉稳）';
  }

  static const _assetPrefix = 'assets/kokoro/';
  static const _modelDirName = 'kokoro-v1';

  // Files copied from assets (flat names inside assets/kokoro/).
  static const _modelFile = 'model.int8.onnx';
  static const _voicesFile = 'voices.bin';
  static const _tokensFile = 'tokens.txt';

  static sherpa_onnx.OfflineTts? _tts;
  static bool _bindingsReady = false;
  static Future<void>? _initFuture;

  /// Synthesizes [text] to 16-bit PCM WAV bytes (mono).
  ///
  /// [voiceId] is a Kokoro voice id (e.g. 'zm_yunyang'); unknown ids fall
  /// back to the default male voice. [rate] is the app setting
  /// (0.1..1.0, higher = faster) and is mapped to Kokoro speed 0.5..2.0.
  /// [pitch] is accepted for API compatibility but Kokoro does not
  /// support pitch shifting — it is intentionally ignored, never an error.
  static Future<Uint8List> synthesize(
    String text, {
    String voiceId = defaultVoice,
    double rate = 0.55,
    // Kept for call-site compat; Kokoro has no pitch control.
    double pitch = 1.0,
  }) async {
    // Referenced so the parameter stays part of the API without lint noise.
    assert(pitch >= 0);
    final clean = text.trim();
    if (clean.isEmpty) throw StateError('Kokoro 合成文本为空');
    await _ensureInitialized();
    final tts = _tts;
    if (tts == null) throw StateError('Kokoro 初始化失败：引擎为空');
    final sid = sidFor(voiceId);
    final speed = _mapRateToSpeed(rate);
    final allSamples = <double>[];
    var sampleRate = 24000;
    for (final chunk in _chunks(clean)) {
      final audio = await Future(
        () => tts.generate(text: chunk, sid: sid, speed: speed),
      );
      if (audio.samples.isEmpty) {
        throw StateError('Kokoro 未返回音频');
      }
      sampleRate = audio.sampleRate;
      allSamples.addAll(audio.samples);
    }
    if (allSamples.isEmpty) throw StateError('Kokoro 未返回音频');
    return _toWav(Float32List.fromList(allSamples), sampleRate);
  }

  /// App rate (0.1..1.0, higher = faster) -> Kokoro speed (0.5..2.0).
  /// rate 0.55 (the app's slow default) maps to speed ~1.0.
  static double _mapRateToSpeed(double rate) {
    final s = 0.6 + rate.clamp(0.1, 1.0) * 0.8;
    return s.clamp(0.5, 2.0);
  }

  /// Split long text into sentence chunks so sherpa-onnx (maxNumSenetences)
  /// does not truncate a long reply. Each chunk stays under ~300 chars.
  static List<String> _chunks(String text, [int max = 300]) {
    final t = text.trim();
    if (t.length <= max) return [t];
    final parts = <String>[];
    final buf = StringBuffer();
    for (final s in t.split(RegExp(r'(?<=[。！？\n])'))) {
      if (buf.length + s.length > max && buf.isNotEmpty) {
        parts.add(buf.toString());
        buf.clear();
      }
      buf.write(s);
    }
    if (buf.isNotEmpty) parts.add(buf.toString());
    return parts;
  }

  static Future<void> _ensureInitialized() {
    _initFuture ??= _init();
    return _initFuture!;
  }

  static Future<void> _init() async {
    try {
      if (!_bindingsReady) {
        sherpa_onnx.initBindings();
        _bindingsReady = true;
      }
      final support = await getApplicationSupportDirectory();
      final base = Directory('${support.path}/$_modelDirName');
      final modelFile = File('${base.path}/$_modelFile');
      final voicesFile = File('${base.path}/$_voicesFile');
      final tokensFile = File('${base.path}/$_tokensFile');
      final espeakDir = Directory('${base.path}/espeak-ng-data');
      final dictDir = Directory('${base.path}/dict');
      final lexiconUsFile = File('${base.path}/lexicon-us-en.txt');
      final lexiconZhFile = File('${base.path}/lexicon-zh.txt');
      final phoneFst = File('${base.path}/phone-zh.fst');
      final dateFst = File('${base.path}/date-zh.fst');
      final numberFst = File('${base.path}/number-zh.fst');
      final marker = File('${base.path}/.ready');
      if (!await marker.exists()) {
        await base.create(recursive: true);
        // Copy every asset under assets/kokoro/ (model, voices, tokens,
        // lexicons, FSTs, dict/, espeak-ng-data/).
        final manifest = await AssetManifest.loadFromAssetBundle(rootBundle);
        final keys = manifest
            .listAssets()
            .where((k) => k.startsWith(_assetPrefix))
            .toList()
          ..sort();
        if (keys.isEmpty) {
          throw StateError('Kokoro 模型缺失：assets 里没有 kokoro 文件');
        }
        for (final key in keys) {
          final rel = key.substring(_assetPrefix.length);
          // Skip the bundle README/LICENSE: not needed at runtime.
          if (rel == 'README.md' || rel == 'LICENSE') continue;
          final target = File('${base.path}/$rel');
          await target.parent.create(recursive: true);
          await _copyAssetToFile(key, target);
        }
        if (!await modelFile.exists() ||
            !await voicesFile.exists() ||
            !await tokensFile.exists()) {
          throw StateError('Kokoro 模型缺失：.onnx / voices.bin / tokens.txt 未拷贝');
        }
        await marker.writeAsString('1');
      }
      // Kokoro multi-lang config (sherpa-onnx):
      // - lexicon: both English + Chinese lexicons, comma-separated.
      // - lang: empty — the model metadata resolves the language; passing
      //   a value like 'zh' or 'eng' is handed to espeak-ng as a voice
      //   name and breaks phonemization.
      // - dictDir: jieba dict for Chinese word segmentation.
      // - ruleFsts: Chinese number/date/phone FSTs, comma-separated.
      final config = sherpa_onnx.OfflineTtsConfig(
        model: sherpa_onnx.OfflineTtsModelConfig(
          kokoro: sherpa_onnx.OfflineTtsKokoroModelConfig(
            model: modelFile.path,
            voices: voicesFile.path,
            tokens: tokensFile.path,
            dataDir: espeakDir.path,
            dictDir: dictDir.path,
            lexicon: '${lexiconUsFile.path},${lexiconZhFile.path}',
            lang: '',
            lengthScale: 1.0,
          ),
          numThreads: 2,
          debug: false,
          provider: 'cpu',
        ),
        ruleFsts:
            '${phoneFst.path},${dateFst.path},${numberFst.path}',
        maxNumSenetences: 10,
        silenceScale: 0.2,
      );
      _tts = sherpa_onnx.OfflineTts(config);
    } catch (e) {
      _initFuture = null;
      throw StateError('Kokoro 初始化失败：$e');
    }
  }

  static Future<void> _copyAssetToFile(String asset, File target) async {
    final data = await rootBundle.load(asset);
    await target.writeAsBytes(
      data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
      flush: true,
    );
  }

  /// Float32 samples in [-1, 1] -> 16-bit PCM WAV (mono).
  static Uint8List _toWav(Float32List samples, int sampleRate) {
    final dataLen = samples.length * 2;
    final out = BytesBuilder();
    void ascii(String s) => out.add(s.codeUnits);
    void u32(int v) => out.add([
          v & 0xFF,
          (v >> 8) & 0xFF,
          (v >> 16) & 0xFF,
          (v >> 24) & 0xFF,
        ]);
    void u16(int v) => out.add([v & 0xFF, (v >> 8) & 0xFF]);
    ascii('RIFF');
    u32(36 + dataLen);
    ascii('WAVE');
    ascii('fmt ');
    u32(16);
    u16(1); // PCM
    u16(1); // mono
    u32(sampleRate);
    u32(sampleRate * 2); // byte rate
    u16(2); // block align
    u16(16); // bits per sample
    ascii('data');
    u32(dataLen);
    final pcm = ByteData(2);
    for (final s in samples) {
      final v = (s.clamp(-1.0, 1.0) * 32767).round();
      pcm.setInt16(0, v, Endian.little);
      out.add(pcm.buffer.asUint8List());
    }
    return out.toBytes();
  }
}
