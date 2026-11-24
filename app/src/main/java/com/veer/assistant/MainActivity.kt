package com.veer.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VeerApp() }
    }
}

@Composable
fun VeerApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cyan = Color(0xFF00E5FF)
    val background = Color(0xFF050B16)

    var status by remember { mutableStateOf("VEER ONLINE") }
    var heard by remember { mutableStateOf("Boss, main sun raha hoon.") }
    var reply by remember { mutableStateOf("Namaste Boss! Main VEER hoon.") }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(context) {
        val engine = TextToSpeech(context) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale("hi", "IN")
            }
        }
        tts = engine
        onDispose {
            engine.stop()
            engine.shutdown()
            tts = null
        }
    }

    fun speak(message: String) {
        reply = message
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "VEER")
    }

    fun processCommand(command: String) {
        heard = command
        val text = command.lowercase(Locale.getDefault())

        when {
            text.contains("namaste") || text.contains("hello") ||
            text.contains("hey veer") -> speak("Namaste Boss! Bataiye, main aapki kya madad kar sakta hoon?")

            text.contains("time") || text.contains("kitne baje") ->
                speak("Abhi time hai " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date()))

            text.contains("date") || text.contains("tarikh") || text.contains("aaj kya") ->
                speak("Aaj ki tarikh hai " + SimpleDateFormat("dd MMMM yyyy", Locale("hi", "IN")).format(Date()))

            text.contains("who are you") || text.contains("kaun ho") ->
                speak("Main VEER hoon, aapka personal assistant.")

            text.contains("youtube") -> {
                speak("YouTube khol raha hoon Boss.")
                val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                if (intent != null) context.startActivity(intent)
                else speak("Boss, YouTube app nahi mil raha.")
            }

            else -> speak("Boss, maine aapki baat sun li hai. Mera advanced AI brain abhi connect hona baaki hai.")
        }
    }

    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun listen() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            status = "MICROPHONE PERMISSION REQUIRED"
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status = "SPEECH SERVICE NOT AVAILABLE"
            speak("Boss, speech service available nahi hai.")
            return
        }

        recognizer?.destroy()
        val speech = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speech

        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                status = "LISTENING..."
            }
            override fun onBeginningOfSpeech() {
                status = "HEARING YOUR VOICE"
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                status = "PROCESSING..."
            }
            override fun onError(error: Int) {
                status = "TAP TO TRY AGAIN"
            }
            override fun onResults(results: Bundle?) {
                val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!words.isNullOrEmpty()) processCommand(words[0])
                status = "VEER ONLINE"
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
            status = "VOICE ERROR"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) listen()
        else status = "MICROPHONE PERMISSION DENIED"
    }

    DisposableEffect(Unit) {
        onDispose {
            recognizer?.destroy()
            recognizer = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(background, Color(0xFF101F35)))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("VEER", color = cyan, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Text("PERSONAL AI ASSISTANT", color = Color.LightGray, fontSize = 12.sp)

            Spacer(Modifier.height(35.dp))

            Box(
                modifier = Modifier
                    .size(170.dp)
                    .background(Color(0xFF102D43), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("◉", color = cyan, fontSize = 100.sp)
            }

            Spacer(Modifier.height(28.dp))
            Text(status, color = cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(25.dp))
            Text("YOU SAID", color = Color.Gray, fontSize = 12.sp)
            Text(heard, color = Color.White, fontSize = 17.sp)

            Spacer(Modifier.height(18.dp))
            Text("VEER", color = cyan, fontSize = 12.sp)
            Text(reply, color = Color.White, fontSize = 17.sp)

            Spacer(Modifier.height(35.dp))

            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                        listen()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007C91)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("🎙  TALK TO VEER", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { speak("Namaste Boss! VEER aapke liye taiyaar hai.") },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("TEST VOICE", color = cyan)
            }

            Spacer(Modifier.height(18.dp))
            Text("Your assistant. Your command.", color = Color.Gray, fontSize = 12.sp)
        }
    }
}
