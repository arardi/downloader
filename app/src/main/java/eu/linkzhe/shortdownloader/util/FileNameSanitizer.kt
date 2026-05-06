package eu.linkzhe.shortdownloader.util

object FileNameSanitizer {
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+")

    fun sanitize(input: String, fallback: String = "video"): String {
        val cleaned = input.replace(illegal, " ").trim().replace(Regex("\\s+"), " ")
        return cleaned.take(80).ifBlank { fallback }
    }
}
