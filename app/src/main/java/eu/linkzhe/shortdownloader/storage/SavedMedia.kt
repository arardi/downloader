package eu.linkzhe.shortdownloader.storage

import android.net.Uri

data class SavedMedia(
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val readablePath: String
)
