package dev.zain.releaseshelf.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encrypted string values via Android Keystore.
 * Prefer one store instance per preferences file / key alias.
 */
class EncryptedStringStore(
    context: Context,
    preferencesName: String,
    private val keyAlias: String,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun get(key: String): String {
        val ciphertext = preferences.getString("${key}_ct", null) ?: return ""
        val iv = preferences.getString("${key}_iv", null) ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse {
            clear(key)
            ""
        }
    }

    fun set(key: String, value: String) {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            clear(key)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        preferences.edit {
            putString("${key}_ct", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            putString("${key}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
    }

    fun clear(key: String) {
        preferences.edit {
            remove("${key}_ct")
            remove("${key}_iv")
        }
    }

    fun has(key: String): Boolean = get(key).isNotBlank()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
