// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bluebubbles.messaging.services.rcs

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The only persisted account state is an authenticated, encrypted private file. */
internal class SessionVault(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "rcs-paired-session.enc"))
    private val alias = "openbubbles-rcs-session-v1"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val aad = "com.openbubbles/rcs/session/v1".toByteArray(Charsets.UTF_8)

    @Synchronized
    fun load(): String? {
        val input = try { file.openRead() } catch (_: FileNotFoundException) { return null }
        val encrypted = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_SESSION_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(encrypted.size in 30..MAX_SESSION_BYTES && encrypted[0] == 1.toByte())
        val key = keyStore.getKey(alias, null) as? SecretKey ?: error("Session key missing")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        val plain = cipher.doFinal(encrypted, 13, encrypted.size - 13)
        return try { plain.toString(Charsets.UTF_8) } finally { plain.fill(0) }
    }

    @Synchronized
    fun save(session: String) {
        val plain = session.toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_SESSION_BYTES - 29)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, existingOrNewKey())
        cipher.updateAAD(aad)
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        require(cipher.iv.size == 12)
        val stream = file.startWrite()
        try {
            stream.write(1)
            stream.write(cipher.iv)
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun existingOrNewKey(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    companion object { private const val MAX_SESSION_BYTES = 4 * 1024 * 1024 }
}
