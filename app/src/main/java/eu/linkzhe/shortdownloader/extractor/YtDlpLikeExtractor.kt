package eu.linkzhe.shortdownloader.extractor

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import eu.linkzhe.shortdownloader.model.DownloadFormat
import eu.linkzhe.shortdownloader.model.VideoInfo
import eu.linkzhe.shortdownloader.util.UrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YtDlpLikeExtractor : VideoExtractor {
    override suspend fun fetchInfo(url: String): VideoInfo = withContext(Dispatchers.IO) {
        val videoId = UrlParser.extractVideoId(url) ?: throw IllegalArgumentException("Invalid YouTube URL.")
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--skip-download")
            addOption("--dump-json")
            addOption("--no-warnings")
            addOption("-f", BEST_MP4_FORMAT)
        }

        try {
            val info = YoutubeDL.getInstance().getInfo(request)
            VideoInfo(
                originalUrl = url,
                videoId = videoId,
                title = info.title ?: "YouTube Shorts $videoId",
                channel = info.uploader,
                durationSeconds = info.duration.takeIf { it > 0 }?.toLong(),
                thumbnailUrl = info.thumbnail ?: "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                formats = presetFormats()
            )
        } catch (exception: YoutubeDLException) {
            throw IllegalStateException(
                "Unable to extract video details. The video may be unavailable, private, restricted, or the downloader engine needs an update.",
                exception
            )
        }
    }

    private fun presetFormats(): List<DownloadFormat> = listOf(
        DownloadFormat(
            id = "best-mp4",
            label = "Best MP4",
            extension = "mp4",
            quality = "Best",
            fileSizeBytes = null,
            directUrl = null,
            ytdlpFormat = BEST_MP4_FORMAT
        ),
        DownloadFormat(
            id = "720p",
            label = "MP4 720p",
            extension = "mp4",
            quality = "720p",
            fileSizeBytes = null,
            directUrl = null,
            ytdlpFormat = "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best[height<=720]"
        ),
        DownloadFormat(
            id = "480p",
            label = "MP4 480p",
            extension = "mp4",
            quality = "480p",
            fileSizeBytes = null,
            directUrl = null,
            ytdlpFormat = "bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/best[height<=480][ext=mp4]/best[height<=480]"
        ),
        DownloadFormat(
            id = "360p",
            label = "MP4 360p",
            extension = "mp4",
            quality = "360p",
            fileSizeBytes = null,
            directUrl = null,
            ytdlpFormat = "bestvideo[height<=360][ext=mp4]+bestaudio[ext=m4a]/best[height<=360][ext=mp4]/best[height<=360]"
        )
    )

    companion object {
        const val BEST_MP4_FORMAT = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"
    }
}
