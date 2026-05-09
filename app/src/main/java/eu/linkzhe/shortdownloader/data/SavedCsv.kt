package eu.linkzhe.shortdownloader.data

import android.net.Uri

data class SavedCsv(
    val uri: Uri,
    val displayName: String,
    val readablePath: String,
    val relativePath: String,
    val publicPath: String
)
