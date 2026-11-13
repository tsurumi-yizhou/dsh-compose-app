package dev.fughetta.dshcompose.domain.chat

/**
 * A plugin-defined tool payload (tool `meta` or an error body) as a plain value tree. Domain and UI
 * code read it with these accessors; only the data layer knows it arrived as wire JSON.
 */
sealed interface ToolPayload {
    data object Null : ToolPayload

    data class Bool(val value: Boolean) : ToolPayload

    data class Num(val raw: String) : ToolPayload {
        val long: Long?
            get() = raw.toLongOrNull()

        val int: Int?
            get() = long?.toInt()
    }

    data class Text(val value: String) : ToolPayload

    data class Arr(val items: List<ToolPayload>) : ToolPayload

    data class Obj(val fields: Map<String, ToolPayload>) : ToolPayload

    val asObject: Map<String, ToolPayload>?
        get() = (this as? Obj)?.fields

    val asArray: List<ToolPayload>?
        get() = (this as? Arr)?.items

    val asText: String?
        get() = (this as? Text)?.value

    val asBoolean: Boolean?
        get() = (this as? Bool)?.value

    val primitiveContent: String?
        get() =
            when (this) {
                is Text -> value
                is Num -> raw
                is Bool -> value.toString()
                else -> null
            }

    val asLong: Long?
        get() = (this as? Num)?.long

    operator fun get(key: String): ToolPayload? = asObject?.get(key)

    fun field(key: String): ToolPayload? = this[key]

    fun text(key: String): String? = this[key]?.asText

    fun objectFields(key: String): Map<String, ToolPayload>? = this[key]?.asObject

    fun arrayItems(key: String): List<ToolPayload>? = this[key]?.asArray

    /** Every object entry as text; non-string values are skipped. */
    fun textFields(): Map<String, String> =
        asObject.orEmpty().mapNotNull { (key, value) -> value.asText?.let { key to it } }.toMap()
}
