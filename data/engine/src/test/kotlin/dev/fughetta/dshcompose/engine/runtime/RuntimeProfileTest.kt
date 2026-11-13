package dev.fughetta.dshcompose.engine.runtime

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(
    sdk = [28],
    application = android.app.Application::class,
    manifest = org.robolectric.annotation.Config.NONE,
)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class RuntimeProfileTest {
    @Test
    fun newProfileUsesComposeApp() {
        val root = Files.createTempDirectory("compose-profile").toFile()
        try {
            RuntimeProfile.prepare(root)
            assertEquals(
                listOf("@deepseek-ai/dsh-base", "dsh-compose-app"),
                LastKnownGood.read(root)!!.bundles,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun existingBundlesMigrateInOrderAndRemainUnchangedOnRestart() {
        val cases =
            listOf(
                listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app") to
                    listOf("@deepseek-ai/dsh-base", "dsh-compose-app"),
                listOf("@deepseek-ai/dsh-web-app", "user-bundle", "dsh-compose-app") to
                    listOf("dsh-compose-app", "user-bundle"),
                listOf("dsh-compose-app", "user-bundle", "@deepseek-ai/dsh-web-app") to
                    listOf("dsh-compose-app", "user-bundle"),
                listOf(
                    "@deepseek-ai/dsh-base",
                    "user-bundle",
                    "@deepseek-ai/dsh-web-app",
                    "tail",
                ) to listOf("@deepseek-ai/dsh-base", "user-bundle", "dsh-compose-app", "tail"),
            )
        val root = Files.createTempDirectory("compose-profile").toFile()
        try {
            val manifest = File(root, "${RuntimeProfile.DIRECTORY}/package.json")
            manifest.parentFile!!.mkdirs()
            for ((before, expected) in cases) {
                val json = buildJsonObject {
                    put("dependencies", buildJsonObject { put("user-bundle", "1") })
                    put(
                        "dsh",
                        buildJsonObject {
                            put(
                                "profile",
                                buildJsonObject {
                                    put("custom", true)
                                    put("bundles", JsonArray(before.map { JsonPrimitive(it) }))
                                },
                            )
                        },
                    )
                }
                manifest.writeText(json.toString())
                RuntimeProfile.prepare(root)
                assertEquals(expected, LastKnownGood.read(root)!!.bundles)
                val migrated = Json.parseToJsonElement(manifest.readText()).jsonObject
                assertEquals(
                    "1",
                    migrated["dependencies"]!!.jsonObject["user-bundle"]!!.jsonPrimitive.content,
                )
                assertTrue(
                    migrated["dsh"]!!
                        .jsonObject["profile"]!!
                        .jsonObject["custom"]!!
                        .jsonPrimitive
                        .boolean,
                )
                val bytes = manifest.readBytes()
                RuntimeProfile.prepare(root)
                assertArrayEquals(bytes, manifest.readBytes())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun manifestsWithoutOldBundleOrWithInvalidShapeRemainUntouched() {
        val root = Files.createTempDirectory("compose-profile").toFile()
        try {
            val manifest = File(root, "${RuntimeProfile.DIRECTORY}/package.json")
            manifest.parentFile!!.mkdirs()
            val contents =
                listOf(
                    """{ "dsh": { "profile": { "bundles": ["user-bundle", "dsh-compose-app"] } } }""",
                    "{}",
                    """{"dsh":[]} """,
                    """{"dsh":{}}""",
                    """{"dsh":{"profile":false}}""",
                    """{"dsh":{"profile":{}}}""",
                    """{"dsh":{"profile":{"bundles":"@deepseek-ai/dsh-web-app"}}}""",
                    """{"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-web-app",42]}}}""",
                    "invalid json",
                )
            for (content in contents) {
                manifest.writeText(content)
                val bytes = manifest.readBytes()
                RuntimeProfile.prepare(root)
                assertArrayEquals(bytes, manifest.readBytes())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun manifestReadFailureReportsMigrationCode() {
        val root = Files.createTempDirectory("compose-profile").toFile()
        try {
            File(root, "${RuntimeProfile.DIRECTORY}/package.json").mkdirs()
            val error =
                assertThrows(java.io.IOException::class.java) { RuntimeProfile.prepare(root) }
            assertEquals("COMPOSE_PROFILE_MIGRATE_FAILED", error.message)
            assertTrue(error.cause is java.io.IOException)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun bundleInstallationKeepsUserDisablementAndSettings() {
        val root = Files.createTempDirectory("compose-bundles").toFile()
        try {
            RuntimeProfile.prepare(root)
            RuntimeBundles.install(root)
            val manifest = File(root, "${RuntimeProfile.DIRECTORY}/package.json")
            val json = Json.parseToJsonElement(manifest.readText()).jsonObject
            val bundles = json["dsh"]!!.jsonObject["profile"]!!.jsonObject["bundles"]!!.jsonArray
            assertEquals(4, bundles.size)
            for (i in 2 until bundles.size) {
                val name = bundles[i].jsonPrimitive.content
                val packageFile = File(root, "opt/dsh/node_modules/$name/package.json")
                val bundle =
                    Json.parseToJsonElement(packageFile.readText())
                        .jsonObject["dsh"]!!
                        .jsonObject["bundle"]!!
                        .jsonObject["patch"]!!
                        .jsonPrimitive
                        .content
                assertTrue(File(packageFile.parentFile, bundle).isFile)
            }
            val dsh = json["dsh"]!!.jsonObject
            val profile = dsh["profile"]!!.jsonObject
            val replaced =
                JsonObject(
                    json +
                        ("dsh" to
                            JsonObject(
                                dsh +
                                    ("profile" to
                                        JsonObject(
                                            profile +
                                                ("bundles" to
                                                    JsonArray(
                                                        listOf(
                                                            JsonPrimitive("user-bundle"),
                                                            JsonPrimitive("dsh-compose-speech"),
                                                        ),
                                                    )),
                                        )),
                            )),
                )
            manifest.writeText(replaced.toString())
            val settings = File(root, "${RuntimeProfile.DIRECTORY}/cordis.patch.yml")
            settings.writeText("- id: custom")
            val retired = File(root, "opt/dsh/node_modules/dsh-compose-speech")
            retired.mkdirs()
            File(retired, "package.json").writeText("{}")
            RuntimeBundles.install(root)
            assertFalse(retired.exists())
            assertEquals(listOf("user-bundle"), LastKnownGood.read(root)!!.bundles)
            assertEquals("- id: custom", settings.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun profilePreparationPreservesInstalledBundlesAcrossRestarts() {
        val root = Files.createTempDirectory("compose-profile").toFile()
        try {
            val previous = File(root, "root/.dsh/profiles/web")
            previous.mkdirs()
            val custom = """{"dsh":{"profile":{"bundles":["user-bundle"]}}}"""
            File(previous, "package.json").writeText(custom)
            File(previous, "cordis.patch.yml").writeText("- id: user-settings")
            RuntimeProfile.prepare(root)
            RuntimeProfile.prepare(root)
            val current = File(root, RuntimeProfile.DIRECTORY)
            assertFalse(previous.exists())
            assertEquals(custom, File(current, "package.json").readText())
            assertEquals("- id: user-settings", File(current, "cordis.patch.yml").readText())
            val profileCommand =
                RuntimeProfile.profileArg("exec /usr/local/bin/dsh compose --port 3080")!!
            assertTrue(profileCommand.contains("dsh --profile compose "))
            assertFalse(profileCommand.contains("--from-default-profile"))
            // 重复锚点仍然拒绝：改写两个启动命令会得到自己追自己的结果。
            assertNull(
                RuntimeProfile.profileArg(
                    "exec /usr/local/bin/dsh compose --port 1 /usr/local/bin/dsh compose --port 2",
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
