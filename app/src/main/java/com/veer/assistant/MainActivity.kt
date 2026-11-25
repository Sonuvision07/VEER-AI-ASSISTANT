package com.veer.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
    val prefs = remember { context.getSharedPreferences("veer_settings", 0) }
    val cyan = Color(0xFF00E5FF)
    val dark = Color(0xFF050B16)

    var status by remember { mutableStateOf("VEER ONLINE") }
    var typedCommand by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var keyDraft by remember { mutableStateOf(apiKey) }
    var showSettings by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val messages = remember {
        mutableStateListOf(
            "VEER" to "Namaste Boss! Main VEER hoon. Aap text ya voice se baat kar sakte hain."
        )
    }

    DisposableEffect(context) {
        val engine = TextToSpeech(context) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale("hi", "IN")
                tts?.setSpeechRate(0.94f)
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
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "VEER_REPLY")
    }

    fun addReply(message: String) {
        messages.add("VEER" to message)
        speak(message)
        status = "VEER ONLINE"
    }

    fun askGemini(question: String) {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            addReply("Boss, AI brain connect karne ke liye Settings mein apni Gemini API key save karein. API key ke bina main sirf basic phone commands chala sakta hoon.")
            showSettings = true
            return
        }
        loading = true
        status = "AI THINKING..."
        Thread {
            var answer: String
            try {
                val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + Uri.encode(key))
                val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
                val body = JSONObject()
                    .put("system_instruction", JSONObject().put("parts", org.json.JSONArray().put(
                        JSONObject().put("text", "You are VEER, a helpful personal AI assistant. Reply naturally in the same language as the user, especially Hindi or Hinglish. Address the user respectfully as Boss. Be accurate, concise, and never claim to have performed phone actions unless the app actually did them.")
                    )))
                    .put("contents", org.json.JSONArray().put(
                        JSONObject().put("role", "user").put("parts", org.json.JSONArray().put(
                            JSONObject().put("text", question)
                        ))
                    ))
                    .put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", 1200))
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val raw = stream.bufferedReader().use { it.readText() }
                connection.disconnect()
                if (code !in 200..299) {
                    val detail = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                    answer = "AI service error ($code). " + (detail ?: "API key ya model settings check karein.")
                } else {
                    val json = JSONObject(raw)
                    answer = json.optJSONArray("candidates")
                        ?.optJSONObject(0)?.optJSONObject("content")
                        ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                        ?.takeIf { !it.isNullOrBlank() }
                        ?: "Boss, AI se khaali response mila. Dobara try karein."
                }
            } catch (e: Exception) {
                answer = "Boss, internet ya AI connection mein problem aa gayi. " + (e.message ?: "Please try again.")
            }
            Handler(Looper.getMainLooper()).post {
                loading = false
                addReply(answer)
            }
        }.start()
    }

    fun processCommand(command: String) {
        val clean = command.trim()
        if (clean.isEmpty()) return
        messages.add("BOSS" to clean)
        val text = clean.lowercase(Locale.getDefault())
        when {
            text.contains("time") || text.contains("samay") || text.contains("kitne baje") -> {
                val now = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                addReply("Boss, abhi time hai $now")
            }
            text.contains("date") || text.contains("tarikh") || text.contains("aaj kya") -> {
                val date = SimpleDateFormat("dd MMMM yyyy", Locale("hi", "IN")).format(Date())
                addReply("Boss, aaj ki tarikh hai $date")
            }
            text.contains("settings kholo") || text.contains("setting kholo") || text.contains("open settings") -> {
                try {
                    context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    addReply("Settings khol di hain Boss.")
                } catch (_: Exception) { addReply("Boss, Settings nahi khul paayi.") }
            }
            text.contains("youtube kholo") || text == "youtube" || text.contains("open youtube") -> {
                val launch = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                if (launch != null) {
                    context.startActivity(launch)
                    addReply("YouTube khol diya hai Boss.")
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")))
                    addReply("YouTube browser mein khol raha hoon Boss.")
                }
            }
            text.contains("chrome kholo") || text.contains("browser kholo") -> {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
                addReply("Browser khol diya hai Boss.")
            }
            else -> askGemini(clean)
        }
    }

    fun listen() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "MICROPHONE PERMISSION REQUIRED"
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status = "SPEECH SERVICE NOT AVAILABLE"
            addReply("Boss, is phone par speech recognition service available nahi hai.")
            return
        }
        recognizer?.destroy()
        val speech = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status = "LISTENING..." }
            override fun onBeginningOfSpeech() { status = "HEARING YOUR VOICE" }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status = "PROCESSING..." }
            override fun onError(error: Int) { status = "TAP TO TRY AGAIN" }
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
        try { speech.startListening(intent) } catch (_: Exception) { status = "VOICE ERROR" }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else status = "MICROPHONE PERMISSION DENIED"
    }

    DisposableEffect(Unit) {
        onDispose { recognizer?.destroy(); recognizer = null }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = cyan,
        background = dark,
        surface = Color(0xFF102238),
        onSurface = Color.White
    )) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(dark, Color(0xFF101F35)))
            )
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("VEER", color = cyan, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Text("PERSONAL AI ASSISTANT", color = Color.LightGray, fontSize = 11.sp)
                    }
                    TextButton(onClick = { keyDraft = apiKey; showSettings = true }) {
                        Text("AI SETTINGS", color = cyan)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier.size(92.dp).border(2.dp, cyan.copy(alpha = 0.8f), CircleShape)
                        .background(Color(0xFF102D43), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text("◉", color = cyan, fontSize = 54.sp) }
                Spacer(Modifier.height(8.dp))
                Text(if (loading) "AI THINKING..." else status, color = cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0x66071220), RoundedCornerShape(16.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages) { item ->
                        val isBoss = item.first == "BOSS"
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (isBoss) Alignment.End else Alignment.Start
                        ) {
                            Text(if (isBoss) "BOSS" else "VEER", color = if (isBoss) Color(0xFFFFD180) else cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Surface(color = if (isBoss) Color(0xFF24445A) else Color(0xFF142A40), shape = RoundedCornerShape(12.dp)) {
                                Text(item.second, color = Color.White, modifier = Modifier.padding(10.dp), fontSize = 14.sp)
                            }
                        }
                    }
                    if (loading) item { Text("VEER soch raha hai...", color = cyan, fontSize = 13.sp) }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = typedCommand,
                    onValueChange = { typedCommand = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3,
                    label = { Text("Boss, yahan message likhein") },
                    placeholder = { Text("Hindi ya English mein kuch bhi poochhein") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = cyan, unfocusedBorderColor = Color(0xFF31536B),
                        cursorColor = cyan, focusedLabelColor = cyan
                    )
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val q = typedCommand.trim()
                            if (q.isNotEmpty() && !loading) { typedCommand = ""; processCommand(q) }
                        },
                        enabled = !loading,
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF164A63))
                    ) { Text("SEND", fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
                            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007C91))
                    ) { Text("🎙 SPEAK", fontWeight = FontWeight.Bold) }
                }
                Text("Voice recognition ke liye phone ki speech service aur internet zaroori ho sakta hai.", color = Color.Gray, fontSize = 10.sp)
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("VEER AI Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gemini API key yahan save karein. Key aapke phone ke app storage mein rahegi; GitHub par upload nahi hogi.", fontSize = 13.sp)
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Gemini API key") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Text("API key Google AI Studio se banayi ja sakti hai. Free-tier limits aur availability Google ke rules par depend karti hain.", color = Color.Gray, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    apiKey = keyDraft.trim()
                    prefs.edit().putString("gemini_api_key", apiKey).apply()
                    showSettings = false
                    messages.add("VEER" to if (apiKey.isNotEmpty()) "Boss, API key is device par save ho gayi. Ab AI chat try kar sakte hain." else "API key hata di gayi hai.")
                }) { Text("SAVE") }
            },
            dismissButton = {
                TextButton(onClick = { keyDraft = ""; apiKey = ""; prefs.edit().remove("gemini_api_key").apply(); showSettings = false }) { Text("REMOVE KEY") }
            }
        )
    }
}
