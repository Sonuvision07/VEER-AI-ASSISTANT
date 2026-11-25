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
    val memories = remember {
        val saved = prefs.getString("veer_memories", null)
        val restored = mutableListOf<String>()
        try {
            if (!saved.isNullOrBlank()) {
                val array = org.json.JSONArray(saved)
                for (i in 0 until array.length()) {
                    val value = array.optString(i)
                    if (value.isNotBlank()) restored.add(value)
                }
            }
        } catch (_: Exception) {}
        mutableStateListOf<String>().apply { addAll(restored.takeLast(100)) }
    }

    LaunchedEffect(memories.size) {
        val saved = org.json.JSONArray()
        memories.takeLast(100).forEach { saved.put(it) }
        prefs.edit().putString("veer_memories", saved.toString()).apply()
    }

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
                "calculator" -> context.startActivity(Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_CALCULATOR"))
                "camera" -> context.startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE))
                "maps" -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=nearby places")))
                "dialer" -> context.startActivity(Intent(Intent.ACTION_DIAL))
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
            addReply("Boss, AI brain connect karne ke liye Settings mein apni Gemini API key save karein.")
            showSettings = true
            return
        }
        val prompt = buildString {
            if (memories.isNotEmpty()) {
                append("Relevant saved notes:\\n")
                memories.takeLast(12).forEach { append("- ").append(it).append("\\n") }
                append("\\n")
            }
            messages.takeLast(8).forEach { append(it.first).append(": ").append(it.second).append("\\n") }
            append("Current request: ").append(question)
        }
        loading = true
        status = "CONNECTING TO AI..."
        Thread {
            var answer = ""
            val models = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.1-flash-lite")
            for ((index, model) in models.withIndex()) {
                try {
                    val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    val generation = JSONObject().put("maxOutputTokens", 900)
                    if (model == "gemini-3.8-flash") generation.put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
                    if (model == "gemini-3.6-flash") generation.put("thinkingConfig", JSONObject().put("thinkingLevel", "MINIMAL"))
                    val systemText = "You are VEER, a fast, accurate personal AI assistant. Reply in natural Hindi/Hinglish matching the user. Be concise by default and answer simple questions directly. For complex tasks, give a clear short plan and practical steps. Use relevant saved preferences and recent context. Never invent facts or claim actions you did not perform. The Android app can only open Settings, YouTube, browser, Calculator, Camera, Maps and phone dialer after user approval. Protect privacy. Address the user as Boss naturally, not in every sentence."
                    val body = JSONObject()
                        .put("systemInstruction", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", systemText))))
                        .put("contents", org.json.JSONArray().put(JSONObject().put("role", "user").put("parts", org.json.JSONArray().put(JSONObject().put("text", prompt)))))
                        .put("generationConfig", generation)
                    val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 10000
                        readTimeout = 25000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        setRequestProperty("x-goog-api-key", key)
                    }
                    val code: Int
                    val raw: String
                    try {
                        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                        code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    } finally { connection.disconnect() }
                    if (code in 200..299) {
                        val json = JSONObject(raw)
                        val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        val result = StringBuilder()
                        if (parts != null) for (i in 0 until parts.length()) result.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
                        answer = if (result.isNotBlank()) result.toString().trim() else "Boss, AI se khaali response mila. Dobara try karein."
                        break
                    }
                    val detail = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                    if (code == 400 || code == 401 || code == 403 || code == 404) {
                        answer = "AI service error ($code). " + (detail ?: "API key ya model access check karein.")
                        break
                    }
                    if (index < models.lastIndex && code in listOf(429, 500, 502, 503, 504)) {
                        status = "SWITCHING AI ENGINE..."
                        continue
                    }
                    answer = if (code in listOf(429, 500, 502, 503, 504))
                        "Boss, Google AI servers abhi busy hain. VEER ne backup engines bhi try kiye, lekin abhi response nahi mila. Thodi der baad try karein."
                    else "AI service error ($code). " + (detail ?: "Please try again.")
                    break
                } catch (_: Exception) {
                    status = if (index < models.lastIndex) "TRYING BACKUP AI..." else "AI CONNECTION ISSUE"
                    if (index == models.lastIndex) answer = "Boss, AI connection timeout ya internet issue hai. Network check karke dobara try karein."
                }
            }
            if (answer.isBlank()) answer = "Boss, AI response nahi mila. Please try again."
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
            text.startsWith("yaad rakho ") || text.startsWith("remember ") || text.startsWith("save memory ") -> {
                val note = when {
                    text.startsWith("yaad rakho ") -> clean.substringAfter("yaad rakho ").trim()
                    text.startsWith("remember ") -> clean.substringAfter("remember ").trim()
                    else -> clean.substringAfter("save memory ").trim()
                }
                if (note.isNotBlank()) {
                    if (memories.none { it.equals(note, ignoreCase = true) }) memories.add(note)
                    addReply("Boss, note VEER ki private local memory mein save kar diya.")
                } else addReply("Boss, bataiye kya yaad rakhna hai.")
            }
            text.contains("meri yaadein") || text.contains("yaadein dikhao") || text.contains("what do you remember") || text.contains("show memories") -> {
                if (memories.isEmpty()) addReply("Boss, abhi koi personal note save nahi hai. Yaad rakho bolkar note save kar sakte hain.")
                else addReply("Boss, meri local memory mein ye notes hain:\\n" + memories.mapIndexed { index, note -> (index + 1).toString() + ". " + note }.joinToString("\\n"))
            }
            text.startsWith("yaad hatao ") || text.startsWith("forget ") || text.startsWith("delete memory ") -> {
                val target = when {
                    text.startsWith("yaad hatao ") -> clean.substringAfter("yaad hatao ").trim()
                    text.startsWith("forget ") -> clean.substringAfter("forget ").trim()
                    else -> clean.substringAfter("delete memory ").trim()
                }
                val removed = memories.removeAll { it.equals(target, ignoreCase = true) }
                addReply(if (removed) "Boss, matching note local memory se hata diya." else "Boss, exact matching note nahi mila.")
            }
            text == "saari yaadein hatao" || text == "clear all memories" || text == "delete all memories" -> {
                memories.clear()
                addReply("Boss, VEER ki saari saved local notes clear kar di gayi hain.")
            }
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
            text.contains("chrome kholo") || text.contains("browser kholo") || text.contains("internet kholo") -> {
                pendingAction = "browser"
                showApproval = true
                addReply("Boss, browser kholne ki permission chahiye. Neeche Approve dabayein.")
            }
            text.contains("calculator kholo") || text.contains("calculator open") || text.contains("hisab kitab") -> {
                pendingAction = "calculator"
                showApproval = true
                addReply("Boss, Calculator kholne ki permission chahiye. Neeche Approve dabayein.")
            }
            text.contains("camera kholo") || text.contains("camera open") -> {
                pendingAction = "camera"
                showApproval = true
                addReply("Boss, Camera kholne ki permission chahiye. Neeche Approve dabayein.")
            }
            text.contains("maps kholo") || text.contains("map kholo") || text.contains("google maps") -> {
                pendingAction = "maps"
                showApproval = true
                addReply("Boss, Maps kholne ki permission chahiye. Neeche Approve dabayein.")
            }
            text.contains("dialer kholo") || text.contains("phone kholo") || text.contains("number dial") -> {
                pendingAction = "dialer"
                showApproval = true
                addReply("Boss, Phone dialer kholne ki permission chahiye. VEER khud call nahi karega. Neeche Approve dabayein.")
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
                HologramCore(
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
            "calculator" -> "Calculator kholna"
            "camera" -> "Camera kholna"
            "maps" -> "Maps kholna"
            "dialer" -> "Phone dialer kholna (call nahi lagegi)"
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
private fun HologramCore(modifier: Modifier = Modifier, active: Boolean) {
    val transition = rememberInfiniteTransition(label = "veer_core")
    val pulse by transition.animateFloat(0.96f, 1.035f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val rotation by transition.animateFloat(0f, 360f,
        animationSpec = infiniteRepeatable(tween(12000, easing = androidx.compose.animation.core.LinearEasing)), label = "rotation")
    val glow = if (active) Color(0xFF8AF8FF) else Color(0xFF39BFEA)
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val u = size.minDimension / 190f
        val outer = 77f * u
        drawCircle(glow.copy(alpha = 0.045f), radius = outer * pulse, center = Offset(cx, cy))
        drawCircle(glow.copy(alpha = 0.16f), radius = 66f * u, center = Offset(cx, cy), style = Stroke(1f * u))
        drawCircle(glow.copy(alpha = 0.48f), radius = 53f * u, center = Offset(cx, cy), style = Stroke(1.3f * u))
        drawCircle(glow.copy(alpha = 0.18f), radius = 39f * u, center = Offset(cx, cy), style = Stroke(0.8f * u))
        drawArc(glow.copy(alpha = 0.85f), rotation, 108f, false,
            Offset(cx - 73f * u, cy - 73f * u), androidx.compose.ui.geometry.Size(146f * u, 146f * u),
            style = Stroke(2f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        drawArc(Color.White.copy(alpha = 0.5f), rotation + 180f, 48f, false,
            Offset(cx - 59f * u, cy - 59f * u), androidx.compose.ui.geometry.Size(118f * u, 118f * u),
            style = Stroke(1.2f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        val a1 = Math.toRadians(rotation.toDouble())
        val a2 = Math.toRadians((-rotation * 0.62f).toDouble())
        drawCircle(Color.White, 2.7f * u, Offset(cx + kotlin.math.cos(a1).toFloat() * outer, cy + kotlin.math.sin(a1).toFloat() * outer))
        drawCircle(glow, 2f * u, Offset(cx + kotlin.math.cos(a2).toFloat() * 56f * u, cy + kotlin.math.sin(a2).toFloat() * 56f * u))
        drawCircle(glow.copy(alpha = 0.12f), 29f * u * pulse, Offset(cx, cy))
        drawCircle(glow.copy(alpha = 0.72f), 19f * u, Offset(cx, cy), style = Stroke(1.4f * u))
        drawCircle(Color.White.copy(alpha = 0.92f), 4f * u, Offset(cx, cy))
        drawLine(glow.copy(alpha = 0.35f), Offset(cx - 92f * u, cy), Offset(cx - 82f * u, cy), 1f * u)
        drawLine(glow.copy(alpha = 0.35f), Offset(cx + 82f * u, cy), Offset(cx + 92f * u, cy), 1f * u)
        drawLine(glow.copy(alpha = 0.35f), Offset(cx, cy - 92f * u), Offset(cx, cy - 82f * u), 1f * u)
        drawLine(glow.copy(alpha = 0.35f), Offset(cx, cy + 82f * u), Offset(cx, cy + 92f * u), 1f * u)
    }
}
