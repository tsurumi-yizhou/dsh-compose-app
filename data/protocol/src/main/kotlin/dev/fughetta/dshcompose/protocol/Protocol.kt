package dev.fughetta.dshcompose.protocol

import java.io.IOException
import kotlinx.serialization.json.*

sealed class HostException(message: String, cause: Throwable? = null) : IOException(message, cause)

class HostHttpException(val status: Int) : HostException("Host returned HTTP $status")

class HostProtocolException(message: String) : HostException(message)

class HostRemoteException(
    val code: String,
    val details: JsonElement? = null,
    val explanation: String? = null,
    val answeredByHost: Boolean = false,
) : HostException("Host refused request ($code)")

class CarrierRefusedException(val code: String) : HostException("Connection refused ($code)")

class ConnectionClosedException : HostException("Connection closed")

class HostTransportException : HostException("Could not exchange data with the Host")

val wireJson = Json { ignoreUnknownKeys = true }

fun JsonElement.objectValue(): JsonObject =
    this as? JsonObject ?: throw HostProtocolException("Expected a JSON object")

fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

fun JsonObject.requiredString(key: String): String =
    string(key)?.takeIf { it.isNotEmpty() } ?: throw HostProtocolException("Missing $key")

fun parseObject(text: String): JsonObject =
    try {
        wireJson.parseToJsonElement(text).objectValue()
    } catch (error: IllegalArgumentException) {
        throw HostProtocolException("Malformed JSON response")
    }

internal fun checkEndpoint(endpoint: String) {
    require(
        endpoint.split('/').all {
            it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9_$.-]+"))
        },
    ) {
        "Invalid Remote endpoint"
    }
}

internal fun remoteError(value: JsonElement?): HostRemoteException {
    val error = value as? JsonObject ?: throw HostProtocolException("Missing Host error")
    return HostRemoteException(
        error.requiredString("code"),
        error["details"],
        error.string("message"),
        answeredByHost = true,
    )
}

internal fun readRpcEnvelope(body: JsonObject, rpcId: String): JsonElement {
    if (body.string("type") != "server-response")
        throw HostProtocolException("Invalid server-response envelope")
    val result = body["result"]?.objectValue() ?: throw HostProtocolException("Missing RPC result")
    return when (result.boolean("ok")) {
        true -> {
            if (body.string("rpcId") != rpcId) throw HostProtocolException("RPC id mismatch")
            result["value"] ?: JsonNull
        }
        false -> throw remoteError(result["error"])
        null -> throw HostProtocolException("Result is neither ok nor error")
    }
}
