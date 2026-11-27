package com.veer.assistant

/**
 * Prevents the always-on recognizer from restarting while VEER is handling
 * a recognized command and speaking its reply.
 *
 * Kept Android-free so the pause/resume contract can be regression-tested.
 */
internal class VeerVoiceReplyGate {
    private var replyInProgress = false

    fun beginReply() {
        replyInProgress = true
    }

    fun resumeAfterReply() {
        replyInProgress = false
    }

    fun isReplyInProgress(): Boolean = replyInProgress

    fun canStartRecognition(stopping: Boolean): Boolean =
        !stopping && !replyInProgress
}
