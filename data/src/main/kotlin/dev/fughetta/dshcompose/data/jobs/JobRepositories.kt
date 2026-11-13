package dev.fughetta.dshcompose.data.jobs

import android.content.Context
import android.net.Uri
import dev.fughetta.dshcompose.domain.jobs.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*

fun createJobsRepository(
    context: Context,
    applicationScope: CoroutineScope,
): EngineJobsRepository {
    val application = context.applicationContext
    return EngineJobsRepository(
        JobOutputFilesDataSource(File(application.cacheDir, "artifacts/task-output")) { destination
            ->
            application.contentResolver.openOutputStream(Uri.parse(destination))
                ?: throw IOException("jobs/save-failed")
        },
        applicationScope,
    )
}
