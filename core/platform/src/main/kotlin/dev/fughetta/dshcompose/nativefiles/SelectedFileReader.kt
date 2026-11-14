package dev.fughetta.dshcompose.nativefiles

import java.io.InputStream

data class SelectedNativeFile(
    val handle: String,
    val name: String,
    val bytes: Long?,
    val mediaType: String,
)

class NativeFileException(val code: String) : Exception(code)

/** One sequential reader; close may run concurrently to interrupt a blocked provider read. */
internal class SelectedFileReader(
    private val budget: SelectedFileBudget,
    handles: Set<String>,
    private val open: (String) -> InputStream,
) : AutoCloseable {
    private class Entry(var stream: InputStream? = null, var ended: Boolean = false)

    private val entries = handles.associateWith { Entry() }
    private val lock = Any()
    @Volatile private var active = true

    fun read(handle: String): ByteArray? {
        val entry = entries[handle] ?: throw NativeFileException("cancelled")
        val stream =
            synchronized(lock) {
                if (!active) throw NativeFileException("cancelled")
                if (entry.ended) return null
                entry.stream ?: open(handle).also { entry.stream = it }
            }
        val buffer = ByteArray(budget.chunkSize(handle))
        val count = stream.read(buffer)
        synchronized(lock) {
            if (!active) throw NativeFileException("cancelled")
            check(count != 0)
            if (count < 0) {
                stream.close()
                entry.stream = null
                entry.ended = true
                return null
            }
            budget.accept(handle, count)
            return buffer.copyOf(count)
        }
    }

    override fun close() {
        active = false
        synchronized(lock) {
            entries.values.forEach { entry ->
                runCatching { entry.stream?.close() }
                entry.stream = null
                entry.ended = true
            }
        }
    }
}
