package dev.ticbox.companion.platform.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.ticbox.companion.engine.cloud.*
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Session/profile encrypted under a non-exportable AndroidKeyStore AES key; no password storage. */
class AndroidMobvoiCredentialStore(context: Context, private val keyAlias: String = "ticbox.mobvoi.account.v1") : MobvoiCredentialStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "mobvoi-account.bin"))
    // A closing runtime can still be finishing an AtomicFile/KeyStore call on IO when the
    // service is recreated. Serialize all instances so they never race the same alias/file.
    private val lock = processLock
    override suspend fun load(): MobvoiCloudCredential? = withContext(Dispatchers.IO) { lock.withLock {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withLock null
        val bytes = file.openRead().use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) { val count = input.read(buffer); if (count < 0) break
                check(count <= 256 * 1024 - out.size()) { "Encrypted account store budget exceeded" }; out.write(buffer, 0, count) }
            out.toByteArray()
        }
        check(bytes.size in 18..(256 * 1024) && bytes[0] == 1.toByte()) { "Unsupported encrypted account store" }
        val ivSize = bytes[1].toInt() and 255
        check(ivSize in 12..32 && bytes.size > ivSize + 2) { "Invalid account store envelope" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(keyAlias, null) as? SecretKey ?: error("Account key unavailable; preserve ciphertext until explicit sign-out")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(2, 2 + ivSize)))
        cipher.updateAAD(keyAlias.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(bytes.copyOfRange(2 + ivSize, bytes.size))
        try { MobvoiCloudJson.credential(plain) } finally { plain.fill(0) }
    } }
    override suspend fun save(credential: MobvoiCloudCredential) = withContext(Dispatchers.IO) { lock.withLock {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (store.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build()); generateKey()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(keyAlias.toByteArray(Charsets.UTF_8))
        val plain = MobvoiCloudJson.credentialBytes(credential)
        val encrypted = try { check(plain.size <= 250 * 1024) { "Account profile storage budget exceeded" }; cipher.doFinal(plain) } finally { plain.fill(0) }
        val output = file.startWrite()
        try { output.write(byteArrayOf(1, cipher.iv.size.toByte()) + cipher.iv + encrypted); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    } }
    override suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock { file.delete(); check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) { "Account store deletion failed" } } }
    private companion object { val processLock = Mutex() }
}
