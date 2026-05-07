package eu.linkzhe.shortdownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import eu.linkzhe.shortdownloader.download.DownloadManager
import eu.linkzhe.shortdownloader.download.DownloadWorker
import eu.linkzhe.shortdownloader.extractor.VideoExtractor
import eu.linkzhe.shortdownloader.extractor.YtdownApiExtractor
import eu.linkzhe.shortdownloader.model.DownloadFormat
import eu.linkzhe.shortdownloader.model.VideoInfo
import eu.linkzhe.shortdownloader.util.TimeFormat
import eu.linkzhe.shortdownloader.util.UrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    private val extractor: VideoExtractor = YtdownApiExtractor()
    private val imageClient = OkHttpClient()
    private lateinit var downloadManager: DownloadManager

    private lateinit var urlInput: EditText
    private lateinit var loadingContainer: View
    private lateinit var loadingText: TextView
    private lateinit var statusText: TextView
    private lateinit var detailCard: View
    private lateinit var thumbnailView: ImageView
    private lateinit var titleText: TextView
    private lateinit var channelText: TextView
    private lateinit var durationText: TextView
    private lateinit var viewsText: TextView
    private lateinit var videoIdText: TextView
    private lateinit var formatSection: View
    private lateinit var formatsContainer: LinearLayout
    private lateinit var downloadCard: View
    private lateinit var downloadFileText: TextView
    private lateinit var downloadStatusText: TextView
    private lateinit var downloadSizeText: TextView
    private lateinit var downloadProgress: ProgressBar
    private lateinit var downloadPercentText: TextView
    private lateinit var openFileButton: Button

    private val formatButtons = mutableListOf<Button>()
    private var currentVideoInfo: VideoInfo? = null
    private var completedUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadManager = DownloadManager(applicationContext)
        bindViews()
        bindActions()
    }

    private fun bindViews() {
        urlInput = findViewById(R.id.urlInput)
        loadingContainer = findViewById(R.id.loadingContainer)
        loadingText = findViewById(R.id.loadingText)
        statusText = findViewById(R.id.statusText)
        detailCard = findViewById(R.id.detailCard)
        thumbnailView = findViewById(R.id.thumbnailView)
        titleText = findViewById(R.id.titleText)
        channelText = findViewById(R.id.channelText)
        durationText = findViewById(R.id.durationText)
        viewsText = findViewById(R.id.viewsText)
        videoIdText = findViewById(R.id.videoIdText)
        formatSection = findViewById(R.id.formatSection)
        formatsContainer = findViewById(R.id.formatsContainer)
        downloadCard = findViewById(R.id.downloadCard)
        downloadFileText = findViewById(R.id.downloadFileText)
        downloadStatusText = findViewById(R.id.downloadStatusText)
        downloadSizeText = findViewById(R.id.downloadSizeText)
        downloadProgress = findViewById(R.id.downloadProgress)
        downloadPercentText = findViewById(R.id.downloadPercentText)
        openFileButton = findViewById(R.id.openFileButton)
    }

    private fun bindActions() {
        findViewById<Button>(R.id.pasteButton).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.clearButton).setOnClickListener { clearInput() }
        findViewById<Button>(R.id.analyzeButton).setOnClickListener { analyzeUrl() }
        findViewById<Button>(R.id.refreshButton).setOnClickListener { analyzeUrl() }
        openFileButton.setOnClickListener { openCompletedFile() }
    }

    private fun pasteFromClipboard() {
        val clipboard = ContextCompat.getSystemService(this, ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isNotBlank()) urlInput.setText(text)
    }

    private fun clearInput() {
        urlInput.setText("")
        statusText.visibility = View.GONE
        detailCard.visibility = View.GONE
        formatSection.visibility = View.GONE
        downloadCard.visibility = View.GONE
        formatButtons.clear()
    }

    private fun analyzeUrl() {
        val input = urlInput.text.toString()
        val videoId = UrlParser.extractVideoId(input)
        if (videoId == null) {
            showError("Invalid URL. Use a YouTube Shorts, youtu.be, or watch URL with an 11-character video ID.")
            return
        }

        loadingText.text = "Analyzing video..."
        loadingContainer.visibility = View.VISIBLE
        statusText.visibility = View.GONE
        lifecycleScope.launch {
            val result = runCatching { extractor.fetchInfo(input) }
            loadingContainer.visibility = View.GONE
            result.onSuccess { renderVideoInfo(it) }
                .onFailure { showError("Unable to analyze this video. ${it.message.orEmpty()}".trim()) }
        }
    }

    private fun renderVideoInfo(videoInfo: VideoInfo) {
        currentVideoInfo = videoInfo
        detailCard.visibility = View.VISIBLE
        titleText.text = videoInfo.title
        channelText.text = "Channel: ${videoInfo.channel ?: "Unknown"} ${videoInfo.username.orEmpty()}".trim()
        durationText.text = videoInfo.durationText?.let { "Duration: $it" } ?: TimeFormat.duration(videoInfo.durationSeconds)
        viewsText.text = videoInfo.viewsText?.let { "Views: $it" } ?: "Views: Unknown"
        videoIdText.text = "Video ID: ${videoInfo.videoId}"
        showInfo("API status: ${videoInfo.apiStatus ?: "ok"}")
        loadThumbnail(videoInfo.thumbnailUrl)
        renderFormats(videoInfo)
    }

    private fun renderFormats(videoInfo: VideoInfo) {
        formatSection.visibility = View.VISIBLE
        formatsContainer.removeAllViews()
        formatButtons.clear()
        if (videoInfo.formats.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No downloadable format returned by API."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 14f
                setPadding(0, 14, 0, 0)
            }
            formatsContainer.addView(empty)
            return
        }

        videoInfo.formats.forEach { format ->
            val item = LayoutInflater.from(this).inflate(R.layout.item_format, formatsContainer, false)
            item.findViewById<TextView>(R.id.qualityBadge).text = format.qualityBadge()
            item.findViewById<TextView>(R.id.formatLabel).text = format.label
            item.findViewById<TextView>(R.id.formatMeta).text = listOfNotNull(
                format.resolution,
                format.extension.uppercase(),
                format.fileSizeText
            ).joinToString(" • ")
            item.findViewById<TextView>(R.id.taskBadge).visibility = if (format.mediaTask.equals("merge", ignoreCase = true)) View.VISIBLE else View.GONE
            val downloadButton = item.findViewById<Button>(R.id.downloadButton)
            val downloadable = format.mediaUrl.isNotBlank()
            downloadButton.isEnabled = downloadable
            downloadButton.alpha = if (downloadable) 1f else 0.5f
            downloadButton.setOnClickListener { startDownload(format) }
            formatButtons.add(downloadButton)
            formatsContainer.addView(item)
        }
    }

    private fun startDownload(format: DownloadFormat) {
        if (!hasLegacyWritePermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_WRITE_STORAGE)
            showError("Storage permission is required on Android 9 and below. Tap Download again after granting permission.")
            return
        }

        setFormatButtonsEnabled(false)
        downloadCard.visibility = View.VISIBLE
        openFileButton.visibility = View.GONE
        downloadProgress.progress = 0
        downloadFileText.text = format.label
        downloadStatusText.text = "Preparing ${format.qualityBadge()}..."
        downloadSizeText.text = format.fileSizeText?.let { "Estimated size: $it" }.orEmpty()
        downloadPercentText.text = "0%"
        showInfo("Preparing selected quality...")

        lifecycleScope.launch {
            val preparedResult = runCatching { extractor.prepareDownload(format) }
            preparedResult.onSuccess { prepared ->
                showInfo("Final URL ready. Downloading...")
                downloadStatusText.text = "Downloading..."
                downloadFileText.text = prepared.fileName
                downloadSizeText.text = prepared.fileSizeText?.let { "File size: $it" }.orEmpty()
                val request = runCatching { downloadManager.download(prepared) }
                    .getOrElse {
                        setFormatButtonsEnabled(true)
                        showError(it.message ?: getString(R.string.no_downloadable_format))
                        return@launch
                    }
                observeDownload(request.id)
            }.onFailure {
                setFormatButtonsEnabled(true)
                downloadStatusText.text = "Preparation failed"
                showError("Could not prepare this quality. Try another quality or analyze again.")
            }
        }
    }

    private fun observeDownload(id: java.util.UUID) {
        WorkManager.getInstance(applicationContext)
            .getWorkInfoByIdLiveData(id)
            .observe(this) { info -> renderDownloadInfo(info) }
    }

    private fun renderDownloadInfo(info: WorkInfo?) {
        if (info == null) return
        val data = if (info.state == WorkInfo.State.SUCCEEDED) info.outputData else info.progress
        val progress = data.getInt(DownloadWorker.KEY_PROGRESS, 0)
        val status = data.getString(DownloadWorker.KEY_STATUS) ?: info.state.name
        val fileName = data.getString(DownloadWorker.KEY_FILE_NAME).orEmpty()
        val speed = data.getLong(DownloadWorker.KEY_SPEED, -1L)
        val error = info.outputData.getString(DownloadWorker.KEY_ERROR)
        val outputUri = info.outputData.getString(DownloadWorker.KEY_OUTPUT_URI) ?: data.getString(DownloadWorker.KEY_OUTPUT_URI)

        downloadProgress.progress = progress
        downloadFileText.text = fileName.ifBlank { "Download" }
        downloadStatusText.text = error ?: status
        downloadPercentText.text = if (speed > 0) "$progress% • ${TimeFormat.bytes(speed)}/s" else "$progress%"

        if (info.state == WorkInfo.State.SUCCEEDED && !outputUri.isNullOrBlank()) {
            completedUri = Uri.parse(outputUri)
            downloadStatusText.text = "Completed"
            openFileButton.visibility = View.VISIBLE
            setFormatButtonsEnabled(true)
        } else if (info.state == WorkInfo.State.FAILED) {
            downloadStatusText.text = error ?: "Download failed"
            setFormatButtonsEnabled(true)
        }
    }

    private fun setFormatButtonsEnabled(enabled: Boolean) {
        formatButtons.forEach { button ->
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.45f
        }
    }

    private fun DownloadFormat.qualityBadge(): String = resolution?.let { Regex("\\d+").find(it)?.value }
        ?.let { "${it}p" }
        ?: quality
        ?: "MP4"

    private fun openCompletedFile() {
        val uri = completedUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Open video"))
    }

    private fun hasLegacyWritePermission(): Boolean = Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun loadThumbnail(url: String?) {
        thumbnailView.setImageResource(R.drawable.ic_video)
        if (url.isNullOrBlank()) return
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url(url).build()
                    imageClient.newCall(request).execute().use { response ->
                        BitmapFactory.decodeStream(response.body?.byteStream())
                    }
                }.getOrNull()
            }
            if (bitmap != null) thumbnailView.setImageBitmap(bitmap)
        }
    }

    private fun showInfo(message: String) {
        statusText.text = message
        statusText.setTextColor(ContextCompat.getColor(this, R.color.success))
        statusText.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        statusText.text = message
        statusText.setTextColor(ContextCompat.getColor(this, R.color.danger))
        statusText.visibility = View.VISIBLE
    }

    companion object {
        private const val REQUEST_WRITE_STORAGE = 28
    }
}
