package com.veer.assistant

/**
 * Tracks the single pending recognition-retry callback owned by the voice service.
 * Tokens prevent a stale callback from consuming a newer retry after cancellation.
 */
internal class VeerVoiceRetryGate {
    private var retryPending = false
    private var generation = 0L

    fun trySchedule(stopping: Boolean, replyInProgress: Boolean): Long? {
        if (stopping || replyInProgress || retryPending) return null
        retryPending = true
        generation += 1
        return generation
    }

    /** Returns true only for the currently pending retry callback. */
    fun onRetryFired(token: Long): Boolean {
        if (!retryPending || token != generation) return false
        retryPending = false
        return true
    }

    fun cancel() {
        retryPending = false
        generation += 1
    }

    fun isRetryPending(): Boolean = retryPending
}
