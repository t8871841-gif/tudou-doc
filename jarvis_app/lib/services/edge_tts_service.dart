import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

/// Microsoft Edge neural TTS — free, no API key required.
///
/// Uses a hand-rolled WebSocket client over a raw TLS socket because the
/// service answers the upgrade with `Connection: close`, which strict
/// WebSocket clients reject even though the upgrade itself succeeds (101).
class EdgeTtsService {
  static const _host = 'speech.platform.bing.com';
  // Public client token shipped with the open-source edge-tts project.
  static const _token = '6A5AA1D4EAFF4E9FB37E23D68491D6F4';
  static const _chromiumMajor = '143';

  /// Chinese neural voices offered in the app.
  /// Male voices first: Jarvis uses a male voice by default.
  static const voices = <Map<String, String>>[
    {'id': 'zh-CN-YunyangNeural', 'label': '云扬（男声·专业）'},
    {'id': 'zh-CN-YunjianNeural', 'label': '云健（男声·沉稳）'},
    {'id': 'zh-CN-YunxiNeural', 'label': '云希（男声·活泼）'},
    {'id': 'zh-CN-XiaoxiaoNeural', 'label': '晓晓（女声·温柔）'},
    {'id': 'zh-CN-XiaoyiNeural', 'label': '晓伊（女声·俏皮）'},
    {'id': 'zh-CN-XiaochenNeural', 'label': '晓辰（女声·年轻）'},
  ];

  static const defaultVoice = 'zh-CN-YunyangNeural';

  /// Synthesizes [text] to MP3 bytes (24kHz 48kbps mono).
  /// Long texts are split into chunks and the resulting MP3 frames concatenated.
  static Future<Uint8List> synthesize(
    String text, {
    required String voice,
    double rate = 0.48,
    double pitch = 1.0,
  }) async {
    final out = BytesBuilder();
    for (final chunk in _chunks(text)) {
      out.add(await _synthesizeChunk(chunk, voice: voice, rate: rate, pitch: pitch)
          .timeout(const Duration(seconds: 45)));
    }
    return out.toBytes();
  }

  static List<String> _chunks(String text, [int max = 1200]) {
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

  static Future<Uint8List> _synthesizeChunk(
    String text, {
    required String voice,
    required double rate,
    required double pitch,
  }) async {
    final conn = await _EdgeConnection.connect();
    try {
      final date = _dateString();
      conn.sendText('X-Timestamp:$date\r\n'
          'Content-Type:application/json; charset=utf-8\r\n'
          'Path:speech.config\r\n\r\n'
          '{"context":{"synthesis":{"audio":{"metadataoptions":{'
          '"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},'
          '"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}\r\n');
      final reqId = _hexId();
      conn.sendText('X-RequestId:$reqId\r\n'
          'Content-Type:application/ssml+xml\r\n'
          'X-Timestamp:${date}Z\r\n'
          'Path:ssml\r\n\r\n'
          '${_ssml(text, voice, rate, pitch)}');
      // 先收齐所有二进制负载，结束后再统一解析，避免音频头被拆到两个包导致越界
      final binAll = BytesBuilder();
      var gotAudio = false;
      while (true) {
        final (opcode, payload) = await conn.readFrame();
        if (opcode == 0x2) {
          if (payload.isNotEmpty) {
            binAll.add(payload);
            gotAudio = true;
          }
        } else if (opcode == 0x1) {
          final msg = utf8.decode(payload, allowMalformed: true);
          if (msg.contains('turn.end')) break;
        } else if (opcode == 0x8) {
          break;
        }
      }
      final audio = _extractAudio(binAll.toBytes());
      if (!gotAudio || audio.isEmpty) throw StateError('Edge 语音未返回音频');
      return audio;
    } finally {
      conn.close();
    }
  }

  /// 从累积的二进制负载中提取音频：按 Path:audio 头切分，取每段头之后到下一个头之前的数据
  static Uint8List _extractAudio(Uint8List data) {
    final hdr = utf8.encode('Path:audio\r\n');
    final out = BytesBuilder();
    var pos = 0;
    while (true) {
      final h = _indexOfFrom(data, hdr, pos);
      if (h < 0) break;
      final segStart = h + hdr.length;
      final nextH = _indexOfFrom(data, hdr, segStart);
      final segEnd = nextH >= 0 ? nextH : data.length;
      if (segEnd > segStart) out.add(data.sublist(segStart, segEnd));
      if (nextH < 0) break;
      pos = segStart;
    }
    return out.toBytes();
  }

  static int _indexOfFrom(Uint8List haystack, List<int> needle, int from) {
    outer:
    for (var i = from; i <= haystack.length - needle.length; i++) {
      for (var j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) continue outer;
      }
      return i;
    }
    return -1;
  }

  static String _ssml(String text, String voice, double rate, double pitch) {
    final esc = text
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;');
    final ratePct = ((rate - 0.5) / 0.5 * 50).round().clamp(-50, 100);
    final pitchHz = ((pitch - 1.0) * 100).round().clamp(-100, 200);
    final rateStr = '${ratePct >= 0 ? '+' : ''}$ratePct%';
    final pitchStr = '${pitchHz >= 0 ? '+' : ''}${pitchHz}Hz';
    return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>"
        "<voice name='$voice'><prosody rate='$rateStr' pitch='$pitchStr'>$esc</prosody></voice></speak>";
  }

  /// Sec-MS-GEC token: SHA256 hex (upper) of Windows-ticks-rounded-to-5min + token.
  static String _secMsGec() {
    var ticks = DateTime.now().toUtc().millisecondsSinceEpoch / 1000 + 11644473600;
    ticks -= ticks % 300;
    final winTicks = (ticks * 1e7).toInt();
    return sha256.convert(utf8.encode('$winTicks$_token')).toString().toUpperCase();
  }

  static String _hexId() {
    final r = Random.secure();
    return List.generate(32, (_) => '0123456789abcdef'[r.nextInt(16)]).join();
  }

  static String _dateString() {
    const wk = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    const mo = [
      'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
      'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'
    ];
    final n = DateTime.now().toUtc();
    String d2(int v) => v.toString().padLeft(2, '0');
    return '${wk[n.weekday - 1]} ${mo[n.month - 1]} ${d2(n.day)} ${n.year} '
        '${d2(n.hour)}:${d2(n.minute)}:${d2(n.second)} '
        'GMT+0000 (Coordinated Universal Time)';
  }

  static int _indexOf(Uint8List haystack, List<int> needle) {
    outer:
    for (var i = 0; i <= haystack.length - needle.length; i++) {
      for (var j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) continue outer;
      }
      return i;
    }
    return -1;
  }
}

class _EdgeConnection {
  final SecureSocket _socket;
  final List<int> _buf = [];
  late final StreamIterator<List<int>> _it;

  _EdgeConnection._(this._socket) {
    _it = StreamIterator(_socket);
  }

  static Future<_EdgeConnection> connect() async {
    final raw = await Socket.connect(EdgeTtsService._host, 443,
        timeout: const Duration(seconds: 15));
    final socket = await SecureSocket.secure(raw, host: EdgeTtsService._host);
    final conn = _EdgeConnection._(socket);
    final gec = EdgeTtsService._secMsGec();
    final key = base64Encode(
        List<int>.generate(16, (_) => Random.secure().nextInt(256)));
    final path = '/consumer/speech/synthesize/readaloud/edge/v1'
        '?TrustedClientToken=${EdgeTtsService._token}'
        '&ConnectionId=${EdgeTtsService._hexId()}'
        '&Sec-MS-GEC=$gec'
        '&Sec-MS-GEC-Version=1-${EdgeTtsService._chromiumMajor}.0.3650.75';
    socket.write('GET $path HTTP/1.1\r\n'
        'Host: ${EdgeTtsService._host}\r\n'
        'Upgrade: websocket\r\n'
        'Connection: Upgrade\r\n'
        'Sec-WebSocket-Key: $key\r\n'
        'Sec-WebSocket-Version: 13\r\n'
        'Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n'
        'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
        '(KHTML, like Gecko) Chrome/${EdgeTtsService._chromiumMajor}.0.0.0 '
        'Safari/537.36 Edg/${EdgeTtsService._chromiumMajor}.0.0.0\r\n'
        '\r\n');
    await socket.flush();
    final head = await conn._readUntil(utf8.encode('\r\n\r\n'));
    final statusLine = utf8.decode(head, allowMalformed: true).split('\r\n').first;
    if (!statusLine.contains('101')) {
      conn.close();
      throw StateError('Edge 语音握手失败：$statusLine');
    }
    return conn;
  }

  Future<Uint8List> _take(int n) async {
    while (_buf.length < n) {
      if (!await _it.moveNext()) throw StateError('Edge 语音连接已关闭');
      _buf.addAll(_it.current);
    }
    final out = Uint8List.fromList(_buf.sublist(0, n));
    _buf.removeRange(0, n);
    return out;
  }

  Future<Uint8List> _readUntil(List<int> delim) async {
    while (true) {
      final idx = EdgeTtsService._indexOf(Uint8List.fromList(_buf), delim);
      if (idx >= 0) {
        final out = Uint8List.fromList(_buf.sublist(0, idx + delim.length));
        _buf.removeRange(0, idx + delim.length);
        return out;
      }
      if (!await _it.moveNext()) throw StateError('Edge 语音连接已关闭');
      _buf.addAll(_it.current);
    }
  }

  /// Sends a masked client-to-server text frame.
  void sendText(String text) {
    final data = utf8.encode(text);
    final rng = Random.secure();
    final mask = List<int>.generate(4, (_) => rng.nextInt(256));
    final frame = BytesBuilder();
    frame.addByte(0x81);
    if (data.length < 126) {
      frame.addByte(0x80 | data.length);
    } else if (data.length < 65536) {
      frame.addByte(0x80 | 126);
      frame.addByte((data.length >> 8) & 0xFF);
      frame.addByte(data.length & 0xFF);
    } else {
      frame.addByte(0x80 | 127);
      for (var i = 7; i >= 0; i--) {
        frame.addByte((data.length >> (8 * i)) & 0xFF);
      }
    }
    frame.add(mask);
    for (var i = 0; i < data.length; i++) {
      frame.addByte(data[i] ^ mask[i % 4]);
    }
    _socket.add(frame.toBytes());
  }

  /// Returns (opcode, payload); server frames are unmasked.
  Future<(int, Uint8List)> readFrame() async {
    final h = await _take(2);
    final opcode = h[0] & 0x0F;
    var len = h[1] & 0x7F;
    if (len == 126) {
      final e = await _take(2);
      len = (e[0] << 8) | e[1];
    } else if (len == 127) {
      final e = await _take(8);
      len = 0;
      for (final b in e) {
        len = (len << 8) | b;
      }
    }
    if ((h[1] & 0x80) != 0) {
      final mask = await _take(4);
      final payload = await _take(len);
      for (var i = 0; i < payload.length; i++) {
        payload[i] ^= mask[i % 4];
      }
      return (opcode, payload);
    }
    return (opcode, await _take(len));
  }

  void close() {
    try {
      _socket.destroy();
    } catch (_) {}
  }
}
