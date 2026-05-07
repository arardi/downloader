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
import java.io.File
import java.util.UUID
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
        val fileUrl = inputData.getString(KEY_FILE_URL)
        if (fileUrl.isNullOrBlank()) {
            return@withContext Result.failure(errorData("No final download URL found."))
        }
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "video.mp4"
        val preparedSizeBytes = inputData.getLong(KEY_FILE_SIZE_BYTES, -1L).takeIf { it > 0L }
        val saver = MediaStoreSaver(applicationContext)

        try {
            setProgress(progressData(0, "Downloading", fileName, null, null))
            val finalUri = downloadFinalUrl(fileUrl, fileName, preparedSizeBytes, saver)
            val output = progressData(100, "Completed", fileName, null, finalUri.toString())
            setProgress(output)
            Result.success(output)
        } catch (throwable: Throwable) {
            Result.failure(errorData("Download failed. ${throwable.message.orEmpty()}".trim()))
        }
    }

    private fun downloadFinalUrl(
        fileUrl: String,
        fileName: String,
        preparedSizeBytes: Long?,
        saver: MediaStoreSaver
    ): android.net.Uri {
        val tempFile = File.createTempFile(UUID.randomUUID().toString(), ".mp4", applicationContext.cacheDir)
        try {
            val request = Request.Builder()
                .url(fileUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Download failed: HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("Download failed: empty response.")
                val totalBytes = body.contentLength().takeIf { it > 0L } ?: preparedSizeBytes
                tempFile.outputStream().use { output ->
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
            return saver.saveVideoFile(tempFile, fileName, "video/mp4")
        } finally {
            tempFile.delete()
        }
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
        const val KEY_FILE_URL = "file_url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_FILE_SIZE_BYTES = "file_size_bytes"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_SPEED = "speed"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_ERROR = "error"
        private const val REFERER = "https://app.ytdown.to/en27/"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
