package dev.fughetta.dshcompose.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class DeltaPatchTest {
    private val dir = Files.createTempDirectory("delta").toFile().apply { deleteOnExit() }
    private val baseBytes = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val base = File(dir, "base.apk").apply { writeBytes(baseBytes) }
    private val literal = "new bytes".toByteArray()
    private val target =
        baseBytes.copyOfRange(100, 150_100) + literal + baseBytes.copyOfRange(0, 10)
    private val baseSha = sha(baseBytes)
    private val targetSha = sha(target)

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun patch(
        magic: String = DeltaPatch.MAGIC,
        from: String = baseSha,
        length: Long = target.size.toLong(),
        ops: DataOutputStream.() -> Unit = {
            write(1)
            writeLong(100)
            writeLong(150_000)
            write(2)
            writeLong(literal.size.toLong())
            write(literal)
            write(1)
            writeLong(0)
            writeLong(10)
            write(0)
        },
    ): File {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write(magic.toByteArray())
            write(from.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            write(targetSha.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            writeLong(length)
            ops()
        }
        return File(dir, "p.dshdelta").apply { writeBytes(bytes.toByteArray()) }
    }

    private fun rejects(patch: File, targetSize: Long? = null) {
        val out = File(dir, "out.apk")
        assertThrows(DeltaPatchException::class.java) {
            DeltaPatch.apply(base, patch, out, baseSha, targetSha, targetSize)
        }
        assertFalse(out.exists())
    }

    @Test
    fun buildsExactlyTheTargetFromCopiesAndLiterals() {
        val out = File(dir, "out.apk")
        DeltaPatch.apply(base, patch(), out, baseSha, targetSha, target.size.toLong())
        assertArrayEquals(target, out.readBytes())
        assertEquals(targetSha, sha256Hex(out))
    }

    @Test
    fun rejectsAnyPatchThatIsNotThisBaseToThisTargetAndLeavesNoOutput() {
        rejects(patch(magic = "DSHDLT02"))
        rejects(patch(from = sha(target)))
        rejects(patch(), targetSize = target.size + 1L)
        rejects(patch(length = target.size - 1L))
        rejects(
            patch(
                ops = {
                    write(2)
                    writeLong(target.size + 1L)
                },
            ),
        )
        rejects(
            patch(
                ops = {
                    write(1)
                    writeLong(199_990)
                    writeLong(20)
                    write(0)
                },
            ),
        )
        rejects(patch(ops = { write(7) }))
        rejects(
            patch(
                ops = {
                    write(2)
                    writeLong(-1)
                },
            ),
        )
        rejects(
            patch(
                ops = {
                    write(1)
                    writeLong(0)
                    writeLong(target.size.toLong())
                    write(0)
                },
            ),
        )
        rejects(
            patch(
                ops = {
                    write(2)
                    writeLong(literal.size.toLong())
                    write(literal)
                },
            ),
        )
    }
}
