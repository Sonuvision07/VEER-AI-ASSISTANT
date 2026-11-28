package com.veer.assistant

import org.json.JSONArray
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Builds a bounded Markdown research report from the structured synthesis response. */
object VeerResearchReport {
    private const val MAX_QUERY = 500
    private const val MAX_ANSWER = 12_000
    private const val MAX_SOURCES = 5
    private const val MAX_TITLE = 300
    private const val MAX_URL = 2_048
    private const val MAX_OUTPUT = 24 * 1024
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
        .withZone(ZoneId.of("UTC"))

    fun format(query: String, answer: String, sources: JSONArray, generatedAt: Instant = Instant.now()): String {
        require(query.isNotBlank() && query.length <= MAX_QUERY) { "Invalid research query" }
        require(answer.isNotBlank() && answer.length <= MAX_ANSWER) { "Invalid research answer" }
        require(sources.length() <= MAX_SOURCES) { "Too many research sources" }

        val report = StringBuilder()
            .append("# VEER Research Report\n\n")
            .append("**Question:** ").append(query.trim().replace("\n", " ")).append("\n")
            .append("**Generated:** ").append(timestampFormat.format(generatedAt)).append("\n\n")
            .append("## Summary\n\n").append(answer.trim()).append("\n")

        val validSources = (0 until sources.length()).mapNotNull { index ->
            val item = sources.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title").trim().take(MAX_TITLE)
            val url = safeHttpUrl(item.optString("url")) ?: return@mapNotNull null
            val id = item.optString("id").take(8).filter { it.isLetterOrDigit() }
            if (title.isBlank()) null else Triple(id, title, url)
        }.take(MAX_SOURCES)

        if (validSources.isNotEmpty()) {
            report.append("\n## Sources\n\n")
            validSources.forEachIndexed { index, source ->
                val label = source.first.ifBlank { "S${index + 1}" }
                report.append("- [").append(label).append("] ")
                    .append(source.second.replace("[", "\\[").replace("]", "\\]"))
                    .append(" — ").append(source.third).append("\n")
            }
        }
        val result = report.toString()
        if (result.toByteArray(Charsets.UTF_8).size > MAX_OUTPUT) {
            throw IllegalArgumentException("Research report is too large to export")
        }
        return result
    }

    private fun safeHttpUrl(raw: String): String? {
        return try {
            val uri = URI(raw.trim())
            val scheme = uri.scheme?.lowercase()
            if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null || uri.rawFragment != null) null
            else uri.toASCIIString().take(MAX_URL).takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }
}
