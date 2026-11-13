package dev.fughetta.dshcompose.domain.terminal

data class TerminalShell(val path: String, val name: String, val args: List<String>)

data class TerminalEnvironment(
    val cwd: String = "",
    val maxInputBytes: Int = 65536,
    val maxBufferedBytes: Int = 2097152,
    val maxCols: Int = 500,
    val maxRows: Int = 200,
    val scrollback: Int = 1000,
)

data class TerminalInfo(
    val id: String,
    val title: String,
    val shell: TerminalShell,
    val cwd: String,
    val cols: Int,
    val rows: Int,
    val state: String,
    val exitCode: Int?,
    val error: String?,
) {
    val running
        get() = state == "running"
}

sealed interface TerminalFrame {
    data class Snapshot(
        val sequence: Long,
        val screen: String,
        val info: TerminalInfo,
        val writable: Boolean,
        val inputWindow: TerminalInputWindow?,
    ) : TerminalFrame

    data class Output(val sequence: Long, val data: String) : TerminalFrame

    data class Status(val info: TerminalInfo, val writable: Boolean) : TerminalFrame

    data class Ack(val sequence: Long, val refusal: String?) : TerminalFrame
}

data class TerminalInputWindow(val maxBytes: Int, val frameOverheadBytes: Int)
