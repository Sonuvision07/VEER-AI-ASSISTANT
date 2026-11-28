package com.veer.assistant

import java.util.Locale

/**
 * Answers simple greetings locally so a basic hello never waits for a cloud provider.
 * Only exact, normalized greeting phrases are handled; all other requests continue
 * through the existing provider/agent pipeline.
 */
object VeerGreetingResponder {
    private val greetings = setOf(
        "hello", "hello veer", "hello there", "hi", "hi veer", "hey", "hey veer",
        "namaste", "namaste veer", "namaskar", "namaskar veer",
        "good morning", "good morning veer", "good afternoon", "good afternoon veer",
        "good evening", "good evening veer", "good night", "good night veer",
        "how are you", "how are you veer", "kaise ho", "kaise ho veer",
        "kya haal hai", "kya haal hai veer", "aur batao"
    )

    fun reply(request: String): String? {
        val normalized = request.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[!?.,।]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized !in greetings) return null

        return when (normalized) {
            "how are you", "how are you veer", "kaise ho", "kaise ho veer", "kya haal hai", "kya haal hai veer" ->
                "Main badhiya hoon, Boss! Aap bataiye, kya help karun?"
            "good night", "good night veer" ->
                "Good night, Boss! Jab bhi zaroorat ho, VEER yahin hai."
            else -> "Hello Boss! Main VEER hoon, ready hoon. Bataiye, kya help chahiye?"
        }
    }
}
