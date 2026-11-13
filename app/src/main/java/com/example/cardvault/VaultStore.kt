package com.example.cardvault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Local-only encrypted vault container. Plaintext is returned only while the caller is unlocked. */
object VaultStore {
    private const val FILE_NAME = "vault.dat"
    private const val KEY_ALIAS = "card_vault_biometric_wrap_v1"
    private const val ITERATIONS = 600_000
    private const val KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val SALT_BYTES = 16
    private const val MIN_BACKUP_ITERATIONS = 100_000
    private const val MAX_BACKUP_ITERATIONS = 2_000_000
    private const val MAX_BACKUP_BYTES = 8 * 1024 * 1024

    data class Entry(
        val id: String,
        val kind: String,
        val title: String,
        val cardholderName: String,
        val username: String,
        val secret: String,
        val cardNumber: String,
        val expiry: String,
        val cvv: String,
        val notes: String,
        val customFields: Map<String, String> = emptyMap()
    ) {
        fun toJson() = JSONObject().apply {
            put("id", id); put("kind", kind); put("title", title); put("cardholderName", cardholderName); put("username", username)
            put("secret", secret); put("cardNumber", cardNumber); put("expiry", expiry)
            put("cvv", cvv); put("notes", notes)
            put("customFields", JSONObject().apply { customFields.forEach { (key, value) -> put(key, value) } })
        }
        companion object {
            fun fromJson(j: JSONObject) = Entry(j.getString("id"), j.getString("kind"), j.getString("title"), j.optString("cardholderName"),
                j.optString("username"), j.optString("secret"), j.optString("cardNumber"),
                j.optString("expiry"), j.optString("cvv"), j.optString("notes"),
                buildMap { val fields = j.optJSONObject("customFields") ?: JSONObject(); fields.keys().forEach { put(it, fields.optString(it)) } })
        }
    }

    data class Session(val dek: ByteArray, val entries: MutableList<Entry>)
    data class SetupResult(val recoveryKey: String, val session: Session)
    data class BiometricPayload(val iv: ByteArray, val ciphertext: ByteArray)
    data class BackupInfo(val iterations: Int, val bytes: Int)

    fun exists(context: Context) = file(context).exists()

    fun create(context: Context, masterPassword: CharArray): SetupResult {
        require(masterPassword.size >= 12) { "Use at least 12 characters for the master password." }
        val dekBytes = randomBytes(KEY_BYTES)
        val recoveryBytes = randomBytes(KEY_BYTES)
        val recovery = recoveryBytes.joinToString("") { "%02X".format(it) }
        val entries = mutableListOf<Entry>()
        writeEnvelope(context, dekBytes, masterPassword, recoveryBytes, entries)
        return SetupResult(recovery, Session(dekBytes, entries))
    }

    fun unlockWithPassword(context: Context, password: CharArray): Session {
        val envelope = readEnvelope(context)
        val salt = decode(envelope.getString("passwordSalt"))
        val key = derive(password, salt, envelope.optInt("iterations", ITERATIONS))
        val dek = decrypt(key, decode(envelope.getString("passwordIv")), decode(envelope.getString("passwordWrap")))
        return Session(dek, decryptEntries(envelope, dek))
    }

    fun unlockWithRecovery(context: Context, recoveryKey: String): Session {
        val envelope = readEnvelope(context)
        val recoveryBytes = hexToBytes(recoveryKey)
        require(recoveryBytes.size == KEY_BYTES) { "Recovery key format is not valid." }
        val dek = decrypt(SecretKeySpec(recoveryBytes, "AES"), decode(envelope.getString("recoveryIv")), decode(envelope.getString("recoveryWrap")))
        return Session(dek, decryptEntries(envelope, dek))
    }

    fun resetPassword(context: Context, oldRecoveryKey: String, newPassword: CharArray): Pair<String, Session> {
        require(newPassword.size >= 12) { "Use at least 12 characters for the master password." }
        val envelope = readEnvelope(context)
        val oldRecovery = hexToBytes(oldRecoveryKey)
        val dek = decrypt(SecretKeySpec(oldRecovery, "AES"), decode(envelope.getString("recoveryIv")), decode(envelope.getString("recoveryWrap")))
        val newRecoveryBytes = randomBytes(KEY_BYTES)
        val entries = decryptEntries(envelope, dek)
        writeEnvelope(context, dek, newPassword, newRecoveryBytes, entries)
        clearBiometric(context)
        return newRecoveryBytes.joinToString("") { "%02X".format(it) } to Session(dek, entries)
    }

    fun save(context: Context, entries: List<Entry>, dek: ByteArray) {
        val envelope = readEnvelope(context)
        val data = JSONObject().put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } }).toString().toByteArray(Charsets.UTF_8)
        val (iv, cipherText) = encrypt(SecretKeySpec(dek, "AES"), data)
        envelope.put("dataIv", encode(iv)).put("data", encode(cipherText))
        persist(context, envelope)
    }

    fun rotateRecoveryKey(context: Context, dek: ByteArray): String {
        val envelope = readEnvelope(context)
        val bytes = randomBytes(KEY_BYTES)
        val (iv, wrapped) = encrypt(SecretKeySpec(bytes, "AES"), dek)
        envelope.put("recoveryIv", encode(iv)).put("recoveryWrap", encode(wrapped))
        persist(context, envelope)
        return bytes.joinToString("") { "%02X".format(it) }
    }

    fun newBiometricEncryptCipher(): Cipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true).setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true).build())
        val key = generator.generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    fun saveBiometricPayload(context: Context, iv: ByteArray, ciphertext: ByteArray) {
        val p = context.getSharedPreferences("biometric_wrap", Context.MODE_PRIVATE)
        p.edit().putString("bioIv", encode(iv)).putString("bioCipher", encode(ciphertext)).apply()
    }

    fun biometricDecryptCipher(payload: BiometricPayload): Cipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(KEY_ALIAS, null) as SecretKey
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, payload.iv)) }
    }

    fun biometricPayload(context: Context): BiometricPayload? {
        val p = context.getSharedPreferences("biometric_wrap", Context.MODE_PRIVATE)
        if (!p.contains("bioIv") || !p.contains("bioCipher")) return null
        return BiometricPayload(decode(p.getString("bioIv", "")!!), decode(p.getString("bioCipher", "")!!))
    }
    fun decryptBiometric(cipher: Cipher, payload: BiometricPayload): ByteArray = cipher.doFinal(payload.ciphertext)
    fun clearBiometric(context: Context) {
        context.getSharedPreferences("biometric_wrap", Context.MODE_PRIVATE).edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }
    fun entriesWithDek(context: Context, dek: ByteArray): MutableList<Entry> = decryptEntries(readEnvelope(context), dek)

    /** The exact encrypted envelope stored on disk, ready to move to another phone. */
    fun backupBytes(context: Context): ByteArray {
        val source = file(context)
        require(source.exists()) { "There is no vault on this device to export." }
        val bytes = source.readBytes()
        validateBackup(bytes)
        return bytes
    }

    /**
     * Checks that [bytes] is a complete, supported Aura envelope before it may
     * replace the local vault. Ciphertext is authenticated at unlock; this
     * rejects empty, oversized, truncated or foreign files up front.
     */
    fun validateBackup(bytes: ByteArray): BackupInfo {
        require(bytes.isNotEmpty()) { "The backup file is empty." }
        require(bytes.size <= MAX_BACKUP_BYTES) { "The backup file is too large to be an Aura backup." }
        val envelope = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            throw IllegalArgumentException("This file is not an Aura Vault backup.")
        }
        require(envelope.optInt("version", -1) == 1) { "This backup uses an unsupported format version." }
        require(envelope.optString("kdf") == "PBKDF2WithHmacSHA256") { "This backup uses an unsupported key derivation method." }
        val iterations = envelope.optInt("iterations", -1)
        require(iterations in MIN_BACKUP_ITERATIONS..MAX_BACKUP_ITERATIONS) { "This backup's key derivation settings are invalid." }
        requireField(envelope, "passwordSalt", SALT_BYTES)
        requireField(envelope, "passwordIv", GCM_IV_BYTES)
        requireField(envelope, "passwordWrap", KEY_BYTES + GCM_TAG_BITS / 8)
        requireField(envelope, "recoveryIv", GCM_IV_BYTES)
        requireField(envelope, "recoveryWrap", KEY_BYTES + GCM_TAG_BITS / 8)
        requireField(envelope, "dataIv", GCM_IV_BYTES)
        requireField(envelope, "data", minimumBytes = GCM_TAG_BITS / 8)
        return BackupInfo(iterations, bytes.size)
    }

    /** Atomically replaces the local vault with a validated backup. */
    fun importBackup(context: Context, bytes: ByteArray): BackupInfo {
        val info = validateBackup(bytes)
        persistBytes(context, bytes)
        return info
    }

    private fun requireField(envelope: JSONObject, name: String, expectedBytes: Int? = null, minimumBytes: Int = 1) {
        val value = envelope.optString(name, "")
        require(value.isNotEmpty()) { "The backup is missing its $name field." }
        val bytes = try {
            decode(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("The backup's $name field is damaged.")
        }
        // Android's decoder is lenient; require the canonical form written by
        // this app so damaged or hand-edited files are rejected here.
        require(encode(bytes) == value) { "The backup's $name field is damaged." }
        if (expectedBytes != null) {
            require(bytes.size == expectedBytes) { "The backup's $name field is damaged." }
        } else {
            require(bytes.size >= minimumBytes) { "The backup's $name field is damaged." }
        }
    }

    private fun writeEnvelope(context: Context, dek: ByteArray, password: CharArray, recovery: ByteArray, entries: List<Entry>) {
        val salt = randomBytes(SALT_BYTES)
        val passwordKey = derive(password, salt)
        val (pIv, pWrap) = encrypt(passwordKey, dek)
        val (rIv, rWrap) = encrypt(SecretKeySpec(recovery, "AES"), dek)
        val data = JSONObject().put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } }).toString().toByteArray(Charsets.UTF_8)
        val (dataIv, dataCipher) = encrypt(SecretKeySpec(dek, "AES"), data)
        val envelope = JSONObject().put("version", 1).put("kdf", "PBKDF2WithHmacSHA256")
            .put("iterations", ITERATIONS).put("passwordSalt", encode(salt)).put("passwordIv", encode(pIv))
            .put("passwordWrap", encode(pWrap)).put("recoveryIv", encode(rIv)).put("recoveryWrap", encode(rWrap))
            .put("dataIv", encode(dataIv)).put("data", encode(dataCipher))
        persist(context, envelope)
    }
    private fun decryptEntries(envelope: JSONObject, dek: ByteArray): MutableList<Entry> {
        val plain = decrypt(SecretKeySpec(dek, "AES"), decode(envelope.getString("dataIv")), decode(envelope.getString("data")))
        val array = JSONObject(String(plain, Charsets.UTF_8)).getJSONArray("entries")
        return MutableList(array.length()) { Entry.fromJson(array.getJSONObject(it)) }
    }
    private fun derive(password: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BYTES * 8)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
    private fun encrypt(key: SecretKey, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv to cipher.doFinal(plaintext)
    }
    private fun decrypt(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
    private fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
    private fun readEnvelope(context: Context) = JSONObject(file(context).readText(Charsets.UTF_8))
    private fun persist(context: Context, envelope: JSONObject) {
        persistBytes(context, envelope.toString().toByteArray(Charsets.UTF_8))
    }
    private fun persistBytes(context: Context, bytes: ByteArray) {
        val atomic = AtomicFile(file(context))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            if (stream != null) atomic.failWrite(stream)
            throw t
        }
    }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(s: String) = Base64.decode(s, Base64.NO_WRAP)
    private fun hexToBytes(value: String): ByteArray {
        val clean = value.filterNot { it.isWhitespace() || it == '-' }
        require(clean.length == KEY_BYTES * 2 && clean.all { it.digitToIntOrNull(16) != null }) { "Recovery key format is not valid." }
        return ByteArray(KEY_BYTES) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
