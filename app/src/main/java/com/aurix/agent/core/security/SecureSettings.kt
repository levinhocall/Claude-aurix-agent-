package com.aurix.agent.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class ProviderConfig(val baseUrl: String, val model: String, val apiKey: String)

/** API key + provider config, encrypted with a Keystore-backed master key. The key is never exposed to UI or logs. */
@Singleton
class SecureSettings @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs: SharedPreferences by lazy {
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "aurix_secure_prefs", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun baseUrl(): String = prefs.getString(K_URL, DEFAULT_URL) ?: DEFAULT_URL
    fun model(): String = prefs.getString(K_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun hasKey(): Boolean = !prefs.getString(K_KEY, null).isNullOrBlank()

    fun load(): ProviderConfig? {
        val key = prefs.getString(K_KEY, null)
        if (key.isNullOrBlank()) return null
        return ProviderConfig(baseUrl(), model(), key)
    }

    /** Blank apiKey keeps the existing key. */
    fun save(baseUrl: String, model: String, apiKey: String?) {
        prefs.edit().apply {
            putString(K_URL, baseUrl.trim())
            putString(K_MODEL, model.trim())
            if (!apiKey.isNullOrBlank()) putString(K_KEY, apiKey.trim())
        }.apply()
    }

    fun getString(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }

    private companion object {
        const val K_URL = "base_url"
        const val K_MODEL = "model"
        const val K_KEY = "api_key"
        const val DEFAULT_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}
