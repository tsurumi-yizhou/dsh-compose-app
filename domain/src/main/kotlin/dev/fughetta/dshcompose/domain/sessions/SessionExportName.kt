package dev.fughetta.dshcompose.domain.sessions

/** Download name for one conversation's log archive. */
fun sessionLogZipName(sessionId: String) =
    "dsh-session-${sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.zip"
