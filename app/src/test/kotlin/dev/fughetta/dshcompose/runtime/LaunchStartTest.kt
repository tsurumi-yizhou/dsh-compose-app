package dev.fughetta.dshcompose.runtime

import dev.fughetta.dshcompose.engine.runtime.RuntimeController
import org.junit.Assert.*
import org.junit.Test

class LaunchStartTest {
    private val installed = RuntimeController.Phase.INSTALLED.wire

    @Test
    fun startsAnInstalledIdleRuntimeWhenTheSettingIsOn() {
        assertTrue(LaunchStart.shouldStart(true, installed, strikes = 0))
    }

    @Test
    fun leavesItWhenTheSettingIsOff() {
        assertFalse(LaunchStart.shouldStart(false, installed, strikes = 0))
    }

    @Test
    fun onlyFromTheInstalledPhase() {
        for (phase in RuntimeController.Phase.entries) {
            if (phase == RuntimeController.Phase.INSTALLED) continue
            assertFalse(phase.wire, LaunchStart.shouldStart(true, phase.wire, strikes = 0))
        }
        assertFalse(LaunchStart.shouldStart(true, null, strikes = 0))
    }

    @Test
    fun repeatedFailuresWaitForTheUser() {
        assertTrue(LaunchStart.shouldStart(true, installed, strikes = 1))
        assertFalse(LaunchStart.shouldStart(true, installed, strikes = 2))
        assertFalse(LaunchStart.shouldStart(true, installed, strikes = 5))
    }
}
