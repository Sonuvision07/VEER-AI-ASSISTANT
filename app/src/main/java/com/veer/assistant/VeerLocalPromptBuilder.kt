package com.veer.assistant

/**
 * Builds a compact prompt for small on-device models with limited context windows.
 * Recent user/assistant turns and the current request are kept ahead of older notes.
 */
internal object VeerLocalPromptBuilder {
    private const val MAX_PROMPT_CHARS = 3_200
    private const val MAX_ITEM_CHARS = 480
    private const val MAX_HISTORY_MESSAGES = 6
    private const val MAX_MEMORY_NOTES = 6

    fun build(
        memories: List<String>,
        history: List<Pair<String, String>>,
        currentRequest: String
    ): String {
        val request = currentRequest.trim().takeLast(1_200)
        val prompt = StringBuilder("You are VEER. Reply in the user's language, naturally and helpfully.\n")
        prompt.append("\nCurrent request: ").append(request)

        val recentHistory = history.takeLast(MAX_HISTORY_MESSAGES)
        for ((role, message) in recentHistory.asReversed()) {
            val entry = "\nEarlier $role: " + message.trim().takeLast(MAX_ITEM_CHARS)
            if (prompt.length + entry.length > MAX_PROMPT_CHARS) break
            prompt.insert(prompt.indexOf("\nCurrent request:"), entry)
        }

        val recentNotes = memories.takeLast(MAX_MEMORY_NOTES)
        for (note in recentNotes.asReversed()) {
            val entry = "\nSaved note: " + note.trim().take(MAX_ITEM_CHARS)
            if (prompt.length + entry.length > MAX_PROMPT_CHARS) break
            prompt.insert(prompt.indexOf("\nEarlier "), entry.takeIf { prompt.contains("\nEarlier ") }?.let { prompt.indexOf("\nEarlier ") } ?: prompt.indexOf("\nCurrent request:"), entry)
        }
        return prompt.toString()
    }
}
