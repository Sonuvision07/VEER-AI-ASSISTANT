package com.veer.assistant

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

data class VeerPcAgentHealth(
    val agent: String,
    val version: String
)

data class VeerPcAgentFileEntry(
    val name: String,
    val type: String,
    val sizeBytes: Long?
)

data class VeerPcAgentTask(
    val taskId: String,
    val operation: String,
    val payload: JSONObject,
    val payloadSha256: String,
    val state: String,
    val result: JSONObject?,
    val error: String?,
    val createdAt: Double,
    val updatedAt: Double,
    val expiresAt: Double
)

/**
 * Minimal authenticated HTTPS client for the restricted Windows companion.
 * Redirects are disabled so the bearer token cannot be forwarded to another host.
 */
class VeerPcAgentClient(baseUrl: String, token: String) {
    private val endpoint = normalizeBaseUrl(baseUrl)
    private val bearerToken = validateToken(token)

    fun listWorkspace(path: String = "."): List<VeerPcAgentFileEntry> {
        val safePath = validateWorkspacePath(path)
        val encodedPath = URLEncoder.encode(safePath, Charsets.UTF_8.name())
        val response = request("/files?path=$encodedPath")
        if (!response.optBoolean("ok", false)) {
            throw IOException(response.optString("error").ifBlank { "Windows workspace listing failed" })
        }
        return parseWorkspaceResponse(response)
    }

    fun health(): VeerPcAgentHealth {
        val response = request("/health")
        if (!response.optBoolean("ok", false)) {
            throw IOException(response.optString("error").ifBlank { "Windows agent health check failed" })
        }
        return VeerPcAgentHealth(
            agent = response.optString("agent", "Unknown agent"),
            version = response.optString("version", "unknown")
        )
    }

    fun enqueueTask(operation: String, payload: JSONObject, idempotencyKey: String): VeerPcAgentTask {
        require(operation in ALLOWED_TASKS) { "Unsupported PC task operation" }
        require(idempotencyKey.length in 16..128 && idempotencyKey.all { it.code in 33..126 }) {
            "Invalid task idempotency key"
        }
        require(payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_TASK_PAYLOAD_BYTES) {
            "PC task payload is too large"
        }
        val body = JSONObject()
            .put("operation", operation)
            .put("payload", payload)
            .put("idempotency_key", idempotencyKey)
        return parseTaskEnvelope(request("POST", "/tasks", body))
    }

    fun getTask(taskId: String): VeerPcAgentTask {
        val safeId = validateTaskId(taskId)
        return parseTaskEnvelope(request("GET", "/tasks/$safeId"))
    }

    fun approveTask(taskId: String, exactPayloadSha256: String): VeerPcAgentTask {
        val safeId = validateTaskId(taskId)
        validateApprovalHash(exactPayloadSha256)
        val body = JSONObject().put("payload_sha256", exactPayloadSha256.lowercase())
        return parseTaskEnvelope(request("POST", "/tasks/$safeId/approve", body))
    }

    fun cancelTask(taskId: String): VeerPcAgentTask {
        val safeId = validateTaskId(taskId)
        return parseTaskEnvelope(request("POST", "/tasks/$safeId/cancel", JSONObject()))
    }

    internal fun parseTaskEnvelope(response: JSONObject): VeerPcAgentTask {
        if (!response.optBoolean("ok", false)) {
            throw IOException(response.optString("error").ifBlank { "Windows task request failed" })
        }
        val task = response.optJSONObject("task") ?: throw IOException("Windows agent returned an invalid task")
        val id = task.optString("task_id")
        val operation = task.optString("operation")
        val payload = task.optJSONObject("payload")
        val hash = task.optString("payload_sha256")
        val state = task.optString("state")
        if (id.isBlank() || operation !in ALLOWED_TASKS || payload == null ||
            !hash.matches(Regex("^[a-fA-F0-9]{64}$")) ||
            state !in TASK_STATES
        ) throw IOException("Windows agent returned an invalid task")
        val created = task.optDouble("created_at", Double.NaN)
        val updated = task.optDouble("updated_at", Double.NaN)
        val expires = task.optDouble("expires_at", Double.NaN)
        if (!created.isFinite() || !updated.isFinite() || !expires.isFinite()) {
            throw IOException("Windows agent returned invalid task timestamps")
        }
        return VeerPcAgentTask(
            taskId = id,
            operation = operation,
            payload = payload,
            payloadSha256 = hash.lowercase(),
            state = state,
            result = task.optJSONObject("result"),
            error = task.optString("error").takeIf { it.isNotBlank() && it != "null" },
            createdAt = created,
            updatedAt = updated,
            expiresAt = expires
        )
    }

    private fun request(path: String): JSONObject = request("GET", path)

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
                connection.outputStream.use { output ->
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    if (bytes.size > MAX_TASK_PAYLOAD_BYTES) throw IOException("PC task payload is too large")
                    output.write(bytes)
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { readBounded(it) } ?: ByteArray(0)
            val json = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                throw IOException("Windows agent returned an invalid response")
            }
            if (status !in 200..299) {
                throw IOException(json.optString("error").ifBlank { "Windows agent HTTP " + status })
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
            if (total > MAX_RESPONSE_BYTES) throw IOException("Windows agent response is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val MAX_TASK_PAYLOAD_BYTES = 16 * 1024
        private val ALLOWED_TASKS = setOf("health", "list_workspace", "create_text_file", "open_url")
        private val TASK_STATES = setOf("pending_approval", "queued", "running", "completed", "failed", "cancelled", "expired")
        private const val MAX_LIST_ITEMS = 200
        private const val MAX_ENTRY_NAME_CHARS = 255
        private val ALLOWED_ENTRY_TYPES = setOf("file", "folder", "link")

        internal fun validateTaskId(raw: String): String {
            require(raw.matches(Regex("^[A-Za-z0-9_-]{16,64}$"))) { "Invalid PC task ID" }
            return raw
        }

        internal fun validateApprovalHash(raw: String): String {
            require(raw.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                "A valid exact-task approval hash is required"
            }
            return raw.lowercase()
        }

        internal fun parseWorkspaceResponse(response: JSONObject): List<VeerPcAgentFileEntry> {
            if (!response.optBoolean("ok", false)) {
                throw IOException(response.optString("error").ifBlank { "Windows workspace listing failed" })
            }
            val items = response.optJSONArray("items") ?: throw IOException("Windows agent returned an invalid file list")
            if (items.length() > MAX_LIST_ITEMS) throw IOException("Windows agent returned too many workspace entries")
            val entries = ArrayList<VeerPcAgentFileEntry>(items.length())
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: throw IOException("Windows agent returned an invalid file entry")
                val name = item.optString("name")
                val type = item.optString("type")
                if (name.isBlank() || name == "." || name == ".." || name.length > MAX_ENTRY_NAME_CHARS || name.any { it.isISOControl() } ||
                    name.contains('/') || name.contains('\\') || type !in ALLOWED_ENTRY_TYPES
                ) throw IOException("Windows agent returned an invalid file entry")
                val size = if (item.isNull("size")) {
                    null
                } else {
                    when (val rawSize = item.opt("size")) {
                        is Number -> {
                            val numeric = rawSize.toDouble()
                            val integer = rawSize.toLong()
                            if (numeric.isFinite() && numeric >= 0.0 && numeric == integer.toDouble()) integer
                            else throw IOException("Windows agent returned an invalid file size")
                        }
                        else -> throw IOException("Windows agent returned an invalid file size")
                    }
                }
                if (type != "file" && size != null) throw IOException("Windows agent returned an unexpected file size")
                entries.add(VeerPcAgentFileEntry(name, type, size))
            }
            return entries
        }

        internal fun validateWorkspacePath(raw: String): String {
            val value = raw.trim()
            require(value.isNotEmpty()) { "Workspace path is required" }
            require(value == "." || (
                !value.startsWith("/") && !value.startsWith("\\\\") &&
                !value.contains('\\') && !value.contains(':') &&
                value.split('/').all { it.isNotBlank() && it != "." && it != ".." && it.none(Char::isISOControl) }
            )) { "Only relative workspace paths inside VEER's workspace are allowed" }
            require(value.length <= 512) { "Workspace path is too long" }
            return value
        }

        fun normalizeBaseUrl(raw: String): String {
            val value = raw.trim().trimEnd('/')
            require(value.isNotBlank()) { "Tailscale HTTPS address is required" }
            val uri = try {
                URI(value)
            } catch (_: Exception) {
                throw IllegalArgumentException("Enter a valid HTTPS address")
            }
            require(uri.scheme?.equals("https", ignoreCase = true) == true) {
                "For safety, the Windows connection must use HTTPS (Tailscale Serve)"
            }
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
                "Enter an HTTPS host without a username or password"
            }
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "The base address cannot contain a query or fragment"
            }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                "Enter the Tailscale host address only, without an extra path"
            }
            return "https://" + uri.rawAuthority
        }

        fun validateToken(raw: String): String {
            val value = raw.trim()
            require(value.length >= 40) { "The Windows agent token must be at least 40 characters" }
            require(value.none { it.isWhitespace() || it.isISOControl() }) {
                "The Windows agent token cannot contain spaces, line breaks or control characters"
            }
            return value
        }
    }
}
