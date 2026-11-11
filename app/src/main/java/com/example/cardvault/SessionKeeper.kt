package com.example.cardvault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Short-lived "recently unlocked" token.
 *
 * The raw vault key (DEK) never touches disk in plaintext. It is wrapped with
 * a non-exportable Android Keystore AES key and stored as ciphertext. If the
 * OS kills the app process while the camera or photo picker is in front (which
 * is exactly what happens on some OEM phones), the next launch can restore the
 * session instead of forcing a password unlock.
 *
 * The token expires after [RESUME_WINDOW_MS] and is erased on explicit lock or
 * when the user leaves the app. This is deliberately a short window, not a
 * "remember me forever" switch.
 */
object SessionKeeper {
    private const val KEY_ALIAS = "card_vault_resume_v1"
    private const val PREFS = "resume_session"
    private const val RESUME_WINDOW_MS = 15 * 60 * 1000L

    fun save(context: Context, dek: ByteArray) {
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.doFinal(dek)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("ct", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putLong("at", System.currentTimeMillis())
                .apply()
        }
    }

    fun restore(context: Context): ByteArray? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = prefs.getString("iv", null) ?: return null
        val ciphertext = prefs.getString("ct", null) ?: return null
        val savedAt = prefs.getLong("at", 0L)
        if (System.currentTimeMillis() - savedAt > RESUME_WINDOW_MS) {
            clear(context)
            return null
        }
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        }.getOrElse {
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
