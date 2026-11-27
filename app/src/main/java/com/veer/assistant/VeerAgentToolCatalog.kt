package com.veer.assistant

/**
 * Provider-neutral description of a tool that a model may request.
 *
 * Provider adapters must translate this contract to their own function-calling
 * format. The catalog is descriptive only: execution still goes through the
 * trusted policy, approval gate, and AgentToolExecutor.
 */
internal data class VeerAgentParameterDefinition(
    val name: String,
    val description: String,
    val type: String = "string",
    val required: Boolean = true,
    val allowedValues: List<String> = emptyList()
)

internal data class VeerAgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<VeerAgentParameterDefinition>
)

internal object VeerAgentToolCatalog {
    fun definitions(): List<VeerAgentToolDefinition> = listOf(
        VeerAgentToolDefinition(
            name = VeerAgentToolPolicy.ANDROID_LAUNCH_APP,
            description = "Open one supported app on the user's Android device. This changes device state and requires user approval.",
            parameters = listOf(
                VeerAgentParameterDefinition(
                    name = "app",
                    description = "Supported app identifier to open.",
                    allowedValues = VeerAgentToolPolicy.allowedAppIds()
                )
            )
        )
    )

    fun find(name: String): VeerAgentToolDefinition? =
        definitions().firstOrNull { it.name == name }
}
