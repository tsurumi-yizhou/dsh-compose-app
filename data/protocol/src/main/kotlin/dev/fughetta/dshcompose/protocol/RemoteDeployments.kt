package dev.fughetta.dshcompose.protocol

import io.ktor.client.plugins.timeout
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable
import java.io.File
import kotlinx.serialization.json.*

data class RemoteDeployment(
    val id: String,
    val name: String,
    val size: Long,
    val status: String,
    val kind: String,
    val encoding: String,
)

suspend fun HostConnection.pollDeployments(deviceName: String): List<RemoteDeployment> {
    val result =
        jsonRequest(
            "/compose/deployments/poll",
            buildJsonObject {
                put("application", "dev.fughetta.dshcompose")
                put("name", deviceName)
            },
            maxBytes = 65536,
        )
    return result["jobs"]!!.jsonArray.map {
        val job = it.jsonObject
        val id = job.requiredString("id")
        require(id.matches(Regex("[\\w-]{36}")))
        RemoteDeployment(
            id,
            job.requiredString("name"),
            job["size"]!!.jsonPrimitive.long,
            job.requiredString("status"),
            job.requiredString("kind"),
            job.requiredString("encoding"),
        )
    }
}

suspend fun HostConnection.deploymentReceipt(id: String, status: String, message: String) {
    require(id.matches(Regex("[\\w-]{36}")))
    jsonRequest(
        "/compose/deployments/$id/receipt",
        buildJsonObject {
            put("status", status)
            put("message", message)
        },
        maxBytes = 8192,
    )
}

suspend fun HostConnection.downloadDeployment(job: RemoteDeployment, target: File) {
    require(job.size in 0..512L * 1024 * 1024)
    val request =
        request("/compose/deployments/${job.id}/artifact").apply {
            method = HttpMethod.Get
            timeout {
                requestTimeoutMillis = 10 * 60_000
                socketTimeoutMillis = 30_000
            }
        }
    try {
        exchange(request) { response ->
            if (response.status.value != 200) throw HostHttpException(response.status.value)
            val input = response.bodyAsChannel()
            var total = 0L
            target.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    total += count
                    if (total > job.size)
                        throw HostProtocolException("Artifact exceeds declared size")
                    output.write(buffer, 0, count)
                }
            }
            if (total != job.size) throw HostProtocolException("Incomplete Artifact download")
        }
    } catch (error: Throwable) {
        target.delete()
        throw error
    }
}
