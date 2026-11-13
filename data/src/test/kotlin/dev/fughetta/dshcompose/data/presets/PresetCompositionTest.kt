package dev.fughetta.dshcompose.data.presets

import dev.fughetta.dshcompose.domain.presets.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

class PresetCompositionTest {
    private fun fixture(id: String) = javaClass.getResource("/preset_$id.yaml")!!.readText()

    @Test
    fun shippedPresetRowsKeepTheirSourceAndEditedBundlesRetainPlatformConditionsAndPromptData() {
        val original = fixture("standard")
        val composition = parsePresetComposition(original)
        assertEquals(original.trimEnd(), composition.render(composition.enabled))
        assertEquals(
            "process.platform === 'win32'",
            composition.rows.single { it.id == "tool-bash" }.condition,
        )
        assertFalse(composition.switchable.any { it.id == "tool-pwsh" })
        val enabled = composition.enabled - "tool-web" - "tool-bash" + "tool-plugin-manager"
        val prompt = "你是只读代码审查员。\n保留 !!js 字面文字，不执行代码。"
        val rendered = composition.render(enabled, prompt, true)
        val edited = parsePresetComposition(rendered)
        assertEquals(prompt, edited.prompt)
        assertTrue(edited.complete)
        assertEquals(enabled, edited.enabled)
        assertEquals(
            composition.rows.single { it.id == "tool-pwsh" }.text,
            edited.rows.single { it.id == "tool-pwsh" }.text,
        )
        val minimal = parsePresetComposition(fixture("minimal"))
        val changed = minimal.render(minimal.enabled, "Be brief.", true)
        assertTrue(changed.contains("includeRuntimeContext: false"))
        assertEquals("Be brief.", parsePresetComposition(changed).prompt)
        assertTrue(parsePresetComposition(fixture("cordis")).rows.isNotEmpty())
        assertTrue(parsePresetComposition(fixture("ptc")).rows.isNotEmpty())
        val inline =
            parsePresetComposition(
                "- id: persona\n  name: '$PERSONA_MODULE'\n  config: {prefix: !!js '() => 1', suffix: !!js '() => 2'}",
            )
        val inlineEdit = inline.render(inline.enabled, "Plain prompt", true)
        assertTrue(inlineEdit.contains("!!js \"() => 2\""))
        assertEquals("Plain prompt", parsePresetComposition(inlineEdit).prompt)
        val bundle = PresetBundle("review", "Review \"只读\"", "检查\n代码", rendered, 20)
        val manifest = Json.parseToJsonElement(bundle.packageJson).jsonObject
        assertEquals("@local/dsh-preset-review", manifest.getValue("name").jsonPrimitive.content)
        val root =
            Load(
                    LoadSettings.builder()
                        .setTagConstructors(
                            mapOf(
                                org.snakeyaml.engine.v2.nodes.Tag("tag:yaml.org,2002:js") to
                                    org.snakeyaml.engine.v2.api.ConstructNode { node ->
                                        (node as org.snakeyaml.engine.v2.nodes.ScalarNode).value
                                    },
                            ),
                        )
                        .build(),
                )
                .loadFromString(bundle.patch) as List<*>
        val declaration = ((root.single() as Map<*, *>)["insert"] as List<*>).single() as Map<*, *>
        val config = declaration["config"] as Map<*, *>
        assertEquals("Review \"只读\"", config["name"])
        assertEquals("检查\n代码", config["description"])
        assertEquals(edited.rows.size, (config["plugins"] as List<*>).size)
        val empty = PresetBundle("empty", "Empty", "", "", null)
        assertTrue(empty.patch.contains("plugins: []"))
    }
}
