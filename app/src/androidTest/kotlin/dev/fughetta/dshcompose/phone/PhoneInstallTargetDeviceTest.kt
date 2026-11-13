package dev.fughetta.dshcompose.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.data.settings.localPreferences
import dev.fughetta.dshcompose.i18n.AppStrings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneInstallTargetDeviceTest {
    @Test
    fun deviceDiscoveryAndInstallAdmissionUseTheSameNativeTarget() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val control = PhoneControl.get(app)
        val device = control.device()
        assertEquals(device.id, app.localPreferences("dsh_phone").getString("deviceId", null))
        val core =
            PhoneCore(
                control.environment.copy(enabled = { true }),
                PhoneTasks(),
                ActionLog(),
            )
        try {
            val discovery =
                Json.parse(core.call(PhoneToolCatalog.DEVICES, emptyMap()).text) as Map<*, *>
            assertEquals(listOf(device.toMap()), discovery["devices"])
            assertFalse(core.tasks.isActive)
            val path = mapOf("path" to "/root/app.apk")
            assertTrue(
                core
                    .call(PhoneToolCatalog.INSTALL_APK, path)
                    .text
                    .startsWith("INSTALL_DEVICE_REQUIRED"),
            )
            assertTrue(
                core
                    .call(PhoneToolCatalog.INSTALL_APK, path + ("deviceId" to "another-phone"))
                    .text
                    .startsWith("INSTALL_DEVICE_MISMATCH"),
            )
            assertFalse(core.tasks.isActive)
            val requests = ApkInstallRequests(app, core.tasks, control.prompts, { false }, device)
            val rejected =
                core.worker.run(1000, 1000) { scope ->
                    requests.install("/root/app.apk", "another-phone", scope)
                } as SerialWorker.Outcome.Done<ToolOutput>
            assertEquals("INSTALL_DEVICE_MISMATCH", rejected.value.text)
            assertTrue(rejected.value.isError)
            assertFalse(ApkInstallGate.active)
            for (message in listOf(R.string.phone_install_new, R.string.phone_install_update)) {
                val text =
                    AppStrings.get(
                        app,
                        message,
                        "Example",
                        "example.app",
                        "1.0",
                        1L,
                        device.name,
                        device.id,
                    )
                assertTrue(text.contains(device.name))
                assertTrue(text.contains(device.id))
            }
        } finally {
            core.worker.shutdown()
        }
    }
}
