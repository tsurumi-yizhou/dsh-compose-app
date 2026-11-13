package dev.fughetta.dshcompose.data.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "drafts")
data class DraftRow(@PrimaryKey val session: String, val text: String, val updatedAt: Long)

@Entity(
    tableName = "media_works",
    primaryKeys = ["host", "rid"],
    indices =
        [
            Index(value = ["host", "created", "rid"]),
            Index(value = ["host", "kind", "created", "rid"]),
        ],
)
data class MediaWorkRow(
    val host: String,
    val rid: String,
    val session: String,
    val created: Long,
    val seq: Long,
    val kind: String,
    val meta: String,
)

@Entity(tableName = "cached_documents", primaryKeys = ["kind", "owner"])
data class CachedDocumentRow(val kind: String, val owner: String, val text: String)

@Dao
interface CachedDocumentDao {
    @Query("SELECT text FROM cached_documents WHERE kind=:kind AND owner=:owner")
    suspend fun read(kind: String, owner: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: CachedDocumentRow)

    @Query("DELETE FROM cached_documents WHERE kind=:kind AND owner=:owner")
    suspend fun remove(kind: String, owner: String)
}

@Dao
abstract class DraftDao {
    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC, session DESC LIMIT 30")
    abstract fun load(): List<DraftRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun put(row: DraftRow)

    @Query("DELETE FROM drafts WHERE session = :session") abstract fun delete(session: String)

    @Query(
        "DELETE FROM drafts WHERE session NOT IN (SELECT session FROM drafts ORDER BY updatedAt DESC, session DESC LIMIT 30)",
    )
    abstract fun prune()

    @Transaction
    open fun save(session: String, text: String, updatedAt: Long) {
        if (text.isBlank()) delete(session) else put(DraftRow(session, text, updatedAt))
        prune()
    }
}

@Dao
abstract class MediaWorkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract fun insert(row: MediaWorkRow): Long

    @Query(
        "UPDATE media_works SET session=:session, created=:created, seq=:seq, kind=:kind, meta=:meta WHERE host=:host AND rid=:rid AND seq<=:seq",
    )
    abstract fun update(
        host: String,
        rid: String,
        session: String,
        created: Long,
        seq: Long,
        kind: String,
        meta: String,
    )

    @Transaction
    open fun ingest(rows: List<MediaWorkRow>) {
        rows.forEach { row ->
            if (insert(row) == -1L)
                update(row.host, row.rid, row.session, row.created, row.seq, row.kind, row.meta)
        }
    }

    @Query(
        "SELECT * FROM media_works WHERE host=:host AND (:kind IS NULL OR kind=:kind) AND (:time IS NULL OR created<:time OR (created=:time AND rid<:rid)) ORDER BY created DESC, rid DESC LIMIT 41",
    )
    abstract fun page(host: String, kind: String?, time: Long?, rid: String?): List<MediaWorkRow>

    @Query("DELETE FROM media_works WHERE host=:host AND rid=:rid")
    abstract fun remove(host: String, rid: String)
}

@Database(
    entities =
        [
            DraftRow::class,
            MediaWorkRow::class,
            CachedDocumentRow::class,
            SessionRow::class,
            TranscriptItemRow::class,
            OutboxRow::class,
            SyncCursorRow::class,
        ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documents(): CachedDocumentDao

    abstract fun drafts(): DraftDao

    abstract fun mediaWorks(): MediaWorkDao

    abstract fun sessionMirror(): SessionMirrorDao

    abstract fun transcriptMirror(): TranscriptMirrorDao

    abstract fun outbox(): OutboxDao

    abstract fun syncCursors(): SyncCursorDao

    companion object {
        /**
         * v1 stored a per-host engine launch command. Compose-only hosts have none, and SQLite
         * before 3.35 cannot drop a column, so rebuild the table and copy the remaining columns.
         * `ssh_hosts` has no indices in v1, so only the table is recreated.
         */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `ssh_hosts_new` (`id` TEXT NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `user` TEXT NOT NULL, `authentication` TEXT NOT NULL, `workingDirectory` TEXT, `trustedKey` TEXT, `trustedKeyType` TEXT, PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "INSERT INTO `ssh_hosts_new` (`id`, `position`, `name`, `host`, `port`, `user`, `authentication`, `workingDirectory`, `trustedKey`, `trustedKeyType`) SELECT `id`, `position`, `name`, `host`, `port`, `user`, `authentication`, `workingDirectory`, `trustedKey`, `trustedKeyType` FROM `ssh_hosts`",
                    )
                    db.execSQL("DROP TABLE `ssh_hosts`")
                    db.execSQL("ALTER TABLE `ssh_hosts_new` RENAME TO `ssh_hosts`")
                }
            }

        /**
         * v3 adds the local-first mirror (`sessions`, `transcript_items`, `outbox`,
         * `sync_cursors`). v2 data is untouched, so the migration only creates the new tables.
         */
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sessions` (`hostId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `title` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `running` INTEGER NOT NULL, `remoteRev` TEXT, `syncState` TEXT NOT NULL, PRIMARY KEY(`hostId`, `sessionId`))",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_sessions_hostId_updatedAt` ON `sessions` (`hostId`, `updatedAt`)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `transcript_items` (`hostId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `kind` TEXT NOT NULL, `payload` TEXT NOT NULL, `recordedAt` INTEGER NOT NULL, PRIMARY KEY(`hostId`, `sessionId`, `seq`))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `outbox` (`localId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hostId` TEXT NOT NULL, `sessionId` TEXT, `kind` TEXT NOT NULL, `payload` TEXT NOT NULL, `receiptId` TEXT NOT NULL, `state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `attempts` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_outbox_hostId_state` ON `outbox` (`hostId`, `state`)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sync_cursors` (`hostId` TEXT NOT NULL, `stream` TEXT NOT NULL, `cursor` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`hostId`, `stream`))",
                    )
                }
            }

        /** Remove the retired connection configuration while retaining chats and their outbox. */
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS `ssh_credentials`")
                    db.execSQL("DROP TABLE IF EXISTS `ssh_hosts`")
                }
            }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
                                context.applicationContext,
                                AppDatabase::class.java,
                                "app.db",
                            )
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                            .build()
                            .also { instance = it }
                }
    }
}
