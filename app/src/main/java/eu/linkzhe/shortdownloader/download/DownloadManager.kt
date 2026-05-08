package eu.linkzhe.shortdownloader.download

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import eu.linkzhe.shortdownloader.model.DownloadFormat
import eu.linkzhe.shortdownloader.model.PreparedDownload
import eu.linkzhe.shortdownloader.model.VideoInfo
import eu.linkzhe.shortdownloader.util.FileNameSanitizer

class DownloadManager(private val context: Context) {
    fun download(preparedDownload: PreparedDownload, videoInfo: VideoInfo?, format: DownloadFormat?): OneTimeWorkRequest {
        if (preparedDownload.fileUrl.isBlank()) {
            throw IllegalArgumentException("No final download URL found.")
        }

        val fileName = FileNameSanitizer.sanitize(preparedDownload.fileName, "video.mp4")
            .let { if (it.endsWith(".mp4", ignoreCase = true)) it else "$it.mp4" }
        val data = Data.Builder()
            .putString(DownloadWorker.KEY_FILE_URL, preparedDownload.fileUrl)
            .putString(DownloadWorker.KEY_FILE_NAME, fileName)
            .putLong(DownloadWorker.KEY_FILE_SIZE_BYTES, preparedDownload.fileSizeBytes ?: -1L)
            .putString(DownloadWorker.KEY_CHANNEL_NAME, videoInfo?.channel)
            .putString(DownloadWorker.KEY_ORIGINAL_URL, videoInfo?.originalUrl)
            .putString(DownloadWorker.KEY_VIDEO_ID, videoInfo?.videoId)
            .putString(DownloadWorker.KEY_TITLE, videoInfo?.title)
            .putString(DownloadWorker.KEY_DESCRIPTION, videoInfo?.description)
            .putString(DownloadWorker.KEY_TAGS, videoInfo?.tags)
            .putString(DownloadWorker.KEY_QUALITY, format?.resolution ?: format?.quality)
            .build()
        val request = OneTimeWorkRequest.Builder(DownloadWorker::class.java)
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueue(request)
        return request
    }
}
