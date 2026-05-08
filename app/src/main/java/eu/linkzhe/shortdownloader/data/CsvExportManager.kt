package eu.linkzhe.shortdownloader.data

import android.content.Context
import android.os.Environment
import eu.linkzhe.shortdownloader.model.DownloadedVideo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CsvExportManager(private val context: Context) {
    fun appendDownload(downloadedVideo: DownloadedVideo): File {
        val file = csvFile()
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(HEADER + "\n")
        }
        file.appendText(downloadedVideo.toCsvLine() + "\n")
        return file
    }

    fun exportAll(downloads: List<DownloadedVideo>): File {
        val file = csvFile()
        file.parentFile?.mkdirs()
        file.writeText(buildString {
            appendLine(HEADER)
            downloads.forEach { appendLine(it.toCsvLine()) }
        })
        return file
    }

    fun csvFile(): File {
        val baseDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val directory = File(baseDirectory, "ZaVideoDownloader")
        return File(directory, FILE_NAME)
    }

    private fun DownloadedVideo.toCsvLine(): String = listOf(
        title,
        tags.orEmpty(),
        description.orEmpty(),
        filePathOrUri,
        originalUrl,
        videoId,
        channel.orEmpty(),
        quality.orEmpty(),
        fileSizeText.orEmpty(),
        CSV_DATE_FORMAT.format(Date(downloadedAt))
    ).joinToString(",") { it.csvEscape() }

    private fun String.csvEscape(): String {
        val escaped = replace("\"", "\"\"")
        return if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
    }

    companion object {
        private const val FILE_NAME = "za_video_downloads.csv"
        private const val HEADER = "title,tag,description,path,original_url,video_id,channel,quality,file_size,downloaded_at"
        private val CSV_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
