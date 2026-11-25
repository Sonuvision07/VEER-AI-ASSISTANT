package com.veer.assistant

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer

/** Prefer Android on-device speech recognition when an offline engine is available. */
object VeerSpeechRecognizerFactory {
    fun isOnDeviceAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun create(context: Context): SpeechRecognizer {
        if (isOnDeviceAvailable(context)) {
            try { return SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
            catch (_: Exception) { /* Fall back to the platform recognizer. */ }
        }
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    fun modeLabel(context: Context): String =
        if (isOnDeviceAvailable(context)) "ON-DEVICE SPEECH" else "SYSTEM SPEECH"
}
