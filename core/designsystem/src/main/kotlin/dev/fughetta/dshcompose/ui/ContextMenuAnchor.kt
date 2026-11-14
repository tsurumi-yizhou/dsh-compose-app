package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round

/** Captures a long press in the same local coordinates as the menu's parent Box. */
class ContextMenuAnchor {
    private var pressedAt: IntOffset? = null
    private var size = IntSize.Zero
    var position by mutableStateOf(IntOffset.Zero)
        private set

    fun capture() {
        // Accessibility and keyboard long clicks have no pointer location.
        position = pressedAt ?: IntOffset(size.width / 2, size.height / 2)
    }

    val modifier: Modifier
        get() =
            Modifier.onSizeChanged { size = it }
                .pointerInput(this) {
                    awaitEachGesture {
                        val down =
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                        pressedAt = down.position.round()
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            pressedAt = null
                        }
                    }
                }
}

@Composable fun rememberContextMenuAnchor(): ContextMenuAnchor = remember { ContextMenuAnchor() }

/** A point anchor lets DropdownMenu position and fit itself around the actual press. */
@Composable
fun BoxScope.ContextMenuPopupAnchor(
    anchor: ContextMenuAnchor,
    content: @Composable () -> Unit,
) {
    Box(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { anchor.position }.size(0.dp)) {
        content()
    }
}

/** Shared surface styling for touch context menus and image actions. */
@Composable
fun ContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)),
        content = content,
    )
}
