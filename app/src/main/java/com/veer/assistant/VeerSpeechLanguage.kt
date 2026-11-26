package com.veer.assistant

import java.util.Locale

/** Selects a useful Android TTS locale from the script used in VEER's reply. */
internal object VeerSpeechLanguage {
    fun localeForText(text: String): Locale =
        if (text.any { it.code in 0x0900..0x097F }) Locale("hi", "IN") else Locale("en", "IN")
}
