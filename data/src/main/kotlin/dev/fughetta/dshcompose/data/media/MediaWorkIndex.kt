package dev.fughetta.dshcompose.data.media

import android.content.Context
import dev.fughetta.dshcompose.data.database.AppDatabase
import dev.fughetta.dshcompose.data.database.MediaWorkRow
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.media.MediaKind
import dev.fughetta.dshcompose.protocol.*
import kotlinx.serialization.json.*

/** Paged relay metadata; media bytes and credentials never enter the database. */
internal class MediaWorkIndex(context: Context) {
    private val dao = AppDatabase.get(context).mediaWorks()

    fun ingest(host: String, session: String, entries: Collection<MediaTranscriptItem>) {
        dao.ingest(
            entries.map { entry ->
                MediaWorkRow(
                    host,
                    entry.key,
                    session,
                    entry.createdAt,
                    entry.seq,
                    entry.media.kind.wire,
                    entry.media.raw.toJsonElement().toString(),
                )
            },
        )
    }

    fun page(host: String, cursor: String?, kind: MediaKind?): MediaWorksPage {
        val position = cursor?.let {
            val at = it.indexOf(':')
            require(at > 0)
            it.substring(0, at).toLong() to it.substring(at + 1)
        }
        val rows = dao.page(host, kind?.wire, position?.first, position?.second)
        val works =
            rows.take(40).mapNotNull { row ->
                val media =
                    decodeGeneratedMedia(Json.parseToJsonElement(row.meta))
                        ?: return@mapNotNull null
                MediaWork(row.rid, row.session, row.created, null, media, true)
            }
        val next = if (rows.size > 40) rows[39].let { "${it.created}:${it.rid}" } else null
        return MediaWorksPage(works, next)
    }

    fun remove(host: String, key: String) {
        dao.remove(host, key)
    }
}
