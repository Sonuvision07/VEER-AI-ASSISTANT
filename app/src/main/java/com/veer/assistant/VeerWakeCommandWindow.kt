package com.veer.assistant

/**
 * Tracks the short command-capture window opened by a wake-only transcript.
 * Uses elapsed timestamps supplied by the caller so the lifecycle is unit-testable.
 */
internal class VeerWakeCommandWindow(
    private val durationMs: Long = DEFAULT_DURATION_MS
) {
    private var armedAtMs: Long? = null

    fun arm(nowMs: Long) {
        armedAtMs = nowMs
    }

    fun isActive(nowMs: Long): Boolean {
        val armedAt = armedAtMs ?: return false
        return nowMs >= armedAt && nowMs - armedAt <= durationMs
    }

    /**
     * Empty recognition results preserve an active window. Any unrelated speech
     * or a result beyond the timeout clears it.
     */
    fun expireIfNeeded(nowMs: Long, hasSpeech: Boolean): Boolean {
        if (hasSpeech || !isActive(nowMs)) {
            clear()
            return false
        }
        return true
    }

    fun clear() {
        armedAtMs = null
    }

    companion object {
        const val DEFAULT_DURATION_MS = 8_000L
    }
}
