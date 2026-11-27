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

    private fun request(path: String): JSONObject {
        val connection = (URL(endpoint + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", "Bearer " + bearerToken)
            setRequestProperty("Accept", "application/json")
        }
        try {
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
        private const val MAX_LIST_ITEMS = 200
        private const val MAX_ENTRY_NAME_CHARS = 255
        private val ALLOWED_ENTRY_TYPES = setOf("file", "folder", "link")

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
