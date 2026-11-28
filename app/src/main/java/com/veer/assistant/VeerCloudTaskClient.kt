package com.veer.assistant

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

/** Task returned by VEER's authenticated Vercel/Neon cloud queue. */
data class VeerCloudTask(
    val taskId: String,
    val owner: String,
    val operation: String,
    val payload: JSONObject,
    val payloadSha256: String,
    val state: String,
    val approvalConsumed: Boolean,
    val result: JSONObject?,
    val error: String?,
    val createdAt: Double,
    val updatedAt: Double,
    val expiresAt: Double
)

/**
 * HTTPS-only Android transport for VEER's cloud task API.
 * The bearer credential is supplied by the caller and is never logged or persisted here.
 */
class VeerCloudTaskClient(baseUrl: String, token: String) {
    private val endpoint = normalizeBaseUrl(baseUrl)
    private val bearerToken = validateToken(token)

    fun enqueueTask(operation: String, payload: JSONObject, idempotencyKey: String = UUID.randomUUID().toString()): VeerCloudTask {
        require(operation in ALLOWED_OPERATIONS) { "Unsupported cloud task operation" }
        require(idempotencyKey.matches(IDEMPOTENCY_KEY)) { "Invalid cloud task idempotency key" }
        require(payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Cloud task payload is too large"
        }
        val body = JSONObject()
            .put("operation", operation)
            .put("payload", payload)
            .put("idempotency_key", idempotencyKey)
        return parseTaskEnvelope(request("POST", "/api/cloud/tasks", body))
    }

    fun getTask(taskId: String): VeerCloudTask =
        parseTaskEnvelope(request("GET", "/api/cloud/tasks/${validateTaskId(taskId)}"))

    fun approveTask(taskId: String, exactPayloadSha256: String): VeerCloudTask {
        require(exactPayloadSha256.matches(HASH)) { "Exact cloud task approval hash is required" }
        val body = JSONObject().put("payload_sha256", exactPayloadSha256.lowercase())
        return parseTaskEnvelope(request("POST", "/api/cloud/tasks/${validateTaskId(taskId)}/approve", body))
    }

    fun cancelTask(taskId: String): VeerCloudTask =
        parseTaskEnvelope(request("POST", "/api/cloud/tasks/${validateTaskId(taskId)}/cancel", JSONObject()))

    fun runTask(taskId: String): VeerCloudTask =
        parseTaskEnvelope(request("POST", "/api/cloud/tasks/${validateTaskId(taskId)}/run", JSONObject()))

    internal fun parseTaskEnvelope(response: JSONObject): VeerCloudTask {
        if (!response.optBoolean("ok", false)) {
            throw IOException(response.optString("error").ifBlank { "VEER cloud task request failed" })
        }
        val task = response.optJSONObject("task") ?: throw IOException("Cloud service returned an invalid task")
        val id = task.optString("task_id")
        val owner = task.optString("owner")
        val operation = task.optString("operation")
        val payload = task.optJSONObject("payload")
        val hash = task.optString("payload_sha256")
        val state = task.optString("state")
        if (!id.matches(TASK_ID) || owner.isBlank() || operation !in ALLOWED_OPERATIONS ||
            payload == null || !hash.matches(HASH) || state !in TASK_STATES
        ) throw IOException("Cloud service returned an invalid task")
        val created = task.optDouble("created_at", Double.NaN)
        val updated = task.optDouble("updated_at", Double.NaN)
        val expires = task.optDouble("expires_at", Double.NaN)
        if (!created.isFinite() || !updated.isFinite() || !expires.isFinite()) {
            throw IOException("Cloud service returned invalid task timestamps")
        }
        return VeerCloudTask(
            taskId = id,
            owner = owner,
            operation = operation,
            payload = payload,
            payloadSha256 = hash.lowercase(),
            state = state,
            approvalConsumed = task.optBoolean("approval_consumed", false),
            result = task.optJSONObject("result"),
            error = task.optString("error").takeIf { it.isNotBlank() && it != "null" },
            createdAt = created,
            updatedAt = updated,
            expiresAt = expires
        )
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(endpoint + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", "Bearer " + bearerToken)
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_REQUEST_BYTES) throw IOException("Cloud task request is too large")
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { readBounded(it) } ?: ByteArray(0)
            val json = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                throw IOException("Cloud service returned an invalid response")
            }
            if (status !in 200..299) {
                throw IOException(json.optString("error").ifBlank { "Cloud service HTTP $status" })
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw IOException("Cloud service response is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val MAX_PAYLOAD_BYTES = 16 * 1024
        private const val MAX_REQUEST_BYTES = 20 * 1024
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private val ALLOWED_OPERATIONS = setOf("health", "list_workspace", "create_text_file", "open_url")
        private val TASK_STATES = setOf("pending_approval", "queued", "running", "completed", "failed", "cancelled", "expired")
        private val TASK_ID = Regex("^[A-Za-z0-9_-]{16,64}$")
        private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]{16,128}$")
        private val HASH = Regex("^[a-fA-F0-9]{64}$")

        internal fun validateTaskId(raw: String): String {
            require(raw.matches(TASK_ID)) { "Invalid cloud task ID" }
            return raw
        }

        fun normalizeBaseUrl(raw: String): String {
            val value = raw.trim().trimEnd('/')
            val uri = try { URI(value) } catch (_: Exception) {
                throw IllegalArgumentException("Enter a valid VEER cloud HTTPS address")
            }
            require(uri.scheme?.equals("https", true) == true && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
                "VEER cloud connection must use HTTPS without embedded credentials"
            }
            require(uri.rawQuery == null && uri.rawFragment == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")) {
                "Enter the cloud host only, without a path, query or fragment"
            }
            return "https://" + uri.rawAuthority
        }

        fun validateToken(raw: String): String {
            val value = raw.trim()
            require(value.length >= 32 && value.none { it.isWhitespace() || it.isISOControl() }) {
                "The cloud API token must be at least 32 characters with no spaces"
            }
            return value
        }
    }
}
