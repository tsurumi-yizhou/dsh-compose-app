package dev.fughetta.dshcompose.files

import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.testing.ClientSessions
import dev.fughetta.dshcompose.ui.*
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class SessionExportViewModelTest {
    private class Request(val session: String) {
        val finish = CompletableDeferred<String?>()
        val ended = CompletableDeferred<Unit>()
    }

    private class Transport(private val http: HostConnection) : HostTransport by http {
        val requests = Channel<Request>(4)

        override suspend fun copySessionArchive(
            sessionId: String,
            destination: OutputStream,
            onProgress: suspend (Long, Long?) -> Unit,
        ): Long {
            val request = Request(sessionId)
            try {
                onProgress(0, 8)
                destination.write(byteArrayOf(1, 2, 3, 4))
                onProgress(4, 8)
                requests.send(request)
                val code = request.finish.await()
                if (code != null) throw HostRemoteException(code)
                destination.write(byteArrayOf(5, 6, 7, 8))
                onProgress(8, 8)
                return 8
            } finally {
                request.ended.complete(Unit)
            }
        }
    }

    private class Destination(root: File, val name: String, private val share: Boolean) {
        val file = File.createTempFile("archive-", ".zip", root)
        var committed = false
        val closed = CompletableDeferred<Unit>()
        val target =
            SessionArchiveDestination(
                output = file.outputStream(),
                publish = {
                    assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), file.readBytes())
                    if (share) SessionArchiveResult(share = SessionArchiveShare(file.path, name))
                    else SessionArchiveResult(savedTo = "Download/$name")
                },
                finish = { published ->
                    committed = published
                    if (!published) file.delete()
                    closed.complete(Unit)
                },
            )
    }

    @Test
    fun publicationPermissionAndCancellationOwnExactlyOneArchiveAndIgnoreRetiredDialogs(): Unit =
        runBlocking {
            withTimeout(15000) {
                val root = Files.createTempDirectory("session-export-").toFile()
                val transport = Transport(HostConnection("http://127.0.0.1:1"))
                val client = ProtocolClient(transport)
                val unsupported =
                    ProtocolClient(
                        object : HostTransport by HostConnection("http://127.0.0.1:1") {
                            override val supportsSessionExport = false

                            override suspend fun copySessionArchive(
                                sessionId: String,
                                destination: OutputStream,
                                onProgress: suspend (Long, Long?) -> Unit,
                            ): Long = throw HostRemoteException("session-export/unsupported")
                        },
                    )
                val state = MutableStateFlow<SessionExportUiState?>(null)
                val targets = Channel<Destination>(8)
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                var granted = false
                val controller =
                    SessionExportViewModel(
                        scope,
                        destination = { operation, name ->
                            Destination(root, name, operation == SessionExportOperation.Share)
                                .also { targets.send(it) }
                                .target
                        },
                        legacyPermission = { true },
                        hasPermission = { granted },
                        publish = { state.value = it },
                    )
                suspend fun next(predicate: (SessionExportUiState) -> Boolean) =
                    state.filterNotNull().first(predicate)
                try {
                    controller.connect(ClientSessions(client))
                    controller.open("session")
                    controller.run(SessionExportOperation.Save)
                    val first = next { it.needsPermission }
                    assertTrue(controller.claimPermission(first.id))
                    assertFalse(controller.claimPermission(first.id))
                    assertTrue(root.listFiles()!!.isEmpty())
                    controller.permissionResult(false)
                    assertEquals("permission", next { !it.busy }.error)
                    controller.run(SessionExportOperation.Save)
                    val retired = next { it.needsPermission }
                    assertTrue(controller.claimPermission(retired.id))
                    controller.dismiss()
                    controller.open("second")
                    controller.run(SessionExportOperation.Save)
                    val waiting = next { it.needsPermission }
                    assertFalse(controller.claimPermission(waiting.id))
                    val revision = waiting.revision
                    controller.permissionResult(true)
                    assertTrue(state.value!!.revision > revision)
                    assertTrue(state.value!!.needsPermission)
                    assertTrue(controller.claimPermission(waiting.id))
                    granted = true
                    controller.permissionResult(true)
                    val save = targets.receive()
                    val request = transport.requests.receive()
                    assertEquals("second", request.session)
                    assertFalse(save.committed)
                    assertNull(state.value!!.savedTo)
                    controller.run(SessionExportOperation.Save)
                    assertTrue(targets.tryReceive().isFailure)
                    request.finish.complete(null)
                    val saved = next { !it.busy && it.savedTo != null }
                    save.closed.await()
                    assertEquals("Download/dsh-session-second.zip", saved.savedTo)
                    assertEquals(8L, saved.bytes)
                    assertTrue(save.file.exists())
                    controller.run(SessionExportOperation.Share)
                    val sharedFile = targets.receive()
                    val share = transport.requests.receive()
                    share.finish.complete(null)
                    val ready = next { it.share != null }
                    sharedFile.closed.await()
                    assertTrue(sharedFile.file.exists())
                    assertTrue(controller.claimShare(ready.id))
                    assertFalse(controller.claimShare(ready.id))
                    controller.shared(ready.id, true)
                    assertTrue(state.value!!.shared)
                    assertNull(state.value!!.share)
                    controller.run(SessionExportOperation.Save)
                    val partial = targets.receive()
                    val cancelled = transport.requests.receive()
                    controller.dismiss()
                    cancelled.ended.await()
                    partial.closed.await()
                    assertFalse(partial.file.exists())
                    assertFalse(partial.committed)
                    assertNull(state.value)
                    controller.open("missing")
                    controller.run(SessionExportOperation.Save)
                    val failed = targets.receive()
                    transport.requests.receive().finish.complete("session-export/http-404")
                    assertEquals("session-export/http-404", next { !it.busy }.error)
                    failed.closed.await()
                    assertFalse(failed.file.exists())
                    controller.run(SessionExportOperation.Share)
                    val old = targets.receive()
                    val oldRequest = transport.requests.receive()
                    controller.connect(ClientSessions(unsupported))
                    oldRequest.ended.await()
                    old.closed.await()
                    assertFalse(old.file.exists())
                    assertNull(state.value)
                    controller.open("remote")
                    assertFalse(state.value!!.supported)
                    controller.run(SessionExportOperation.Save)
                    assertFalse(state.value!!.busy)
                    assertTrue(targets.tryReceive().isFailure)
                    assertFalse(unsupported.canExportSessionLog)
                    try {
                        unsupported.exportSessionLog("remote", OutputStream.nullOutputStream())
                        fail("Unsupported archive route")
                    } catch (error: HostRemoteException) {
                        assertEquals("session-export/unsupported", error.code)
                    }
                } finally {
                    controller.close()
                    scope.cancel()
                    client.close()
                    unsupported.close()
                    root.deleteRecursively()
                }
            }
        }
}
