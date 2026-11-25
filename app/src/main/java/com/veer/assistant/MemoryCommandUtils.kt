package com.veer.assistant

/** Returns an exact saved-memory match, ignoring case. Blank targets never match. */
internal fun findMemoryIndex(memories: List<String>, target: String): Int {
    val normalizedTarget = target.trim()
    if (normalizedTarget.isBlank()) return -1
    return memories.indexOfFirst { it.equals(normalizedTarget, ignoreCase = true) }
}
