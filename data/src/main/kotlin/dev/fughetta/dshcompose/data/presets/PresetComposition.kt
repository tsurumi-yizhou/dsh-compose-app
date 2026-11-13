package dev.fughetta.dshcompose.data.presets

import dev.fughetta.dshcompose.domain.presets.*
import kotlinx.serialization.json.*
import org.snakeyaml.engine.v2.api.ConstructNode
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.Tag

/** YAML is data here: !!js is read as text, and unedited rows retain their source. */
private class PresetJsText(val value: String)

private class YamlPresetComposition
private constructor(val rows: List<PresetRow>, private val persona: Map<String, Any?>) {
    val prompt
        get() =
            when (val value = persona["prefix"]) {
                is String -> value
                is PresetJsText -> "!!js ${value.value}"
                else -> ""
            }

    val complete
        get() = persona["complete"] == true

    fun render(
        enabled: Set<String>,
        prompt: String = this.prompt,
        complete: Boolean = this.complete,
    ): String =
        rows.joinToString("\n") { row ->
            if (row.module == PERSONA_MODULE) {
                if (prompt == this.prompt && complete == this.complete) row.text
                else renderPersona(row, prompt, complete)
            } else {
                val on = row.id in enabled
                when {
                    on == !row.disabled -> row.text
                    on ->
                        row.text
                            .split('\n')
                            .filterNot { it.trimEnd() == "  disabled: true" }
                            .joinToString("\n")
                    else -> {
                        val lines = row.text.split('\n').toMutableList()
                        val disabled = lines.indexOfFirst { it.startsWith("  disabled:") }
                        if (disabled >= 0) lines[disabled] = "  disabled: true"
                        else {
                            val name = lines.indexOfFirst { it.startsWith("  name:") }
                            lines.add(if (name < 0) 1 else name + 1, "  disabled: true")
                        }
                        lines.joinToString("\n")
                    }
                }
            }
        }

    private fun renderPersona(row: PresetRow, prompt: String, complete: Boolean): String {
        val lines = row.text.split('\n').toMutableList()
        val config = lines.indexOfFirst { it.startsWith("  config:") }
        if (
            config < 0 ||
                lines[config].substringAfter(':').trim().let {
                    it.isNotEmpty() && !it.startsWith('#')
                }
        ) {
            val values =
                persona.toMutableMap().apply {
                    if (prompt != this@YamlPresetComposition.prompt) put("prefix", prompt)
                    if (complete != this@YamlPresetComposition.complete) {
                        if (complete) put("complete", true) else remove("complete")
                    }
                }
            val replacement =
                listOf("  config:") +
                    values.map { (key, value) -> "    ${JsonPrimitive(key)}: ${yamlFlow(value)}" }
            if (config < 0) lines.addAll(replacement)
            else {
                lines.removeAt(config)
                lines.addAll(config, replacement)
            }
        } else {
            fun property(key: String, replacement: String?) {
                val field = Regex("^    (?:$key|'$key'|\"$key\"):")
                var at = config + 1
                while (at < lines.size) {
                    val line = lines[at]
                    if (
                        line.isNotBlank() &&
                            !line.trimStart().startsWith('#') &&
                            line.takeWhile { it == ' ' }.length <= 2
                    )
                        break
                    if (field.containsMatchIn(line)) {
                        var end = at + 1
                        while (
                            end < lines.size &&
                                (lines[end].isBlank() ||
                                    lines[end].takeWhile { it == ' ' }.length > 4)
                        ) end++
                        repeat(end - at) { lines.removeAt(at) }
                        if (replacement != null) lines.add(at, "    $key: $replacement")
                        return
                    }
                    at++
                }
                if (replacement != null) lines.add(config + 1, "    $key: $replacement")
            }
            if (prompt != this.prompt) property("prefix", JsonPrimitive(prompt).toString())
            if (complete != this.complete) property("complete", if (complete) "true" else null)
        }
        return lines.joinToString("\n")
    }

    companion object {
        fun parse(yaml: String): PresetComposition {
            val blocks = ArrayList<MutableList<String>>()
            for (line in yaml.lineSequence()) {
                if (line.startsWith("- ")) blocks.add(mutableListOf(line))
                else blocks.lastOrNull()?.add(line)
            }
            val loader =
                Load(
                    LoadSettings.builder()
                        .setUseMarks(false)
                        .setTagConstructors(
                            mapOf(
                                Tag("tag:yaml.org,2002:js") to
                                    ConstructNode { node ->
                                        PresetJsText(
                                            (node as? ScalarNode)?.value
                                                ?: throw IllegalArgumentException(
                                                    "Expected scalar JavaScript tag",
                                                ),
                                        )
                                    },
                            ),
                        )
                        .build(),
                )
            var persona: Map<String, Any?> = emptyMap()
            val rows = blocks.mapIndexed { index, block ->
                while (block.lastOrNull()?.isBlank() == true) block.removeAt(block.lastIndex)
                val text = block.joinToString("\n")
                val value =
                    try {
                        (loader.loadFromString(text) as? List<*>)?.firstOrNull() as? Map<*, *>
                    } catch (_: Exception) {
                        null
                    }
                val module = value?.get("name") as? String
                val id = (value?.get("id") ?: module) as? String
                if (module == null || id == null) PresetRow("row-${index + 1}", "", text)
                else {
                    val disabled = value["disabled"]
                    val config = value["config"]
                    if (module == PERSONA_MODULE && config is Map<*, *>) {
                        val parsed =
                            try {
                                config.entries.associate {
                                    it.key.toString() to yamlData(it.value, 0)
                                }
                            } catch (_: IllegalArgumentException) {
                                null
                            }
                        if (parsed == null) return@mapIndexed PresetRow(id, "", text)
                        persona = parsed
                    }
                    PresetRow(
                        id,
                        module,
                        text,
                        disabled == true,
                        (disabled as? PresetJsText)?.value,
                        (config as? List<*>).orEmpty().mapNotNull {
                            (it as? Map<*, *>)?.get("name") as? String
                        },
                    )
                }
            }
            return YamlPresetComposition(rows, persona).let {
                PresetComposition(it.rows, it.prompt, it.complete) { enabled, prompt, complete ->
                    it.render(enabled, prompt, complete)
                }
            }
        }

        private fun yamlData(value: Any?, depth: Int): Any? {
            require(depth < 64)
            return when (value) {
                null,
                is String,
                is Boolean,
                is Number,
                is PresetJsText -> value
                is Map<*, *> ->
                    value.entries.associate { it.key.toString() to yamlData(it.value, depth + 1) }
                is List<*> -> value.map { yamlData(it, depth + 1) }
                else -> throw IllegalArgumentException("Non-data YAML value")
            }
        }

        private fun yamlFlow(value: Any?): String =
            when (value) {
                null -> "null"
                is PresetJsText -> "!!js ${JsonPrimitive(value.value)}"
                is String -> JsonPrimitive(value).toString()
                is Boolean,
                is Number -> value.toString()
                is Map<*, *> ->
                    value.entries.joinToString(", ", "{", "}") { (key, child) ->
                        "${JsonPrimitive(key.toString())}: ${yamlFlow(child)}"
                    }
                is List<*> -> value.joinToString(", ", "[", "]") { yamlFlow(it) }
                else -> throw IllegalArgumentException("Non-data YAML value")
            }
    }
}

fun parsePresetComposition(yaml: String): PresetComposition = YamlPresetComposition.parse(yaml)
