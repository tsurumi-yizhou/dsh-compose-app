package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.terminal.TerminalFrame
import dev.fughetta.dshcompose.domain.terminal.TerminalInfo
import dev.fughetta.dshcompose.domain.terminal.TerminalInputWindow
import dev.fughetta.dshcompose.domain.terminal.TerminalShell
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

fun terminalShell(raw: JsonObject): TerminalShell =
    TerminalShell(
        raw.requiredString("path"),
        raw.string("name") ?: raw.requiredString("path").substringAfterLast('/'),
        (raw["args"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
    )

fun terminalInfo(raw: JsonObject): TerminalInfo =
    TerminalInfo(
        raw.requiredString("id"),
        raw.string("title").orEmpty(),
        (raw["shell"] as? JsonObject)?.let(::terminalShell) ?: TerminalShell("", "", emptyList()),
        raw.string("cwd").orEmpty(),
        (raw.long("cols") ?: 80).toInt(),
        (raw.long("rows") ?: 24).toInt(),
        raw.string("state") ?: "running",
        raw.long("exitCode")?.toInt(),
        raw.string("error"),
    )

internal fun terminalFrame(raw: JsonObject): TerminalFrame =
    when (raw.string("type")) {
        "snapshot" ->
            TerminalFrame.Snapshot(
                raw.requiredLong("sequence"),
                raw.string("screen").orEmpty(),
                terminalInfo((raw["info"] ?: JsonNull).objectValue()),
                raw.boolean("writable") == true,
                (raw["inputWindow"] as? JsonObject)?.let {
                    TerminalInputWindow(
                        it.requiredLong("maxBytes").coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
                        (it.long("frameOverheadBytes") ?: 0)
                            .coerceIn(0, Int.MAX_VALUE.toLong())
                            .toInt(),
                    )
                },
            )
        "output" -> TerminalFrame.Output(raw.requiredLong("sequence"), raw.string("data").orEmpty())
        "state" ->
            TerminalFrame.Status(
                terminalInfo((raw["info"] ?: JsonNull).objectValue()),
                raw.boolean("writable") == true,
            )
        "ack" -> TerminalFrame.Ack(raw.requiredLong("sequence"), raw.string("refusal"))
        else -> throw HostProtocolException("Invalid terminal frame")
    }
