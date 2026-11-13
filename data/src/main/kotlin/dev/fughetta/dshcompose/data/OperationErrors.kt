package dev.fughetta.dshcompose.data

import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.protocol.HostRemoteException

/** Decode a wire rejection at the repository boundary, retaining its diagnostic cause. */
internal fun Throwable.asOperationFailure(): Throwable =
    if (this is HostRemoteException) OperationFailure(code, this) else this

internal inline fun <T> domainOperation(block: () -> T): T =
    try {
        block()
    } catch (error: HostRemoteException) {
        throw OperationFailure(error.code, error)
    }
