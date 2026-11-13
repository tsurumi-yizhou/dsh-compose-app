package dev.fughetta.dshcompose.data.sessions

import dev.fughetta.dshcompose.domain.sessions.SessionOperationException
import dev.fughetta.dshcompose.protocol.*

fun sessionDeletionFailure(error: Exception): String =
    when (error) {
        is SessionOperationException -> error.code
        is dev.fughetta.dshcompose.domain.OperationFailure -> error.code
        is HostRemoteException -> error.code
        is HostHttpException,
        is HostTransportException,
        is ConnectionClosedException -> "connection-lost"
        is HostProtocolException -> "protocol-error"
        else -> "failed"
    }
