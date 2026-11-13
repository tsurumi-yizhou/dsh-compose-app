package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.presets.PresetBundle
import kotlinx.serialization.json.*

val PresetBundle.directory
    get() = "dsh-preset-$id"

val PresetBundle.packageJson
    get() = Json {
        prettyPrint = true
    }
        .encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("name", existingPackage ?: "@local/dsh-preset-$id")
                put("version", "1.0.0")
                put("private", true)
                put("type", "module")
                putJsonObject("dsh") {
                    putJsonObject("bundle") { put("patch", "./cordis.patch.yml") }
                }
            },
        )

val PresetBundle.patch
    get() = buildList {
        add("- insert:")
        add("    - id: preset-$id")
        add("      name: '$AGENT_PRESET_MODULE'")
        add("      config:")
        add("        id: $id")
        add("        name: ${JsonPrimitive(name)}")
        if (description.isNotEmpty()) add("        description: ${JsonPrimitive(description)}")
        order?.let { add("        order: $it") }
        if (plugins.isBlank()) add("        plugins: []")
        else {
            add("        plugins:")
            plugins.split('\n').forEach { add(if (it.isEmpty()) "" else "          $it") }
        }
    }
        .joinToString("\n")

fun PresetBundle.files(): String =
    "`$directory/package.json`\n```json\n$packageJson\n```\n\n`$directory/cordis.patch.yml`\n```yaml\n$patch\n```"
