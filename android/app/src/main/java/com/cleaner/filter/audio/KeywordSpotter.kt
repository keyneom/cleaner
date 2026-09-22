package com.cleaner.filter.audio

object KeywordSpotter {
    private val badWords = com.cleaner.filter.text.ProfanityWordLists.allBlocked()

    /**
     * Simple energy + keyword window detector for PCM16 mono at [sampleRate].
     * Returns true if window should be muted.
     */
    fun shouldMuteWindow(pcm: ShortArray, sampleRate: Int): Boolean {
        if (pcm.isEmpty()) return false
        // Placeholder: real KWS would run sherpa-onnx; here we detect high-energy speech-like
        // segments and apply conservative mute when parent enabled strict audio (paired with delay).
        val rms = kotlin.math.sqrt(
            pcm.map { it.toDouble() * it }.average(),
        )
        if (rms < 500) return false
        // Without ASR, strict mode mutes loud speech segments when audio filter is on.
        // Leaky mode skips this in FilteredAudioPipeline.
        return rms > 4000
    }

    fun containsProfanity(transcript: String): Boolean =
        com.cleaner.filter.text.ProfanityWordLists.containsBlocked(transcript)
}
