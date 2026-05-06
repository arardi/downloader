package eu.linkzhe.shortdownloader.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

class MediaStoreSaver(private val context: Context) {
    data class PendingVideo(val uri: Uri, val stream: OutputStream, val legacyPath: String?)

    fun createVideo(displayName: String, mimeType: String = "video/mp4"): PendingVideo {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ZaShortsDownloader")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ZaShortsDownloader")
            directory.mkdirs()
            val file = File(directory, displayName)
            values.put(MediaStore.Video.Media.DATA, file.absolutePath)
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values) ?: error("Cannot create MediaStore entry.")
        val stream = resolver.openOutputStream(uri) ?: error("Cannot open MediaStore output stream.")
        return PendingVideo(uri, stream, values.getAsString(MediaStore.Video.Media.DATA))
    }

    fun publish(uri: Uri, legacyPath: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        } else if (!legacyPath.isNullOrBlank()) {
            MediaScannerConnection.scanFile(context, arrayOf(legacyPath), arrayOf("video/mp4"), null)
        }
    }

    fun delete(uri: Uri) {
        context.contentResolver.delete(uri, null, null)
    }
}
