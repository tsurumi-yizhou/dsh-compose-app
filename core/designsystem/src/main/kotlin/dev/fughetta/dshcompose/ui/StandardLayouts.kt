package dev.fughetta.dshcompose.ui

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.core.designsystem.R

/**
 * Below this control width, segmented controls and toggle rows stack instead of staying on one
 * line; scaled by font scale at the call site.
 */
val NarrowControlWidth = 360.dp

/** Max reading width is 720 dp; wider panes center the content instead of stretching. */
val ReadableWidth = 720.dp

/**
 * Supplies a horizontal inset that centers [ReadableWidth] content while keeping the whole pane
 * scrollable.
 */
@Composable
fun ReadablePane(
    modifier: Modifier = Modifier,
    base: Dp = 16.dp,
    content: @Composable (inset: Dp) -> Unit,
) {
    BoxWithConstraints(modifier) {
        content(base + ((maxWidth - ReadableWidth) / 2).coerceAtLeast(0.dp))
    }
}

/** Top app bar up button; routes through the back dispatcher so NavDisplay pops the stack. */
@Composable
fun UpButton(onClick: (() -> Unit)? = null) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    IconButton(onClick = { onClick?.invoke() ?: dispatcher?.onBackPressed() }) {
        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.navigate_up))
    }
}

@Composable
fun ActionIcon(
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(painterResource(icon), label) }
}

/** Overflow menu for top app bar actions that don't merit their own icon. */
@Composable
fun OverflowMenu(content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ActionIcon(R.drawable.ic_more, stringResource(R.string.more_actions)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            content { open = false }
        }
    }
}

@Composable
fun SwitchListItem(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    ListItem(
        modifier =
            modifier.toggleable(
                checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked, onCheckedChange = null, enabled = enabled) },
    ) {
        Text(text)
    }
}

@Composable
fun CheckboxListItem(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    ListItem(
        modifier =
            modifier.toggleable(
                checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ),
        leadingContent = { Checkbox(checked, onCheckedChange = null, enabled = enabled) },
        supportingContent = supporting?.let { { Text(it) } },
    ) {
        Text(text)
    }
}

@Composable
fun RadioListItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    ListItem(
        modifier =
            modifier.selectable(
                selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        leadingContent = { RadioButton(selected, onClick = null, enabled = enabled) },
        supportingContent = supporting?.let { { Text(it) } },
    ) {
        Text(text)
    }
}
