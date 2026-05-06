package eu.linkzhe.shortdownloader.util

import android.net.Uri

object UrlParser {
    private val videoIdRegex = Regex("^[A-Za-z0-9_-]{11}$")

    fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.matches(videoIdRegex)) return trimmed

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val segments = uri.pathSegments.orEmpty()

        val candidate = when {
            host == "youtube.com" || host == "m.youtube.com" -> when {
                segments.firstOrNull() == "shorts" && segments.size >= 2 -> segments[1]
                segments.firstOrNull() == "watch" -> uri.getQueryParameter("v")
                else -> null
            }
            host == "youtu.be" -> segments.firstOrNull()
            else -> null
        }?.take(11)

        return candidate?.takeIf { it.matches(videoIdRegex) }
    }
}
