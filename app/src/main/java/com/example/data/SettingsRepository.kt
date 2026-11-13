package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.syncBeatDataStore: DataStore<Preferences> by preferencesDataStore(name = "syncbeat_prefs")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val NICKNAME = stringPreferencesKey("nickname")
        val AVATAR_COLOR = stringPreferencesKey("avatar_color")
        val CALIBRATION_OFFSET_MS = intPreferencesKey("calibration_offset_ms")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val DRIFT_SENSITIVITY_MS = intPreferencesKey("drift_sensitivity_ms")
        val CHAT_DUCKING_ENABLED = booleanPreferencesKey("chat_ducking_enabled")
        val CHAT_DUCKING_REDUCTION_PERCENT = intPreferencesKey("chat_ducking_reduction_percent")
    }

    val settingsFlow: Flow<UserSettings> = context.syncBeatDataStore.data.map { prefs ->
        UserSettings(
            nickname = prefs[Keys.NICKNAME] ?: defaultDeviceName(),
            avatarColorHex = prefs[Keys.AVATAR_COLOR] ?: "#8B5CF6",
            calibrationOffsetMs = prefs[Keys.CALIBRATION_OFFSET_MS] ?: 0,
            autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
            driftSensitivityMs = prefs[Keys.DRIFT_SENSITIVITY_MS] ?: 22,
            chatDuckingEnabled = prefs[Keys.CHAT_DUCKING_ENABLED] ?: false,
            chatDuckingReductionPercent = (prefs[Keys.CHAT_DUCKING_REDUCTION_PERCENT] ?: 35).coerceIn(15, 65)
        )
    }

    suspend fun updateNickname(nickname: String) {
        val clean = nickname.trim().take(24).ifEmpty { defaultDeviceName() }
        context.syncBeatDataStore.edit { it[Keys.NICKNAME] = clean }
    }

    suspend fun updateAvatarColor(hex: String) {
        context.syncBeatDataStore.edit { it[Keys.AVATAR_COLOR] = hex }
    }

    suspend fun updateCalibrationOffset(offsetMs: Int) {
        context.syncBeatDataStore.edit { it[Keys.CALIBRATION_OFFSET_MS] = offsetMs.coerceIn(-200, 200) }
    }

    suspend fun updateAutoReconnect(enabled: Boolean) {
        context.syncBeatDataStore.edit { it[Keys.AUTO_RECONNECT] = enabled }
    }

    suspend fun updateDriftSensitivity(ms: Int) {
        context.syncBeatDataStore.edit { it[Keys.DRIFT_SENSITIVITY_MS] = ms.coerceIn(12, 60) }
    }

    suspend fun updateChatDuckingEnabled(enabled: Boolean) {
        context.syncBeatDataStore.edit { it[Keys.CHAT_DUCKING_ENABLED] = enabled }
    }

    suspend fun updateChatDuckingReductionPercent(percent: Int) {
        context.syncBeatDataStore.edit { it[Keys.CHAT_DUCKING_REDUCTION_PERCENT] = percent.coerceIn(15, 65) }
    }

    private fun defaultDeviceName(): String {
        val model = android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        return model.take(18)
    }
}
