package com.example.sshremote.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 의 AES-256 키로 비밀번호 · 개인키를 AES/GCM 으로 암호화한다.
 * - 키는 하드웨어(TEE/StrongBox 가능 시) 안에 있고 앱 밖으로 꺼낼 수 없다
 * - 저장 형식: "v1:" + Base64(IV) + ":" + Base64(암호문+태그)
 * - 앱을 지우면 키도 사라지므로 저장된 비밀번호는 다시 입력해야 한다
 */
class CryptoManager(private val alias: String = "ssh_remote_secret_key") {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // IV 는 Keystore 가 무작위로 만든다
        val bytes = plain.toByteArray(Charsets.UTF_8)
        try {
            val sealed = cipher.doFinal(bytes)
            val b64 = Base64.getEncoder()
            return "$VERSION:${b64.encodeToString(cipher.iv)}:${b64.encodeToString(sealed)}"
        } finally {
            bytes.fill(0)
        }
    }

    fun decrypt(stored: String): String {
        val parts = stored.split(":")
        require(parts.size == 3 && parts[0] == VERSION) { "알 수 없는 암호문 형식" }
        val b64 = Base64.getDecoder()
        val iv = b64.decode(parts[1])
        val sealed = b64.decode(parts[2])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        val bytes = cipher.doFinal(sealed)
        try {
            return String(bytes, Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VERSION = "v1"
    }
}
