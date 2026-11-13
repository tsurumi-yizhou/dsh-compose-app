package dev.fughetta.dshcompose.domain.uiconfig

sealed interface UiConfigRead {
    data class Text(val text: String) : UiConfigRead

    data object Absent : UiConfigRead

    data object TooLarge : UiConfigRead

    data object NotText : UiConfigRead

    data object Unavailable : UiConfigRead
}
