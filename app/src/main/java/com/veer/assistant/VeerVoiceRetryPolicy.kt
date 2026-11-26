package com.veer.assistant

/**
 * Backoff policy for restarting Android SpeechRecognizer after transient failures.
 * The delay grows linearly and is capped at [MAX_DELAY_MS]. The failure counter
 * saturates at [MAX_FAILURE_COUNT]; this does not limit the number of retry attempts.
 * The service keeps retrying at the capped delay until voice mode is stopped or a
 * reply is being handled.
 */
object VeerVoiceRetryPolicy {
    const val INITIAL_DELAY_MS = 1_200L
    const val MAX_DELAY_MS = 7_200L
    const val MAX_FAILURE_COUNT = 6

    fun nextFailureCount(current: Int): Int =
        (current + 1).coerceIn(1, MAX_FAILURE_COUNT)

    fun delayForFailureCount(failureCount: Int): Long {
        val safeCount = failureCount.coerceIn(1, MAX_FAILURE_COUNT)
        return (INITIAL_DELAY_MS * safeCount).coerceAtMost(MAX_DELAY_MS)
    }
}
