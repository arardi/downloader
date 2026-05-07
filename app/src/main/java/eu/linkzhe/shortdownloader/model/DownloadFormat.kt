package eu.linkzhe.shortdownloader.model

data class DownloadFormat(
    val id: String,
    val label: String,
    val extension: String,
    val quality: String?,
    val fileSizeBytes: Long? = null,
    val fileSizeText: String? = null,
    val directUrl: String,
    val previewUrl: String? = null,
    val mediaTask: String? = null
)
