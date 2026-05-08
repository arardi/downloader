package eu.linkzhe.shortdownloader.extractor

import eu.linkzhe.shortdownloader.model.DownloadFormat
import eu.linkzhe.shortdownloader.model.PreparedDownload
import eu.linkzhe.shortdownloader.model.VideoInfo
import eu.linkzhe.shortdownloader.util.FileNameSanitizer
import eu.linkzhe.shortdownloader.util.UrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        parseVideoInfo(postProxy(url), url, videoId)
    }

    override suspend fun prepareDownload(
        format: DownloadFormat,
        onProgress: ((String) -> Unit)?
    ): PreparedDownload = withContext(Dispatchers.IO) {
        warmupSession()
        var lastProgress: String? = null
        repeat(PREPARE_ATTEMPTS) { attemptIndex ->
            val attempt = attemptIndex + 1
            onProgress?.invoke("Preparing file... attempt $attempt/$PREPARE_ATTEMPTS")
            val api = postProxy(format.mediaUrl).optJSONObject("api")
                ?: throw IllegalStateException("API response is missing download data")
            val status = api.optCleanString("status")
            val fileUrl = api.optCleanString("fileUrl")
            val apiProgress = api.optCleanString("progress") ?: api.optCleanString("percent")
            lastProgress = apiProgress ?: status
            if (!apiProgress.isNullOrBlank()) {
                onProgress?.invoke(apiProgress)
            }

            if (status.equals("completed", ignoreCase = true) && !fileUrl.isNullOrBlank()) {
                onProgress?.invoke("Final URL ready")
                return@withContext parsePreparedDownload(api, format)
            }

            if (!status.isProcessingStatus() && !status.equals("completed", ignoreCase = true) && fileUrl.isNullOrBlank()) {
                throw IllegalStateException(lastProgress ?: "Download preparation not completed")
            }
            if (attempt < PREPARE_ATTEMPTS) delay(prepareDelayMs(attempt))
        }
        throw IllegalStateException("Final URL is not ready. Please try again.")
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

    private fun postProxy(urlValue: String): JSONObject {
        val body = FormBody.Builder()
            .add("url", urlValue)
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
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("API HTTP ${response.code}")
            if (text.isBlank()) throw IllegalStateException("API returned empty response")
            return JSONObject(text)
        }
    }

    private fun parseVideoInfo(root: JSONObject, originalUrl: String, fallbackVideoId: String): VideoInfo {
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
                    val type = item.optCleanString("type") ?: continue
                    val extension = item.optCleanString("mediaExtension") ?: continue
                    val mediaUrl = item.optCleanString("mediaUrl") ?: continue
                    if (!type.equals("Video", ignoreCase = true)) continue
                    if (!extension.equals("MP4", ignoreCase = true)) continue

                    val mediaQuality = item.optCleanString("mediaQuality")
                    val mediaRes = item.optCleanString("mediaRes")
                    val fileSize = item.optCleanString("mediaFileSize")
                    add(
                        DownloadFormat(
                            id = item.optCleanString("mediaId") ?: index.toString(),
                            label = listOfNotNull(mediaQuality, mediaRes, fileSize).joinToString(" • ").ifBlank { "Video ${index + 1}" },
                            extension = extension.lowercase(),
                            quality = mediaQuality,
                            resolution = mediaRes,
                            fileSizeBytes = null,
                            fileSizeText = fileSize,
                            mediaUrl = mediaUrl,
                            mediaPreviewUrl = item.optCleanString("mediaPreviewUrl"),
                            thumbnailUrl = item.optCleanString("mediaThumbnail"),
                            mediaTask = item.optCleanString("mediaTask"),
                            type = type
                        )
                    )
                }
            }
        }.sortedByDescending { it.qualityRank() }

        val userInfo = api.optJSONObject("userInfo")
        val stats = api.optJSONObject("mediaStats")
        val durationText = firstItem?.optCleanString("mediaDuration")
        val thumbnailUrl = api.optCleanString("imagePreviewUrl")
            ?: firstItem?.optCleanString("mediaThumbnail")
            ?: api.optCleanString("previewUrl")

        val title = api.optCleanString("title") ?: "Untitled video"
        val description = api.optCleanString("description")
        val tags = api.optTags() ?: extractHashtags(title, description)

        return VideoInfo(
            videoId = api.optCleanString("id") ?: fallbackVideoId,
            title = title,
            channel = userInfo?.optCleanString("name"),
            username = userInfo?.optCleanString("username"),
            durationSeconds = durationText?.toDurationSeconds(),
            durationText = durationText,
            thumbnailUrl = thumbnailUrl,
            viewsText = stats?.optCleanString("viewsCount"),
            description = description,
            tags = tags,
            apiStatus = status,
            originalUrl = originalUrl,
            formats = formats
        )
    }

    private fun parsePreparedDownload(api: JSONObject, format: DownloadFormat): PreparedDownload {
        val fileUrl = api.optCleanString("fileUrl")
            ?: throw IllegalStateException("API did not return final file URL")
        val fallbackName = "ZaVideo-${FileNameSanitizer.sanitize(format.quality ?: format.resolution ?: format.id)}.mp4"
        return PreparedDownload(
            fileName = api.optCleanString("fileName") ?: fallbackName,
            fileUrl = fileUrl,
            viewUrl = api.optCleanString("viewUrl"),
            fileSizeText = api.optCleanString("fileSize"),
            fileSizeBytes = api.optLongOrNull("fileSizeBytes")
        )
    }

    private fun DownloadFormat.qualityRank(): Int = resolution?.firstNumber()
        ?: mediaUrl.substringAfterLast('/').firstNumber()
        ?: label.firstNumber()
        ?: 0

    private fun String?.isProcessingStatus(): Boolean = this == null || equals("processing", ignoreCase = true) ||
        equals("pending", ignoreCase = true) || equals("queued", ignoreCase = true) ||
        equals("ok", ignoreCase = true) || equals("running", ignoreCase = true)

    private fun prepareDelayMs(attempt: Int): Long = when (attempt) {
        in 1..3 -> 1_500L
        in 4..7 -> 2_500L
        else -> 4_000L
    }

    private fun String.firstNumber(): Int? = Regex("\\d+").find(this)?.value?.toIntOrNull()

    private fun JSONObject.optCleanString(name: String): String? = optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun JSONObject.optTags(): String? {
        val array = optJSONArray("tags")
        if (array != null) {
            return buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotEmpty() && it != "null" }?.let { add(it) }
                }
            }.joinToString(", ").takeIf { it.isNotBlank() }
        }
        return optCleanString("tags")
    }

    private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) optLong(name).takeIf { it > 0L } else null

    private fun String.toDurationSeconds(): Long? {
        val parts = split(':').mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> null
        }
    }

    private fun extractHashtags(title: String?, description: String?): String? {
        val source = listOfNotNull(title, description).joinToString(" ")
        if (source.isBlank()) return null

        return HASHTAG_REGEX
            .findAll(source)
            .map { it.value }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
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
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val PREPARE_ATTEMPTS = 8
        private val HASHTAG_REGEX = Regex("#[A-Za-z0-9_]+")
    }
}
