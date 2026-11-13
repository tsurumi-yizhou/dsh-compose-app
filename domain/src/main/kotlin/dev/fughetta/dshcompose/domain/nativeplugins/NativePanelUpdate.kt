package dev.fughetta.dshcompose.domain.nativeplugins

sealed interface NativePanelUpdate {
    data class Snapshot(val value: NativePluginView) : NativePanelUpdate

    data class Patch(val value: NativePluginData) : NativePanelUpdate

    data class Micro(val text: String?) : NativePanelUpdate
}
