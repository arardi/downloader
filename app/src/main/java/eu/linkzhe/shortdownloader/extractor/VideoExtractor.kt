package eu.linkzhe.shortdownloader.extractor

import eu.linkzhe.shortdownloader.model.DownloadFormat
import eu.linkzhe.shortdownloader.model.PreparedDownload
import eu.linkzhe.shortdownloader.model.VideoInfo

interface VideoExtractor {
    suspend fun fetchInfo(url: String): VideoInfo
    suspend fun prepareDownload(
        format: DownloadFormat,
        onProgress: ((String) -> Unit)? = null
    ): PreparedDownload
}
