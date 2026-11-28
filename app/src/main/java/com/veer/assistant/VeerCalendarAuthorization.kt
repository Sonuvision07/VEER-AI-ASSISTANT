package com.veer.assistant

import android.accounts.Account
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope

/**
 * Google Calendar authorization policy.
 *
 * Read access is requested first. Event-write access is a separate incremental
 * request and must only be invoked after the user starts a write operation.
 * Access tokens are returned by Google Play Services to the caller and are
 * deliberately not persisted or logged here.
 */
object VeerCalendarAuthorization {
    const val CALENDAR_READONLY_SCOPE =
        "https://www.googleapis.com/auth/calendar.events.readonly"
    const val CALENDAR_EVENTS_SCOPE =
        "https://www.googleapis.com/auth/calendar.events"
    const val EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
    const val OPENID_SCOPE = "openid"

    /** Stable URI list, exposed separately so the scope policy can be unit tested. */
    fun readScopeUris(): List<String> = listOf(
        CALENDAR_READONLY_SCOPE,
        EMAIL_SCOPE,
        OPENID_SCOPE
    )

    /** Request write access only after a user-initiated create/update action. */
    fun writeScopeUris(): List<String> = listOf(CALENDAR_EVENTS_SCOPE)

    fun readAuthorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(readScopeUris().map(::Scope))
            .build()

    fun writeAuthorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(writeScopeUris().map(::Scope))
            .build()

    /** Revoke all Calendar scopes granted by VEER for this verified account. */
    fun revokeRequest(verifiedEmail: String): RevokeAccessRequest {
        val email = verifiedEmail.trim()
        require(isValidVerifiedEmailShape(email)) {
            "A verified Google account email is required to disconnect Calendar"
        }
        val allScopes = (readScopeUris() + writeScopeUris()).distinct().map(::Scope)
        return RevokeAccessRequest.builder()
            .setAccount(Account(email, "com.google"))
            .setScopes(allScopes)
            .build()
    }

    internal fun isValidVerifiedEmailShape(email: String): Boolean {
        if (email.isBlank() || email.length > 320 || email.any {
                it.isWhitespace() || it.isISOControl()
            }) return false
        val at = email.indexOf('@')
        return at > 0 && at == email.lastIndexOf('@') &&
            at < email.lastIndex && email.substring(at + 1).contains('.')
    }
}
