package com.akay.feature.browser.agent

private val YOUTUBE_ID_REGEX = Regex("^[A-Za-z0-9_-]{10,12}$")

/**
 * Models sometimes hand back a bare YouTube video ID (or otherwise mangle
 * the URL) instead of a full address. This makes a best effort to turn
 * whatever the agent produced into a real, complete URL the downloader can
 * actually use, falling back to the page currently open in the browser.
 */
fun normalizeDownloadUrl(raw: String, fallbackCurrentUrl: String): String {
    val trimmed = raw.trim().trim('"', '\'')

    if (trimmed.isBlank()) return fallbackCurrentUrl

    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        return trimmed
    }

    // Bare 11-char YouTube video id, e.g. "dQw4w9WgXcQ"
    if (YOUTUBE_ID_REGEX.matches(trimmed)) {
        return "https://www.youtube.com/watch?v=$trimmed"
    }

    // "youtube.com/watch?v=xyz" or "youtu.be/xyz" missing only the scheme
    if (trimmed.contains("youtube.com", ignoreCase = true) || trimmed.contains("youtu.be", ignoreCase = true)) {
        return "https://$trimmed"
    }

    // Any other domain-looking string missing a scheme
    if (trimmed.contains(".") && !trimmed.contains(" ")) {
        return "https://$trimmed"
    }

    // Couldn't make sense of it - use whatever's actually open in the browser right now.
    return fallbackCurrentUrl
}
