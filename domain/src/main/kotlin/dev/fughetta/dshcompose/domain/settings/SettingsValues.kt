package dev.fughetta.dshcompose.domain.settings

import dev.fughetta.dshcompose.domain.chat.ToolPayload

fun lookupSetting(root: ToolPayload?, path: List<String>): ToolPayload? {
    var value = root
    for (part in path) value =
        when (val parent = value) {
            is ToolPayload.Obj -> parent.fields[part]
            is ToolPayload.Arr -> part.toIntOrNull()?.let(parent.items::getOrNull)
            else -> null
        }
    return value
}

fun replaceSetting(
    root: ToolPayload?,
    path: List<String>,
    value: ToolPayload?,
    unset: Boolean = false,
): ToolPayload {
    if (path.isEmpty()) return if (unset) ToolPayload.Obj(emptyMap()) else value ?: ToolPayload.Null
    val key = path.first()
    val tail = path.drop(1)
    if (root is ToolPayload.Arr) {
        val index = key.toIntOrNull() ?: return root
        if (index !in root.items.indices) return root
        val copy = root.items.toMutableList()
        if (tail.isEmpty() && unset) copy.removeAt(index)
        else copy[index] = replaceSetting(copy[index], tail, value, unset)
        return ToolPayload.Arr(copy)
    }
    val copy = root?.asObject.orEmpty().toMutableMap()
    if (tail.isEmpty() && unset) copy.remove(key)
    else copy[key] = replaceSetting(copy[key], tail, value, unset)
    return ToolPayload.Obj(copy)
}
