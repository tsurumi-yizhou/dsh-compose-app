package dev.fughetta.dshcompose.data.hosts

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.fughetta.dshcompose.domain.hosts.RemoteHost
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore encryption binds a device grant to one saved service URL. */
internal class DeviceCredentialCipher(private val keySource: (() -> SecretKey)? = null) {
    fun encrypt(host: RemoteHost, credential: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(identity(host))
        val plain = credential.toByteArray(Charsets.UTF_8)
        return try {
            Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain))
        } finally {
            plain.fill(0)
        }
    }

    fun decrypt(host: RemoteHost, encrypted: String): String {
        val value = Base64.getDecoder().decode(encrypted)
        require(value.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0, 12)))
        cipher.updateAAD(identity(host))
        val plain = cipher.doFinal(value, 12, value.size - 12)
        return try {
            plain.toString(Charsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    fun clearRetiredKey() =
        synchronized(gate) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias("dsh-ssh-credentials")) store.deleteEntry("dsh-ssh-credentials")
        }

    private fun identity(host: RemoteHost) =
        listOf(host.id, host.url)
            .joinToString("") { "${it.length}:$it" }
            .toByteArray(Charsets.UTF_8)

    private fun key(): SecretKey =
        synchronized(gate) {
            keySource?.let {
                return@synchronized it()
            }
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)
                ?: KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        "AndroidKeyStore",
                    )
                    .run {
                        init(
                            KeyGenParameterSpec.Builder(
                                    alias,
                                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                                )
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setKeySize(256)
                                .build(),
                        )
                        generateKey()
                    }
        }

    private companion object {
        const val alias = "compose-device-credentials"
        val gate = Any()
    }
}
