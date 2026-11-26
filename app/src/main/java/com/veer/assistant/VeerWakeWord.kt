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

        fun clean(value: String): String =
            value.replace(Regex("\\s+"), " ")
                .trim { it.isWhitespace() || it in ",.!?:;—-" || it == '।' }

        var command = clean(transcript.substring(match.range.last + 1))
        // Recognition engines sometimes repeat the wake phrase in the command segment.
        while (command.isNotEmpty()) {
            val repeatedWake = patterns.asSequence()
                .mapNotNull { it.find(command)?.takeIf { found -> found.range.first == 0 } }
                .maxByOrNull { it.range.last - it.range.first }
                ?: break
            command = clean(command.substring(repeatedWake.range.last + 1))
        }
        return command
    }

    /** Prefer an alternative containing an actual command over a wake-only match. */
    fun selectCandidate(phrases: List<String>): Pair<String, String>? {
        val matches = phrases.mapNotNull { phrase ->
            extractCommand(phrase)?.let { command -> phrase to command }
        }
        return matches.firstOrNull { it.second.isNotBlank() } ?: matches.firstOrNull()
    }
}
