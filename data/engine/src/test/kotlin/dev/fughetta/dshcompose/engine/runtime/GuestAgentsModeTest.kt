package dev.fughetta.dshcompose.engine.runtime

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GuestAgentsModeTest {
    @Test
    fun compatibleLaunchRestoresTheExactOriginalDefaultInstructionsAfterDirect() {
        val root = kotlin.io.path.createTempDirectory("agents-mode").toFile()
        try {
            val file = File(root, "root/.dsh/AGENTS.md")
            GuestSetup.writeAgentsMd(root, phoneStorage = false)
            val original = file.readBytes()
            assertTrue(file.readText().contains("`/root/workspace`"))
            GuestSetup.writeAgentsMd(root, phoneStorage = false, directEngine = true)
            assertTrue(file.readText().contains("`${root.canonicalPath}/root/workspace`"))
            assertFalse(original.contentEquals(file.readBytes()))
            GuestSetup.writeAgentsMd(root, phoneStorage = false, directEngine = false)
            assertArrayEquals(original, file.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aUserOwnedFileIsUnchangedAcrossBothLaunchModes() {
        val root = kotlin.io.path.createTempDirectory("agents-user").toFile()
        try {
            val file = File(root, "root/.dsh/AGENTS.md")
            file.parentFile!!.mkdirs()
            val original =
                "# My own workspace instructions\nKeep my paths and examples.\n".toByteArray()
            file.writeBytes(original)
            for (direct in listOf(true, false)) {
                GuestSetup.writeAgentsMd(
                    root,
                    phoneStorage = true,
                    capabilityInstructions = "\nPhone capability instructions\n",
                    directEngine = direct,
                )
                assertArrayEquals(original, file.readBytes())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun phoneStorageSendsOutputsToTheDshComposeFolder() {
        val root = kotlin.io.path.createTempDirectory("agents-storage").toFile()
        try {
            GuestSetup.writeAgentsMd(root, phoneStorage = true)
            val text = File(root, "root/.dsh/AGENTS.md").readText()
            // The folder FilesPlugin saves into and lists, with no space to quote in shell
            // commands.
            assertTrue(text.contains("给用户的产出文件放到 `/sdcard/Download/DSH-Compose/`。"))
            assertFalse(text.contains("/sdcard/Download/DSH/"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun diagnosticsSectionNamesThePathForEachLaunchMode() {
        val root = kotlin.io.path.createTempDirectory("agents-diagnostics").toFile()
        try {
            val file = File(root, "root/.dsh/AGENTS.md")
            GuestSetup.writeAgentsMd(root, phoneStorage = false)
            val proot = file.readText()
            assertTrue(proot.contains("## 本机诊断"))
            assertTrue(proot.contains("`\$DSH_HOME/dsh-app/diagnostics.md`"))
            assertTrue(proot.contains("dsh-compose-support"))
            GuestSetup.writeAgentsMd(root, phoneStorage = false, directEngine = true)
            val direct = file.readText()
            assertTrue(direct.contains("`${root.canonicalPath}/root/.dsh/dsh-app/diagnostics.md`"))
            assertFalse(direct.contains("\$DSH_HOME/dsh-app/diagnostics.md"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun diagnosticsSnapshotIsWrittenWhereTheEngineCanReadIt() {
        val root = kotlin.io.path.createTempDirectory("agents-write").toFile()
        try {
            val file = GuestSetup.writeDiagnostics(root, "# first\n")
            assertEquals(
                File(root, "root/.dsh/dsh-app/diagnostics.md").absoluteFile,
                file.absoluteFile,
            )
            assertEquals("# first\n", file.readText())
            GuestSetup.writeDiagnostics(root, "# second\n")
            assertEquals("# second\n", file.readText())
            assertEquals(
                0,
                file.parentFile!!.listFiles()!!.count { it.name.startsWith(".diagnostics") },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun laterDiagnosticsReplaceThePreviousReportInTheSameHome() {
        val root = kotlin.io.path.createTempDirectory("diagnostics-replace").toFile()
        try {
            val original = GuestSetup.writeDiagnostics(root, "# original\n")
            val normalFile = GuestSetup.writeDiagnostics(root, "# normal again\n")
            assertEquals(original.absoluteFile, normalFile.absoluteFile)
            assertEquals("# normal again\n", original.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun diagnosticsRejectABrokenHomeWithoutReplacingIt() {
        val root = kotlin.io.path.createTempDirectory("diagnostics-broken-home").toFile()
        try {
            val originalHome = File(root, "root/.dsh")
            originalHome.parentFile!!.mkdirs()
            val originalBytes = "original path is not a directory\n".toByteArray()
            originalHome.writeBytes(originalBytes)
            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                GuestSetup.writeDiagnostics(root, "# report\n")
            }
            assertArrayEquals(originalBytes, originalHome.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unchangedDefaultStartupDoesNotRewriteAnExistingManagedFile() {
        val root = kotlin.io.path.createTempDirectory("agents-unchanged").toFile()
        try {
            val file = File(root, "root/.dsh/AGENTS.md")
            GuestSetup.writeAgentsMd(root, phoneStorage = true, visualReplies = false)
            val original = file.readBytes()
            assertTrue(file.setLastModified(1_234_567_000L))
            val modified = file.lastModified()
            GuestSetup.writeAgentsMd(
                root,
                phoneStorage = true,
                visualReplies = false,
                directEngine = false,
            )
            assertArrayEquals(original, file.readBytes())
            assertEquals(modified, file.lastModified())
        } finally {
            root.deleteRecursively()
        }
    }
}
