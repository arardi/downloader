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
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure(errorData("No downloadable format found."))
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "video.mp4"
        val saver = MediaStoreSaver(applicationContext)
        var pending: MediaStoreSaver.PendingVideo? = null

        try {
            setProgress(progressData(0, "Downloading", fileName, null, null))
            val request = Request.Builder().url(url).header("User-Agent", "ZaShortsDownloader/1.0").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Download failed: HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("Download failed: empty response.")
                val totalBytes = body.contentLength().takeIf { it > 0L }
                pending = saver.createVideo(fileName)
                pending!!.stream.use { output ->
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
                                setProgress(progressData(progress, "Downloading", fileName, speed, null))
                                lastUpdate = now
                            }
                        }
                        output.flush()
                    }
                }
                saver.publish(pending!!.uri, pending!!.legacyPath)
                val output = progressData(100, "Completed", fileName, null, pending!!.uri.toString())
                setProgress(output)
                Result.success(output)
            }
        } catch (throwable: Throwable) {
            pending?.let { saver.delete(it.uri) }
            Result.failure(errorData(throwable.message ?: "Download failed."))
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
        const val KEY_URL = "url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_TITLE = "title"
        const val KEY_VIDEO_ID = "video_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_SPEED = "speed"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_ERROR = "error"
    }
}
