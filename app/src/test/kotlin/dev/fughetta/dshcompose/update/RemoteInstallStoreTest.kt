package dev.fughetta.dshcompose.update

import android.content.Context
import android.content.pm.PackageInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(manifest = Config.NONE, sdk = [28])
class RemoteInstallStoreTest {
    @Test
    fun selfReplacementRetainsTheHostAndReportsSuccessWithoutReinstalling() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("compose_self_update", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val current =
            PackageInfo().apply {
                packageName = app.packageName
                setLongVersionCode(12)
                lastUpdateTime = 200
            }
        shadowOf(app.packageManager).installPackage(current)
        val first = RemoteInstallStore(app)
        first.select("compose-host")
        first.write(
            RemoteInstallRecord(
                "request",
                "compose-host",
                "awaiting-install",
                installerSession = 3,
                version = 12,
                previousUpdate = 100,
            ),
        )
        val reopened = RemoteInstallStore(app)
        recoverInstallation(app, reopened)
        val receipt = reopened.read()!!
        assertEquals("installed", receipt.status)
        assertEquals("compose-host", receipt.host)
        assertEquals("compose-host", reopened.selectedHost())
        reopened.acknowledge(receipt)
        assertNull(RemoteInstallStore(app).read())
    }
}
