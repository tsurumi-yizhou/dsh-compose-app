package dev.fughetta.dshcompose.domain.settings

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.text.parseLocalizedText

class SchemaMeta(val raw: ToolPayload.Obj) {
    val hidden
        get() = raw["hidden"]?.primitiveContent?.toBooleanStrictOrNull() == true

    val disabled
        get() = raw["disabled"]?.primitiveContent?.toBooleanStrictOrNull() == true

    val required
        get() = raw["required"]?.primitiveContent?.toBooleanStrictOrNull() == true

    val collapse
        get() = raw["collapse"]?.primitiveContent?.toBooleanStrictOrNull() == true

    val volatile
        get() = raw["volatile"]?.primitiveContent?.toBooleanStrictOrNull() == true

    val role
        get() = raw["role"]?.primitiveContent

    val title
        get() = parseLocalizedText(raw["title"])

    val description
        get() = parseLocalizedText(raw["description"])

    val min
        get() = raw["min"]?.primitiveContent?.toDoubleOrNull()

    val max
        get() = raw["max"]?.primitiveContent?.toDoubleOrNull()

    val step
        get() = raw["step"]?.primitiveContent?.toDoubleOrNull()

    val default
        get() = raw["default"]
}

/** Nodes retain references so recursive lazy schemas do not recursively allocate copies. */
class SchemaNode
internal constructor(
    val type: String,
    val meta: SchemaMeta,
    val value: ToolPayload?,
    val bits: Map<String, Int>,
) {
    internal val mutableFields = linkedMapOf<String, SchemaNode>()
    internal val mutableMembers = mutableListOf<SchemaNode>()
    internal var wrapped: SchemaNode? = null
    internal var keyNode: SchemaNode? = null
    val dict: Map<String, SchemaNode>
        get() = mutableFields

    val list: List<SchemaNode>
        get() = mutableMembers

    val inner
        get() = wrapped

    val sKey
        get() = keyNode

    val effective: SchemaNode
        get() {
            var node = this
            val seen = mutableSetOf<SchemaNode>()
            while (
                node.type in setOf("transform", "lazy") && node.wrapped != null && seen.add(node)
            ) node = node.wrapped!!
            return node
        }

    val secret
        get() = meta.role == "secret" || effective.meta.role == "secret"

    val credentialRef
        get() = meta.role == "credential-ref" || effective.meta.role == "credential-ref"

    val enumValues: List<ToolPayload>?
        get() {
            val node = effective
            if (node.type == "const") return listOf(node.value ?: ToolPayload.Null)
            if (node.type != "union") return null
            val members = node.list.map { it.effective }
            return members
                .takeIf { it.isNotEmpty() && it.all { item -> item.type == "const" } }
                ?.map { it.value ?: ToolPayload.Null }
        }

    fun fields(seen: Set<SchemaNode> = emptySet()): Map<String, SchemaNode>? {
        val node = effective
        if (node in seen) return null
        if (node.type == "object") return node.dict
        if (node.type == "union") {
            val members =
                node.list
                    .map { it.effective }
                    .filterNot {
                        it.type == "const" && (it.value == null || it.value == ToolPayload.Null)
                    }
            return members.singleOrNull()?.fields(seen + node)
        }
        if (node.type != "intersect") return null
        val result = linkedMapOf<String, SchemaNode>()
        for (member in node.list) result.putAll(member.fields(seen + node) ?: return null)
        return result
    }

    fun containsSecret(): Boolean {
        val visited = mutableSetOf<SchemaNode>()
        val pending = ArrayDeque<SchemaNode>()
        pending.add(this)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (!visited.add(node)) continue
            if (node.secret) return true
            pending.addAll(node.dict.values)
            pending.addAll(node.list)
            node.inner?.let(pending::add)
        }
        return false
    }

    companion object {
        private fun create(raw: ToolPayload.Obj) =
            SchemaNode(
                raw["type"]?.primitiveContent ?: "unknown",
                SchemaMeta(raw["meta"] as? ToolPayload.Obj ?: ToolPayload.Obj(emptyMap())),
                raw["value"],
                (raw["bits"] as? ToolPayload.Obj)
                    ?.fields
                    .orEmpty()
                    .mapNotNull { (key, value) ->
                        value.primitiveContent?.toLongOrNull()?.let { key to it.toInt() }
                    }
                    .toMap(),
            )

        fun parse(value: ToolPayload?): SchemaNode {
            val root = value as? ToolPayload.Obj ?: return create(ToolPayload.Obj(emptyMap()))
            val refs = root["refs"] as? ToolPayload.Obj
            if (refs != null) {
                val nodes =
                    refs.fields.mapValues {
                        create(it.value as? ToolPayload.Obj ?: ToolPayload.Obj(emptyMap()))
                    }
                fun ref(value: ToolPayload?) = value?.primitiveContent?.let(nodes::get)
                for ((id, body) in refs.fields) {
                    val raw = body as? ToolPayload.Obj ?: continue
                    val node = nodes.getValue(id)
                    (raw["dict"] as? ToolPayload.Obj)?.fields.orEmpty().forEach { (key, uid) ->
                        ref(uid)?.let { node.mutableFields[key] = it }
                    }
                    (raw["list"] as? ToolPayload.Arr)?.items.orEmpty().forEach { uid ->
                        ref(uid)?.let(node.mutableMembers::add)
                    }
                    node.wrapped = ref(raw["inner"])
                    node.keyNode = ref(raw["sKey"])
                }
                return ref(root["uid"]) ?: create(ToolPayload.Obj(emptyMap()))
            }
            fun inline(raw: ToolPayload.Obj, depth: Int): SchemaNode {
                if (depth > 64) return create(ToolPayload.Obj(emptyMap()))
                val node = create(raw)
                (raw["dict"] as? ToolPayload.Obj)?.fields.orEmpty().forEach { (key, child) ->
                    (child as? ToolPayload.Obj)?.let {
                        node.mutableFields[key] = inline(it, depth + 1)
                    }
                }
                (raw["list"] as? ToolPayload.Arr)?.items.orEmpty().forEach { child ->
                    (child as? ToolPayload.Obj)?.let {
                        node.mutableMembers.add(inline(it, depth + 1))
                    }
                }
                node.wrapped = (raw["inner"] as? ToolPayload.Obj)?.let { inline(it, depth + 1) }
                node.keyNode = (raw["sKey"] as? ToolPayload.Obj)?.let { inline(it, depth + 1) }
                return node
            }
            return inline(root, 0)
        }
    }
}

fun schemaAt(root: SchemaNode, path: List<String>): SchemaNode? {
    var node = root
    for (part in path) {
        val current = node.effective
        node =
            current.fields()?.get(part)
                ?: if (current.type == "array" || current.type == "dict")
                    current.inner ?: return null
                else if (current.type == "tuple")
                    part.toIntOrNull()?.let(current.list::getOrNull) ?: return null
                else return null
    }
    return node
}
