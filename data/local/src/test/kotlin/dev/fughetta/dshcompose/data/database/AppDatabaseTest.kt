package dev.fughetta.dshcompose.data.database

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.*
import org.junit.Assert.*
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
class AppDatabaseTest {
    @Test
    fun draftsSurviveReopenWithBoundedHistory() =
        runBlocking<Unit>(Dispatchers.IO) {
            val context = RuntimeEnvironment.getApplication()
            val name = "records-test.db"
            context.deleteDatabase(name)
            fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            open().useDatabase { db ->
                for (index in 0..31) db.drafts()
                    .save("session-$index", "draft-$index", index.toLong())
                db.drafts().save("session-31", "", 32)
            }
            open().useDatabase { db ->
                assertEquals(29, db.drafts().load().size)
                assertEquals("draft-30", db.drafts().load().first().text)
                assertFalse(
                    db.drafts().load().any {
                        it.session == "session-0" || it.session == "session-31"
                    },
                )
            }
            context.deleteDatabase(name)
        }

    @Test
    fun mediaPagingRejectsStaleRowsAndSeparatesHostsAndDocuments() =
        runBlocking<Unit>(Dispatchers.IO) {
            Room.inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    AppDatabase::class.java,
                )
                .build()
                .useDatabase { db ->
                    fun row(host: String, id: String, seq: Long = 2) =
                        MediaWorkRow(host, id, "session", 100, seq, "image", "{\"seq\":$seq}")
                    val works = db.mediaWorks()
                    works.ingest(
                        (0..42).map { row("one", it.toString().padStart(2, '0')) } +
                            row("two", "42"),
                    )
                    works.ingest(listOf(row("one", "42", 1)))
                    val first = works.page("one", null, null, null)
                    assertEquals(41, first.size)
                    assertEquals(2L, first.first().seq)
                    assertEquals("42", first.first().rid)
                    val second = works.page("one", "image", first[39].created, first[39].rid)
                    assertEquals(listOf("02", "01", "00"), second.map { it.rid })
                    works.remove("one", "42")
                    assertEquals(1, works.page("two", null, null, null).size)
                    db.documents().put(CachedDocumentRow("ui", "one", "one-config"))
                    db.documents().put(CachedDocumentRow("ui", "two", "two-config"))
                    db.documents().remove("ui", "one")
                    assertNull(db.documents().read("ui", "one"))
                    assertEquals("two-config", db.documents().read("ui", "two"))
                }
        }
}

private inline fun <T> AppDatabase.useDatabase(block: (AppDatabase) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
