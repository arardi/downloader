package eu.linkzhe.shortdownloader.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import eu.linkzhe.shortdownloader.model.DownloadedVideo
import eu.linkzhe.shortdownloader.util.StoragePathSanitizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CsvExportManager(private val context: Context) {
    fun appendOrCreate(download: DownloadedVideo, channelDownloads: List<DownloadedVideo> = listOf(download)): SavedCsv {
        val sameChannelDownloads = channelDownloads
            .filter { StoragePathSanitizer.sanitizeFolderName(it.channel) == StoragePathSanitizer.sanitizeFolderName(download.channel) }
            .ifEmpty { listOf(download) }
        return saveChannelCsv(download.channel, sameChannelDownloads)
    }

    fun exportAll(downloads: List<DownloadedVideo>): List<SavedCsv> {
        if (downloads.isEmpty()) return emptyList()
        return downloads
            .groupBy { StoragePathSanitizer.sanitizeFolderName(it.channel) }
            .values
            .map { channelDownloads -> saveChannelCsv(channelDownloads.firstOrNull()?.channel, channelDownloads) }
    }

    fun exportChannel(channel: String?, downloads: List<DownloadedVideo>): SavedCsv {
        val sameChannelDownloads = downloads.filter {
            StoragePathSanitizer.sanitizeFolderName(it.channel) == StoragePathSanitizer.sanitizeFolderName(channel)
        }
        return saveChannelCsv(channel, sameChannelDownloads)
    }

    private fun saveChannelCsv(channel: String?, downloads: List<DownloadedVideo>): SavedCsv {
        val fileName = csvFileName(channel)
        val content = buildCsv(downloads)
        return saveCsv(fileName, content)
    }

    private fun saveCsv(displayName: String, content: String): SavedCsv {
        val relativePath = "${Environment.DIRECTORY_MOVIES}/${StoragePathSanitizer.APP_DIRECTORY}/csv"
        val bytes = content.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { saveCsvToMediaStore(displayName, relativePath, bytes) }
                .getOrElse { mediaStoreError ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                        saveDirectCsv(displayName, relativePath, bytes)
                    } else {
                        throw IllegalStateException("Unable to create CSV in Movies/ZaVideoDownloader/csv: ${mediaStoreError.message.orEmpty()}", mediaStoreError)
                    }
                }
        } else {
            saveLegacyCsv(displayName, relativePath, bytes)
        }
    }

    private fun saveCsvToMediaStore(displayName: String, relativePath: String, bytes: ByteArray): SavedCsv {
        val resolver = context.contentResolver
        val uri = findCsvUri(displayName, relativePath) ?: run {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: error("Cannot create CSV file.")
        }

        try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Cannot open CSV output stream.")
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null
            )
            return SavedCsv(uri, displayName, "$relativePath/$displayName", relativePath, publicCsvPath(relativePath, displayName))
        } catch (throwable: Throwable) {
            throw throwable
        }
    }

    private fun findCsvUri(displayName: String, relativePath: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(displayName, "$relativePath/")
        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    private fun saveLegacyCsv(displayName: String, relativePath: String, bytes: ByteArray): SavedCsv {
        return saveDirectCsv(displayName, relativePath, bytes)
    }

    private fun saveDirectCsv(displayName: String, relativePath: String, bytes: ByteArray): SavedCsv {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "${StoragePathSanitizer.APP_DIRECTORY}/csv"
        )
        if (!directory.exists() && !directory.mkdirs()) {
            error("Cannot create CSV folder at Movies/ZaVideoDownloader/csv.")
        }
        val file = File(directory, displayName)
        file.writeBytes(bytes)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(MIME_TYPE), null)
        return SavedCsv(Uri.fromFile(file), displayName, file.absolutePath, relativePath, file.absolutePath)
    }

    private fun buildCsv(downloads: List<DownloadedVideo>): String = buildString {
        appendLine(HEADER)
        downloads.forEach { appendLine(it.toCsvLine()) }
    }

    private fun csvFileName(channel: String?): String {
        val safeChannel = StoragePathSanitizer.csvSafeName(channel, StoragePathSanitizer.UNKNOWN_CHANNEL)
        return "${safeChannel}_downloads.csv"
    }

    private fun DownloadedVideo.toCsvLine(): String = listOf(
        title,
        tags,
        description,
        publicPath.ifBlank { readablePath.toPublicMoviesPath() },
        contentUri,
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

    private fun publicCsvPath(relativePath: String, displayName: String): String =
        "/storage/emulated/0/$relativePath/$displayName"

    private fun String.toPublicMoviesPath(): String = if (startsWith("/storage/")) this else "/storage/emulated/0/$this"

    companion object {
        private const val MIME_TYPE = "text/csv"
        private const val HEADER = "title,tags,description,path,content_uri,original_url,video_id,channel,quality,file_size,downloaded_at"
        private val CSV_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
