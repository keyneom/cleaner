package com.cleaner.filter.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfanityWordListsTest {
    @Test
    fun detectsBlockedWords() {
        assertTrue(ProfanityWordLists.containsBlocked("click for free porn now"))
        assertFalse(ProfanityWordLists.containsBlocked("hello world"))
    }
}
