package dev.fughetta.dshcompose.runtime

import dev.fughetta.dshcompose.engine.runtime.*
import dev.fughetta.dshcompose.ui.runtime.BackgroundNetReason
import org.junit.Assert.*
import org.junit.Test

class BackgroundNetPolicyTest {
    private val none = emptyMap<BackgroundNetReason, Int>()

    @Test
    fun aReturnNamesABlockSinceLeavingOverFailuresAndOnlyWhileNotExempt() {
        assertEquals(
            BackgroundNetReason.Blocked,
            BackgroundNetPolicy.onReturn(100, false, none, blockedAt = 100, transportFailed = true),
        )
        assertEquals(
            BackgroundNetReason.Failures,
            BackgroundNetPolicy.onReturn(100, false, none, blockedAt = 99, transportFailed = true),
        )
        assertNull(BackgroundNetPolicy.onReturn(100, false, none, blockedAt = 99))
        assertNull(BackgroundNetPolicy.onReturn(100, true, none, blockedAt = 100))
        assertNull(BackgroundNetPolicy.onReturn(100, null, none, blockedAt = 100))
        assertNull(
            BackgroundNetPolicy.onReturn(
                100,
                false,
                mapOf(BackgroundNetReason.Blocked to BackgroundNetPolicy.MAX_SHOWS),
                blockedAt = 100,
            ),
        )
    }

    @Test
    fun theIntroComesOnceOnStrictVendorsBeforeAnyOtherLine() {
        assertTrue(BackgroundNetPolicy.introDue(false, VendorSettings.VIVO, false, none))
        assertFalse(BackgroundNetPolicy.introDue(true, VendorSettings.VIVO, false, none))
        assertFalse(BackgroundNetPolicy.introDue(false, VendorSettings.SAMSUNG, false, none))
        assertFalse(BackgroundNetPolicy.introDue(false, null, false, none))
        assertFalse(BackgroundNetPolicy.introDue(false, VendorSettings.VIVO, null, none))
        assertFalse(
            BackgroundNetPolicy.introDue(
                false,
                VendorSettings.VIVO,
                false,
                mapOf(BackgroundNetReason.Failures to 1),
            ),
        )
    }
}
