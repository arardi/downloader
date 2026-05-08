package eu.linkzhe.shortdownloader.model

data class VideoInfo(
    val videoId: String,
    val title: String,
    val channel: String?,
    val username: String? = null,
    val durationSeconds: Long?,
    val durationText: String? = null,
    val thumbnailUrl: String?,
    val viewsText: String? = null,
    val description: String? = null,
    val tags: String? = null,
    val apiStatus: String? = null,
    val originalUrl: String,
    val formats: List<DownloadFormat>
)
