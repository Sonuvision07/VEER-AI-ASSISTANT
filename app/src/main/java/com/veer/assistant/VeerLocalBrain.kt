package com.veer.assistant

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Small offline reasoning layer for common requests.
 * Unknown or open-ended questions return null so the configured language model can handle them.
 */
object VeerLocalBrain {
    private val arithmetic = Regex(
        """^\\s*(?:calculate\\s+|hisab\\s+|kitna\\s+hota\\s+hai\\s+)?(-?\\d+(?:\\.\\d+)?)\\s*(\\+|-|\\*|/|plus|add|jod|jodo|minus|ghatao|guna|multiply|into|times|divide|divided\\s+by|bhaag)\\s*(-?\\d+(?:\\.\\d+)?)\\s*[?।.!]*\\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun reply(input: String, memories: List<String> = emptyList()): String? {
        val clean = input.trim()
        if (clean.isEmpty()) return null
        val normalized = clean.lowercase(Locale.ROOT)
            .replace(Regex("[\\s!?.,।]+$"), "")
            .trim()

        when (normalized) {
            "hi", "hello", "hey", "namaste", "namaskar", "राम राम", "नमस्ते" ->
                return "Namaste Boss! VEER yahin hai. Bataiye, kya kaam karna hai?"
            "tum kaun ho", "aap kaun ho", "who are you", "who are you?" ->
                return "Main VEER hoon — aapka personal assistant. Main basic baatein aur calculations offline handle kar sakta hoon; complex kaam ke liye connected AI ki madad le sakta hoon."
            "tum kya kar sakte ho", "aap kya kar sakte ho", "what can you do", "help" ->
                return "Main time/date, basic hisaab, saved memories aur supported phone actions handle kar sakta hoon. Naye ya complex sawaalon ke liye AI provider ki zaroorat pad sakti hai."
            "kaise ho", "tum kaise ho", "how are you" ->
                return "Main bilkul ready hoon Boss! Aap batao, aaj kya karna hai?"
            "shukriya", "dhanyawad", "thanks", "thank you" ->
                return "Aapka swagat hai Boss. Aur koi kaam ho to bataiye."
            "bye", "goodbye", "alvida" ->
                return "Theek hai Boss. Main yahin hoon, jab zaroorat ho bula lena."
            "meri memory mein kya hai", "what do you remember" ->
                return if (memories.isEmpty()) "Abhi meri local memory mein koi saved note nahi hai." else "Meri local memory mein ye notes hain:\\n" + memories.takeLast(10).mapIndexed { i, note -> "${i + 1}. $note" }.joinToString("\\n")
        }

        val match = arithmetic.matchEntire(clean) ?: return null
        val left = match.groupValues[1].toDoubleOrNull() ?: return null
        val operator = match.groupValues[2].lowercase(Locale.ROOT)
        val right = match.groupValues[3].toDoubleOrNull() ?: return null
        val result = when (operator) {
            "+", "plus", "add", "jod", "jodo" -> left + right
            "-", "minus", "ghatao" -> left - right
            "*", "guna", "multiply", "into", "times" -> left * right
            "/", "divide", "divided by", "bhaag" -> {
                if (right == 0.0) return "Zero se divide nahi kar sakte, Boss."
                left / right
            }
            else -> return null
        }
        if (!result.isFinite()) return "Is calculation ka result valid range mein nahi hai."
        val symbols = DecimalFormatSymbols(Locale.US)
        val formatted = DecimalFormat("0.##########", symbols).format(result)
        return "Boss, jawab $formatted hai."
    }
}
