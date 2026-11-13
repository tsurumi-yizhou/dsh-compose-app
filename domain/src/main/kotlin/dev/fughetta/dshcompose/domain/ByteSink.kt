package dev.fughetta.dshcompose.domain

/** Consumes a byte window synchronously; the producer may reuse the buffer after returning. */
typealias ByteSink = (bytes: ByteArray, offset: Int, length: Int) -> Unit
