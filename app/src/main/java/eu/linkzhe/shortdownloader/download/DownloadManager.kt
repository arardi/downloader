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
        val ytdlpFormat = format.ytdlpFormat
        val directUrl = format.directUrl
        if (ytdlpFormat.isNullOrBlank() && directUrl.isNullOrBlank()) {
            throw IllegalArgumentException("No downloadable format found.")
        }

        val fileName = "${FileNameSanitizer.sanitize(videoInfo.title)}-${videoInfo.videoId}.mp4"
        val data = Data.Builder()
            .putString(DownloadWorker.KEY_URL, videoInfo.originalUrl)
            .putString(DownloadWorker.KEY_DIRECT_URL, directUrl)
            .putString(DownloadWorker.KEY_YTDLP_FORMAT, ytdlpFormat)
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
