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
    /** NudeNet score a detection needs before it is covered. Lower catches more. */
    val visualSensitivity: Float = DEFAULT_SCORE_THRESHOLD,
    /** Also cover bikini / lingerie / bare midriff / shirtless detections. */
    val coverPartialNudity: Boolean = true,
    val textFilterEnabled: Boolean = true,
    val audioFilterEnabled: Boolean = true,
    val audioMode: AudioMode = AudioMode.SYNCED_DELAY,
    val dnsEnabled: Boolean = false,
    val dnsProvider: DnsProvider = DnsProvider.CLOUDFLARE_FAMILY,
    val presentationDelayMs: Long = 50L,
    val targetFps: Int = 30,
    val parentalPinHash: String? = null,
)

/**
 * Provisional default. The old 0.02 floor let almost every weak detection through and
 * painted junk covers; misses came from the 320-px-wide capture, not the threshold.
 * Calibrate against the local trigger images on a device before changing this.
 */
const val DEFAULT_SCORE_THRESHOLD = 0.15f
const val MIN_SCORE_THRESHOLD = 0.05f
const val MAX_SCORE_THRESHOLD = 0.50f

/** Stored threshold, or the default, kept inside the slider range. */
internal fun effectiveSensitivity(stored: Float?): Float =
    (stored ?: DEFAULT_SCORE_THRESHOLD).coerceIn(MIN_SCORE_THRESHOLD, MAX_SCORE_THRESHOLD)
