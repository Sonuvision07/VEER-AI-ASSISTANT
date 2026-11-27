package com.veer.assistant

import android.Manifest
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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


private fun safelyReleaseSpeechRecognizer(speech: SpeechRecognizer?) {
    try { speech?.cancel() } catch (_: Exception) {}
    try { speech?.destroy() } catch (_: Exception) {}
}

private const val VEER_KEY_ALIAS = "veer_gemini_api_key"
private const val VEER_PC_TOKEN_ALIAS = "veer_pc_agent_token"
private const val VEER_CHAT_ALIAS = "veer_chat_history"
private const val VEER_MEMORY_ALIAS = "veer_saved_memories"
private const val VEER_ENCRYPTED_PREFIX = "enc:v1:"

private fun readPrivateStore(prefs: android.content.SharedPreferences, key: String, alias: String): String? {
    val stored = prefs.getString(key, null) ?: return "[]"
    if (stored.startsWith(VEER_ENCRYPTED_PREFIX)) {
        val plain = decryptSecret(stored.removePrefix(VEER_ENCRYPTED_PREFIX), alias)
        return plain.takeIf { it.isNotBlank() }
    }
    // Migrate legacy plaintext in place. If encryption fails, retain the old value.
    val encrypted = encryptSecret(stored, alias) ?: return stored
    prefs.edit().putString(key, VEER_ENCRYPTED_PREFIX + encrypted).commit()
    return stored
}

private fun writePrivateStore(prefs: android.content.SharedPreferences, key: String, alias: String, value: String): Boolean {
    val encrypted = encryptSecret(value, alias) ?: return false
    return prefs.edit().putString(key, VEER_ENCRYPTED_PREFIX + encrypted).commit()
}

private fun encryptSecret(value: String, alias: String): String? {
    return try {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var secret = store.getKey(alias, null) as? SecretKey
        if (secret == null) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
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

private fun decryptSecret(encoded: String, alias: String): String {
    if (encoded.isBlank()) return ""
    return try {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val secret = store.getKey(alias, null) as? SecretKey ?: return ""
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        if (combined.size <= 12) return ""
        val iv = combined.copyOfRange(0, 12)
        val payload = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
        String(cipher.doFinal(payload), Charsets.UTF_8)
    } catch (_: Exception) { "" }
}

private fun encryptApiKey(value: String): String? = encryptSecret(value, VEER_KEY_ALIAS)
private fun decryptApiKey(encoded: String): String = decryptSecret(encoded, VEER_KEY_ALIAS)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VeerApp() }
    }
}

@Composable
private fun VeerActionGlyph(kind: String, modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier) {
        val sw = size.minDimension * 0.085f
        if (kind == "mic") {
            drawRoundRect(tint, topLeft = Offset(size.width * 0.38f, size.height * 0.08f), size = androidx.compose.ui.geometry.Size(size.width * 0.24f, size.height * 0.55f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.12f), style = Stroke(sw))
            drawArc(tint, 0f, 180f, false, topLeft = Offset(size.width * 0.20f, size.height * 0.25f), size = androidx.compose.ui.geometry.Size(size.width * 0.60f, size.height * 0.52f), style = Stroke(sw))
            drawLine(tint, Offset(size.width * 0.50f, size.height * 0.76f), Offset(size.width * 0.50f, size.height * 0.91f), sw)
            drawLine(tint, Offset(size.width * 0.34f, size.height * 0.92f), Offset(size.width * 0.66f, size.height * 0.92f), sw)
        } else {
            drawRoundRect(tint, topLeft = Offset(size.width * 0.08f, size.height * 0.25f), size = androidx.compose.ui.geometry.Size(size.width * 0.84f, size.height * 0.60f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.10f), style = Stroke(sw))
            drawRoundRect(tint, topLeft = Offset(size.width * 0.28f, size.height * 0.13f), size = androidx.compose.ui.geometry.Size(size.width * 0.24f, size.height * 0.13f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.04f))
            drawCircle(tint, radius = size.minDimension * 0.16f, center = Offset(size.width * 0.52f, size.height * 0.55f), style = Stroke(sw))
        }
    }
}

@Composable
private fun VeerBrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.12f, h * 0.20f)
            lineTo(w * 0.47f, h * 0.82f)
            quadraticTo(w * 0.50f, h * 0.88f, w * 0.54f, h * 0.81f)
            lineTo(w * 0.88f, h * 0.20f)
        }
        drawPath(path, Brush.linearGradient(listOf(Color(0xFF83F4FF), Color(0xFF28A8FF), Color(0xFF9B7BFF))), style = Stroke(width = w * 0.12f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(Color(0xFF8CEFFF), radius = w * 0.055f, center = Offset(w * 0.50f, h * 0.10f))
        drawCircle(Color(0xFF27C8FF).copy(alpha = 0.22f), radius = w * 0.46f, style = Stroke(width = w * 0.025f))
    }
}

@Composable
fun VeerApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("veer_settings", 0) }
    val localAiEngine = remember(context) { VeerOnDeviceAiEngine.create(context) }
    val localAiScope = rememberCoroutineScope()
    val cyan = Color(0xFF00E5FF)
    val dark = Color(0xFF050B16)

    var status by remember { mutableStateOf("VEER ONLINE") }
    var typedCommand by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf(decryptApiKey(prefs.getString("gemini_api_key", "") ?: "")) }
    var openAiApiKey by remember { mutableStateOf(decryptApiKey(prefs.getString("openai_api_key", "") ?: "")) }
    var openAiKeyDraft by remember { mutableStateOf(openAiApiKey) }
    var claudeApiKey by remember { mutableStateOf(decryptApiKey(prefs.getString("claude_api_key", "") ?: "")) }
    var claudeKeyDraft by remember { mutableStateOf(claudeApiKey) }
    var selectedProvider by remember { mutableStateOf(prefs.getString("ai_provider", "AUTO") ?: "AUTO") }
    var pcAgentUrl by remember { mutableStateOf(prefs.getString("pc_agent_url", "") ?: "") }
    var pcAgentUrlDraft by remember { mutableStateOf(pcAgentUrl) }
    var pcAgentToken by remember { mutableStateOf(decryptSecret(prefs.getString("pc_agent_token", "") ?: "", VEER_PC_TOKEN_ALIAS)) }
    var pcAgentTokenDraft by remember { mutableStateOf(pcAgentToken) }
    var pcAgentStatus by remember { mutableStateOf(if (pcAgentUrl.isNotBlank() && pcAgentToken.isNotBlank()) "Saved; connection not checked" else "Not configured") }
    var pcAgentChecking by remember { mutableStateOf(false) }
    var showProviderMenu by remember { mutableStateOf(false) }
    val fallbackAttempted = remember { mutableStateListOf<String>() }
    var keyDraft by remember { mutableStateOf(apiKey) }
    var showSettings by remember { mutableStateOf(false) }
    val modelInstalledAtLaunch = remember(localAiEngine) { localAiEngine.isModelInstalled }
    var localModelInstalled by remember { mutableStateOf(modelInstalledAtLaunch) }
    var localAiReady by remember { mutableStateOf(false) }
    var modelDownloading by remember { mutableStateOf(false) }
    var modelDownloadPercent by remember { mutableStateOf(0) }
    var showBackConfirm by remember { mutableStateOf(false) }
    var showClearChatConfirm by remember { mutableStateOf(false) }
    var showApproval by remember { mutableStateOf(false) }
    var approvalGeneration by remember { mutableLongStateOf(0L) }
    var pendingAction by remember { mutableStateOf("") }
    val agentApprovalSession = remember { VeerAgentApprovalSession() }
    var pendingAgentApproval by remember { mutableStateOf<VeerAgentApprovalRequest?>(null) }
    var pendingMusicUrl by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var voiceMode by remember { mutableStateOf(false) }
    var backgroundVoiceActive by remember { mutableStateOf(prefs.getBoolean("background_voice_enabled", false)) }
    var pendingBackgroundVoicePermission by remember { mutableStateOf(false) }
    var attachedImageBase64 by remember { mutableStateOf<String?>(null) }
    var attachedMimeType by remember { mutableStateOf("image/jpeg") }
    var attachedText by remember { mutableStateOf<String?>(null) }
    var attachedFileName by remember { mutableStateOf<String?>(null) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsSpeaking by remember { mutableStateOf(false) }
    var ttsMouthOpenTarget by remember { mutableFloatStateOf(0f) }
    var pendingTtsMessage by remember { mutableStateOf<String?>(null) }
    var activeTtsUtteranceId by remember { mutableStateOf<String?>(null) }
    var ttsUtteranceSequence by remember { mutableLongStateOf(0L) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val memoriesStorageReadable = remember {
        val stored = prefs.getString("veer_memories", null)
        stored == null || !stored.startsWith(VEER_ENCRYPTED_PREFIX) ||
            decryptSecret(stored.removePrefix(VEER_ENCRYPTED_PREFIX), VEER_MEMORY_ALIAS).isNotBlank()
    }
    val memories = remember {
        val saved = readPrivateStore(prefs, "veer_memories", VEER_MEMORY_ALIAS)
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
        if (memoriesStorageReadable) writePrivateStore(prefs, "veer_memories", VEER_MEMORY_ALIAS, saved.toString())
    }

    val messagesStorageReadable = remember {
        val stored = prefs.getString("chat_history", null)
        stored == null || !stored.startsWith(VEER_ENCRYPTED_PREFIX) ||
            decryptSecret(stored.removePrefix(VEER_ENCRYPTED_PREFIX), VEER_CHAT_ALIAS).isNotBlank()
    }
    val messages = remember {
        val saved = readPrivateStore(prefs, "chat_history", VEER_CHAT_ALIAS)
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

    LaunchedEffect(messages.toList()) {
        val history = org.json.JSONArray()
        messages.takeLast(100).forEach { item ->
            history.put(JSONObject().put("role", item.first).put("message", item.second))
        }
        if (messagesStorageReadable) writePrivateStore(prefs, "chat_history", VEER_CHAT_ALIAS, history.toString())
    }

    LaunchedEffect(localAiEngine) {
        if (modelInstalledAtLaunch) {
            try {
                withContext(Dispatchers.Default) { localAiEngine.initialize() }
                localAiReady = true
            } catch (error: Exception) {
                messages.add("VEER" to "Offline AI auto-start nahi ho paaya. Settings mein dobara activate karein: " + (error.message ?: "unknown error"))
            }
        }
    }

    DisposableEffect(localAiEngine) {
        onDispose { localAiEngine.close() }
    }

    DisposableEffect(context) {
        // The callback can run only after the engine is created. Keep a holder so
        // the callback can compare identity without illegally capturing a val
        // that is still being initialized.
        val engineHolder = arrayOfNulls<TextToSpeech>(1)
        val engine = TextToSpeech(context) { result ->
            // Ignore callbacks from an engine whose effect was disposed/replaced.
            val currentEngine = engineHolder[0]
            if (currentEngine != null && tts === currentEngine) {
                if (result == TextToSpeech.SUCCESS) {
                    currentEngine.language = Locale("hi", "IN")
                    currentEngine.setSpeechRate(0.94f)
                    ttsReady = true
                } else {
                    ttsReady = false
                    pendingTtsMessage = null
                    if (prefs.getBoolean("background_voice_enabled", false)) {
                        try {
                            ContextCompat.startForegroundService(context, Intent(context, VeerVoiceService::class.java).setAction(VeerVoiceService.ACTION_RESUME))
                        } catch (_: Exception) {
                            backgroundVoiceActive = false
                        }
                    }
                    status = "VOICE OUTPUT UNAVAILABLE"
                }
            }
        }
        engineHolder[0] = engine
        tts = engine
        onDispose {
            // Invalidate a late onInit callback before shutting the engine down.
            if (tts === engine) tts = null
            ttsReady = false
            ttsSpeaking = false
            ttsMouthOpenTarget = 0f
            pendingTtsMessage = null
            activeTtsUtteranceId = null
            engine.stop()
            engine.shutdown()
        }
    }

    fun resumeBackgroundVoiceIfEnabled() {
        if (!prefs.getBoolean("background_voice_enabled", false)) {
            backgroundVoiceActive = false
            return
        }
        if (!backgroundVoiceActive) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, VeerVoiceService::class.java).setAction(VeerVoiceService.ACTION_RESUME))
        } catch (error: Exception) {
            backgroundVoiceActive = false
            status = "BACKGROUND VOICE STOPPED"
            messages.add("VEER" to "Background voice dobara start nahi ho paayi. Voice button se phir try karein.")
        }
    }

    // Restore the persisted voice-mode state after Activity/process recreation.
    LaunchedEffect(Unit) {
        if (backgroundVoiceActive && prefs.getBoolean("background_voice_enabled", false)) {
            resumeBackgroundVoiceIfEnabled()
        }
    }

    fun speak(message: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            // Keep the reply until Android finishes initializing TextToSpeech.
            pendingTtsMessage = message
            return
        }
        try {
            val preferredLocale = VeerSpeechLanguage.localeForText(message)
            val languageResult = engine.setLanguage(preferredLocale)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale("hi", "IN"))
            }
            ttsUtteranceSequence += 1L
            val utteranceId = "VEER_REPLY_$ttsUtteranceSequence"
            activeTtsUtteranceId = utteranceId
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Handler(Looper.getMainLooper()).post {
                        if (utteranceId == activeTtsUtteranceId) {
                            ttsSpeaking = true
                            ttsMouthOpenTarget = 0.28f
                        }
                    }
                }
                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && start >= 0 && end > start && start < message.length) {
                        val spokenPart = message.substring(start, end.coerceAtMost(message.length)).lowercase(Locale.ROOT)
                        val openness = when {
                            spokenPart.any { it in "आािीुूेैोौंःअ" } -> 0.9f
                            spokenPart.any { it in "aou" } -> 0.9f
                            spokenPart.any { it in "ei" } -> 0.58f
                            spokenPart.any { it.isLetterOrDigit() } -> 0.22f
                            else -> 0.08f
                        }
                        Handler(Looper.getMainLooper()).post {
                            if (utteranceId == activeTtsUtteranceId) ttsMouthOpenTarget = openness
                        }
                    }
                }
                override fun onDone(utteranceId: String?) {
                    Handler(Looper.getMainLooper()).post {
                        if (utteranceId == activeTtsUtteranceId) {
                            ttsSpeaking = false
                            ttsMouthOpenTarget = 0f
                            activeTtsUtteranceId = null
                            resumeBackgroundVoiceIfEnabled()
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Handler(Looper.getMainLooper()).post {
                        if (utteranceId == activeTtsUtteranceId) {
                            ttsSpeaking = false
                            ttsMouthOpenTarget = 0f
                            activeTtsUtteranceId = null
                            resumeBackgroundVoiceIfEnabled()
                        }
                    }
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    Handler(Looper.getMainLooper()).post {
                        if (utteranceId == activeTtsUtteranceId) {
                            ttsSpeaking = false
                            ttsMouthOpenTarget = 0f
                            activeTtsUtteranceId = null
                            resumeBackgroundVoiceIfEnabled()
                        }
                    }
                }
            })
            val result = engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                ttsSpeaking = false
                ttsMouthOpenTarget = 0f
                activeTtsUtteranceId = null
                status = "VOICE OUTPUT ERROR"
                resumeBackgroundVoiceIfEnabled()
            }
        } catch (error: Exception) {
            // A disconnected or already-shutdown TTS engine must not leave the
            // background microphone paused while the service waits for a reply.
            ttsSpeaking = false
            ttsMouthOpenTarget = 0f
            activeTtsUtteranceId = null
            status = "VOICE OUTPUT ERROR"
            Log.w("VEER", "Text-to-speech request failed", error)
            resumeBackgroundVoiceIfEnabled()
        }
    }

    fun addReply(message: String) {
        messages.add("VEER" to message)
        if (voiceMode) speak(message)
        status = if (voiceMode) "VEER READY" else "ONLINE"
    }

    LaunchedEffect(ttsReady, pendingTtsMessage) {
        val pending = pendingTtsMessage
        if (ttsReady && !pending.isNullOrBlank()) {
            pendingTtsMessage = null
            speak(pending)
        }
    }

    fun launchSocialApp(packageName: String, fallbackUrl: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) context.startActivity(launch)
        else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
    }

    fun runApprovedAction(action: String): Boolean {
        val allowedActions = setOf(
            "settings", "youtube", "music", "browser", "calculator", "camera", "maps",
            "dialer", "instagram", "facebook", "whatsapp", "x", "telegram",
            "youtube_studio", "gmail"
        )
        if (action !in allowedActions) {
            approvalGeneration += 1L
            agentApprovalSession.clear()
            pendingAgentApproval = null
            pendingAction = ""
            pendingMusicUrl = null
            showApproval = false
            addReply("Boss, ye action supported ya approved list mein nahi hai. Maine ise run nahi kiya.")
            return false
        }
        return try {
            when (action) {
                "settings" -> context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                "youtube" -> {
                    val launch = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                    if (launch != null) context.startActivity(launch)
                    else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")))
                }
                "music" -> {
                    val searchUrl = pendingMusicUrl
                    if (searchUrl != null) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)))
                    } else {
                        val launch = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.music")
                        if (launch != null) context.startActivity(launch)
                        else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com")))
                    }
                }
                "browser" -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
                "calculator" -> context.startActivity(Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_CALCULATOR"))
                "camera" -> context.startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE))
                "maps" -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=nearby places")))
                "dialer" -> context.startActivity(Intent(Intent.ACTION_DIAL))
                "instagram" -> launchSocialApp("com.instagram.android", "https://www.instagram.com")
                "facebook" -> launchSocialApp("com.facebook.katana", "https://www.facebook.com")
                "whatsapp" -> launchSocialApp("com.whatsapp", "https://www.whatsapp.com")
                "x" -> launchSocialApp("com.twitter.android", "https://x.com")
                "telegram" -> launchSocialApp("org.telegram.messenger", "https://t.me")
                "youtube_studio" -> launchSocialApp("com.google.android.apps.youtube.creator", "https://studio.youtube.com")
                "gmail" -> launchSocialApp("com.google.android.gm", "https://mail.google.com")
            }
            addReply("Boss, aapki permission se action complete kar diya.")
            true
        } catch (_: Exception) {
            addReply("Boss, ye action phone par complete nahi ho paaya.")
            false
        } finally {
            approvalGeneration += 1L
            pendingAction = ""
            agentApprovalSession.clear()
            pendingAgentApproval = null
            pendingMusicUrl = null
            showApproval = false
        }
    }

    fun runApprovedAgentTool(call: AgentToolCall) {
        val validatedAction = VeerAgentToolPolicy.toPendingAppAction(call)
        if (validatedAction == null || validatedAction != pendingAction) {
            approvalGeneration += 1L
            agentApprovalSession.clear()
            pendingAgentApproval = null
            pendingAction = ""
            pendingMusicUrl = null
            showApproval = false
            addReply("Boss, pending agent action verify nahi hua. Maine ise run nahi kiya.")
            return
        }
        agentApprovalSession.clear()
        pendingAgentApproval = null
        try {
            VeerAndroidAppToolExecutor { action ->
                if (runApprovedAction(action)) {
                    "Approved Android action launched successfully."
                } else {
                    "Approved action could not be launched on this phone."
                }
            }.execute(call)
        } catch (_: Exception) {
            approvalGeneration += 1L
            agentApprovalSession.clear()
            pendingAgentApproval = null
            pendingAction = ""
            pendingMusicUrl = null
            showApproval = false
            addReply("Boss, agent action safely stop kar diya.")
        }
    }

    var providerFallback: ((String, String, String?) -> Boolean)? = null

    fun askOpenAI(question: String, imageBase64: String? = null) {
        val key = openAiApiKey.trim()
        if (key.isEmpty()) { addReply("Boss, OpenAI API key Settings mein add karein."); showSettings = true; return }
        val prompt = buildString {
            if (memories.isNotEmpty()) { append("Saved context:\n"); memories.takeLast(12).forEach { append("- ").append(it).append("\n") } }
            messages.dropLast(1).takeLast(8).forEach { append(it.first).append(": ").append(it.second).append("\n") }
            if (!attachedText.isNullOrBlank()) append("\nAttached document text:\n").append(attachedText)
            append("\nCurrent request: ").append(question)
        }
        loading = true
        status = "CONNECTING TO OPENAI..."
        Thread {
            var answer = ""
            try {
                val content = org.json.JSONArray()
                if (!imageBase64.isNullOrBlank()) {
                    val dataUrl = "data:" + attachedMimeType + ";base64," + imageBase64
                    content.put(JSONObject().put("type", "input_image").put("image_url", dataUrl))
                }
                content.put(JSONObject().put("type", "input_text").put("text", prompt))
                val input = org.json.JSONArray()
                    .put(JSONObject().put("role", "developer").put("content", "You are VEER, a mature, highly capable personal assistant. Reply naturally in Hindi/Hinglish matching the user. Be direct, thoughtful and concise by default. Never claim an action you did not perform."))
                    .put(JSONObject().put("role", "user").put("content", content))
                val body = JSONObject().put("model", "gpt-6-luna").put("store", false).put("input", input)
                val connection = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 15000; readTimeout = 60000; doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $key")
                }
                val code: Int
                val raw: String
                try {
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    raw = stream?.use { VeerBoundedHttpBody.readUtf8(it) } ?: ""
                } finally { connection.disconnect() }
                if (code in 200..299) {
                    val json = JSONObject(raw)
                    val output = json.optJSONArray("output")
                    val result = StringBuilder()
                    if (output != null) for (i in 0 until output.length()) {
                        val item = output.optJSONObject(i) ?: continue
                        val parts = item.optJSONArray("content") ?: continue
                        for (k in 0 until parts.length()) {
                            val part = parts.optJSONObject(k) ?: continue
                            if (part.optString("type") == "output_text") result.append(part.optString("text"))
                        }
                    }
                    answer = if (result.isNotBlank()) result.toString().trim() else "Boss, OpenAI se khaali response mila."
                } else {
                    val detail = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                    answer = "OpenAI API error ($code). " + (detail ?: "API key, model access aur billing check karein.")
                }
            } catch (_: Exception) { answer = "Boss, OpenAI connection fail hua. Internet aur API access check karke dobara try karein." }
            Handler(Looper.getMainLooper()).post {
                val failed = answer.startsWith("OpenAI API error") || answer.startsWith("Boss, OpenAI connection fail")
                val moved = failed && providerFallback?.invoke("OPENAI", question, imageBase64) == true
                if (!moved) { loading = false; addReply(answer) }
            }
        }.start()
    }

    fun askClaude(question: String) {
        val key = claudeApiKey.trim()
        if (key.isEmpty()) { addReply("Boss, Claude API key Settings mein add karein."); showSettings = true; return }
        val prompt = buildString {
            append("You are VEER, a mature personal AI assistant. Reply naturally in Hindi/Hinglish matching the user. Be accurate, direct and concise by default. Never claim actions you did not perform.\n\n")
            if (memories.isNotEmpty()) { append("Saved context:\n"); memories.takeLast(12).forEach { append("- ").append(it).append("\n") } }
            messages.dropLast(1).takeLast(8).forEach { append(it.first).append(": ").append(it.second).append("\n") }
            if (!attachedText.isNullOrBlank()) append("\nAttached document text:\n").append(attachedText)
            append("\nCurrent request: ").append(question)
        }
        loading = true
        status = "CONNECTING TO CLAUDE..."
        Thread {
            var failure: String? = null
            val answer = try { ClaudeApiClient.generateReply(key, prompt) }
            catch (e: Exception) { failure = e.message ?: "Claude connection failed."; "Claude connection failed. " + failure }
            Handler(Looper.getMainLooper()).post {
                val moved = failure != null && providerFallback?.invoke("CLAUDE", question, null) == true
                if (!moved) { loading = false; addReply(answer) }
            }
        }.start()
    }

    fun askGemini(question: String, imageBase64: String? = null) {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            addReply("Boss, AI brain connect karne ke liye Settings mein apni Gemini API key save karein.")
            showSettings = true
            return
        }
        val prompt = buildString {
            if (memories.isNotEmpty()) {
                append("Relevant saved notes:\n")
                memories.takeLast(12).forEach { append("- ").append(it).append("\n") }
                append("\n")
            }
            messages.dropLast(1).takeLast(8).forEach { append(it.first).append(": ").append(it.second).append("\n") }
            if (!attachedText.isNullOrBlank()) append("\n\nAttached document text:\n").append(attachedText)
            append("\nCurrent request: ").append(question)
        }
        loading = true
        status = "CONNECTING TO AI..."
        Thread {
            var answer = ""
            val models = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
            for ((index, model) in models.withIndex()) {
                try {
                    val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    val generation = JSONObject().put("maxOutputTokens", 900)
                    if (model == "gemini-3.8-flash") generation.put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
                    val systemText = "You are VEER, a fast, accurate personal AI assistant. Reply in natural Hindi/Hinglish matching the user. Be concise by default and answer simple questions directly. For complex tasks, give a clear short plan and practical steps. Use relevant saved preferences and recent context. Never invent facts or claim actions you did not perform. The Android app can only open Settings, YouTube, browser, Calculator, Camera, Maps and phone dialer after user approval. Protect privacy. Address the user as Boss naturally, not in every sentence."
                    val requestParts = org.json.JSONArray()
                    if (!imageBase64.isNullOrBlank()) requestParts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", attachedMimeType).put("data", imageBase64)))
                    requestParts.put(JSONObject().put("text", prompt))
                    val body = JSONObject()
                        .put("systemInstruction", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", systemText))))
                        .put("contents", org.json.JSONArray().put(JSONObject().put("role", "user").put("parts", requestParts)))
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
                        raw = stream?.use { VeerBoundedHttpBody.readUtf8(it) } ?: ""
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
                val failed = answer.startsWith("AI service error") ||
                    answer.startsWith("Boss, Google AI servers") ||
                    answer.startsWith("Boss, AI connection timeout") ||
                    answer.startsWith("Boss, AI response nahi mila")
                val moved = failed && providerFallback?.invoke("GEMINI", question, imageBase64) == true
                if (!moved) { loading = false; addReply(answer) }
            }
        }.start()
    }

    providerFallback = { failedProvider, question, image ->
        val preferred = when (selectedProvider) {
            "GEMINI" -> listOf("GEMINI", "OPENAI", "CLAUDE")
            "CLAUDE" -> listOf("CLAUDE", "OPENAI", "GEMINI")
            "OPENAI" -> listOf("OPENAI", "GEMINI", "CLAUDE")
            else -> listOf("OPENAI", "GEMINI", "CLAUDE")
        }
        if (failedProvider !in fallbackAttempted) fallbackAttempted.add(failedProvider)
        val next = preferred.firstOrNull { provider ->
            provider !in fallbackAttempted && when (provider) {
                "OPENAI" -> openAiApiKey.isNotBlank()
                "GEMINI" -> apiKey.isNotBlank()
                "CLAUDE" -> claudeApiKey.isNotBlank() && image == null
                else -> false
            }
        }
        if (next == null) false else {
            fallbackAttempted.add(next)
            status = "FALLING BACK TO $next..."
            when (next) {
                "OPENAI" -> askOpenAI(question, image)
                "GEMINI" -> askGemini(question, image)
                "CLAUDE" -> askClaude(question)
            }
            true
        }
    }

    fun processCommand(command: String) {
        val clean = command.trim()
        if (clean.isEmpty()) return
        messages.add("BOSS" to clean)
        val approvalPhrase = VeerApprovalPhrase.parse(clean)
        if (showApproval && approvalPhrase != VeerApprovalPhrase.Decision.NONE) {
            if (approvalPhrase == VeerApprovalPhrase.Decision.APPROVE) {
                approvalGeneration += 1L
                val agentRequest = pendingAgentApproval
                val agentCall = agentRequest?.let { request ->
                    agentApprovalSession.approve(request.id)?.takeIf { it == request.call }
                }
                val approvedAction = if (agentRequest != null) {
                    agentCall?.let { VeerAgentToolPolicy.toPendingAppAction(it) }
                        ?.takeIf { it == pendingAction }
                } else pendingAction
                if (approvedAction.isNullOrBlank()) {
                    agentRequest?.let { request -> agentApprovalSession.cancel(request.id) }
                    pendingAgentApproval = null
                    pendingAction = ""
                    pendingMusicUrl = null
                    showApproval = false
                    addReply("Boss, pending approval request verify nahi hui. Action run nahi kiya.")
                } else if (agentRequest != null && agentCall != null) {
                    pendingAgentApproval = null
                    runApprovedAgentTool(agentCall)
                } else {
                    runApprovedAction(approvedAction)
                }
            } else {
                approvalGeneration += 1L
                pendingAgentApproval?.let { request -> agentApprovalSession.cancel(request.id) }
                pendingAgentApproval = null
                pendingAction = ""
                pendingMusicUrl = null
                showApproval = false
                addReply("Theek hai Boss, action cancel kar diya.")
            }
            return
        }
        val text = clean.lowercase(Locale.getDefault())
        // Handle simple everyday conversation and arithmetic locally before any cloud provider.
        VeerLocalBrain.reply(clean, memories)?.let { localReply ->
            addReply(localReply)
            return
        }
        when {
            text.startsWith("yaad rakho ") || text.startsWith("remember ") -> {
                val prefixLength = if (text.startsWith("yaad rakho ")) "yaad rakho ".length else "remember ".length
                val note = clean.drop(prefixLength).trim()
                if (note.isNotBlank()) {
                    if (memories.none { it.equals(note, ignoreCase = true) }) {
                        memories.add(note)
                        if (memories.size > 100) memories.removeAt(0)
                    }
                    addReply("Note local memory mein save kiya.")
                } else addReply("Kya yaad rakhna hai?")
            }
            text.contains("meri yaadein") || text.contains("yaadein dikhao") || text.contains("show memories") -> addReply(if (memories.isEmpty()) "Abhi koi note save nahi hai." else "Saved notes:\n" + memories.mapIndexed { i, note -> "${i+1}. $note" }.joinToString("\n"))
            text.startsWith("forget ") || text.startsWith("yaad hatao ") -> {
                val prefixLength = if (text.startsWith("forget ")) "forget ".length else "yaad hatao ".length
                val target = clean.drop(prefixLength).trim()
                val memoryIndex = findMemoryIndex(memories, target)
                val removed = memoryIndex >= 0
                if (removed) memories.removeAt(memoryIndex)
                addReply(if (removed) "Note delete kiya." else "Note nahi mila.")
            }
            text=="clear all memories" || text=="saari yaadein hatao" -> {memories.clear();addReply("Saved memories clear kar di.")}
            text.contains("time") || text.contains("samay") || text.contains("kitne baje") -> addReply("Abhi time hai "+SimpleDateFormat("hh:mm a",Locale.getDefault()).format(Date()))
            text.contains("tarikh") || text.contains("date") || text.contains("aaj kya") -> addReply("Aaj "+SimpleDateFormat("dd MMMM yyyy",Locale("hi","IN")).format(Date()))
            text.contains("gaana chalao") || text.contains("song play") || text.contains("music chalao") || text.contains("play song") -> {
                val query=clean.replace(Regex("(?i)^(gaana|song|music)\\s*(chalao|play)?\\s*"),"").trim()
                pendingMusicUrl = if (query.isBlank()) null else "https://music.youtube.com/search?q=" + Uri.encode(query)
                pendingAction = "music"
                agentApprovalSession.clear()
                pendingAgentApproval = null
                approvalGeneration += 1L
                showApproval = true
            }
            VeerCommandRouter.route(clean) != null -> {
                val routed = VeerCommandRouter.route(clean)!!
                val agentCall = AgentToolCall(
                    name = VeerAgentToolPolicy.ANDROID_LAUNCH_APP,
                    arguments = mapOf("app" to routed.action),
                    risk = AgentRisk.DEVICE_CHANGE
                )
                val request = agentApprovalSession.request(agentCall)
                if (request == null) {
                    addReply("Boss, ek approval pehle se pending hai ya request safety check mein fail hui. Pehle use approve/cancel karein.")
                } else {
                    pendingAgentApproval = request
                    pendingAction = request.action
                    approvalGeneration += 1L
                    showApproval = true
                }
            }
            else -> {
                val image = attachedImageBase64
                if (localAiReady && image == null && attachedText.isNullOrBlank()) {
                    val localPrompt = VeerLocalPromptBuilder.build(
                        memories = memories.toList(),
                        history = messages.dropLast(1).takeLast(8).toList(),
                        currentRequest = clean
                    )
                    loading = true
                    status = "LOCAL AI THINKING..."
                    localAiScope.launch {
                        try {
                            val answer = withContext(Dispatchers.Default) { localAiEngine.generateReply(localPrompt) }
                            loading = false
                            addReply(answer.ifBlank { "Boss, local model se khaali jawab mila. Dobara try karein." })
                        } catch (error: Exception) {
                            loading = false
                            status = "LOCAL AI ERROR"
                            addReply("Boss, offline AI run nahi ho paaya: " + (error.message ?: "unknown error"))
                        }
                    }
                    attachedImageBase64 = null; attachedText = null; attachedFileName = null; attachedMimeType = "image/jpeg"
                    return
                }
                if (localAiReady && (image != null || !attachedText.isNullOrBlank())) {
                    addReply("Boss, VEER ka current offline model abhi text-only hai. Image aur document analysis local model mein abhi add nahi hua; normal text chat offline chalegi.")
                    attachedImageBase64 = null; attachedText = null; attachedFileName = null; attachedMimeType = "image/jpeg"
                    return
                }
                if (!localAiReady && apiKey.isBlank() && openAiApiKey.isBlank() && claudeApiKey.isBlank()) {
                    addReply(if (localModelInstalled) "Offline AI model phone mein hai, lekin activate nahi hua. Settings kholkar ACTIVATE OFFLINE AI dabayein." else "VEER ka apna offline AI model abhi install nahi hai. Settings mein 347 MB model download karein; iske liye API key nahi chahiye.")
                    showSettings = true
                    return
                }
                fallbackAttempted.clear()
                when (selectedProvider) {
                    "CLAUDE" -> {
                        if (claudeApiKey.isNotBlank() && image == null) askClaude(clean)
                        else if (image != null && openAiApiKey.isNotBlank()) askOpenAI(clean, image)
                        else if (apiKey.isNotBlank()) askGemini(clean, image)
                        else addReply("Boss, Claude ke liye API key chahiye. Image request ke liye Gemini ya OpenAI key add karein.")
                    }
                    "OPENAI" -> {
                        if (openAiApiKey.isNotBlank()) askOpenAI(clean, image)
                        else if (apiKey.isNotBlank()) askGemini(clean, image)
                        else if (claudeApiKey.isNotBlank() && image == null) askClaude(clean)
                        else addReply("Boss, selected provider ki API key Settings mein add karein.")
                    }
                    "GEMINI" -> {
                        if (apiKey.isNotBlank()) askGemini(clean, image)
                        else if (openAiApiKey.isNotBlank()) askOpenAI(clean, image)
                        else if (claudeApiKey.isNotBlank() && image == null) askClaude(clean)
                        else addReply("Boss, selected provider ki API key Settings mein add karein.")
                    }
                    else -> {
                        if (openAiApiKey.isNotBlank()) askOpenAI(clean, image)
                        else if (apiKey.isNotBlank()) askGemini(clean, image)
                        else if (claudeApiKey.isNotBlank() && image == null) askClaude(clean)
                        else { addReply("Boss, pehle Settings mein kam se kam ek AI provider ki API key add karein."); showSettings = true }
                    }
                }
                attachedImageBase64=null; attachedText=null; attachedFileName=null; attachedMimeType="image/jpeg"
            }
        }
    }

    fun listen(languageAttempt: Int = 0) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "MICROPHONE PERMISSION REQUIRED"
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status = "SPEECH SERVICE NOT AVAILABLE"
            addReply("Boss, is phone par speech recognition service available nahi hai.")
            return
        }
        // Invalidate the old listener before teardown so late callbacks cannot be accepted
        // while a replacement recognizer is being created.
        val previousRecognizer = recognizer
        recognizer = null
        try { previousRecognizer?.cancel() } catch (_: Exception) {}
        try { previousRecognizer?.destroy() } catch (_: Exception) {}
        val speech = try { VeerSpeechRecognizerFactory.create(context, VeerRecognitionLanguage.useOnDeviceRecognizer(languageAttempt)) } catch (error: Exception) {
            status = "VOICE SETUP ERROR"
            addReply("Boss, voice recognition start nahi ho paayi. Microphone permission aur phone ka speech service check karein.")
            return
        }
        recognizer = speech
        try {
            speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (recognizer !== speech) return
                status = "LISTENING..."
            }
            override fun onBeginningOfSpeech() {
                if (recognizer !== speech) return
                status = "HEARING YOUR VOICE"
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (recognizer !== speech) return
                status = "PROCESSING..."
            }
            override fun onError(error: Int) {
                if (recognizer !== speech) return
                status = "VOICE INPUT ERROR"
                if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE &&
                    VeerRecognitionLanguage.hasNext(languageAttempt)) {
                    status = "SWITCHING SPEECH LANGUAGE..."
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (recognizer === speech) {
                            listen(VeerRecognitionLanguage.nextAttempt(languageAttempt))
                        }
                    }, 250L)
                    return
                }
                if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED &&
                    VeerRecognitionLanguage.useOnDeviceRecognizer(languageAttempt)) {
                    status = "SWITCHING TO SYSTEM SPEECH..."
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (recognizer === speech) {
                            listen(VeerRecognitionLanguage.systemFallbackAttempt())
                        }
                    }, 350L)
                    return
                }
                Log.w("VEER", "Speech recognition failed with error code $error")
                val detail = when (error) {
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Hindi/English speech pack phone ke speech service mein available nahi hai. System language fallback bhi try ho chuka hai."
                    SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission nahi mili."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service ko network chahiye ho sakta hai."
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Awaaz samajh nahi aayi. Dobara bolkar try karein."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice service busy hai. Kuch seconds baad try karein."
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Android speech service disconnect ho gayi. Internet check karein, Google app/Speech Services by Google update karein, phir mic dobara dabayein."
                    else -> "Voice recognition error ($error). Dobara try karein."
                }
                // Terminal errors leave this single-shot recognizer idle; release it now
                // instead of retaining it until the next manual microphone attempt.
                if (recognizer === speech) {
                    recognizer = null
                    try { speech.destroy() } catch (_: Exception) {}
                }
                addReply("Boss, $detail")
            }
            override fun onResults(results: Bundle?) {
                if (recognizer !== speech) return
                val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                // A single-shot recognition session is finished once results arrive.
                // Release it before processing the command or updating the UI.
                recognizer = null
                try { speech.destroy() } catch (_: Exception) {}
                if (!words.isNullOrEmpty()) processCommand(words[0])
                else addReply("Boss, awaaz clear nahi mili. Mic dabakar phir boliye.")
                status = "VEER ONLINE"
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            VeerRecognitionLanguage.localeForAttempt(languageAttempt)?.let { locale ->
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
            speech.startListening(intent)
        } catch (error: Exception) {
            Log.w("VEER", "Speech recognizer setup/start failed", error)
            // Listener registration and startListening can both throw before callbacks arrive.
            if (recognizer === speech) {
                recognizer = null
            }
            try {
                speech.destroy()
            } catch (cleanupError: Exception) {
                Log.w("VEER", "Failed recognizer cleanup after setup/start failure", cleanupError)
            }
            status = "VOICE ERROR"
            addReply("Boss, voice recognition start nahi ho paayi. Dobara mic dabakar try karein.")
        }
    }

    fun bitmapToBase64(bitmap: Bitmap): String {
        val scaled = if (bitmap.width > 1280 || bitmap.height > 1280) {
            val ratio = minOf(1280f / bitmap.width, 1280f / bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        } else bitmap
        val bytes = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, bytes)
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            status = "LOADING IMAGE..."
            Thread {
                val encoded = try { context.contentResolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input)?.let { bitmapToBase64(it) } } } catch (_: Exception) { null }
                Handler(Looper.getMainLooper()).post {
                    attachedImageBase64 = encoded
                    status = if (encoded != null) "IMAGE ATTACHED" else "IMAGE LOAD FAILED"
                    if (encoded == null) addReply("Image open nahi ho paayi. Doosri image select karein.")
                }
            }.start()
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        attachedImageBase64 = bitmap?.let { bitmapToBase64(it) }
        attachedMimeType = "image/jpeg"
        attachedText = null
        attachedFileName = if (bitmap != null) "Camera photo" else null
        status = if (bitmap != null) "CAMERA IMAGE ATTACHED" else "CAMERA CANCELLED"
    }

    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            status = "READING FILE..."
            Thread {
                var encoded: String? = null
                var extractedText: String? = null
                var mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                var name = "Selected file"
                try {
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                    }
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        if (bytes.size > 18 * 1024 * 1024) throw IllegalArgumentException("File is larger than 18 MB")
                        if (mime == "text/plain" || name.endsWith(".txt", true) || name.endsWith(".md", true) || name.endsWith(".csv", true)) {
                            extractedText = String(bytes, Charsets.UTF_8).take(120000)
                        } else if (mime == "application/pdf" || mime.startsWith("image/")) {
                            if (mime == "application/octet-stream" && name.endsWith(".pdf", true)) mime = "application/pdf"
                            encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        } else throw IllegalArgumentException("Supported files: PDF, images, TXT, MD and CSV")
                    }
                } catch (e: Exception) { encoded = null; extractedText = null }
                Handler(Looper.getMainLooper()).post {
                    if (encoded != null || !extractedText.isNullOrBlank()) {
                        attachedImageBase64 = encoded
                        attachedMimeType = mime
                        attachedText = extractedText
                        attachedFileName = name
                        status = "FILE READY"
                    } else addReply("Boss, file read nahi ho paayi. PDF, image, TXT, MD ya CSV select karein. " + (if (name.isNotBlank()) name else ""))
                }
            }.start()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingBackgroundVoicePermission) {
            pendingBackgroundVoicePermission = false
            try {
                prefs.edit().putBoolean("background_voice_enabled", true).apply()
                ContextCompat.startForegroundService(context, Intent(context, VeerVoiceService::class.java))
                backgroundVoiceActive = true
                status = "BACKGROUND VOICE STARTING"
            } catch (error: Exception) {
                prefs.edit().putBoolean("background_voice_enabled", false).apply()
                backgroundVoiceActive = false
                status = "BACKGROUND VOICE COULD NOT START"
                messages.add("VEER" to "Background voice start nahi ho paayi. App ko dobara kholkar try karein.")
                Log.w("VEER", "Background voice start failed after microphone permission", error)
            }
        } else if (granted) {
            pendingBackgroundVoicePermission = false
            listen()
        } else {
            pendingBackgroundVoicePermission = false
            status = "MICROPHONE PERMISSION DENIED"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val activeRecognizer = recognizer
            recognizer = null
            safelyReleaseSpeechRecognizer(activeRecognizer)
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
                when (intent?.action) {
                    VeerVoiceService.ACTION_TRANSCRIPT -> {
                        val phrase = intent.getStringExtra("text").orEmpty()
                        if (phrase.isNotBlank()) {
                            voiceMode = true
                            processCommand(phrase)
                        }
                    }
                    VeerVoiceService.ACTION_STATUS -> {
                        status = intent.getStringExtra("status") ?: status
                        if (!prefs.getBoolean("background_voice_enabled", false)) {
                            backgroundVoiceActive = false
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(VeerVoiceService.ACTION_TRANSCRIPT)
            addAction(VeerVoiceService.ACTION_STATUS)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = cyan, background = Color.Black, surface = Color(0xFF202124), onSurface = Color.White
    )) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (voiceMode) {
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { voiceMode = false; status = "ONLINE"; val activeRecognizer = recognizer; recognizer = null; safelyReleaseSpeechRecognizer(activeRecognizer) }) { Text("‹  CHAT", color = Color.White, fontSize = 15.sp) }
                        Spacer(Modifier.weight(1f))
                        Text("VEER", color = Color(0xFF69DFFF), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { keyDraft = apiKey; showSettings = true }) { Text("•••", color = Color.LightGray, fontSize = 20.sp) }
                    }
                    Spacer(Modifier.weight(1f))
                    val faceExpression = when {
                        loading || status.contains("PROCESSING") || status.contains("CONNECTING") -> VeerFaceExpression.THINKING
                        ttsSpeaking -> VeerFaceExpression.SPEAKING
                        status.contains("LISTENING") || status.contains("HEARING") -> VeerFaceExpression.LISTENING
                        else -> VeerFaceExpression.IDLE
                    }
                    HologramFace(Modifier.fillMaxWidth().aspectRatio(0.92f), active = faceExpression != VeerFaceExpression.IDLE, expression = faceExpression, speaking = ttsSpeaking, mouthOpenTarget = ttsMouthOpenTarget)
                    Spacer(Modifier.height(18.dp))
                    Text(when { loading -> "THINKING"; backgroundVoiceActive -> "BACKGROUND VOICE ACTIVE"; status.contains("LISTENING") || status.contains("HEARING") -> "LISTENING"; else -> "I'M HERE" },
                        color = Color(0xFF78DFFF), fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(messages.lastOrNull { it.first == "VEER" }?.second ?: "Namaste. Main sun raha hoon.",
                        color = Color(0xFFE6F6FF), fontSize = 16.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 88.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth().padding(bottom = 26.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { voiceMode = false; status = "ONLINE"; val activeRecognizer = recognizer; recognizer = null; safelyReleaseSpeechRecognizer(activeRecognizer) }, shape = CircleShape, modifier = Modifier.size(58.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("×", fontSize = 27.sp) }
                        Spacer(Modifier.width(30.dp))
                        Button(onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
                            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }, shape = CircleShape, modifier = Modifier.size(76.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1677B8))) { Text("🎙", fontSize = 27.sp) }
                        Spacer(Modifier.width(30.dp))
                        OutlinedButton(onClick = {
                            if (backgroundVoiceActive) {
                                prefs.edit().putBoolean("background_voice_enabled", false).apply()
                                context.stopService(Intent(context, VeerVoiceService::class.java))
                                backgroundVoiceActive = false
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                try {
                                    prefs.edit().putBoolean("background_voice_enabled", true).apply()
                                    ContextCompat.startForegroundService(context, Intent(context, VeerVoiceService::class.java))
                                    backgroundVoiceActive = true
                                    status = "BACKGROUND VOICE STARTING"
                                } catch (_: Exception) {
                                    // The service never starts when startForegroundService throws.
                                    prefs.edit().putBoolean("background_voice_enabled", false).apply()
                                    backgroundVoiceActive = false
                                    status = "BACKGROUND VOICE COULD NOT START"
                                    messages.add("VEER" to "Background voice start nahi ho paayi. App ko dobara kholkar try karein.")
                                }
                            } else {
                                pendingBackgroundVoicePermission = true
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }, shape = CircleShape, modifier = Modifier.size(58.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text(if (backgroundVoiceActive) "■" else "BG", fontSize = 15.sp) }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { keyDraft = apiKey; showSettings = true }) { Text("☰", color = Color(0xFFB8C8E4), fontSize = 23.sp) }
                        Spacer(Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VeerBrandMark(Modifier.size(30.dp))
                            Text("VEER", color = Color(0xFFF4F8FF), fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showClearChatConfirm = true }) { Text("＋", color = Color(0xFFB8C8E4), fontSize = 25.sp) }
                    }
                    if (messages.size <= 1) {
                        Spacer(Modifier.weight(0.7f))
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(164.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x553A8BFF), Color(0x222A4D9B), Color.Transparent)), CircleShape))
                                VeerBrandMark(Modifier.size(104.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Hello, Boss", color = Color(0xFFF4F7FF), fontSize = 29.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(7.dp))
                            Text("What can I help you with today?", color = Color(0xFF9EACC4), fontSize = 15.sp)
                            Spacer(Modifier.height(28.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf("Ask anything", "Create", "Learn").forEach { suggestion ->
                                    Surface(color = Color(0xFF111C30), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263B5D))) {
                                        Text(suggestion, color = Color(0xFFD4E1F6), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            items(messages) { item ->
                                val isBoss = item.first == "BOSS"
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isBoss) Alignment.End else Alignment.Start) {
                                    Surface(color = if (isBoss) Color(0xFF30343A) else Color.Transparent, shape = RoundedCornerShape(20.dp)) {
                                        Text(item.second, color = Color(0xFFF1F1F1), modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp), fontSize = 16.sp, lineHeight = 23.sp)
                                    }
                                }
                            }
                            if (loading) item { Text("VEER is thinking…", color = Color.Gray, fontSize = 14.sp) }
                        }
                    }
                    if (attachedImageBase64 != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${attachedFileName ?: "ATTACHMENT READY"} — send a message to analyse", color = Color(0xFF70DFFF), fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { attachedImageBase64 = null; attachedText = null; attachedFileName = null }) { Text("REMOVE", color = Color.LightGray, fontSize = 11.sp) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).background(Brush.horizontalGradient(listOf(Color(0xFF111B2D), Color(0xFF101827))), RoundedCornerShape(28.dp)).border(1.dp, Color(0xFF263B5D), RoundedCornerShape(28.dp)).padding(horizontal = 7.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { documentLauncher.launch("*/*") }, contentPadding = PaddingValues(5.dp)) { Text("＋", color = Color(0xFF9CB5D8), fontSize = 25.sp) }
                        OutlinedTextField(value = typedCommand, onValueChange = { typedCommand = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text("Message VEER...", color = Color(0xFF8292AA)) }, singleLine = false, maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = Color(0xFF62CFFF)))
                        TextButton(onClick = { cameraLauncher.launch(null) }, contentPadding = PaddingValues(5.dp)) { VeerActionGlyph("camera", Modifier.size(22.dp), Color(0xFFB9C9E5)) }
                        Button(onClick = { voiceMode = true; if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(42.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF126CBA))) { VeerActionGlyph("mic", Modifier.size(21.dp), Color.White) }
                        Button(onClick = { val q = typedCommand.trim().ifBlank { "Please analyse the attached file and explain its important details." }; if (!loading && (typedCommand.isNotBlank() || attachedImageBase64 != null || !attachedText.isNullOrBlank())) { typedCommand = ""; processCommand(q) } },
                            enabled = !loading && (typedCommand.isNotBlank() || attachedImageBase64 != null || !attachedText.isNullOrBlank()), shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(42.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDAE9FF), contentColor = Color(0xFF071326))) { Text("↑", fontSize = 23.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }

    LaunchedEffect(showApproval, pendingAction, approvalGeneration) {
        if (showApproval && pendingAction.isNotBlank()) {
            val requestedAction = pendingAction
            val requestedGeneration = approvalGeneration
            kotlinx.coroutines.delay(60_000)
            if (showApproval && pendingAction == requestedAction &&
                approvalGeneration == requestedGeneration
            ) {
                // Expiry is a real cancellation: invalidate captured dialog callbacks
                // and consume the matching one-shot request so it cannot be approved later.
                approvalGeneration += 1L
                pendingAgentApproval?.let { request ->
                    agentApprovalSession.cancel(request.id)
                }
                pendingAgentApproval = null
                pendingMusicUrl = null
                pendingAction = ""
                showApproval = false
                addReply("Boss, permission request 60 seconds mein expire ho gayi. Zarurat ho to command dobara dein.")
            }
        }
    }

    if (showClearChatConfirm) {
        AlertDialog(
            onDismissRequest = { showClearChatConfirm = false },
            title = { Text("Start a new chat?") },
            text = { Text("Current chat history will be cleared from this phone. Saved memories will remain unchanged. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    showClearChatConfirm = false
                    messages.clear()
                    messages.add("VEER" to "Namaste. Main VEER hoon. Aaj kis cheez mein madad karun?")
                }) { Text("CLEAR CHAT") }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatConfirm = false }) { Text("CANCEL") }
            }
        )
    }

    if (showBackConfirm) {
        AlertDialog(
            onDismissRequest = { showBackConfirm = false },
            title = { Text("Confirm Back Action") },
            text = { Text("VEER abhi current screen par Android ka Back action karega. Kya aap approve karte hain?") },
            confirmButton = {
                TextButton(onClick = {
                    showBackConfirm = false
                    val performed = VeerAccessibilityService.performApprovedBack()
                    messages.add("VEER" to if (performed) "Back action perform ho gaya." else "Accessibility Service enabled nahi hai. Pehle Android Settings mein VEER ko manually enable karein.")
                }) { Text("APPROVE") }
            },
            dismissButton = {
                TextButton(onClick = { showBackConfirm = false }) { Text("CANCEL") }
            }
        )
    }

    if (showApproval) {
        val agentRequest = pendingAgentApproval
        val dialogGeneration = approvalGeneration
        val dialogAction = pendingAction
        val actionLabel = if (agentRequest != null) {
            VeerAgentApprovalPresentation.actionLabel(agentRequest.call)
        } else {
            VeerAgentApprovalPresentation.actionLabel(pendingAction)
        } ?: "Action chalana"
        AlertDialog(
            onDismissRequest = {
                if (approvalGeneration != dialogGeneration) return@AlertDialog
                approvalGeneration += 1L
                showApproval = false
                agentRequest?.let { request -> agentApprovalSession.cancel(request.id) }
                pendingAgentApproval = null
                pendingAction = ""
                pendingMusicUrl = null
                addReply("Permission request cancel kar di.")
            },
            title = { Text("VEER Permission") },
            text = { Text("Boss, kya aap VEER ko ye action karne ki permission dete hain?\n\n$actionLabel") },
            confirmButton = {
                TextButton(onClick = {
                    if (approvalGeneration != dialogGeneration) return@TextButton
                    approvalGeneration += 1L
                    // Capture this dialog's request, never a newer Compose state value.
                    val currentRequest = agentRequest
                    val currentCall = currentRequest?.let { request ->
                        agentApprovalSession.approve(request.id)?.takeIf { it == request.call }
                    }
                    val approvedAction = if (currentRequest != null) {
                        currentCall?.let { VeerAgentToolPolicy.toPendingAppAction(it) }
                            ?.takeIf { it == dialogAction }
                    } else dialogAction
                    if (approvedAction.isNullOrBlank()) {
                        currentRequest?.let { request -> agentApprovalSession.cancel(request.id) }
                        pendingAgentApproval = null
                        pendingAction = ""
                        pendingMusicUrl = null
                        showApproval = false
                        addReply("Boss, pending approval request verify nahi hui. Action run nahi kiya.")
                    } else if (currentRequest != null && currentCall != null) {
                        pendingAgentApproval = null
                        runApprovedAgentTool(currentCall)
                    } else {
                        runApprovedAction(approvedAction)
                    }
                }) { Text("APPROVE") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (approvalGeneration != dialogGeneration) return@TextButton
                    approvalGeneration += 1L
                    agentRequest?.let { request -> agentApprovalSession.cancel(request.id) }
                    pendingAgentApproval = null
                    pendingAction = ""
                    pendingMusicUrl = null
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
                Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("VEER OFFLINE AI", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00B8D4))
                    Text("Model status: " + when { modelDownloading -> "Downloading... " + modelDownloadPercent + "%"; localAiReady -> "Ready — answers run on this phone"; localModelInstalled -> "Downloaded — activate to start"; else -> "Not installed — approx. 347 MB download" }, fontSize = 12.sp)
                    if (modelDownloading) LinearProgressIndicator(progress = { modelDownloadPercent / 100f }, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            if (!modelDownloading && !localAiReady) {
                                localAiScope.launch {
                                    modelDownloading = true
                                    try {
                                        if (!localModelInstalled) {
                                            withContext(Dispatchers.IO) {
                                                VeerModelInstaller.install(context) { downloaded, total ->
                                                    val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
                                                    Handler(Looper.getMainLooper()).post { modelDownloadPercent = percent }
                                                }
                                            }
                                            localModelInstalled = true
                                        }
                                        withContext(Dispatchers.Default) { localAiEngine.initialize() }
                                        localAiReady = true
                                        messages.add("VEER" to "Boss, VEER ka offline AI model activate ho gaya. Ab text chat bina API key ke chalegi.")
                                    } catch (error: Exception) {
                                        messages.add("VEER" to "Offline AI setup fail hua: " + (error.message ?: "unknown error"))
                                    } finally {
                                        modelDownloading = false
                                    }
                                }
                            }
                        },
                        enabled = !modelDownloading && !localAiReady,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (localAiReady) "OFFLINE AI READY" else if (localModelInstalled) "ACTIVATE OFFLINE AI" else "DOWNLOAD & ACTIVATE (347 MB)") }
                    Text("Ek baar download ke baad text AI isi phone par chalega. Model download ke liye internet aur lagbhag 500 MB free storage chahiye. Model chhota hai, isliye jawab cloud AI jitne advanced nahi honge. Image/document AI abhi local model mein enabled nahi hai.", fontSize = 11.sp, color = Color.Gray)
                    Divider()
                    Text("AI provider keys phone par Android Keystore encryption ke saath save hoti hain; GitHub par upload nahi hoti.", fontSize = 13.sp)
                    Text("Privacy: chat history aur saved memories app ke private phone storage mein Android Keystore AES-GCM se encrypted save hoti hain. Phone ka app data clear ya uninstall hone par ye data recover nahi ho sakta. Passwords ya highly sensitive details memory mein save na karein.", fontSize = 11.sp, color = Color.Gray)
                    Divider()
                    Text("Optional Accessibility Bridge", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("VEER screen ka text nahi padhta, taps ya typing automate nahi karta. Android Settings mein ise manually enable karne ke baad bhi Back action sirf aapke alag APPROVE confirmation par chalega.", fontSize = 11.sp, color = Color.Gray)
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (_: Exception) {
                            messages.add("VEER" to "Accessibility Settings open nahi ho paayi.")
                        }
                    }) { Text("OPEN ACCESSIBILITY SETTINGS") }
                    TextButton(onClick = { showSettings = false; showBackConfirm = true }) { Text("TEST APPROVED BACK ACTION") }
                    Divider()
                    Text("WINDOWS PC COMPANION", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00B8D4))
                    Text("Tailscale Serve ka HTTPS address aur Windows agent token daalein. VEER sirf connection health test karega; abhi PC par koi command execute nahi hogi.", fontSize = 11.sp, color = Color.Gray)
                    OutlinedTextField(
                        value = pcAgentUrlDraft,
                        onValueChange = { pcAgentUrlDraft = it },
                        enabled = !pcAgentChecking,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Windows PC HTTPS address") },
                        placeholder = { Text("https://my-pc.example.ts.net") }
                    )
                    OutlinedTextField(
                        value = pcAgentTokenDraft,
                        onValueChange = { pcAgentTokenDraft = it },
                        enabled = !pcAgentChecking,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Windows agent token") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Text("Connection: $pcAgentStatus", fontSize = 11.sp, color = if (pcAgentStatus.startsWith("Connected")) Color(0xFF58D68D) else Color.Gray)
                    Button(
                        onClick = {
                            val base = try { VeerPcAgentClient.normalizeBaseUrl(pcAgentUrlDraft) } catch (error: Exception) {
                                pcAgentStatus = error.message ?: "Enter a valid Tailscale HTTPS address"
                                return@Button
                            }
                            val token = try { VeerPcAgentClient.validateToken(pcAgentTokenDraft) } catch (error: Exception) {
                                pcAgentStatus = error.message ?: "Check the Windows agent token"
                                return@Button
                            }
                            pcAgentChecking = true
                            pcAgentStatus = "Connecting securely..."
                            localAiScope.launch {
                                try {
                                    val health = withContext(Dispatchers.IO) { VeerPcAgentClient(base, token).health() }
                                    val encryptedToken = encryptSecret(token, VEER_PC_TOKEN_ALIAS)
                                    if (encryptedToken == null) {
                                        pcAgentStatus = "Connected, but token could not be encrypted"
                                    } else {
                                        prefs.edit().putString("pc_agent_url", base).putString("pc_agent_token", encryptedToken).apply()
                                        pcAgentUrl = base
                                        pcAgentToken = token
                                        pcAgentUrlDraft = base
                                        pcAgentTokenDraft = token
                                        pcAgentStatus = "Connected — " + health.agent + " v" + health.version
                                        messages.add("VEER" to "Windows PC agent se secure HTTPS connection verify ho gaya. Abhi sirf health check enabled hai.")
                                    }
                                } catch (error: Exception) {
                                    pcAgentStatus = "Connection failed: " + (error.message ?: "Check Tailscale and token")
                                } finally {
                                    pcAgentChecking = false
                                }
                            }
                        },
                        enabled = !pcAgentChecking && pcAgentUrlDraft.isNotBlank() && pcAgentTokenDraft.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (pcAgentChecking) "CONNECTING..." else "TEST & SAVE PC CONNECTION") }
                    TextButton(
                        onClick = {
                            prefs.edit().remove("pc_agent_url").remove("pc_agent_token").apply()
                            pcAgentUrl = ""
                            pcAgentUrlDraft = ""
                            pcAgentToken = ""
                            pcAgentTokenDraft = ""
                            pcAgentStatus = "Not configured"
                        },
                        enabled = !pcAgentChecking
                    ) { Text("REMOVE PC CONNECTION") }
                    Text("AI provider", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Box {
                        TextButton(onClick = { showProviderMenu = true }) { Text("Selected: " + when (selectedProvider) { "GEMINI" -> "Gemini"; "OPENAI" -> "OpenAI"; "CLAUDE" -> "Claude"; else -> "AUTO (fallback order)" }) }
                        DropdownMenu(expanded = showProviderMenu, onDismissRequest = { showProviderMenu = false }) {
                            listOf("AUTO", "GEMINI", "OPENAI", "CLAUDE").forEach { provider ->
                                DropdownMenuItem(text = { Text(when (provider) { "AUTO" -> "AUTO (available keys)"; else -> provider }) }, onClick = { selectedProvider = provider; showProviderMenu = false })
                            }
                        }
                    }
                    OutlinedTextField(value = openAiKeyDraft, onValueChange = { openAiKeyDraft = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("OpenAI API key (optional)") }, visualTransformation = PasswordVisualTransformation())
                    Text("OpenAI API billing ChatGPT subscription se alag hoti hai. API usage paid ho sakti hai.", color = Color.Gray, fontSize = 11.sp)
                    OutlinedTextField(value = claudeKeyDraft, onValueChange = { claudeKeyDraft = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Claude API key (optional)") }, visualTransformation = PasswordVisualTransformation())
                    Text("Claude API billing alag hoti hai. API usage paid ho sakti hai; key encrypted local storage mein rahegi.", color = Color.Gray, fontSize = 11.sp)
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
                    val openAiCandidate = openAiKeyDraft.trim()
                    if (openAiCandidate.isNotEmpty()) {
                        val encryptedOpenAi = encryptApiKey(openAiCandidate)
                        if (encryptedOpenAi != null) { openAiApiKey = openAiCandidate; prefs.edit().putString("openai_api_key", encryptedOpenAi).apply() }
                        else { messages.add("VEER" to "OpenAI key encrypt nahi ho paayi."); return@TextButton }
                    } else { openAiApiKey = ""; prefs.edit().remove("openai_api_key").apply() }
                    val claudeCandidate = claudeKeyDraft.trim()
                    if (claudeCandidate.isNotEmpty()) {
                        val encryptedClaude = encryptApiKey(claudeCandidate)
                        if (encryptedClaude != null) { claudeApiKey = claudeCandidate; prefs.edit().putString("claude_api_key", encryptedClaude).apply() }
                        else { messages.add("VEER" to "Claude key encrypt nahi ho paayi."); return@TextButton }
                    } else { claudeApiKey = ""; prefs.edit().remove("claude_api_key").apply() }
                    prefs.edit().putString("ai_provider", selectedProvider).apply()
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
                TextButton(onClick = { keyDraft = ""; apiKey = ""; openAiKeyDraft = ""; openAiApiKey = ""; claudeKeyDraft = ""; claudeApiKey = ""; prefs.edit().remove("gemini_api_key").remove("openai_api_key").remove("claude_api_key").apply(); showSettings = false }) { Text("REMOVE KEYS") }
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


private enum class VeerFaceExpression { IDLE, LISTENING, THINKING, SPEAKING }

@Composable
private fun HologramFace(
    modifier: Modifier = Modifier,
    active: Boolean,
    expression: VeerFaceExpression = VeerFaceExpression.IDLE,
    speaking: Boolean = false,
    mouthOpenTarget: Float = 0f
) {
    val faceStateDescription = when (expression) {
        VeerFaceExpression.IDLE -> "Idle"
        VeerFaceExpression.LISTENING -> "Listening"
        VeerFaceExpression.THINKING -> "Thinking"
        VeerFaceExpression.SPEAKING -> "Speaking"
    }
    val accessibleModifier = modifier.semantics {
        contentDescription = "VEER hologram assistant face"
        stateDescription = faceStateDescription
        role = Role.Image
    }
    val transition = rememberInfiniteTransition(label = "veer_face")
    val pulse by transition.animateFloat(0.96f, 1.025f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "facePulse")
    val mouthOpen by animateFloatAsState(
        targetValue = if (speaking) mouthOpenTarget.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
        label = "mouthLipSync"
    )
    val cyan = if (active) Color(0xFF8AF8FF) else Color(0xFF28C8F5)
    val eyeHeight by animateFloatAsState(
        targetValue = when (expression) {
            VeerFaceExpression.IDLE -> 23f
            VeerFaceExpression.LISTENING -> 38f
            VeerFaceExpression.THINKING -> 27f
            VeerFaceExpression.SPEAKING -> 31f
        },
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "faceExpressionEyes"
    )
    val gazeOffset by animateFloatAsState(
        targetValue = when (expression) {
            VeerFaceExpression.THINKING -> 6f
            VeerFaceExpression.LISTENING -> -2f
            else -> 0f
        },
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "faceExpressionGazeX"
    )
    val gazeVerticalOffset by animateFloatAsState(
        targetValue = when (expression) {
            VeerFaceExpression.THINKING -> -3f
            VeerFaceExpression.LISTENING -> -1f
            VeerFaceExpression.SPEAKING -> 1f
            VeerFaceExpression.IDLE -> 0f
        },
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "faceExpressionGazeY"
    )
    val idleGazeX by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.keyframes {
                durationMillis = 5200
                0f at 0
                1.4f at 1100
                0.2f at 2300
                -1.1f at 3600
                0f at 5200
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "idleEyeMicroMovementX"
    )
    val idleGazeY by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.keyframes {
                durationMillis = 5200
                0f at 0
                -0.6f at 1100
                0.7f at 2300
                0f at 3600
                0f at 5200
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "idleEyeMicroMovementY"
    )
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.keyframes {
                durationMillis = 4800
                1f at 0
                1f at 4300
                0.12f at 4380
                1f at 4460
                1f at 4800
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "naturalFaceBlink"
    )
    val faceGlowStrength by animateFloatAsState(
        targetValue = when (expression) {
            VeerFaceExpression.IDLE -> 0.16f
            VeerFaceExpression.LISTENING -> 0.27f
            VeerFaceExpression.THINKING -> 0.22f
            VeerFaceExpression.SPEAKING -> 0.34f
        },
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "faceExpressionGlow"
    )
    val scanLineY by transition.animateFloat(
        initialValue = 48f,
        targetValue = 330f,
        animationSpec = infiniteRepeatable(
            animation = tween(6200, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "faceHologramScan"
    )
    val scanLineGlow by transition.animateFloat(
        initialValue = 0.32f,
        targetValue = 0.78f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "faceHologramScanGlow"
    )

    // The face mesh never changes shape. Build its paths once instead of
    // recalculating hundreds of points and line segments on every animation frame.
    val cachedHead = remember {
        androidx.compose.ui.graphics.Path().apply {
            moveTo(180f, 24f)
            cubicTo(92f, 20f, 52f, 88f, 58f, 190f)
            cubicTo(62f, 294f, 112f, 355f, 180f, 365f)
            cubicTo(248f, 355f, 298f, 294f, 302f, 190f)
            cubicTo(308f, 88f, 268f, 20f, 180f, 24f)
            close()
        }
    }
    val cachedMesh = remember {
        val lines = androidx.compose.ui.graphics.Path()
        val dots = androidx.compose.ui.graphics.Path()
        for (row in 0..17) {
            val y = 42f + row * 17f
            val t = (y - 194f) / 174f
            val half = 115f * kotlin.math.sqrt((1f - t * t).coerceAtLeast(0.08f))
            for (col in 0..12) {
                val x = 180f - half + (2f * half * col / 12f)
                val yy = y + kotlin.math.sin((col + row) * 0.7f) * 2.2f
                if (col < 12) {
                    val nx = 180f - half + (2f * half * (col + 1) / 12f)
                    lines.moveTo(x, yy)
                    lines.lineTo(nx, yy + 2f)
                }
                if (row < 17) {
                    lines.moveTo(x, yy)
                    lines.lineTo(x + 1f, y + 17f)
                }
                dots.addOval(
                    androidx.compose.ui.geometry.Rect(
                        x - 1.05f, yy - 1.05f, x + 1.05f, yy + 1.05f
                    )
                )
            }
        }
        lines to dots
    }
    Canvas(modifier = accessibleModifier) {
        val sx = size.width / 360f
        val sy = size.height / 390f
        fun p(x: Float, y: Float) = Offset(x * sx, y * sy)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cyan.copy(alpha = faceGlowStrength), cyan.copy(alpha = faceGlowStrength * 0.28f), Color.Transparent),
                center = p(180f, 190f),
                radius = size.minDimension * 0.49f
            ),
            radius = size.minDimension * 0.49f,
            center = p(180f, 190f)
        )
        drawCircle(cyan.copy(alpha = 0.07f), size.minDimension * 0.48f * pulse, p(180f, 190f))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cyan.copy(alpha = faceGlowStrength * 0.72f), Color.Transparent),
                center = p(112f, 245f),
                radius = 30f * sx
            ),
            radius = 30f * sx,
            center = p(112f, 245f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cyan.copy(alpha = faceGlowStrength * 0.72f), Color.Transparent),
                center = p(248f, 245f),
                radius = 30f * sx
            ),
            radius = 30f * sx,
            center = p(248f, 245f)
        )
        withTransform({ scale(sx, sy, pivot = Offset.Zero) }) {
            drawPath(cachedHead, cyan.copy(alpha = 0.12f), style = Stroke(width = 2f))
        }
        val scanT = ((scanLineY - 194f) / 174f).coerceIn(-0.98f, 0.98f)
        val scanHalfWidth = 115f * kotlin.math.sqrt((1f - scanT * scanT).coerceAtLeast(0.08f))
        val scanStart = p(180f - scanHalfWidth, scanLineY)
        val scanEnd = p(180f + scanHalfWidth, scanLineY)
        drawLine(cyan.copy(alpha = 0.10f + scanLineGlow * 0.12f), scanStart, scanEnd, strokeWidth = 7f * sy)
        drawLine(cyan.copy(alpha = scanLineGlow), scanStart, scanEnd, strokeWidth = 0.8f * sy)
        // Draw cached mesh geometry with one transform and two path draws.
        withTransform({ scale(sx, sy, pivot = Offset.Zero) }) {
            drawPath(cachedMesh.first, cyan.copy(alpha = 0.2f), style = Stroke(width = 0.7f))
            drawPath(cachedMesh.second, cyan.copy(alpha = 0.78f))
        }
        // Ears and cheek contours
        drawOval(cyan.copy(alpha = 0.55f), p(43f, 164f), androidx.compose.ui.geometry.Size(25f*sx, 66f*sy), style = Stroke(1.5f*sx))
        drawOval(cyan.copy(alpha = 0.55f), p(292f, 164f), androidx.compose.ui.geometry.Size(25f*sx, 66f*sy), style = Stroke(1.5f*sx))
        // Expression-aware brows, eyelids and pupils.
        val browLift = when (expression) {
            VeerFaceExpression.LISTENING -> 7f
            VeerFaceExpression.THINKING -> 3f
            else -> 0f
        }
        val thinkingTilt = if (expression == VeerFaceExpression.THINKING) 4f else 0f
        drawLine(cyan, p(88f, 145f - browLift), p(150f, 139f - browLift + thinkingTilt), 2.3f*sx)
        drawLine(cyan, p(210f, 139f - browLift - thinkingTilt), p(272f, 145f - browLift), 2.3f*sx)
        val currentEyeHeight = eyeHeight * blink
        val eyeTop = 166f - currentEyeHeight / 2f
        drawOval(cyan.copy(alpha = 0.9f), p(91f, eyeTop), androidx.compose.ui.geometry.Size(68f*sx, currentEyeHeight*sy), style = Stroke(1.8f*sx))
        drawOval(cyan.copy(alpha = 0.9f), p(201f, eyeTop), androidx.compose.ui.geometry.Size(68f*sx, currentEyeHeight*sy), style = Stroke(1.8f*sx))
        val leftPupil = p(125f + gazeOffset + if (expression == VeerFaceExpression.IDLE) idleGazeX else 0f, 166f + gazeVerticalOffset + if (expression == VeerFaceExpression.IDLE) idleGazeY else 0f)
        val rightPupil = p(235f + gazeOffset + if (expression == VeerFaceExpression.IDLE) idleGazeX else 0f, 166f + gazeVerticalOffset + if (expression == VeerFaceExpression.IDLE) idleGazeY else 0f)
        drawCircle(cyan.copy(alpha = blink), 5f*sx, leftPupil)
        drawCircle(cyan.copy(alpha = blink), 5f*sx, rightPupil)
        // Small white glints add depth while fading naturally during each blink.
        drawCircle(Color.White.copy(alpha = 0.82f * blink), 1.45f*sx, leftPupil + Offset(-1.5f*sx, -1.6f*sy))
        drawCircle(Color.White.copy(alpha = 0.82f * blink), 1.45f*sx, rightPupil + Offset(-1.5f*sx, -1.6f*sy))
        // Nose
        drawLine(cyan.copy(alpha = 0.75f), p(180f, 168f), p(173f, 226f), 1.6f*sx)
        drawLine(cyan.copy(alpha = 0.75f), p(173f, 226f), p(180f, 232f), 1.6f*sx)
        drawLine(cyan.copy(alpha = 0.75f), p(180f, 232f), p(187f, 226f), 1.6f*sx)
        // Mouth
        val mouth = androidx.compose.ui.graphics.Path().apply {
            val opening = 2f + 15f * mouthOpen
            val smile = when (expression) {
                VeerFaceExpression.IDLE -> 7f
                VeerFaceExpression.LISTENING -> 4f
                VeerFaceExpression.THINKING -> 1f
                VeerFaceExpression.SPEAKING -> 0f
            }
            moveTo(145f*sx, 278f*sy)
            cubicTo(163f*sx, (278f + smile + opening)*sy, 197f*sx, (278f + smile + opening)*sy, 215f*sx, 278f*sy)
            if (mouthOpen > 0.08f) {
                cubicTo(202f*sx, (292f + opening)*sy, 158f*sx, (292f + opening)*sy, 145f*sx, 278f*sy)
            } else {
                cubicTo(163f*sx, (278f + smile)*sy, 197f*sx, (278f + smile)*sy, 215f*sx, 278f*sy)
            }
            close()
        }
        drawPath(mouth, cyan.copy(alpha = 0.9f), style = Stroke(1.7f*sx))
        // Jaw, neck and light scan lines
        drawLine(cyan.copy(alpha = 0.4f), p(100f, 260f), p(120f, 320f), 1f*sx)
        drawLine(cyan.copy(alpha = 0.4f), p(260f, 260f), p(240f, 320f), 1f*sx)
        drawLine(cyan.copy(alpha = 0.65f), p(125f, 340f), p(119f, 390f), 2f*sx)
        drawLine(cyan.copy(alpha = 0.65f), p(235f, 340f), p(241f, 390f), 2f*sx)
    }
}

// Rebuild after completing the voice-mode hologram component.
