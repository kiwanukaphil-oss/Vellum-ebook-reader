package app.vellum.reader.shared

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Stores the optional Shared Library session encrypted with a non-exportable
 * Android Keystore key. Local reading does not depend on this store.
 */
class SecureSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(): SharedAuthSession? {
        val encoded = preferences.getString(SESSION, null) ?: return null
        return runCatching {
            val envelope = JSONObject(encoded)
            val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
            val ciphertext = Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8))
            SharedAuthSession(
                accessToken = json.getString("accessToken"),
                refreshToken = json.getString("refreshToken"),
                expiresAtEpochSeconds = json.getLong("expiresAt"),
                userId = json.getString("userId"),
                email = json.getString("email"),
            )
        }.getOrElse {
            clear()
            null
        }
    }

    fun write(session: SharedAuthSession) {
        val plaintext = JSONObject()
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("expiresAt", session.expiresAtEpochSeconds)
            .put("userId", session.userId)
            .put("email", session.email)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val envelope = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP))
        preferences.edit().putString(SESSION, envelope.toString()).apply()
    }

    fun clear() {
        preferences.edit().remove(SESSION).apply()
    }

    fun readPendingInvitation(): String? =
        preferences.getString(PENDING_INVITATION, null)?.let { encoded ->
            runCatching {
                val envelope = JSONObject(encoded)
                val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
                val ciphertext = Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
            }.getOrNull()
        }

    fun writePendingInvitation(code: String?) {
        if (code.isNullOrBlank()) {
            preferences.edit().remove(PENDING_INVITATION).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val envelope = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put(
                "ciphertext",
                Base64.encodeToString(
                    cipher.doFinal(code.toByteArray(StandardCharsets.UTF_8)),
                    Base64.NO_WRAP,
                ),
            )
        preferences.edit().putString(PENDING_INVITATION, envelope.toString()).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "vellum_shared_auth"
        const val SESSION = "session"
        const val PENDING_INVITATION = "pending_invitation"
        const val KEY_ALIAS = "vellum_shared_session_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
