package dev.fughetta.dshcompose.update

import android.content.Context
import dev.fughetta.dshcompose.data.hosts.RemoteHostStore
import dev.fughetta.dshcompose.phone.PhoneControl
import dev.fughetta.dshcompose.protocol.*
import io.ktor.http.Url
import java.io.File
import kotlinx.coroutines.*

/** Receives self-update jobs only through a saved, paired host connection. */
class RemoteUpdates(
    private val app: Context,
    private val hosts: RemoteHostStore,
    scope: CoroutineScope,
) {
    private val store = RemoteInstallStore(app)
    private var connection: HostConnection? = null
    private var identity: Triple<String, String, String>? = null

    init {
        scope.launch {
            // Downloads are disposable; restart can pull a pending artifact again.
            File(app.cacheDir, "compose-self-update").deleteRecursively()
            recoverInstallation(app, store)
            while (isActive) {
                try {
                    step()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    connection?.close()
                    connection = null
                    identity = null
                    // Keep the durable record: a lost response must not cause another installation.
                }
                delay(5_000)
            }
        }
    }

    fun select(host: String) {
        store.select(host.takeIf { it.startsWith("compose-") })
    }

    private suspend fun connect(hostId: String): HostConnection? {
        val host = hosts.load().firstOrNull { it.id == hostId } ?: return null
        val secret = hosts.credential(hostId) ?: return null
        val next = Triple(hostId, host.url, secret)
        if (identity != next) {
            connection?.close()
            val url = Url(host.url)
            val hostname =
                if (':' in url.host && !url.host.startsWith('[')) "[${url.host}]" else url.host
            val authority =
                if (url.port == url.protocol.defaultPort) hostname else "$hostname:${url.port}"
            connection = HostConnection(host.url, credential = HostCredential(secret, authority))
            identity = next
        }
        return connection
    }

    private suspend fun step() {
        recoverInstallation(app, store)
        val pending = store.read()
        val hostId = pending?.host ?: store.selectedHost() ?: return
        if (hosts.load().none { it.id == hostId }) {
            if (pending != null) {
                runCatching {
                    app.packageManager.packageInstaller.abandonSession(pending.installerSession)
                }
                store.acknowledge(pending)
            }
            return
        }
        val transport = connect(hostId) ?: return
        if (pending?.terminal == true) {
            try {
                transport.deploymentReceipt(
                    pending.id,
                    if (pending.status == "installed") "succeeded" else pending.status,
                    pending.message,
                )
            } catch (error: HostRemoteException) {
                // A receipt may have aged out while this client was offline.
                if (error.code != "deploy/not-found") throw error
            }
            store.acknowledge(pending)
            return
        }
        if (pending?.status == "awaiting-install") {
            val control = PhoneControl.get(app)
            if (
                !control.isEnabled() ||
                    control.tasks.snapshot().stopped ||
                    control.environment.isLocked()
            ) {
                runCatching {
                    app.packageManager.packageInstaller.abandonSession(pending.installerSession)
                }
                store.update(pending.id) {
                    it.copy(status = "cancelled", message = "INSTALL_STOPPED")
                }
                return
            }
            transport.deploymentReceipt(pending.id, "deploying", pending.message)
            return
        }
        val control = PhoneControl.get(app)
        if (!control.isEnabled()) return
        val jobs = transport.pollDeployments(control.device().name)
        val job =
            if (pending == null) jobs.firstOrNull() else jobs.firstOrNull { it.id == pending.id }
        if (job == null) {
            if (pending != null) store.acknowledge(pending)
            return
        }
        // Artifact interpretation belongs to this Android client, not the host.
        if (
            job.kind != "file" ||
                job.encoding != "identity" ||
                !job.name.endsWith(".apk", ignoreCase = true)
        ) {
            transport.deploymentReceipt(
                job.id,
                "failed",
                "This client deploys Compose APK files only",
            )
            if (pending != null) store.acknowledge(pending)
            return
        }
        val record = pending ?: RemoteInstallRecord(job.id, hostId, "downloading")
        store.write(record)
        transport.deploymentReceipt(
            job.id,
            "downloading",
            "Downloading deployment artifact",
        )
        val directory = File(app.cacheDir, "compose-self-update").apply { mkdirs() }
        val file = File(directory, "${job.id}.apk")
        try {
            transport.downloadDeployment(job, file)
            val result = runInterruptible(Dispatchers.IO) { control.installRemoteApk(file, record) }
            if (result.isError)
                store.update(job.id) {
                    if (it.terminal) it
                    else
                        it.copy(
                            status = if (it.installerSession >= 0) "unknown" else "failed",
                            message = result.text.take(500),
                        )
                }
        } finally {
            file.delete()
        }
    }
}
