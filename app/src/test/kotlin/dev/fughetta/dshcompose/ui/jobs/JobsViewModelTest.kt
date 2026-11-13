package dev.fughetta.dshcompose.ui.jobs

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fughetta.dshcompose.domain.jobs.*
import dev.fughetta.dshcompose.ui.AppAction
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JobsViewModelTest {
    private val job = JobInfo("job1", "bash", "build", "running", null, null, 1, null, 0)

    private class Repository(val job: JobInfo) : JobsRepository {
        override suspend fun share(lines: List<String>): String = error("unused")

        override suspend fun save(destination: String, lines: List<String>) = error("unused")

        val frames = MutableSharedFlow<JobOutput>()
        var stopCount = 0
        var reply = CompletableDeferred<Boolean>()

        override val connection = MutableStateFlow<JobsRepository?>(this)
        override val active = true
        override val canStop = true

        override fun watch(sessionId: String) = flowOf(JobRoster(listOf(job), loading = false))

        override fun output(sessionId: String, job: JobInfo): Flow<JobOutput> = frames

        override suspend fun stop(sessionId: String, jobId: String): Boolean {
            stopCount++
            return withContext(NonCancellable) { reply.await() }
        }
    }

    private class Files : JobsRepository {
        override val connection = MutableStateFlow<JobsRepository?>(null)
        override val active = false
        override val canStop = false

        override fun watch(sessionId: String): Flow<JobRoster> = error("unused")

        override fun output(sessionId: String, job: JobInfo): Flow<JobOutput> = error("unused")

        override suspend fun stop(sessionId: String, jobId: String): Boolean = error("unused")

        val saved = mutableListOf<Pair<String, List<String>>>()

        var shareGate: CompletableDeferred<Unit>? = null

        override suspend fun share(lines: List<String>): String {
            shareGate?.let { withContext(NonCancellable) { it.await() } }
            return "task-output.txt"
        }

        override suspend fun save(destination: String, lines: List<String>) {
            saved += destination to lines.toList()
        }
    }

    @Test
    fun stoppingRequiresConfirmationAndAnAcknowledgementDoesNotMarkTheJobEnded() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val repository = Repository(job)
        try {
            val provider =
                ViewModelProvider(
                    store,
                    viewModelFactory {
                        initializer { JobsViewModel(repository.connection, Files()) }
                    },
                )
            val model = provider[JobsViewModel::class.java]
            runCurrent()
            model.handle(AppAction.OpenJobs("s1"))
            model.handle(AppAction.ObserveJobs(true))
            runCurrent()
            model.handle(AppAction.OpenJobOutput("job1"))
            runCurrent()
            repository.frames.emit(JobOutput(job, loading = false))
            runCurrent()
            model.handle(AppAction.RequestJobStop)
            assertTrue(model.uiState.value!!.output!!.confirmStop)
            assertEquals(0, repository.stopCount)
            model.handle(AppAction.CancelJobStop)
            assertFalse(model.uiState.value!!.output!!.confirmStop)
            model.handle(AppAction.RequestJobStop)
            model.handle(AppAction.ConfirmJobStop)
            runCurrent()
            assertEquals(1, repository.stopCount)
            assertTrue(model.uiState.value!!.output!!.stopping)
            repository.reply.complete(true)
            runCurrent()
            assertTrue(model.uiState.value!!.output!!.stopRequested)
            assertEquals("running", model.uiState.value!!.output!!.job.status)
            repository.frames.emit(JobOutput(job.copy(status = "terminated"), loading = false))
            advanceTimeBy(33)
            runCurrent()
            assertFalse(model.uiState.value!!.output!!.stopRequested)
            assertFalse(model.uiState.value!!.output!!.job.live)
            model.handle(AppAction.CloseJobOutput)
            assertNull(model.uiState.value!!.output)
            assertSame(model, provider[JobsViewModel::class.java])
            model.handle(AppAction.CloseJobs)
            assertNull(model.uiState.value)
        } finally {
            repository.reply.complete(false)
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun documentSelectionSurvivesRecreationAndSavesTheSnapshotChosenBeforeLaterOutput() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val repository = Repository(job)
        val files = Files()
        try {
            val provider =
                ViewModelProvider(
                    store,
                    viewModelFactory {
                        initializer { JobsViewModel(repository.connection, files) }
                    },
                )
            val model = provider[JobsViewModel::class.java]
            runCurrent()
            model.open("s1")
            model.observe(true)
            runCurrent()
            model.openOutput("job1")
            runCurrent()
            repository.frames.emit(JobOutput(job, listOf("before"), loading = false))
            runCurrent()
            model.requestSave()
            val request = model.uiState.value!!.export.saveRequest!!
            assertTrue(model.savePickerOpened(request.id))
            assertFalse(model.savePickerOpened(request.id))
            model.observe(false)
            assertSame(model, provider[JobsViewModel::class.java])
            model.observe(true)
            runCurrent()
            repository.frames.emit(JobOutput(job, listOf("later"), loading = false))
            runCurrent()
            model.saveDocument("content://documents/chosen")
            runCurrent()
            assertEquals(listOf("content://documents/chosen" to listOf("before")), files.saved)
            assertFalse(model.uiState.value!!.export.saving)
            model.shareOutput()
            runCurrent()
            val share = model.consumeShare()!!
            assertEquals(File("task-output.txt"), share.file)
            assertNull(model.consumeShare())
            model.shareFailed(share.id)
            assertTrue(model.uiState.value!!.export.shareFailed)
            model.requestSave()
            model.savePickerOpened(model.uiState.value!!.export.saveRequest!!.id)
            model.closeDialog()
            model.saveDocument("content://documents/after-close")
            runCurrent()
            assertEquals("content://documents/after-close" to listOf("later"), files.saved.last())
            assertNull(model.uiState.value)
        } finally {
            repository.reply.complete(false)
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aLateShareResultCannotOpenTheSystemChooserAfterTheOutputWasClosed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val repository = Repository(job)
        val files = Files().apply { shareGate = CompletableDeferred() }
        try {
            val model =
                ViewModelProvider(
                    store,
                    viewModelFactory {
                        initializer { JobsViewModel(repository.connection, files) }
                    },
                )[JobsViewModel::class.java]
            runCurrent()
            model.open("s1")
            model.observe(true)
            runCurrent()
            model.openOutput("job1")
            runCurrent()
            repository.frames.emit(JobOutput(job, listOf("snapshot"), loading = false))
            runCurrent()
            model.shareOutput()
            runCurrent()
            assertTrue(model.uiState.value!!.export.sharing)
            model.closeOutput()
            model.openOutput("job1")
            files.shareGate!!.complete(Unit)
            runCurrent()
            assertNull(model.consumeShare())
            assertFalse(model.uiState.value!!.export.sharing)
        } finally {
            files.shareGate!!.complete(Unit)
            repository.reply.complete(false)
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
