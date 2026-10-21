package com.jarvis.jarvis_app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

/**
 * 注册为系统“数字助理”：用户在 设置 > 默认应用 > 数字助理 里选 Jarvis 后，
 * 电源键长按 / Home 长按 / 角滑动 会进到这里，我们再拉起 MainActivity 开始听。
 */
class JarvisVoiceInteractionService : VoiceInteractionService()

class JarvisVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisVoiceSession(this)
}

class JarvisVoiceSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val i = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_ACTION, "assist")
        }
        try {
            startAssistantActivity(i)
        } catch (t: Throwable) {
            try {
                context.startActivity(i)
            } catch (_: Throwable) {
            }
        }
        hide()
    }
}

/** 占位：部分系统版本要求数字助理声明 recognitionService。不对外提供识别能力，直接报“无匹配”。 */
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(android.speech.SpeechRecognizer.ERROR_NO_MATCH)
        } catch (_: Exception) {
        }
    }

    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
