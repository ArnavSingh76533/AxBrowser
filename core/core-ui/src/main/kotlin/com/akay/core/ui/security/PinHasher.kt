package com.akay.core.ui.security

import java.security.MessageDigest

object PinHasher {
    private const val SALT = "axbrowser_pin_v1"

    fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((SALT + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun matches(pin: String, hash: String): Boolean = hash(pin) == hash
}
