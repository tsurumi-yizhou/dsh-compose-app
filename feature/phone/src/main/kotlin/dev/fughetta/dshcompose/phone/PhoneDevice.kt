package dev.fughetta.dshcompose.phone

/** App-owned identity, distinct from a conversation, mux client ID or hardware serial number. */
data class PhoneDevice(val id: String, val name: String) {
    fun toMap(): Map<String, String> = mapOf("deviceId" to id, "name" to name)
}
