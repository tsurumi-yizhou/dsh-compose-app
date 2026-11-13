package dev.fughetta.dshcompose.domain.presets

const val PERSONA_MODULE = "@deepseek-ai/dsh-persona"

enum class PresetRowKind {
    Tool,
    Skill,
    Context,
}

class PresetRow(
    val id: String,
    val module: String,
    val text: String,
    val disabled: Boolean = false,
    val condition: String? = null,
    val children: List<String> = emptyList(),
) {
    val windowsOnly
        get() = condition?.replace(" ", "")?.contains("!=='win32'") == true

    val kind: PresetRowKind
        get() {
            val names = (listOf(module) + children).joinToString(" ")
            return when {
                "skill" in names -> PresetRowKind.Skill
                id.startsWith("tool-") ||
                    "dsh-tool-" in names ||
                    "/tools" in names ||
                    "terminal" in names ||
                    id == "present" -> PresetRowKind.Tool
                else -> PresetRowKind.Context
            }
        }
}

class PresetComposition(
    val rows: List<PresetRow>,
    val prompt: String,
    val complete: Boolean,
    /** Re-renders the source document with the edited selection, prompt and completion flag. */
    private val source: (enabled: Set<String>, prompt: String, complete: Boolean) -> String,
) {
    fun render(
        enabled: Set<String>,
        prompt: String = this.prompt,
        complete: Boolean = this.complete,
    ): String = source(enabled, prompt, complete)

    val hasPersona
        get() = rows.count { it.module == PERSONA_MODULE } == 1

    val switchable
        get() = rows.filter {
            it.module.isNotEmpty() && it.module != PERSONA_MODULE && !it.windowsOnly
        }

    val enabled
        get() = rows.filterNot { it.disabled }.map { it.id }.toSet()
}
