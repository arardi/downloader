package eu.linkzhe.shortdownloader.extractor

import eu.linkzhe.shortdownloader.model.VideoInfo
import eu.linkzhe.shortdownloader.util.UrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class YtDlpLikeExtractor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
) : VideoExtractor {
    override suspend fun fetchInfo(url: String): VideoInfo = withContext(Dispatchers.IO) {
        val videoId = UrlParser.extractVideoId(url) ?: throw IllegalArgumentException("Invalid YouTube URL.")
        val fallback = fallbackInfo(videoId)
        val oEmbedUrl = "https://www.youtube.com/oembed".toHttpUrl().newBuilder()
            .addQueryParameter("url", "https://www.youtube.com/watch?v=$videoId")
            .addQueryParameter("format", "json")
            .build()

        val request = Request.Builder()
            .url(oEmbedUrl)
            .header("User-Agent", "ZaShortsDownloader/1.0")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("oEmbed failed: HTTP ${response.code}")
                val json = JSONObject(response.body?.string().orEmpty())
                fallback.copy(
                    title = json.optString("title", fallback.title).ifBlank { fallback.title },
                    channel = json.optString("author_name", fallback.channel).ifBlank { fallback.channel },
                    thumbnailUrl = json.optString("thumbnail_url", fallback.thumbnailUrl).ifBlank { fallback.thumbnailUrl }
                )
            }
        }.getOrElse { fallback }
    }

    private fun fallbackInfo(videoId: String): VideoInfo = VideoInfo(
        videoId = videoId,
        title = "YouTube Shorts $videoId",
        channel = "Unknown uploader",
        durationSeconds = null,
        thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
        formats = emptyList()
    )
}
