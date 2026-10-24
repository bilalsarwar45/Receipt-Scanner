package com.receiptscan.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Ordinary preferences (DataStore). Nothing here is synced or backed up. */
data class Settings(
    val skin: String = "SIMPLE",
    val themeMode: String = "SYSTEM",
    val reduceMotion: Boolean = false,
    val biggerAmounts: Boolean = false,
    val currency: String = "PKR",
    /** DMY or MDY for ambiguous printed dates. */
    val dateOrder: String = "DMY",
    val zoneId: String? = null,
    val showTips: Boolean = true,
    val dismissedTips: Set<String> = emptySet(),
    val recentTools: List<String> = emptyList(),
    val keepPhotos: Boolean = true,
    val autoDeleteDays: Int = 0,
    val wifiOnlyDownloads: Boolean = true,
    val voiceQuickAdd: Boolean = false,
    val allowSystemSpeech: Boolean = false,
    val notificationsAsked: Boolean = false,
    val cameraAsked: Boolean = false,
    val enhanceByDefault: Boolean = true,
    val autoCapture: Boolean = true,
    val defaultCategory: String = "uncategorized",
    val translateTarget: String = "en",
    val budgetAlerts: Boolean = true,
    val onboarded: Boolean = false,
    val micAsked: Boolean = false,
)

class AppPreferences(private val context: Context) {
    private object K {
        val skin = stringPreferencesKey("skin")
        val theme = stringPreferencesKey("theme")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val bigger = booleanPreferencesKey("bigger_amounts")
        val currency = stringPreferencesKey("currency")
        val dateOrder = stringPreferencesKey("date_order")
        val zone = stringPreferencesKey("zone")
        val showTips = booleanPreferencesKey("show_tips")
        val dismissed = stringSetPreferencesKey("dismissed_tips")
        val recent = stringPreferencesKey("recent_tools")
        val keepPhotos = booleanPreferencesKey("keep_photos")
        val autoDelete = intPreferencesKey("auto_delete_days")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val quickAdd = booleanPreferencesKey("voice_quick_add")
        val systemSpeech = booleanPreferencesKey("allow_system_speech")
        val notifAsked = booleanPreferencesKey("notifications_asked")
        val cameraAsked = booleanPreferencesKey("camera_asked")
        val enhance = booleanPreferencesKey("enhance_default")
        val autoCapture = booleanPreferencesKey("auto_capture")
        val defaultCategory = stringPreferencesKey("default_category")
        val translateTarget = stringPreferencesKey("translate_target")
        val budgetAlerts = booleanPreferencesKey("budget_alerts")
        val onboarded = booleanPreferencesKey("onboarded")
        val micAsked = booleanPreferencesKey("mic_asked")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            skin = p[K.skin] ?: "SIMPLE",
            themeMode = p[K.theme] ?: "SYSTEM",
            reduceMotion = p[K.reduceMotion] ?: false,
            biggerAmounts = p[K.bigger] ?: false,
            currency = p[K.currency] ?: "PKR",
            dateOrder = p[K.dateOrder] ?: "DMY",
            zoneId = p[K.zone],
            showTips = p[K.showTips] ?: true,
            dismissedTips = p[K.dismissed] ?: emptySet(),
            recentTools = (p[K.recent] ?: "").split(',').filter { it.isNotBlank() },
            keepPhotos = p[K.keepPhotos] ?: true,
            autoDeleteDays = p[K.autoDelete] ?: 0,
            wifiOnlyDownloads = p[K.wifiOnly] ?: true,
            voiceQuickAdd = p[K.quickAdd] ?: false,
            allowSystemSpeech = p[K.systemSpeech] ?: false,
            notificationsAsked = p[K.notifAsked] ?: false,
            cameraAsked = p[K.cameraAsked] ?: false,
            enhanceByDefault = p[K.enhance] ?: true,
            autoCapture = p[K.autoCapture] ?: true,
            defaultCategory = p[K.defaultCategory] ?: "uncategorized",
            translateTarget = p[K.translateTarget] ?: "en",
            budgetAlerts = p[K.budgetAlerts] ?: true,
            onboarded = p[K.onboarded] ?: false,
            micAsked = p[K.micAsked] ?: false,
        )
    }

    suspend fun setSkin(v: String) = context.dataStore.edit { it[K.skin] = v }
    suspend fun setTheme(v: String) = context.dataStore.edit { it[K.theme] = v }
    suspend fun setReduceMotion(v: Boolean) = context.dataStore.edit { it[K.reduceMotion] = v }
    suspend fun setBiggerAmounts(v: Boolean) = context.dataStore.edit { it[K.bigger] = v }
    suspend fun setCurrency(v: String) = context.dataStore.edit { it[K.currency] = v }
    suspend fun setDateOrder(v: String) = context.dataStore.edit { it[K.dateOrder] = v }
    suspend fun setZone(v: String?) = context.dataStore.edit { if (v == null) it.remove(K.zone) else it[K.zone] = v }
    suspend fun setShowTips(v: Boolean) = context.dataStore.edit {
        it[K.showTips] = v
        if (v) it[K.dismissed] = emptySet()
    }
    suspend fun dismissTip(module: String) = context.dataStore.edit { it[K.dismissed] = (it[K.dismissed] ?: emptySet()) + module }
    suspend fun touchTool(id: String) = context.dataStore.edit {
        val list = listOf(id) + (it[K.recent] ?: "").split(',').filter { s -> s.isNotBlank() && s != id }
        it[K.recent] = list.take(6).joinToString(",")
    }
    suspend fun setKeepPhotos(v: Boolean) = context.dataStore.edit { it[K.keepPhotos] = v }
    suspend fun setAutoDeleteDays(v: Int) = context.dataStore.edit { it[K.autoDelete] = v }
    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[K.wifiOnly] = v }
    suspend fun setVoiceQuickAdd(v: Boolean) = context.dataStore.edit { it[K.quickAdd] = v }
    suspend fun setAllowSystemSpeech(v: Boolean) = context.dataStore.edit { it[K.systemSpeech] = v }
    suspend fun setNotificationsAsked() = context.dataStore.edit { it[K.notifAsked] = true }
    suspend fun setCameraAsked() = context.dataStore.edit { it[K.cameraAsked] = true }
    suspend fun setEnhance(v: Boolean) = context.dataStore.edit { it[K.enhance] = v }
    suspend fun setAutoCapture(v: Boolean) = context.dataStore.edit { it[K.autoCapture] = v }
    suspend fun setDefaultCategory(v: String) = context.dataStore.edit { it[K.defaultCategory] = v }
    suspend fun setTranslateTarget(v: String) = context.dataStore.edit { it[K.translateTarget] = v }
    suspend fun setBudgetAlerts(v: Boolean) = context.dataStore.edit { it[K.budgetAlerts] = v }
    suspend fun setOnboarded() = context.dataStore.edit { it[K.onboarded] = true }
    suspend fun setMicAsked() = context.dataStore.edit { it[K.micAsked] = true }
    suspend fun clear() = context.dataStore.edit { it.clear() }
}
