// Moss: derived from Andee (KUAFUAI, Apache-2.0 + conditions). Expression layer
// disabled: this file only strips bracket tags so they are never spoken or
// shown. It never sets a face; no cues are recorded.
package com.moss.app.brain

/**
 * Bracket-tag stripper for model replies.
 *
 * The model may still write bracketed words (for example `[happy]`) out of
 * habit. Those tags must never reach the scrollback or the voice pipeline,
 * where they would be shown or read aloud as words. This object removes
 * anything bracket-shaped that is clearly not words — the same shapes the
 * original parser ate — and records no face for any of it.
 *
 * The discriminator is unchanged from the original: letters-only, not
 * SHOUTED, and not followed by `(` (a markdown link label). `[END]` is a
 * sentinel in our own prompt and `[12条]` is a notification badge; both
 * survive because they are SHOUTED or followed by `(`.
 */
object SpeechMood {

    /** A reply with its markers removed. */
    data class Script(val text: String)

    private val MARKER = Regex("[\\[\\uFF3B\\u3010]\\s*([A-Za-z\\u4e00-\\u9fff]{1,12})\\s*[\\]\\uFF3D\\u3011]")

    private fun droppable(word: String, after: Char?): Boolean =
        word.all { it in 'a'..'z' || it in 'A'..'Z' } &&
            word != word.uppercase() &&
            after != '('

    fun parse(raw: String): Script {
        if (raw.isEmpty()) return Script(raw)
        val matches = MARKER.findAll(raw).toList()
        if (matches.isEmpty()) return Script(raw)

        val out = StringBuilder(raw.length)
        var cursor = 0
        for (m in matches) {
            val word = m.groupValues[1]
            // Not a face, and never was one here: either drop it silently or
            // leave it to be spoken — and "leave it" means leaving the
            // brackets too, so this branch must not touch [cursor] or the
            // whitespace around the match.
            if (!droppable(word, raw.getOrNull(m.range.last + 1))) continue

            out.append(raw, cursor, m.range.first)
            cursor = m.range.last + 1

            val spaceBefore = out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')
            var spaceAfter = false
            while (cursor < raw.length && (raw[cursor] == ' ' || raw[cursor] == '\t')) {
                cursor++
                spaceAfter = true
            }
            while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) {
                out.setLength(out.length - 1)
            }
            if (out.isNotEmpty() && cursor < raw.length && (spaceBefore || spaceAfter)) {
                out.append(' ')
            }
        }
        out.append(raw, cursor, raw.length)

        val text = out.toString()
        if (text.isBlank() && raw.isNotBlank()) return Script(raw)
        return Script(text)
    }

    /** [parse] for callers that only need the words. */
    fun strip(raw: String): String = parse(raw).text
}
