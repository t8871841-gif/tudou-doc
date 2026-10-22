/// 一键急停的取消信号（v3）。
///
/// 来源：悬浮球双击 / 音量键组合 / 语音“停”。agent 循环（AgentLoop、_executeBoard、
/// 视觉看屏）在每一轮动作前都调用 [throwIfStopped]，收到信号就抛 [AgentStopped]，
/// 不会再执行下一步。新任务开始时调用 [reset] 清除信号。
class AgentStopped implements Exception {
  @override
  String toString() => '已被急停中断';
}

class AgentControl {
  static bool _stopped = false;
  static DateTime? _at;

  static bool get stopped => _stopped;
  static DateTime? get stoppedAt => _at;

  static void reset() => _stopped = false;

  static void stopAll() {
    _stopped = true;
    _at = DateTime.now();
  }

  static void throwIfStopped() {
    if (_stopped) throw AgentStopped();
  }
}
