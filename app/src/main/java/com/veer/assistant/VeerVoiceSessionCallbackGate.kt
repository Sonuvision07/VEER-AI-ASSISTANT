package com.veer.assistant

/**
 * Invalidates delayed work belonging to an older voice-service session.
 * All access is confined to the service main thread.
 */
internal class VeerVoiceSessionCallbackGate {
    private var generation = 0L

    fun currentToken(): Long = generation

    fun isCurrent(token: Long): Boolean = token == generation

    fun invalidate() {
        generation += 1
    }
}
