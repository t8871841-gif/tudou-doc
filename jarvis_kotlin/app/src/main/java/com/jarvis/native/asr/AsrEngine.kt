package com.jarvis.native.asr

/**
 * 移植自参考实现（原 net.kuafuai.andee.asr.AsrEngine，仅改包名，逻辑未改动）。
 * 一次识别会话的通用形状：开会话、边到边喂 PCM、告诉它"这轮说完了"、等最终结果。
 * 本工程目前只接了 sherpa-onnx 这一个后端（见 OfflineAsr.kt），接口留着是因为
 * 原实现就是这样分的层，以后如果要接别的识别后端，照这个形状实现就行。
 */
interface AsrEngine {

    /** waitFinished 应该等多久——由引擎自己说了算，不同后端差异很大。 */
    val finalWaitMs: Long

    /** 调用方自己的"安静多久算说完"判断窗口；本地识别收敛快，这个值主要是上层的兜底。 */
    val silenceMs: Long

    interface Listener {
        /** 引擎已连接/已就绪，可以开始算安静计时了（在此之前不能算，不然容易把初始化耗时误判成用户沉默）。 */
        fun onReady() {}

        /** 目前为止听到的内容，仅供展示，不作为最终结果。说话过程中会多次回调。 */
        fun onPartial(text: String)

        /** 这一轮最终结果。空字符串代表什么都没听到。 */
        fun onFinal(text: String)

        fun onError(msg: String)

        /** 底层连接/会话已经结束。 */
        fun onClosed()
    }

    /** 这一轮是怎么结束的；本地引擎的结束信号完全由 sendAudio(isLast=true) 驱动，不需要这个，留空实现即可。 */
    fun endOfTurn(bySilence: Boolean) = Unit

    fun start(sampleRate: Int, channels: Int)

    /** 一块 16bit 小端 PCM；isLast=true 时 pcm 被忽略，表示这一轮说完了。调用方（采集线程）不能被这里阻塞。 */
    fun sendAudio(pcm: ByteArray, isLast: Boolean)

    /** 阻塞等到最终结果到达，或超时。 */
    fun waitFinished(timeoutMs: Long): Boolean

    fun close()
}
