package eu.linkzhe.shortdownloader.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import eu.linkzhe.shortdownloader.util.StoragePathSanitizer
import java.io.File
import java.io.OutputStream

class MediaStoreSaver(private val context: Context) {
    data class PendingVideo(
        val uri: Uri,
        val stream: OutputStream,
        val legacyPath: String?,
        val displayName: String,
        val relativePath: String,
        val readablePath: String,
        val publicPath: String
    )

    fun createVideo(displayName: String, mimeType: String = "video/mp4", channelName: String?): PendingVideo {
        val safeChannel = StoragePathSanitizer.sanitizeFolderName(channelName)
        val relativePath = "${Environment.DIRECTORY_MOVIES}/${StoragePathSanitizer.APP_DIRECTORY}/$safeChannel"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "${StoragePathSanitizer.APP_DIRECTORY}/$safeChannel")
            directory.mkdirs()
            val file = File(directory, displayName)
            values.put(MediaStore.Video.Media.DATA, file.absolutePath)
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values) ?: error("Cannot create MediaStore entry.")
        val stream = resolver.openOutputStream(uri) ?: error("Cannot open MediaStore output stream.")
        val legacyPath = values.getAsString(MediaStore.Video.Media.DATA)
        val readablePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "$relativePath/$displayName"
        } else {
            legacyPath ?: "$relativePath/$displayName"
        }
        val publicPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "/storage/emulated/0/$relativePath/$displayName"
        } else {
            legacyPath ?: readablePath.toPublicMoviesPath()
        }
        return PendingVideo(
            uri = uri,
            stream = stream,
            legacyPath = legacyPath,
            displayName = displayName,
            relativePath = relativePath,
            readablePath = readablePath,
            publicPath = publicPath
        )
    }

    fun saveVideoFile(
        sourceFile: File,
        displayName: String,
        mimeType: String = "video/mp4",
        channelName: String?
    ): SavedMedia {
        val pending = createVideo(displayName, mimeType, channelName)
        try {
            pending.stream.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            publish(pending.uri, pending.legacyPath, mimeType)
            return SavedMedia(
                uri = pending.uri,
                displayName = pending.displayName,
                relativePath = pending.relativePath,
                readablePath = pending.readablePath,
                publicPath = pending.publicPath
            )
        } catch (throwable: Throwable) {
            delete(pending.uri)
            throw throwable
        }
    }

    fun publish(uri: Uri, legacyPath: String?, mimeType: String = "video/mp4") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        } else if (!legacyPath.isNullOrBlank()) {
            MediaScannerConnection.scanFile(context, arrayOf(legacyPath), arrayOf(mimeType), null)
        }
    }

    fun delete(uri: Uri) {
        context.contentResolver.delete(uri, null, null)
    }
}

private fun String.toPublicMoviesPath(): String = if (startsWith("/storage/")) this else "/storage/emulated/0/$this"
