package eu.linkzhe.shortdownloader.extractor

import eu.linkzhe.shortdownloader.model.DownloadFormat
import eu.linkzhe.shortdownloader.model.VideoInfo
import eu.linkzhe.shortdownloader.util.UrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class YtdownApiExtractor : VideoExtractor {
    private val client = OkHttpClient.Builder()
        .cookieJar(SimpleMemoryCookieJar())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchInfo(url: String): VideoInfo = withContext(Dispatchers.IO) {
        val videoId = UrlParser.extractVideoId(url)
            ?: throw IllegalArgumentException("Invalid YouTube URL")

        warmupSession()

        val body = FormBody.Builder()
            .add("url", url)
            .build()

        val request = Request.Builder()
            .url(API_URL)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("x-requested-with", "XMLHttpRequest")
            .header("origin", "https://app.ytdown.to")
            .header("referer", WARMUP_URL)
            .header("accept-language", "en-US,en;q=0.9")
            .build()

        client.newCall(request).execute().use { response ->
            val jsonText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("API error HTTP ${response.code}")
            }
            if (jsonText.isBlank()) {
                throw IllegalStateException("API returned empty response")
            }
            parseVideoInfo(jsonText, url, videoId)
        }
    }

    private fun warmupSession() {
        val request = Request.Builder()
            .url(WARMUP_URL)
            .header("User-Agent", USER_AGENT)
            .build()
        runCatching {
            client.newCall(request).execute().close()
        }
    }

    private fun parseVideoInfo(jsonText: String, originalUrl: String, fallbackVideoId: String): VideoInfo {
        val root = JSONObject(jsonText)
        val api = root.optJSONObject("api") ?: throw IllegalStateException("API response is missing video data")
        val status = api.optCleanString("status")
        if (!status.equals("ok", ignoreCase = true)) {
            val message = api.optCleanString("message") ?: "API status is ${status ?: "unknown"}"
            throw IllegalStateException(message)
        }

        val mediaItems = api.optJSONArray("mediaItems")
        val firstItem = mediaItems?.optJSONObject(0)
        val formats = buildList {
            if (mediaItems != null) {
                for (index in 0 until mediaItems.length()) {
                    val item = mediaItems.optJSONObject(index) ?: continue
                    if (!item.optCleanString("type").equals("Video", ignoreCase = true)) continue
                    val mediaUrl = item.optCleanString("mediaUrl") ?: continue
                    val mediaQuality = item.optCleanString("mediaQuality")
                    val mediaRes = item.optCleanString("mediaRes")
                    val extension = item.optCleanString("mediaExtension")?.lowercase() ?: "mp4"
                    add(
                        DownloadFormat(
                            id = item.optCleanString("mediaId") ?: index.toString(),
                            label = listOfNotNull(mediaQuality, mediaRes).joinToString(" ").ifBlank { "Video ${index + 1}" },
                            extension = extension,
                            quality = mediaQuality ?: mediaRes,
                            fileSizeBytes = null,
                            fileSizeText = item.optCleanString("mediaFileSize"),
                            directUrl = mediaUrl,
                            previewUrl = item.optCleanString("mediaPreviewUrl"),
                            mediaTask = item.optCleanString("mediaTask")
                        )
                    )
                }
            }
        }

        val userInfo = api.optJSONObject("userInfo")
        val stats = api.optJSONObject("mediaStats")
        val durationText = firstItem?.optCleanString("mediaDuration")
        val thumbnailUrl = api.optCleanString("imagePreviewUrl")
            ?: firstItem?.optCleanString("mediaThumbnail")
            ?: api.optCleanString("previewUrl")

        return VideoInfo(
            videoId = api.optCleanString("id") ?: fallbackVideoId,
            title = api.optCleanString("title") ?: "Untitled video",
            channel = userInfo?.optCleanString("name"),
            username = userInfo?.optCleanString("username"),
            durationSeconds = durationText?.toDurationSeconds(),
            durationText = durationText,
            thumbnailUrl = thumbnailUrl,
            viewsText = stats?.optCleanString("viewsCount"),
            apiStatus = status,
            originalUrl = originalUrl,
            formats = formats
        )
    }

    private fun JSONObject.optCleanString(name: String): String? = optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun String.toDurationSeconds(): Long? {
        val parts = split(':').mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> null
        }
    }

    private class SimpleMemoryCookieJar : CookieJar {
        private val cookies = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies[url.host] = cookies.toMutableList()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return cookies[url.host]
                ?.filter { it.expiresAt > now }
                .orEmpty()
        }
    }

    companion object {
        private const val API_URL = "https://app.ytdown.to/proxy.php"
        private const val WARMUP_URL = "https://app.ytdown.to/en27/"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}
