package com.veer.assistant

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.util.Log

class VeerVoiceService : Service() {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopping = false
    private val replyGate = VeerVoiceReplyGate()
    private var consecutiveErrors = 0
    private val retryGate = VeerVoiceRetryGate()
    private val sessionCallbackGate = VeerVoiceSessionCallbackGate()
    private var recognitionLanguageAttempt = 0
    private val wakeCommandWindow = VeerWakeCommandWindow()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val voicePrefs = getSharedPreferences("veer_settings", MODE_PRIVATE)
        if (intent?.action == ACTION_STOP) {
            voicePrefs.edit().putBoolean("background_voice_enabled", false).apply()
            stopping = true
            wakeCommandWindow.clear()
            stopRecognition()
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("status", "Background voice stopped"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME && !voicePrefs.getBoolean("background_voice_enabled", false)) {
            stopping = true
            stopRecognition()
            stopSelf()
            return START_NOT_STICKY
        }
        // Ignore stale/null start commands if background voice has already been disabled.
        if (intent?.action != ACTION_RESUME && !voicePrefs.getBoolean("background_voice_enabled", false)) {
            stopping = true
            stopRecognition()
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voicePrefs.edit().putBoolean("background_voice_enabled", false).apply()
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("status", "Microphone permission required"))
            stopAfterStartupFailure()
            return START_NOT_STICKY
        }
        if (!startVoiceForeground()) {
            voicePrefs.edit().putBoolean("background_voice_enabled", false).apply()
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("status", "Background voice could not start"))
            stopAfterStartupFailure()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME) {
            stopping = false
            replyGate.resumeAfterReply()
            wakeCommandWindow.clear()
            consecutiveErrors = 0
            recognitionLanguageAttempt = 0
            startRecognition()
            return START_NOT_STICKY
        }
        voicePrefs.edit().putBoolean("background_voice_enabled", true).apply()
        stopping = false
        replyGate.resumeAfterReply()
        wakeCommandWindow.clear()
        recognitionLanguageAttempt = 0
        startRecognition()
        return START_NOT_STICKY
    }

    private fun stopAfterStartupFailure() {
        stopping = true
        replyGate.resumeAfterReply()
        wakeCommandWindow.clear()
        stopRecognition()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startVoiceForeground(): Boolean {
        val stopIntent = Intent(this, VeerVoiceService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VEER voice mode active")
            .setContentText("Say Hey Veer, then give your command. Use Stop to end listening.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e("VEER", "Unable to enter foreground voice mode", e)
            false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VEER voice mode", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startRecognition() {
        if (!replyGate.canStartRecognition(stopping)) return
        // Tear down any previous session before checking availability. If the
        // platform temporarily reports speech unavailable during RESUME, an old
        // recognizer must not remain active alongside the scheduled retry.
        stopRecognition()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("status", "Speech recognition unavailable; retrying..."))
            scheduleRecognitionRetry()
            return
        }
        val speech = try {
            VeerSpeechRecognizerFactory.create(this, VeerRecognitionLanguage.useOnDeviceRecognizer(recognitionLanguageAttempt))
        } catch (e: Exception) {
            Log.e("VEER", "Unable to create speech recognizer", e)
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("status", "Voice setup failed; retrying..."))
            scheduleRecognitionRetry()
            return
        }
        recognizer = speech
        try {
            speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (recognizer !== speech) return
                // Readiness alone is not a successful recognition. Keep the retry
                // backoff until a transcript result is actually received.
                sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("status", "Listening for Hey Veer"))
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (recognizer !== speech || !replyGate.canStartRecognition(stopping)) return
                Log.w("VEER", "Background speech recognition failed with error code $error")
                if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE &&
                    VeerRecognitionLanguage.hasNext(recognitionLanguageAttempt)) {
                    recognitionLanguageAttempt = VeerRecognitionLanguage.nextAttempt(recognitionLanguageAttempt)
                    sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("status", "Speech language unavailable; switching language..."))
                } else if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED &&
                    VeerRecognitionLanguage.useOnDeviceRecognizer(recognitionLanguageAttempt)) {
                    recognitionLanguageAttempt = VeerRecognitionLanguage.systemFallbackAttempt()
                    sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("status", "On-device speech disconnected; switching to Android system speech..."))
                } else {
                    val statusMessage = when (error) {
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                            "Speech language unavailable. Check Google Speech Services language downloads."
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                            "Android speech service disconnected; VEER is reconnecting..."
                        else -> "Voice reconnecting..."
                    }
                    sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("status", statusMessage))
                }
                // A terminal recognition callback ends this session. Release its idle
                // recognizer now rather than holding it throughout the retry backoff.
                if (recognizer === speech) {
                    recognizer = null
                    try {
                        speech.destroy()
                    } catch (cleanupError: Exception) {
                        Log.w("VEER", "Failed recognizer cleanup after error", cleanupError)
                    }
                }
                scheduleRecognitionRetry()
            }
            override fun onResults(results: Bundle?) {
                if (recognizer !== speech || !replyGate.canStartRecognition(stopping)) return
                // Results finish this recognition session. Release it before command
                // handling and before the delayed next listening session is scheduled.
                recognizer = null
                try {
                    speech.destroy()
                } catch (cleanupError: Exception) {
                    Log.w("VEER", "Failed recognizer cleanup after results", cleanupError)
                }
                consecutiveErrors = 0
                val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.map { it.trim() }.orEmpty()
                // Recognition alternatives can differ; prefer any candidate that actually contains the wake phrase.
                val wakeMatch = VeerWakeWord.selectCandidate(phrases)
                val phrase = wakeMatch?.first ?: phrases.firstOrNull().orEmpty()
                val command = wakeMatch?.second
                when {
                    command != null && command.isNotBlank() -> {
                        wakeCommandWindow.clear()
                        replyGate.beginReply()
                        sendBroadcast(Intent(ACTION_TRANSCRIPT).setPackage(packageName).putExtra("text", command))
                    }
                    command != null -> {
                        wakeCommandWindow.arm(SystemClock.elapsedRealtime())
                        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("status", "VEER activated. Say your command"))
                    }
                    phrase.isNotBlank() && wakeCommandWindow.isActive(SystemClock.elapsedRealtime()) -> {
                        wakeCommandWindow.clear()
                        replyGate.beginReply()
                        sendBroadcast(Intent(ACTION_TRANSCRIPT).setPackage(packageName).putExtra("text", phrase))
                    }
                    else -> {
                        // Preserve an armed window for empty results; expire it on timeout or unrelated speech.
                        val wakeWindowActive = wakeCommandWindow.expireIfNeeded(
                            SystemClock.elapsedRealtime(),
                            phrase.isNotBlank()
                        )
                        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(
                            "status",
                            if (wakeWindowActive) "VEER activated. Say your command" else "Waiting for Hey Veer"
                        ))
                    }
                }
                if (replyGate.canStartRecognition(stopping)) {
                    val delayMs = if (wakeCommandWindow.isActive(SystemClock.elapsedRealtime())) 350L else 700L
                    val callbackToken = sessionCallbackGate.currentToken()
                    handler.postDelayed({
                        if (sessionCallbackGate.isCurrent(callbackToken) &&
                            replyGate.canStartRecognition(stopping)) {
                            startRecognition()
                        }
                    }, delayMs)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                VeerRecognitionLanguage.localeForAttempt(recognitionLanguageAttempt)?.let { locale ->
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
                }
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            speech.startListening(intent)
        } catch (e: Exception) {
            Log.w("VEER", "Speech recognizer setup/start failed", e)
            // Release a recognizer if listener registration or startListening fails.
            if (recognizer === speech) {
                recognizer = null
            }
            try {
                speech.destroy()
            } catch (cleanupError: Exception) {
                Log.w("VEER", "Failed recognizer cleanup also failed", cleanupError)
            }
            scheduleRecognitionRetry()
        }
    }

    private fun scheduleRecognitionRetry() {
        val retryToken = retryGate.trySchedule(stopping, replyGate.isReplyInProgress()) ?: return
        val sessionToken = sessionCallbackGate.currentToken()
        consecutiveErrors = VeerVoiceRetryPolicy.nextFailureCount(consecutiveErrors)
        val delayMs = VeerVoiceRetryPolicy.delayForFailureCount(consecutiveErrors)
        handler.postDelayed({
            if (retryGate.onRetryFired(retryToken) &&
                sessionCallbackGate.isCurrent(sessionToken) &&
                replyGate.canStartRecognition(stopping)) {
                startRecognition()
            }
        }, delayMs)
    }

    private fun stopRecognition() {
        handler.removeCallbacksAndMessages(null)
        sessionCallbackGate.invalidate()
        retryGate.cancel()
        // Invalidate callbacks before touching the recognizer: cancel/destroy may
        // synchronously trigger listener activity on some speech-service versions.
        val previousRecognizer = recognizer
        recognizer = null
        try {
            previousRecognizer?.cancel()
        } catch (error: Exception) {
            Log.w("VEER", "Failed to cancel speech recognizer during stop", error)
        }
        try {
            previousRecognizer?.destroy()
        } catch (error: Exception) {
            Log.w("VEER", "Failed to destroy speech recognizer during stop", error)
        }
    }

    override fun onDestroy() {
        stopping = true
        wakeCommandWindow.clear()
        stopRecognition()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_TRANSCRIPT = "com.veer.assistant.VOICE_TRANSCRIPT"
        const val ACTION_STATUS = "com.veer.assistant.VOICE_STATUS"
        const val ACTION_STOP = "com.veer.assistant.STOP_VOICE"
        const val ACTION_RESUME = "com.veer.assistant.RESUME_VOICE"
        private const val CHANNEL_ID = "veer_voice_channel"
        private const val NOTIFICATION_ID = 7201
    }
}
