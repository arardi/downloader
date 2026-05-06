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
        val directUrl = format.directUrl ?: throw IllegalArgumentException("No downloadable format found.")
        val extension = format.extension.ifBlank { "mp4" }
        val fileName = "${FileNameSanitizer.sanitize(videoInfo.title)}-${videoInfo.videoId}.$extension"
        val data = Data.Builder()
            .putString(DownloadWorker.KEY_URL, directUrl)
            .putString(DownloadWorker.KEY_FILE_NAME, fileName)
            .putString(DownloadWorker.KEY_TITLE, videoInfo.title)
            .putString(DownloadWorker.KEY_VIDEO_ID, videoInfo.videoId)
            .build()
        val request = OneTimeWorkRequest.Builder(DownloadWorker::class.java)
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueue(request)
        return request
    }
}
