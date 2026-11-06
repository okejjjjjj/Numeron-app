package fi.oma.numeron

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SettingsManager {
    private fun getPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "numeron_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences("numeron_prefs", Context.MODE_PRIVATE)
        }
    }

    fun isFirstRun(context: Context): Boolean = !getPrefs(context).contains("settings_initialized")

    fun getOrganizationRaw(context: Context): String =
        getPrefs(context).getString("org_domain", "siunsote") ?: "siunsote"

    fun getBaseUrl(context: Context): String {
        val raw = getOrganizationRaw(context).trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        if (raw.isEmpty()) return "https://siunsote.numeronwfm.com"
        return if (raw.contains(".")) "https://$raw" else "https://$raw.numeronwfm.com"
    }

    fun getUsername(context: Context): String = getPrefs(context).getString("username", "") ?: ""
    fun getPassword(context: Context): String = getPrefs(context).getString("password", "") ?: ""

    fun isBiometricEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean("biometric_enabled", true)

    fun isAutoSyncEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean("auto_sync_enabled", true)

    fun isStampNotificationEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean("stamp_notif_enabled", true)

    fun getTargetCalendarId(context: Context): Long =
        getPrefs(context).getLong("target_calendar_id", -1L)

    fun saveAll(
        context: Context,
        org: String,
        user: String,
        pass: String,
        biometric: Boolean,
        autoSync: Boolean,
        stampNotif: Boolean,
        calendarId: Long
    ) {
        getPrefs(context).edit().apply {
            putBoolean("settings_initialized", true)
            putString("org_domain", org.trim().ifEmpty { "siunsote" })
            putString("username", user.trim())
            putString("password", pass)
            putBoolean("biometric_enabled", biometric)
            putBoolean("auto_sync_enabled", autoSync)
            putBoolean("stamp_notif_enabled", stampNotif)
            putLong("target_calendar_id", calendarId)
            apply()
        }
    }
}
