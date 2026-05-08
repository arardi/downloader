package eu.linkzhe.shortdownloader.model

data class AnalyzedUrl(
    val url: String,
    val title: String,
    val videoId: String,
    val analyzedAt: Long
)
