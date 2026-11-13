package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.media.GeneratedMediaItem
import dev.fughetta.dshcompose.domain.media.MediaKind
import dev.fughetta.dshcompose.domain.media.MediaModelRef
import dev.fughetta.dshcompose.domain.media.MediaParameters
import dev.fughetta.dshcompose.domain.media.MediaStatus
import dev.fughetta.dshcompose.protocol.*
import kotlinx.serialization.json.*

fun generatedMediaFromToolMeta(meta: JsonElement?): GeneratedMedia? =
    decodeGeneratedMedia((meta as? JsonObject)?.get("dsh-compose/media"))

fun decodeGeneratedMedia(value: JsonElement?): GeneratedMedia? {
    val raw = value as? JsonObject ?: return null
    if (raw.miInt("v") != 1) return null
    val items =
        raw.npObjects("items").take(4).mapNotNull { item ->
            val path = item.npString("path")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            GeneratedMediaItem(
                path,
                item.npString("mime"),
                item.miInt("width")?.takeIf { it > 0 },
                item.miInt("height")?.takeIf { it > 0 },
                item.miLong("bytes"),
                item.miDouble("duration"),
                item.npString("poster"),
                item.npString("codec"),
            )
        }
    val kind =
        when (raw.npString("kind")) {
            "image" -> MediaKind.Image
            "video" -> MediaKind.Video
            else -> items.firstOrNull()?.let { if (it.video) MediaKind.Video else MediaKind.Image }
        } ?: return null
    val status =
        when (raw.npString("status")) {
            "running" -> MediaStatus.Running
            "failed" -> MediaStatus.Failed
            else -> MediaStatus.Done
        }
    if (items.isEmpty() && status == MediaStatus.Done) return null
    return GeneratedMedia(
        raw.npString("rid"),
        kind,
        status,
        raw.npString("job"),
        items,
        decodeMediaRequest(raw["request"] as? JsonObject ?: JsonObject(emptyMap())),
        raw.npString("prompt"),
        raw.npString("revisedPrompt"),
        (raw["cost"] as? JsonObject)?.let {
            MediaCost((it["estimate"] as? JsonPrimitive)?.doubleOrNull, it.npString("currency"))
        },
        (raw["provenance"] as? JsonObject)?.let {
            MediaProvenance(it.npBool("visibleMark"), it.npBool("c2pa"), it.npBool("implicitLabel"))
        },
        (raw["error"] as? JsonObject)?.let {
            MediaGenerationError(it.npString("text"), it.npString("code"))
        },
        raw.toToolPayload() as ToolPayload.Obj,
    )
}

private fun decodeMediaRequest(value: JsonObject) =
    MediaGenerationRequest(
        value.npString("label"),
        value.npString("model"),
        value.npString("provider"),
        value.npString("aspect"),
        (value["n"] as? JsonPrimitive)?.intOrNull,
        value.npString("quality"),
        (value["seconds"] as? JsonPrimitive)?.doubleOrNull,
        (value["seconds"] as? JsonPrimitive)?.contentOrNull,
        value.npString("resolution") ?: value.npString("res"),
    )

internal fun JsonObject.miInt(key: String) = (get(key) as? JsonPrimitive)?.intOrNull

internal fun JsonObject.miLong(key: String) = (get(key) as? JsonPrimitive)?.longOrNull

internal fun JsonObject.miDouble(key: String) =
    (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)

fun MediaModelRef.json(): JsonObject = buildJsonObject {
    namespace?.let { put("ns", it) }
    service?.let { put("service", it) }
    id?.let { put("id", it) }
    put("label", label)
}

fun MediaParameters.json(): JsonObject = buildJsonObject {
    put("kind", kind.wire)
    model?.let { put("model", it.json()) }
    aspect?.let { put("aspect", it) }
    put("count", count)
    quality?.let { put("quality", it) }
    seconds?.let { put("seconds", it) }
    resolution?.let { put("resolution", it) }
}

fun parseMediaParameters(value: JsonObject): MediaParameters {
    val kind =
        when (value.string("kind")) {
            "video" -> MediaKind.Video
            else -> MediaKind.Image
        }
    val model = value["model"] as? JsonObject
    return MediaParameters(
        kind,
        model?.let {
            MediaModelRef(
                it.string("ns"),
                it.string("service"),
                it.string("id"),
                it.string("label") ?: it.string("id") ?: "",
            )
        },
        value.string("aspect"),
        (value.long("count") ?: 1).toInt(),
        value.string("quality"),
        value.long("seconds")?.toInt(),
        value.string("resolution"),
    )
}
