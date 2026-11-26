package com.veer.assistant

/** Deterministic, conservative routing for supported app-launch commands. */
data class VeerRoutedCommand(val action: String)

object VeerCommandRouter {
    private val routes = listOf(
        "settings" to listOf("settings", "setting", "phone settings"),
        "youtube_studio" to listOf("youtube studio"),
        "music" to listOf("youtube music", "music app", "music"),
        "youtube" to listOf("youtube"),
        "browser" to listOf("chrome", "browser", "internet"),
        "calculator" to listOf("calculator"),
        "camera" to listOf("camera"),
        "maps" to listOf("google maps", "maps", "map"),
        "instagram" to listOf("instagram"),
        "facebook" to listOf("facebook"),
        "whatsapp" to listOf("whatsapp"),
        "telegram" to listOf("telegram"),
        "gmail" to listOf("gmail"),
        "dialer" to listOf("phone dialer", "dialer", "phone"),
        "x" to listOf("twitter", "x")
    )

    private val politePrefixes = listOf("please ", "veer ", "hey veer ", "can you ", "could you ")
    private val openVerbs = listOf(
        "open", "launch", "start", "kholo", "khol do", "kholna", "open karo",
        "chalao", "shuru karo", "khul jao"
    )

    fun route(command: String): VeerRoutedCommand? {
        var normalized = command.trim().lowercase(java.util.Locale.ROOT)
            .replace(Regex("[!?.,।]+$"), "")
            .replace(Regex("\\s+"), " ")
        if (normalized.isEmpty() || normalized.length > 120) return null

        var changed: Boolean
        do {
            changed = false
            politePrefixes.firstOrNull { normalized.startsWith(it) }?.let {
                normalized = normalized.removePrefix(it).trim()
                changed = true
            }
        } while (changed)

        normalized = normalized.removeSuffix(" please").trim()
        normalized = normalized
            .replace(Regex("^(open|launch|start) the "), "")
            .replace(Regex("^(open|launch|start) "), "")

        for (verb in openVerbs.sortedByDescending { it.length }) {
            if (normalized.endsWith(" $verb")) {
                normalized = normalized.removeSuffix(" $verb").trim()
                break
            }
        }
        normalized = normalized.removeSuffix(" app").trim()

        val match = routes.firstOrNull { (_, names) -> names.any { it == normalized } }
        return match?.let { VeerRoutedCommand(it.first) }
    }
}
