package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.account.AccountSignInPhase
import dev.fughetta.dshcompose.domain.account.AccountWallet
import dev.fughetta.dshcompose.domain.account.accountCallbackOrigin
import dev.fughetta.dshcompose.domain.account.formatAccountWallet
import dev.fughetta.dshcompose.domain.account.sumAccountWallets
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DeepSeekAccountTest {
    @Test
    fun callbackStaysOnThePhoneAndStoredGrantDoesNotInventAnIdentity() {
        assertEquals("http://127.0.0.1:8080", accountCallbackOrigin("http://127.0.0.1:8080/path"))
        assertEquals("http://[::1]:8080", accountCallbackOrigin("http://[::1]:8080"))
        assertNull(accountCallbackOrigin("https://example.com:8080"))
        assertNull(accountCallbackOrigin("http://192.168.1.2:8080"))
        val view =
            parseAccountView(
                Json.parseToJsonElement(
                    """{"status":"credential-stored","attempt":{"id":"attempt","phase":"succeeded"}}""",
                ),
            )
        assertTrue(view.signedIn)
        assertEquals(AccountSignInPhase.Succeeded, view.attempt?.phase)
        assertNull(view.attempt?.authorizeUrl)
    }

    @Test
    fun walletDisplayAndTotalsKeepDecimalsExactAcrossWalletKinds() {
        assertEquals("0.00", formatAccountWallet("0E-16"))
        assertEquals("<0.01", formatAccountWallet("0.000000000000000001"))
        assertEquals("-0.01", formatAccountWallet("-0.000000000000000001"))
        assertEquals("1,234.56", formatAccountWallet("1234.569999999999999999"))
        assertEquals("-1,234.57", formatAccountWallet("-1234.565"))
        assertEquals(
            "0.3",
            sumAccountWallets(
                listOf(
                    AccountWallet("CNY", "0.1"),
                    AccountWallet("CNY", "0.2"),
                    AccountWallet("USD", "99"),
                ),
                "CNY",
            ),
        )
    }
}
