package dev.fughetta.dshcompose.domain.commands

import kotlinx.collections.immutable.*

enum class SlashCommandKind {
    Host,
    App,
    Skill,
}

data class SlashCommand(
    val name: String,
    val label: String,
    val description: String,
    val hint: String?,
    val kind: SlashCommandKind = SlashCommandKind.Host,
    val definitionId: String? = null,
    val acceptsAttachments: Boolean = false,
) {
    val takesInput
        get() = hint != null
}

data class CommandResult(val commandId: String?, val success: Boolean, val text: String?)

data class SlashInput(val token: String, val rest: String?)

fun parseSlashInput(text: String): SlashInput? {
    val line = text.trimStart()
    if (!line.startsWith('/')) return null
    val space = line.indexOfFirst(Char::isWhitespace)
    return if (space < 0) SlashInput(line.drop(1), null)
    else SlashInput(line.substring(1, space), line.substring(space))
}

fun mergeSlashCommands(
    app: List<SlashCommand>,
    host: List<SlashCommand>,
    skills: List<SlashCommand>,
): PersistentList<SlashCommand> {
    val all = (app + host.filter { it.name != "export" } + skills).distinctBy { it.name }
    val order =
        listOf(
            "new",
            "model",
            "compact",
            "permission",
            "plan",
            "goal",
            "feedback",
            "export",
            "features",
        )
    return all.sortedBy {
            order.indexOf(it.name).let { rank -> if (rank < 0) order.size else rank }
        }
        .toPersistentList()
}

fun resolveSlashCommand(commands: List<SlashCommand>, token: String): SlashCommand? =
    commands.firstOrNull {
        it.name.equals(token, true) || it.label.equals(token, true)
    }

fun filterSlashCommands(commands: List<SlashCommand>, token: String): PersistentList<SlashCommand> {
    val query = token.trim().lowercase()
    if (query.isEmpty()) return commands.toPersistentList()
    fun score(command: SlashCommand): Int? {
        val name = command.name.lowercase()
        val label = command.label.lowercase()
        if (name.startsWith(query)) return 0
        if (label.startsWith(query)) return 1
        if (name.contains(query) || label.contains(query)) return 2
        var offset = 0
        for (char in name) if (offset < query.length && char == query[offset]) offset++
        return if (offset == query.length) 3 else null
    }
    return commands
        .mapIndexedNotNull { index, command -> score(command)?.let { Triple(it, index, command) } }
        .sortedWith(compareBy<Triple<Int, Int, SlashCommand>> { it.first }.thenBy { it.second })
        .map { it.third }
        .toPersistentList()
}
