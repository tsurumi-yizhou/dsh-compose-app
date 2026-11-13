import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.function.Function
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

object ApkDelta {
    private const val chunkSize = 1 shl 20

    private data class Entry(
        val name: String,
        val crc: Long,
        val size: Long,
        val method: Int,
        val offset: Long,
    )

    private data class Operation(val copy: Boolean, val offset: Long, val size: Long)

    fun digest(file: File): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            file.inputStream().use { input ->
                val buffer = ByteArray(chunkSize)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    update(buffer, 0, count)
                }
            }
            digest()
        }

    fun hex(file: File): String = digest(file).joinToString("") { "%02x".format(it) }

    fun make(base: File, target: File, output: File) {
        val oldEntries = entries(base).associateBy { it.name }
        val operations = mutableListOf<Operation>()
        fun add(copy: Boolean, offset: Long, size: Long) {
            val previous = operations.lastOrNull()
            if (
                previous != null &&
                    previous.copy == copy &&
                    previous.offset + previous.size == offset
            ) {
                operations[operations.lastIndex] = previous.copy(size = previous.size + size)
            } else operations += Operation(copy, offset, size)
        }
        RandomAccessFile(base, "r").use { old ->
            RandomAccessFile(target, "r").use { current ->
                var position = 0L
                for (entry in entries(target).sortedBy { it.offset }) {
                    val candidate = oldEntries[entry.name]
                    if (
                        entry.size > 0 &&
                            candidate != null &&
                            candidate.crc == entry.crc &&
                            candidate.size == entry.size &&
                            candidate.method == entry.method &&
                            same(old, candidate.offset, current, entry.offset, entry.size)
                    ) {
                        if (entry.offset > position) add(false, position, entry.offset - position)
                        add(true, candidate.offset, entry.size)
                        position = entry.offset + entry.size
                    }
                }
                if (position < current.length()) add(false, position, current.length() - position)
                output.outputStream().buffered().use { stream ->
                    DataOutputStream(stream).use { sink ->
                        sink.write("DSHDLT01".toByteArray(StandardCharsets.US_ASCII))
                        sink.write(digest(base))
                        sink.write(digest(target))
                        sink.writeLong(target.length())
                        for (operation in operations) {
                            sink.writeByte(if (operation.copy) 1 else 2)
                            if (operation.copy) sink.writeLong(operation.offset)
                            sink.writeLong(operation.size)
                            if (!operation.copy)
                                transfer(current, operation.offset, operation.size, sink)
                        }
                        sink.writeByte(0)
                    }
                }
            }
        }
    }

    private fun same(
        first: RandomAccessFile,
        firstOffset: Long,
        second: RandomAccessFile,
        secondOffset: Long,
        size: Long,
    ): Boolean {
        first.seek(firstOffset)
        second.seek(secondOffset)
        val firstBuffer = ByteArray(minOf(chunkSize.toLong(), size).toInt())
        val secondBuffer = ByteArray(firstBuffer.size)
        var remaining = size
        while (remaining > 0) {
            val count = minOf(remaining, firstBuffer.size.toLong()).toInt()
            first.readFully(firstBuffer, 0, count)
            second.readFully(secondBuffer, 0, count)
            for (index in 0 until count) if (firstBuffer[index] != secondBuffer[index]) return false
            remaining -= count
        }
        return true
    }

    private fun transfer(
        source: RandomAccessFile,
        offset: Long,
        size: Long,
        output: DataOutputStream,
    ) {
        source.seek(offset)
        val buffer = ByteArray(chunkSize)
        var remaining = size
        while (remaining > 0) {
            val count = minOf(remaining, buffer.size.toLong()).toInt()
            source.readFully(buffer, 0, count)
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun RandomAccessFile.shortLE(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)

    private fun RandomAccessFile.intLE(): Long = shortLE().toLong() or (shortLE().toLong() shl 16)

    private fun entries(file: File): List<Entry> =
        RandomAccessFile(file, "r").use { input ->
            val earliest = maxOf(0, input.length() - 65_557)
            var end = input.length() - 22
            while (end >= earliest) {
                input.seek(end)
                if (input.intLE() == 0x06054b50L) {
                    input.seek(end + 20)
                    if (end + 22 + input.shortLE() == input.length()) break
                }
                end--
            }
            require(end >= earliest) { "$file: ZIP directory not found" }
            input.seek(end + 8)
            val diskCount = input.shortLE()
            val count = input.shortLE()
            input.seek(end + 16)
            var position = input.intLE()
            require(diskCount == count && count < 65_535 && position < 0xffffffffL) {
                "$file: expected a single APK ZIP directory"
            }
            buildList {
                repeat(count) {
                    input.seek(position)
                    require(input.intLE() == 0x02014b50L) { "$file: invalid ZIP directory" }
                    input.seek(position + 10)
                    val method = input.shortLE()
                    input.seek(position + 16)
                    val crc = input.intLE()
                    val size = input.intLE()
                    input.seek(position + 28)
                    val nameLength = input.shortLE()
                    val extraLength = input.shortLE()
                    val commentLength = input.shortLE()
                    input.seek(position + 42)
                    val header = input.intLE()
                    val nameBytes = ByteArray(nameLength)
                    input.readFully(nameBytes)
                    val name = String(nameBytes, StandardCharsets.UTF_8)
                    input.seek(header + 26)
                    val data = header + 30 + input.shortLE() + input.shortLE()
                    add(Entry(name, crc, size, method, data))
                    position += 46 + nameLength + extraLength + commentLength
                }
            }
        }
}

// Functions shared with the release task carry no Project or script receiver.
extra["makeApkDelta"] = Action<List<File>> { ApkDelta.make(this[0], this[1], this[2]) }

extra["apkDigest"] = Function<File, String> { file -> ApkDelta.hex(file) }

@DisableCachingByDefault(because = "Verifies the delta producer against two assembled APKs")
abstract class VerifyReleaseDelta : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baseDirectory: DirectoryProperty
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val targetDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val base = baseDirectory.get().asFile.listFiles()!!.single { it.extension == "apk" }
        val target = targetDirectory.get().asFile.listFiles()!!.single { it.extension == "apk" }
        val patch = temporaryDir.resolve("update.dshd")
        val restored = temporaryDir.resolve("restored.apk")
        ApkDelta.make(base, target, patch)
        DataInputStream(patch.inputStream().buffered()).use { input ->
            require(String(input.readNBytes(8), StandardCharsets.US_ASCII) == "DSHDLT01")
            input.skipNBytes(64) // Digests are format metadata; this verification compares bytes.
            val expectedSize = input.readLong()
            RandomAccessFile(base, "r").use { old ->
                restored.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        val op = input.readUnsignedByte()
                        if (op == 0) break
                        require(op == 1 || op == 2)
                        if (op == 1) old.seek(input.readLong())
                        var remaining = input.readLong()
                        while (remaining > 0) {
                            val count = minOf(remaining, buffer.size.toLong()).toInt()
                            if (op == 1) old.readFully(buffer, 0, count)
                            else input.readFully(buffer, 0, count)
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                    require(input.read() == -1)
                }
            }
            require(restored.length() == expectedSize)
        }
        check(Files.mismatch(restored.toPath(), target.toPath()) == -1L) {
            "The delta did not restore the target APK bytes"
        }
        logger.lifecycle("APK delta restored the target APK byte for byte")
    }
}

val verifyReleaseDelta =
    tasks.register<VerifyReleaseDelta>("verifyReleaseDelta") {
        group = "verification"
        description = "Create and apply a delta between the arm64 debug and release APKs."
    }

extra["deltaApk"] =
    Action<Pair<String, Provider<Directory>>> {
        val (buildType, artifact) = this
        verifyReleaseDelta.configure {
            when (buildType) {
                "debug" -> baseDirectory.set(artifact)
                "release" -> targetDirectory.set(artifact)
            }
        }
    }
