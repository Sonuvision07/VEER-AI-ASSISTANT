package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Android HTTPS client for VEER Phase 5 web search and source-grounded synthesis. */
class VeerResearchClient(baseUrl: String, token: String) {
    private val endpoint = normalizeBaseUrl(baseUrl)
    private val bearer = validateToken(token)

    fun search(query: String, maxResults: Int = 5): JSONArray {
        require(query.isNotBlank() && query.length <= MAX_QUERY) { "Invalid research query" }
        require(maxResults in 1..5) { "Result count must be between 1 and 5" }
        val response = request("/api/research/search", JSONObject().put("query",query.trim()).put("max_results",maxResults))
        if (!response.optBoolean("ok",false)) throw IOException(response.optString("error").ifBlank { "Search failed" })
        return response.optJSONArray("results") ?: throw IOException("Search response missing results")
    }

    /** Sources must contain title, url and snippet fields. */
    fun synthesize(query: String, sources: JSONArray): JSONObject {
        require(query.isNotBlank() && query.length <= MAX_QUERY) { "Invalid research query" }
        require(sources.length() in 1..5) { "Provide 1–5 sources" }
        val body = JSONObject().put("query",query.trim()).put("sources",sources)
        if (body.toString().toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) throw IOException("Research payload too large")
        val response = request("/api/research/synthesize",body)
        if (!response.optBoolean("ok",false)) throw IOException(response.optString("error").ifBlank { "Research synthesis failed" })
        val answer=response.optString("answer")
        val citations=response.optJSONArray("citations") ?: JSONArray()
        if (answer.isBlank()) throw IOException("Research response contained no answer")
        return JSONObject().put("query",response.optString("query",query)).put("answer",answer)
            .put("citations",citations).put("sources",response.optJSONArray("sources") ?: JSONArray())
    }

    private fun request(path: String, body: JSONObject): JSONObject {
        val bytes=body.toString().toByteArray(Charsets.UTF_8)
        if(bytes.size>MAX_REQUEST_BYTES) throw IOException("Research payload too large")
        val connection=(URL(endpoint+path).openConnection() as HttpURLConnection).apply {
            requestMethod="POST"; connectTimeout=CONNECT_TIMEOUT; readTimeout=READ_TIMEOUT
            instanceFollowRedirects=false; useCaches=false; doOutput=true
            setRequestProperty("Authorization","Bearer $bearer")
            setRequestProperty("Accept","application/json")
            setRequestProperty("Content-Type","application/json; charset=utf-8")
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            val status=connection.responseCode
            val stream=if(status in 200..299) connection.inputStream else connection.errorStream
            val raw=stream?.use { readBounded(it) } ?: ByteArray(0)
            val json=try { JSONObject(String(raw,Charsets.UTF_8)) } catch (_:Exception) { throw IOException("Research service returned invalid JSON") }
            if(status !in 200..299) throw IOException(json.optString("error").ifBlank { "Research service HTTP $status" })
            return json
        } finally { connection.disconnect() }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(4096);var total=0
        while(true){val n=input.read(buffer);if(n<0)break;total+=n
            if(total>MAX_RESPONSE_BYTES)throw IOException("Research response too large")
            out.write(buffer,0,n)}
        return out.toByteArray()
    }

    companion object {
        private const val CONNECT_TIMEOUT=5000
        private const val READ_TIMEOUT=15000
        private const val MAX_QUERY=500
        private const val MAX_REQUEST_BYTES=12*1024
        private const val MAX_RESPONSE_BYTES=128*1024
    }
}
