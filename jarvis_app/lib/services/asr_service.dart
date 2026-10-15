import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter/services.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:record/record.dart';
import 'package:sherpa_onnx/sherpa_onnx.dart' as sherpa;

/// Offline streaming speech recognition (sherpa-onnx).
///
/// * Microphone audio comes from the `record` plugin (main isolate).
/// * Decoding runs in a background isolate, so it never blocks the UI.
/// * Model files are bundled under [assetDir] and copied to app storage
///   on first launch (sherpa-onnx needs real file paths).
///
/// Usage: `await init()` once (slow, ~1-2 s), then `start(...)` / `stop()`.
class AsrService {
  /// Asset folder holding encoder / decoder / joiner / tokens.txt.
  static const assetDir = 'assets/asr/';

  /// Bump to force a re-copy after you replace the model files.
  static const _assetVersion = 'v1';

  /// 'zipformer' for sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.
  /// Use 'zipformer2' for zipformer2-family models.
  static const modelType = 'zipformer';

  static const _numThreads = 2;

  // Endpoint (end-of-utterance) rules, in seconds.
  // rule1: silence before anything was said; rule2: silence after speech
  // (the main "how fast do I react" knob, lower = snappier but may cut
  // slow speakers off); rule3: hard cap on one utterance's length.
  static const _rule1 = 2.4;
  static const _rule2 = 1.0;
  static const _rule3 = 20.0;

  final _rec = AudioRecorder();
  Isolate? _isolate;
  SendPort? _toWorker;
  ReceivePort? _fromWorker;
  Future<void>? _initFuture;
  StreamSubscription<Uint8List>? _sub;
  bool _running = false;
  void Function(String text)? _onPartial;
  void Function(String text)? _onEndpoint;
  void Function(double level)? _onLevel;
  Future<void> _chain = Future.value();

  bool get isRunning => _running;

  /// Copies the model (first run) and starts the decoder isolate.
  /// Safe to call repeatedly; failures can be retried.
  Future<void> init() async {
    final f = _initFuture ??= _doInit();
    try {
      await f;
    } catch (_) {
      if (identical(_initFuture, f)) _initFuture = null;
      rethrow;
    }
  }

  /// Starts the microphone and recognition. Returns false when the
  /// microphone is unavailable / permission denied.
  ///
  /// [onPartial] fires while the current utterance changes;
  /// [onEndpoint] fires once the speaker stops (text may be empty after
  /// a stretch of silence). Calling start() again replaces the callbacks.
  Future<bool> start({
    void Function(String text)? onPartial,
    required void Function(String text) onEndpoint,
    void Function(double level)? onLevel,
  }) =>
      _serial(() => _start(onPartial, onEndpoint, onLevel));

  Future<void> stop() => _serial(_stop);

  Future<void> dispose() async {
    await stop();
    _toWorker?.send('dispose');
    _isolate?.kill(priority: Isolate.beforeNextEvent);
    _fromWorker?.close();
    await _rec.dispose();
  }

  // ---------------------------------------------------------------------

  /// start()/stop() calls run strictly one after another.
  Future<T> _serial<T>(Future<T> Function() job) {
    final r = _chain.then((_) => job());
    _chain = r.then((_) {}, onError: (_) {});
    return r;
  }

  Future<bool> _start(
    void Function(String)? onPartial,
    void Function(String) onEndpoint,
    void Function(double)? onLevel,
  ) async {
    await init();
    await _stop();
    if (!await _rec.hasPermission()) return false;
    _onPartial = onPartial;
    _onEndpoint = onEndpoint;
    _onLevel = onLevel;
    _toWorker!.send('reset');
    final Stream<Uint8List> audio;
    try {
      audio = await _rec.startStream(const RecordConfig(
        encoder: AudioEncoder.pcm16bits,
        sampleRate: 16000,
        numChannels: 1,
        autoGain: true,
        echoCancel: true,
        noiseSuppress: true,
      ));
    } catch (_) {
      return false;
    }
    _running = true;
    _sub = audio.listen(_onAudio, onError: (_) {});
    return true;
  }

  Future<void> _stop() async {
    _running = false;
    _onPartial = null;
    _onEndpoint = null;
    _onLevel = null;
    await _sub?.cancel();
    _sub = null;
    try {
      if (await _rec.isRecording()) await _rec.stop();
    } catch (_) {}
  }

  void _onAudio(Uint8List bytes) {
    if (!_running) return;
    _onLevel?.call(_level(bytes));
    _toWorker?.send(TransferableTypedData.fromList([bytes]));
  }

  /// RMS of a PCM16 chunk, scaled to 0..1 for the reactor bars.
  static double _level(Uint8List bytes) {
    final n = bytes.length ~/ 2;
    if (n == 0) return 0;
    final d = ByteData.sublistView(bytes);
    var sum = 0.0;
    for (var i = 0; i < n; i++) {
      final s = d.getInt16(i * 2, Endian.little) / 32768.0;
      sum += s * s;
    }
    return (math.sqrt(sum / n) * 8).clamp(0.0, 1.0);
  }

  Future<void> _doInit() async {
    final dir = await _prepareModelDir();
    final files = dir.listSync().whereType<File>().toList();

    String? pick(String prefix, {required bool preferInt8}) {
      final c = files.where((f) {
        final n = p.basename(f.path);
        return n.startsWith(prefix) && n.endsWith('.onnx');
      }).toList();
      final int8 = c.where((f) => p.basename(f.path).contains('int8'));
      final fp = c.where((f) => !p.basename(f.path).contains('int8'));
      final ordered = preferInt8 ? [...int8, ...fp] : [...fp, ...int8];
      return ordered.isEmpty ? null : ordered.first.path;
    }

    final encoder = pick('encoder', preferInt8: true);
    final decoder = pick('decoder', preferInt8: false);
    final joiner = pick('joiner', preferInt8: false);
    final tokens = files
        .map((f) => f.path)
        .where((path) => p.basename(path) == 'tokens.txt')
        .firstOrNull;
    if (encoder == null ||
        decoder == null ||
        joiner == null ||
        tokens == null) {
      throw StateError(
          '识别模型文件不完整：assets/asr/ 里需要 encoder*.onnx、decoder*.onnx、joiner*.onnx 和 tokens.txt');
    }

    final fromWorker = ReceivePort();
    final ready = Completer<SendPort>();
    fromWorker.listen((m) {
      if (m is! List || m.isEmpty) return;
      switch (m[0]) {
        case 'ready':
          if (!ready.isCompleted) ready.complete(m[1] as SendPort);
        case 'err':
          if (!ready.isCompleted) {
            ready.completeError(StateError(m[1] as String));
          }
        case 'p':
          if (_running) _onPartial?.call(m[1] as String);
        case 'e':
          if (_running) _onEndpoint?.call(m[1] as String);
      }
    });
    _fromWorker = fromWorker;
    _isolate = await Isolate.spawn(_asrWorker, <Object>[
      fromWorker.sendPort,
      encoder,
      decoder,
      joiner,
      tokens,
      modelType,
      _numThreads,
      _rule1,
      _rule2,
      _rule3,
    ]);
    _toWorker = await ready.future.timeout(const Duration(seconds: 60));
  }

  /// Copies the bundled model into app storage (once per [_assetVersion]).
  Future<Directory> _prepareModelDir() async {
    final base = await getApplicationSupportDirectory();
    final dir = Directory(p.join(base.path, 'asr_model'));
    final marker = File(p.join(dir.path, '.ready_$_assetVersion'));
    if (await marker.exists()) return dir;

    final manifest = await AssetManifest.loadFromAssetBundle(rootBundle);
    final keys =
        manifest.listAssets().where((k) => k.startsWith(assetDir)).toList();
    if (keys.isEmpty) {
      throw StateError('找不到识别模型：请把模型文件放进 assets/asr/ 并在 pubspec.yaml 里声明');
    }
    if (await dir.exists()) await dir.delete(recursive: true);
    await dir.create(recursive: true);
    for (final key in keys) {
      final data = await rootBundle.load(key);
      await File(p.join(dir.path, p.basename(key))).writeAsBytes(
        data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
        flush: true,
      );
    }
    await marker.writeAsString('ok');
    return dir;
  }
}

Float32List _pcm16ToFloat32(Uint8List bytes) {
  final d = ByteData.sublistView(bytes);
  final n = bytes.length ~/ 2;
  final out = Float32List(n);
  for (var i = 0; i < n; i++) {
    out[i] = d.getInt16(i * 2, Endian.little) / 32768.0;
  }
  return out;
}

/// Decoder isolate. Receives PCM16 chunks, replies with
/// ['p', partialText] / ['e', endpointText]; handshake: ['ready', SendPort].
void _asrWorker(List<Object> args) {
  final reply = args[0] as SendPort;
  try {
    sherpa.initBindings();
    final recognizer = sherpa.OnlineRecognizer(sherpa.OnlineRecognizerConfig(
      model: sherpa.OnlineModelConfig(
        transducer: sherpa.OnlineTransducerModelConfig(
          encoder: args[1] as String,
          decoder: args[2] as String,
          joiner: args[3] as String,
        ),
        tokens: args[4] as String,
        modelType: args[5] as String,
        numThreads: args[6] as int,
        debug: false,
      ),
      ruleFsts: '',
      enableEndpoint: true,
      rule1MinTrailingSilence: args[7] as double,
      rule2MinTrailingSilence: args[8] as double,
      rule3MinUtteranceLength: args[9] as double,
    ));
    var stream = recognizer.createStream();
    var last = '';
    final inbox = ReceivePort();
    inbox.listen((m) {
      if (m is TransferableTypedData) {
        final samples = _pcm16ToFloat32(m.materialize().asUint8List());
        stream.acceptWaveform(samples: samples, sampleRate: 16000);
        while (recognizer.isReady(stream)) {
          recognizer.decode(stream);
        }
        final text = recognizer.getResult(stream).text.trim();
        if (text.isNotEmpty && text != last) {
          last = text;
          reply.send(['p', text]);
        }
        if (recognizer.isEndpoint(stream)) {
          recognizer.reset(stream);
          last = '';
          reply.send(['e', text]);
        }
      } else if (m == 'reset') {
        stream.free();
        stream = recognizer.createStream();
        last = '';
      } else if (m == 'dispose') {
        stream.free();
        recognizer.free();
        inbox.close();
      }
    });
    reply.send(['ready', inbox.sendPort]);
  } catch (e) {
    reply.send(['err', e.toString()]);
  }
}
