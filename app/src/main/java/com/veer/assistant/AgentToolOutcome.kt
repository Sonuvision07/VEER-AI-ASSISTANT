package com.veer.assistant

/** Outcome state deliberately separates dispatch from independently verified success. */
enum class AgentOutcomeStatus { ATTEMPTED, VERIFIED, UNCERTAIN }

data class AgentToolOutcome(
    val status: AgentOutcomeStatus,
    val evidenceSummary: String? = null
) {
    companion object {
        private const val MAX_EVIDENCE_CHARS = 240

        /** A dispatch alone is never proof that the requested effect happened. */
        fun attempted(): AgentToolOutcome = AgentToolOutcome(AgentOutcomeStatus.ATTEMPTED)

        /**
         * Call only when a trusted verifier has independently confirmed the
         * expected postcondition. A tool's own success message is not enough.
         */
        fun verified(evidenceSummary: String): AgentToolOutcome {
            val safe = sanitize(evidenceSummary)
            require(safe.isNotBlank()) { "Verified outcome requires non-empty evidence." }
            return AgentToolOutcome(AgentOutcomeStatus.VERIFIED, safe.take(MAX_EVIDENCE_CHARS))
        }

        /** Use when dispatch/result/verification is incomplete or ambiguous. */
        fun uncertain(reason: String? = null): AgentToolOutcome {
            val safe = reason?.let(::sanitize)?.take(MAX_EVIDENCE_CHARS)?.takeIf { it.isNotBlank() }
            return AgentToolOutcome(AgentOutcomeStatus.UNCERTAIN, safe)
        }

        private fun sanitize(value: String): String = value
            .replace(Regex("[\\p{Cc}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
