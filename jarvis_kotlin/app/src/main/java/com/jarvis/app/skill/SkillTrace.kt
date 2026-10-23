package com.jarvis.app.skill

/**
 * 批四：点按轨迹采集。ScreenVisionAgent 每次点按成功都记一笔目标描述，
 * 设置页「把最近操作存为技能」把这串轨迹转成 tap 步骤。仅内存保存，进程重启即清。
 */
object SkillTrace {
    private val taps = mutableListOf<String>()

    @Synchronized
    fun recordTap(target: String) {
        if (target.isNotBlank()) taps.add(target)
    }

    @Synchronized
    fun snapshot(): List<String> = taps.toList()

    @Synchronized
    fun clear() = taps.clear()

    @Synchronized
    fun size(): Int = taps.size
}
