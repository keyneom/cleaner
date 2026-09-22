package com.cleaner.filter.security

import java.security.MessageDigest

object PinManager {
    fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyPin(pin: String, hash: String?): Boolean {
        if (hash.isNullOrBlank()) return true
        return hashPin(pin) == hash
    }
}
