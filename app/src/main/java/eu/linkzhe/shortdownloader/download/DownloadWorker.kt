package eu.linkzhe.shortdownloader.download

import android.content.Context
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import eu.linkzhe.shortdownloader.storage.MediaStoreSaver
import eu.linkzhe.shortdownloader.util.FileNameSanitizer
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
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sourceUrl = inputData.getString(KEY_URL) ?: return@withContext Result.failure(errorData("No downloadable format found."))
        val ytdlpFormat = inputData.getString(KEY_YTDLP_FORMAT)
        val directUrl = inputData.getString(KEY_DIRECT_URL)
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "video.mp4"
        val saver = MediaStoreSaver(applicationContext)

        try {
            ensureDownloaderInitialized()
            setProgress(progressData(0, "Downloading", fileName, null, null))
            val finalUri = if (!ytdlpFormat.isNullOrBlank()) {
                downloadWithYoutubeDL(sourceUrl, ytdlpFormat, fileName, saver)
            } else if (!directUrl.isNullOrBlank()) {
                downloadDirectUrl(directUrl, fileName, saver)
            } else {
                throw IllegalArgumentException("No downloadable format found.")
            }

            val output = progressData(100, "Completed", fileName, null, finalUri.toString())
            setProgress(output)
            Result.success(output)
        } catch (throwable: Throwable) {
            Result.failure(errorData("Download failed. Try Update downloader engine or another quality. ${throwable.message.orEmpty()}".trim()))
        }
    }

    private fun ensureDownloaderInitialized() {
        try {
            YoutubeDL.getInstance().init(applicationContext)
        } catch (exception: YoutubeDLException) {
            throw IllegalStateException("Downloader engine failed to initialize: ${exception.message}", exception)
        }
    }

    private fun downloadWithYoutubeDL(sourceUrl: String, ytdlpFormat: String, fileName: String, saver: MediaStoreSaver): android.net.Uri {
        val tempDir = File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "downloads").apply { mkdirs() }
        val safeBaseName = FileNameSanitizer.sanitize(fileName.removeSuffix(".mp4"), "video")
        val outputTemplate = File(tempDir, "$safeBaseName.%(ext)s").absolutePath
        val request = YoutubeDLRequest(sourceUrl).apply {
            addOption("--no-playlist")
            addOption("-f", ytdlpFormat)
            addOption("--merge-output-format", "mp4")
            addOption("--no-warnings")
            addOption("-o", outputTemplate)
        }

        val existingFiles = tempDir.listFiles()?.map { it.absolutePath }?.toSet().orEmpty()
        val processId = "ZaShorts-${UUID.randomUUID()}"
        YoutubeDL.getInstance().execute(request, processId) { progress: Float, etaInSeconds: Long, line: String ->
            val statusLine = line.takeIf { it.isNotBlank() } ?: "Downloading • ETA ${etaInSeconds}s"
            setProgressAsync(progressData(progress.toInt().coerceIn(0, 99), statusLine, fileName, null, null))
        }

        val downloadedFile = findDownloadedFile(tempDir, safeBaseName, existingFiles)
            ?: throw IllegalStateException("yt-dlp completed but no output file was found.")
        val finalUri = saver.saveVideoFile(downloadedFile, fileName)
        downloadedFile.delete()
        return finalUri
    }

    private fun findDownloadedFile(tempDir: File, safeBaseName: String, existingFiles: Set<String>): File? = tempDir
        .listFiles()
        ?.filter { it.isFile && it.absolutePath !in existingFiles && it.name.startsWith(safeBaseName) && !it.name.endsWith(".part") }
        ?.maxByOrNull { it.lastModified() }

    private fun downloadDirectUrl(directUrl: String, fileName: String, saver: MediaStoreSaver): android.net.Uri {
        val tempFile = File.createTempFile(UUID.randomUUID().toString(), ".mp4", applicationContext.cacheDir)
        try {
            val request = Request.Builder().url(directUrl).header("User-Agent", "ZaShortsDownloader/1.0").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Download failed: HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("Download failed: empty response.")
                val totalBytes = body.contentLength().takeIf { it > 0L }
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
            return saver.saveVideoFile(tempFile, fileName)
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
        const val KEY_URL = "url"
        const val KEY_DIRECT_URL = "direct_url"
        const val KEY_YTDLP_FORMAT = "ytdlp_format"
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
