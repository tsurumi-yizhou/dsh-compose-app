package dev.fughetta.dshcompose.data.sessions

import java.util.concurrent.atomic.AtomicLong

/** Revision ordering is shared by active transcripts and cleared UI projections. */
class TranscriptClock {

    private val revisions = AtomicLong()

    private val epochs = AtomicLong()

    fun nextRevision(): Long = revisions.incrementAndGet()

    fun nextEpoch(): Long = epochs.incrementAndGet()
}
