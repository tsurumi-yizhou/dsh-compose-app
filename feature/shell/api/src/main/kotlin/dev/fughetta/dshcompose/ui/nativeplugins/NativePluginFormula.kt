package dev.fughetta.dshcompose.ui.nativeplugins

import kotlinx.serialization.json.*

/**
 * A bounded numeric grammar. No scripting runtime, property calls, assignments or executable
 * values.
 */
internal fun nativePluginFormula(source: String, data: JsonElement): Double? {
    if (source.isEmpty() || source.length > 256) return null
    return try {
        Formula(source, data).run()
    } catch (_: IllegalArgumentException) {
        null
    }
}

private class Formula(private val text: String, private val data: JsonElement) {
    private var at = 0
    private var depth = 0
    private var tokens = 0

    private fun token() {
        require(++tokens <= 128)
    }

    private fun space() {
        while (text.getOrNull(at)?.isWhitespace() == true) at++
    }

    private fun take(character: Char): Boolean {
        space()
        if (text.getOrNull(at) != character) return false
        token()
        at++
        return true
    }

    private fun finite(value: Double): Double {
        require(value.isFinite())
        return value
    }

    fun run(): Double {
        val value = expression()
        space()
        require(at == text.length)
        return finite(value)
    }

    private fun expression(): Double {
        require(++depth <= 16)
        var value = term()
        while (true) value =
            when {
                take('+') -> finite(value + term())
                take('-') -> finite(value - term())
                else -> break
            }
        depth--
        return value
    }

    private fun term(): Double {
        var value = factor()
        while (true) value =
            when {
                take('*') -> finite(value * factor())
                take('/') -> finite(value / factor())
                take('%') -> finite(value % factor())
                else -> break
            }
        return value
    }

    private fun factor(): Double {
        require(++depth <= 16)
        val value = atom()
        depth--
        return value
    }

    private fun atom(): Double {
        if (take('+')) return factor()
        if (take('-')) return -factor()
        if (take('(')) {
            val value = expression()
            require(take(')'))
            return value
        }
        space()
        Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
            .find(text, at)
            ?.takeIf { it.range.first == at }
            ?.let {
                token()
                at = it.range.last + 1
                return finite(it.value.toDouble())
            }
        Regex("data(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[[0-9]{1,6}])+")
            .find(text, at)
            ?.takeIf { it.range.first == at }
            ?.let {
                token()
                at = it.range.last + 1
                val value = nativePluginLookup(data, it.value) as? JsonPrimitive
                return finite(value?.doubleOrNull ?: throw IllegalArgumentException())
            }
        val name =
            Regex("[A-Za-z]+").find(text, at)?.takeIf { it.range.first == at }
                ?: throw IllegalArgumentException()
        token()
        at = name.range.last + 1
        require(name.value in setOf("abs", "min", "max", "clamp") && take('('))
        val args = mutableListOf(expression())
        while (take(',')) {
            require(args.size < 3)
            args.add(expression())
        }
        require(take(')'))
        return when (name.value to args.size) {
            "abs" to 1 -> kotlin.math.abs(args[0])
            "min" to 2 -> minOf(args[0], args[1])
            "max" to 2 -> maxOf(args[0], args[1])
            "clamp" to 3 -> {
                require(args[1] <= args[2])
                args[0].coerceIn(args[1], args[2])
            }
            else -> throw IllegalArgumentException()
        }
    }
}
