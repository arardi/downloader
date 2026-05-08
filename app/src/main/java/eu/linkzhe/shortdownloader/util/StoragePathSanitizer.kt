package eu.linkzhe.shortdownloader.util

object StoragePathSanitizer {
    const val APP_DIRECTORY = "ZaVideoDownloader"
    const val UNKNOWN_CHANNEL = "UnknownChannel"

    fun sanitizeFolderName(input: String?): String = input.orEmpty()
        .trim()
        .ifBlank { UNKNOWN_CHANNEL }
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .take(80)
        .ifBlank { UNKNOWN_CHANNEL }

    fun csvSafeName(input: String?, fallback: String): String = input.orEmpty()
        .trim()
        .ifBlank { fallback }
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .replace(Regex("\\s+"), "_")
        .take(80)
        .ifBlank { fallback }
}
