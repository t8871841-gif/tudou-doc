import 'dart:async';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import '../services/app_status.dart';
import '../services/local_llm_service.dart';
import '../services/native_bridge.dart';
import '../services/standby_meter.dart';
import '../services/settings_store.dart';
import 'audit_screen.dart';

/// 功能自检（v3 第 1 项）：逐项列出关键能力的状态（可用 / 不可用 / 未授权），
/// 不可用的写明原因，并给出去开启的入口。目的：真机回归不用猜哪坏了。
/// 原生能直接判断的项来自 SystemBridge.selfCheck；本地模型加载、唤醒监听由 Dart 侧补充。
enum _St { ok, bad, perm }

class _Item {
  final String name;
  final _St st;
  final String detail;
  final String? actionLabel;
  final Future<void> Function()? action;
  const _Item(this.name, this.st, this.detail, {this.actionLabel, this.action});
}

class DiagnosticsScreen extends StatefulWidget {
  final JarvisSettings settings;
  const DiagnosticsScreen({super.key, required this.settings});

  @override
  State<DiagnosticsScreen> createState() => _DiagnosticsScreenState();
}

class _DiagnosticsScreenState extends State<DiagnosticsScreen> {
  List<_Item> _items = const [];
  bool _loading = true;
  String _info = '';
  String _modelNote = '';

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  Future<void> _refresh() async {
    setState(() => _loading = true);
    final m = await NativeBridge.selfCheck();
    final s = widget.settings;
    bool b(String k) => m[k] == true;
    final brand = (m['brand'] ?? '').toString();
    final flyme = brand.toLowerCase().contains('meizu');
    final a11y = b('accessibility');
    final meter = await StandbyMeter.report();
    final calOk = (await Permission.calendar.status).isGranted;
    const sensNames = ['严格', '标准', '宽松'];
    final tighten = AppStatus.tightenUntil != null && DateTime.now().isBefore(AppStatus.tightenUntil!);
    final modelReady = s.localEnabled && s.localModelPath.isNotEmpty;
    final items = <_Item>[
      _Item('无障碍服务', a11y ? _St.ok : _St.bad,
          a11y
              ? '运行中（看屏、悬浮球、音量键急停都依赖它）。升级到新版本后，需把它关掉再打开一次，新增的按键权限才会生效。'
              : '没有运行：看屏、悬浮球、音量键急停都不可用。',
          actionLabel: a11y ? null : '去开启', action: a11y ? null : NativeBridge.openAccessibilitySettings),
      _Item(
          '悬浮球',
          !b('orbEnabled') ? _St.bad : (a11y ? _St.ok : _St.bad),
          !b('orbEnabled')
              ? '未启用（设置 > 常驻与入口 > 悬浮球）。'
              : (a11y ? '已启用；Jarvis 在后台时显示，双击急停。' : '已启用，但无障碍服务没运行，球显示不出来。')),
      _Item(
          '后台常驻（前台服务）',
          !b('mic') ? _St.perm : (b('keepAlive') ? _St.ok : _St.bad),
          !b('mic')
              ? '没有录音权限，前台服务（麦克风类型）无法启动。'
              : (b('keepAlive')
                  ? '运行中。'
                  : '没有运行（设置 > 常驻与入口 > 后台常驻待命）。没开时，点悬浮球会先把 Jarvis 拉到前台再聆听。')),
      _Item('电池优化豁免', b('batteryIgnoring') ? _St.ok : _St.bad,
          b('batteryIgnoring') ? '已忽略电池优化。' : '${flyme ? 'Flyme 清后台比较激进，' : ''}建议忽略电池优化，否则常驻服务可能被杀。',
          actionLabel: b('batteryIgnoring') ? null : '去设置', action: b('batteryIgnoring') ? null : NativeBridge.requestIgnoreBattery),
      _Item(
          '默认助手入口（电源键长按）',
          m['defaultAssistant'] == 'yes' ? _St.ok : _St.bad,
          m['defaultAssistant'] == 'yes'
              ? 'Jarvis 已是数字助理。'
              : (m['defaultAssistant'] == 'no'
                  ? '当前默认助手不是 Jarvis。${flyme ? 'Flyme 的电源键入口可能被系统助手占用，选不到时请用悬浮球或快捷设置磁贴。' : ''}'
                  : '系统不让应用读取这个设置，无法判断；请到系统里确认。'),
          actionLabel: m['defaultAssistant'] == 'yes' ? null : '去设置', action: m['defaultAssistant'] == 'yes' ? null : NativeBridge.openAssistantSettings),
      _Item('通知读取', b('notificationAccess') ? _St.ok : _St.perm,
          b('notificationAccess') ? '已授权。' : '未授权：读通知不可用。',
          actionLabel: b('notificationAccess') ? null : '去授权', action: b('notificationAccess') ? null : NativeBridge.openNotificationAccess),
      _Item('通知权限（提醒 / 晨报）', b('postNotifications') ? _St.ok : _St.perm,
          b('postNotifications') ? '已允许。' : '未允许：提醒、晨报无法弹出。',
          actionLabel: b('postNotifications') ? null : '去允许', action: b('postNotifications') ? null : () async { await Permission.notification.request(); }),
      _Item('麦克风权限', b('mic') ? _St.ok : _St.perm, b('mic') ? '已授权。' : '未授权：语音、唤醒都不可用。',
          actionLabel: b('mic') ? null : '去授权', action: b('mic') ? null : () async { await Permission.microphone.request(); }),
      _Item(
          '唤醒监听',
          AppStatus.wakeListening ? _St.ok : _St.bad,
          '${AppStatus.wakeListening ? '正在监听“贾维斯”' : '当前没有在监听（设置 > 语音唤醒，或主界面唤醒词开关）'}；'
          '灵敏度：${sensNames[s.wakeSensitivity.clamp(0, 2)]}${tighten ? '（误唤醒较多，已临时调严）' : ''}。'
          '本次运行唤醒 ${AppStatus.wakeCount} 次，误唤醒 ${AppStatus.falseWakeCount} 次。'
          '熄屏 / 后台能否唤醒取决于“后台常驻”是否在运行。\n待命耗电实测：$meter'),
      _Item(
          '来电 / 短信 / 通知播报',
          !s.announceOn ? _St.bad : (b('notificationAccess') ? (b('keepAlive') ? _St.ok : _St.bad) : _St.perm),
          !s.announceOn
              ? '未启用（设置 > 主动播报与出发提醒）。'
              : (!b('notificationAccess')
                  ? '没有“通知读取”授权，收不到通知。'
                  : (b('keepAlive')
                      ? '已启用，后台常驻在运行；勿扰时段 ${s.quietStart}–${s.quietEnd} 内不播，验证码类不读内容。'
                      : '已启用，但后台常驻没运行：Jarvis 不在前台时可能收不到、播不了。')),
          actionLabel: s.announceOn && !b('notificationAccess') ? '去授权' : null,
          action: s.announceOn && !b('notificationAccess') ? NativeBridge.openNotificationAccess : null),
      _Item(
          '日程出发提醒',
          !s.departRemind ? _St.bad : (calOk ? (b('postNotifications') ? _St.ok : _St.perm) : _St.perm),
          !s.departRemind
              ? '未启用（设置 > 主动播报与出发提醒）。'
              : (!calOk
                  ? '没有日历读取权限。'
                  : (!b('postNotifications')
                      ? '没有通知权限，提醒弹不出来。'
                      : '已启用：按“有定位且能查到地点→直线距离粗估，否则固定 ${s.departLeadMin} 分钟”提前提醒；当天需打开过 Jarvis 才会排程。')),
          actionLabel: s.departRemind && !calOk ? '去授权' : null,
          action: s.departRemind && !calOk ? () async { await Permission.calendar.request(); } : null),
      _Item(
          '打断说话（降级版）',
          !s.bargeIn ? _St.bad : (b('mic') ? _St.ok : _St.perm),
          !s.bargeIn
              ? '未启用（设置 > 语音：唤醒与打断）。'
              : (!b('mic')
                  ? '没有录音权限，无法在播报时监听。'
                  : '已启用（实现等级 B：平台回声消除 + 文本比对，不是完整 AEC）。说“停”识别到即停；说别的需持续约 0.35 秒。'
                      '本次运行打断 ${AppStatus.bargeCount} 次。平台 AEC 是否生效取决于机型，未实测。')),
      _Item(
          '视觉看屏（截图 + 多模态）',
          s.visionConfigured && a11y && (m['lastCaptureError'] ?? '').toString().isEmpty ? _St.ok : _St.bad,
          !s.visionEnabled
              ? '未启用（设置 > 视觉看屏）。'
              : (!s.visionConfigured
                  ? '已启用但没有可用的 Key。'
                  : (!a11y
                      ? '无障碍服务没运行，无法截图。'
                      : ((m['lastCaptureError'] ?? '').toString().isEmpty
                          ? '已配置。截图 / 坐标点击需要升级后重新开关一次无障碍服务才有权限；最近一次截图没有报错。'
                          : '最近一次截图失败：${m['lastCaptureError']}（self=Jarvis 在前台；blocked_app=黑名单界面；secure_window=防截屏；unknown_app=无法确认前台）。')))),
      _Item(
          '本地模型',
          modelReady ? _St.ok : _St.bad,
          !s.localEnabled
              ? '未启用（设置 > 端侧小模型）。'
              : (s.localModelPath.isEmpty
                  ? '已启用但没有导入模型文件。'
                  : '已配置：${s.localModelPath.split('/').last}。是否真的能加载，请点右侧“加载测试”。$_modelNote'),
          actionLabel: modelReady ? '加载测试' : null,
          action: modelReady ? _testLocalModel : null),
    ];
    if (!mounted) return;
    setState(() {
      _items = items;
      _info = 'Android SDK ${m['sdk'] ?? '?'} · $brand';
      _loading = false;
    });
  }

  Future<void> _testLocalModel() async {
    setState(() => _modelNote = '（加载中，可能要几十秒…）');
    LocalLlmService.resetFailure();
    final sw = Stopwatch()..start();
    final ok = await LocalLlmService.ensureLoaded(widget.settings);
    sw.stop();
    _modelNote = ok ? '（加载成功，用时 ${sw.elapsed.inSeconds} 秒）' : '（加载失败：文件损坏 / 格式不对 / 内存不足 / 依赖版本不匹配）';
  }

  Color _color(_St s) => s == _St.ok ? const Color(0xFF43C463) : (s == _St.perm ? const Color(0xFFFFA726) : const Color(0xFFEF5350));
  String _label(_St s) => s == _St.ok ? '可用' : (s == _St.perm ? '未授权' : '不可用');

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0B1020),
      appBar: AppBar(
        backgroundColor: const Color(0xFF0B1020),
        foregroundColor: Colors.white,
        title: const Text('功能自检'),
        actions: [IconButton(onPressed: _refresh, icon: const Icon(Icons.refresh))],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(padding: const EdgeInsets.all(16), children: [
              Text(_info, style: const TextStyle(color: Colors.white54, fontSize: 12)),
              const SizedBox(height: 8),
              for (final it in _items)
                Card(
                  color: const Color(0xFF141B33),
                  child: ListTile(
                    title: Row(children: [
                      Expanded(child: Text(it.name, style: const TextStyle(color: Colors.white))),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                        decoration: BoxDecoration(color: _color(it.st).withOpacity(0.18), borderRadius: BorderRadius.circular(10)),
                        child: Text(_label(it.st), style: TextStyle(color: _color(it.st), fontSize: 12)),
                      ),
                    ]),
                    subtitle: Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text(it.detail, style: const TextStyle(color: Colors.white60, fontSize: 12)),
                    ),
                    trailing: it.actionLabel == null
                        ? null
                        : TextButton(
                            onPressed: () async {
                              await it.action?.call();
                              await _refresh();
                            },
                            child: Text(it.actionLabel!)),
                  ),
                ),
              const SizedBox(height: 8),
              OutlinedButton(
                  onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const AuditScreen())),
                  child: const Text('查看操作日志')),
            ]),
    );
  }
}
