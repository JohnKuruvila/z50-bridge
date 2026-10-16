package io.github.johnkuruvila.z50bridge.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the ingest bearer token and the camera Wi-Fi password with a
 * hardware-backed Android Keystore key (AES/GCM) before they land in DataStore.
 *
 * New values are written with a [PREFIX] tag. Values written before this class
 * existed have no prefix: [decrypt] tries to decrypt them and otherwise treats
 * them as a plaintext token, so legacy plaintext keeps working and is upgraded
 * to ciphertext on the next save.
 *
 * [encrypt] deliberately **throws** instead of falling back to plaintext. A
 * silent fallback would store the secret in the clear and would hide a Keystore
 * failure from the user; the save path in
 * [io.github.johnkuruvila.z50bridge.data.SettingsRepository.update] lets the
 * exception surface so the change is rejected rather than downgraded.
 */
class TokenCipher(private val alias: String = DEFAULT_ALIAS) {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (stored.startsWith(PREFIX)) {
            // Written by us: it must decrypt. A failure means corruption or a
            // key that is gone, and the value is not usable as a credential.
            return decode(stored.removePrefix(PREFIX))
        }
        // Legacy value from before this class existed. It is either a plaintext
        // token or ciphertext in the untagged format; try ciphertext first.
        return try {
            decode(stored)
        } catch (_: Exception) {
            stored
        }
    }

    private fun decode(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IV_BYTES) { "stored secret is too short to be ciphertext" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES))
        return String(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "z50_bridge_token"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** Marks ciphertext written by this class, so failures are not silent. */
        private const val PREFIX = "v1:"
    }
}
