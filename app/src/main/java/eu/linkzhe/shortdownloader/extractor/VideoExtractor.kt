package eu.linkzhe.shortdownloader.extractor

import eu.linkzhe.shortdownloader.model.VideoInfo

interface VideoExtractor {
    suspend fun fetchInfo(url: String): VideoInfo
}
