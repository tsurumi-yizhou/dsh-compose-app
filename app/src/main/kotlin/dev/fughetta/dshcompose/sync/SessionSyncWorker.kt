package dev.fughetta.dshcompose.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.fughetta.dshcompose.ClientApplication
import java.util.concurrent.TimeUnit

/** Refreshes a host's mirrored directory using the process-owned endpoint registry. */
class SessionSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = inputData.getString(HOST) ?: return Result.failure()
        val app = applicationContext as ClientApplication
        return when (app.hostsRepository.syncHost(host)) {
            dev.fughetta.dshcompose.domain.sessions.HostSyncOutcome.Completed -> Result.success()
            dev.fughetta.dshcompose.domain.sessions.HostSyncOutcome.Retryable ->
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            dev.fughetta.dshcompose.domain.sessions.HostSyncOutcome.Unavailable -> Result.failure()
        }
    }

    companion object {
        const val HOST = "host"
        private const val MAX_ATTEMPTS = 3
    }
}

/** Schedules the background directory refresh; one unique job per host. */
object SessionSyncWork {
    private const val INTERVAL_MINUTES = 15L

    /** Keeps a connected host's mirror fresh while the app stays backgrounded. */
    fun ensurePeriodic(context: Context, host: String) {
        val request =
            PeriodicWorkRequestBuilder<SessionSyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setInputData(workDataOf(SessionSyncWorker.HOST to host))
                .addTag("host:$host")
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(periodicName(host), ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Host is gone: drop both the periodic and the one-shot jobs that still name it. */
    fun cancel(context: Context, host: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag("host:$host")
    }

    fun periodicName(host: String): String = "session-sync-periodic-$host"

    fun requestNow(context: Context, host: String) {
        val request =
            OneTimeWorkRequestBuilder<SessionSyncWorker>()
                .setInputData(workDataOf(SessionSyncWorker.HOST to host))
                .addTag("host:$host")
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(name(host), ExistingWorkPolicy.KEEP, request)
    }

    fun name(host: String): String = "session-sync-$host"
}
