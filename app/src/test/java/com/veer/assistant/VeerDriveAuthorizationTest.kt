package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerDriveAuthorizationTest {
    @Test fun metadataRequestUsesMetadataOnlyAndIdentityScopes() {
        assertEquals(
            listOf(
                "https://www.googleapis.com/auth/drive.metadata.readonly",
                "https://www.googleapis.com/auth/userinfo.email",
                "openid"
            ),
            VeerDriveAuthorization.metadataScopeUris()
        )
        assertFalse(VeerDriveAuthorization.metadataScopeUris()
            .contains(VeerDriveAuthorization.DRIVE_READONLY_SCOPE))
    }

    @Test fun contentPermissionIsSeparateAndReadOnly() {
        assertEquals(
            listOf("https://www.googleapis.com/auth/drive.readonly"),
            VeerDriveAuthorization.contentScopeUris()
        )
        assertFalse(VeerDriveAuthorization.contentScopeUris()
            .contains("https://www.googleapis.com/auth/drive"))
    }

    @Test fun revokeScopeSetCoversAllDriveGrantsWithoutDuplicates() {
        val all = (VeerDriveAuthorization.metadataScopeUris() +
            VeerDriveAuthorization.contentScopeUris()).distinct()
        assertEquals(4, all.size)
        assertTrue(all.contains(VeerDriveAuthorization.DRIVE_METADATA_READONLY_SCOPE))
        assertTrue(all.contains(VeerDriveAuthorization.DRIVE_READONLY_SCOPE))
    }

    @Test fun validatesVerifiedAccountEmailShapeBeforeRevocation() {
        assertTrue(VeerDriveAuthorization.isValidVerifiedEmailShape("person@example.com"))
        assertFalse(VeerDriveAuthorization.isValidVerifiedEmailShape(""))
        assertFalse(VeerDriveAuthorization.isValidVerifiedEmailShape("not-an-email"))
        assertFalse(VeerDriveAuthorization.isValidVerifiedEmailShape("a@@example.com"))
        assertFalse(VeerDriveAuthorization.isValidVerifiedEmailShape("a b@example.com"))
        assertFalse(VeerDriveAuthorization.isValidVerifiedEmailShape("person@example.com\n"))
    }
}
