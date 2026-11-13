package dev.fughetta.dshcompose.domain.providers

import kotlinx.collections.immutable.*

/**
 * Source representation retained by the adapter when editable model fields change. [payload] stays
 * opaque to the domain.
 */
class ProviderModelOrigin(
    val hasAdditionalSettings: Boolean = false,
    val payload: Any? = null,
    /** Adapter-provided identity of the retained non-editable settings; opaque to the domain. */
    private val signature: Any? = null,
) {
    override fun equals(other: Any?) =
        other is ProviderModelOrigin &&
            hasAdditionalSettings == other.hasAdditionalSettings &&
            signature == other.signature

    override fun hashCode() = 31 * hasAdditionalSettings.hashCode() + signature.hashCode()

    override fun toString() = "ProviderModelOrigin(hasAdditionalSettings=$hasAdditionalSettings)"
}

/** One reasoning-effort entry: [level] for a plain level, [payload] for an adapter-shaped value. */
data class ProviderEffortValue(val level: String? = null, val payload: Any? = null)

data class ProviderReasoningEfforts(val values: PersistentMap<String, ProviderEffortValue?>) {
    val levels
        get() = values.keys.toPersistentSet()
}

data class ProviderModelSpec(
    val id: String,
    val name: String? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val input: PersistentList<String>? = null,
    val reasoning: ProviderReasoningEfforts? = null,
    val origin: ProviderModelOrigin? = null,
) {
    val reasoningLevels: PersistentSet<String>
        get() = reasoning?.levels ?: persistentSetOf()

    val thinks
        get() = reasoningLevels.any { it != "off" }

    fun acceptsImages(defaultInput: List<String> = listOf("text")) =
        (input?.takeIf { it.isNotEmpty() } ?: defaultInput).contains("image")

    fun sameSettings(other: ProviderModelSpec) =
        copy(origin = null) == other.copy(origin = null) &&
            (origin?.hasAdditionalSettings != true && other.origin?.hasAdditionalSettings != true ||
                origin == other.origin)

    fun withParameters(
        name: String?,
        contextWindow: Long?,
        maxTokens: Long?,
        images: Boolean? = null,
        efforts: Set<String>? = null,
    ): ProviderModelSpec =
        copy(
            name = name?.trim()?.takeIf { it.isNotEmpty() },
            contextWindow = contextWindow,
            maxTokens = maxTokens,
            input =
                images?.let {
                    if (it) persistentListOf("text", "image") else persistentListOf("text")
                } ?: input,
            reasoning =
                efforts?.let { selected ->
                    ProviderReasoningEfforts(
                        if (selected.none { it != "off" }) persistentMapOf()
                        else
                            selected
                                .associateWith { level ->
                                    val previous = reasoning?.values?.get(level)
                                    if (level == "off") previous
                                    else previous ?: ProviderEffortValue(level = level)
                                }
                                .toPersistentMap(),
                    )
                } ?: reasoning,
        )

    override fun toString() = "ProviderModelSpec(id=$id)"

    companion object {
        fun create(id: String): ProviderModelSpec {
            require(id.isNotBlank())
            return ProviderModelSpec(id)
        }
    }
}

data class DiscoveredModel(
    val id: String,
    val name: String?,
    val contextWindow: Long?,
    val maxTokens: Long?,
    val inputModalities: PersistentList<String>?,
) {
    fun toSpec() =
        ProviderModelSpec(
            id,
            name?.takeIf { it.isNotBlank() && it != id },
            contextWindow,
            maxTokens,
            inputModalities,
        )
}

/**
 * Settings retained by the adapter which are outside the editable context-window field. [payload]
 * stays opaque to the domain.
 */
class ProviderModelOverrideOrigin(
    val hasOtherSettings: Boolean = false,
    val payload: Any? = null,
    /** Adapter-provided identity of the retained settings; opaque to the domain. */
    private val signature: Any? = null,
) {
    override fun equals(other: Any?) =
        other is ProviderModelOverrideOrigin &&
            hasOtherSettings == other.hasOtherSettings &&
            signature == other.signature

    override fun hashCode() = 31 * hasOtherSettings.hashCode() + signature.hashCode()

    override fun toString() = "ProviderModelOverrideOrigin(hasOtherSettings=$hasOtherSettings)"
}

data class ProviderModelOverride(
    val contextWindow: Long? = null,
    val origin: ProviderModelOverrideOrigin? = null,
) {
    val isEmpty
        get() = contextWindow == null && origin?.hasOtherSettings != true

    fun withContextWindow(tokens: Long?): ProviderModelOverride {
        require(tokens == null || tokens > 0)
        return copy(contextWindow = tokens)
    }

    fun sameSettings(other: ProviderModelOverride) =
        contextWindow == other.contextWindow &&
            (origin?.hasOtherSettings != true && other.origin?.hasOtherSettings != true ||
                origin == other.origin)

    override fun toString() = "ProviderModelOverride(contextWindow=$contextWindow)"

    companion object {
        fun empty() = ProviderModelOverride()
    }
}
