package com.veer.assistant

/** Recognizes short spoken responses only while an approval prompt is active. */
object VeerApprovalPhrase {
    enum class Decision { APPROVE, CANCEL, NONE }

    fun parse(command: String): Decision {
        val normalized = command.trim().lowercase(java.util.Locale.ROOT)
            .replace(Regex("[!?.,।]+$"), "")
            .replace(Regex("\\s+"), " ")
        if (normalized.isEmpty() || normalized.length > 40) return Decision.NONE

        return when (normalized) {
            "haan", "han", "haan veer", "han veer", "yes", "yes veer",
            "approve", "approved", "kar do", "kardo", "theek hai", "theek hai veer",
            " अनुमति", "अनुमति do", "हाँ", "हाँ वीर", "कर दो", "कर दीजिए"
            -> Decision.APPROVE
            "nahi", "nahin", "no", "no veer", "cancel", "mat karo",
            "rehne do", "chhodo", "नहीं", "नहीं वीर", "मत करो", "रहने दो"
            -> Decision.CANCEL
            else -> Decision.NONE
        }
    }
}
