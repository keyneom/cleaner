package com.cleaner.filter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleaner.filter.CleanerApp
import com.cleaner.filter.security.PinManager
import com.cleaner.filter.settings.AudioMode
import com.cleaner.filter.settings.DnsProvider
import com.cleaner.filter.settings.FilterSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    private val prefs = CleanerApp.instance.preferences

    val settings: StateFlow<FilterSettings> = prefs.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        FilterSettings(),
    )

    fun setFilterEnabled(enabled: Boolean) = update { it.copy(filterEnabled = enabled) }

    fun setVisualNudity(enabled: Boolean) = update { it.copy(visualNudityEnabled = enabled) }

    fun setVisualSensitivity(value: Float) = update { it.copy(visualSensitivity = value) }

    fun setTextFilter(enabled: Boolean) = update { it.copy(textFilterEnabled = enabled) }

    fun setAudioFilter(enabled: Boolean) = update { it.copy(audioFilterEnabled = enabled) }

    fun setAudioMode(mode: AudioMode) = update { it.copy(audioMode = mode) }

    fun setDnsEnabled(enabled: Boolean) = update { it.copy(dnsEnabled = enabled) }

    fun setDnsProvider(provider: DnsProvider) = update { it.copy(dnsProvider = provider) }

    fun setPresentationDelay(ms: Long) = update { it.copy(presentationDelayMs = ms) }

    fun setPin(pin: String) = update { it.copy(parentalPinHash = PinManager.hashPin(pin)) }

    fun clearPin() = update { it.copy(parentalPinHash = null) }

    fun verifyPin(pin: String, onResult: (Boolean) -> Unit) {
        val hash = settings.value.parentalPinHash
        onResult(PinManager.verifyPin(pin, hash))
    }

    private fun update(transform: (FilterSettings) -> FilterSettings) {
        viewModelScope.launch {
            prefs.update(transform)
        }
    }
}
