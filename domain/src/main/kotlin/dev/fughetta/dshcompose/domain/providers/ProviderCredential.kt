package dev.fughetta.dshcompose.domain.providers

/** A request-owned secret, excluded from diagnostic strings and cleared when the request ends. */
class ProviderKey private constructor(private val characters: CharArray) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun <T> useCharacters(block: (CharArray) -> T): T {
        check(!closed) { "Credential input was cleared" }
        return block(characters)
    }

    @Synchronized
    override fun close() {
        characters.fill('\u0000')
        closed = true
    }

    override fun toString() = "ProviderKey(<redacted>)"

    companion object {
        fun from(value: CharSequence): ProviderKey {
            require(value.isNotBlank()) { "Credential input must not be blank" }
            return ProviderKey(CharArray(value.length) { value[it] })
        }
    }
}

data class CredentialStatus(val configured: Boolean, val writable: Boolean, val source: String?)

data class ProviderAccount(val id: String, val name: String, val credentialRef: String?)

fun validCredentialRef(ref: String) = ref.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$"))
