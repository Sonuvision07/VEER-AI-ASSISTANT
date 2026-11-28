package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerCalendarAuthorizationTest {
    @Test fun readRequestUsesOnlyReadOnlyCalendarScopeAndIdentityScopes() {
        assertEquals(
            listOf(
                "https://www.googleapis.com/auth/calendar.events.readonly",
                "https://www.googleapis.com/auth/userinfo.email",
                "openid"
            ),
            VeerCalendarAuthorization.readScopeUris()
        )
        assertFalse(VeerCalendarAuthorization.readScopeUris()
            .contains(VeerCalendarAuthorization.CALENDAR_EVENTS_SCOPE))
    }

    @Test fun writePermissionIsSeparateAndNarrow() {
        assertEquals(
            listOf("https://www.googleapis.com/auth/calendar.events"),
            VeerCalendarAuthorization.writeScopeUris()
        )
        assertFalse(VeerCalendarAuthorization.writeScopeUris()
            .contains(VeerCalendarAuthorization.CALENDAR_READONLY_SCOPE))
    }

    @Test fun revokeScopeSetCoversReadAndWriteGrantsWithoutDuplicates() {
        val all = (VeerCalendarAuthorization.readScopeUris() +
            VeerCalendarAuthorization.writeScopeUris()).distinct()
        assertEquals(4, all.size)
        assertTrue(all.contains(VeerCalendarAuthorization.CALENDAR_READONLY_SCOPE))
        assertTrue(all.contains(VeerCalendarAuthorization.CALENDAR_EVENTS_SCOPE))
    }

    @Test fun validatesVerifiedAccountEmailShapeBeforeRevocation() {
        assertTrue(VeerCalendarAuthorization.isValidVerifiedEmailShape("person@example.com"))
        assertFalse(VeerCalendarAuthorization.isValidVerifiedEmailShape(""))
        assertFalse(VeerCalendarAuthorization.isValidVerifiedEmailShape("not-an-email"))
        assertFalse(VeerCalendarAuthorization.isValidVerifiedEmailShape("a@@example.com"))
        assertFalse(VeerCalendarAuthorization.isValidVerifiedEmailShape("a b@example.com"))
    }
}
