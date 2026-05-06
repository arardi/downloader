package eu.linkzhe.shortdownloader.model

data class DownloadFormat(
    val id: String,
    val label: String,
    val extension: String,
    val quality: String?,
    val fileSizeBytes: Long?,
    val directUrl: String?,
    val ytdlpFormat: String? = null
)
