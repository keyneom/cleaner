package com.cleaner.filter.settings

enum class DnsProvider(val hostname: String, val label: String) {
    CLOUDFLARE_FAMILY("family.cloudflare-dns.com", "Cloudflare Family (1.1.1.3)"),
    CLEANBROWSING_FAMILY("family-filter-dns.cleanbrowsing.org", "CleanBrowsing Family"),
    NEXTDNS("dns.nextdns.io", "NextDNS (configure profile in app)"),
    OFF("", "Off"),
}

enum class AudioMode {
    SYNCED_DELAY,
    LEAKY_REALTIME,
}

data class FilterSettings(
    val filterEnabled: Boolean = false,
    val visualNudityEnabled: Boolean = true,
    val visualSensitivity: Float = 0.6f,
    val textFilterEnabled: Boolean = true,
    val audioFilterEnabled: Boolean = true,
    val audioMode: AudioMode = AudioMode.SYNCED_DELAY,
    val dnsEnabled: Boolean = false,
    val dnsProvider: DnsProvider = DnsProvider.CLOUDFLARE_FAMILY,
    val presentationDelayMs: Long = 50L,
    val targetFps: Int = 30,
    val parentalPinHash: String? = null,
)
