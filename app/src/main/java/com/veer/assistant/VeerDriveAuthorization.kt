package com.veer.assistant

import android.accounts.Account
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope

/**
 * Google Drive OAuth policy.
 *
 * Metadata access is the initial grant. Full read access is requested only
 * after the user explicitly opens a selected file. No write scopes are used.
 * Tokens are returned to the caller and must remain ephemeral.
 */
object VeerDriveAuthorization {
    const val DRIVE_METADATA_READONLY_SCOPE =
        "https://www.googleapis.com/auth/drive.metadata.readonly"
    const val DRIVE_READONLY_SCOPE =
        "https://www.googleapis.com/auth/drive.readonly"
    const val EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
    const val OPENID_SCOPE = "openid"

    fun metadataScopeUris(): List<String> = listOf(
        DRIVE_METADATA_READONLY_SCOPE,
        EMAIL_SCOPE,
        OPENID_SCOPE
    )

    /** Request content access only after an explicit user action on a selected file. */
    fun contentScopeUris(): List<String> = listOf(DRIVE_READONLY_SCOPE)

    fun metadataAuthorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(metadataScopeUris().map(::Scope))
            .build()

    fun contentAuthorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(contentScopeUris().map(::Scope))
            .build()

    /** Revoke every Drive scope VEER may have requested for this verified account. */
    fun revokeRequest(verifiedEmail: String): RevokeAccessRequest {
        val email = verifiedEmail.trim()
        require(isValidVerifiedEmailShape(email)) {
            "A verified Google account email is required to disconnect Drive"
        }
        val allScopes = (metadataScopeUris() + contentScopeUris()).distinct().map(::Scope)
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
