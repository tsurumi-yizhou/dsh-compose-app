package dev.fughetta.dshcompose.sync

import android.app.Application
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SessionSyncWorkTest {
    @Test
    fun requestNowKeepsOneConstrainedJobPerHost() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        SessionSyncWork.requestNow(context, "local")
        SessionSyncWork.requestNow(context, "local")

        val infos =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(SessionSyncWork.name("local"))
                .get()

        assertEquals(1, infos.size)
        val info = infos.single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals(
            "local",
            info.tags.firstOrNull { it.startsWith("host:") }?.removePrefix("host:"),
        )
    }

    @Test
    fun periodicSyncKeepsOneJobPerHost() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        SessionSyncWork.ensurePeriodic(context, "local")
        SessionSyncWork.ensurePeriodic(context, "local")

        val infos =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(SessionSyncWork.periodicName("local"))
                .get()

        assertEquals(1, infos.size)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
        assertEquals(
            TimeUnit.MINUTES.toMillis(15),
            infos.single().periodicityInfo?.repeatIntervalMillis,
        )
    }

    @Test
    fun removingAHostCancelsItsJobsAndLeavesOtherHostsAlone() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        SessionSyncWork.ensurePeriodic(context, "local")
        SessionSyncWork.requestNow(context, "local")
        SessionSyncWork.ensurePeriodic(context, "ssh-1")
        SessionSyncWork.requestNow(context, "ssh-1")

        SessionSyncWork.cancel(context, "ssh-1")

        val periodic =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(SessionSyncWork.periodicName("ssh-1"))
                .get()
        val oneshot =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(SessionSyncWork.name("ssh-1"))
                .get()
        assertTrue(periodic.all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(oneshot.all { it.state == WorkInfo.State.CANCELLED })

        assertEquals(
            1,
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(SessionSyncWork.periodicName("local"))
                .get()
                .count { it.state == WorkInfo.State.ENQUEUED },
        )
        assertEquals(
            1,
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(SessionSyncWork.name("local"))
                .get()
                .count { it.state == WorkInfo.State.ENQUEUED },
        )
    }
}
