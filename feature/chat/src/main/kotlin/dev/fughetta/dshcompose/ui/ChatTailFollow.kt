package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

private data class TailFrame(
    val scrolling: Boolean,
    val below: Boolean,
    val index: Int,
    val jump: Long,
    val pause: Long,
)

/** The tail is a separate item, so following a tall reply reaches its end rather than its top. */
@Composable
internal fun rememberChatTailFollow(
    sessionId: String,
    list: LazyListState,
    tailIndex: Int,
    manualPause: Long = 0,
): () -> Unit {
    var following by rememberSaveable(sessionId) { mutableStateOf(true) }
    var jump by remember(sessionId) { mutableLongStateOf(0) }
    val latestIndex by rememberUpdatedState(tailIndex)
    val latestPause by rememberUpdatedState(manualPause)
    LaunchedEffect(sessionId, list) {
        var scrolling = false
        var seenJump = jump
        var seenPause = manualPause
        snapshotFlow {
            TailFrame(
                list.isScrollInProgress,
                list.canScrollForward,
                latestIndex,
                jump,
                latestPause,
            )
        }
            .collect { frame ->
                if (frame.jump != seenJump) {
                    seenJump = frame.jump
                    seenPause = frame.pause
                    following = true
                    scrolling = false
                    list.requestScrollToItem(frame.index)
                } else if (frame.pause != seenPause) {
                    seenPause = frame.pause
                    following = false
                    scrolling = false
                } else if (frame.scrolling) {
                    scrolling = true
                } else {
                    // Only the end of an actual scroll changes the user's choice to keep reading
                    // above.
                    // Content growth and delayed Markdown/image measurement must not clear that
                    // choice.
                    if (scrolling) {
                        following = !frame.below
                        scrolling = false
                    }
                    if (!frame.below) following = true
                    if (following && frame.below) list.requestScrollToItem(frame.index)
                }
            }
    }
    return { jump++ }
}
