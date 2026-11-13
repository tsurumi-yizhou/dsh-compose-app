package dev.fughetta.dshcompose.domain.notify

import org.junit.Assert.*
import org.junit.Test

class NotificationPrivacyTest {
    @Test
    fun masksAssignmentValues() {
        val masked = NotificationPrivacy.mask("api_key: sk-1234567890abcdef")
        assertFalse(masked.contains("sk-1234567890abcdef"))
        assertTrue(masked.contains("api_key"))
    }

    @Test
    fun masksBearerTokens() {
        val masked = NotificationPrivacy.mask("Authorization: Bearer abc.def.ghi")
        assertFalse(masked.contains("abc.def.ghi"))
    }

    @Test
    fun snippetKeepsShortSafeText() {
        assertEquals("done", NotificationPrivacy.snippet("done"))
        assertNull(NotificationPrivacy.snippet(null))
    }

    @Test
    fun exactRefusesLongOrSecretBearingText() {
        assertEquals("tool call", NotificationPrivacy.exact("tool call"))
        assertNull(NotificationPrivacy.exact("x".repeat(3073)))
        assertNull(NotificationPrivacy.exact("password=hunter2"))
    }
}
