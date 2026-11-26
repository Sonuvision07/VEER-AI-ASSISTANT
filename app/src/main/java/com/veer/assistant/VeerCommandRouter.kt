package com.veer.assistant

/** Deterministic, conservative routing for supported app-launch commands. */
data class VeerRoutedCommand(val action: String)

object VeerCommandRouter {
    private val routes = listOf(
        "settings" to listOf("settings kholo", "open settings", "settings open karo", "settings khol do"),
        "youtube_studio" to listOf("youtube studio kholo", "open youtube studio", "youtube studio open karo", "youtube studio khol do"),
        "music" to listOf("youtube music kholo", "open youtube music", "youtube music", "youtube music open karo", "youtube music chalao", "music app kholo"),
        "youtube" to listOf("youtube kholo", "open youtube", "youtube", "youtube open karo", "youtube khol do", "youtube app kholo", "youtube app open karo", "open the youtube app", "youtube chalao"),
        "browser" to listOf("chrome kholo", "browser kholo", "open browser", "chrome open karo", "browser open karo", "chrome khol do"),
        "calculator" to listOf("calculator kholo", "calculator open", "open calculator", "calculator open karo", "calculator khol do"),
        "camera" to listOf("camera kholo", "camera open", "open camera", "camera open karo", "camera khol do"),
        "maps" to listOf("maps kholo", "map kholo", "open maps", "open map", "maps open karo", "maps khol do"),
        "instagram" to listOf("instagram kholo", "open instagram", "instagram", "instagram open karo", "instagram khol do", "instagram app kholo"),
        "facebook" to listOf("facebook kholo", "open facebook", "facebook", "facebook open karo", "facebook khol do"),
        "whatsapp" to listOf("whatsapp kholo", "open whatsapp", "whatsapp", "whatsapp open karo", "whatsapp khol do", "whatsapp app kholo"),
        "telegram" to listOf("telegram kholo", "open telegram", "telegram", "telegram open karo", "telegram khol do"),
        "gmail" to listOf("gmail kholo", "open gmail", "gmail", "gmail open karo", "gmail khol do"),
        "dialer" to listOf("phone kholo", "dialer kholo", "open dialer", "number dial karo"),
        "x" to listOf("x kholo", "open x")
    )

    fun route(command: String): VeerRoutedCommand? {
        val normalized = command.trim().lowercase(java.util.Locale.ROOT)
            .replace(Regex("[!?.,।]+$"), "")
            .replace(Regex("\\s+"), " ")
        if (normalized.isEmpty() || normalized.length > 120) return null
        val match = routes.firstOrNull { (_, phrases) -> phrases.any { it == normalized } }
        return match?.let { VeerRoutedCommand(it.first) }
    }
}
