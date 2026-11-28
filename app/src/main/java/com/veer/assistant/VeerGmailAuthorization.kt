package com.veer.assistant

import android.accounts.Account
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Gmail authorization helpers. Access tokens are ephemeral and never persisted or logged. */
object VeerGmailAuthorization {
    const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    const val EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
    const val OPENID_SCOPE = "openid"
    private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"

    fun scopes(): List<Scope> = listOf(Scope(GMAIL_READONLY_SCOPE), Scope(EMAIL_SCOPE), Scope(OPENID_SCOPE))

    fun authorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(scopes()).build()

    fun revokeRequest(email: String): RevokeAccessRequest {
        val normalized = email.trim()
        require(normalized.length <= 320 && normalized.count { it == '@' } == 1 &&
            normalized.substringAfter('@').contains('.') && normalized.none { it.isWhitespace() || it.isISOControl() }) {
            "A verified Google account email is required to disconnect"
        }
        return RevokeAccessRequest.builder()
            .setAccount(Account(normalized, "com.google"))
            .setScopes(scopes())
            .build()
    }

    /** Verify account identity from Google's userinfo endpoint; never log or persist the token. */
    fun fetchEmail(accessToken: String): String {
        require(accessToken.isNotBlank() && accessToken.length <= 8192 &&
            accessToken.none { it.isWhitespace() || it.isISOControl() }) { "Invalid Google access token" }
        val connection = (URL(USERINFO_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 8000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Google account verification returned HTTP $status")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 8192) throw IOException("Google account response too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val profile = JSONObject(String(bytes, Charsets.UTF_8))
            val email = profile.optString("email").trim()
            if (!profile.optBoolean("email_verified", false) || !email.contains("@")) {
                throw IOException("Google did not return a verified account email")
            }
            return email
        } finally {
            connection.disconnect()
        }
    }
}
