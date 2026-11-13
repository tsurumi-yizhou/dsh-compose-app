package dev.fughetta.dshcompose.data.jobs

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.jobs.*
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.protocol.HostRemoteException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The connection owns subscriptions and stop requests; consumers own their observation lifetime.
 */
class EngineJobsRepository
private constructor(
    private val computation: CoroutineDispatcher,
    private val outputFiles: JobOutputFilesDataSource?,
    private val applicationScope: CoroutineScope?,
    private val io: CoroutineDispatcher,
) : JobsRepository, AutoCloseable {
    constructor(
        computation: CoroutineDispatcher = Dispatchers.Default,
    ) : this(computation, null, null, Dispatchers.IO)

    internal constructor(
        source: JobOutputFilesDataSource,
        applicationScope: CoroutineScope,
        io: CoroutineDispatcher = Dispatchers.IO,
    ) : this(Dispatchers.Default, source, applicationScope, io)

    override val active: Boolean
        get() = owner?.active == true

    override val canStop: Boolean
        get() = owner?.canStop == true

    private fun current(): JobsRepository =
        owner ?: throw CancellationException("Job connection unavailable")

    override fun watch(sessionId: String) = current().watch(sessionId)

    override fun output(sessionId: String, job: JobInfo) = current().output(sessionId, job)

    override suspend fun stop(sessionId: String, jobId: String) = current().stop(sessionId, jobId)

    private val shares = Mutex()

    override suspend fun share(lines: List<String>): String = shares.withLock {
        export("jobs/share-failed") { checkNotNull(outputFiles).share(lines).absolutePath }
    }

    override suspend fun save(destination: String, lines: List<String>) {
        checkNotNull(applicationScope)
            .async(io) {
                export("jobs/save-failed") { checkNotNull(outputFiles).save(destination, lines) }
            }
            .await()
    }

    private suspend fun <T> export(code: String, operation: () -> T): T =
        withContext(io) {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw JobOutputExportException(code, error)
            }
        }

    private val mutable = MutableStateFlow<JobsRepository?>(null)
    override val connection = mutable.asStateFlow()
    private var owner: ConnectedRepository? = null
    private var client: ProtocolClient? = null
    private var muxGeneration: ConnectionGeneration? = null

    fun connect(client: ProtocolClient?, parent: CoroutineScope) {
        val generation = client?.mux?.state?.value?.generation
        if (this.client === client && this.muxGeneration == generation && owner?.active == true)
            return
        this.client = client
        this.muxGeneration = generation
        owner?.close()
        owner =
            client
                ?.takeIf {
                    generation != null && it.mux.state.value.state == ConnectionState.Connected
                }
                ?.let { ConnectedRepository(it, parent) }
        mutable.value = owner
    }

    override fun close() {
        owner?.close()
        owner = null
        client = null
        muxGeneration = null
        mutable.value = null
    }

    private inner class ConnectedRepository(
        private val client: ProtocolClient,
        parent: CoroutineScope,
    ) : JobsRepository {
        override val connection = MutableStateFlow<JobsRepository?>(this).asStateFlow()

        override suspend fun share(lines: List<String>): String =
            this@EngineJobsRepository.share(lines)

        override suspend fun save(destination: String, lines: List<String>) =
            this@EngineJobsRepository.save(destination, lines)

        private val scope =
            CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        private val generation = client.mux.state.value.generation
        override val active
            get() =
                scope.isActive &&
                    generation != null &&
                    client.mux.state.value.generation == generation &&
                    client.mux.state.value.state == ConnectionState.Connected

        override val canStop
            get() = client.connection.supportsJobStop

        fun close() {
            scope.cancel()
        }

        private fun current() {
            if (!active) throw CancellationException("Job connection retired")
        }

        private fun failure(error: Throwable): Throwable =
            when (error) {
                is CancellationException -> error
                is HostRemoteException -> JobOperationException(error.code, error)
                is JobOperationException -> error
                else -> JobOperationException("jobs/read-failed", error)
            }

        private fun <T> owned(upstream: Flow<T>): Flow<T> = channelFlow {
            current()
            val task = scope.launch {
                try {
                    current()
                    upstream.collect {
                        current()
                        send(it)
                    }
                    close()
                } catch (error: Throwable) {
                    close(failure(error))
                }
            }
            task.invokeOnCompletion { error -> if (error != null) close(failure(error)) }
            awaitClose { task.cancel() }
        }
            .buffer(Channel.RENDEZVOUS)

        override fun watch(sessionId: String) =
            owned(
                    client.watchJobs(sessionId).map {
                        when (it) {
                            JobsUpdate.Reset -> JobRoster()
                            is JobsUpdate.Snapshot -> JobRoster(it.jobs, loading = false)
                        }
                    },
                )
                .conflate()

        override fun output(sessionId: String, job: JobInfo) = owned(outputFrames(sessionId, job))

        private fun outputFrames(sessionId: String, initial: JobInfo): Flow<JobOutput> = flow {
            var buffer = JobOutputBuffer()
            var job = initial
            var loading = true
            client.followJob(sessionId, initial.id).collect { frame ->
                when (frame) {
                    JobFrame.Reset -> {
                        buffer = JobOutputBuffer()
                        loading = true
                    }
                    is JobFrame.Opened -> {
                        buffer = JobOutputBuffer()
                        job = frame.job
                        loading = false
                    }
                    is JobFrame.Status -> {
                        job = frame.job
                        loading = false
                    }
                    is JobFrame.Output -> {
                        if (frame.lossy) buffer.markGap()
                        frame.chunks.forEach { chunk ->
                            if (chunk.gapBefore) buffer.markGap()
                            buffer.add(chunk.text)
                        }
                    }
                }
                emit(JobOutput(job, buffer.lines(), buffer.firstLine, buffer.trimmed, loading))
            }
        }
            .flowOn(computation)
            .conflate()

        override suspend fun stop(sessionId: String, jobId: String): Boolean {
            current()
            val task = scope.async {
                current()
                try {
                    client.killJob(sessionId, jobId).also { current() }
                } catch (error: Exception) {
                    throw failure(error)
                }
            }
            return try {
                task.await()
            } finally {
                task.cancel()
            }
        }
    }
}
