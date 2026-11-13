package dev.fughetta.dshcompose.ui.personal

/** The user's own AGENTS.md (`root/.agents/AGENTS.md`) on the local engine. */
data class ReplyPrefsState(
    /** False until the local runtime is installed. */
    val available: Boolean = false,
    val loaded: Boolean = false,
    val text: String = "",
    val saving: Boolean = false,
    /** Bumped after each save so the page can confirm it. */
    val savedRevision: Long = 0,
    val error: String? = null,
) {
    companion object {
        const val MaxChars = 64 * 1024
    }
}

sealed interface ReplyPrefsAction {
    data object Load : ReplyPrefsAction

    /** Empty text removes the file. */
    data class Save(val text: String) : ReplyPrefsAction
}
