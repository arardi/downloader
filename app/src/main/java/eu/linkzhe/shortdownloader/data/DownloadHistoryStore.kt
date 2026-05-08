package eu.linkzhe.shortdownloader.data

import android.content.Context
import eu.linkzhe.shortdownloader.model.AnalyzedUrl
import eu.linkzhe.shortdownloader.model.DownloadedVideo
import org.json.JSONArray
import org.json.JSONObject

class DownloadHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDownloads(): List<DownloadedVideo> = prefs.getString(KEY_DOWNLOADS, null)
        ?.let { parseDownloads(it) }
        .orEmpty()

    fun addDownload(video: DownloadedVideo) {
        val items = buildList {
            add(video)
            addAll(getDownloads().filterNot { it.contentUri == video.contentUri || it.readablePath == video.readablePath })
        }.take(MAX_DOWNLOADS)
        prefs.edit().putString(KEY_DOWNLOADS, JSONArray(items.map { it.toJson() }).toString()).apply()
    }

    fun clearDownloads() {
        prefs.edit().remove(KEY_DOWNLOADS).apply()
    }

    fun getRecentUrls(): List<AnalyzedUrl> = prefs.getString(KEY_RECENT_URLS, null)
        ?.let { parseRecentUrls(it) }
        .orEmpty()

    fun addRecentUrl(url: String, title: String, videoId: String, analyzedAt: Long = System.currentTimeMillis()) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        val item = AnalyzedUrl(cleanUrl, title, videoId, analyzedAt)
        val items = buildList {
            add(item)
            addAll(getRecentUrls().filterNot { it.url.equals(cleanUrl, ignoreCase = true) })
        }.take(MAX_RECENT_URLS)
        prefs.edit().putString(KEY_RECENT_URLS, JSONArray(items.map { it.toJson() }).toString()).apply()
    }

    fun clearRecentUrls() {
        prefs.edit().remove(KEY_RECENT_URLS).apply()
    }

    private fun parseDownloads(raw: String): List<DownloadedVideo> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                add(
                    DownloadedVideo(
                        id = json.optLong("id"),
                        title = json.optString("title"),
                        originalUrl = json.optString("originalUrl"),
                        videoId = json.optString("videoId"),
                        channel = json.optNullableString("channel"),
                        description = json.optNullableString("description"),
                        tags = json.optNullableString("tags"),
                        quality = json.optNullableString("quality"),
                        fileSizeText = json.optNullableString("fileSizeText"),
                        contentUri = json.optString("contentUri").ifBlank { json.optString("filePathOrUri") },
                        readablePath = json.optString("readablePath").ifBlank { json.optString("filePathOrUri") },
                        downloadedAt = json.optLong("downloadedAt")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun parseRecentUrls(raw: String): List<AnalyzedUrl> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                add(
                    AnalyzedUrl(
                        url = json.optString("url"),
                        title = json.optString("title"),
                        videoId = json.optString("videoId"),
                        analyzedAt = json.optLong("analyzedAt")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun DownloadedVideo.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("originalUrl", originalUrl)
        .put("videoId", videoId)
        .put("channel", channel)
        .put("description", description)
        .put("tags", tags)
        .put("quality", quality)
        .put("fileSizeText", fileSizeText)
        .put("contentUri", contentUri)
        .put("readablePath", readablePath)
        .put("downloadedAt", downloadedAt)

    private fun AnalyzedUrl.toJson(): JSONObject = JSONObject()
        .put("url", url)
        .put("title", title)
        .put("videoId", videoId)
        .put("analyzedAt", analyzedAt)

    private fun JSONObject.optNullableString(name: String): String? = optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }

    companion object {
        private const val PREFS_NAME = "download_history"
        private const val KEY_DOWNLOADS = "downloads"
        private const val KEY_RECENT_URLS = "recent_urls"
        private const val MAX_DOWNLOADS = 50
        private const val MAX_RECENT_URLS = 20
    }
}
