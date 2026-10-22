import 'package:flutter/material.dart';

import '../services/audit_log.dart';

/// 操作日志（v3 第 2 项）：回看每次任务做了什么、核验结果；可清空。
class AuditScreen extends StatefulWidget {
  const AuditScreen({super.key});

  @override
  State<AuditScreen> createState() => _AuditScreenState();
}

class _AuditScreenState extends State<AuditScreen> {
  List<AuditEntry> _list = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final l = await AuditLog.load();
    if (mounted) setState(() { _list = l; _loading = false; });
  }

  String _t(DateTime d) {
    String two(int v) => v.toString().padLeft(2, '0');
    return '${two(d.month)}-${two(d.day)} ${two(d.hour)}:${two(d.minute)}:${two(d.second)}';
  }

  @override
  Widget build(BuildContext context) {
    // 按任务分组（同一条 task 文本的连续条目归一组；新的在前）。
    final groups = <String, List<AuditEntry>>{};
    for (final e in _list) {
      groups.putIfAbsent('${e.task}', () => []).add(e);
    }
    return Scaffold(
      backgroundColor: const Color(0xFF0B1020),
      appBar: AppBar(
        backgroundColor: const Color(0xFF0B1020),
        foregroundColor: Colors.white,
        title: const Text('操作日志'),
        actions: [
          IconButton(
            tooltip: '清空',
            icon: const Icon(Icons.delete_outline),
            onPressed: _list.isEmpty
                ? null
                : () async {
                    final ok = await showDialog<bool>(
                        context: context,
                        builder: (c) => AlertDialog(
                              title: const Text('清空操作日志？'),
                              actions: [
                                TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('取消')),
                                TextButton(onPressed: () => Navigator.pop(c, true), child: const Text('清空')),
                              ],
                            ));
                    if (ok == true) {
                      await AuditLog.clear();
                      await _load();
                    }
                  },
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _list.isEmpty
              ? const Center(child: Text('还没有记录。', style: TextStyle(color: Colors.white54)))
              : ListView(padding: const EdgeInsets.all(12), children: [
                  const Text('验证码、密码、长数字已脱敏；看屏“输入”只记字数。',
                      style: TextStyle(color: Colors.white38, fontSize: 12)),
                  for (final g in groups.entries)
                    Card(
                      color: const Color(0xFF141B33),
                      child: Padding(
                        padding: const EdgeInsets.all(12),
                        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          Text(g.key.isEmpty ? '（无任务描述）' : g.key,
                              style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600)),
                          const SizedBox(height: 6),
                          // 组内是新→旧，展示时反过来：按时间顺序读一条链路
                          for (final e in g.value.reversed)
                            Padding(
                              padding: const EdgeInsets.symmetric(vertical: 3),
                              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                                Icon(
                                    e.ok == null ? Icons.circle_outlined : (e.ok! ? Icons.check_circle : Icons.cancel),
                                    size: 16,
                                    color: e.ok == null ? Colors.white38 : (e.ok! ? const Color(0xFF43C463) : const Color(0xFFEF5350))),
                                const SizedBox(width: 8),
                                Expanded(
                                  child: Text('${_t(e.time)}  ${e.action}${e.detail.isEmpty ? '' : '\n${e.detail}'}',
                                      style: const TextStyle(color: Colors.white70, fontSize: 12)),
                                ),
                              ]),
                            ),
                        ]),
                      ),
                    ),
                ]),
    );
  }
}
