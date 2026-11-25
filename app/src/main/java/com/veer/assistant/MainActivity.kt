package com.veer.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


private const val VEER_KEY_ALIAS = "veer_gemini_api_key"

private fun encryptApiKey(value: String): String? {
    return try {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var secret = store.getKey(VEER_KEY_ALIAS, null) as? SecretKey
        if (secret == null) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    VEER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            secret = generator.generateKey()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    } catch (_: Exception) { null }
}

private fun decryptApiKey(encoded: String): String {
    if (encoded.isBlank()) return ""
    return try {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val secret = store.getKey(VEER_KEY_ALIAS, null) as? SecretKey ?: return ""
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        if (combined.size <= 12) return ""
        val iv = combined.copyOfRange(0, 12)
        val payload = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
        String(cipher.doFinal(payload), Charsets.UTF_8)
    } catch (_: Exception) { "" }
}

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
    var apiKey by remember { mutableStateOf(decryptApiKey(prefs.getString("gemini_api_key", "") ?: "")) }
    var keyDraft by remember { mutableStateOf(apiKey) }
    var showSettings by remember { mutableStateOf(false) }
    var showApproval by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val messages = remember {
        val saved = prefs.getString("chat_history", null)
        val restored = mutableListOf<Pair<String, String>>()
        try {
            if (!saved.isNullOrBlank()) {
                val array = org.json.JSONArray(saved)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val role = item.optString("role")
                    val message = item.optString("message")
                    if ((role == "BOSS" || role == "VEER") && message.isNotBlank()) restored.add(role to message)
                }
            }
        } catch (_: Exception) {}
        mutableStateListOf<Pair<String, String>>().apply {
            if (restored.isEmpty()) add("VEER" to "Namaste Boss! Main VEER hoon. Aap text ya voice se baat kar sakte hain.")
            else addAll(restored.takeLast(100))
        }
    }

    LaunchedEffect(messages.size) {
        val history = org.json.JSONArray()
        messages.takeLast(100).forEach { item ->
            history.put(JSONObject().put("role", item.first).put("message", item.second))
        }
        prefs.edit().putString("chat_history", history.toString()).apply()
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

    fun runApprovedAction(action: String) {
        try {
            when (action) {
                "settings" -> context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                "youtube" -> {
                    val launch = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                    if (launch != null) context.startActivity(launch)
                    else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")))
                }
                "browser" -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
            }
            addReply("Boss, aapki permission se action complete kar diya.")
        } catch (_: Exception) {
            addReply("Boss, ye action phone par complete nahi ho paaya.")
        }
        pendingAction = ""
        showApproval = false
    }

    fun askGemini(question: String) {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            addReply("Boss, AI brain connect karne ke liye Settings mein apni Gemini API key save karein. API key ke bina main sirf basic phone commands chala sakta hoon.")
            showSettings = true
            return
        }
        val prompt = buildString {
            val contextMessages = messages.takeLast(12)
            if (contextMessages.isNotEmpty()) {
                append("Recent conversation for context:\n")
                contextMessages.forEach { append(it.first).append(": ").append(it.second).append("\n") }
                append("\nCurrent user message: ")
            }
            append(question)
        }
        loading = true
        status = "AI THINKING..."
        Thread {
            var answer: String
            try {
                val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/interactions")
                val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 45000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("x-goog-api-key", key)
                }
                val body = JSONObject()
                    .put("model", "gemini-3.8-flash")
                    .put("input", prompt)
                    .put("system_instruction", "You are VEER, a capable, warm, highly practical personal AI assistant. Reply in the same language as the user, especially natural Hindi/Hinglish. Address the user as Boss without overusing it. First understand the actual request and answer it directly. Give accurate, useful, step-by-step guidance when needed; explain unfamiliar concepts simply. Use recent conversation context to avoid asking repeated questions. If information may be outdated or uncertain, say so clearly instead of inventing facts. Never claim you searched the web, opened an app, changed a setting, sent a message, or completed an action unless a connected tool actually did it. This app currently supports only Settings, YouTube, and browser launch with explicit user approval; for unsupported actions explain the limitation honestly. Keep replies concise but complete.")
                    .put("generation_config", JSONObject().put("temperature", 0.45).put("max_output_tokens", 1800))
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
                    val directText = json.optString("output_text", "")
                    val steps = json.optJSONArray("steps")
                    val collected = StringBuilder()
                    if (steps != null) {
                        for (i in 0 until steps.length()) {
                            val step = steps.optJSONObject(i) ?: continue
                            if (step.optString("type") == "model_output") {
                                val parts = step.optJSONArray("content") ?: continue
                                for (j in 0 until parts.length()) {
                                    val part = parts.optJSONObject(j) ?: continue
                                    if (part.optString("type") == "text") collected.append(part.optString("text"))
                                }
                            }
                        }
                    }
                    answer = directText.takeIf { it.isNotBlank() }
                        ?: collected.toString().takeIf { it.isNotBlank() }
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
                pendingAction = "settings"
                showApproval = true
                addReply("Boss, Settings kholne ki permission chahiye. Neeche Approve dabayein.")
            }
            text.contains("youtube kholo") || text == "youtube" || text.contains("open youtube") -> {
                pendingAction = "youtube"
                showApproval = true
                addReply("Boss, YouTube kholne ki permission chahiye. Neeche Approve dabayein.")
            }
            text.contains("chrome kholo") || text.contains("browser kholo") -> {
                pendingAction = "browser"
                showApproval = true
                addReply("Boss, browser kholne ki permission chahiye. Neeche Approve dabayein.")
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            messages.clear()
                            messages.add("VEER" to "Boss, nayi chat shuru kar di.")
                        }) { Text("NEW CHAT", color = Color.LightGray, fontSize = 10.sp) }
                        TextButton(onClick = { keyDraft = apiKey; showSettings = true }) {
                            Text("AI SETTINGS", color = cyan)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                HologramFace(
                    modifier = Modifier.size(190.dp),
                    active = loading || status.contains("LISTENING") || status.contains("HEARING")
                )
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

    if (showApproval) {
        val actionLabel = when (pendingAction) {
            "settings" -> "Phone Settings kholna"
            "youtube" -> "YouTube kholna"
            "browser" -> "Browser kholna"
            else -> "Action chalana"
        }
        AlertDialog(
            onDismissRequest = { showApproval = false; pendingAction = "" },
            title = { Text("VEER Permission") },
            text = { Text("Boss, kya aap VEER ko ye action karne ki permission dete hain?\\n\\n$actionLabel") },
            confirmButton = {
                TextButton(onClick = { runApprovedAction(pendingAction) }) { Text("APPROVE") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingAction = ""
                    showApproval = false
                    addReply("Theek hai Boss, action cancel kar diya.")
                }) { Text("CANCEL") }
            }
        )
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
                    val candidate = keyDraft.trim()
                    if (candidate.isEmpty()) {
                        apiKey = ""
                        prefs.edit().remove("gemini_api_key").apply()
                        messages.add("VEER" to "API key hata di gayi hai.")
                        showSettings = false
                    } else {
                        val encrypted = encryptApiKey(candidate)
                        if (encrypted != null) {
                            apiKey = candidate
                            prefs.edit().putString("gemini_api_key", encrypted).apply()
                            messages.add("VEER" to "Boss, API key securely is phone par save ho gayi. Ab AI chat try karein.")
                            showSettings = false
                        } else {
                            messages.add("VEER" to "Boss, API key encrypt nahi ho paayi. Dobara try karein.")
                        }
                    }
                }) { Text("SAVE") }
            },
            dismissButton = {
                TextButton(onClick = { keyDraft = ""; apiKey = ""; prefs.edit().remove("gemini_api_key").apply(); showSettings = false }) { Text("REMOVE KEY") }
            }
        )
    }
}


@Composable
private fun HologramFace(modifier: Modifier = Modifier, active: Boolean) {
    val transition = rememberInfiniteTransition(label = "veer_hologram")
    val pulse by transition.animateFloat(
        initialValue = 0.94f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val orbit by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "orbit"
    )
    val glow = if (active) Color(0xFF66F7FF) else Color(0xFF00D9F5)
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val unit = size.minDimension / 190f
        drawCircle(glow.copy(alpha = 0.07f), radius = 82f * unit * pulse, center = Offset(cx, cy))
        drawCircle(glow.copy(alpha = 0.18f), radius = 72f * unit, center = Offset(cx, cy), style = Stroke(1.4f * unit))
        drawCircle(glow.copy(alpha = 0.55f), radius = 62f * unit, center = Offset(cx, cy), style = Stroke(1.1f * unit))
        drawCircle(glow.copy(alpha = 0.35f), radius = 78f * unit, center = Offset(cx, cy), style = Stroke(0.8f * unit))
        val orbitRad = Math.toRadians(orbit.toDouble())
        val ox = cx + kotlin.math.cos(orbitRad).toFloat() * 78f * unit
        val oy = cy + kotlin.math.sin(orbitRad).toFloat() * 78f * unit
        drawCircle(Color.White.copy(alpha = 0.9f), radius = 3f * unit, center = Offset(ox, oy))
        // Minimal holographic face silhouette, eyes and animated mouth.
        drawCircle(glow.copy(alpha = 0.8f), radius = 43f * unit, center = Offset(cx, cy), style = Stroke(1.5f * unit))
        drawCircle(glow.copy(alpha = 0.28f), radius = 38f * unit, center = Offset(cx, cy))
        drawLine(glow, Offset(cx - 20f * unit, cy - 7f * unit), Offset(cx - 8f * unit, cy - 7f * unit), 3f * unit)
        drawLine(glow, Offset(cx + 8f * unit, cy - 7f * unit), Offset(cx + 20f * unit, cy - 7f * unit), 3f * unit)
        val mouthWidth = if (active) 13f else 8f
        drawLine(glow.copy(alpha = 0.9f), Offset(cx - mouthWidth * unit, cy + 15f * unit), Offset(cx + mouthWidth * unit, cy + 15f * unit), 2f * unit)
        drawLine(glow.copy(alpha = 0.4f), Offset(cx - 27f * unit, cy + 30f * unit), Offset(cx + 27f * unit, cy + 30f * unit), 0.8f * unit)
        drawCircle(Color.White.copy(alpha = 0.8f), radius = 2f * unit, center = Offset(cx, cy - 57f * unit))
    }
}
