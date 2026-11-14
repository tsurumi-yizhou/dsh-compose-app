package dev.fughetta.dshcompose.update

import android.content.Context
import org.json.JSONObject

data class RemoteInstallRecord(
    val id: String,
    val host: String,
    val status: String,
    val message: String = "",
    val installerSession: Int = -1,
    val version: Long = 0,
    val previousUpdate: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
) {
    val terminal: Boolean
        get() = status in setOf("installed", "failed", "cancelled", "unknown")
}

/** A single durable hand-off survives replacement of the app process. */
class RemoteInstallStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("compose_self_update", Context.MODE_PRIVATE)

    fun read(): RemoteInstallRecord? =
        synchronized(lock) {
            preferences.getString("pending", null)?.let {
                val json = JSONObject(it)
                RemoteInstallRecord(
                    json.getString("id"),
                    json.getString("host"),
                    json.getString("status"),
                    json.optString("message"),
                    json.optInt("installerSession", -1),
                    json.optLong("version"),
                    json.optLong("previousUpdate"),
                    json.getLong("startedAt"),
                )
            }
        }

    fun write(record: RemoteInstallRecord) =
        synchronized(lock) {
            val json =
                JSONObject()
                    .put("id", record.id)
                    .put("host", record.host)
                    .put("status", record.status)
                    .put("message", record.message)
                    .put("installerSession", record.installerSession)
                    .put("version", record.version)
                    .put("previousUpdate", record.previousUpdate)
                    .put("startedAt", record.startedAt)
            check(preferences.edit().putString("pending", json.toString()).commit()) {
                "Cannot persist installation state"
            }
        }

    fun update(id: String, transform: (RemoteInstallRecord) -> RemoteInstallRecord) =
        synchronized(lock) { read()?.takeIf { it.id == id }?.let { write(transform(it)) } }

    fun acknowledge(record: RemoteInstallRecord) =
        synchronized(lock) {
            if (read() == record) check(preferences.edit().remove("pending").commit())
        }

    fun selectedHost(): String? = preferences.getString("selectedHost", null)

    fun select(host: String?) {
        check(preferences.edit().putString("selectedHost", host).commit())
    }

    companion object {
        private val lock = Any()
    }
}
