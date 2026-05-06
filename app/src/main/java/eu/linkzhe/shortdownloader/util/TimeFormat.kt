package eu.linkzhe.shortdownloader.util

object TimeFormat {
    fun duration(seconds: Long?): String = when {
        seconds == null -> "Duration: Unknown"
        seconds >= 3600 -> "Duration: %d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
        else -> "Duration: %d:%02d".format(seconds / 60, seconds % 60)
    }

    fun bytes(bytes: Long?): String = when {
        bytes == null || bytes <= 0 -> "Size unknown"
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
