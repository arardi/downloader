package eu.linkzhe.shortdownloader.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import eu.linkzhe.shortdownloader.storage.MediaStoreSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val directUrl = inputData.getString(KEY_DIRECT_URL)
        if (directUrl.isNullOrBlank()) {
            return@withContext Result.failure(errorData("No downloadable format found."))
        }
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "video.mp4"
        val extension = inputData.getString(KEY_EXTENSION) ?: fileName.substringAfterLast('.', "mp4")
        val saver = MediaStoreSaver(applicationContext)

        try {
            setProgress(progressData(0, "Downloading", fileName, null, null))
            val finalUri = downloadDirectUrl(directUrl, fileName, extension, saver)
            val output = progressData(100, "Completed", fileName, null, finalUri.toString())
            setProgress(output)
            Result.success(output)
        } catch (throwable: Throwable) {
            Result.failure(errorData("Download failed. ${throwable.message.orEmpty()}".trim()))
        }
    }

    private fun downloadDirectUrl(
        directUrl: String,
        fileName: String,
        extension: String,
        saver: MediaStoreSaver
    ): android.net.Uri {
        val mimeType = mimeTypeFor(extension)
        val pending = saver.createVideo(fileName, mimeType)
        try {
            val request = Request.Builder()
                .url(directUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Download failed: HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("Download failed: empty response.")
                val totalBytes = body.contentLength().takeIf { it > 0L }
                pending.stream.use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var lastUpdate = 0L
                        val startedAt = System.currentTimeMillis()
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 500) {
                                val progress = totalBytes?.let { ((downloaded * 100) / it).toInt().coerceIn(0, 99) } ?: 0
                                val speed = if (now > startedAt) downloaded * 1000 / (now - startedAt) else null
                                setProgressAsync(progressData(progress, "Downloading", fileName, speed, null))
                                lastUpdate = now
                            }
                        }
                    }
                }
            }
            saver.publish(pending.uri, pending.legacyPath, mimeType)
            return pending.uri
        } catch (throwable: Throwable) {
            runCatching { pending.stream.close() }
            saver.delete(pending.uri)
            throw throwable
        }
    }

    private fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        else -> "video/mp4"
    }

    private fun progressData(progress: Int, status: String, fileName: String, speedBytesPerSecond: Long?, uri: String?): Data =
        Data.Builder()
            .putInt(KEY_PROGRESS, progress)
            .putString(KEY_STATUS, status)
            .putString(KEY_FILE_NAME, fileName)
            .putLong(KEY_SPEED, speedBytesPerSecond ?: -1L)
            .putString(KEY_OUTPUT_URI, uri)
            .build()

    private fun errorData(message: String): Data = Data.Builder().putString(KEY_ERROR, message).putString(KEY_STATUS, "Failed").build()

    companion object {
        const val KEY_DIRECT_URL = "direct_url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_TITLE = "title"
        const val KEY_VIDEO_ID = "video_id"
        const val KEY_EXTENSION = "extension"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_SPEED = "speed"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_ERROR = "error"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}
