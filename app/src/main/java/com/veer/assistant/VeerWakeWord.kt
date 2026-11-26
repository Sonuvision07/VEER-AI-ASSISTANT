package com.veer.assistant

/** Extracts a spoken command only after the explicit VEER wake phrase. */
internal object VeerWakeWord {
    private val patterns = listOf(
        Regex("(?i)\\bhey\\s*veer\\b"),
        Regex("(?i)\\bheyveer\\b"),
        Regex("हे\\s*वीर"),
        Regex("हेवीर")
    )

    /**
     * Returns null when the wake phrase is absent, an empty string when only
     * the wake phrase was spoken, or the remaining command text otherwise.
     */
    fun extractCommand(transcript: String): String? {
        val match = patterns.asSequence()
            .mapNotNull { it.find(transcript) }
            .minByOrNull { it.range.first }
            ?: return null

        return transcript.substring(match.range.last + 1)
            .replace(Regex("\\s+"), " ")
            .trim { it.isWhitespace() || it in ",.!?:;—-" || it == '।' }
    }
}
