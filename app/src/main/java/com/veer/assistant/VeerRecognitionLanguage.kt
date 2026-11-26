package com.veer.assistant

/** Language fallbacks for devices whose speech service has not downloaded Hindi data. */
internal object VeerRecognitionLanguage {
    private val locales = listOf("hi-IN", "en-IN", null)
    fun localeForAttempt(attempt: Int): String? = locales[attempt.coerceIn(0, locales.lastIndex)]
    fun hasNext(attempt: Int): Boolean = attempt < locales.lastIndex
    fun nextAttempt(attempt: Int): Int = (attempt + 1).coerceAtMost(locales.lastIndex)

    /** Last fallback uses Android's standard recognizer, not the same on-device engine. */
    fun useOnDeviceRecognizer(attempt: Int): Boolean = attempt < locales.lastIndex
}