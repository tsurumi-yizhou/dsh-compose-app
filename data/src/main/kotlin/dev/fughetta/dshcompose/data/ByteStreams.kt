package dev.fughetta.dshcompose.data

import dev.fughetta.dshcompose.domain.ByteSink
import java.io.OutputStream

/** Bridges protocol writers without taking ownership of the caller's destination. */
internal fun ByteSink.outputStream(): OutputStream {
    val writeBytes = this
    return object : OutputStream() {
        override fun write(value: Int) = writeBytes(byteArrayOf(value.toByte()), 0, 1)

        override fun write(bytes: ByteArray, offset: Int, length: Int) =
            writeBytes(bytes, offset, length)

        override fun write(bytes: ByteArray) = writeBytes(bytes, 0, bytes.size)
    }
}
