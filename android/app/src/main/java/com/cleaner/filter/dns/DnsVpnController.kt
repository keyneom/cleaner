package com.cleaner.filter.dns

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.cleaner.filter.service.DnsVpnService
import com.cleaner.filter.settings.DnsProvider
import com.cleaner.filter.settings.FilterSettings

object DnsVpnController {
    fun prepareIntent(context: Context): Intent? =
        VpnService.prepare(context)

    fun start(context: Context, provider: DnsProvider) {
        val intent = Intent(context, DnsVpnService::class.java).apply {
            putExtra(DnsVpnService.EXTRA_PROVIDER, provider.name)
        }
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, DnsVpnService::class.java))
    }

    fun sync(context: Context, settings: FilterSettings) {
        if (settings.dnsEnabled && settings.dnsProvider != DnsProvider.OFF) {
            val prep = prepareIntent(context)
            if (prep == null) {
                start(context, settings.dnsProvider)
            }
        } else {
            stop(context)
        }
    }

    fun privateDnsInstructions(provider: DnsProvider): String =
        when (provider) {
            DnsProvider.OFF -> "DNS filtering is off."
            else -> buildString {
                appendLine("Settings → Network & internet → Private DNS")
                appendLine("Select: Private DNS provider hostname")
                appendLine("Enter: ${provider.hostname}")
                appendLine("Save, then verify at https://1.1.1.1/help")
            }
        }
}
