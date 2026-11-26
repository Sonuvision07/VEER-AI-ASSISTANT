package com.veer.assistant

/** Deterministic, conservative routing for supported app-launch commands. */
data class VeerRoutedCommand(val action: String)

object VeerCommandRouter {
    private val routes = listOf(
        "settings" to listOf("settings kholo", "open settings"),
        "youtube_studio" to listOf("youtube studio kholo", "open youtube studio"),
        "music" to listOf("youtube music kholo", "open youtube music", "youtube music"),
        "youtube" to listOf("youtube kholo", "open youtube", "youtube"),
        "browser" to listOf("chrome kholo", "browser kholo", "open browser"),
        "calculator" to listOf("calculator kholo", "calculator open", "open calculator"),
        "camera" to listOf("camera kholo", "camera open", "open camera"),
        "maps" to listOf("maps kholo", "map kholo", "open maps", "open map"),
        "instagram" to listOf("instagram kholo", "open instagram", "instagram"),
        "facebook" to listOf("facebook kholo", "open facebook", "facebook"),
        "whatsapp" to listOf("whatsapp kholo", "open whatsapp", "whatsapp"),
        "telegram" to listOf("telegram kholo", "open telegram", "telegram"),
        "gmail" to listOf("gmail kholo", "open gmail", "gmail"),
        "dialer" to listOf("phone kholo", "dialer kholo", "open dialer", "number dial karo"),
        "x" to listOf("x kholo", "open x")
    )

    fun route(command: String): VeerRoutedCommand? {
        val normalized = command.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
        if (normalized.isEmpty() || normalized.length > 120) return null
        val match = routes.firstOrNull { (_, phrases) -> phrases.any { it == normalized } }
        return match?.let { VeerRoutedCommand(it.first) }
    }
}
