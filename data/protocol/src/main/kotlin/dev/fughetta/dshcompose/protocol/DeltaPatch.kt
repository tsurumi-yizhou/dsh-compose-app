package dev.fughetta.dshcompose.protocol

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

class DeltaPatchException(message: String) : IOException(message)

/**
 * Builds a release's APK from the installed one and a `DSHDLT01` patch (the delta patch format).
 * Big-endian throughout:
 * ```text
 * header  "DSHDLT01" · SHA-256 of the base APK (32 bytes) · SHA-256 of the target (32) · target length (u64)
 * ops     0x01 COPY  base offset (u64) · length (u64)    that many bytes of the base APK
 *         0x02 DATA  length (u64) · that many bytes       literal bytes
 *         0x00 END
 * ```
 *
 * The patch is read front to back and the base by offset through one copy buffer, and the output is
 * hashed as it is written: memory stays flat whatever the APK's size.
 */
object DeltaPatch {
    const val MAGIC = "DSHDLT01"

    private const val HEADER = 8 + 32 + 32 + 8

    /**
     * Writes to [out] what [patch] builds from [base]. Throws [DeltaPatchException] when the patch
     * isn't one from [baseSha256] to [targetSha256] ([targetSize] bytes, when known), is cut short
     * or malformed, or builds anything but exactly that target; [out] is gone then. Blocking.
     */
    fun apply(
        base: File,
        patch: File,
        out: File,
        baseSha256: String,
        targetSha256: String,
        targetSize: Long? = null,
    ) {
        var built = false
        try {
            BufferedInputStream(FileInputStream(patch), 1 shl 16).use { input ->
                val header = ByteArray(HEADER)
                if (input.readNBytes(header, 0, HEADER) < HEADER || String(header, 0, 8) != MAGIC)
                    throw DeltaPatchException("not a DSHDLT01 patch")
                if (hex(header, 8) != baseSha256)
                    throw DeltaPatchException("the patch is for another base APK")
                if (hex(header, 40) != targetSha256)
                    throw DeltaPatchException("the patch builds another APK")
                val length = size(u64(header, 72))
                if (targetSize != null && length != targetSize)
                    throw DeltaPatchException("the patch builds another length")
                RandomAccessFile(base, "r").use { source ->
                    FileOutputStream(out).use { output ->
                        run(input, source, output, length, targetSha256)
                        output.fd.sync()
                    }
                }
            }
            built = true
        } finally {
            if (!built) out.delete()
        }
    }

    private fun run(
        input: BufferedInputStream,
        base: RandomAccessFile,
        out: FileOutputStream,
        target: Long,
        targetSha256: String,
    ) {
        val buffer = ByteArray(1 shl 16)
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        fun byte(): Int =
            input.read().also { if (it < 0) throw DeltaPatchException("the patch is cut short") }
        fun readU64(): Long {
            var value = 0L
            repeat(8) { value = value shl 8 or byte().toLong() }
            return size(value)
        }
        fun bounded(length: Long): Long {
            if (length > target - written)
                throw DeltaPatchException("the patch builds past the target length")
            return length
        }
        fun emit(count: Int) {
            out.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            written += count
        }
        while (true) {
            when (val op = byte()) {
                0x00 -> {
                    if (written != target || hex(digest.digest(), 0) != targetSha256)
                        throw DeltaPatchException("the patch did not build the target")
                    return
                }
                0x01 -> {
                    val offset = readU64()
                    var left = bounded(readU64())
                    base.seek(offset)
                    while (left > 0) {
                        val n = base.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                        if (n <= 0) throw DeltaPatchException("COPY past the end of the base APK")
                        emit(n)
                        left -= n
                    }
                }
                0x02 -> {
                    var left = bounded(readU64())
                    while (left > 0) {
                        val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                        if (n <= 0) throw DeltaPatchException("the patch is cut short")
                        emit(n)
                        left -= n
                    }
                }
                else -> throw DeltaPatchException("unknown patch op $op")
            }
        }
    }

    /** An offset or a length. No file is 2^63 bytes: one that wraps negative is a broken patch. */
    private fun size(value: Long) =
        if (value < 0) throw DeltaPatchException("the patch has a size out of range") else value

    private fun u64(bytes: ByteArray, start: Int): Long {
        var value = 0L
        for (i in start until start + 8) value = value shl 8 or (bytes[i].toLong() and 0xff)
        return value
    }

    private fun hex(bytes: ByteArray, start: Int) =
        (start until start + 32).joinToString("") { "%02x".format(bytes[it]) }
}

/** The SHA-256 of [file] as lowercase hex. Blocking. */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
