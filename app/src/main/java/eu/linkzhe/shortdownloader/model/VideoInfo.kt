package eu.linkzhe.shortdownloader.model

data class VideoInfo(
    val videoId: String,
    val title: String,
    val channel: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val formats: List<DownloadFormat>
)
