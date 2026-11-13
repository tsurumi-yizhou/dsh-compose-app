package dev.fughetta.dshcompose.domain.hosts

import org.junit.Assert.*
import org.junit.Test

class HostServiceTest {
    @Test
    fun serviceAddressesAcceptTailnetNamesAndKeepCredentialsSeparate() {
        assertEquals("http://desktop:3095", hostServiceUrl("desktop"))
        assertEquals(
            "http://desktop.tail123.ts.net:3095",
            hostServiceUrl("desktop.tail123.ts.net"),
        )
        assertEquals("http://100.100.1.2:4000", hostServiceUrl("100.100.1.2:4000"))
        assertEquals("https://example.com:3443", hostServiceUrl(" https://example.com:3443/ "))
        assertEquals("http://127.0.0.1:3080", hostServiceUrl("http://127.0.0.1:3080"))
        assertEquals("http://[::1]:3080", hostServiceUrl("http://[::1]:3080"))
        for (address in
            listOf(
                "http://example.com",
                "http://127.0.0.1.example.com",
                "https://user:password@example.com",
                "https://example.com?token=secret",
                "https://example.com/../other",
            )) {
            assertNull(address, hostServiceUrl(address))
        }
        assertFalse(
            HostPairing("https://example.com", "private-pairing-code")
                .toString()
                .contains("private-pairing-code"),
        )
    }
}
