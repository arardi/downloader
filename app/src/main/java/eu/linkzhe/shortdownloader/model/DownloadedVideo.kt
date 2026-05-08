package eu.linkzhe.shortdownloader.model

data class DownloadedVideo(
    val id: Long,
    val title: String,
    val originalUrl: String,
    val videoId: String,
    val channel: String?,
    val description: String?,
    val tags: String?,
    val quality: String?,
    val fileSizeText: String?,
    val filePathOrUri: String,
    val downloadedAt: Long
)
