package com.veer.assistant

/**
 * Small, task-scoped context buffer. It is deliberately in-memory and has no
 * persistence or provider-specific continuation fields. Callers decide which
 * already-safe snippets are appropriate to add; each entry and the total
 * retained character count are bounded.
 */
class AgentTaskContext(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxEntryChars: Int = DEFAULT_MAX_ENTRY_CHARS,
    private val maxTotalChars: Int = DEFAULT_MAX_TOTAL_CHARS,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val text: String, val createdAt: Long)
    private val entries = ArrayDeque<Entry>()
    private var retainedChars = 0

    init {
        require(maxEntries in 1..MAX_ALLOWED_ENTRIES)
        require(maxEntryChars in 1..MAX_ALLOWED_ENTRY_CHARS)
        require(maxTotalChars in 1..MAX_ALLOWED_TOTAL_CHARS)
        require(ttlMillis in 1L..MAX_TTL_MILLIS)
    }

    @Synchronized
    fun add(value: String): Boolean {
        purgeExpired()
        val safe = sanitize(value).take(maxEntryChars)
        if (safe.isBlank()) return false
        if (safe.length > maxTotalChars) return false
        while (entries.size >= maxEntries || retainedChars + safe.length > maxTotalChars) {
            val removed = entries.removeFirstOrNull() ?: break
            retainedChars -= removed.text.length
        }
        entries.addLast(Entry(safe, clockMillis()))
        retainedChars += safe.length
        return true
    }

    @Synchronized
    fun snapshot(): List<String> {
        purgeExpired()
        return entries.map { it.text }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        retainedChars = 0
    }

    @Synchronized
    fun retainedCharacterCount(): Int {
        purgeExpired()
        return retainedChars
    }

    private fun purgeExpired() {
        val now = clockMillis()
        while (entries.isNotEmpty()) {
            val age = now - entries.first().createdAt
            if (age < 0L || age <= ttlMillis) break
            val removed = entries.removeFirst()
            retainedChars -= removed.text.length
        }
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("[\\p{Cc}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    companion object {
        const val DEFAULT_MAX_ENTRIES = 8
        const val DEFAULT_MAX_ENTRY_CHARS = 1_000
        const val DEFAULT_MAX_TOTAL_CHARS = 4_000
        const val DEFAULT_TTL_MILLIS = 15 * 60 * 1_000L
        const val MAX_ALLOWED_ENTRIES = 32
        const val MAX_ALLOWED_ENTRY_CHARS = 4_000
        const val MAX_ALLOWED_TOTAL_CHARS = 8_000
        const val MAX_TTL_MILLIS = 60 * 60 * 1_000L
    }
}
