package dev.fughetta.dshcompose.ui.uiconfig

// The app's validator and the skill's check.mjs against the same fixtures: same pointers, same
// kinds, same Chinese words. check.mjs runs only when `node` is on PATH.

import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.core.designsystem.R as DesignR
import java.io.File
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Element

class UiConfigParserTest {
    private val skill = File("src/main/assets/skills/dsh-compose-ui")
    private val schema = UiSchema.parse(File(skill, "schema.json").readText())
    private val fixtures = File("src/test/resources/uiconfig/fixtures")

    private fun names() =
        fixtures
            .listFiles()!!
            .map { it.name }
            .filter { it.endsWith(".json") && !it.endsWith(".expected.json") }
            .map { it.removeSuffix(".json") }
            .sorted()

    private fun summarize(load: UiConfigLoad) = buildJsonObject {
        put("applied", load.appliedCount)
        put("fatal", load.fatal)
        putJsonArray("issues") {
            for (issue in load.issues) addJsonObject {
                put("pointer", issue.pointer)
                put("kind", issue.kind.wire)
                put("message", issue.message.render(zh))
            }
        }
    }

    @Test
    fun fixturesMatchExpected() {
        val names = names()
        assertEquals(27, names.size)
        for (name in names) {
            val expected = Json.parseToJsonElement(File(fixtures, "$name.expected.json").readText())
            val actual = summarize(parseUiConfig(File(fixtures, "$name.json").readText(), schema))
            assertEquals(name, expected, actual)
        }
    }

    @Test
    fun fatalKeepsLastGood() {
        val good = parseUiConfig("""{"version": 1, "density": "compact"}""", schema)
        val broken = parseUiConfig("""{"version": 1,}""", schema, good.config)
        assertTrue(broken.fatal && broken.fromLastGood)
        assertEquals("compact", broken.config.density)
        assertEquals(0, broken.appliedCount)
    }

    @Test
    fun withoutSetsSettingsAside() {
        val config =
            parseUiConfig(
                    """{"version": 1, "density": "compact", "hide": ["goalBar", "todoPill"]}""",
                    schema,
                )
                .config
        val rest = config.without(setOf(UiSettingKey.DENSITY, UiSettingKey.hide("goalBar")))
        assertEquals(null, rest.density)
        assertEquals(listOf("todoPill"), rest.hide)
        assertEquals(setOf(UiSettingKey.hide("todoPill")), rest.entries.keys)
    }

    @Test
    fun checkMjsAgrees() {
        val node =
            System.getenv("PATH")
                .orEmpty()
                .split(':')
                .map { File(it, "node") }
                .firstOrNull { it.canExecute() }
        assumeTrue("node is not installed", node != null)
        fun check(vararg args: String, stdin: String? = null): JsonObject {
            val process =
                ProcessBuilder(node!!.path, File(skill, "check.mjs").path, *args)
                    .redirectErrorStream(false)
                    .start()
            process.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } }
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor(30, TimeUnit.SECONDS)
            return JsonObject(Json.parseToJsonElement(text).jsonObject - "file")
        }
        for (name in names()) {
            val text = File(fixtures, "$name.json").readText()
            assertEquals(
                name,
                summarize(parseUiConfig(text, schema)),
                check(File(fixtures, "$name.json").path, "--json"),
            )
        }
        val cases =
            listOf(
                """{"version": 1, "x": "${"a".repeat(64 * 1024)}"}""",
                """{"version": 1, "header": {"actions": [{"id": "x1", "icon": "bolt", "label": "\u0000 ", "action": {"type": "send", "text": "a"}}]}}""",
                """{"version": 1, "composer": {"quickActions": [{"label": "x", "action": {"type": "send", "text": "${"字".repeat(2001)}"}}]}}""",
                """{"version": 1, "composer": {"quickActions": [{"label": "x", "action": {"type": "command", "name": "a", "args": "${"b".repeat(501)}"}}]}}""",
                """{"version": 1, "composer": {"quickActions": [{"label": "x", "action": {"type": "link", "url": "https://${"a".repeat(2000)}.com"}}]}}""",
                """{"version": 1, "plugins": {"seats": {"main": {"${"a".repeat(215)}": "web"}}}}""",
                """{"version": 99999, "density": "compact"}""",
                """{"version": -1}""",
                """{"version": 1, "density": "compact"} """,
                """{"version":1,"theme":{"accentPreset":"amber","accent":"#000000"}}""",
                """{"version":1,"theme":{"accent":"#000000","accentPreset":"amber"}}""",
                """{"version":1,"density":"${"长".repeat(40)}"}""",
            )
        for (text in cases) assertEquals(
            text.take(120),
            summarize(parseUiConfig(text, schema)),
            check("-", "--json", stdin = text),
        )
    }

    private companion object {
        /** The Chinese strings of values-zh/ui_config.xml, keyed by resource id. */
        val zh: UiStrings by lazy {
            val documents =
                listOf(
                        File("src/main/res/values-zh/ui_config.xml"),
                        File("../core/designsystem/src/main/res/values-zh/ui_config.xml"),
                    )
                    .map { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
            val strings = mutableMapOf<String, String>()
            val plurals = mutableMapOf<String, String>()
            fun unescape(s: String) =
                s.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
            for (doc in documents) {
                val stringNodes = doc.getElementsByTagName("string")
                for (i in 0 until stringNodes.length) {
                    val e = stringNodes.item(i) as Element
                    strings[e.getAttribute("name")] = unescape(e.textContent)
                }
                val pluralNodes = doc.getElementsByTagName("plurals")
                for (i in 0 until pluralNodes.length) {
                    val e = pluralNodes.item(i) as Element
                    plurals[e.getAttribute("name")] =
                        unescape(e.getElementsByTagName("item").item(0).textContent)
                }
            }
            fun names(type: Class<*>) = type.fields.associate { it.getInt(null) to it.name }
            val stringNames = names(R.string::class.java) + names(DesignR.string::class.java)
            val pluralNames = names(R.plurals::class.java)
            UiStrings(
                resolveText = { id, args ->
                    strings.getValue(stringNames.getValue(id)).format(*args)
                },
                count = { id, count -> plurals.getValue(pluralNames.getValue(id)).format(count) },
            )
        }
    }
}
