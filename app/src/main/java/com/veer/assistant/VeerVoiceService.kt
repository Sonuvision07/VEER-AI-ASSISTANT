package com.veer.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var awaitingReply = false
    private var consecutiveErrors = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            stopRecognition()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startVoiceForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME) {
            if (!stopping) {
                awaitingReply = false
                consecutiveErrors = 0
                startRecognition()
            }
            return START_NOT_STICKY
        }
        stopping = false
        awaitingReply = false
        startRecognition()
        return START_NOT_STICKY
    }

    private fun startVoiceForeground(): Boolean {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VEER voice mode active")
            .setContentText("VEER is listening. Tap Stop in the app to turn it off.")
            .setOngoing(true)
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
        if (stopping || awaitingReply) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("status", "Speech recognition unavailable"))
            return
        }
        stopRecognition()
        val speech = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            Log.e("VEER", "Unable to create speech recognizer", e)
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("status", "Voice setup failed; retrying..."))
            if (!stopping && !awaitingReply) {
                consecutiveErrors = (consecutiveErrors + 1).coerceAtMost(6)
                val delayMs = (1200L * consecutiveErrors).coerceAtMost(15000L)
                handler.postDelayed({ startRecognition() }, delayMs)
            }
            return
        }
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { consecutiveErrors = 0; sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("status", "Listening in background")) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (!stopping && !awaitingReply) {
                    consecutiveErrors = (consecutiveErrors + 1).coerceAtMost(6)
                    val delayMs = (1200L * consecutiveErrors).coerceAtMost(15000L)
                    sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("status", "Voice reconnecting..."))
                    handler.postDelayed({ startRecognition() }, delayMs)
                }
            }
            override fun onResults(results: Bundle?) {
                val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (phrase.isNotBlank()) {
                    awaitingReply = true
                    sendBroadcast(Intent(ACTION_TRANSCRIPT).setPackage(packageName).putExtra("text", phrase))
                }
                if (!stopping && !awaitingReply) handler.postDelayed({ startRecognition() }, 1500)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            speech.startListening(intent)
        } catch (e: Exception) {
            Log.w("VEER", "Speech start failed", e)
            if (!stopping && !awaitingReply) {
                consecutiveErrors = (consecutiveErrors + 1).coerceAtMost(6)
                val delayMs = (1200L * consecutiveErrors).coerceAtMost(15000L)
                handler.postDelayed({ startRecognition() }, delayMs)
            }
        }
    }

    private fun stopRecognition() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    override fun onDestroy() {
        stopping = true
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
