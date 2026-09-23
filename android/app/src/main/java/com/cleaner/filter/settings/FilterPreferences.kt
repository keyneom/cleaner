package com.cleaner.filter.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "filter_settings")

class FilterPreferences(private val context: Context) {
    val settings: Flow<FilterSettings> = context.dataStore.data.map { prefs ->
        FilterSettings(
            filterEnabled = prefs[KEY_FILTER_ENABLED] ?: false,
            visualNudityEnabled = prefs[KEY_VISUAL_NUDITY] ?: true,
            visualSensitivity = effectiveSensitivity(prefs[KEY_VISUAL_SENSITIVITY]),
            textFilterEnabled = prefs[KEY_TEXT_FILTER] ?: true,
            audioFilterEnabled = prefs[KEY_AUDIO_FILTER] ?: true,
            audioMode = AudioMode.entries.getOrElse(prefs[KEY_AUDIO_MODE] ?: 0) { AudioMode.SYNCED_DELAY },
            dnsEnabled = prefs[KEY_DNS_ENABLED] ?: false,
            dnsProvider = DnsProvider.entries.getOrElse(prefs[KEY_DNS_PROVIDER] ?: 0) { DnsProvider.CLOUDFLARE_FAMILY },
            presentationDelayMs = prefs[KEY_PRESENTATION_DELAY] ?: 50L,
            targetFps = prefs[KEY_TARGET_FPS] ?: 30,
            parentalPinHash = prefs[KEY_PIN_HASH],
        )
    }

    suspend fun update(transform: (FilterSettings) -> FilterSettings) {
        context.dataStore.edit { prefs ->
            val current = FilterSettings(
                filterEnabled = prefs[KEY_FILTER_ENABLED] ?: false,
                visualNudityEnabled = prefs[KEY_VISUAL_NUDITY] ?: true,
                visualSensitivity = effectiveSensitivity(prefs[KEY_VISUAL_SENSITIVITY]),
                textFilterEnabled = prefs[KEY_TEXT_FILTER] ?: true,
                audioFilterEnabled = prefs[KEY_AUDIO_FILTER] ?: true,
                audioMode = AudioMode.entries.getOrElse(prefs[KEY_AUDIO_MODE] ?: 0) { AudioMode.SYNCED_DELAY },
                dnsEnabled = prefs[KEY_DNS_ENABLED] ?: false,
                dnsProvider = DnsProvider.entries.getOrElse(prefs[KEY_DNS_PROVIDER] ?: 0) { DnsProvider.CLOUDFLARE_FAMILY },
                presentationDelayMs = prefs[KEY_PRESENTATION_DELAY] ?: 50L,
                targetFps = prefs[KEY_TARGET_FPS] ?: 30,
                parentalPinHash = prefs[KEY_PIN_HASH],
            )
            val updated = transform(current)
            prefs[KEY_FILTER_ENABLED] = updated.filterEnabled
            prefs[KEY_VISUAL_NUDITY] = updated.visualNudityEnabled
            prefs[KEY_VISUAL_SENSITIVITY] = updated.visualSensitivity
            prefs[KEY_TEXT_FILTER] = updated.textFilterEnabled
            prefs[KEY_AUDIO_FILTER] = updated.audioFilterEnabled
            prefs[KEY_AUDIO_MODE] = updated.audioMode.ordinal
            prefs[KEY_DNS_ENABLED] = updated.dnsEnabled
            prefs[KEY_DNS_PROVIDER] = updated.dnsProvider.ordinal
            prefs[KEY_PRESENTATION_DELAY] = updated.presentationDelayMs
            prefs[KEY_TARGET_FPS] = updated.targetFps
            if (updated.parentalPinHash != null) {
                prefs[KEY_PIN_HASH] = updated.parentalPinHash
            } else {
                prefs.remove(KEY_PIN_HASH)
            }
        }
    }

    companion object {
        private val KEY_FILTER_ENABLED = booleanPreferencesKey("filter_enabled")
        private val KEY_VISUAL_NUDITY = booleanPreferencesKey("visual_nudity")
        private val KEY_VISUAL_SENSITIVITY = floatPreferencesKey("visual_sensitivity")
        private val KEY_TEXT_FILTER = booleanPreferencesKey("text_filter")
        private val KEY_AUDIO_FILTER = booleanPreferencesKey("audio_filter")
        private val KEY_AUDIO_MODE = intPreferencesKey("audio_mode")
        private val KEY_DNS_ENABLED = booleanPreferencesKey("dns_enabled")
        private val KEY_DNS_PROVIDER = intPreferencesKey("dns_provider")
        private val KEY_PRESENTATION_DELAY = longPreferencesKey("presentation_delay_ms")
        private val KEY_TARGET_FPS = intPreferencesKey("target_fps")
        private val KEY_PIN_HASH = stringPreferencesKey("pin_hash")
    }
}

/** Old slider floors were too high and missed regions. Remap them down. */
internal fun effectiveSensitivity(stored: Float?): Float {
    val value = stored ?: 0.02f
    return when {
        value >= 0.20f -> 0.02f
        value in 0.08f..0.15f -> 0.02f
        else -> value.coerceIn(0.02f, 0.40f)
    }
}
