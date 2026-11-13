package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.plugins.PluginBundle
import kotlinx.serialization.json.JsonArray

suspend fun ProtocolClient.listBundles(): List<PluginBundle> =
    (connection.call("pluginManager/listBundles") as? JsonArray
            ?: throw HostProtocolException("Invalid bundle inventory"))
        .map { parsePluginBundle(it.objectValue()) }
