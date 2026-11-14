package dev.fughetta.dshcompose.ui.commands

import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.sessions.SessionsRepository
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CommandsState(
    private val scope: CoroutineScope,
    private val appCommands: () -> List<SlashCommand>,
    private val decorate: (SlashCommand) -> SlashCommand,
    private val failed: () -> String,
) {
    private val mutable = MutableStateFlow<CommandsUiState?>(null)
    val state = mutable.asStateFlow()

    private fun publish(value: CommandsUiState?) {
        mutable.value = value
    }

    private var repository: SessionsRepository? = null
    private var sessionId: String? = null
    private var epoch = 0L
    private var revision = 0L
    private var refresh: Job? = null
    private var available = persistentListOf<SlashCommand>()

    fun known(token: String) =
        resolveSlashCommand(appCommands(), token) != null ||
            resolveSlashCommand(available, token) != null

    private var draft = ""
    private var composing = false

    fun connect(value: SessionsRepository?) {
        if (repository === value) return
        clear()
        repository = value
    }

    fun session(id: String) {
        if (sessionId != id) {
            clear()
            sessionId = id
            repository?.selectCommandSession(id)
        }
    }

    private fun clear() {
        epoch++
        revision++
        refresh?.cancel()
        repository?.selectCommandSession(null)
        sessionId = null
        available = persistentListOf()
        draft = ""
        publish(null)
    }

    fun composer(id: String, text: String, isComposing: Boolean) {
        session(id)
        draft = text
        composing = isComposing
        render()
    }

    fun invalidate() {
        repository?.invalidateCommands()
        render()
    }

    private suspend fun load(owner: SessionsRepository, id: String): PersistentList<SlashCommand> {
        val catalog = owner.commandCatalog(id)
        return withContext(Dispatchers.Default) {
            mergeSlashCommands(appCommands(), catalog.commands.map(decorate), catalog.skills)
        }
    }

    private fun render() {
        val id = sessionId ?: return
        val input = parseSlashInput(draft)
        val rev = ++revision
        val own = epoch
        refresh?.cancel()
        if (input == null || composing) {
            publish(null)
            return
        }
        val owner = repository ?: return
        publish(
            CommandsUiState(id, filterSlashCommands(appCommands(), input.token), loading = true),
        )
        refresh = scope.launch {
            try {
                val all = load(owner, id)
                val view =
                    withContext(Dispatchers.Default) {
                        val exact = resolveSlashCommand(all, input.token)
                        if (input.rest != null)
                            exact
                                ?.takeIf { it.kind == SlashCommandKind.Host && it.takesInput }
                                ?.let { CommandsUiState(id, hint = it) }
                        else CommandsUiState(id, filterSlashCommands(all, input.token))
                    }
                if (epoch == own && revision == rev && owner.active) {
                    available = all
                    publish(view)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (epoch == own && revision == rev && owner.active)
                    publish(CommandsUiState(id, error = failed()))
            }
        }
    }

    suspend fun resolve(id: String, text: String): SlashResolution? {
        val input = parseSlashInput(text) ?: return null
        if (input.token.isEmpty()) return null
        val owner = repository ?: throw CancellationException("Command connection retired")
        session(id)
        if (!owner.active) throw CancellationException("Command connection retired")
        val own = epoch
        val app = resolveSlashCommand(appCommands(), input.token)
        val all = if (app != null) appCommands().toPersistentList() else load(owner, id)
        if (repository !== owner || epoch != own || !owner.active)
            throw CancellationException("Command selection retired")
        val exact = app ?: resolveSlashCommand(all, input.token)
        if (exact?.kind == SlashCommandKind.Skill) return null
        if (exact != null)
            return SlashResolution(exact, "/${exact.name}${input.rest.orEmpty()}", false, owner, id)
        if (input.rest != null) return null
        return filterSlashCommands(all, input.token).firstOrNull()?.let {
            SlashResolution(it, "/${it.name}", true, owner, id)
        }
    }

    suspend fun execute(resolution: SlashResolution): CommandResult {
        if (repository !== resolution.owner || !resolution.owner.active)
            throw CancellationException("Command connection retired")
        return resolution.owner.executeCommand(resolution.sessionId, resolution.line)
    }

    suspend fun pick(id: String, name: String): SlashCommand? {
        val owner = repository?.takeIf { it.active } ?: return null
        appCommands()
            .firstOrNull { it.name == name }
            ?.let {
                return it
            }
        session(id)
        val own = epoch
        val all = load(owner, id)
        if (owner !== repository || own != epoch || !owner.active) return null
        available = all
        return all.firstOrNull { it.name == name }
    }

    fun close() {
        clear()
        repository = null
    }
}
