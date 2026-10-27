package com.moss.app.asr

import java.util.Locale

/**
 * A small, device-side escape hatch. Match the whole utterance: asking how to
 * exit, cancelling a subscription, or quoting a command must remain a turn for
 * the brain rather than unexpectedly shutting the assistant down.
 */
internal object VoiceExitCommand {
    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private val commands = setOf(
        "exit Moss", "close Moss", "quit Moss",
        "Moss exit", "Moss close", "Moss quit",
        "take a break", "Moss take a break", "Hey Moss take a break",
        "退出 Moss", "关闭 Moss", "Moss 退出", "Moss 关闭",
        "退出Moss", "关闭Moss", "Moss退出", "Moss关闭",
        "休息一下", "先休息一下", "你先休息一下",
        "Moss 休息一下", "Moss休息一下", "Moss你先休息一下",
    ).map(::normalize).toSet()

    fun matches(text: String, userInitiated: Boolean = true): Boolean =
        userInitiated && normalize(text) in commands
}
