package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Bounded, metadata-only Google Drive v3 client.
 *
 * This client intentionally exposes GET operations only. Callers must obtain
 * the metadata-only OAuth grant before use. It never logs or persists tokens
 * or private Drive metadata, and it never follows redirects.
 */
object VeerDriveClient {
    private const val API_ROOT = "https://www.googleapis.com/drive/v3"
    private const val MAX_RESPONSE_BYTES = 1_048_576
    private const val MAX_CONTENT_BYTES = 2_097_152
    private const val MAX_RESULTS = 50
    private const val MAX_PAGE_TOKEN = 2048
    private const val MAX_FILE_ID = 256
    private const val MAX_NAME_QUERY = 200
    private const val MAX_TEXT = 1000
    private const val FILE_FIELDS =
        "id,name,mimeType,size,modifiedTime,webViewLink,parents,owners(displayName),trashed,shortcutDetails(targetId,targetMimeType)"
    private const val LIST_FIELDS = "nextPageToken,files($FILE_FIELDS)"

    data class DriveFile(
        val id: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long?,
        val modifiedTime: String,
        val webViewLink: String,
        val parentIds: List<String>,
        val ownerNames: List<String>,
        val isTrashed: Boolean,
        val shortcutTargetId: String,
        val shortcutTargetMimeType: String
    )

    data class FilePage(val files: List<DriveFile>, val nextPageToken: String)

    data class SelectedContent(val bytes: ByteArray, val contentType: String, val exported: Boolean)

    /** Fetch one explicitly selected file into bounded memory; never persists content. */
    fun fetchSelectedContent(
        accessToken: String,
        fileId: String,
        mimeType: String,
        declaredSizeBytes: Long?
    ): SelectedContent {
        validateToken(accessToken)
        val request = buildContentRequest(fileId, mimeType, declaredSizeBytes)
        val connection = (URL(request.first).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 15000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", request.second)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Google Drive content request returned HTTP $status")
            val declared = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (declared != null && declared > MAX_CONTENT_BYTES) {
                throw IOException("Selected file exceeds the 2 MiB preview limit")
            }
            val responseType = connection.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (responseType.isNotBlank() && responseType != request.second) {
                throw IOException("Google Drive returned an unexpected content type")
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (count > MAX_CONTENT_BYTES - total) throw IOException("Selected file exceeds the 2 MiB preview limit")
                    output.write(buffer, 0, count)
                    total += count
                }
                output.toByteArray()
            }
            return SelectedContent(bytes, responseType.ifBlank { request.second }, request.third)
        } finally {
            connection.disconnect()
        }
    }

    /** URL, expected MIME type, and whether this uses a Workspace export endpoint. */
    internal fun buildContentRequest(
        fileId: String,
        mimeType: String,
        declaredSizeBytes: Long?
    ): Triple<String, String, Boolean> {
        require(isSafeId(fileId)) { "Invalid Drive file id" }
        require(declaredSizeBytes == null || declaredSizeBytes in 0..MAX_CONTENT_BYTES.toLong()) {
            "Selected file exceeds the 2 MiB preview limit"
        }
        val exportMime = when (mimeType) {
            "application/vnd.google-apps.document" -> "text/plain"
            "application/vnd.google-apps.spreadsheet" -> "text/csv"
            "application/vnd.google-apps.presentation" -> "application/pdf"
            else -> null
        }
        if (exportMime != null) {
            return Triple("$API_ROOT/files/${enc(fileId)}/export?mimeType=${enc(exportMime)}", exportMime, true)
        }
        require(mimeType in setOf("text/plain", "text/markdown", "text/csv", "application/pdf")) {
            "This file type is not supported for the first preview release"
        }
        return Triple("$API_ROOT/files/${enc(fileId)}?alt=media", mimeType, false)
    }

    /** Fetch one page only. Pagination is explicit and never follows nextPageToken automatically. */
    fun listFiles(
        accessToken: String,
        nameContains: String = "",
        parentId: String? = null,
        pageSize: Int = 25,
        pageToken: String = ""
    ): FilePage {
        validateToken(accessToken)
        require(pageSize in 1..MAX_RESULTS) { "pageSize must be between 1 and $MAX_RESULTS" }
        require(pageToken.length <= MAX_PAGE_TOKEN && pageToken.none(Char::isISOControl)) {
            "Invalid Drive page token"
        }
        val query = buildQuery(nameContains, parentId)
        val url = buildString {
            append("$API_ROOT/files?q=").append(enc(query))
            append("&pageSize=").append(pageSize)
            append("&orderBy=name")
            append("&fields=").append(enc(LIST_FIELDS))
            if (pageToken.isNotEmpty()) append("&pageToken=").append(enc(pageToken))
            append("&spaces=drive")
        }
        val json = request(url, accessToken)
        val items = json.optJSONArray("files") ?: JSONArray()
        val files = parseFiles(items, pageSize)
        val next = json.optString("nextPageToken").takeIf {
            it.isNotBlank() && it.length <= MAX_PAGE_TOKEN && it.none(Char::isISOControl)
        }.orEmpty()
        return FilePage(files, next)
    }

    /** Fetch metadata for one explicitly selected file; this does not fetch its content. */
    fun getFile(accessToken: String, fileId: String): DriveFile {
        validateToken(accessToken)
        require(isSafeId(fileId)) { "Invalid Drive file id" }
        val url = "$API_ROOT/files/${enc(fileId)}?fields=${enc(FILE_FIELDS)}"
        return parseFile(request(url, accessToken))
    }

    internal fun buildQuery(nameContains: String, parentId: String?): String {
        require(nameContains.length <= MAX_NAME_QUERY &&
            nameContains.none { it.isISOControl() }) { "Invalid Drive name search" }
        if (parentId != null) require(isSafeId(parentId)) { "Invalid Drive parent id" }
        return buildList {
            add("trashed = false")
            if (nameContains.isNotBlank()) {
                val escaped = nameContains.replace("\\", "\\\\").replace("'", "\\'")
                add("name contains '$escaped'")
            }
            if (parentId != null) add("'$parentId' in parents")
        }.joinToString(" and ")
    }

    internal fun parseFiles(items: JSONArray, pageSize: Int): List<DriveFile> {
        require(pageSize in 1..MAX_RESULTS)
        require(items.length() <= pageSize) { "Drive returned more files than requested" }
        return (0 until items.length()).map { index ->
            val item = items.optJSONObject(index)
                ?: throw IllegalArgumentException("Drive page contains a malformed file item")
            parseFile(item)
        }
    }

    internal fun parseFile(json: JSONObject): DriveFile {
        val id = json.optString("id")
        require(isSafeId(id)) { "Drive file has invalid id" }
        val parents = json.optJSONArray("parents") ?: JSONArray()
        val parentIds = (0 until parents.length()).map { index ->
            val value = parents.optString(index)
            require(isSafeId(value)) { "Drive file has invalid parent id" }
            value
        }.take(100)
        val owners = json.optJSONArray("owners") ?: JSONArray()
        val ownerNames = (0 until minOf(owners.length(), 20)).mapNotNull { index ->
            owners.optJSONObject(index)?.optString("displayName")
                ?.take(MAX_TEXT)?.takeIf(String::isNotBlank)
        }
        val shortcut = json.optJSONObject("shortcutDetails") ?: JSONObject()
        val size = json.optString("size").toLongOrNull()?.takeIf { it >= 0 }
        val link = json.optString("webViewLink").takeIf(::isSafeDriveLink).orEmpty()
        return DriveFile(
            id = id,
            name = json.optString("name").take(MAX_TEXT),
            mimeType = json.optString("mimeType").take(256),
            sizeBytes = size,
            modifiedTime = json.optString("modifiedTime").take(64),
            webViewLink = link,
            parentIds = parentIds,
            ownerNames = ownerNames,
            isTrashed = json.optBoolean("trashed", false),
            shortcutTargetId = shortcut.optString("targetId").takeIf(::isSafeId).orEmpty(),
            shortcutTargetMimeType = shortcut.optString("targetMimeType").take(256)
        )
    }

    private fun request(url: String, token: String): JSONObject {
        require(url.startsWith("$API_ROOT/")) { "Only the Google Drive API host is allowed" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 10000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Google Drive API returned HTTP $status")
            val declared = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (declared != null && declared > MAX_RESPONSE_BYTES) {
                throw IOException("Drive response too large")
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (count > MAX_RESPONSE_BYTES - total) throw IOException("Drive response too large")
                    output.write(buffer, 0, count)
                    total += count
                }
                output.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun validateToken(token: String) {
        require(token.isNotBlank() && token.length <= 8192 &&
            token.none { it.isWhitespace() || it.isISOControl() }) {
            "A temporary Google access token is required"
        }
    }

    private fun isSafeId(value: String): Boolean =
        value.length in 1..MAX_FILE_ID && value.all {
            it.isLetterOrDigit() || it == '-' || it == '_'
        }

    private fun isSafeDriveLink(value: String): Boolean {
        if (value.length !in 1..2048) return false
        return try {
            val uri = java.net.URI(value)
            uri.scheme == "https" && uri.host in setOf("drive.google.com", "docs.google.com") &&
                uri.userInfo == null
        } catch (_: Exception) { false }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
