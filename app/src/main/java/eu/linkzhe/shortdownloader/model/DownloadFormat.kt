package eu.linkzhe.shortdownloader.model

data class DownloadFormat(
    val id: String,
    val label: String,
    val extension: String,
    val quality: String?,
    val resolution: String? = null,
    val fileSizeBytes: Long? = null,
    val fileSizeText: String? = null,
    val mediaUrl: String,
    val mediaPreviewUrl: String? = null,
    val thumbnailUrl: String? = null,
    val mediaTask: String? = null,
    val type: String = "Video"
)
