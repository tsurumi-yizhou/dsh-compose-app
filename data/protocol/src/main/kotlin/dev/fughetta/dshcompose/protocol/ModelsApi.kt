package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

fun decodeModelSelection(value: JsonElement?): ModelSelection? {
    if (value == null || value == JsonNull) return null
    val obj = value.objectValue()
    return ModelSelection(
        obj.requiredString("provider"),
        obj.requiredString("model"),
        obj.string("reasoningEffort"),
    )
}

suspend fun ProtocolClient.listModels(): ModelCatalog =
    withContext(Dispatchers.Default) {
        val value = connection.call("session/modelCatalog").objectValue()
        ModelCatalog(
            decodeModelSelection(value["default"]),
            (value["routableProviders"] as? JsonArray)
                .orEmpty()
                .map { it.jsonPrimitive.content }
                .toPersistentSet(),
            (value["groups"] as? JsonArray)
                .orEmpty()
                .map { raw ->
                    val group = raw.objectValue()
                    val provider = group.requiredString("id")
                    ProviderGroup(
                        provider,
                        group.string("name") ?: provider,
                        (group["models"] as? JsonArray)
                            .orEmpty()
                            .map { rawModel ->
                                val model = rawModel.objectValue()
                                val reasoning = model["reasoning"] as? JsonObject
                                val id = model.requiredString("id")
                                ModelInfo(
                                    provider,
                                    id,
                                    model.string("name") ?: id,
                                    model.string("description"),
                                    (reasoning?.get("efforts") as? JsonArray)
                                        .orEmpty()
                                        .map { rawEffort ->
                                            val effort = rawEffort.objectValue()
                                            val effortId = effort.requiredString("id")
                                            ReasoningEffort(
                                                effortId,
                                                effort.string("name") ?: effortId,
                                                effort.string("description"),
                                            )
                                        }
                                        .toPersistentList(),
                                    reasoning?.string("defaultEffort"),
                                )
                            }
                            .toPersistentList(),
                    )
                }
                .toPersistentList(),
            (value["failures"] as? JsonArray)
                .orEmpty()
                .map { raw ->
                    val failure = raw.objectValue()
                    ModelCatalogFailure(
                        failure.requiredString("id"),
                        failure.string("name") ?: failure.requiredString("id"),
                    )
                }
                .toPersistentList(),
        )
    }

suspend fun ProtocolClient.selectModel(
    sessionId: String,
    selection: ModelSelection,
): ModelSelectionResult {
    val value =
        connection
            .call(
                "session/selectModel",
                buildJsonObject {
                    putJsonObject("request") {
                        put("sessionId", sessionId)
                        put("provider", selection.provider)
                        put("model", selection.model)
                        selection.reasoningEffort?.let { put("reasoningEffort", it) }
                    }
                },
            )
            .objectValue()
    val selected =
        decodeModelSelection(value["selected"])
            ?: throw HostProtocolException("Missing selected model")
    val status =
        when ((value["defaultSave"] as? JsonObject)?.string("status")) {
            "saved" -> ModelDefaultSaveStatus.Saved
            "skipped" -> ModelDefaultSaveStatus.Skipped
            "failed" -> ModelDefaultSaveStatus.Failed
            else -> ModelDefaultSaveStatus.Unknown
        }
    return ModelSelectionResult(selected, status)
}
