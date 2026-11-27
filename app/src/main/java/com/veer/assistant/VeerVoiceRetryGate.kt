package com.veer.assistant

/**
 * Tracks the single pending recognition-retry callback owned by the voice service.
 * Android-free so duplicate scheduling, firing, and cancellation can be tested.
 */
internal class VeerVoiceRetryGate {
    private var retryPending = false

    fun trySchedule(stopping: Boolean, replyInProgress: Boolean): Boolean {
        if (stopping || replyInProgress || retryPending) return false
        retryPending = true
        return true
    }

    /** Returns true only for the currently pending retry callback. */
    fun onRetryFired(): Boolean {
        if (!retryPending) return false
        retryPending = false
        return true
    }

    fun cancel() {
        retryPending = false
    }

    fun isRetryPending(): Boolean = retryPending
}
