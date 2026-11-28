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
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.util.UUID


private fun safelyReleaseSpeechRecognizer(speech: SpeechRecognizer?) {
    try { speech?.cancel() } catch (_: Exception) {}
    try { speech?.destroy() } catch (_: Exception) {}
}

private const val VEER_KEY_ALIAS = "veer_gemini_api_key"
private const val VEER_PC_TOKEN_ALIAS = "veer_pc_agent_token"
private const val VEER_CLOUD_TOKEN_ALIAS = "veer_cloud_api_token"
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

private fun generateDemoReply(question: String): String =
    "Main Veer hoon. Aapka message mil gaya: $question\n\nAbhi main demo mode mein hoon. Asli AI connection agle update mein jodega."

@Composable
private fun VeerVoiceOrb(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.43f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF3DD6C4).copy(alpha = 0.34f), Color(0xFF3DD6C4).copy(alpha = 0.08f), Color.Transparent),
                center = c,
                radius = size.minDimension * 0.50f
            ),
            radius = size.minDimension * 0.50f,
            center = c
        )
        drawCircle(Color(0xFF2D5F5A), radius = radius * 1.04f, center = c, style = Stroke(width = size.minDimension * 0.012f))
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFF70F1E2), Color(0xFF3DD6C4), Color(0xFF168E83)), center = Offset(c.x * 0.72f, c.y * 0.66f), radius = radius * 1.6f),
            radius = radius,
            center = c
        )
        val heights = listOf(0.20f, 0.36f, 0.54f, 0.36f, 0.20f)
        heights.forEachIndexed { index, fraction ->
            val x = c.x + (index - 2) * size.width * 0.105f
            val half = radius * fraction
            drawLine(Color(0xFF062B27), Offset(x, c.y - half), Offset(x, c.y + half), size.minDimension * 0.045f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
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
private fun VeerOutlineIcon(kind: String, modifier: Modifier = Modifier, tint: Color = Color(0xFF17181A)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = size.minDimension * 0.085f
        val start = Offset(w * 0.14f, h * 0.5f)
        when (kind) {
            "menu" -> {
                for (y in listOf(0.25f, 0.5f, 0.75f)) {
                    drawLine(tint, Offset(w * 0.16f, h * y), Offset(w * 0.84f, h * y), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
            "new" -> {
                drawRoundRect(tint, Offset(w * 0.13f, h * 0.13f), androidx.compose.ui.geometry.Size(w * 0.74f, h * 0.74f), androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = Stroke(stroke))
                drawLine(tint, Offset(w * 0.5f, h * 0.31f), Offset(w * 0.5f, h * 0.69f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(tint, Offset(w * 0.31f, h * 0.5f), Offset(w * 0.69f, h * 0.5f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            "plus" -> {
                drawLine(tint, Offset(w * 0.5f, h * 0.17f), Offset(w * 0.5f, h * 0.83f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(tint, Offset(w * 0.17f, h * 0.5f), Offset(w * 0.83f, h * 0.5f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            "file" -> {
                val page = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.28f, h * 0.12f)
                    lineTo(w * 0.58f, h * 0.12f)
                    lineTo(w * 0.76f, h * 0.30f)
                    lineTo(w * 0.76f, h * 0.86f)
                    lineTo(w * 0.28f, h * 0.86f)
                    close()
                    moveTo(w * 0.58f, h * 0.12f)
                    lineTo(w * 0.58f, h * 0.30f)
                    lineTo(w * 0.76f, h * 0.30f)
                }
                drawPath(page, tint, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                drawLine(tint, Offset(w * 0.38f, h * 0.49f), Offset(w * 0.66f, h * 0.49f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(tint, Offset(w * 0.38f, h * 0.64f), Offset(w * 0.66f, h * 0.64f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            "camera" -> {
                drawRoundRect(tint, Offset(w * 0.10f, h * 0.27f), androidx.compose.ui.geometry.Size(w * 0.80f, h * 0.57f), androidx.compose.ui.geometry.CornerRadius(w * 0.10f), style = Stroke(stroke))
                drawRoundRect(tint, Offset(w * 0.28f, h * 0.15f), androidx.compose.ui.geometry.Size(w * 0.22f, h * 0.14f), androidx.compose.ui.geometry.CornerRadius(w * 0.04f), style = Stroke(stroke))
                drawCircle(tint, w * 0.15f, Offset(w * 0.52f, h * 0.55f), style = Stroke(stroke))
            }
            "mic" -> {
                drawRoundRect(tint, Offset(w * 0.38f, h * 0.08f), androidx.compose.ui.geometry.Size(w * 0.24f, h * 0.55f), androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = Stroke(stroke))
                drawArc(tint, 0f, 180f, false, Offset(w * 0.20f, h * 0.25f), androidx.compose.ui.geometry.Size(w * 0.60f, h * 0.52f), style = Stroke(stroke))
                drawLine(tint, Offset(w * 0.50f, h * 0.76f), Offset(w * 0.50f, h * 0.91f), stroke)
                drawLine(tint, Offset(w * 0.34f, h * 0.92f), Offset(w * 0.66f, h * 0.92f), stroke)
            }
            "send" -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.18f, h * 0.50f)
                    lineTo(w * 0.82f, h * 0.50f)
                    moveTo(w * 0.56f, h * 0.23f)
                    lineTo(w * 0.83f, h * 0.50f)
                    lineTo(w * 0.56f, h * 0.77f)
                }
                drawPath(path, tint, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
    }
}

@Composable
fun VeerApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var latestResearchReport by remember { mutableStateOf("") }
    val researchSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val destination = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && destination != null) {
            try {
                context.contentResolver.openOutputStream(destination, "w")?.use { stream ->
                    stream.write(latestResearchReport.toByteArray(Charsets.UTF_8))
                } ?: throw java.io.IOException("Unable to open selected document")
                android.widget.Toast.makeText(context, "Research report saved.", android.widget.Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                android.widget.Toast.makeText(context, "Report save failed: " + (error.message ?: "Storage error"), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val veerFont = remember { FontFamily(Font(R.font.inter_variable, weight = FontWeight.Normal)) }
    val prefs = remember { context.getSharedPreferences("veer_settings", 0) }
    val localAiScope = rememberCoroutineScope()
    val gmailAuthorizationClient = remember(context) { Identity.getAuthorizationClient(context) }
    val calendarAuthorizationClient = remember(context) { Identity.getAuthorizationClient(context) }
    val driveAuthorizationClient = remember(context) { Identity.getAuthorizationClient(context) }
    var driveConnected by remember { mutableStateOf(prefs.getBoolean("drive_connected", false)) }
    var driveEmail by remember { mutableStateOf(prefs.getString("drive_email", "") ?: "") }
    var driveAccessToken by remember { mutableStateOf<String?>(null) }
    var driveBusy by remember { mutableStateOf(false) }
    var driveQuery by remember { mutableStateOf("") }
    var driveParentId by remember { mutableStateOf<String?>(null) }
    var drivePageToken by remember { mutableStateOf("") }
    var driveFiles by remember { mutableStateOf<List<VeerDriveClient.DriveFile>>(emptyList()) }
    var driveSelectedFile by remember { mutableStateOf<VeerDriveClient.DriveFile?>(null) }
    var drivePendingContentFile by remember { mutableStateOf<VeerDriveClient.DriveFile?>(null) }
    var driveContentBytes by remember { mutableStateOf<ByteArray?>(null) }
    var drivePreviewText by remember { mutableStateOf("") }
    var driveSummaryConsentPending by remember { mutableStateOf(false) }
    var driveSummaryBusy by remember { mutableStateOf(false) }
    var driveSummaryText by remember { mutableStateOf("") }
    var driveSummaryStatus by remember { mutableStateOf("") }
    var driveSummaryFileId by remember { mutableStateOf("") }
    var driveContentStatus by remember { mutableStateOf("") }
    var driveContentBusy by remember { mutableStateOf(false) }
    var driveStatus by remember { mutableStateOf(if (driveConnected) "Connected — refresh access to search" else "Not connected") }
    var calendarConnected by remember { mutableStateOf(prefs.getBoolean("calendar_connected", false)) }
    var calendarEmail by remember { mutableStateOf(prefs.getString("calendar_email", "") ?: "") }
    var calendarAccessToken by remember { mutableStateOf<String?>(null) }
    var calendarStatus by remember { mutableStateOf(if (calendarConnected) "Connected — authorize again when using Calendar" else "Not connected") }
    var calendarBusy by remember { mutableStateOf(false) }
    var calendarEvents by remember { mutableStateOf<List<VeerCalendarClient.CalendarEvent>>(emptyList()) }
    var selectedCalendarEventId by remember { mutableStateOf("") }
    var calendarAgendaStatus by remember { mutableStateOf("Agenda not loaded") }
    var calendarNextPageToken by remember { mutableStateOf("") }
    var calendarRangeStart by remember { mutableStateOf("") }
    var calendarRangeEnd by remember { mutableStateOf("") }
    var calendarScheduleReport by remember { mutableStateOf("") }
    var calendarDraftTitle by remember { mutableStateOf("") }
    var calendarDraftDate by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    var calendarDraftStart by remember { mutableStateOf("10:00") }
    var calendarDraftEnd by remember { mutableStateOf("10:30") }
    var calendarDraftLocation by remember { mutableStateOf("") }
    var calendarDraftDescription by remember { mutableStateOf("") }
    var calendarDraftPreview by remember { mutableStateOf("") }
    var calendarDraftApprovedPayload by remember { mutableStateOf("") }
    var calendarWriteAccessToken by remember { mutableStateOf<String?>(null) }
    var calendarWriteAuthorizationPending by remember { mutableStateOf(false) }
    var calendarWriteStatus by remember { mutableStateOf("Event saving is not authorized") }
    var calendarReminders by remember { mutableStateOf(VeerCalendarReminderStore.list(context)) }
    var calendarReminderPendingEvent by remember { mutableStateOf<Pair<String, String>?>(null) }
    var calendarReminderStatus by remember { mutableStateOf("Reminders are off until you choose an event") }
    val calendarReminderPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = calendarReminderPendingEvent
        calendarReminderPendingEvent = null
        if (granted && pending != null) {
            try {
                VeerCalendarReminderScheduler.schedule(context, pending.first, pending.second)
                calendarReminders = VeerCalendarReminderStore.list(context)
                calendarReminderStatus = "10-minute local reminder scheduled."
            } catch (error: Exception) {
                calendarReminderStatus = "Reminder could not be scheduled: " + (error.message ?: "Try again")
            }
        } else if (!granted) {
            calendarReminderStatus = "Notification permission denied. No reminder was scheduled."
        }
    }
    var calendarMutationTitle by remember { mutableStateOf("") }
    var calendarMutationAction by remember { mutableStateOf("") }
    var calendarMutationPatch by remember { mutableStateOf("") }
    var calendarMutationSnapshot by remember { mutableStateOf("") }
    var calendarMutationEventId by remember { mutableStateOf("") }
    var calendarMutationStatus by remember { mutableStateOf("Select an event to prepare an update or cancellation") }
    var calendarDraftError by remember { mutableStateOf("") }
    var gmailConnected by remember { mutableStateOf(prefs.getBoolean("gmail_connected", false)) }
    var gmailEmail by remember { mutableStateOf(prefs.getString("gmail_email", "") ?: "") }
    var gmailAccessToken by remember { mutableStateOf<String?>(null) }
    var gmailStatus by remember { mutableStateOf(if (gmailConnected) "Connected — authorize again when using Gmail" else "Not connected") }
    var gmailBusy by remember { mutableStateOf(false) }
    var gmailQuery by remember { mutableStateOf("in:inbox") }
    var gmailInboxItems by remember { mutableStateOf<List<VeerGmailClient.Message>>(emptyList()) }
    var gmailInboxLoading by remember { mutableStateOf(false) }
    var gmailInboxStatus by remember { mutableStateOf("Inbox not loaded") }
    var gmailSummary by remember { mutableStateOf<List<VeerGmailSummary.Item>>(emptyList()) }
    var gmailSecurityFindings by remember { mutableStateOf<List<VeerGmailSecurity.Finding>>(emptyList()) }
    var gmailComposeAuthorizationPending by remember { mutableStateOf(false) }
    var gmailComposeAuthorized by remember { mutableStateOf(false) }
    var gmailSendAuthorizationPending by remember { mutableStateOf(false) }
    var gmailSendAuthorized by remember { mutableStateOf(false) }
    var gmailSendReviewOpen by remember { mutableStateOf(false) }
    var gmailReviewTo by remember { mutableStateOf("") }
    var gmailReviewSubject by remember { mutableStateOf("") }
    var gmailReviewBody by remember { mutableStateOf("") }
    var gmailSendBusy by remember { mutableStateOf(false) }
    var gmailSendStatus by remember { mutableStateOf("No email sent") }
    var gmailDraftTo by remember { mutableStateOf("") }
    var gmailDraftSubject by remember { mutableStateOf("") }
    var gmailDraftBody by remember { mutableStateOf("") }
    var gmailDraftBusy by remember { mutableStateOf(false) }
    var gmailDraftStatus by remember { mutableStateOf("No draft created") }
    var gmailReminders by remember { mutableStateOf(VeerGmailReminderStore.list(context)) }
    var gmailPendingReminder by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val gmailReminderPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = gmailPendingReminder
        gmailPendingReminder = null
        if (granted && pending != null) {
            try {
                VeerGmailReminderScheduler.schedule(context, pending.first, pending.second)
                gmailReminders = VeerGmailReminderStore.list(context)
                gmailInboxStatus = "Follow-up reminder scheduled."
            } catch (error: Exception) {
                gmailInboxStatus = "Reminder could not be scheduled: " + (error.message ?: "Try again")
            }
        } else if (!granted) {
            gmailInboxStatus = "Notification permission denied. No reminder was scheduled."
            android.widget.Toast.makeText(context, "Reminder notification permission denied.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val gmailAuthorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            try {
                val authorization = gmailAuthorizationClient.getAuthorizationResultFromIntent(result.data!!)
                val accessToken = authorization.accessToken
                if (accessToken.isNullOrBlank()) {
                    gmailStatus = "Google did not return Gmail access. Try connecting again."
                    gmailComposeAuthorizationPending = false
                    gmailSendAuthorizationPending = false
                    gmailBusy = false
                } else {
                    gmailAccessToken = accessToken
                    if (gmailComposeAuthorizationPending) { gmailComposeAuthorized = true; gmailComposeAuthorizationPending = false }
                    if (gmailSendAuthorizationPending) { gmailSendAuthorized = true; gmailSendAuthorizationPending = false; gmailSendStatus = "Send permission granted. Review the email before sending." }
                    gmailStatus = "Checking authorized Google account..."
                    localAiScope.launch {
                        try {
                            val email = withContext(Dispatchers.IO) { VeerGmailAuthorization.fetchEmail(accessToken) }
                            gmailEmail = email
                            gmailConnected = true
                            gmailStatus = "Connected — read-only Gmail access"
                            prefs.edit().putBoolean("gmail_connected", true).putString("gmail_email", email).apply()
                        } catch (error: Exception) {
                            gmailAccessToken = null
                            gmailComposeAuthorizationPending = false
                            gmailSendAuthorizationPending = false
                            gmailStatus = "Account verification failed: " + (error.message ?: "Try again")
                        } finally { gmailBusy = false }
                    }
                }
            } catch (error: Exception) {
                gmailStatus = "Google authorization failed: " + (error.message ?: "Try again")
                gmailComposeAuthorizationPending = false
                gmailSendAuthorizationPending = false
                gmailBusy = false
            }
        } else {
            gmailComposeAuthorizationPending = false
            gmailSendAuthorizationPending = false
            gmailStatus = "Google authorization cancelled"
            gmailBusy = false
        }
    }
    fun fetchDriveSelectedContent(token: String, file: VeerDriveClient.DriveFile) {
        driveContentBusy = true
        driveContentBytes = null
        drivePreviewText = ""
        driveContentStatus = "Fetching selected file in temporary memory..."
        localAiScope.launch {
            var fetchedBytes: ByteArray? = null
            try {
                val content = withContext(Dispatchers.IO) {
                    VeerDriveClient.fetchSelectedContent(token, file.id, file.mimeType, file.sizeBytes)
                }
                fetchedBytes = content.bytes
                if (driveSelectedFile?.id != file.id) return@launch
                driveContentBytes = content.bytes
                val preview = withContext(Dispatchers.Default) {
                    VeerDrivePreview.createPreview(content.bytes, content.contentType)
                }
                if (driveSelectedFile?.id != file.id) {
                    drivePreviewText = ""
                    return@launch
                }
                drivePreviewText = preview.text
                driveContentStatus = preview.message
            } catch (error: Exception) {
                drivePreviewText = ""
                driveContentStatus = "File preview failed: " + (error.message ?: "Unsupported, malformed or unavailable file")
            } finally {
                fetchedBytes?.fill(0)
                driveContentBytes = null
                driveContentBusy = false
                drivePendingContentFile = null
            }
        }
    }
    val driveContentAuthorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            try {
                val authorization = driveAuthorizationClient.getAuthorizationResultFromIntent(result.data!!)
                val token = authorization.accessToken
                val selected = drivePendingContentFile
                if (token.isNullOrBlank() || selected == null) {
                    driveContentStatus = "Google did not return content access for the selected file"
                    driveContentBusy = false
                    drivePendingContentFile = null
                } else {
                    fetchDriveSelectedContent(token, selected)
                }
            } catch (error: Exception) {
                driveContentStatus = "Drive content authorization failed: " + (error.message ?: "Try again")
                driveContentBusy = false
                drivePendingContentFile = null
            }
        } else {
            driveContentStatus = "Drive file access permission cancelled"
            driveContentBusy = false
            drivePendingContentFile = null
        }
    }
    val driveAuthorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            try {
                val authorization = driveAuthorizationClient.getAuthorizationResultFromIntent(result.data!!)
                val token = authorization.accessToken
                if (token.isNullOrBlank()) {
                    driveStatus = "Google did not return Drive metadata access"
                    driveBusy = false
                } else {
                    driveAccessToken = token
                    driveStatus = "Verifying Google account..."
                    localAiScope.launch {
                        try {
                            val email = withContext(Dispatchers.IO) { VeerGmailAuthorization.fetchEmail(token) }
                            require(VeerDriveAuthorization.isValidVerifiedEmailShape(email)) { "Google account email could not be verified" }
                            driveEmail = email
                            driveConnected = true
                            driveStatus = "Connected — Drive metadata access only"
                            prefs.edit().putBoolean("drive_connected", true).putString("drive_email", email).apply()
                        } catch (error: Exception) {
                            driveAccessToken = null
                            driveConnected = false
                            driveStatus = "Drive account verification failed: " + (error.message ?: "Try again")
                        } finally { driveBusy = false }
                    }
                }
            } catch (error: Exception) {
                driveStatus = "Drive authorization failed: " + (error.message ?: "Try again")
                driveBusy = false
            }
        } else {
            driveStatus = "Google Drive authorization cancelled"
            driveBusy = false
        }
    }
    fun acceptCalendarAccessToken(token: String) {
        calendarAccessToken = token
        calendarStatus = "Verifying Google account..."
        localAiScope.launch {
            try {
                val email = withContext(Dispatchers.IO) { VeerGmailAuthorization.fetchEmail(token) }
                require(VeerCalendarAuthorization.isValidVerifiedEmailShape(email)) { "Google account email could not be verified" }
                calendarEmail = email
                calendarConnected = true
                calendarStatus = "Connected — read-only Calendar access"
                prefs.edit().putBoolean("calendar_connected", true).putString("calendar_email", email).apply()
            } catch (error: Exception) {
                calendarAccessToken = null
                calendarConnected = false
                calendarStatus = "Calendar account verification failed: " + (error.message ?: "Try again")
            } finally { calendarBusy = false }
        }
    }
    val calendarAuthorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            try {
                val authorization = calendarAuthorizationClient.getAuthorizationResultFromIntent(result.data!!)
                val token = authorization.accessToken
                if (token.isNullOrBlank()) {
                    calendarStatus = "Google did not return Calendar access"
                    calendarWriteAuthorizationPending = false
                    calendarBusy = false
                } else if (calendarWriteAuthorizationPending) {
                    calendarWriteAccessToken = token
                    calendarWriteAuthorizationPending = false
                    calendarWriteStatus = "Write permission granted. Review and approve the exact event before saving."
                    calendarBusy = false
                } else acceptCalendarAccessToken(token)
            } catch (error: Exception) {
                calendarStatus = "Calendar authorization failed: " + (error.message ?: "Try again")
                calendarWriteAuthorizationPending = false
                calendarBusy = false
            }
        } else {
            calendarStatus = "Calendar authorization cancelled"
            calendarWriteAuthorizationPending = false
            calendarBusy = false
        }
    }
    val localAiEngine = remember(context) { VeerOnDeviceAiEngine.create(context) }
    val cyan = Color(0xFF00E5FF)
    val dark = Color(0xFF050B16)

    var status by remember { mutableStateOf("VEER ONLINE") }
    var typedCommand by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf(decryptApiKey(prefs.getString("gemini_api_key", "") ?: "")) }
    var openAiApiKey by remember { mutableStateOf(decryptApiKey(prefs.getString("openai_api_key", "") ?: "")) }
    var openAiKeyDraft by remember { mutableStateOf(openAiApiKey) }
    var claudeApiKey by remember { mutableStateOf(decryptApiKey(prefs.getString("claude_api_key", "") ?: "")) }
    var claudeKeyDraft by remember { mutableStateOf(claudeApiKey) }
    var llamaApiKey by remember { mutableStateOf(decryptApiKey(prefs.getString("llama_api_key", "") ?: "")) }
    var llamaKeyDraft by remember { mutableStateOf(llamaApiKey) }
    var grokApiKey by remember { mutableStateOf(decryptApiKey(prefs.getString("grok_api_key", "") ?: "")) }
    var grokKeyDraft by remember { mutableStateOf(grokApiKey) }
    var museApiKey by remember { mutableStateOf(decryptApiKey(prefs.getString("muse_api_key", "") ?: "")) }
    var museKeyDraft by remember { mutableStateOf(museApiKey) }
    var selectedProvider by remember { mutableStateOf(prefs.getString("ai_provider", "AUTO") ?: "AUTO") }
    var pcAgentUrl by remember { mutableStateOf(prefs.getString("pc_agent_url", "") ?: "") }
    var pcAgentUrlDraft by remember { mutableStateOf(pcAgentUrl) }
    var pcAgentToken by remember { mutableStateOf(decryptSecret(prefs.getString("pc_agent_token", "") ?: "", VEER_PC_TOKEN_ALIAS)) }
    var pcAgentTokenDraft by remember { mutableStateOf(pcAgentToken) }
    var pcAgentStatus by remember { mutableStateOf(if (pcAgentUrl.isNotBlank() && pcAgentToken.isNotBlank()) "Saved; connection not checked" else "Not configured") }
    var cloudApiUrl by remember { mutableStateOf(prefs.getString("cloud_api_url", "https://veer-ai-assistant.vercel.app") ?: "https://veer-ai-assistant.vercel.app") }
    var cloudApiUrlDraft by remember { mutableStateOf(cloudApiUrl) }
    var cloudApiToken by remember { mutableStateOf(decryptSecret(prefs.getString("cloud_api_token", "") ?: "", VEER_CLOUD_TOKEN_ALIAS)) }
    var cloudApiTokenDraft by remember { mutableStateOf(cloudApiToken) }
    var cloudApiStatus by remember { mutableStateOf(if (cloudApiToken.isNotBlank()) "Saved; connection not checked" else "Not configured") }
    var cloudApiChecking by remember { mutableStateOf(false) }
    var pcAgentChecking by remember { mutableStateOf(false) }
    var pcWorkspacePreview by remember { mutableStateOf<List<VeerPcAgentFileEntry>?>(null) }
    var pcWorkspaceLoading by remember { mutableStateOf(false) }
    var pcWorkspaceStatus by remember { mutableStateOf("") }
    var pcTaskOperation by remember { mutableStateOf("create_text_file") }
    var pcTaskPath by remember { mutableStateOf("notes.txt") }
    var pcTaskContent by remember { mutableStateOf("") }
    var pcTaskUrl by remember { mutableStateOf("https://") }
    var pcTaskCurrent by remember { mutableStateOf<VeerPcAgentTask?>(null) }
    var pcTaskBusy by remember { mutableStateOf(false) }
    var pcTaskStatus by remember { mutableStateOf("") }
    var showPcTaskApproval by remember { mutableStateOf(false) }
    var showPcTaskCancelConfirm by remember { mutableStateOf(false) }
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
    var pendingBrainContinuation by remember { mutableStateOf<AgentApprovalContinuation?>(null) }
    var activeBrainCancellationToken by remember { mutableStateOf<AgentCancellationToken?>(null) }
    var pendingMusicUrl by remember { mutableStateOf<String?>(null) }
    var pendingGeminiContents by remember { mutableStateOf<String?>(null) }
    var pendingGeminiModelContent by remember { mutableStateOf<String?>(null) }
    var pendingGeminiFunctionName by remember { mutableStateOf<String?>(null) }
    var pendingGeminiModel by remember { mutableStateOf<String?>(null) }
    var pendingGeminiApiKey by remember { mutableStateOf<String?>(null) }
    var pendingGeminiSystemText by remember { mutableStateOf<String?>(null) }
    var pendingGeminiToolSteps by remember { mutableIntStateOf(0) }
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
            addAll(restored.takeLast(100))
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

    fun runApprovedAction(action: String, announce: Boolean = true): Boolean {
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
            if (announce) addReply("Boss, ye action supported ya approved list mein nahi hai. Maine ise run nahi kiya.")
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
            if (announce) addReply("Boss, aapki permission se action complete kar diya.")
            true
        } catch (_: Exception) {
            if (announce) addReply("Boss, ye action phone par complete nahi ho paaya.")
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

    // Agent tools may run on a worker; Activity launches must execute on Android's main thread.
    val agentRuntime = remember {
        VeerAndroidAgentRuntime(
            apiKeyProvider = { apiKey },
            openAiApiKeyProvider = { openAiApiKey },
            claudeApiKeyProvider = { claudeApiKey },
            llamaApiKeyProvider = { llamaApiKey },
            grokApiKeyProvider = { grokApiKey },
            museApiKeyProvider = { museApiKey },
            dispatchApprovedAction = { action ->
                val completed = java.util.concurrent.CountDownLatch(1)
                val outcome = java.util.concurrent.atomic.AtomicReference<String>()
                val posted = Handler(Looper.getMainLooper()).post {
                    val success = runApprovedAction(action, announce = false)
                    outcome.set(if (success) "Approved Android action launched successfully."
                        else "Approved Android action could not be launched.")
                    completed.countDown()
                }
                if (!posted || !completed.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw IllegalStateException("Android action dispatcher timed out.")
                }
                outcome.get()
            },
            approvalSession = agentApprovalSession,
            providerOrder = VeerAndroidAgentRuntime.providerOrderForSelection(selectedProvider)
        )
    }
    agentRuntime.setProviderOrder(VeerAndroidAgentRuntime.providerOrderForSelection(selectedProvider))

    fun continueGeminiAfterApprovedTool(call: AgentToolCall, toolResult: String) {
        val savedContents = pendingGeminiContents
        val savedModelContent = pendingGeminiModelContent
        val functionName = pendingGeminiFunctionName
        val model = pendingGeminiModel
        val key = pendingGeminiApiKey
        val systemText = pendingGeminiSystemText
        val completedSteps = pendingGeminiToolSteps
        pendingGeminiContents = null
        pendingGeminiModelContent = null
        pendingGeminiFunctionName = null
        pendingGeminiModel = null
        pendingGeminiApiKey = null
        pendingGeminiSystemText = null

        if (savedContents.isNullOrBlank() || savedModelContent.isNullOrBlank() ||
            functionName.isNullOrBlank() || model.isNullOrBlank() || key.isNullOrBlank() || systemText.isNullOrBlank() ||
            functionName != call.name
        ) {
            pendingGeminiToolSteps = 0
            addReply("Boss, Gemini continuation verify nahi hui. Approved action ke baad AI response safely rok diya.")
            return
        }

        val nextContents = try {
            VeerGeminiFunctionResponse.appendToConversation(
                org.json.JSONArray(savedContents),
                JSONObject(savedModelContent),
                functionName,
                toolResult
            )
        } catch (_: Exception) { null }
        if (nextContents == null) {
            pendingGeminiToolSteps = 0
            addReply("Boss, tool result conversation mein safely add nahi ho paaya.")
            return
        }

        loading = true
        status = "SENDING TOOL RESULT TO GEMINI..."
        Thread {
            var answer = ""
            try {
                val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                val body = VeerGeminiContinuationRequest.build(
                    model = model,
                    systemText = systemText,
                    contents = nextContents,
                    functionDeclarations = VeerGeminiToolSchema.functionDeclarations()
                ) ?: throw IllegalStateException("Invalid Gemini continuation request.")
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

                if (code !in 200..299) {
                    val detail = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                    answer = "Boss, action ka result Gemini ko bheja gaya, lekin follow-up response nahi mila ($code). " + (detail ?: "Dobara pooch sakte hain.")
                } else {
                    val decision = VeerGeminiToolCallParser.parse(raw)
                    if (decision?.toolCall != null) {
                        val nextCall = decision.toolCall
                        val nextModelContent = JSONObject(raw).optJSONArray("candidates")
                            ?.optJSONObject(0)?.optJSONObject("content")
                        if (completedSteps >= 5 || nextModelContent == null) {
                            answer = "Boss, VEER ki safe tool-step limit poori ho gayi ya next request verify nahi hui. Aage koi action nahi chalaya."
                        } else {
                            Handler(Looper.getMainLooper()).post {
                                val request = agentApprovalSession.request(nextCall)
                                if (request == null) {
                                    pendingGeminiToolSteps = 0
                                    loading = false
                                    addReply("Boss, next approval request verify nahi hui. Koi extra action run nahi kiya.")
                                } else {
                                    pendingGeminiContents = nextContents.toString()
                                    pendingGeminiModelContent = nextModelContent.toString()
                                    pendingGeminiFunctionName = nextCall.name
                                    pendingGeminiModel = model
                                    pendingGeminiApiKey = key
                                    pendingGeminiSystemText = systemText
                                    pendingGeminiToolSteps = completedSteps + 1
                                    pendingAgentApproval = request
                                    pendingAction = request.action
                                    approvalGeneration += 1L
                                    showApproval = true
                                    loading = false
                                    addReply(decision.message)
                                }
                            }
                            return@Thread
                        }
                    } else if (decision != null) {
                        answer = decision.message
                    } else {
                        val parts = JSONObject(raw).optJSONArray("candidates")
                            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        val result = StringBuilder()
                        if (parts != null) for (index in 0 until parts.length()) {
                            result.append(parts.optJSONObject(index)?.optString("text", "") ?: "")
                        }
                        answer = result.toString().trim().ifBlank {
                            "Boss, tool result Gemini tak pahunch gaya, lekin final text response khaali tha."
                        }
                    }
                }
            } catch (_: Exception) {
                answer = "Boss, approved action ka result Gemini ko bhejte waqt connection issue aaya."
            }
            Handler(Looper.getMainLooper()).post {
                pendingGeminiToolSteps = 0
                loading = false
                addReply(answer)
            }
        }.start()
    }

    fun runApprovedAgentTool(call: AgentToolCall) {
        val validatedAction = VeerAgentToolPolicy.toPendingAppAction(call)
        if (validatedAction == null || validatedAction != pendingAction) {
            approvalGeneration += 1L
            agentApprovalSession.clear()
            pendingAgentApproval = null
            pendingAction = ""
            pendingMusicUrl = null
            pendingGeminiContents = null
            pendingGeminiModelContent = null
            pendingGeminiFunctionName = null
            pendingGeminiModel = null
            pendingGeminiApiKey = null
            pendingGeminiSystemText = null
            pendingGeminiToolSteps = 0
            showApproval = false
            addReply("Boss, pending agent action verify nahi hua. Maine ise run nahi kiya.")
            return
        }
        val brainContinuation = pendingBrainContinuation
        if (brainContinuation != null) {
            pendingBrainContinuation = null
            pendingAgentApproval = null
            showApproval = false
            loading = true
            status = "VEER AGENT RESUMING..."
            Thread {
                val result = try {
                    agentRuntime.brain.resumeAfterApproval(brainContinuation, call)
                } catch (_: Exception) {
                    AgentRunResult("Boss, approval ke baad agent safely stop ho gaya.", null, 0)
                }
                Handler(Looper.getMainLooper()).post {
                    if (activeBrainCancellationToken !== brainContinuation.cancellationToken) return@post
                    loading = false
                    if (result.stoppedForApproval && result.pendingApproval != null && result.continuation != null) {
                        val request = agentApprovalSession.request(result.pendingApproval)
                        if (request == null) {
                            activeBrainCancellationToken = null
                            addReply("Boss, next approval request register nahi hui. Koi additional action execute nahi hua.")
                        } else {
                            pendingBrainContinuation = result.continuation
                            pendingAgentApproval = request
                            pendingAction = request.action
                            approvalGeneration += 1L
                            showApproval = true
                            addReply(result.message.ifBlank { "Agla action karne ke liye approval chahiye." })
                        }
                    } else {
                        activeBrainCancellationToken = null
                        pendingBrainContinuation = null
                        addReply(result.message)
                    }
                }
            }.start()
            return
        }
        agentApprovalSession.clear()
        pendingAgentApproval = null
        try {
            val toolResult = VeerAndroidAppToolExecutor { action ->
                if (runApprovedAction(action)) {
                    "Approved Android action launched successfully."
                } else {
                    "Approved action could not be launched on this phone."
                }
            }.execute(call)
            continueGeminiAfterApprovedTool(call, toolResult)
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

    fun askAgentBrain(question: String) {
        val turn = AgentTurn(userRequest = question, memory = memories.takeLast(12).toList(), recentConversation = messages.dropLast(1).takeLast(8).map { "${it.first}: ${it.second}" })
        val cancellationToken = AgentCancellationToken()
        activeBrainCancellationToken = cancellationToken
        loading = true
        status = "VEER AGENT THINKING..."
        Thread {
            val result = try { agentRuntime.brain.run(turn, cancellationToken) } catch (_: Exception) { AgentRunResult("Boss, VEER agent request safely fail ho gayi. Dobara try karein.", null, 0) }
            Handler(Looper.getMainLooper()).post {
                if (activeBrainCancellationToken !== cancellationToken) return@post
                loading = false
                if (result.stoppedForApproval && result.pendingApproval != null && result.continuation != null) {
                    val request = agentApprovalSession.request(result.pendingApproval)
                    if (request == null) { activeBrainCancellationToken = null; pendingBrainContinuation = null; addReply("Boss, agent approval request register nahi hui. Koi action execute nahi hua.") }
                    else { pendingBrainContinuation = result.continuation; pendingAgentApproval = request; pendingAction = request.action; approvalGeneration += 1L; showApproval = true; addReply(result.message.ifBlank { "Is action ke liye approval chahiye." }) }
                } else { pendingBrainContinuation = null; activeBrainCancellationToken = null; addReply(result.message) }
            }
        }.start()
    }

    fun askOpenAI(question: String, imageBase64: String? = null) {
        if (imageBase64 == null && attachedText.isNullOrBlank()) { askAgentBrain(question); return }
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
        if (attachedText.isNullOrBlank()) { askAgentBrain(question); return }
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


    fun askMetaLlama(question: String) { if (attachedText.isNullOrBlank()) { askAgentBrain(question); return }; val key=llamaApiKey.trim(); if(key.isEmpty()){addReply("Meta Llama API key Settings mein add karein.");showSettings=true;return}; loading=true;status="CONNECTING TO LLAMA...";val prompt=messages.dropLast(1).takeLast(8).joinToString("\n"){it.first+": "+it.second}+"\n"+(attachedText?.takeIf{it.isNotBlank()}?.let{"Attached document text:\n"+it+"\n\n"}?:"")+question;Thread{var answer="";try{answer=VeerExtraAiClient.chat("https://api.groq.com/openai/v1/chat/completions",key,"llama-3.3-70b-versatile",prompt)}catch(e:Exception){answer="Meta Llama error: "+(e.message?: "connection failed")};Handler(Looper.getMainLooper()).post{val moved=answer.startsWith("Meta Llama error:")&&providerFallback?.invoke("LLAMA",question,null)==true;if(!moved){loading=false;addReply(answer)}}}.start() }
    fun askGrok(question: String) { if (attachedText.isNullOrBlank()) { askAgentBrain(question); return }; val key=grokApiKey.trim(); if(key.isEmpty()){addReply("Grok (xAI) API key Settings mein add karein.");showSettings=true;return};loading=true;status="CONNECTING TO GROK...";val prompt=messages.dropLast(1).takeLast(8).joinToString("\n"){it.first+": "+it.second}+"\n"+(attachedText?.takeIf{it.isNotBlank()}?.let{"Attached document text:\n"+it+"\n\n"}?:"")+question;Thread{var answer="";try{answer=VeerExtraAiClient.chat("https://api.x.ai/v1/chat/completions",key,"grok-3-mini",prompt)}catch(e:Exception){answer="Grok error: "+(e.message?: "connection failed")};Handler(Looper.getMainLooper()).post{val moved=answer.startsWith("Grok error:")&&providerFallback?.invoke("GROK",question,null)==true;if(!moved){loading=false;addReply(answer)}}}.start() }

    fun askMuse(question: String) {
        if (attachedText.isNullOrBlank()) { askAgentBrain(question); return }
        val key = museApiKey.trim()
        if (key.isEmpty()) {
            val moved = providerFallback?.invoke("MUSE", question, null) == true
            if (!moved) { addReply("Boss, Meta Muse API key Settings mein add karein."); showSettings = true }
            return
        }
        loading = true
        status = "CONNECTING TO META MUSE..."
        val prompt = messages.dropLast(1).takeLast(8).joinToString("\n") { it.first + ": " + it.second } +
            "\nAttached document text:\n" + attachedText.orEmpty().take(12_000) + "\n\n" + question
        Thread {
            var answer = ""
            try { answer = VeerExtraAiClient.chat("https://api.meta.ai/v1/chat/completions", key, "muse-spark-1.3", prompt) }
            catch (failure: Exception) { answer = "Meta Muse error: " + (failure.message ?: "connection failed") }
            Handler(Looper.getMainLooper()).post {
                val moved = answer.startsWith("Meta Muse error:") && providerFallback?.invoke("MUSE", question, null) == true
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
        if (shouldUseAgentBrainForGeminiRequest(imageBase64, attachedText)) { askAgentBrain(question); return }
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
                        .put("tools", org.json.JSONArray().put(JSONObject().put("functionDeclarations", VeerGeminiToolSchema.functionDeclarations())))
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
                        val toolDecision = VeerGeminiToolCallParser.parse(raw)
                        if (toolDecision != null) {
                            answer = toolDecision.message
                            val requestedCall = toolDecision.toolCall
                            if (requestedCall != null) {
                                val candidateContent = try {
                                    JSONObject(raw).optJSONArray("candidates")
                                        ?.optJSONObject(0)?.optJSONObject("content")
                                } catch (_: Exception) { null }
                                if (candidateContent == null) {
                                    Handler(Looper.getMainLooper()).post {
                                        loading = false
                                        addReply("Boss, Gemini function request ka original content verify nahi hua; action block kar diya.")
                                    }
                                    return@Thread
                                }
                                Handler(Looper.getMainLooper()).post {
                                    val request = agentApprovalSession.request(requestedCall)
                                    if (request == null) {
                                        loading = false
                                        addReply("Boss, ek approval pehle se pending hai ya tool request safety check mein fail hui.")
                                    } else {
                                        pendingGeminiContents = body.optJSONArray("contents")?.toString()
                                        pendingGeminiModelContent = candidateContent.toString()
                                        pendingGeminiFunctionName = requestedCall.name
                                        pendingGeminiModel = model
                                        pendingGeminiApiKey = key
                                        pendingGeminiSystemText = systemText
                                        pendingGeminiToolSteps = 1
                                        pendingAgentApproval = request
                                        pendingAction = request.action
                                        approvalGeneration += 1L
                                        showApproval = true
                                        loading = false
                                        addReply(toolDecision.message)
                                    }
                                }
                                return@Thread
                            }
                        } else {
                            val json = JSONObject(raw)
                            val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                            val result = StringBuilder()
                            if (parts != null) for (i in 0 until parts.length()) result.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
                            answer = if (result.isNotBlank()) result.toString().trim() else "Boss, AI se khaali response mila. Dobara try karein."
                        }
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
            "GEMINI" -> listOf("GEMINI", "OPENAI", "CLAUDE", "MUSE", "LLAMA", "GROK")
            "CLAUDE" -> listOf("CLAUDE", "OPENAI", "GEMINI", "MUSE", "LLAMA", "GROK")
            "OPENAI" -> listOf("OPENAI", "GEMINI", "CLAUDE", "MUSE", "LLAMA", "GROK")
            "MUSE" -> listOf("MUSE", "GEMINI", "OPENAI", "CLAUDE", "LLAMA", "GROK")
            "LLAMA" -> listOf("LLAMA", "GROK", "MUSE", "GEMINI", "OPENAI", "CLAUDE")
            "GROK" -> listOf("GROK", "LLAMA", "MUSE", "GEMINI", "OPENAI", "CLAUDE")
            else -> listOf("OPENAI", "GEMINI", "CLAUDE", "MUSE", "LLAMA", "GROK")
        }
        if (failedProvider !in fallbackAttempted) fallbackAttempted.add(failedProvider)
        val next = preferred.firstOrNull { provider ->
            provider !in fallbackAttempted && when (provider) {
                "OPENAI" -> openAiApiKey.isNotBlank()
                "GEMINI" -> apiKey.isNotBlank()
                "CLAUDE" -> claudeApiKey.isNotBlank() && image == null
                "LLAMA" -> llamaApiKey.isNotBlank() && image == null
                "GROK" -> grokApiKey.isNotBlank() && image == null
                "MUSE" -> museApiKey.isNotBlank() && image == null
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
                "LLAMA" -> askMetaLlama(question)
                "GROK" -> askGrok(question)
                "MUSE" -> askMuse(question)
            }
            true
        }
    }

    fun processCommand(command: String) {
        val clean = command.trim()
        if (clean.isEmpty()) return

        // Snapshot attachments before clearing composer state; the provider reads these values
        // asynchronously on its worker thread.
        val imageForRequest = attachedImageBase64
        val textForRequest = attachedText
        val fileNameForRequest = attachedFileName
        val messageWithAttachment = if (!fileNameForRequest.isNullOrBlank()) "$clean\nAttachment: $fileNameForRequest" else clean
        messages.add("BOSS" to messageWithAttachment)
        fallbackAttempted.clear()

        // Explicit opt-in research commands keep ordinary chat and the established UI unchanged.
        val researchMode = when {
            clean.startsWith("research:", ignoreCase = true) -> "research"
            clean.startsWith("web research:", ignoreCase = true) -> "research"
            clean.startsWith("search:", ignoreCase = true) -> "search"
            clean.startsWith("web search:", ignoreCase = true) -> "search"
            else -> null
        }
        if (researchMode != null) {
            val researchQuery = clean.substringAfter(':').trim()
            if (researchQuery.isBlank() || researchQuery.length > 500) {
                addReply("Boss, research query 1–500 characters ka hona chahiye.")
            } else if (cloudApiUrl.isBlank() || cloudApiToken.isBlank()) {
                addReply("Boss, Web Search ke liye Settings mein VEER Cloud URL aur API token configure karein.")
                showSettings = true
            } else {
                latestResearchReport = ""
                loading = true
                status = if (researchMode == "research") "SEARCHING AND RESEARCHING..." else "SEARCHING THE WEB..."
                val baseUrl = cloudApiUrl
                val token = cloudApiToken
                Thread {
                    var reply = ""
                    var completedResearchReport = ""
                    try {
                        val client = VeerResearchClient(baseUrl, token)
                        val results = client.search(researchQuery, 5)
                        if (results.length() == 0) {
                            reply = "Boss, is query ke liye koi verified web source nahi mila."
                        } else if (researchMode == "search") {
                            val lines = (0 until results.length()).mapNotNull { index ->
                                val item = results.optJSONObject(index) ?: return@mapNotNull null
                                val title = item.optString("title").take(180)
                                val url = item.optString("url").take(2048)
                                val snippet = item.optString("snippet").take(400)
                                if (url.isBlank()) null else "• $title\n$snippet\n$url"
                            }
                            reply = if (lines.isEmpty()) "Boss, search se valid sources nahi mile." else "Web Search results:\n\n" + lines.joinToString("\n\n")
                        } else {
                            val sources = org.json.JSONArray()
                            for (index in 0 until minOf(results.length(), 5)) {
                                val item = results.optJSONObject(index) ?: continue
                                val title = item.optString("title")
                                val url = item.optString("url")
                                val snippet = item.optString("snippet")
                                if (title.isNotBlank() && url.isNotBlank() && snippet.isNotBlank()) {
                                    sources.put(JSONObject().put("title", title).put("url", url).put("snippet", snippet))
                                }
                            }
                            if (sources.length() == 0) {
                                reply = "Boss, search sources mein research ke liye valid text nahi mila."
                            } else {
                                val report = client.synthesize(researchQuery, sources)
                                val citations = report.optJSONArray("citations") ?: org.json.JSONArray()
                                val sourceLines = (0 until citations.length()).mapNotNull { index ->
                                    val source = citations.optJSONObject(index) ?: return@mapNotNull null
                                    val id = source.optString("id")
                                    val title = source.optString("title")
                                    val url = source.optString("url")
                                    if (url.isBlank()) null else "[$id] $title\n$url"
                                }
                                completedResearchReport = VeerResearchReport.format(researchQuery, report.optString("answer", "Research response empty hai."), citations)
                                reply = completedResearchReport +
                                    if (sourceLines.isEmpty()) "" else "\n\nSources:\n" + sourceLines.joinToString("\n")
                            }
                        }
                    } catch (error: Exception) {
                        reply = "Boss, Web Research abhi complete nahi ho paayi: " +
                            (error.message?.take(180) ?: "Cloud connection ya provider configuration check karein.")
                    }
                    Handler(Looper.getMainLooper()).post {
                        loading = false
                        if (completedResearchReport.isNotBlank()) latestResearchReport = completedResearchReport
                        addReply(reply)
                    }
                }.start()
            }
            attachedImageBase64 = null
            attachedText = null
            attachedFileName = null
            return
        }

        // Route the message to the selected real provider/agent. Keep the existing UI unchanged.
        when {
            imageForRequest != null && selectedProvider !in listOf("OPENAI", "GEMINI") -> {
                // The local agent and text-only providers cannot consume image bytes.
                // Prefer the configured vision provider instead of silently dropping the photo.
                when {
                    apiKey.isNotBlank() -> askGemini(clean, imageForRequest)
                    openAiApiKey.isNotBlank() -> askOpenAI(clean, imageForRequest)
                    else -> addReply("Photo samajhne ke liye Settings mein Gemini ya OpenAI API key add karein.")
                }
            }
            textForRequest.isNullOrBlank().not() && selectedProvider == "AUTO" -> {
                // Document text must reach a text-capable provider; the local agent turn
                // currently has no attachment field.
                when {
                    apiKey.isNotBlank() -> askGemini(clean)
                    openAiApiKey.isNotBlank() -> askOpenAI(clean)
                    claudeApiKey.isNotBlank() -> askClaude(clean)
                    museApiKey.isNotBlank() -> askMuse(clean)
                    llamaApiKey.isNotBlank() -> askMetaLlama(clean)
                    grokApiKey.isNotBlank() -> askGrok(clean)
                    else -> addReply("File padhne ke liye Settings mein kisi AI provider ki API key add karein.")
                }
            }
            else -> when (selectedProvider) {
                "OPENAI" -> askOpenAI(clean, imageForRequest)
                "GEMINI" -> askGemini(clean, imageForRequest)
                "CLAUDE" -> if (imageForRequest != null) addReply("Image analysis ke liye Gemini ya OpenAI provider select karein.") else askClaude(clean)
                "LLAMA" -> if (imageForRequest != null) addReply("Image analysis ke liye Gemini ya OpenAI provider select karein.") else askMetaLlama(clean)
                "GROK" -> if (imageForRequest != null) addReply("Image analysis ke liye Gemini ya OpenAI provider select karein.") else askGrok(clean)
                "MUSE" -> if (imageForRequest != null) addReply("Image analysis ke liye Gemini ya OpenAI provider select karein.") else askMuse(clean)
                else -> askAgentBrain(clean)
            }
        }

        attachedImageBase64 = null
        attachedText = null
        attachedFileName = null
        // Keep MIME type intact: OpenAI's background request reads it asynchronously.
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

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraLauncher.launch(null)
        else {
            status = "CAMERA PERMISSION DENIED"
            addReply("Camera permission nahi mili. Photo lene ke liye Android Settings mein VEER ko Camera permission dein.")
        }
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
            addReply("Microphone permission nahi mili. Voice input ke liye Android Settings mein VEER ko Microphone permission dein.")
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
                            voiceMode = false
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

    fun sendTypedMessage() {
        val question = typedCommand.trim()
        if (loading || (question.isBlank() && attachedImageBase64 == null && attachedText.isNullOrBlank())) return
        typedCommand = ""
        processCommand(question.ifBlank { "Please analyse the attached file." })
    }

    val isListening = status.contains("LISTENING") || status.contains("HEARING")
    val uiBackground = Color(0xFF101214)
    val uiSurface = Color(0xFF1A1D21)
    val uiBorder = Color(0xFF2C3137)
    val uiText = Color(0xFFF1F2F4)
    val uiMuted = Color(0xFFA6ABB3)
    val uiAccent = Color(0xFF3DD6C4)
    val uiAccentInk = Color(0xFF062B27)

    MaterialTheme(colorScheme = darkColorScheme(
        primary = uiAccent,
        onPrimary = uiAccentInk,
        background = uiBackground,
        surface = uiSurface,
        onSurface = uiText
    )) {
        CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides
                androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = veerFont)
        ) {
        Column(
            Modifier.fillMaxSize().background(uiBackground).imePadding()
        ) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { keyDraft = apiKey; showSettings = true },
                    modifier = Modifier.size(44.dp)
                ) { VeerOutlineIcon("menu", Modifier.size(19.dp), uiText) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Surface(
                        color = uiSurface,
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Box(Modifier.size(7.dp).background(uiAccent, CircleShape))
                            Text("Veer", color = uiText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                IconButton(
                    onClick = {
                        activeBrainCancellationToken?.cancel()
                        activeBrainCancellationToken = null
                        pendingBrainContinuation = null
                        pendingAgentApproval?.let { agentApprovalSession.cancel(it.id) }
                        pendingAgentApproval = null
                        pendingAction = ""
                        showApproval = false
                        loading = false
                        typedCommand = ""
                        attachedImageBase64 = null
                        attachedText = null
                        attachedFileName = null
                        attachedMimeType = "image/jpeg"
                        messages.clear()
                        status = "ONLINE"
                    },
                    modifier = Modifier.size(44.dp)
                ) { VeerOutlineIcon("plus", Modifier.size(19.dp), uiText) }
            }

            if (messages.isEmpty()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        VeerVoiceOrb(Modifier.size(124.dp))
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "Main Veer hoon",
                            color = uiText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(
                            "Boliye ya likhiye",
                            color = uiMuted,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(22.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = { typedCommand = "Mere liye ek email draft likho: " },
                                color = uiSurface,
                                shape = RoundedCornerShape(18.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder)
                            ) {
                                Text("Mail likho", color = uiText, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                            }
                            Surface(
                                onClick = { typedCommand = "Mere liye ek video script likho: " },
                                color = uiSurface,
                                shape = RoundedCornerShape(18.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder)
                            ) {
                                Text("Video script", color = uiText, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    cameraLauncher.launch(null)
                                } else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            },
                            color = uiSurface,
                            shape = RoundedCornerShape(18.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder)
                        ) {
                            Text("Photo samjhao", color = uiText, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages) { item ->
                        val isUser = item.first == "BOSS"
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                        ) {
                            Surface(
                                color = if (isUser) uiAccent else uiSurface,
                                shape = RoundedCornerShape(16.dp),
                                border = if (isUser) null else androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
                                modifier = Modifier.widthIn(max = 320.dp)
                            ) {
                                Text(
                                    item.second,
                                    color = if (isUser) uiAccentInk else uiText,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                    if (loading) {
                        item {
                            Text("Veer soch raha hai…", color = uiMuted, fontSize = 13.sp)
                        }
                    }
                }
            }

            if (latestResearchReport.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent.createChooser(VeerResearchExport.shareIntent(latestResearchReport), "Share VEER research report"))
                        } catch (error: Exception) {
                            messages.add("VEER" to "Share open nahi ho paaya: " + (error.message ?: "No compatible app"))
                        }
                    }) { Text("SHARE REPORT", color = uiAccent, fontSize = 11.sp) }
                    TextButton(onClick = {
                        researchSaveLauncher.launch(VeerResearchExport.createDocumentIntent())
                    }) { Text("SAVE .MD", color = uiAccent, fontSize = 11.sp) }
                }
            }

            if (attachedImageBase64 != null || !attachedText.isNullOrBlank()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        attachedFileName ?: "Attachment ready",
                        color = uiMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    TextButton(onClick = {
                        attachedImageBase64 = null
                        attachedText = null
                        attachedFileName = null
                    }) { Text("HATAO", color = uiAccent, fontSize = 12.sp) }
                }
            }

            Surface(
                color = uiSurface,
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    OutlinedTextField(
                        value = typedCommand,
                        onValueChange = { typedCommand = it },
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 42.dp),
                        placeholder = {
                            Text(
                                if (isListening) "Sun raha hoon..." else "Message likhiye",
                                color = uiMuted,
                                fontSize = 15.sp
                            )
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = uiText,
                            fontSize = 15.sp,
                            lineHeight = 20.sp
                        ),
                        singleLine = false,
                        maxLines = 4,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { sendTypedMessage() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = uiText,
                            unfocusedTextColor = uiText,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            cursorColor = uiAccent
                        )
                    )
                    Row(
                        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { documentLauncher.launch("*/*") },
                            modifier = Modifier.size(44.dp)
                        ) { VeerOutlineIcon("file", Modifier.size(18.dp), uiMuted) }
                        IconButton(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    cameraLauncher.launch(null)
                                } else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            },
                            modifier = Modifier.size(44.dp)
                        ) { VeerOutlineIcon("camera", Modifier.size(19.dp), uiMuted) }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
                                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            modifier = Modifier.size(44.dp)
                        ) {
                            VeerOutlineIcon("mic", Modifier.size(19.dp), if (isListening) uiAccent else uiMuted)
                        }
                        Box(
                            Modifier.size(44.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier.size(34.dp)
                                    .background(
                                        if (!loading && (typedCommand.isNotBlank() || attachedImageBase64 != null || !attachedText.isNullOrBlank())) uiAccent else Color(0xFF2C3137),
                                        CircleShape
                                    )
                                    .then(
                                        if (!loading && (typedCommand.isNotBlank() || attachedImageBase64 != null || !attachedText.isNullOrBlank()))
                                            Modifier
                                        else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(
                                    onClick = { sendTypedMessage() },
                                    enabled = !loading && (typedCommand.isNotBlank() || attachedImageBase64 != null || !attachedText.isNullOrBlank()),
                                    modifier = Modifier.size(44.dp)
                                ) { VeerOutlineIcon("send", Modifier.size(17.dp), Color.White) }
                            }
                        }
                    }
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
                activeBrainCancellationToken?.cancel()
                activeBrainCancellationToken = null
                pendingBrainContinuation = null
                pendingAgentApproval = null
                pendingMusicUrl = null
                pendingAction = ""
                pendingGeminiContents = null
                pendingGeminiModelContent = null
                pendingGeminiFunctionName = null
                pendingGeminiModel = null
                pendingGeminiApiKey = null
                pendingGeminiSystemText = null
                pendingGeminiToolSteps = 0
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
                activeBrainCancellationToken?.cancel()
                activeBrainCancellationToken = null
                pendingBrainContinuation = null
                pendingAgentApproval = null
                pendingAction = ""
                pendingMusicUrl = null
                pendingGeminiContents = null
                pendingGeminiModelContent = null
                pendingGeminiFunctionName = null
                pendingGeminiModel = null
                pendingGeminiApiKey = null
                pendingGeminiSystemText = null
                pendingGeminiToolSteps = 0
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
                    activeBrainCancellationToken?.cancel()
                    activeBrainCancellationToken = null
                    pendingBrainContinuation = null
                    pendingAgentApproval = null
                    pendingAction = ""
                    pendingMusicUrl = null
                    pendingGeminiContents = null
                    pendingGeminiModelContent = null
                    pendingGeminiFunctionName = null
                    pendingGeminiModel = null
                    pendingGeminiApiKey = null
                    pendingGeminiSystemText = null
                    pendingGeminiToolSteps = 0
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
                    Text("Tailscale Serve ka HTTPS address aur Windows agent token daalein. Health test ke baad ek alag read-only button se sirf VEER workspace ke file names aur sizes dekhe ja sakte hain; file content read nahi hota.", fontSize = 11.sp, color = Color.Gray)
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
                    Button(
                        onClick = {
                            pcWorkspaceLoading = true
                            pcWorkspacePreview = null
                            pcWorkspaceStatus = "Reading workspace directory names only..."
                            localAiScope.launch {
                                try {
                                    val entries = withContext(Dispatchers.IO) {
                                        VeerPcAgentClient(pcAgentUrl, pcAgentToken).listWorkspace(".")
                                    }
                                    pcWorkspacePreview = entries
                                    pcWorkspaceStatus = "Listed ${entries.size} item(s). File contents were not opened."
                                } catch (error: Exception) {
                                    pcWorkspaceStatus = "Workspace listing failed: " + (error.message ?: "Check connection")
                                } finally {
                                    pcWorkspaceLoading = false
                                }
                            }
                        },
                        enabled = !pcAgentChecking && !pcWorkspaceLoading && pcAgentStatus.startsWith("Connected") && pcAgentUrl.isNotBlank() && pcAgentToken.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (pcWorkspaceLoading) "READING..." else "READ-ONLY: LIST VEER WORKSPACE") }
                    if (pcWorkspaceStatus.isNotBlank()) {
                        Text(pcWorkspaceStatus, fontSize = 11.sp, color = Color.Gray)
                    }
                    pcWorkspacePreview?.let { entries ->
                        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                            if (entries.isEmpty()) Text("Workspace is empty.", fontSize = 12.sp)
                            entries.forEach { entry ->
                                Text(
                                    (if (entry.type == "folder") "[DIR] " else if (entry.type == "link") "[LINK] " else "[FILE] ") +
                                        entry.name + (entry.sizeBytes?.let { " — $it bytes" } ?: ""),
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                            }
                        }
                        TextButton(onClick = { pcWorkspacePreview = null; pcWorkspaceStatus = "" }) { Text("CLEAR WORKSPACE PREVIEW") }
                    }
                    Divider()
                    Text("PC TASK QUEUE", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00B8D4))
                    Text("Windows PC par task bhejein. File banana ya website kholna pehle pending approval mein rahega; bina aapke approve kiye execute nahi hoga.", fontSize = 11.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { pcTaskOperation = "create_text_file" },
                            enabled = !pcTaskBusy,
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (pcTaskOperation == "create_text_file") Color(0xFF123B42) else Color.Transparent)
                        ) { Text("CREATE FILE", fontSize = 10.sp) }
                        OutlinedButton(
                            onClick = { pcTaskOperation = "open_url" },
                            enabled = !pcTaskBusy,
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (pcTaskOperation == "open_url") Color(0xFF123B42) else Color.Transparent)
                        ) { Text("OPEN WEBSITE", fontSize = 10.sp) }
                    }
                    if (pcTaskOperation == "create_text_file") {
                        OutlinedTextField(value = pcTaskPath, onValueChange = { pcTaskPath = it }, enabled = !pcTaskBusy, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("New file path inside VEER workspace") })
                        OutlinedTextField(value = pcTaskContent, onValueChange = { pcTaskContent = it }, enabled = !pcTaskBusy, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, label = { Text("Text to write") })
                    } else {
                        OutlinedTextField(value = pcTaskUrl, onValueChange = { pcTaskUrl = it }, enabled = !pcTaskBusy, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("HTTP(S) website URL") })
                    }
                    Button(
                        onClick = {
                            val payload = try {
                                if (pcTaskOperation == "create_text_file") {
                                    require(pcTaskPath.isNotBlank()) { "File path required" }
                                    require(pcTaskContent.isNotBlank()) { "File content required" }
                                    JSONObject().put("path", pcTaskPath.trim()).put("content", pcTaskContent)
                                } else {
                                    val parsed = java.net.URI(pcTaskUrl.trim())
                                    require(parsed.scheme in listOf("http", "https") && !parsed.host.isNullOrBlank() && parsed.rawUserInfo == null) { "Valid HTTP(S) URL without username/password required" }
                                    JSONObject().put("url", pcTaskUrl.trim())
                                }
                            } catch (error: Exception) {
                                pcTaskStatus = error.message ?: "Check task details"
                                return@Button
                            }
                            pcTaskBusy = true
                            pcTaskStatus = "Submitting task securely..."
                            localAiScope.launch {
                                try {
                                    val task = withContext(Dispatchers.IO) {
                                        VeerPcAgentClient(pcAgentUrl, pcAgentToken).enqueueTask(pcTaskOperation, payload, UUID.randomUUID().toString())
                                    }
                                    pcTaskCurrent = task
                                    pcTaskStatus = "Task created: " + task.state.replace('_', ' ')
                                    if (task.state == "pending_approval") showPcTaskApproval = true
                                } catch (error: Exception) {
                                    pcTaskStatus = "Task submit failed: " + (error.message ?: "Check PC connection")
                                } finally { pcTaskBusy = false }
                            }
                        },
                        enabled = !pcTaskBusy && pcAgentStatus.startsWith("Connected") && pcAgentUrl.isNotBlank() && pcAgentToken.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (pcTaskBusy) "PLEASE WAIT..." else "SUBMIT PC TASK") }
                    pcTaskCurrent?.let { task ->
                        Text("Task: " + task.taskId, fontSize = 10.sp, color = Color.Gray)
                        Text("Operation: " + task.operation + " • Status: " + task.state.replace('_', ' '), fontSize = 11.sp, color = Color.White)
                        task.error?.let { Text("Error: " + it, fontSize = 11.sp, color = Color(0xFFFF8A80)) }
                        task.result?.let { Text("Result: " + it.toString(), fontSize = 11.sp, color = Color(0xFF80CBC4)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    pcTaskBusy = true
                                    localAiScope.launch {
                                        try {
                                            pcTaskCurrent = withContext(Dispatchers.IO) { VeerPcAgentClient(pcAgentUrl, pcAgentToken).getTask(task.taskId) }
                                            pcTaskStatus = "Task status refreshed."
                                        } catch (error: Exception) {
                                            pcTaskStatus = "Refresh failed: " + (error.message ?: "Connection error")
                                        } finally { pcTaskBusy = false }
                                    }
                                },
                                enabled = !pcTaskBusy
                            ) { Text("REFRESH", fontSize = 10.sp) }
                            if (task.state == "pending_approval") {
                                Button(onClick = { showPcTaskApproval = true }, enabled = !pcTaskBusy) { Text("REVIEW & APPROVE", fontSize = 10.sp) }
                            }
                            if (task.state == "pending_approval" || task.state == "queued") {
                                TextButton(onClick = { showPcTaskCancelConfirm = true }, enabled = !pcTaskBusy) { Text("CANCEL", fontSize = 10.sp, color = Color(0xFFFF8A80)) }
                            }
                        }
                    }
                    if (pcTaskStatus.isNotBlank()) Text(pcTaskStatus, fontSize = 11.sp, color = Color.Gray)
                    TextButton(
                        onClick = {
                            prefs.edit().remove("pc_agent_url").remove("pc_agent_token").apply()
                            pcAgentUrl = ""
                            pcAgentUrlDraft = ""
                            pcAgentToken = ""
                            pcAgentTokenDraft = ""
                            pcAgentStatus = "Not configured"
                            pcWorkspacePreview = null
                            pcWorkspaceStatus = ""
                            pcTaskCurrent = null
                            pcTaskStatus = ""
                            showPcTaskApproval = false
                            showPcTaskCancelConfirm = false
                        },
                        enabled = !pcAgentChecking
                    ) { Text("REMOVE PC CONNECTION") }
                    Divider()
                    Text("VEER CLOUD TASKS", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00B8D4))
                    Text("Vercel + Neon cloud queue. Test harmless health task create, read-back aur bounded cloud executor run verify karega.", fontSize = 11.sp, color = Color.Gray)
                    OutlinedTextField(value = cloudApiUrlDraft, onValueChange = { cloudApiUrlDraft = it }, enabled = !cloudApiChecking, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("VEER cloud HTTPS address") })
                    OutlinedTextField(value = cloudApiTokenDraft, onValueChange = { cloudApiTokenDraft = it }, enabled = !cloudApiChecking, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Cloud API bearer token") }, visualTransformation = PasswordVisualTransformation())
                    Text("Cloud API: $cloudApiStatus", fontSize = 11.sp, color = if (cloudApiStatus.startsWith("Connected")) Color(0xFF58D68D) else Color.Gray)
                    Button(onClick = {
                        val base = try { VeerCloudTaskClient.normalizeBaseUrl(cloudApiUrlDraft) } catch (error: Exception) { cloudApiStatus = error.message ?: "Enter a valid cloud HTTPS address"; return@Button }
                        val token = try { VeerCloudTaskClient.validateToken(cloudApiTokenDraft) } catch (error: Exception) { cloudApiStatus = error.message ?: "Check the cloud API token"; return@Button }
                        cloudApiChecking = true
                        cloudApiStatus = "Testing authenticated cloud API, Neon persistence and executor..."
                        localAiScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val client = VeerCloudTaskClient(base, token)
                                    val created = client.enqueueTask("health", JSONObject(), UUID.randomUUID().toString())
                                    val fetched = client.getTask(created.taskId)
                                    require(fetched.taskId == created.taskId && fetched.owner == created.owner && fetched.operation == "health" && fetched.payloadSha256 == created.payloadSha256 && fetched.state == "queued") { "Cloud task read-back did not match the created task" }
                                    val executed = client.runTask(created.taskId)
                                    require(executed.taskId == created.taskId && executed.state == "completed" && executed.result?.optBoolean("ok", false) == true) { "Cloud health task did not complete successfully" }
                                    val verified = client.getTask(created.taskId)
                                    require(verified.state == "completed" && verified.result?.optBoolean("ok", false) == true) { "Completed cloud task could not be verified by read-back" }
                                }
                                val encrypted = encryptSecret(token, VEER_CLOUD_TOKEN_ALIAS)
                                if (encrypted == null) cloudApiStatus = "API and executor work, but token encryption failed; not saved"
                                else if (!prefs.edit().putString("cloud_api_url", base).putString("cloud_api_token", encrypted).commit()) cloudApiStatus = "API and executor work, but secure settings could not be saved"
                                else {
                                    cloudApiUrl = base; cloudApiUrlDraft = base; cloudApiToken = token; cloudApiTokenDraft = token
                                    cloudApiStatus = "Connected — auth + Neon write/read + executor run verified"
                                    messages.add("VEER" to "Cloud connection, Neon task persistence aur health executor ka live test pass hua.")
                                }
                            } catch (error: Exception) { cloudApiStatus = "Cloud test failed: " + (error.message ?: "Check Vercel token and deployment") }
                            finally { cloudApiChecking = false }
                        }
                    }, enabled = !cloudApiChecking && cloudApiUrlDraft.isNotBlank() && cloudApiTokenDraft.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (cloudApiChecking) "TESTING..." else "TEST & SAVE CLOUD CONNECTION") }
                    TextButton(onClick = {
                        prefs.edit().remove("cloud_api_url").remove("cloud_api_token").apply()
                        cloudApiUrl = "https://veer-ai-assistant.vercel.app"; cloudApiUrlDraft = cloudApiUrl
                        cloudApiToken = ""; cloudApiTokenDraft = ""; cloudApiStatus = "Not configured"
                    }, enabled = !cloudApiChecking) { Text("REMOVE CLOUD CONNECTION") }
                    Divider()
                    Text("GOOGLE CALENDAR", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00B8D4))
                    Text("Google ke official consent screen se sirf read-only Calendar access. VEER password ya OTP nahi maangta; access token disk par save nahi hota.", fontSize = 11.sp, color = Color.Gray)
                    Text(if (calendarEmail.isNotBlank() && calendarConnected) "Calendar: $calendarEmail" else "Calendar: ${if (calendarConnected) "Connected" else "Not connected"}", fontSize = 12.sp, color = if (calendarConnected) Color(0xFF58D68D) else Color.Gray)
                    Text(calendarStatus, fontSize = 11.sp, color = Color.Gray)
                    Button(onClick = {
                        calendarBusy = true
                        calendarStatus = "Requesting Google Calendar read permission..."
                        calendarAuthorizationClient.authorize(VeerCalendarAuthorization.readAuthorizationRequest())
                            .addOnSuccessListener { authorization ->
                                if (authorization.hasResolution()) {
                                    val pending = authorization.pendingIntent
                                    if (pending == null) {
                                        calendarStatus = "Google Calendar consent screen could not be opened"
                                        calendarBusy = false
                                    } else calendarAuthorizationLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                                } else {
                                    val token = authorization.accessToken
                                    if (token.isNullOrBlank()) {
                                        calendarStatus = "Google did not return Calendar access"
                                        calendarBusy = false
                                    } else acceptCalendarAccessToken(token)
                                }
                            }
                            .addOnFailureListener { error ->
                                calendarStatus = "Calendar authorization failed: " + (error.message ?: "Try again")
                                calendarBusy = false
                            }
                    }, enabled = !calendarBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (calendarBusy) "CONNECTING..." else if (calendarConnected) "REFRESH CALENDAR ACCESS" else "CONNECT GOOGLE CALENDAR")
                    }
                    if (calendarConnected) {
                        Button(onClick = {
                            val token = calendarAccessToken
                            if (token.isNullOrBlank()) {
                                calendarAgendaStatus = "Session expired. Press REFRESH CALENDAR ACCESS first."
                            } else {
                                calendarBusy = true
                                calendarEvents = emptyList()
                                calendarNextPageToken = ""
                                calendarScheduleReport = ""
                                selectedCalendarEventId = ""
                                calendarAgendaStatus = "Loading next 7 days..."
                                localAiScope.launch {
                                    try {
                                        val now = java.time.OffsetDateTime.now()
                                        calendarRangeStart = now.toString()
                                        calendarRangeEnd = now.plusDays(7).toString()
                                        val page = withContext(Dispatchers.IO) {
                                            VeerCalendarClient.listEvents(token, calendarRangeStart, calendarRangeEnd, 25)
                                        }
                                        calendarEvents = page.events
                                        calendarNextPageToken = page.nextPageToken
                                        calendarScheduleReport = ""
                                        selectedCalendarEventId = ""
                                        calendarAgendaStatus = if (page.events.isEmpty()) "No events in the next 7 days" else "${page.events.size} events loaded" + if (page.nextPageToken.isNotBlank()) " · More events available" else ""
                                    } catch (error: Exception) {
                                        calendarAgendaStatus = "Agenda load failed: " + (error.message ?: "Reconnect Calendar")
                                    } finally { calendarBusy = false }
                                }
                            }
                        }, enabled = !calendarBusy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (calendarBusy) "LOADING AGENDA..." else "LOAD NEXT 7 DAYS")
                        }
                        Text(calendarAgendaStatus, fontSize = 11.sp, color = Color.Gray)
                        if (calendarNextPageToken.isNotBlank()) {
                            Button(onClick = {
                                val token = calendarAccessToken
                                if (token.isNullOrBlank()) {
                                    calendarAgendaStatus = "Session expired. Refresh Calendar access first."
                                } else {
                                    calendarBusy = true
                                    calendarAgendaStatus = "Loading more events..."
                                    localAiScope.launch {
                                        try {
                                            val page = withContext(Dispatchers.IO) {
                                                VeerCalendarClient.listEvents(token, calendarRangeStart, calendarRangeEnd, 25, calendarNextPageToken)
                                            }
                                            val merged = (calendarEvents + page.events).distinctBy { it.id }
                                            calendarEvents = merged
                                            calendarNextPageToken = page.nextPageToken
                                            calendarScheduleReport = ""
                                            calendarAgendaStatus = "${merged.size} events loaded" + if (page.nextPageToken.isNotBlank()) " · More events available" else " · All pages loaded"
                                        } catch (error: Exception) {
                                            calendarAgendaStatus = "More events load failed: " + (error.message ?: "Try again")
                                        } finally { calendarBusy = false }
                                    }
                                }
                            }, enabled = !calendarBusy, modifier = Modifier.fillMaxWidth()) {
                                Text(if (calendarBusy) "LOADING..." else "LOAD MORE EVENTS")
                            }
                        }
                        Button(onClick = {
                            if (calendarNextPageToken.isNotBlank()) {
                                calendarScheduleReport = "More Calendar events are available. Load every page before VEER checks free time, so no busy event is missed."
                            } else {
                                val result = VeerCalendarSchedule.analyze(
                                    calendarEvents,
                                    java.time.LocalDate.now(),
                                    dayCount = 7,
                                    zone = java.time.ZoneId.systemDefault(),
                                    requestedMinutes = 60
                                )
                                calendarScheduleReport = if (!result.analysisComplete) {
                                    "Some event times could not be interpreted. VEER cannot safely confirm free time."
                                } else {
                                    val slots = result.freeWindows.take(5).map { VeerCalendarSchedule.formatWindow(it) }
                                    "Next 7 days: ${result.eventCount} calendar events checked.\n60-minute windows (9 AM–5 PM):\n" +
                                        if (slots.isEmpty()) "No 60-minute free window found." else slots.joinToString("\n") +
                                        if (result.freeWindows.size > 5) "\nMore windows available." else ""
                                }
                            }
                        }, enabled = !calendarBusy && calendarEvents.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text("SUMMARIZE SCHEDULE & FIND 60-MIN SLOTS")
                        }
                        if (calendarScheduleReport.isNotBlank()) {
                            Text(calendarScheduleReport, fontSize = 11.sp, color = Color(0xFF80DEEA))
                        }
                        Divider()
                        Text("PREPARE NEW EVENT", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF80DEEA))
                        Text("Create form sirf preview banata hai. Is step mein Google Calendar par kuch save nahi hota.", fontSize = 11.sp, color = Color.Gray)
                        OutlinedTextField(value = calendarDraftTitle, onValueChange = { calendarDraftTitle = it; calendarDraftPreview = ""; calendarDraftApprovedPayload = ""; calendarWriteAuthorizationPending = false; calendarWriteAccessToken = null; calendarDraftError = "" }, label = { Text("Event title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = calendarDraftDate, onValueChange = { calendarDraftDate = it; calendarDraftPreview = ""; calendarDraftApprovedPayload = ""; calendarWriteAuthorizationPending = false; calendarWriteAccessToken = null; calendarDraftError = "" }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(value = calendarDraftStart, onValueChange = { calendarDraftStart = it; calendarDraftPreview = ""; calendarDraftApprovedPayload = ""; calendarWriteAuthorizationPending = false; calendarWriteAccessToken = null; calendarDraftError = "" }, label = { Text("Start (HH:MM)") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = calendarDraftEnd, onValueChange = { calendarDraftEnd = it; calendarDraftPreview = ""; calendarDraftApprovedPayload = ""; calendarWriteAuthorizationPending = false; calendarWriteAccessToken = null; calendarDraftError = "" }, label = { Text("End (HH:MM)") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        OutlinedTextField(value = java.time.ZoneId.systemDefault().id, onValueChange = {}, readOnly = true, label = { Text("Timezone (device)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = calendarDraftLocation, onValueChange = { calendarDraftLocation = it; calendarDraftPreview = ""; calendarDraftApprovedPayload = ""; calendarWriteAuthorizationPending = false; calendarWriteAccessToken = null; calendarDraftError = "" }, label = { Text("Location (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = calendarDraftDescription, onValueChange = { calendarDraftDescription = it; calendarDraftPreview = ""; calendarDraftApprovedPayload = ""; calendarWriteAuthorizationPending = false; calendarWriteAccessToken = null; calendarDraftError = "" }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        Button(onClick = {
                            try {
                                val prepared = VeerCalendarEventDraft.prepare(
                                    calendarDraftTitle, calendarDraftDate, calendarDraftStart, calendarDraftEnd,
                                    java.time.ZoneId.systemDefault().id, calendarDraftLocation, calendarDraftDescription
                                )
                                calendarDraftPreview = prepared.preview()
                                calendarDraftApprovedPayload = VeerCalendarEventWriter.approvalSnapshot(prepared.requestBody)
                                calendarWriteStatus = "Preview ready. No event has been saved."
                                calendarDraftError = ""
                            } catch (error: Exception) {
                                calendarDraftPreview = ""
                                calendarDraftApprovedPayload = ""
                                calendarDraftError = error.message ?: "Please check event details"
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("REVIEW EVENT PREVIEW") }
                        if (calendarDraftError.isNotBlank()) Text(calendarDraftError, fontSize = 11.sp, color = Color(0xFFFF8A80))
                        if (calendarDraftPreview.isNotBlank()) {
                            Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF101B2B), RoundedCornerShape(10.dp)).padding(12.dp)) {
                                Text("PREVIEW ONLY — NOT SAVED", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD180))
                                Text(calendarDraftPreview, fontSize = 12.sp, color = Color.White)
                            }
                        }
                        if (calendarDraftPreview.isNotBlank()) {
                            Button(onClick = {
                                if (calendarDraftApprovedPayload.isBlank()) {
                                    calendarWriteStatus = "Review the event preview again before requesting permission."
                                } else {
                                    calendarWriteAuthorizationPending = true
                                    calendarBusy = true
                                    calendarWriteStatus = "Requesting Google Calendar event-write permission..."
                                    calendarAuthorizationClient.authorize(VeerCalendarAuthorization.writeAuthorizationRequest())
                                        .addOnSuccessListener { authorization ->
                                            if (authorization.hasResolution()) {
                                                val pending = authorization.pendingIntent
                                                if (pending == null) {
                                                    calendarWriteAuthorizationPending = false
                                                    calendarBusy = false
                                                    calendarWriteStatus = "Google write-consent screen could not be opened."
                                                } else calendarAuthorizationLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                                            } else {
                                                val writeToken = authorization.accessToken
                                                if (writeToken.isNullOrBlank()) {
                                                    calendarWriteAuthorizationPending = false
                                                    calendarBusy = false
                                                    calendarWriteStatus = "Google did not return event-write access."
                                                } else {
                                                    calendarWriteAccessToken = writeToken
                                                    calendarWriteAuthorizationPending = false
                                                    calendarBusy = false
                                                    calendarWriteStatus = "Write permission granted. Final approval is still required."
                                                }
                                            }
                                        }
                                        .addOnFailureListener { error ->
                                            calendarWriteAuthorizationPending = false
                                            calendarBusy = false
                                            calendarWriteStatus = "Write permission not granted: " + (error.message ?: "Try again")
                                        }
                                }
                            }, enabled = !calendarBusy && calendarDraftApprovedPayload.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                Text(if (calendarBusy && calendarWriteAuthorizationPending) "REQUESTING WRITE ACCESS..." else "REQUEST EVENT-SAVE PERMISSION")
                            }
                            Text(calendarWriteStatus, fontSize = 11.sp, color = Color.Gray)
                            Button(onClick = {
                                val token = calendarWriteAccessToken
                                val snapshot = calendarDraftApprovedPayload
                                if (token.isNullOrBlank() || snapshot.isBlank()) {
                                    calendarWriteStatus = "Request write permission and review the event first."
                                } else {
                                    calendarDraftApprovedPayload = ""
                                    calendarBusy = true
                                    calendarWriteStatus = "Creating the event after your explicit approval..."
                                    localAiScope.launch {
                                        try {
                                            val created = withContext(Dispatchers.IO) {
                                                VeerCalendarEventWriter.createEvent(token, org.json.JSONObject(snapshot), snapshot)
                                            }
                                            calendarDraftPreview = ""
                                            calendarWriteStatus = "Event created successfully" + created.optString("htmlLink").takeIf { it.startsWith("https://calendar.google.com/") }?.let { ". Open it from Google Calendar." }.orEmpty()
                                            calendarDraftTitle = ""
                                            calendarDraftLocation = ""
                                            calendarDraftDescription = ""
                                            calendarEvents = emptyList()
                                            calendarNextPageToken = ""
                                            calendarAgendaStatus = "Event created. Reload the agenda to verify it."
                                        } catch (error: Exception) {
                                            calendarWriteStatus = "Event save failed or response was uncertain: " + (error.message ?: "Check Google Calendar before retrying")
                                        } finally { calendarBusy = false }
                                    }
                                }
                            }, enabled = !calendarBusy && calendarWriteAccessToken != null && calendarDraftApprovedPayload.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                Text(if (calendarBusy && !calendarWriteAuthorizationPending) "SAVING..." else "I APPROVE — CREATE THIS EVENT")
                            }
                        }
                        calendarEvents.forEach { event ->
                            Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF101B2B), RoundedCornerShape(10.dp)).padding(10.dp)) {
                                TextButton(onClick = {
                                    selectedCalendarEventId = if (selectedCalendarEventId == event.id) "" else event.id
                                    calendarMutationTitle = event.summary
                                    calendarMutationAction = ""
                                    calendarMutationPatch = ""
                                    calendarMutationSnapshot = ""
                                    calendarMutationEventId = ""
                                    calendarMutationStatus = "Prepare and review a change before approving it."
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(event.summary.ifBlank { "(No title)" }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                        Text(VeerCalendarPresentation.formatStart(event), fontSize = 11.sp, color = Color(0xFF80DEEA))
                                    }
                                }
                                if (selectedCalendarEventId == event.id) {
                                    if (event.location.isNotBlank()) Text("Location: ${event.location}", fontSize = 11.sp, color = Color.LightGray)
                                    if (event.description.isNotBlank()) Text(event.description, fontSize = 11.sp, color = Color.LightGray)
                                    Text("Status: ${event.status.ifBlank { "unknown" }}", fontSize = 10.sp, color = Color.Gray)
                                    if (!event.start.isAllDay) {
                                        val eventReminder = calendarReminders.firstOrNull { it.eventId == event.id }
                                        if (eventReminder == null) {
                                            OutlinedButton(onClick = {
                                                val start = event.start.dateTime
                                                if (start.isBlank()) {
                                                    calendarReminderStatus = "This event has no supported timed start."
                                                } else if (Build.VERSION.SDK_INT >= 33 &&
                                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                                    calendarReminderPendingEvent = event.id to start
                                                    calendarReminderPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                } else {
                                                    try {
                                                        VeerCalendarReminderScheduler.schedule(context, event.id, start)
                                                        calendarReminders = VeerCalendarReminderStore.list(context)
                                                        calendarReminderStatus = "10-minute local reminder scheduled."
                                                    } catch (error: Exception) {
                                                        calendarReminderStatus = "Reminder could not be scheduled: " + (error.message ?: "Try again")
                                                    }
                                                }
                                            }, enabled = !calendarBusy, modifier = Modifier.fillMaxWidth()) { Text("REMIND ME 10 MINUTES BEFORE") }
                                        } else {
                                            OutlinedButton(onClick = {
                                                VeerCalendarReminderScheduler.cancel(context, eventReminder.id)
                                                calendarReminders = VeerCalendarReminderStore.list(context)
                                                calendarReminderStatus = "Reminder cancelled."
                                            }, enabled = !calendarBusy, modifier = Modifier.fillMaxWidth()) { Text("CANCEL LOCAL REMINDER") }
                                        }
                                        Text(calendarReminderStatus, fontSize = 10.sp, color = Color.Gray)
                                    } else {
                                        Text("Local reminder unavailable for all-day events in this release.", fontSize = 10.sp, color = Color.Gray)
                                    }
                                    OutlinedTextField(
                                        value = calendarMutationTitle,
                                        onValueChange = { calendarMutationTitle = it; calendarMutationSnapshot = ""; calendarMutationAction = ""; calendarMutationStatus = "Title changed — prepare a fresh preview." },
                                        label = { Text("Updated event title") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (event.etag.isBlank()) {
                                        Text("Safe update/cancel is unavailable because Google did not return a current event version. Reload the agenda first.", fontSize = 11.sp, color = Color(0xFFFF8A80))
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Button(onClick = {
                                            try {
                                                val clean = calendarMutationTitle.trim()
                                                require(clean.isNotEmpty() && clean.length <= 200 && clean.none { it.isISOControl() }) { "Title is required (maximum 200 characters)" }
                                                val patch = org.json.JSONObject().put("summary", clean)
                                                calendarMutationPatch = patch.toString()
                                                calendarMutationAction = VeerCalendarEventMutation.Action.UPDATE.name
                                                calendarMutationEventId = event.id
                                                calendarMutationSnapshot = VeerCalendarEventMutation.approvalSnapshot(VeerCalendarEventMutation.Action.UPDATE, event.id, event.etag, patch)
                                                calendarMutationStatus = "UPDATE PREVIEW — title will change to: $clean. Nothing has been saved."
                                            } catch (error: Exception) { calendarMutationSnapshot = ""; calendarMutationStatus = error.message ?: "Invalid event title" }
                                        }, enabled = event.etag.isNotBlank(), modifier = Modifier.weight(1f)) { Text("PREVIEW UPDATE") }
                                        Button(onClick = {
                                            calendarMutationPatch = ""
                                            calendarMutationAction = VeerCalendarEventMutation.Action.CANCEL.name
                                            calendarMutationEventId = event.id
                                            calendarMutationSnapshot = VeerCalendarEventMutation.approvalSnapshot(VeerCalendarEventMutation.Action.CANCEL, event.id, event.etag)
                                            calendarMutationStatus = "CANCELLATION PREVIEW — this event will be deleted from your primary calendar. Nothing has been changed."
                                        }, enabled = event.etag.isNotBlank(), modifier = Modifier.weight(1f)) { Text("PREVIEW CANCEL") }
                                    }
                                    Text(calendarMutationStatus, fontSize = 11.sp, color = Color(0xFFFFD180))
                                    if (calendarMutationSnapshot.isNotBlank() && calendarMutationEventId == event.id) {
                                        Button(onClick = {
                                            if (calendarWriteAccessToken != null) {
                                                calendarMutationStatus = "Write permission is ready. Review the preview, then approve below."
                                            } else {
                                                calendarWriteAuthorizationPending = true
                                                calendarBusy = true
                                                calendarMutationStatus = "Requesting Google Calendar write permission..."
                                                calendarAuthorizationClient.authorize(VeerCalendarAuthorization.writeAuthorizationRequest())
                                                    .addOnSuccessListener { authorization ->
                                                        if (authorization.hasResolution()) {
                                                            val pending = authorization.pendingIntent
                                                            if (pending == null) {
                                                                calendarWriteAuthorizationPending = false
                                                                calendarBusy = false
                                                                calendarMutationStatus = "Google consent screen could not be opened."
                                                            } else calendarAuthorizationLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                                                        } else {
                                                            val token = authorization.accessToken
                                                            calendarWriteAuthorizationPending = false
                                                            calendarBusy = false
                                                            if (token.isNullOrBlank()) calendarMutationStatus = "Google did not return write access."
                                                            else { calendarWriteAccessToken = token; calendarMutationStatus = "Write permission granted. Final approval is still required." }
                                                        }
                                                    }
                                                    .addOnFailureListener { error ->
                                                        calendarWriteAuthorizationPending = false
                                                        calendarBusy = false
                                                        calendarMutationStatus = "Write permission not granted: " + (error.message ?: "Try again")
                                                    }
                                            }
                                        }, enabled = !calendarBusy, modifier = Modifier.fillMaxWidth()) { Text("REQUEST / CHECK WRITE PERMISSION") }
                                        Button(onClick = {
                                            val token = calendarWriteAccessToken
                                            val action = runCatching { VeerCalendarEventMutation.Action.valueOf(calendarMutationAction) }.getOrNull()
                                            val patch = if (calendarMutationPatch.isBlank()) null else runCatching { org.json.JSONObject(calendarMutationPatch) }.getOrNull()
                                            val snapshot = calendarMutationSnapshot
                                            val target = calendarMutationEventId
                                            if (token.isNullOrBlank() || action == null || target != event.id || snapshot.isBlank()) {
                                                calendarMutationStatus = "Write permission and a fresh matching preview are required."
                                            } else {
                                                calendarMutationSnapshot = ""
                                                calendarBusy = true
                                                calendarMutationStatus = "Applying only the change you explicitly approved..."
                                                localAiScope.launch {
                                                    try {
                                                        withContext(Dispatchers.IO) {
                                                            VeerCalendarEventMutation.execute(token, action, target, event.etag, patch, snapshot)
                                                        }
                                                        if (action == VeerCalendarEventMutation.Action.CANCEL) {
                                                            VeerCalendarReminderScheduler.cancelForEvent(context, target)
                                                            calendarReminders = VeerCalendarReminderStore.list(context)
                                                        }
                                                        calendarMutationStatus = if (action == VeerCalendarEventMutation.Action.CANCEL) "Event cancelled successfully." else "Event updated successfully."
                                                        calendarEvents = emptyList()
                                                        calendarNextPageToken = ""
                                                        selectedCalendarEventId = ""
                                                        calendarMutationEventId = ""
                                                        calendarAgendaStatus = "Calendar changed. Reload the agenda to verify."
                                                    } catch (error: Exception) {
                                                        calendarMutationStatus = "Change failed or response was uncertain: " + (error.message ?: "Check Google Calendar before retrying")
                                                    } finally { calendarBusy = false }
                                                }
                                            }
                                        }, enabled = !calendarBusy && calendarWriteAccessToken != null && event.etag.isNotBlank() && calendarMutationSnapshot.isNotBlank() && calendarMutationEventId == event.id, modifier = Modifier.fillMaxWidth()) {
                                            Text(if (calendarMutationAction == VeerCalendarEventMutation.Action.CANCEL.name) "I APPROVE — CANCEL THIS EVENT" else "I APPROVE — UPDATE THIS EVENT")
                                        }
                                    }
                                }
                            }
                        }
                        TextButton(onClick = {
                            val email = calendarEmail
                            if (!VeerCalendarAuthorization.isValidVerifiedEmailShape(email)) {
                                calendarStatus = "Reconnect Calendar before disconnecting so VEER can verify the account."
                            } else {
                                calendarBusy = true
                                calendarStatus = "Revoking Calendar access..."
                                calendarAuthorizationClient.revokeAccess(VeerCalendarAuthorization.revokeRequest(email))
                                    .addOnSuccessListener {
                                        VeerCalendarReminderScheduler.cancelAll(context)
                                        calendarReminders = emptyList()
                                        calendarReminderStatus = "Calendar reminders cleared after disconnect."
                                        calendarAccessToken = null
                                        calendarWriteAccessToken = null
                                        calendarConnected = false
                                        calendarEmail = ""
                                        calendarEvents = emptyList()
                                        calendarNextPageToken = ""
                                        calendarRangeStart = ""
                                        calendarRangeEnd = ""
                                        calendarScheduleReport = ""
                                        selectedCalendarEventId = ""
                                        prefs.edit().remove("calendar_connected").remove("calendar_email").apply()
                                        calendarStatus = "Google Calendar access revoked"
                                        calendarBusy = false
                                    }
                                    .addOnFailureListener { error ->
                                        calendarStatus = "Calendar revoke failed: " + (error.message ?: "Try again")
                                        calendarBusy = false
                                    }
                            }
                        }, enabled = !calendarBusy) { Text("DISCONNECT & REVOKE CALENDAR ACCESS", color = Color(0xFFFF8A80)) }
                    }
                    Divider()
                    Divider()
                    Text("GOOGLE DRIVE", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00B8D4))
                    Text("Pehle sirf file/folder metadata access. File ka content kholne par alag permission maangi jayegi. VEER password/OTP nahi maangta; token disk par save nahi hota.", fontSize = 11.sp, color = Color.Gray)
                    Text(if (driveEmail.isNotBlank() && driveConnected) "Drive: $driveEmail" else "Drive: ${if (driveConnected) "Connected" else "Not connected"}", fontSize = 12.sp, color = if (driveConnected) Color(0xFF58D68D) else Color.Gray)
                    Text(driveStatus, fontSize = 11.sp, color = Color.Gray)
                    Button(onClick = {
                        driveBusy = true
                        driveStatus = "Requesting Google Drive metadata permission..."
                        driveAuthorizationClient.authorize(VeerDriveAuthorization.metadataAuthorizationRequest())
                            .addOnSuccessListener { authorization ->
                                if (authorization.hasResolution()) {
                                    val pending = authorization.pendingIntent
                                    if (pending == null) {
                                        driveStatus = "Google Drive consent screen could not be opened"
                                        driveBusy = false
                                    } else driveAuthorizationLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                                } else {
                                    val token = authorization.accessToken
                                    if (token.isNullOrBlank()) {
                                        driveStatus = "Google did not return Drive metadata access"
                                        driveBusy = false
                                    } else {
                                        driveAccessToken = token
                                        driveStatus = "Verifying Google account..."
                                        localAiScope.launch {
                                            try {
                                                val email = withContext(Dispatchers.IO) { VeerGmailAuthorization.fetchEmail(token) }
                                                require(VeerDriveAuthorization.isValidVerifiedEmailShape(email)) { "Google account email could not be verified" }
                                                driveEmail = email
                                                driveConnected = true
                                                driveStatus = "Connected — Drive metadata access only"
                                                prefs.edit().putBoolean("drive_connected", true).putString("drive_email", email).apply()
                                            } catch (error: Exception) {
                                                driveAccessToken = null
                                                driveConnected = false
                                                driveStatus = "Drive account verification failed: " + (error.message ?: "Try again")
                                            } finally { driveBusy = false }
                                        }
                                    }
                                }
                            }
                            .addOnFailureListener { error ->
                                driveStatus = "Drive authorization failed: " + (error.message ?: "Try again")
                                driveBusy = false
                            }
                    }, enabled = !driveBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (driveBusy) "CONNECTING..." else if (driveConnected) "REFRESH DRIVE ACCESS" else "CONNECT GOOGLE DRIVE")
                    }
                    if (driveConnected) {
                        OutlinedTextField(value = driveQuery, onValueChange = { driveQuery = it.take(200) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search Drive file names") })
                        Button(onClick = {
                            val token = driveAccessToken
                            if (token.isNullOrBlank()) {
                                driveStatus = "Session expired. Refresh Drive access first."
                            } else {
                                driveBusy = true
                                driveFiles = emptyList()
                                drivePageToken = ""
                                driveStatus = "Searching Drive metadata..."
                                localAiScope.launch {
                                    try {
                                        val page = withContext(Dispatchers.IO) { VeerDriveClient.listFiles(token, driveQuery, driveParentId, 25) }
                                        driveFiles = page.files
                                        drivePageToken = page.nextPageToken
                                        driveStatus = if (page.files.isEmpty()) "No files found" else "${page.files.size} files loaded" + if (page.nextPageToken.isNotBlank()) " · More results available" else ""
                                    } catch (error: Exception) {
                                        driveStatus = "Drive search failed: " + (error.message ?: "Reconnect Drive")
                                    } finally { driveBusy = false }
                                }
                            }
                        }, enabled = !driveBusy, modifier = Modifier.fillMaxWidth()) { Text(if (driveBusy) "SEARCHING..." else "SEARCH DRIVE") }
                        if (driveParentId != null) {
                            TextButton(onClick = {
                                driveParentId = null
                                driveFiles = emptyList()
                                drivePageToken = ""
                                driveStatus = "Root folder selected. Press SEARCH DRIVE."
                            }, enabled = !driveBusy) { Text("BACK TO MY DRIVE") }
                        }
                        driveFiles.forEach { file ->
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Text(file.name.ifBlank { "(Unnamed file)" }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text(file.mimeType + if (file.sizeBytes != null) " · ${file.sizeBytes} bytes" else "", fontSize = 10.sp, color = Color.Gray)
                                TextButton(onClick = { driveContentBytes = null; drivePreviewText = ""; driveContentStatus = ""; driveSelectedFile = file }, enabled = !driveBusy && !driveContentBusy) { Text("FILE DETAILS") }
                                if (file.mimeType == "application/vnd.google-apps.folder") {
                                    TextButton(onClick = {
                                        driveSelectedFile = null
                                        driveContentBytes = null
                                        drivePreviewText = ""
                                        driveContentStatus = ""
                                        driveParentId = file.id
                                        driveFiles = emptyList()
                                        drivePageToken = ""
                                        driveStatus = "Folder selected. Press SEARCH DRIVE."
                                    }, enabled = !driveBusy) { Text("OPEN FOLDER") }
                                }
                            }
                            Divider()
                        }
                        driveSelectedFile?.let { selected ->
                            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                Text("SELECTED FILE DETAILS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00B8D4))
                                Text(selected.name, fontSize = 12.sp, color = Color.White)
                                Text("Type: ${selected.mimeType}", fontSize = 11.sp, color = Color.Gray)
                                Text("Size: ${selected.sizeBytes?.let { "${it} bytes" } ?: "Not provided"}", fontSize = 11.sp, color = Color.Gray)
                                Text("Modified: ${selected.modifiedTime.ifBlank { "Not provided" }}", fontSize = 11.sp, color = Color.Gray)
                                Text("Owner: ${selected.ownerNames.joinToString().ifBlank { "Not provided" }}", fontSize = 11.sp, color = Color.Gray)
                                Button(onClick = {
                                    drivePendingContentFile = selected
                                    driveContentBytes = null
                                    drivePreviewText = ""
                                    driveContentStatus = "Requesting read-only permission for this selected file..."
                                    driveContentBusy = true
                                    driveAuthorizationClient.authorize(VeerDriveAuthorization.contentAuthorizationRequest())
                                        .addOnSuccessListener { authorization ->
                                            if (authorization.hasResolution()) {
                                                val pending = authorization.pendingIntent
                                                if (pending == null) {
                                                    driveContentStatus = "Google content permission screen could not be opened"
                                                    driveContentBusy = false
                                                    drivePendingContentFile = null
                                                } else {
                                                    driveContentAuthorizationLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                                                }
                                            } else {
                                                val token = authorization.accessToken
                                                if (token.isNullOrBlank()) {
                                                    driveContentStatus = "Google did not grant file content access"
                                                    driveContentBusy = false
                                                    drivePendingContentFile = null
                                                } else {
                                                    fetchDriveSelectedContent(token, selected)
                                                }
                                            }
                                        }
                                        .addOnFailureListener { error ->
                                            driveContentStatus = "Drive content permission failed: " + (error.message ?: "Try again")
                                            driveContentBusy = false
                                            drivePendingContentFile = null
                                        }
                                }, enabled = !driveContentBusy, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (driveContentBusy) "REQUESTING FILE ACCESS..." else "OPEN / FETCH SELECTED FILE")
                                }
                                if (driveContentStatus.isNotBlank()) {
                                    Text(driveContentStatus, fontSize = 10.sp, color = Color.Gray)
                                }
                                if (drivePreviewText.isNotBlank()) {
                                    Text("ON-DEVICE TEXT PREVIEW (first 12,000 characters)", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF70F1E2))
                                    Text(
                                        drivePreviewText,
                                        fontSize = 11.sp,
                                        color = Color.White,
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())
                                    )
                                    Button(
                                        onClick = { driveSummaryConsentPending = true },
                                        enabled = !driveSummaryBusy && !driveContentBusy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(if (driveSummaryBusy) "SUMMARIZING SELECTED FILE..." else "SUMMARIZE WITH AI") }
                                }
                                if (driveSummaryStatus.isNotBlank()) {
                                    Text(driveSummaryStatus, fontSize = 10.sp, color = Color.Gray)
                                }
                                if (driveSummaryFileId == selected.id && driveSummaryText.isNotBlank()) {
                                    Text("AI SUMMARY (sent to Gemini only after your approval)", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF70F1E2))
                                    Text(driveSummaryText, fontSize = 11.sp, color = Color.White)
                                }
                                if (driveSummaryConsentPending) {
                                    AlertDialog(
                                        onDismissRequest = { driveSummaryConsentPending = false },
                                        title = { Text("Share selected Drive text with Gemini?") },
                                        text = {
                                            Text(
                                                "VEER will send only the currently selected file name and its on-device text preview (up to 12,000 characters) directly to Google Gemini using your saved Gemini API key. Google/Gemini will process this content under its applicable terms. The content is not sent to VEER cloud. No other AI provider or automatic fallback will receive it. The file may contain untrusted instructions; VEER treats them only as document text. Continue only if you agree."
                                            )
                                        },
                                        confirmButton = {
                                            TextButton(onClick = {
                                                driveSummaryConsentPending = false
                                                val selectedForSummary = driveSelectedFile
                                                val previewForSummary = drivePreviewText
                                                val keyForSummary = apiKey.trim()
                                                if (selectedForSummary == null || previewForSummary.isBlank()) {
                                                    driveSummaryStatus = "Open a supported text file preview first."
                                                } else if (keyForSummary.isBlank()) {
                                                    driveSummaryStatus = "Gemini API key is not configured. No file content was sent."
                                                    showSettings = true
                                                } else {
                                                    driveSummaryBusy = true
                                                    driveSummaryText = ""
                                                    driveSummaryFileId = ""
                                                    driveSummaryStatus = "Sending selected preview to Gemini after your approval..."
                                                    localAiScope.launch {
                                                        try {
                                                            val result = withContext(Dispatchers.IO) {
                                                                VeerDriveSummaryClient.summarize(keyForSummary, selectedForSummary.name, previewForSummary)
                                                            }
                                                            if (driveSelectedFile?.id == selectedForSummary.id) {
                                                                driveSummaryText = result
                                                                driveSummaryFileId = selectedForSummary.id
                                                                driveSummaryStatus = "Summary received from Gemini. No provider fallback was used."
                                                            } else {
                                                                driveSummaryStatus = "Selected file changed; summary was not displayed."
                                                            }
                                                        } catch (error: Exception) {
                                                            driveSummaryStatus = "Gemini summary failed: " + (error.message ?: "Try again")
                                                        } finally {
                                                            driveSummaryBusy = false
                                                        }
                                                    }
                                                }
                                            }) { Text("I AGREE — SEND TO GEMINI") }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { driveSummaryConsentPending = false }) { Text("CANCEL") }
                                        }
                                    )
                                }
                                TextButton(onClick = {
                                    driveContentBytes = null
                                    drivePreviewText = ""
                                    driveSummaryText = ""
                                    driveSummaryFileId = ""
                                    driveSummaryStatus = ""
                                    driveSummaryConsentPending = false
                                    driveContentStatus = ""
                                    driveSelectedFile = null
                                }, enabled = !driveContentBusy && !driveSummaryBusy) { Text("CLOSE DETAILS & CLEAR TEMP CONTENT") }
                                if (selected.shortcutTargetId.isNotBlank()) {
                                    Text("Shortcut target type: ${selected.shortcutTargetMimeType}", fontSize = 11.sp, color = Color.Gray)
                                }
                                if (selected.webViewLink.isNotBlank()) {
                                    TextButton(onClick = {
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(selected.webViewLink)))
                                        } catch (_: Exception) {
                                            driveStatus = "Google Drive link could not be opened on this device."
                                        }
                                    }, enabled = !driveBusy) { Text("OPEN IN GOOGLE DRIVE") }
                                } else {
                                    Text("No trusted Google Drive web link is available.", fontSize = 10.sp, color = Color.Gray)
                                }
                            }
                            Divider()
                        }
                        if (drivePageToken.isNotBlank()) {
                            Button(onClick = {
                                val token = driveAccessToken
                                if (token.isNullOrBlank()) driveStatus = "Refresh Drive access first."
                                else {
                                    driveBusy = true
                                    localAiScope.launch {
                                        try {
                                            val page = withContext(Dispatchers.IO) { VeerDriveClient.listFiles(token, driveQuery, driveParentId, 25, drivePageToken) }
                                            driveFiles = driveFiles + page.files
                                            drivePageToken = page.nextPageToken
                                            driveStatus = "${driveFiles.size} files loaded"
                                        } catch (error: Exception) {
                                            driveStatus = "More results failed: " + (error.message ?: "Try again")
                                        } finally { driveBusy = false }
                                    }
                                }
                            }, enabled = !driveBusy, modifier = Modifier.fillMaxWidth()) { Text("LOAD MORE DRIVE FILES") }
                        }
                    }
                    Text("GMAIL ASSISTANT", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00B8D4))
                    Text("Google ke official consent screen se Gmail read-only permission connect hogi. VEER password nahi maangta aur access token disk par save nahi karta.", fontSize = 11.sp, color = Color.Gray)
                    Text(if (gmailEmail.isNotBlank() && gmailConnected) "Gmail: $gmailEmail" else "Gmail: ${if (gmailConnected) "Connected" else "Not connected"}", fontSize = 12.sp, color = if (gmailConnected) Color(0xFF58D68D) else Color.Gray)
                    Text(gmailStatus, fontSize = 11.sp, color = Color.Gray)
                    Button(onClick = {
                        gmailBusy = true
                        gmailStatus = "Requesting Google consent..."
                        gmailAuthorizationClient.authorize(VeerGmailAuthorization.authorizationRequest())
                            .addOnSuccessListener { authorization ->
                                if (authorization.hasResolution()) {
                                    val pending = authorization.pendingIntent
                                    if (pending == null) {
                                        gmailStatus = "Google consent screen could not be opened"
                                        gmailBusy = false
                                    } else {
                                        gmailAuthorizationLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                                    }
                                } else {
                                    val token = authorization.accessToken
                                    if (token.isNullOrBlank()) {
                                        gmailStatus = "Google did not return Gmail access"
                                        gmailBusy = false
                                    } else {
                                        gmailAccessToken = token
                                        localAiScope.launch {
                                            try {
                                                val email = withContext(Dispatchers.IO) { VeerGmailAuthorization.fetchEmail(token) }
                                                gmailEmail = email
                                                gmailConnected = true
                                                gmailStatus = "Connected — read-only Gmail access"
                                                prefs.edit().putBoolean("gmail_connected", true).putString("gmail_email", email).apply()
                                            } catch (error: Exception) {
                                                gmailAccessToken = null
                                                gmailStatus = "Account verification failed: " + (error.message ?: "Try again")
                                            } finally { gmailBusy = false }
                                        }
                                    }
                                }
                            }
                            .addOnFailureListener { error ->
                                gmailStatus = "Google authorization failed: " + (error.message ?: "Try again")
                                gmailBusy = false
                            }
                    }, enabled = !gmailBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (gmailBusy) "CONNECTING..." else if (gmailConnected) "REFRESH GMAIL ACCESS" else "CONNECT GMAIL")
                    }
                    if (gmailConnected) {
                        OutlinedButton(onClick = {
                            val accountEmail = gmailEmail
                            if (accountEmail.isBlank()) {
                                gmailStatus = "Reconnect Gmail before disconnecting so VEER can identify the Google account."
                            } else {
                                gmailBusy = true
                                gmailStatus = "Revoking Google access..."
                                try {
                                    gmailAuthorizationClient.revokeAccess(VeerGmailAuthorization.revokeRequest(accountEmail))
                                        .addOnSuccessListener {
                                            gmailAccessToken = null
                                            gmailComposeAuthorized = false
                                            gmailComposeAuthorizationPending = false
                                            gmailDraftStatus = "Gmail disconnected"
                                            gmailSendAuthorized = false
                                            gmailSendAuthorizationPending = false
                                            gmailSendReviewOpen = false
                                            gmailSendStatus = "Gmail disconnected"
                                            gmailConnected = false
                                            gmailEmail = ""
                                            gmailInboxItems = emptyList()
                                            gmailSummary = emptyList()
                                            gmailSecurityFindings = emptyList()
                                            VeerGmailReminderScheduler.cancelAll(context)
                                            gmailReminders = emptyList()
                                            gmailQuery = "in:inbox"
                                            gmailInboxStatus = "Inbox cleared after disconnect"
                                            prefs.edit().remove("gmail_connected").remove("gmail_email").apply()
                                            gmailStatus = "Google access revoked and VEER disconnected"
                                            gmailBusy = false
                                        }
                                        .addOnFailureListener { error ->
                                            gmailStatus = "Google revoke failed: " + (error.message ?: "Try again")
                                            gmailBusy = false
                                        }
                                } catch (error: Exception) {
                                    gmailStatus = "Disconnect failed: " + (error.message ?: "Try again")
                                    gmailBusy = false
                                }
                            }
                        }, enabled = !gmailBusy, modifier = Modifier.fillMaxWidth()) {
                            Text("DISCONNECT & REVOKE GMAIL ACCESS")
                        }
                    }
                    if (gmailConnected) {
                        Text("INBOX & SEARCH", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00B8D4))
                        Text("Read-only: inbox aur Gmail search. Email send, delete ya modify nahi hota.", fontSize = 11.sp, color = Color.Gray)
                        Text("Follow-up reminders are local and opt-in. Background Gmail summaries/polling are OFF.", fontSize = 10.sp, color = Color.Gray)
                        OutlinedTextField(value = gmailQuery, onValueChange = { gmailQuery = it.take(500) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Gmail search (e.g. in:inbox is:unread)") })
                        Button(onClick = {
                            val token = gmailAccessToken
                            if (token.isNullOrBlank()) {
                                gmailInboxStatus = "Access token memory mein nahi hai. Pehle REFRESH GMAIL ACCESS dabayein."
                            } else {
                                gmailInboxLoading = true
                                gmailSummary = emptyList()
                                gmailSecurityFindings = emptyList()
                                gmailInboxStatus = "Loading Gmail messages..."
                                localAiScope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            VeerGmailClient.listMessages(token, gmailQuery.trim(), 10).map { ref -> VeerGmailClient.getMessage(token, ref.id) }
                                        }
                                        gmailInboxItems = result
                                        gmailInboxStatus = if (result.isEmpty()) "No matching emails found" else result.size.toString() + " emails loaded"
                                    } catch (error: Exception) {
                                        gmailInboxStatus = "Inbox load failed: " + (error.message ?: "Try refreshing Gmail access")
                                    } finally { gmailInboxLoading = false }
                                }
                            }
                        }, enabled = !gmailInboxLoading && !gmailBusy, modifier = Modifier.fillMaxWidth()) { Text(if (gmailInboxLoading) "LOADING INBOX..." else "LOAD INBOX / SEARCH") }
                        Text(gmailInboxStatus, fontSize = 11.sp, color = Color.Gray)
                        OutlinedButton(onClick = {
                            gmailSummary = VeerGmailSummary.summarize(gmailInboxItems)
                        }, enabled = gmailInboxItems.isNotEmpty() && !gmailInboxLoading, modifier = Modifier.fillMaxWidth()) {
                            Text("SUMMARIZE & PRIORITIZE EMAILS")
                        }
                        OutlinedButton(onClick = {
                            gmailSecurityFindings = VeerGmailSecurity.analyze(gmailInboxItems)
                        }, enabled = gmailInboxItems.isNotEmpty() && !gmailInboxLoading, modifier = Modifier.fillMaxWidth()) {
                            Text("CHECK SUSPICIOUS EMAILS")
                        }
                        if (gmailSecurityFindings.isNotEmpty()) {
                            Text("ADVISORY CHECK — verify the original email; this is not a definitive phishing verdict.", fontSize = 10.sp, color = Color(0xFFFFB74D))
                        }
                        gmailSecurityFindings.forEach { finding ->
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1B20))) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text((if (finding.risk == VeerGmailSecurity.Risk.REVIEW) "REVIEW CAREFULLY" else "CAUTION") + " · " + finding.subject, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFFB74D))
                                    Text(finding.sender, fontSize = 10.sp, color = Color(0xFF8EDDE8))
                                    finding.indicators.forEach { indicator -> Text("• " + indicator, fontSize = 10.sp, color = Color.LightGray) }
                                }
                            }
                        }
                        gmailSummary.forEach { item ->
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF152236))) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text((if (item.priority == VeerGmailSummary.Priority.HIGH) "HIGH PRIORITY" else "NORMAL") + " · " + item.subject, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (item.priority == VeerGmailSummary.Priority.HIGH) Color(0xFFFFB74D) else Color.White)
                                    Text(item.sender, fontSize = 10.sp, color = Color(0xFF8EDDE8))
                                    Text(item.reason, fontSize = 10.sp, color = Color.LightGray)
                                    if (item.preview.isNotBlank()) Text(item.preview, fontSize = 10.sp, color = Color.Gray, maxLines = 2)
                                }
                            }
                        }
                        Divider()
                        Text("CREATE EMAIL DRAFT", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00B8D4))
                        Text("Draft permission alag se Google se maangi jayegi. Save karne par email sirf Gmail Drafts mein rahega; send nahi hoga.", fontSize = 11.sp, color = Color.Gray)
                        OutlinedTextField(value = gmailDraftTo, onValueChange = { gmailDraftTo = it.take(320) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("To — recipient email") })
                        OutlinedTextField(value = gmailDraftSubject, onValueChange = { gmailDraftSubject = it.take(200) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Subject") })
                        OutlinedTextField(value = gmailDraftBody, onValueChange = { gmailDraftBody = it.take(20000) }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8, label = { Text("Email message") })
                        OutlinedButton(onClick = {
                            if (!gmailComposeAuthorized) {
                                gmailComposeAuthorizationPending = true
                                gmailBusy = true
                                gmailStatus = "Requesting Gmail draft permission..."
                                gmailAuthorizationClient.authorize(VeerGmailAuthorization.authorizationRequest(includeCompose = true))
                                    .addOnSuccessListener { authorization ->
                                        if (authorization.hasResolution()) {
                                            val pending = authorization.pendingIntent
                                            if (pending == null) {
                                                gmailDraftStatus = "Google draft permission screen could not be opened"
                                                gmailComposeAuthorizationPending = false
                                                gmailBusy = false
                                            } else {
                                                gmailAuthorizationLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                                            }
                                        } else {
                                            val token = authorization.accessToken
                                            if (token.isNullOrBlank()) {
                                                gmailDraftStatus = "Google did not return draft permission"
                                                gmailComposeAuthorizationPending = false
                                                gmailBusy = false
                                            } else {
                                                gmailAccessToken = token
                                                gmailComposeAuthorized = true
                                                gmailComposeAuthorizationPending = false
                                                gmailDraftStatus = "Draft permission granted. Press SAVE DRAFT again."
                                                gmailBusy = false
                                            }
                                        }
                                    }
                                    .addOnFailureListener { error ->
                                        gmailDraftStatus = "Draft permission failed: " + (error.message ?: "Try again")
                                        gmailComposeAuthorizationPending = false
                                        gmailBusy = false
                                    }
                            } else {
                                val token = gmailAccessToken
                                if (token.isNullOrBlank()) {
                                    gmailDraftStatus = "Refresh Gmail access, then try again."
                                } else {
                                    gmailDraftBusy = true
                                    gmailDraftStatus = "Saving draft to Gmail..."
                                    localAiScope.launch {
                                        try {
                                            val saved = withContext(Dispatchers.IO) {
                                                VeerGmailDraftClient.createDraft(token, gmailDraftTo, gmailDraftSubject, gmailDraftBody)
                                            }
                                            gmailDraftStatus = "Saved in Gmail Drafts. Nothing was sent. Draft ID: " + saved.id
                                        } catch (error: Exception) {
                                            gmailDraftStatus = "Draft save failed: " + (error.message ?: "Try again")
                                        } finally { gmailDraftBusy = false }
                                    }
                                }
                            }
                        }, enabled = !gmailDraftBusy && !gmailBusy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (gmailDraftBusy) "SAVING DRAFT..." else if (!gmailComposeAuthorized) "AUTHORIZE DRAFT ACCESS" else "SAVE TO GMAIL DRAFTS")
                        }
                        Text(gmailDraftStatus, fontSize = 11.sp, color = Color.Gray)
                        Divider()
                        Text("SEND EMAIL — EXPLICIT APPROVAL", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB74D))
                        Text("Send permission alag se Google se maangi jayegi. Uske baad poora recipient, subject aur message preview hoga. Sirf final SEND button dabane par email jayega.", fontSize = 11.sp, color = Color.Gray)
                        OutlinedButton(onClick = {
                            if (!gmailSendAuthorized) {
                                gmailSendAuthorizationPending = true
                                gmailBusy = true
                                gmailStatus = "Requesting separate Gmail send permission..."
                                gmailAuthorizationClient.authorize(VeerGmailAuthorization.authorizationRequest(includeSend = true))
                                    .addOnSuccessListener { authorization ->
                                        if (authorization.hasResolution()) {
                                            val pending = authorization.pendingIntent
                                            if (pending == null) {
                                                gmailSendStatus = "Google send permission screen could not be opened"
                                                gmailSendAuthorizationPending = false
                                                gmailBusy = false
                                            } else {
                                                gmailAuthorizationLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                                            }
                                        } else {
                                            val token = authorization.accessToken
                                            if (token.isNullOrBlank()) {
                                                gmailSendStatus = "Google did not return send permission"
                                                gmailSendAuthorizationPending = false
                                                gmailBusy = false
                                            } else {
                                                gmailAccessToken = token
                                                gmailSendAuthorized = true
                                                gmailSendAuthorizationPending = false
                                                gmailSendStatus = "Send permission granted. Press REVIEW EMAIL again."
                                                gmailBusy = false
                                            }
                                        }
                                    }
                                    .addOnFailureListener { error ->
                                        gmailSendStatus = "Send permission failed: " + (error.message ?: "Try again")
                                        gmailSendAuthorizationPending = false
                                        gmailBusy = false
                                    }
                            } else {
                                gmailReviewTo = gmailDraftTo.trim()
                                gmailReviewSubject = gmailDraftSubject.trim()
                                gmailReviewBody = gmailDraftBody
                                gmailSendReviewOpen = true
                            }
                        }, enabled = !gmailSendBusy && !gmailBusy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (!gmailSendAuthorized) "AUTHORIZE SEND ACCESS" else "REVIEW EMAIL BEFORE SENDING")
                        }
                        Text(gmailSendStatus, fontSize = 11.sp, color = Color.Gray)
                        if (gmailSendReviewOpen) {
                            AlertDialog(
                                onDismissRequest = { if (!gmailSendBusy) gmailSendReviewOpen = false },
                                title = { Text("Final email approval") },
                                text = {
                                    Column(
                                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text("Recipient", fontWeight = FontWeight.Bold)
                                        Text(gmailReviewTo)
                                        Text("Subject", fontWeight = FontWeight.Bold)
                                        Text(gmailReviewSubject.ifBlank { "(No subject)" })
                                        Text("Complete message", fontWeight = FontWeight.Bold)
                                        Text(gmailReviewBody)
                                        Text("This exact email will be sent only if you press SEND EMAIL below.", color = Color(0xFFFFB74D), fontSize = 12.sp)
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val token = gmailAccessToken
                                        if (token.isNullOrBlank()) {
                                            gmailSendStatus = "Refresh Gmail access before sending."
                                            gmailSendReviewOpen = false
                                        } else {
                                            gmailSendBusy = true
                                            gmailSendReviewOpen = false
                                            gmailSendStatus = "Sending the reviewed email..."
                                            localAiScope.launch {
                                                try {
                                                    val sent = withContext(Dispatchers.IO) {
                                                        VeerGmailSendClient.sendReviewedMessage(token, gmailReviewTo, gmailReviewSubject, gmailReviewBody)
                                                    }
                                                    gmailSendStatus = "Email sent successfully. Message ID: " + sent.messageId
                                                } catch (error: Exception) {
                                                    gmailSendStatus = "Email was not confirmed as sent: " + (error.message ?: "Check Gmail Sent folder before retrying")
                                                } finally { gmailSendBusy = false }
                                            }
                                        }
                                    }, enabled = !gmailSendBusy) { Text("SEND EMAIL") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { gmailSendReviewOpen = false }, enabled = !gmailSendBusy) { Text("CANCEL — DO NOT SEND") }
                                }
                            )
                        }
                        gmailInboxItems.forEach { email ->
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF101B2B))) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(email.subject.ifBlank { "(No subject)" }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    Text(email.from.ifBlank { "Unknown sender" }, fontSize = 11.sp, color = Color(0xFF8EDDE8))
                                    Text(email.date, fontSize = 10.sp, color = Color.Gray)
                                    Text(email.snippet.ifBlank { email.bodyText.take(240) }, fontSize = 11.sp, color = Color.LightGray, maxLines = 3)
                                    if (email.labels.isNotEmpty()) Text(email.labels.joinToString(" · "), fontSize = 9.sp, color = Color.Gray)
                                    Text("FOLLOW UP", fontSize = 10.sp, color = Color(0xFF8EDDE8), fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf("1 DAY" to 24L * 60 * 60 * 1000, "3 DAYS" to 3L * 24 * 60 * 60 * 1000, "1 WEEK" to 7L * 24 * 60 * 60 * 1000).forEach { (label, delay) ->
                                            TextButton(onClick = {
                                                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                                    gmailPendingReminder = email.id to delay
                                                    gmailReminderPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                } else {
                                                    try {
                                                        VeerGmailReminderScheduler.schedule(context, email.id, delay)
                                                        gmailReminders = VeerGmailReminderStore.list(context)
                                                        gmailInboxStatus = "Follow-up reminder scheduled."
                                                    } catch (error: Exception) {
                                                        gmailInboxStatus = "Reminder could not be scheduled: " + (error.message ?: "Try again")
                                                    }
                                                }
                                            }) { Text(label, fontSize = 9.sp, color = Color(0xFF8EDDE8)) }
                                        }
                                    }
                                }
                            }
                        }
                        if (gmailReminders.isNotEmpty()) {
                            Text("SCHEDULED FOLLOW-UPS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8EDDE8))
                            gmailReminders.sortedBy { it.triggerAtMillis }.forEach { reminder ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text(gmailInboxItems.firstOrNull { it.id == reminder.messageId }?.subject?.ifBlank { "Email follow-up" } ?: "Email follow-up", fontSize = 11.sp, color = Color.White, maxLines = 1)
                                        Text(SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(reminder.triggerAtMillis)), fontSize = 10.sp, color = Color.Gray)
                                    }
                                    TextButton(onClick = {
                                        VeerGmailReminderScheduler.cancel(context, reminder.id)
                                        gmailReminders = VeerGmailReminderStore.list(context)
                                    }) { Text("CANCEL", fontSize = 10.sp, color = Color(0xFFFFB74D)) }
                                }
                            }
                        }
                    }
                    Divider()
                    Text("AI provider", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Box {
                        TextButton(onClick = { showProviderMenu = true }) { Text("Selected: " + when (selectedProvider) { "GEMINI" -> "Gemini"; "OPENAI" -> "OpenAI"; "CLAUDE" -> "Claude"; "LLAMA" -> "Meta Llama"; "GROK" -> "Grok"; "MUSE" -> "Meta Muse Spark"; else -> "AUTO (fallback order)" }) }
                        DropdownMenu(expanded = showProviderMenu, onDismissRequest = { showProviderMenu = false }) {
                            listOf("AUTO", "GEMINI", "OPENAI", "CLAUDE", "MUSE", "LLAMA", "GROK").forEach { provider ->
                                DropdownMenuItem(text = { Text(when (provider) { "AUTO" -> "AUTO (available keys)"; "MUSE" -> "Meta Muse Spark"; "LLAMA" -> "Meta Llama (Groq)"; "GROK" -> "Grok (xAI)"; else -> provider }) }, onClick = { selectedProvider = provider; showProviderMenu = false })
                            }
                        }
                    }
                    OutlinedTextField(value = openAiKeyDraft, onValueChange = { openAiKeyDraft = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("OpenAI API key (optional)") }, visualTransformation = PasswordVisualTransformation())
                    Text("OpenAI API billing ChatGPT subscription se alag hoti hai. API usage paid ho sakti hai.", color = Color.Gray, fontSize = 11.sp)
                    OutlinedTextField(value = claudeKeyDraft, onValueChange = { claudeKeyDraft = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Claude API key (optional)") }, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(value = llamaKeyDraft, onValueChange = { llamaKeyDraft = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Meta Llama (via Groq) API key (optional)") }, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(value = grokKeyDraft, onValueChange = { grokKeyDraft = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Grok / xAI API key (optional)") }, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(value = museKeyDraft, onValueChange = { museKeyDraft = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Meta Muse Spark API key (optional)") }, visualTransformation = PasswordVisualTransformation())
                    Text("Meta Model API pay-as-you-go hai; usage charge ho sakta hai. Key phone par encrypted save hoti hai.", color = Color.Gray, fontSize = 11.sp)
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
                    val llamaCandidate=llamaKeyDraft.trim();if(llamaCandidate.isNotEmpty()){val enc=encryptApiKey(llamaCandidate);if(enc!=null){llamaApiKey=llamaCandidate;prefs.edit().putString("llama_api_key",enc).apply()}else{messages.add("VEER" to "Meta Llama key encrypt fail.");return@TextButton}}else{llamaApiKey="";prefs.edit().remove("llama_api_key").apply()}
                    val grokCandidate=grokKeyDraft.trim();if(grokCandidate.isNotEmpty()){val enc=encryptApiKey(grokCandidate);if(enc!=null){grokApiKey=grokCandidate;prefs.edit().putString("grok_api_key",enc).apply()}else{messages.add("VEER" to "Grok key encrypt fail.");return@TextButton}}else{grokApiKey="";prefs.edit().remove("grok_api_key").apply()}
                    val museCandidate=museKeyDraft.trim();if(museCandidate.isNotEmpty()){val enc=encryptApiKey(museCandidate);if(enc!=null){museApiKey=museCandidate;prefs.edit().putString("muse_api_key",enc).apply()}else{messages.add("VEER" to "Meta Muse key encrypt fail.");return@TextButton}}else{museApiKey="";prefs.edit().remove("muse_api_key").apply()}
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
                        messages.add("VEER" to "AI provider settings save ho gayi. Gemini key blank thi, isliye remove hui.")
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
                TextButton(onClick = { keyDraft = ""; apiKey = ""; openAiKeyDraft = ""; openAiApiKey = ""; claudeKeyDraft = ""; claudeApiKey = ""; llamaKeyDraft = ""; llamaApiKey = ""; grokKeyDraft = ""; grokApiKey = ""; museKeyDraft = ""; museApiKey = ""; prefs.edit().remove("gemini_api_key").remove("openai_api_key").remove("claude_api_key").remove("llama_api_key").remove("grok_api_key").remove("muse_api_key").apply(); showSettings = false }) { Text("REMOVE KEYS") }
            }
        )
    }
    if (showPcTaskApproval) {
        val task = pcTaskCurrent
        AlertDialog(
            onDismissRequest = { if (!pcTaskBusy) showPcTaskApproval = false },
            title = { Text("Approve this PC task?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Only approve if the exact action and payload below are correct. This sends a one-time approval for the exact SHA-256 payload.")
                    if (task != null) {
                        Text("Operation: " + task.operation, fontWeight = FontWeight.Bold)
                        Text(task.payload.toString(2), fontSize = 11.sp)
                        Text("Payload SHA-256:", fontSize = 10.sp, color = Color.Gray)
                        Text(task.payloadSha256, fontSize = 9.sp)
                    } else Text("Task details unavailable. Refresh before approving.")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !pcTaskBusy && task != null && task.state == "pending_approval",
                    onClick = {
                        val approvedTask = task ?: return@TextButton
                        showPcTaskApproval = false
                        pcTaskBusy = true
                        pcTaskStatus = "Sending your one-time approval..."
                        localAiScope.launch {
                            try {
                                pcTaskCurrent = withContext(Dispatchers.IO) {
                                    VeerPcAgentClient(pcAgentUrl, pcAgentToken).approveTask(approvedTask.taskId, approvedTask.payloadSha256)
                                }
                                pcTaskStatus = "Approval sent. Task status: " + (pcTaskCurrent?.state ?: "unknown")
                            } catch (error: Exception) {
                                pcTaskStatus = "Approval failed: " + (error.message ?: "Check task status")
                            } finally { pcTaskBusy = false }
                        }
                    }
                ) { Text(if (pcTaskBusy) "SENDING..." else "APPROVE THIS EXACT TASK") }
            },
            dismissButton = { TextButton(onClick = { showPcTaskApproval = false }, enabled = !pcTaskBusy) { Text("NOT NOW") } }
        )
    }

    if (showPcTaskCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showPcTaskCancelConfirm = false },
            title = { Text("Cancel PC task?") },
            text = { Text("The task will be cancelled if it has not started running.") },
            confirmButton = {
                TextButton(onClick = {
                    val task = pcTaskCurrent ?: return@TextButton
                    showPcTaskCancelConfirm = false
                    pcTaskBusy = true
                    localAiScope.launch {
                        try {
                            pcTaskCurrent = withContext(Dispatchers.IO) { VeerPcAgentClient(pcAgentUrl, pcAgentToken).cancelTask(task.taskId) }
                            pcTaskStatus = "Task status: " + (pcTaskCurrent?.state ?: "unknown")
                        } catch (error: Exception) {
                            pcTaskStatus = "Cancel failed: " + (error.message ?: "Check task status")
                        } finally { pcTaskBusy = false }
                    }
                }) { Text("CANCEL TASK") }
            },
            dismissButton = { TextButton(onClick = { showPcTaskCancelConfirm = false }) { Text("KEEP TASK") } }
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
    val browLift by animateFloatAsState(
        targetValue = when (expression) {
            VeerFaceExpression.LISTENING -> 7f
            VeerFaceExpression.THINKING -> 3f
            else -> 0f
        },
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "faceExpressionBrowLift"
    )
    val thinkingBrowTilt by animateFloatAsState(
        targetValue = if (expression == VeerFaceExpression.THINKING) 4f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "faceThinkingBrowTilt"
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
    val energyAura by animateFloatAsState(
        targetValue = when (expression) {
            VeerFaceExpression.IDLE -> 0.18f
            VeerFaceExpression.LISTENING -> 0.48f
            VeerFaceExpression.THINKING -> 0.32f
            VeerFaceExpression.SPEAKING -> 0.62f
        },
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "faceEnergyAuraStrength"
    )
    val auraPulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "faceEnergyAuraPulse"
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
    val particlePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "faceParticlePhase"
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
    val orbitRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(18000, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart),
        label = "faceOrbitRotation"
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
    val hudSignalPulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "faceHudSignalPulse"
    )
    val hudSweepRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "faceHudRadarSweep"
    )
    val hudSweepGlow by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "faceHudRadarSweepGlow"
    )
    val hudSweepTrail by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.62f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "faceHudRadarSweepTrail"
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
    val particlePoints = remember {
        listOf(
            Offset(76f, 96f), Offset(284f, 112f), Offset(54f, 228f),
            Offset(306f, 244f), Offset(92f, 318f), Offset(268f, 326f),
            Offset(140f, 72f), Offset(224f, 86f), Offset(66f, 286f),
            Offset(294f, 296f), Offset(180f, 52f), Offset(180f, 350f)
        )
    }
    Canvas(modifier = accessibleModifier) {
        val sx = size.width / 360f
        val sy = size.height / 390f
        fun p(x: Float, y: Float) = Offset(x * sx, y * sy)

        // Four subtle corner brackets frame the hologram HUD.
        val bracketAlpha = 0.38f + scanLineGlow * 0.22f
        val bracketLength = 13f
        val bracketStroke = 1.35f * sx
        val bracketCorners = listOf(
            Triple(38f, 42f, 1f), Triple(322f, 42f, -1f),
            Triple(38f, 338f, 1f), Triple(322f, 338f, -1f)
        )
        bracketCorners.forEach { (x, y, horizontalDirection) ->
            val verticalDirection = if (y < 190f) 1f else -1f
            val corner = p(x, y)
            drawLine(cyan.copy(alpha = bracketAlpha), corner,
                p(x + bracketLength * horizontalDirection, y), bracketStroke)
            drawLine(cyan.copy(alpha = bracketAlpha), corner,
                p(x, y + bracketLength * verticalDirection), bracketStroke)
        }
        // Three pulsing signal indicators show the HUD is active.
        for (signal in 0 until 3) {
            val phase = (hudSignalPulse + signal * 0.22f).coerceIn(0f, 1f)
            val signalAlpha = 0.24f + phase * 0.66f
            drawCircle(
                cyan.copy(alpha = signalAlpha),
                radius = (2.1f + phase * 0.7f) * sx,
                center = p(164f + signal * 16f, 22f)
            )
        }
        // A fading arc trails the radar head to visualize scan direction.
        drawArc(
            cyan.copy(alpha = hudSweepTrail * 0.38f),
            startAngle = hudSweepRotation - 34f,
            sweepAngle = 30f,
            useCenter = false,
            topLeft = Offset(22f * sx, 24f * sy),
            size = androidx.compose.ui.geometry.Size(316f * sx, 316f * sy),
            style = Stroke(width = 3.6f * sx)
        )
        // A bright endpoint marks the radar sweep's current position.
        val sweepRadians = Math.toRadians(hudSweepRotation.toDouble())
        val sweepHead = Offset(
            p(180f, 190f).x + kotlin.math.cos(sweepRadians).toFloat() * 158f * sx,
            p(180f, 190f).y + kotlin.math.sin(sweepRadians).toFloat() * 158f * sy
        )
        drawCircle(cyan.copy(alpha = 0.22f), 7f * sx, sweepHead)
        drawCircle(cyan.copy(alpha = 0.88f), 2.5f * sx, sweepHead)
        // A short radial beam connects the sweep center to its moving endpoint.
        drawLine(
            cyan.copy(alpha = 0.10f + hudSweepGlow * 0.16f),
            p(180f, 190f),
            sweepHead,
            strokeWidth = 0.85f * sx
        )
        // The radar sweep has a soft halo and a crisp leading edge.
        drawArc(
            cyan.copy(alpha = 0.12f + hudSweepGlow * 0.12f),
            startAngle = hudSweepRotation - 3f,
            sweepAngle = 32f,
            useCenter = false,
            topLeft = Offset(19f * sx, 21f * sy),
            size = androidx.compose.ui.geometry.Size(322f * sx, 322f * sy),
            style = Stroke(width = 7f * sx)
        )
        drawArc(
            cyan.copy(alpha = 0.42f + hudSweepGlow * 0.3f),
            startAngle = hudSweepRotation,
            sweepAngle = 26f,
            useCenter = false,
            topLeft = Offset(22f * sx, 24f * sy),
            size = androidx.compose.ui.geometry.Size(316f * sx, 316f * sy),
            style = Stroke(width = 2.2f * sx)
        )
        // HUD tick marks around the face perimeter.
        val hudRadius = size.minDimension * 0.49f
        val hudCenter = p(180f, 190f)
        for (tick in 0 until 48) {
            val angle = tick * (6.28318f / 48f)
            val outer = hudRadius
            val inner = hudRadius - if (tick % 4 == 0) 9f * sx else 4f * sx
            val start = Offset(hudCenter.x + kotlin.math.cos(angle) * inner, hudCenter.y + kotlin.math.sin(angle) * inner)
            val end = Offset(hudCenter.x + kotlin.math.cos(angle) * outer, hudCenter.y + kotlin.math.sin(angle) * outer)
            drawLine(
                cyan.copy(alpha = if (tick % 4 == 0) 0.38f else 0.12f),
                start,
                end,
                strokeWidth = if (tick % 4 == 0) 1.3f * sx else 0.65f * sx
            )
        }
        // Reactive outer energy aura intensifies while VEER listens or speaks.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    cyan.copy(alpha = energyAura * 0.34f),
                    cyan.copy(alpha = energyAura * 0.14f),
                    Color.Transparent
                ),
                center = p(180f, 190f),
                radius = size.minDimension * 0.56f * auraPulse
            ),
            radius = size.minDimension * 0.56f * auraPulse,
            center = p(180f, 190f)
        )
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
        // Subtle particles drift slowly around the hologram face.
        particlePoints.forEachIndexed { index, point ->
            val phase = (particlePhase + index / particlePoints.size.toFloat()) % 1f
            val drift = (phase - 0.5f) * 10f
            val alpha = (0.25f + 0.55f * (0.5f + 0.5f * kotlin.math.sin(phase * 6.28318f))).coerceIn(0f, 0.8f)
            drawCircle(
                cyan.copy(alpha = alpha),
                radius = (1.1f + (index % 3) * 0.45f) * sx,
                center = p(point.x, point.y + drift)
            )
        }
        // Light orbital arcs reinforce the hologram effect without heavy geometry.
        drawArc(cyan.copy(alpha = 0.14f), orbitRotation, 72f, false,
            Offset(28f * sx, 32f * sy), androidx.compose.ui.geometry.Size(304f * sx, 304f * sy),
            style = Stroke(1f * sx))
        drawArc(Color.White.copy(alpha = 0.10f), -orbitRotation * 0.55f, 38f, false,
            Offset(38f * sx, 42f * sy), androidx.compose.ui.geometry.Size(284f * sx, 284f * sy),
            style = Stroke(0.8f * sx))
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
        val currentThinkingTilt = thinkingBrowTilt
        drawLine(cyan, p(88f, 145f - browLift), p(150f, 139f - browLift + currentThinkingTilt), 2.3f*sx)
        drawLine(cyan, p(210f, 139f - browLift - currentThinkingTilt), p(272f, 145f - browLift), 2.3f*sx)
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
