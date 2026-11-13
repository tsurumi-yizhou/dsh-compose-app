package dev.fughetta.dshcompose.data.database

import android.content.Context
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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
@Config(sdk = [28], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppDatabaseMigrationTest {
    private val name = "room-migration-test.db"

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun cleanBefore() {
        context.deleteDatabase(name)
    }

    @After
    fun cleanAfter() {
        context.deleteDatabase(name)
    }

    @Test
    fun removingSshStoragePreservesLocalConversationAndPendingWork() =
        runBlocking<Unit>(Dispatchers.IO) {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
                createTables(database, exportedSchema(3))
                database.execSQL(
                    "INSERT INTO ssh_hosts VALUES ('ssh-old', 0, 'Old host', 'example.invalid', 22, 'user', 'Password', NULL, NULL, NULL)",
                )
                database.execSQL(
                    "INSERT INTO ssh_credentials VALUES ('ssh-old', ?, ?)",
                    arrayOf<Any>(byteArrayOf(1), byteArrayOf(2)),
                )
                database.execSQL("INSERT INTO drafts VALUES ('session', 'draft', 1)")
                database.execSQL(
                    "INSERT INTO sessions VALUES ('local', 'session', 'Title', 1, 0, NULL, 'pending')",
                )
                database.execSQL(
                    "INSERT INTO transcript_items VALUES ('local', 'session', 1, 'message', 'stored-message', 1)",
                )
                database.execSQL(
                    "INSERT INTO outbox VALUES (1, 'local', 'session', 'send', 'pending-message', 'receipt', 'pending', 1, 0)",
                )
                database.version = 3
            }
            repeat(2) {
                val database =
                    Room.databaseBuilder(context, AppDatabase::class.java, name)
                        .addMigrations(AppDatabase.MIGRATION_3_4)
                        .build()
                try {
                    assertEquals("draft", database.drafts().load().single().text)
                    assertEquals("Title", database.sessionMirror().find("local", "session")?.title)
                    assertEquals(
                        "stored-message",
                        database
                            .transcriptMirror()
                            .page("local", "session", Long.MAX_VALUE, 10)
                            .single()
                            .payload,
                    )
                    val pending = database.outbox().byState("local", "pending").single()
                    assertEquals("pending-message", pending.payload)
                    assertEquals("receipt", pending.receiptId)
                    database.openHelper.readableDatabase
                        .query(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('ssh_hosts', 'ssh_credentials')",
                        )
                        .use { tables -> assertEquals(0, tables.count) }
                } finally {
                    database.close()
                }
            }
        }

    private fun createTables(
        database: android.database.sqlite.SQLiteDatabase,
        schema: JsonObject,
    ) {
        for (entity in schema["entities"]!!.jsonArray) {
            val table = entity.jsonObject
            val tableName = table["tableName"]!!.jsonPrimitive.content
            database.execSQL(
                table["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tableName),
            )
            table["indices"]?.jsonArray?.forEach { index ->
                database.execSQL(
                    index.jsonObject["createSql"]!!
                        .jsonPrimitive
                        .content
                        .replace("\${TABLE_NAME}", tableName),
                )
            }
        }
    }

    private fun exportedSchema(version: Int): JsonObject {
        val root =
            File(
                checkNotNull(System.getProperty("dsh.test.roomSchemas")) {
                    "dsh.test.roomSchemas is set by data/local/build.gradle.kts"
                },
            )
        val file = File(root, "dev.fughetta.dshcompose.data.database.AppDatabase/$version.json")
        assertTrue("missing exported schema $file", file.isFile)
        return Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }
}
