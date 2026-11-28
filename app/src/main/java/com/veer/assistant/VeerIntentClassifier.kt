package com.veer.assistant

import org.json.JSONObject

enum class VeerIntentClassificationStatus {
    SUPPORTED_ACTION,
    NEEDS_CLARIFICATION,
    NOT_HANDLED
}

data class VeerIntentClassification(
    val status: VeerIntentClassificationStatus,
    val plan: VeerTaskPlan? = null
)

/**
 * Conservative first-pass classifier for the deterministic app-launch routes VEER
 * already supports. Unknown requests are left for the existing agent brain; they
 * are never guessed into a device action.
 */
object VeerIntentClassifier {
    fun classify(request: String, requestId: String): VeerIntentClassification {
        val routed = VeerCommandRouter.route(request)
        if (routed != null) {
            val arguments = JSONObject().put("app", routed.action).toString()
            return VeerIntentClassification(
                status = VeerIntentClassificationStatus.SUPPORTED_ACTION,
                plan = VeerTaskPlan(
                    requestId = requestId,
                    kind = VeerIntentKind.ACTION,
                    summary = "Open ${routed.action}",
                    steps = listOf(
                        VeerPlanStep(
                            id = "open_app",
                            toolName = VeerAgentToolPolicy.ANDROID_LAUNCH_APP,
                            argumentsJson = arguments
                        )
                    )
                )
            )
        }

        if (isExplicitAppLaunchRequest(request)) {
            return VeerIntentClassification(
                status = VeerIntentClassificationStatus.NEEDS_CLARIFICATION,
                plan = VeerTaskPlan(
                    requestId = requestId,
                    kind = VeerIntentKind.NEEDS_CLARIFICATION,
                    summary = "App target is not clear or is not supported",
                    clarificationQuestion = "Kaunsi supported app kholni hai? App ka naam bataiye."
                )
            )
        }

        return VeerIntentClassification(VeerIntentClassificationStatus.NOT_HANDLED)
    }

    private fun isExplicitAppLaunchRequest(request: String): Boolean {
        val normalized = request.trim().lowercase(java.util.Locale.ROOT)
            .replace(Regex("[!?.,।]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return false

        val openingPrefixes = listOf(
            "open ", "launch ", "start ", "please open ", "please launch ",
            "please start ", "hey veer open ", "veer open ", "kholo ",
            "khol do ", "kholna ", "chalao ", "shuru karo "
        )
        if (openingPrefixes.any { normalized.startsWith(it) }) return true

        val openingSuffixes = listOf(
            " open", " launch", " start", " kholo", " khol do",
            " kholna", " chalao", " shuru karo"
        )
        return openingSuffixes.any { normalized.endsWith(it) }
    }
}
