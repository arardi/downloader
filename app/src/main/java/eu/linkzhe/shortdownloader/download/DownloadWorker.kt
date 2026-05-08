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
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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
            val finalUri = downloadFinalUrlWithRetry(fileUrl, fileName, preparedSizeBytes, saver)
            val output = progressData(100, "Completed", fileName, null, finalUri.toString())
            setProgress(output)
            Result.success(output)
        } catch (throwable: Throwable) {
            Result.failure(errorData(throwable.message ?: "Download failed"))
        }
    }

    private fun downloadFinalUrlWithRetry(
        fileUrl: String,
        fileName: String,
        preparedSizeBytes: Long?,
        saver: MediaStoreSaver
    ): android.net.Uri {
        var lastError: Throwable? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attemptIndex ->
            val attempt = attemptIndex + 1
            try {
                setProgressAsync(progressData(0, "Downloading • attempt $attempt/$MAX_DOWNLOAD_ATTEMPTS", fileName, null, null))
                return downloadFinalUrlOnce(fileUrl, fileName, preparedSizeBytes, saver)
            } catch (throwable: Throwable) {
                lastError = throwable
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    setProgressAsync(progressData(0, "Retrying download ${attempt + 1}/$MAX_DOWNLOAD_ATTEMPTS...", fileName, null, null))
                    Thread.sleep(DOWNLOAD_RETRY_DELAY_MS)
                }
            }
        }
        throw lastError ?: IllegalStateException("Download failed")
    }

    private fun downloadFinalUrlOnce(
        fileUrl: String,
        fileName: String,
        preparedSizeBytes: Long?,
        saver: MediaStoreSaver
    ): android.net.Uri {
        val tempFile = java.io.File.createTempFile(
            java.util.UUID.randomUUID().toString(),
            ".mp4",
            applicationContext.cacheDir
        )
        var downloaded = 0L
        try {
            val request = Request.Builder()
                .url(fileUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Accept", "*/*")
                .header("Connection", "keep-alive")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code in RETRYABLE_HTTP_STATUS_CODES) {
                    throw IOException("Final URL failed or is not ready: HTTP ${response.code}")
                }
                if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
                val body = response.body ?: throw IOException("Download failed: empty response.")
                val contentLength = body.contentLength().takeIf { it > 0L }
                val expectedBytes = contentLength ?: preparedSizeBytes
                tempFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var lastUpdate = 0L
                        val startedAt = System.currentTimeMillis()
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 500) {
                                val progress = expectedBytes?.let { ((downloaded * 100) / it).toInt().coerceIn(0, 99) } ?: 0
                                val speed = if (now > startedAt) downloaded * 1000 / (now - startedAt) else null
                                setProgressAsync(progressData(progress, "Downloading", fileName, speed, null))
                                lastUpdate = now
                            }
                        }
                    }
                }
                validateDownloadedSize(downloaded, contentLength, preparedSizeBytes)
            }
            return saver.saveVideoFile(tempFile, fileName, "video/mp4")
        } finally {
            tempFile.delete()
        }
    }

    private fun validateDownloadedSize(downloaded: Long, contentLength: Long?, preparedSizeBytes: Long?) {
        if (downloaded < MIN_VALID_DOWNLOAD_BYTES) {
            throw IOException("Partial or corrupt download detected: $downloaded bytes")
        }
        if (contentLength != null && downloaded < contentLength) {
            throw IOException("Partial download detected: $downloaded of $contentLength bytes")
        }
        if (preparedSizeBytes != null) {
            val minimumValidSize = (preparedSizeBytes.toDouble() * MIN_VALID_DOWNLOAD_RATIO).toLong()
            if (downloaded < minimumValidSize) {
                throw IOException("Partial download detected: $downloaded of $preparedSizeBytes bytes")
            }
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
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val DOWNLOAD_RETRY_DELAY_MS = 1_500L
        private const val MIN_VALID_DOWNLOAD_BYTES = 50L * 1024L
        private const val MIN_VALID_DOWNLOAD_RATIO = 0.98
        private val RETRYABLE_HTTP_STATUS_CODES = setOf(403, 404, 410, 429, 500, 502, 503)
    }
}
