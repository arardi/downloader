package eu.linkzhe.shortdownloader.model

data class PreparedDownload(
    val fileName: String,
    val fileUrl: String,
    val viewUrl: String?,
    val fileSizeText: String?,
    val fileSizeBytes: Long?
)
