package com.cleaner.filter.text

object ProfanityWordLists {
    val sexual = setOf(
        "porn", "xxx", "nude", "nudes", "nsfw", "onlyfans", "sex", "sexy",
        "hentai", "erotic", "adult", "camgirl", "webcam",
    )

    val profanity = setOf(
        "fuck", "shit", "bitch", "asshole", "damn", "cunt", "dick", "pussy",
    )

    fun allBlocked(): Set<String> = sexual + profanity

    fun containsBlocked(text: String, extra: Set<String> = emptySet()): Boolean {
        val lower = text.lowercase()
        val words = allBlocked() + extra
        return words.any { word ->
            lower.contains(word)
        }
    }
}
