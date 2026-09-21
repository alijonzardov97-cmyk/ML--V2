package com.privacy.imsidetector.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Improvement #6 (encrypt local history at rest): generates a random
 * SQLCipher passphrase once, on first launch, and stores it inside
 * EncryptedSharedPreferences — which itself is protected by a key that
 * never leaves the Android Keystore (hardware-backed where available).
 * The passphrase is never hardcoded and never logged.
 */
object DbKeyStore {

    private const val PREFS_NAME = "imsi_detector_secure_prefs"
    private const val KEY_PASSPHRASE = "db_passphrase"

    fun getOrCreatePassphrase(context: Context): CharArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val existing = prefs.getString(KEY_PASSPHRASE, null)
        if (existing != null) return existing.toCharArray()

        val fresh = generateRandomPassphrase()
        prefs.edit().putString(KEY_PASSPHRASE, String(fresh)).apply()
        return fresh
    }

    private fun generateRandomPassphrase(length: Int = 32): CharArray {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return CharArray(length) { chars[random.nextInt(chars.length)] }
    }
}
