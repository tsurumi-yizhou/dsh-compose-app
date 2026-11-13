package dev.fughetta.dshcompose.ui.terminal

import dev.fughetta.dshcompose.domain.terminal.TerminalEnvironment
import dev.fughetta.dshcompose.domain.terminal.TerminalInfo
import dev.fughetta.dshcompose.domain.terminal.TerminalShell
import kotlinx.collections.immutable.*
import org.connectbot.terminal.TerminalEmulator

enum class TerminalPhase {
    Starting,
    Connecting,
    Live,
    Disconnected,
    Ended,
    Missing,
    Failed,
}

data class TerminalTab(
    val id: String,
    val title: String = "",
    val phase: TerminalPhase = TerminalPhase.Connecting,
    val info: TerminalInfo? = null,
    val writable: Boolean = false,
    val emulator: TerminalEmulator? = null,
    val error: String? = null,
)

data class TerminalState(
    val revision: Long = 0,
    val connected: Boolean = false,
    val supported: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val sessionId: String? = null,
    val hostKey: String? = null,
    val tabs: PersistentList<TerminalTab> = persistentListOf(),
    val selectedId: String? = null,
    val shells: PersistentList<TerminalShell> = persistentListOf(),
    val shellPath: String? = null,
    val environment: TerminalEnvironment? = null,
    val error: String? = null,
    val renameId: String? = null,
    val renameText: String = "",
    val confirmClose: String? = null,
    val pendingClipboard: String? = null,
    val ctrl: Int = 0,
    val alt: Int = 0,
) {
    val selected
        get() = tabs.firstOrNull { it.id == selectedId }
}

sealed interface TerminalAction {
    data class Observe(val value: Boolean) : TerminalAction

    data object Refresh : TerminalAction

    data class Select(val id: String) : TerminalAction

    data class New(val replacing: String? = null) : TerminalAction

    data class ChooseShell(val path: String?) : TerminalAction

    data class Reconnect(val id: String) : TerminalAction

    data class RequestClose(val id: String) : TerminalAction

    data object ConfirmClose : TerminalAction

    data object Dismiss : TerminalAction

    data class RequestRename(val id: String) : TerminalAction

    data class RenameText(val text: String) : TerminalAction

    data object SaveRename : TerminalAction

    data class Size(val cols: Int, val rows: Int) : TerminalAction

    data object ConsumeModifiers : TerminalAction

    data class SpecialKey(val key: Int) : TerminalAction

    data class Key(val value: String) : TerminalAction

    data class ModifierKey(val ctrl: Boolean, val locked: Boolean = false) : TerminalAction

    data class Paste(val text: String) : TerminalAction

    data object AllowClipboard : TerminalAction

    data object DenyClipboard : TerminalAction
}

sealed interface TerminalEffect {
    data class CopyClipboard(val text: String) : TerminalEffect
}

fun terminalModifiers(text: String, ctrl: Boolean, alt: Boolean): String {
    if (text.codePointCount(0, text.length) != 1) return text
    val point = text.codePointAt(0)
    val folded =
        if (!ctrl) text
        else
            when {
                point in 97..122 -> (point - 96).toChar().toString()
                point in 65..90 -> (point - 64).toChar().toString()
                text in listOf("@", " ", "2") -> "\u0000"
                text in listOf("[", "3") -> "\u001b"
                text in listOf("\\", "4") -> "\u001c"
                text in listOf("]", "5") -> "\u001d"
                text in listOf("^", "~", "6") -> "\u001e"
                text in listOf("_", "-", "/", "7") -> "\u001f"
                text in listOf("?", "8") -> "\u007f"
                else -> text
            }
    return if (alt) "\u001b$folded" else folded
}
