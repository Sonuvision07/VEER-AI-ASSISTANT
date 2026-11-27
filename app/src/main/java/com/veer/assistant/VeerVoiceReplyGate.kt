package com.veer.assistant

/**
 * Prevents the always-on recognizer from restarting while VEER is handling
 * a recognized command and speaking its reply.
 *
 * Reply tokens make delayed recovery callbacks one-shot and stale-safe.
 * Kept Android-free so the pause/resume contract can be regression-tested.
 */
internal class VeerVoiceReplyGate {
    private var activeReplyToken: Any? = null

    fun beginReply(): Any {
        val token = Any()
        activeReplyToken = token
        return token
    }

    fun isCurrentReply(token: Any): Boolean = activeReplyToken === token

    fun resumeAfterReply() {
        activeReplyToken = null
    }

    fun isReplyInProgress(): Boolean = activeReplyToken != null

    fun canStartRecognition(stopping: Boolean): Boolean =
        !stopping && !isReplyInProgress()
}
