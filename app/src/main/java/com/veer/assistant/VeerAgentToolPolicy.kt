package com.veer.assistant

/**
 * Trusted policy for the first Android agent tool.
 *
 * The model may request only a named app action; it cannot provide a package
 * name, URL, Intent action, or arbitrary command. Execution remains separate
 * and must still pass through the user-facing approval flow.
 */
internal object VeerAgentToolPolicy {
    const val ANDROID_LAUNCH_APP = "android.launch_app"
    private const val APP_ARGUMENT = "app"

    private val allowedApps = setOf(
        "settings", "youtube", "music", "browser", "calculator", "camera",
        "maps", "dialer", "instagram", "facebook", "whatsapp", "x",
        "telegram", "youtube_studio", "gmail"
    )

    fun supports(name: String): Boolean = name == ANDROID_LAUNCH_APP

    fun riskFor(name: String): AgentRisk? =
        if (supports(name)) AgentRisk.DEVICE_CHANGE else null

    /**
     * Validates the full model request against a closed schema.
     * A matching risk label alone is never sufficient.
     */
    fun isValid(call: AgentToolCall): Boolean {
        if (call.name != ANDROID_LAUNCH_APP) return false
        if (call.risk != AgentRisk.DEVICE_CHANGE) return false
        if (call.arguments.keys != setOf(APP_ARGUMENT)) return false
        val app = call.arguments[APP_ARGUMENT] ?: return false
        return app in allowedApps
    }
}
