package com.veer.assistant

/** Human-readable confirmation text for the closed Android app-tool schema. */
internal object VeerAgentApprovalPresentation {
    fun actionLabel(call: AgentToolCall): String? {
        val app = VeerAgentToolPolicy.toPendingAppAction(call) ?: return null
        return actionLabel(app)
    }

    fun actionLabel(app: String): String? =
        when (app) {
            "settings" -> "Phone Settings kholna"
            "youtube" -> "YouTube kholna"
            "music" -> "YouTube Music kholna"
            "browser" -> "Browser kholna"
            "calculator" -> "Calculator kholna"
            "camera" -> "Camera kholna"
            "maps" -> "Maps kholna"
            "instagram" -> "Instagram kholna"
            "facebook" -> "Facebook kholna"
            "whatsapp" -> "WhatsApp kholna"
            "x" -> "X kholna"
            "telegram" -> "Telegram kholna"
            "youtube_studio" -> "YouTube Studio kholna"
            "gmail" -> "Gmail kholna"
            "dialer" -> "Phone dialer kholna (call nahi lagegi)"
            else -> null
        }
}
