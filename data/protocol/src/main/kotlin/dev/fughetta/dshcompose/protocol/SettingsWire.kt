package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.settings.*
import dev.fughetta.dshcompose.domain.settings.PluginConfigDocument
import dev.fughetta.dshcompose.domain.settings.SettingsDocument
import dev.fughetta.dshcompose.domain.settings.SettingsNamespace
import dev.fughetta.dshcompose.domain.settings.SettingsOp as DomainSettingsOp
import dev.fughetta.dshcompose.domain.settings.SettingsSecretSlot
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.*

/** Wire boundary for the settings document: host JSON views become the domain tree and back. */
fun SettingsNamespace.toView(): SettingsNamespaceView =
    SettingsNamespaceView(
        ns,
        schema,
        value?.toJsonElement() as? JsonObject ?: JsonObject(emptyMap()),
        revision,
        base?.toJsonElement() as? JsonObject,
        user?.toJsonElement() as? JsonObject,
        autoGenerate,
        secrets.toPersistentList(),
        writable,
    )

fun SettingsNamespaceView.toDomain(): SettingsNamespace =
    SettingsNamespace(
        ns,
        revision,
        writable,
        autoGenerate,
        secrets.toList(),
        (value as JsonElement).toToolPayload() as? ToolPayload.Obj,
        base?.toToolPayload(),
        user?.toToolPayload(),
        schema,
    )

fun SettingsDescription.toDomain(): SettingsDocument =
    SettingsDocument(namespaces.map { it.toDomain() }, writable, hasDocument)

fun PluginConfigEntryDetail.toDomain(): PluginConfigDocument =
    PluginConfigDocument(
        entryId,
        revision,
        writable,
        secrets.toList(),
        (value as JsonElement).toToolPayload() as? ToolPayload.Obj,
        base?.toToolPayload(),
        user?.toToolPayload(),
        schema,
    )

fun PluginConfigDocument.toDetail(): PluginConfigEntryDetail =
    PluginConfigEntryDetail(
        entryId,
        revision,
        schema,
        value?.toJsonElement() as? JsonObject ?: JsonObject(emptyMap()),
        base?.toJsonElement() as? JsonObject,
        user?.toJsonElement() as? JsonObject,
        secrets.toPersistentList(),
        writable,
    )

fun DomainSettingsOp.toWire(): SettingsOp = SettingsOp(path, value?.toJsonElement(), unset)

fun SettingsOp.toDomain(): DomainSettingsOp = DomainSettingsOp(path, value?.toToolPayload(), unset)

/** Keeps a secret-bearing slot list out of logs. */
internal fun List<SettingsSecretSlot>.toPersistentSecrets() = toPersistentList()

fun SettingsDocument.toDescription(): SettingsDescription =
    SettingsDescription(namespaces.map { it.toView() }.toPersistentList(), writable, hasDocument)
