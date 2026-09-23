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
            coverPartialNudity = prefs[KEY_COVER_PARTIAL] ?: true,
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
                coverPartialNudity = prefs[KEY_COVER_PARTIAL] ?: true,
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
            prefs[KEY_COVER_PARTIAL] = updated.coverPartialNudity
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
        // New key: values stored under "visual_sensitivity" were forced to 0.02 and are ignored.
        private val KEY_VISUAL_SENSITIVITY = floatPreferencesKey("visual_score_threshold_v2")
        private val KEY_COVER_PARTIAL = booleanPreferencesKey("cover_partial_nudity")
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
