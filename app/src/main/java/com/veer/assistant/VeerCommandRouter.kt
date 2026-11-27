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

    private val politePrefixes = listOf("please ", "hey veer ", "veer ", "can you ", "could you ")
    private val openingPrefixes = listOf("open ", "launch ", "start ")
    private val openingSuffixes = listOf(" open", " launch", " start", " kholo", " khol do", " kholna", " chalao", " shuru karo", " khul jao", " open karo")

    fun route(command: String): VeerRoutedCommand? {
        var normalized = command.trim().lowercase(java.util.Locale.ROOT)
            .replace(Regex("[!?.,।]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
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
        // Handle "open the app" before stripping the ordinary opening prefix.
        val prefixWithArticle = openingPrefixes.firstOrNull { normalized.startsWith(it + "the ") }
        if (prefixWithArticle != null) {
            normalized = normalized.removePrefix(prefixWithArticle + "the ").trim()
        } else {
            openingPrefixes.firstOrNull { normalized.startsWith(it) }?.let {
                normalized = normalized.removePrefix(it).trim()
            }
        }

        openingSuffixes.sortedByDescending { it.length }.firstOrNull { normalized.endsWith(it) }?.let {
            normalized = normalized.removeSuffix(it).trim()
        }
        normalized = normalized.removeSuffix(" app").trim()

        val match = routes.firstOrNull { (_, names) -> names.any { it == normalized } }
        return match?.let { VeerRoutedCommand(it.first) }
    }
}
