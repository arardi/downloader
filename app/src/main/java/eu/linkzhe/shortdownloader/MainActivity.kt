package eu.linkzhe.shortdownloader

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import eu.linkzhe.shortdownloader.data.CsvExportManager
import eu.linkzhe.shortdownloader.data.DownloadHistoryStore
import eu.linkzhe.shortdownloader.download.DownloadManager
import eu.linkzhe.shortdownloader.download.DownloadWorker
import eu.linkzhe.shortdownloader.extractor.VideoExtractor
import eu.linkzhe.shortdownloader.extractor.YtdownApiExtractor
import eu.linkzhe.shortdownloader.model.AnalyzedUrl
import eu.linkzhe.shortdownloader.model.DownloadFormat
import eu.linkzhe.shortdownloader.model.DownloadedVideo
import eu.linkzhe.shortdownloader.model.PreparedDownload
import eu.linkzhe.shortdownloader.model.VideoInfo
import eu.linkzhe.shortdownloader.util.StoragePermissionHelper
import eu.linkzhe.shortdownloader.util.TimeFormat
import eu.linkzhe.shortdownloader.util.UrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    private val extractor: VideoExtractor = YtdownApiExtractor()
    private val imageClient = OkHttpClient()
    private lateinit var downloadManager: DownloadManager
    private lateinit var historyStore: DownloadHistoryStore
    private lateinit var csvExportManager: CsvExportManager

    private lateinit var screenContainer: FrameLayout
    private lateinit var downloadBottomSheet: View
    private lateinit var downloadPanelTitle: TextView
    private lateinit var downloadPanelPercent: TextView
    private lateinit var downloadPanelToggle: ImageButton
    private lateinit var downloadPanelClose: ImageButton
    private lateinit var downloadPanelContent: View
    private lateinit var downloadStatusText: TextView
    private lateinit var downloadFileText: TextView
    private lateinit var downloadProgress: ProgressBar
    private lateinit var openFileButton: Button
    private lateinit var retryButton: Button

    private var urlInput: EditText? = null
    private var loadingContainer: View? = null
    private var loadingText: TextView? = null
    private var statusText: TextView? = null
    private var detailCard: View? = null
    private var thumbnailView: ImageView? = null
    private var titleText: TextView? = null
    private var channelText: TextView? = null
    private var durationText: TextView? = null
    private var viewsText: TextView? = null
    private var videoIdText: TextView? = null
    private var formatSection: View? = null
    private var formatsContainer: LinearLayout? = null

    private val formatButtons = mutableListOf<Button>()
    private var currentScreen = ScreenState.HOME
    private var lastBackPressedTime = 0L
    private var currentVideoInfo: VideoInfo? = null
    private var completedUri: Uri? = null
    private var completedReadablePath: String? = null
    private var activeFormat: DownloadFormat? = null
    private var lastSelectedFormat: DownloadFormat? = null
    private var activeFormatAutoReprepared = false
    private var isDownloadPanelExpanded = true
    private var pendingCsvExport = false
    private var pendingCsvAfterPermission = false
    private var hasCsvError = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = ContextCompat.getColor(this, R.color.app_background)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.app_background)
        downloadManager = DownloadManager(applicationContext)
        historyStore = DownloadHistoryStore(applicationContext)
        csvExportManager = CsvExportManager(applicationContext)
        bindRootViews()
        bindBackNavigation()
        showHome()
    }

    private fun bindRootViews() {
        screenContainer = findViewById(R.id.screenContainer)
        downloadBottomSheet = findViewById(R.id.downloadBottomSheet)
        downloadPanelTitle = findViewById(R.id.downloadPanelTitle)
        downloadPanelPercent = findViewById(R.id.downloadPanelPercent)
        downloadPanelToggle = findViewById(R.id.downloadPanelToggle)
        downloadPanelClose = findViewById(R.id.downloadPanelClose)
        downloadPanelContent = findViewById(R.id.downloadPanelContent)
        downloadStatusText = findViewById(R.id.downloadStatusText)
        downloadFileText = findViewById(R.id.downloadFileText)
        downloadProgress = findViewById(R.id.downloadProgress)
        openFileButton = findViewById(R.id.openFileButton)
        retryButton = findViewById(R.id.retryButton)
        downloadPanelToggle.setOnClickListener { setDownloadPanelExpanded(!isDownloadPanelExpanded) }
        downloadPanelClose.setOnClickListener { downloadBottomSheet.visibility = View.GONE }
        openFileButton.setOnClickListener { openCompletedFile() }
        retryButton.setOnClickListener { lastSelectedFormat?.let { startDownload(it) } }
    }

    private fun bindBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentScreen != ScreenState.HOME) {
                    showHome()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressedTime <= EXIT_BACK_WINDOW_MS) {
                    finish()
                } else {
                    lastBackPressedTime = now
                    Toast.makeText(this@MainActivity, "Tekan sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun showHome() {
        currentScreen = ScreenState.HOME
        screenContainer.removeAllViews()
        val home = LayoutInflater.from(this).inflate(R.layout.screen_home, screenContainer, false)
        screenContainer.addView(home)
        home.findViewById<Button>(R.id.newDownloadButton).setOnClickListener { showDownloader() }
        home.findViewById<Button>(R.id.exportCsvButton).setOnClickListener { exportCsvFromHome() }
        home.findViewById<Button>(R.id.fixCsvPermissionButton).setOnClickListener { fixCsvPermission() }
        renderHome(home)
    }

    private fun showDownloader(prefillUrl: String? = null, autoAnalyze: Boolean = false) {
        currentScreen = ScreenState.DOWNLOADER
        screenContainer.removeAllViews()
        val downloader = LayoutInflater.from(this).inflate(R.layout.screen_downloader, screenContainer, false)
        screenContainer.addView(downloader)
        bindDownloaderViews(downloader)
        bindDownloaderActions(downloader)
        if (!prefillUrl.isNullOrBlank()) {
            urlInput?.setText(prefillUrl)
            if (autoAnalyze) analyzeUrl()
        }
    }

    private fun bindDownloaderViews(root: View) {
        urlInput = root.findViewById(R.id.urlInput)
        loadingContainer = root.findViewById(R.id.loadingContainer)
        loadingText = root.findViewById(R.id.loadingText)
        statusText = root.findViewById(R.id.statusText)
        detailCard = root.findViewById(R.id.detailCard)
        thumbnailView = root.findViewById(R.id.thumbnailView)
        titleText = root.findViewById(R.id.titleText)
        channelText = root.findViewById(R.id.channelText)
        durationText = root.findViewById(R.id.durationText)
        viewsText = root.findViewById(R.id.viewsText)
        videoIdText = root.findViewById(R.id.videoIdText)
        formatSection = root.findViewById(R.id.formatSection)
        formatsContainer = root.findViewById(R.id.formatsContainer)
    }

    private fun bindDownloaderActions(root: View) {
        root.findViewById<ImageButton>(R.id.backHomeButton).setOnClickListener { showHome() }
        root.findViewById<Button>(R.id.pasteButton).setOnClickListener { pasteFromClipboard() }
        root.findViewById<Button>(R.id.clearButton).setOnClickListener { clearInput() }
        root.findViewById<Button>(R.id.analyzeButton).setOnClickListener { analyzeUrl() }
        root.findViewById<Button>(R.id.refreshButton).setOnClickListener { analyzeUrl() }
    }

    private fun renderHome(root: View) {
        val downloads = historyStore.getDownloads()
        val downloadsContainer = root.findViewById<LinearLayout>(R.id.recentDownloadsContainer)
        val downloadsEmptyText = root.findViewById<TextView>(R.id.downloadsEmptyText)
        val fixCsvPermissionButton = root.findViewById<Button>(R.id.fixCsvPermissionButton)
        downloadsContainer.removeAllViews()
        downloadsEmptyText.visibility = if (downloads.isEmpty()) View.VISIBLE else View.GONE
        fixCsvPermissionButton.visibility = if (hasCsvError) View.VISIBLE else View.GONE
        downloads.forEach { download ->
            val item = LayoutInflater.from(this).inflate(R.layout.item_downloaded_video, downloadsContainer, false)
            item.findViewById<TextView>(R.id.downloadedTitle).text = download.title
            item.findViewById<TextView>(R.id.downloadedMeta).text = listOfNotNull(
                download.quality,
                download.fileSizeText,
                download.channel,
                formatDate(download.downloadedAt)
            ).joinToString(" • ")
            item.findViewById<TextView>(R.id.downloadedPath).text = download.readablePath
            item.findViewById<Button>(R.id.openDownloadedButton).setOnClickListener { openVideoUri(download.contentUri) }
            item.findViewById<Button>(R.id.copyPathButton).setOnClickListener { copyToClipboard("Video path", download.readablePath, "Path copied") }
            item.findViewById<Button>(R.id.csvButton).setOnClickListener { openCsvForDownload(download) }
            downloadsContainer.addView(item)
        }

        val urls = historyStore.getRecentUrls()
        val urlsContainer = root.findViewById<LinearLayout>(R.id.recentUrlsContainer)
        val urlsEmptyText = root.findViewById<TextView>(R.id.recentUrlsEmptyText)
        urlsContainer.removeAllViews()
        urlsEmptyText.visibility = if (urls.isEmpty()) View.VISIBLE else View.GONE
        urls.forEach { recentUrl ->
            val item = LayoutInflater.from(this).inflate(R.layout.item_recent_url, urlsContainer, false)
            bindRecentUrlItem(item, recentUrl)
            urlsContainer.addView(item)
        }
    }

    private fun bindRecentUrlItem(item: View, recentUrl: AnalyzedUrl) {
        item.findViewById<TextView>(R.id.recentUrlTitle).text = recentUrl.title.ifBlank { recentUrl.videoId }
        item.findViewById<TextView>(R.id.recentUrlText).text = recentUrl.url
        item.findViewById<TextView>(R.id.recentUrlDate).text = formatDate(recentUrl.analyzedAt)
        val reAnalyze = { showDownloader(recentUrl.url, autoAnalyze = true) }
        item.setOnClickListener { reAnalyze() }
        item.findViewById<Button>(R.id.reAnalyzeButton).setOnClickListener { reAnalyze() }
    }

    private fun pasteFromClipboard() {
        val clipboard = ContextCompat.getSystemService(this, ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isNotBlank()) urlInput?.setText(text)
    }

    private fun clearInput() {
        urlInput?.setText("")
        statusText?.visibility = View.GONE
        detailCard?.visibility = View.GONE
        formatSection?.visibility = View.GONE
        completedUri = null
        completedReadablePath = null
        activeFormat = null
        lastSelectedFormat = null
        activeFormatAutoReprepared = false
        currentVideoInfo = null
        formatButtons.clear()
    }

    private fun analyzeUrl() {
        val input = urlInput?.text?.toString().orEmpty()
        val videoId = UrlParser.extractVideoId(input)
        if (videoId == null) {
            showError("Invalid URL. Use a YouTube video URL, youtu.be link, or watch URL with an 11-character video ID.")
            return
        }

        loadingText?.text = "Analyzing video..."
        loadingContainer?.visibility = View.VISIBLE
        statusText?.visibility = View.GONE
        lifecycleScope.launch {
            val result = runCatching { extractor.fetchInfo(input) }
            loadingContainer?.visibility = View.GONE
            result.onSuccess {
                historyStore.addRecentUrl(input, it.title, it.videoId)
                renderVideoInfo(it)
            }.onFailure { showError("Unable to analyze this video. ${it.message.orEmpty()}".trim()) }
        }
    }

    private fun renderVideoInfo(videoInfo: VideoInfo) {
        currentVideoInfo = videoInfo
        detailCard?.visibility = View.VISIBLE
        titleText?.text = videoInfo.title
        channelText?.text = listOfNotNull(videoInfo.channel ?: "Unknown channel", videoInfo.username?.let { "@$it" }).joinToString(" • ")
        durationText?.text = videoInfo.durationText ?: TimeFormat.duration(videoInfo.durationSeconds)
        viewsText?.text = videoInfo.viewsText ?: "Views unknown"
        videoIdText?.text = videoInfo.videoId.take(6)
        showInfo("Ready: choose a quality to download")
        loadThumbnail(videoInfo.thumbnailUrl)
        renderFormats(videoInfo)
    }

    private fun renderFormats(videoInfo: VideoInfo) {
        formatSection?.visibility = View.VISIBLE
        formatsContainer?.removeAllViews()
        formatButtons.clear()

        val mp4Formats: List<DownloadFormat> = videoInfo.formats
            .filter { format ->
                format.type.equals("Video", ignoreCase = true) &&
                    format.extension.equals("mp4", ignoreCase = true) &&
                    format.mediaUrl.isNotBlank()
            }

        if (mp4Formats.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No MP4 video quality returned by API."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 14f
                setPadding(0, 14, 0, 0)
            }
            formatsContainer?.addView(empty)
            return
        }

        mp4Formats.forEach { format: DownloadFormat ->
            val item = LayoutInflater.from(this).inflate(R.layout.item_format, formatsContainer, false)
            item.findViewById<TextView>(R.id.qualityBadge).text = format.qualityBadge()
            item.findViewById<TextView>(R.id.formatLabel).text = format.displayTitle()
            item.findViewById<TextView>(R.id.formatMeta).text = listOfNotNull(
                format.resolution,
                format.extension.uppercase(),
                format.fileSizeText
            ).joinToString(" • ")
            item.findViewById<TextView>(R.id.taskBadge).apply {
                text = "API ready"
                visibility = View.VISIBLE
            }

            val downloadButton = item.findViewById<Button>(R.id.downloadButton)
            downloadButton.isEnabled = true
            downloadButton.alpha = 1f
            downloadButton.setOnClickListener { startDownload(format) }
            formatButtons.add(downloadButton)
            formatsContainer?.addView(item)
        }
    }

    private fun startDownload(format: DownloadFormat) {
        if (!hasLegacyWritePermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_WRITE_STORAGE)
            showError("Storage permission is required on Android 9 and below. Tap Download again after granting permission.")
            return
        }

        activeFormat = format
        lastSelectedFormat = format
        activeFormatAutoReprepared = false
        prepareAndStartDownload(format)
    }

    private fun prepareAndStartDownload(format: DownloadFormat) {
        setFormatButtonsEnabled(false)
        completedUri = null
        completedReadablePath = null
        downloadBottomSheet.visibility = View.VISIBLE
        setDownloadPanelExpanded(true)
        openFileButton.visibility = View.GONE
        retryButton.visibility = View.GONE
        downloadProgress.isIndeterminate = true
        downloadProgress.progress = 5
        downloadPanelTitle.text = "Preparing..."
        downloadFileText.text = "Preparing ${format.qualityBadge()}"
        downloadStatusText.text = format.fileSizeText?.let { "Estimated size: $it" } ?: "Waiting for final file URL"
        downloadPanelPercent.text = "5%"
        statusText?.visibility = View.GONE

        lifecycleScope.launch {
            val preparedResult = runCatching {
                extractor.prepareDownload(format) { message ->
                    runOnUiThread {
                        downloadPanelTitle.text = message
                        downloadStatusText.text = "Preparing final file URL"
                    }
                }
            }
            preparedResult.onSuccess { prepared ->
                beginPreparedDownload(prepared)
            }.onFailure {
                setFormatButtonsEnabled(true)
                downloadProgress.isIndeterminate = false
                downloadPanelTitle.text = "Download failed"
                downloadFileText.text = "Preparation failed"
                downloadStatusText.text = it.message ?: "Final URL is not ready. Please try again."
                retryButton.visibility = View.VISIBLE
                openFileButton.visibility = View.GONE
                showError("Could not prepare this quality. ${it.message.orEmpty()}".trim())
            }
        }
    }

    private fun beginPreparedDownload(prepared: PreparedDownload) {
        statusText?.visibility = View.GONE
        downloadProgress.isIndeterminate = false
        downloadProgress.progress = 0
        retryButton.visibility = View.GONE
        openFileButton.visibility = View.GONE
        downloadPanelTitle.text = "Downloading..."
        downloadFileText.text = prepared.fileName
        val channel = currentVideoInfo?.channel?.takeIf { it.isNotBlank() } ?: "UnknownChannel"
        downloadStatusText.text = prepared.fileSizeText?.let { "File size: $it" } ?: "Saving to Movies/ZaVideoDownloader/$channel"
        downloadPanelPercent.text = "0%"
        val request = runCatching { downloadManager.download(prepared, currentVideoInfo, lastSelectedFormat) }
            .getOrElse {
                setFormatButtonsEnabled(true)
                downloadPanelTitle.text = "Download failed"
                downloadStatusText.text = it.message ?: getString(R.string.no_downloadable_format)
                retryButton.visibility = View.VISIBLE
                showError(it.message ?: getString(R.string.no_downloadable_format))
                return
            }
        observeDownload(request.id)
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
        val outputUri = info.outputData.getString(DownloadWorker.KEY_CONTENT_URI)
            ?: info.outputData.getString(DownloadWorker.KEY_OUTPUT_URI)
            ?: data.getString(DownloadWorker.KEY_OUTPUT_URI)
        val readablePath = info.outputData.getString(DownloadWorker.KEY_READABLE_PATH)
            ?: data.getString(DownloadWorker.KEY_READABLE_PATH)
            ?: outputUri
        val relativePath = info.outputData.getString(DownloadWorker.KEY_RELATIVE_PATH)
            ?: readablePath?.substringBeforeLast('/', missingDelimiterValue = "Movies/ZaVideoDownloader")

        downloadProgress.isIndeterminate = false
        downloadProgress.progress = progress
        downloadFileText.text = fileName.ifBlank { "Download" }
        downloadPanelTitle.text = if (info.state == WorkInfo.State.SUCCEEDED) "Download completed" else status
        downloadStatusText.text = if (speed > 0 && info.state == WorkInfo.State.RUNNING) {
            "${TimeFormat.bytes(speed)}/s • ${relativePath ?: "Movies/ZaVideoDownloader"}"
        } else {
            error ?: (relativePath ?: "Movies/ZaVideoDownloader")
        }
        downloadPanelPercent.text = "$progress%"

        if (info.state == WorkInfo.State.SUCCEEDED && !outputUri.isNullOrBlank()) {
            val wasAlreadyCompleted = completedUri != null
            completedUri = Uri.parse(outputUri)
            completedReadablePath = readablePath
            downloadFileText.text = fileName.ifBlank { "Download completed" }
            downloadPanelTitle.text = "Download completed"
            downloadStatusText.text = "Saved to ${relativePath ?: readablePath ?: "Movies/ZaVideoDownloader"}"
            retryButton.visibility = View.GONE
            openFileButton.visibility = View.VISIBLE
            setFormatButtonsEnabled(true)
            if (!wasAlreadyCompleted) recordCompletedDownload(outputUri, readablePath.orEmpty(), fileName)
        } else if (info.state == WorkInfo.State.FAILED) {
            val message = error ?: "Download failed"
            if (shouldAutoReprepare(message)) {
                retryExpiredFinalUrl()
                return
            }
            downloadFileText.text = "Download failed"
            downloadPanelTitle.text = "Download failed"
            downloadStatusText.text = message
            retryButton.visibility = View.VISIBLE
            openFileButton.visibility = View.GONE
            setFormatButtonsEnabled(true)
        }
    }

    private fun recordCompletedDownload(contentUri: String, readablePath: String, fileName: String) {
        val videoInfo = currentVideoInfo
        val format = lastSelectedFormat
        val download = DownloadedVideo(
            id = System.currentTimeMillis(),
            title = videoInfo?.title ?: fileName.ifBlank { "Downloaded video" },
            originalUrl = videoInfo?.originalUrl ?: urlInput?.text?.toString().orEmpty(),
            videoId = videoInfo?.videoId.orEmpty(),
            channel = videoInfo?.channel,
            description = videoInfo?.description,
            tags = videoInfo?.tags,
            quality = format?.qualityBadge(),
            fileSizeText = format?.fileSizeText,
            contentUri = contentUri,
            readablePath = readablePath.ifBlank { contentUri },
            downloadedAt = System.currentTimeMillis()
        )
        historyStore.addDownload(download)
        val savedDownloads = historyStore.getDownloads()
        runCatching { csvExportManager.appendOrCreate(download, savedDownloads) }
            .onFailure {
                hasCsvError = true
                Toast.makeText(
                    this,
                    "Video tersimpan, tapi CSV gagal dibuat. Tap Export CSV dan izinkan storage.",
                    Toast.LENGTH_LONG
                ).show()
            }
        if (currentScreen == ScreenState.HOME) showHome()
    }

    private fun setDownloadPanelExpanded(expanded: Boolean) {
        isDownloadPanelExpanded = expanded
        downloadPanelContent.visibility = if (expanded) View.VISIBLE else View.GONE
        downloadPanelToggle.setImageResource(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up)
        downloadPanelToggle.contentDescription = if (expanded) "Collapse download panel" else "Expand download panel"
    }

    private fun exportCsvFromHome() {
        val downloads = historyStore.getDownloads()

        if (downloads.isEmpty()) {
            Toast.makeText(
                this,
                "Belum ada video yang didownload. CSV akan dibuat otomatis setelah download pertama.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (StoragePermissionHelper.needsLegacyWritePermission(this)) {
            pendingCsvExport = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_STORAGE
            )
            return
        }

        safeExportCsv()
    }

    private fun safeExportCsv() {
        runCatching {
            val exported = csvExportManager.exportAll(historyStore.getDownloads())
            if (exported.isEmpty()) {
                Toast.makeText(this, "Belum ada data CSV.", Toast.LENGTH_LONG).show()
            } else {
                hasCsvError = false
                Toast.makeText(
                    this,
                    "CSV saved to Movies/ZaVideoDownloader/csv",
                    Toast.LENGTH_LONG
                ).show()
                if (currentScreen == ScreenState.HOME) showHome()
            }
        }.onFailure { error ->
            handleCsvError(error)
        }
    }

    private fun handleCsvError(error: Throwable) {
        hasCsvError = true
        if (currentScreen == ScreenState.HOME) showHome()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !StoragePermissionHelper.hasAllFilesAccess()
        ) {
            AlertDialog.Builder(this)
                .setTitle("Storage permission needed")
                .setMessage(
                    "CSV gagal dibuat di folder Movies. Izinkan akses file agar aplikasi bisa menyimpan CSV ke Movies/ZaVideoDownloader/csv.\n\nDetail: ${error.message.orEmpty()}"
                )
                .setPositiveButton("Izinkan") { _, _ ->
                    pendingCsvAfterPermission = true
                    StoragePermissionHelper.openAllFilesAccessSettings(this)
                }
                .setNegativeButton("Nanti", null)
                .show()
        } else {
            Toast.makeText(
                this,
                "Gagal membuat CSV: ${error.message.orEmpty()}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun fixCsvPermission() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                pendingCsvAfterPermission = true
                StoragePermissionHelper.openAllFilesAccessSettings(this)
            }
            StoragePermissionHelper.needsLegacyWritePermission(this) -> {
                pendingCsvExport = true
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_WRITE_STORAGE
                )
            }
            else -> safeExportCsv()
        }
    }

    private fun setFormatButtonsEnabled(enabled: Boolean) {
        formatButtons.forEach { button ->
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.45f
        }
    }

    private fun shouldAutoReprepare(message: String): Boolean {
        val lower = message.lowercase()
        return !activeFormatAutoReprepared && activeFormat != null &&
            listOf("expired", "not ready", "403", "404", "410").any { it in lower }
    }

    private fun retryExpiredFinalUrl() {
        val format = activeFormat ?: return
        activeFormatAutoReprepared = true
        downloadProgress.isIndeterminate = true
        downloadFileText.text = "Preparing again..."
        downloadPanelTitle.text = "Preparing again..."
        downloadStatusText.text = "Final URL expired. Refreshing token"
        retryButton.visibility = View.GONE
        statusText?.visibility = View.GONE
        prepareAndStartDownload(format)
    }

    private fun DownloadFormat.qualityBadge(): String = resolution?.let { Regex("\\d+").find(it)?.value }
        ?.let { "${it}p" }
        ?: quality
        ?: "MP4"

    private fun DownloadFormat.displayTitle(): String {
        val height = resolution?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        val tier = when {
            height == null -> quality ?: "MP4"
            height >= 1080 -> "FHD"
            height >= 720 -> "HD"
            else -> "SD"
        }
        return listOfNotNull(tier, resolution).joinToString(" • ")
    }

    private fun openCompletedFile() {
        val uri = completedUri ?: return
        openVideoUri(uri.toString())
    }

    private fun openVideoUri(value: String) {
        openUri(value, "video/mp4", "Open video", "Unable to open this video")
    }

    private fun openCsvForDownload(download: DownloadedVideo) {
        val saved = runCatching { csvExportManager.exportChannel(download.channel, historyStore.getDownloads()) }
            .getOrElse {
                handleCsvError(it)
                return
            }
        openUri(saved.uri.toString(), "text/csv", "Open CSV", "Unable to open this CSV")
    }

    private fun openUri(value: String, mimeType: String, chooserTitle: String, errorMessage: String) {
        val uri = Uri.parse(value)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, chooserTitle)) }
            .onFailure { Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show() }
    }

    private fun copyToClipboard(label: String, value: String, toastMessage: String = "Copied") {
        val clipboard = ContextCompat.getSystemService(this, ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()

        if (pendingCsvAfterPermission) {
            pendingCsvAfterPermission = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()
            ) {
                safeExportCsv()
            } else {
                Toast.makeText(
                    this,
                    "Permission storage belum diizinkan.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_WRITE_STORAGE) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED

            if (granted) {
                if (pendingCsvExport) {
                    pendingCsvExport = false
                    safeExportCsv()
                }
            } else {
                pendingCsvExport = false
                Toast.makeText(
                    this,
                    "Storage permission dibutuhkan untuk export CSV.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun hasLegacyWritePermission(): Boolean = !StoragePermissionHelper.needsLegacyWritePermission(this)

    private fun loadThumbnail(url: String?) {
        thumbnailView?.setImageResource(R.drawable.ic_video)
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
            if (bitmap != null) thumbnailView?.setImageBitmap(bitmap)
        }
    }

    private fun showInfo(message: String) {
        statusText?.text = message
        statusText?.setTextColor(ContextCompat.getColor(this, R.color.success))
        statusText?.setBackgroundResource(R.drawable.bg_status_success)
        statusText?.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        statusText?.text = message
        statusText?.setTextColor(ContextCompat.getColor(this, R.color.danger))
        statusText?.setBackgroundResource(R.drawable.bg_status_error)
        statusText?.visibility = View.VISIBLE
    }

    private fun formatDate(timestamp: Long): String = DATE_FORMAT.format(Date(timestamp))

    private enum class ScreenState { HOME, DOWNLOADER }

    companion object {
        private const val REQUEST_WRITE_STORAGE = 28
        private const val EXIT_BACK_WINDOW_MS = 2_000L
        private val DATE_FORMAT = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US)
    }
}
