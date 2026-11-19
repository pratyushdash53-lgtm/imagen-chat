package app.imagen.chat.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores provider settings; the API key lives in encrypted preferences. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // Keystore unavailable on some devices; fall back to app-private preferences.
        context.getSharedPreferences("settings_fallback", Context.MODE_PRIVATE)
    }

    fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            baseUrl = prefs.getString("base_url", defaults.baseUrl) ?: defaults.baseUrl,
            apiKey = prefs.getString("api_key", "") ?: "",
            model = prefs.getString("model", "auto") ?: "auto",
            quality = prefs.getString("quality", "auto") ?: "auto",
            transparentDefault = prefs.getBoolean("transparent_default", false),
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString("base_url", settings.baseUrl)
            .putString("api_key", settings.apiKey)
            .putString("model", settings.model)
            .putString("quality", settings.quality)
            .putBoolean("transparent_default", settings.transparentDefault)
            .apply()
    }
}
