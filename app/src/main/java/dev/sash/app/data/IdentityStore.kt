package dev.sash.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.sash.protocol.Identity
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This device's P-256 identity: the key every Mac pins. The private key is kept in the app's
 * files, encrypted by an AES key that never leaves the Android Keystore (and is not backed up),
 * so the identity is this install's alone, as the iOS app's Keychain key is.
 */
class IdentityStore(private val context: Context) {
    private val dir = File(context.noBackupFilesDir, "identity").apply { mkdirs() }
    private val keyFile = File(dir, "device.key.enc")
    private val pubFile = File(dir, "device.pub")

    @Volatile private var cached: Identity? = null

    fun identity(): Identity {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val id = load() ?: create()
            cached = id
            return id
        }
    }

    private fun load(): Identity? = try {
        if (!keyFile.exists() || !pubFile.exists()) null
        else Identity.restore(decrypt(keyFile.readBytes()), pubFile.readBytes())
    } catch (e: Exception) {
        null
    }

    private fun create(): Identity {
        val id = Identity.generate()
        keyFile.writeBytes(encrypt(id.exportPrivate()))
        pubFile.writeBytes(id.publicKey.encoded)
        return id
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return g.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        return c.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }

    companion object {
        private const val ALIAS = "sash.identity.wrap"
    }
}
