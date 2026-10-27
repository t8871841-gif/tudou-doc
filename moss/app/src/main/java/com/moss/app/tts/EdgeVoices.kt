// Moss: ported from Jarvis (same author, same device). Edge voice protocol support; no key needed. See NOTICE.md.

package com.moss.app.tts

/**
 * 批二新增：设置页"播报声音"下拉框用的候选列表。
 *
 * 这几个都是微软 Edge Read Aloud 公开、长期稳定提供的 zh-CN 神经网络语音 ID，
 * 不是从 Flutter 原版 `EdgeTtsService.voices` 抄来的（那份定义不在这次给的参考文件里），
 * 是按公开资料整理的一份功能等价清单。如果 Flutter 原版的列表、顺序、默认项跟这里不一样，
 * 把原始定义发我，我再对齐。
 */
object EdgeVoices {
    data class Voice(val id: String, val label: String)

    val LIST = listOf(
        Voice("zh-CN-YunyangNeural", "云扬（男声 · 新闻播报风）"),
        Voice("zh-CN-XiaoxiaoNeural", "晓晓（女声 · 温和）"),
        Voice("zh-CN-YunxiNeural", "云希（男声 · 青年）"),
        Voice("zh-CN-YunjianNeural", "云健（男声 · 沉稳）"),
        Voice("zh-CN-XiaoyiNeural", "晓伊（女声 · 活泼）"),
        Voice("zh-CN-XiaomoNeural", "晓墨（女声 · 温柔）"),
    )

    const val DEFAULT_ID = "zh-CN-YunyangNeural"

    fun labelFor(id: String): String = LIST.firstOrNull { it.id == id }?.label ?: id
}
