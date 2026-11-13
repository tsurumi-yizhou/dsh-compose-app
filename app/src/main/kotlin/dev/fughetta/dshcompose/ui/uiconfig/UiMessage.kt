package dev.fughetta.dshcompose.ui.uiconfig

// What ui.json's reader and validator say, kept as resource ids until shown: the settings page
// renders them in the app's language, `ui.status.json` in the same one. The skill's check.mjs
// says the same words in Chinese.
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.fughetta.dshcompose.R

sealed interface UiMessage {
    data class Text(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiMessage

    data class Count(@param:PluralsRes val id: Int, val count: Int) : UiMessage

    /** 「不支持的<noun>「value」」, with the options when there are at most eight. */
    data class BadValue(
        @param:StringRes val noun: Int,
        val value: String,
        val options: List<String>,
    ) : UiMessage

    /** A syntax error: `第 line 行第 column 列：reason`. */
    data class Syntax(val line: Int, val column: Int, val reason: UiMessage) : UiMessage
}

/** Where rendered words come from: the app's resources, or a strings file in tests. */
class UiStrings(
    private val resolveText: (Int, Array<out Any>) -> String,
    val count: (Int, Int) -> String,
) {
    fun text(@StringRes id: Int, vararg args: Any): String = resolveText(id, args)
}

fun Resources.uiStrings(): UiStrings =
    UiStrings(
        resolveText = { id, args -> getString(id, *args) },
        count = { id, count -> getQuantityString(id, count, count) },
    )

fun UiMessage.render(strings: UiStrings): String =
    when (this) {
        is UiMessage.Text ->
            strings.text(
                id,
                *args.map { if (it is UiMessage) it.render(strings) else it }.toTypedArray(),
            )
        is UiMessage.Count -> strings.count(id, count)
        is UiMessage.BadValue -> {
            val points = value.codePointCount(0, value.length)
            val shown =
                if (points <= 32) value
                else value.substring(0, value.offsetByCodePoints(0, 32)) + "…"
            val noun = strings.text(noun)
            if (options.size <= 8)
                strings.text(
                    R.string.ui_config_value_options,
                    noun,
                    shown,
                    options.joinToString(strings.text(R.string.ui_config_list_separator)),
                )
            else strings.text(R.string.ui_config_unsupported_value, noun, shown)
        }
        is UiMessage.Syntax ->
            strings.text(R.string.ui_json_position, line, column, reason.render(strings))
    }
