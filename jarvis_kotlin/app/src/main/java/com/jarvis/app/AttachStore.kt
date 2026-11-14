package com.jarvis.app

/**
 * 对话框附件（2026-11-14）：用户在面板“文件”区点添加文件选的文件，先存这里，
 * 等下一句提问（打字或语音）时一起发给模型。一次只留一个，新的顶掉旧的，
 * 用掉即清空。文本与图片分开存，只会同时有一种。
 */
object AttachStore {
    /** 文本文件最多带这么多字进问题，超了截断（小模型上下文装不下，云端也别一次喂太多）。 */
    const val MAX_TEXT_CHARS = 20_000

    @Volatile var name: String? = null
        private set
    @Volatile var text: String? = null
        private set
    @Volatile var imageB64: String? = null
        private set

    @Synchronized fun putText(name: String, text: String) {
        this.name = name
        this.text = text
        this.imageB64 = null
    }

    @Synchronized fun putImage(name: String, b64: String) {
        this.name = name
        this.imageB64 = b64
        this.text = null
    }

    @Synchronized fun clear() {
        name = null
        text = null
        imageB64 = null
    }

    fun has(): Boolean = text != null || imageB64 != null
}
