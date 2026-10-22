/// 运行时状态（给功能自检页读）：由主界面同步，不持久化。
class AppStatus {
  static bool wakeListening = false; // 唤醒词待命是否正在监听
  static bool asrRunning = false; // 识别器是否在跑
  static int bargeCount = 0; // 本次运行打断播报次数
  static int wakeCount = 0; // 本次运行唤醒词命中次数
  static int falseWakeCount = 0; // 本次运行误唤醒次数（唤醒后没有有效指令）
  static DateTime? lastWakeAt;
  static DateTime? tightenUntil; // 误唤醒过多时临时调严到的时间
}
