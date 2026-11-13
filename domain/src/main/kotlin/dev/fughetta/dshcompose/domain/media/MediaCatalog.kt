package dev.fughetta.dshcompose.domain.media

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.providers.CredentialStatus
import dev.fughetta.dshcompose.domain.settings.SettingsNamespace

data class MediaCatalogModel(
    val id: String,
    val label: String,
    val kind: MediaKind,
    val profile: String?,
    val ratios: List<String>,
    val qualities: List<String>,
    val maxN: Int,
    val durations: List<Int>,
    val resolutions: List<String>,
    val maxRefs: Int,
    val firstFrame: Boolean,
    val lastFrame: Boolean,
    val edit: Boolean,
    val price: MediaPrice?,
)

data class MediaPrice(val amount: Double?, val unit: String?, val currency: String?)

data class MediaServiceConfig(
    val namespace: String,
    val id: String,
    val label: String,
    val enabled: Boolean,
    val provider: String,
    val baseUrl: String,
    val profile: String,
    val responseFormat: String,
    val models: List<String>,
    val keyRef: String,
    val raw: ToolPayload.Obj,
) {
    val title
        get() = label.ifBlank { id }
}

data class MediaCatalogEntry(val service: MediaServiceConfig, val model: MediaCatalogModel) {
    val ref
        get() = MediaModelRef(service.namespace, service.id, model.id, model.label)

    val key
        get() = "${service.namespace}\u0000${service.id}\u0000${model.id}"
}

data class MediaCatalog(
    val status: MediaCatalogStatus = MediaCatalogStatus.Loading,
    val namespaces: List<SettingsNamespace> = emptyList(),
    val models: Map<String, MediaCatalogModel> = emptyMap(),
    val services: List<MediaServiceConfig> = emptyList(),
    val entries: List<MediaCatalogEntry> = emptyList(),
    val presets: List<MediaPreset> = emptyList(),
    val credentials: Map<String, CredentialStatus> = emptyMap(),
) {
    fun resolve(ref: MediaModelRef?, kind: MediaKind): MediaCatalogEntry? =
        entries
            .filter {
                it.model.kind == kind &&
                    ref != null &&
                    ref.pinned &&
                    it.service.id == ref.service &&
                    it.model.id == ref.id &&
                    (ref.namespace == null || ref.namespace == it.service.namespace)
            }
            .singleOrNull()

    fun default(kind: MediaKind): MediaCatalogEntry? {
        val available = entries.filter { it.model.kind == kind }
        for (namespace in namespaces) {
            val defaults = namespace.value?.get("defaults") as? ToolPayload.Obj ?: continue
            val prefix = kind.wire
            val service = defaults.text("${prefix}ServiceId")
            val model = defaults.text("${prefix}ModelId")
            available
                .find {
                    it.service.namespace == namespace.ns &&
                        it.service.id == service &&
                        it.model.id == model
                }
                ?.let {
                    return it
                }
        }
        return available.firstOrNull()
    }

    fun fit(parameters: MediaParameters, start: Boolean = false): MediaParameters {
        val found = resolve(parameters.model, parameters.kind)
        val entry =
            found
                ?: if (parameters.model == null || start) default(parameters.kind)
                else return parameters
        entry ?: return parameters
        val model = entry.model
        fun pick(value: String?, choices: List<String>, preferred: String): String? =
            value?.takeIf(choices::contains)
                ?: preferred.takeIf(choices::contains)
                ?: choices.firstOrNull()
        return parameters.copy(
            model = entry.ref,
            aspect =
                pick(
                    parameters.aspect,
                    model.ratios,
                    if (parameters.kind == MediaKind.Video) "16:9" else "1:1",
                ),
            count =
                if (parameters.kind == MediaKind.Video) 1
                else parameters.count.coerceIn(1, model.maxN),
            quality =
                parameters.quality?.takeIf {
                    parameters.kind == MediaKind.Image && it in model.qualities
                },
            seconds =
                if (parameters.kind == MediaKind.Video)
                    model.durations.minByOrNull {
                        kotlin.math.abs(
                            it - (parameters.seconds ?: model.durations.minOrNull() ?: 0),
                        )
                    }
                else null,
            resolution =
                if (parameters.kind == MediaKind.Video)
                    pick(parameters.resolution, model.resolutions, "720p")
                else null,
        )
    }
}

data class MediaPreset(
    val namespace: String,
    val id: String,
    val label: String,
    val labelEn: String?,
    val provider: String,
    val profile: String,
    val baseUrl: String,
    val responseFormat: String,
    val models: List<String>,
    val kind: MediaKind,
    val keyGroup: String,
)
