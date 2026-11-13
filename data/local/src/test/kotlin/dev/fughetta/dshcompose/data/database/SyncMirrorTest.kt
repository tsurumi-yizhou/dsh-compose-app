package dev.fughetta.dshcompose.data.database

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SyncMirrorTest {
    private lateinit var database: AppDatabase

    @Before
    fun open() {
        database =
            Room.inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    AppDatabase::class.java,
                )
                .build()
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun sessionsAreReadBackFromTheLocalMirrorPerHost() =
        runBlocking<Unit>(Dispatchers.IO) {
            val host = "host"
            database
                .sessionMirror()
                .upsert(
                    listOf(
                        SessionRow(host, "a", "Old", 10, false, null, SyncStates.ACKED),
                        SessionRow(host, "b", "New", 20, true, "rev-2", SyncStates.PENDING),
                        SessionRow("other", "c", "Other", 30, false, null, SyncStates.ACKED),
                    ),
                )
            assertEquals(
                listOf("b", "a"),
                database.sessionMirror().watch(host).first().map { it.sessionId },
            )
            database
                .sessionMirror()
                .upsert(listOf(SessionRow(host, "a", "Renamed", 40, false, null, SyncStates.ACKED)))
            assertEquals("Renamed", database.sessionMirror().find(host, "a")?.title)
            database.sessionMirror().setState(host, "a", SyncStates.UNKNOWN)
            assertEquals(SyncStates.UNKNOWN, database.sessionMirror().find(host, "a")?.syncState)
            database.sessionMirror().delete(host, "b")
            assertEquals(
                listOf("a"),
                database.sessionMirror().watch(host).first().map { it.sessionId },
            )
            database.sessionMirror().clear(host)
            assertTrue(database.sessionMirror().watch(host).first().isEmpty())
            assertEquals(1, database.sessionMirror().watch("other").first().size)
        }

    @Test
    fun transcriptPagesResumeFromThePersistedTail() =
        runBlocking<Unit>(Dispatchers.IO) {
            val host = "host"
            val session = "s"
            database
                .transcriptMirror()
                .upsert(
                    (0L..9L).map {
                        TranscriptItemRow(host, session, it, "item", """{"n":$it}""", it)
                    },
                )
            assertEquals(
                listOf(6L, 5L, 4L),
                database.transcriptMirror().page(host, session, throughSeq = 6, limit = 3).map {
                    it.seq
                },
            )
            assertEquals(9L, database.transcriptMirror().lastSeq(host, session))
            assertNull(database.transcriptMirror().lastSeq(host, "missing"))
            database
                .transcriptMirror()
                .upsert(listOf(TranscriptItemRow(host, session, 9, "item", """{"n":99}""", 99)))
            assertEquals(
                """{"n":99}""",
                database
                    .transcriptMirror()
                    .page(host, session, throughSeq = 9, limit = 1)
                    .single()
                    .payload,
            )
            database.transcriptMirror().delete(host, session)
            assertNull(database.transcriptMirror().lastSeq(host, session))
        }

    @Test
    fun outboxKeepsInsertionOrderAndCountsRetries() =
        runBlocking<Unit>(Dispatchers.IO) {
            val host = "host"
            fun row(kind: String, receipt: String) =
                OutboxRow(
                    hostId = host,
                    sessionId = "s",
                    kind = kind,
                    payload = "{}",
                    receiptId = receipt,
                    state = SyncStates.PENDING,
                    createdAt = 1,
                )
            val send = database.outbox().enqueue(row("send", "r1"))
            val delete = database.outbox().enqueue(row("delete", "r2"))
            assertTrue(delete > send)
            assertEquals(
                listOf("send", "delete"),
                database
                    .outbox()
                    .next(host, listOf("send", "delete"), SyncStates.outstanding.toList(), 10)
                    .map { it.kind },
            )
            database.outbox().claim(send, SyncStates.SENDING)
            database.outbox().settle(send, SyncStates.UNKNOWN)
            database.outbox().claim(delete, SyncStates.SENDING)
            database.outbox().remove(delete)
            val unknown = database.outbox().byState(host, SyncStates.UNKNOWN).single()
            assertEquals("r1", unknown.receiptId)
            assertEquals(1, unknown.attempts)
            assertEquals(
                listOf("send"),
                database
                    .outbox()
                    .next(host, listOf("send", "delete"), SyncStates.outstanding.toList(), 10)
                    .map { it.kind },
            )
            assertEquals(
                1,
                database
                    .outbox()
                    .retag(host, listOf("send", "delete"), SyncStates.UNKNOWN, SyncStates.PENDING),
            )
            assertEquals(
                SyncStates.PENDING,
                database.outbox().byState(host, SyncStates.PENDING).single().state,
            )
            database.outbox().remove(send)
            assertEquals(0, database.outbox().count(host))
            database.outbox().clear(host)
            assertEquals(0, database.outbox().count(host))
        }

    @Test
    fun cursorsAreScopedPerHostAndStream() =
        runBlocking<Unit>(Dispatchers.IO) {
            database.syncCursors().put(SyncCursorRow("h1", "directory", "42", 1))
            database.syncCursors().put(SyncCursorRow("h1", "session:a", "7", 2))
            database.syncCursors().put(SyncCursorRow("h2", "directory", "1", 3))
            assertEquals("42", database.syncCursors().read("h1", "directory"))
            assertEquals("7", database.syncCursors().read("h1", "session:a"))
            assertNull(database.syncCursors().read("h1", "session:b"))
            database.syncCursors().put(SyncCursorRow("h1", "directory", "43", 4))
            assertEquals("43", database.syncCursors().read("h1", "directory"))
            database.syncCursors().clear("h1")
            assertNull(database.syncCursors().read("h1", "directory"))
            assertEquals("1", database.syncCursors().read("h2", "directory"))
        }
}
