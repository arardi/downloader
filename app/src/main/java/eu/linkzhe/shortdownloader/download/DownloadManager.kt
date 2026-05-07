package eu.linkzhe.shortdownloader.download

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import eu.linkzhe.shortdownloader.model.DownloadFormat
import eu.linkzhe.shortdownloader.model.VideoInfo
import eu.linkzhe.shortdownloader.util.FileNameSanitizer

class DownloadManager(private val context: Context) {
    fun download(format: DownloadFormat, videoInfo: VideoInfo): OneTimeWorkRequest {
        if (format.directUrl.isBlank()) {
            throw IllegalArgumentException("No downloadable format found.")
        }
        if (!format.extension.equals("mp4", ignoreCase = true)) {
            throw IllegalArgumentException("Only Video MP4 downloads are supported right now.")
        }

        val quality = FileNameSanitizer.sanitize(format.quality ?: format.label, "video")
        val fileName = "${FileNameSanitizer.sanitize(videoInfo.title)}-${videoInfo.videoId}-$quality.mp4"
        val data = Data.Builder()
            .putString(DownloadWorker.KEY_DIRECT_URL, format.directUrl)
            .putString(DownloadWorker.KEY_FILE_NAME, fileName)
            .putString(DownloadWorker.KEY_TITLE, videoInfo.title)
            .putString(DownloadWorker.KEY_VIDEO_ID, videoInfo.videoId)
            .putString(DownloadWorker.KEY_EXTENSION, format.extension.lowercase())
            .build()
        val request = OneTimeWorkRequest.Builder(DownloadWorker::class.java)
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueue(request)
        return request
    }
}
