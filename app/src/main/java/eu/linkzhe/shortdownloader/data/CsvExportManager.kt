package eu.linkzhe.shortdownloader.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import eu.linkzhe.shortdownloader.model.DownloadedVideo
import eu.linkzhe.shortdownloader.storage.SavedMedia
import eu.linkzhe.shortdownloader.util.StoragePathSanitizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CsvExportManager(private val context: Context) {
    fun saveDownloadCsv(downloadedVideo: DownloadedVideo): SavedMedia {
        val fileName = csvFileName(downloadedVideo)
        return saveCsv(fileName, buildDownloadCsv(downloadedVideo))
    }

    fun exportAll(downloads: List<DownloadedVideo>): SavedMedia {
        val content = buildString {
            appendLine(HEADER)
            downloads.forEach { appendLine(it.toCsvLine()) }
        }
        return saveCsv("${StoragePathSanitizer.APP_DIRECTORY}_downloads.csv", content)
    }

    private fun saveCsv(displayName: String, content: String): SavedMedia {
        val relativePath = "${Environment.DIRECTORY_MOVIES}/${StoragePathSanitizer.APP_DIRECTORY}/csv"
        val bytes = content.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: error("Cannot create CSV file.")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Cannot open CSV output stream.")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                SavedMedia(uri, displayName, relativePath, "$relativePath/$displayName")
            } catch (throwable: Throwable) {
                resolver.delete(uri, null, null)
                throw throwable
            }
        } else {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "${StoragePathSanitizer.APP_DIRECTORY}/csv")
            directory.mkdirs()
            val file = File(directory, displayName)
            file.writeBytes(bytes)
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(MIME_TYPE), null)
            SavedMedia(Uri.fromFile(file), displayName, relativePath, "$relativePath/$displayName")
        }
    }

    private fun buildDownloadCsv(downloadedVideo: DownloadedVideo): String = buildString {
        appendLine(HEADER)
        appendLine(downloadedVideo.toCsvLine())
    }

    private fun csvFileName(downloadedVideo: DownloadedVideo): String {
        val channel = StoragePathSanitizer.csvSafeName(downloadedVideo.channel, StoragePathSanitizer.UNKNOWN_CHANNEL)
        val title = StoragePathSanitizer.csvSafeName(downloadedVideo.title, "video")
        return "${channel}_${title}.csv"
    }

    private fun DownloadedVideo.toCsvLine(): String = listOf(
        title,
        tags,
        description,
        readablePath,
        originalUrl,
        videoId,
        channel,
        quality,
        fileSizeText,
        CSV_DATE_FORMAT.format(Date(downloadedAt))
    ).joinToString(",") { csv(it) }

    private fun csv(value: String?): String {
        val safe = value.orEmpty()
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    companion object {
        private const val MIME_TYPE = "text/csv"
        private const val HEADER = "title,tag,description,path,original_url,video_id,channel,quality,file_size,downloaded_at"
        private val CSV_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
