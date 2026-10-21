import 'dart:async';

import 'package:flutter/services.dart';

import 'settings_store.dart';

/// 端侧小模型（MediaPipe LLM Inference，原生层实现，见 LocalLlmBridge.kt）。
///
/// 定位：简单任务（寒暄 / 闲聊 / 短改写 / 晨报润色）本地答，零延迟、零流量；
/// 云端不可用（断网 / 没配 Key）时也兜底聊天。复杂任务（规划、看屏决策、
/// 联网综合、长推理、记忆整理）一律走云端大模型，不交给小模型。
/// 模型文件需用户自己导入（设置里“选择模型文件”），App 不内置、不联网下载。
class LocalLlmService {
  static const _ch = MethodChannel('jarvis/localllm');
  static String? _loadedPath;
  static bool _failed = false; // 本次运行加载失败过：不再反复尝试，直接走云端

  static bool usable(JarvisSettings s) =>
      s.localEnabled && s.localModelPath.trim().isNotEmpty && !_failed;

  static void resetFailure() => _failed = false;

  static Future<bool> ensureLoaded(JarvisSettings s) async {
    final p = s.localModelPath.trim();
    if (p.isEmpty) return false;
    if (_loadedPath == p) return true;
    try {
      final ok = await _ch
              .invokeMethod<bool>('load', {'path': p, 'maxTokens': 1024})
              .timeout(const Duration(seconds: 90)) ??
          false;
      if (ok) {
        _loadedPath = p;
        _failed = false;
      } else {
        _failed = true;
      }
      return ok;
    } catch (_) {
      _failed = true;
      return false;
    }
  }

  /// 本地生成。任何失败返回 null（调用方回退云端），不抛异常。
  static Future<String?> generate(
    JarvisSettings s, {
    required String system,
    required List<Map<String, String>> history,
    required String user,
  }) async {
    if (!usable(s)) return null;
    if (!await ensureLoaded(s)) return null;
    final prompt = formatPrompt(s.localModelPath, system, history, user);
    try {
      final out = await _ch
          .invokeMethod<String>('generate', {'prompt': prompt})
          .timeout(const Duration(seconds: 60));
      return _clean(out);
    } catch (_) {
      return null;
    }
  }

  static Future<void> unload() async {
    _loadedPath = null;
    try {
      await _ch.invokeMethod('unload');
    } catch (_) {}
  }

  /// 调系统文件选择器，把模型复制进 App 私有目录，返回新路径（取消 / 失败返回 null）。
  static Future<String?> importModel() async {
    try {
      final p = await _ch.invokeMethod<String>('importModel');
      _failed = false;
      _loadedPath = null;
      return p;
    } catch (_) {
      return null;
    }
  }

  static String? _clean(String? t) {
    if (t == null) return null;
    final c = t
        .replaceAll(RegExp(r'<end_of_turn>|<\|im_end\|>|<eos>|<\|endoftext\|>'), '')
        .trim();
    return c.length < 2 ? null : c;
  }

  /// 按模型文件名选对话模板：含 qwen → ChatML，其余按 Gemma 模板。
  static String formatPrompt(String modelPath, String system,
      List<Map<String, String>> history, String user) {
    final name = modelPath.toLowerCase();
    final h = history.length > 6 ? history.sublist(history.length - 6) : history;
    final sb = StringBuffer();
    if (name.contains('qwen') || name.contains('chatml')) {
      sb.write('<|im_start|>system\n$system<|im_end|>\n');
      for (final m in h) {
        final r = m['role'] == 'assistant' ? 'assistant' : 'user';
        sb.write('<|im_start|>$r\n${m['content']}<|im_end|>\n');
      }
      sb.write('<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n');
    } else {
      var sysPending = system;
      void turn(String role, String text) {
        final body = sysPending.isNotEmpty && role == 'user'
            ? '$sysPending\n\n$text'
            : text;
        if (role == 'user') sysPending = '';
        sb.write('<start_of_turn>$role\n$body<end_of_turn>\n');
      }

      for (final m in h) {
        turn(m['role'] == 'assistant' ? 'model' : 'user', m['content'] ?? '');
      }
      turn('user', user);
      sb.write('<start_of_turn>model\n');
    }
    return sb.toString();
  }
}
