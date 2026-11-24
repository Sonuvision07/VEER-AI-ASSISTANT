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
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VeerScreen() }
    }
}

@Composable
fun VeerScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf("SYSTEM ONLINE") }
    var heard by remember { mutableStateOf("Boss, main sun raha hoon.") }
    var reply by remember { mutableStateOf("Namaste boss! Main VEER hoon.") }
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
        }
    }

    fun speak(message: String) {
        reply = message
        status = "VEER SPEAKING"
        tts?.let {
            it.language = Locale("hi", "IN")
            it.setPitch(0.82f)
            it.setSpeechRate(0.88f)
            it.speak(message, TextToSpeech.QUEUE_FLUSH, null, "VEER")
        }
    }

    fun processCommand(command: String) {
        val q = command.lowercase(Locale.ROOT)
        val answer = when {
            "hello" in q || "नमस्ते" in q || "namaste" in q ->
                "Namaste boss! Bataiye, main aapki kya madad karun?"
            "time" in q || "समय" in q || "kitne baje" in q ->
                "Abhi samay hai " +
                java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(java.util.Date())
            "date" in q || "तारीख" in q || "tarikh" in q ->
                "Aaj ki tareekh hai " +
                java.text.SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(java.util.Date())
            "who are you" in q || "tum kaun" in q ->
                "Main VEER hoon, aapka personal AI assistant."
            "youtube" in q ->
                "Boss, YouTube kholne ki koshish karta hoon."
            else ->
                "Boss, maine aapki baat sun li. Abhi mera AI brain connect hona baaki hai."
        }

        if ("youtube" in q) {
            val launch = context.packageManager
                .getLaunchIntentForPackage("com.google.android.youtube")
            if (launch != null) context.startActivity(launch)
        }
        speak(answer)
        status = "SYSTEM ONLINE"
    }

    fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status = "SPEECH SERVICE NOT AVAILABLE"
            return
        }
        status = "LISTENING..."
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status = "LISTENING..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status = "PROCESSING..." }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val words = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )?.firstOrNull()
                recognizer.destroy()
                if (words != null) {
                    heard = words
                    processCommand(words)
                } else {
                    status = "NOT HEARD. TRY AGAIN"
                }
            }

            override fun onError(error: Int) {
                recognizer.destroy()
                status = "VOICE ERROR: TRY AGAIN"
            }
        })
        recognizer.startListening(intent)
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) listen() else status = "MICROPHONE PERMISSION REQUIRED"
    }

    val cyan = Color(0xFF35E8FF)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF061B2B), Color(0xFF020812), Color.Black)
                )
            )
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("V E E R", color = cyan, fontSize = 36.sp,
            fontWeight = FontWeight.Bold)
        Text("PERSONAL AI ASSISTANT", color = Color.LightGray,
            fontSize = 12.sp)

        Spacer(Modifier.height(35.dp))

        Box(
            modifier = Modifier.size(190.dp).background(
                Brush.radialGradient(
                    listOf(Color(0xFF126C91), Color(0xFF07304A), Color.Transparent)
                ), CircleShape
            ),
            contentAlignment = Alignment.Center
        ) {
            Text("◉", color = cyan, fontSize = 110.sp)
        }

        Spacer(Modifier.height(25.dp))
        Text(status, color = cyan, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))

        Text("YOU SAID:", color = Color.Gray, fontSize = 11.sp)
        Text(heard, color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        Text("VEER:", color = cyan, fontSize = 11.sp)
        Text(reply, color = Color.White, fontSize = 16.sp)

        Spacer(Modifier.height(25.dp))
        Button(
            onClick = {
                if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED) {
                    listen()
                } else {
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF087E9B)
            )
        ) {
            Text("🎙  TALK TO VEER")
        }

        TextButton(onClick = { speak("Namaste boss! VEER taiyar hai.") }) {
            Text("TEST VEER VOICE", color = cyan)
        }
    }
}
