package com.akay.feature.downloads.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.akay.feature.downloads.engine.DirectDownloadEngine
import com.akay.feature.downloads.engine.DownloadProgressUnified
import com.akay.feature.downloads.engine.YtDlpEngine
import com.akay.feature.downloads.engine.YtDlpSetup
import com.akay.feature.downloads.notification.DownloadNotificationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class DownloadItem(
    val id: String,
    val url: String,
    val filename: String,
    val outputPath: String,
    val progress: Float = 0f,
    val speedStr: String = "",
    val totalStr: String = "",
    val status: ItemStatus = ItemStatus.QUEUED,
    val errorMsg: String? = null,
    val useYtDlp: Boolean = false,
    val formatId: String? = null
)

enum class ItemStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

data class DownloadManagerState(
    val downloads: List<DownloadItem> = emptyList(),
    val ytDlpReady: Boolean = false,
    val ytDlpSetupProgress: Int = 0,
    val isSettingUpYtDlp: Boolean = false,
    val setupError: String? = null
) {
    val active get() = downloads.filter { it.status == ItemStatus.RUNNING || it.status == ItemStatus.QUEUED }
    val completed get() = downloads.filter { it.status == ItemStatus.COMPLETED }
    val failed get() = downloads.filter { it.status == ItemStatus.FAILED || it.status == ItemStatus.CANCELLED }
}

@HiltViewModel
class DownloadViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(DownloadManagerState())
    val state: StateFlow<DownloadManagerState> = _state.asStateFlow()

    private val ytDlpEngine = YtDlpEngine(application)
    private val directEngine = DirectDownloadEngine(
        okhttp3.OkHttpClient.Builder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("User-Agent", "Mozilla/5.0 (Android) AxBrowser/1.0")
                    .build()
                chain.proceed(req)
            }
            .build()
    )
    private val downloadJobs = mutableMapOf<String, Job>()
    private val downloadDir: File = run {
        val ext = application.getExternalFilesDir(null)
        val base = ext ?: application.filesDir
        File(base, "AxBrowser Downloads").also { it.mkdirs() }
    }

    init {
        checkYtDlp()
    }

    private fun checkYtDlp() {
        val installed = YtDlpSetup.isInstalled(getApplication())
        _state.update { it.copy(ytDlpReady = installed) }
        if (!installed) {
            _state.update {
                it.copy(setupError = "yt-dlp not initialized. Restart the app.")
            }
        }
    }

    fun retryYtDlpCheck() {
        checkYtDlp()
    }

    fun enqueue(
        url: String,
        filename: String,
        useYtDlp: Boolean = true,
        formatId: String? = null
    ): String {
        val id = UUID.randomUUID().toString()
        val outputFile = uniqueFile(downloadDir, filename)
        val canUseYtDlp = useYtDlp && _state.value.ytDlpReady

        val item = DownloadItem(
            id = id, url = url, filename = outputFile.name,
            outputPath = outputFile.absolutePath, useYtDlp = canUseYtDlp, formatId = formatId,
            errorMsg = if (useYtDlp && !_state.value.ytDlpReady) "yt-dlp not available - using direct download" else null
        )
        _state.update { it.copy(downloads = it.downloads + item) }
        startDownload(item)
        return id
    }

    private fun startDownload(item: DownloadItem) {
        val job = viewModelScope.launch {
            updateItem(item.id) { it.copy(status = ItemStatus.RUNNING) }
            val ctx: Context = getApplication()

            val outputPath = if (item.useYtDlp) {
                "${downloadDir.absolutePath}/%(title)s.%(ext)s"
            } else {
                item.outputPath
            }

            val flow = if (item.useYtDlp) {
                ytDlpEngine.download(item.url, outputPath, item.formatId)
            } else {
                directEngine.download(item.id, item.url, File(item.outputPath))
            }

            flow.collect { progress ->
                when (progress) {
                    is DownloadProgressUnified.Running -> {
                        updateItem(item.id) {
                            it.copy(
                                progress = progress.percent,
                                speedStr = progress.speedStr,
                                totalStr = progress.totalBytesStr,
                                status   = ItemStatus.RUNNING
                            )
                        }
                        DownloadNotificationManager.showProgress(
                            ctx, item.id, item.filename,
                            progress.percent, progress.speedStr, progress.totalBytesStr
                        )
                    }
                    DownloadProgressUnified.Completed -> {
                        updateItem(item.id) { it.copy(progress = 100f, status = ItemStatus.COMPLETED) }
                        DownloadNotificationManager.showComplete(ctx, item.id, item.filename)
                    }
                    is DownloadProgressUnified.Failed -> {
                        updateItem(item.id) { it.copy(status = ItemStatus.FAILED, errorMsg = progress.reason) }
                        DownloadNotificationManager.showFailed(ctx, item.id, item.filename, progress.reason)
                    }
                }
            }
        }
        downloadJobs[item.id] = job
    }

    fun pause(id: String) {
        val item = _state.value.downloads.find { it.id == id } ?: return
        directEngine.pause(id, (item.progress / 100f * 1_000_000L).toLong())
        downloadJobs[id]?.cancel()
        updateItem(id) { it.copy(status = ItemStatus.PAUSED) }
    }

    fun resume(id: String) {
        val item = _state.value.downloads.find { it.id == id } ?: return
        startDownload(item)
    }

    fun cancel(id: String) {
        directEngine.cancel(id)
        downloadJobs[id]?.cancel()
        DownloadNotificationManager.cancel(getApplication(), id)
        updateItem(id) { it.copy(status = ItemStatus.CANCELLED) }
    }

    fun retry(id: String) {
        val item = _state.value.downloads.find { it.id == id } ?: return
        updateItem(id) { it.copy(status = ItemStatus.QUEUED, progress = 0f, errorMsg = null) }
        startDownload(item.copy(status = ItemStatus.QUEUED))
    }

    fun delete(id: String) {
        val item = _state.value.downloads.find { it.id == id } ?: return
        File(item.outputPath).delete()
        downloadJobs[id]?.cancel()
        DownloadNotificationManager.cancel(getApplication(), id)
        _state.update { it.copy(downloads = it.downloads.filter { d -> d.id != id }) }
    }

    fun updateYtDlp() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isSettingUpYtDlp = true, setupError = null) }
            ytDlpEngine.updateYtDlp(
                onProgress = { msg -> _state.update { it.copy(setupError = msg) } },
                onSuccess  = {
                    _state.update { it.copy(
                        isSettingUpYtDlp = false,
                        ytDlpReady       = true,
                        setupError       = null
                    )}
                },
                onError = { err ->
                    _state.update { it.copy(isSettingUpYtDlp = false, setupError = err) }
                }
            )
        }
    }

    fun openFile(id: String, context: Context) {
        val item = _state.value.downloads.find { it.id == id } ?: return
        val file = File(item.outputPath)
        if (!file.exists()) return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open with"))
        } catch (_: Exception) {
            val uri = Uri.fromFile(file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
            }
            context.startActivity(Intent.createChooser(intent, "Open with"))
        }
    }

    fun shareFile(id: String, context: Context) {
        val item = _state.value.downloads.find { it.id == id } ?: return
        val file = File(item.outputPath)
        if (!file.exists()) return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(uri) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share via"))
        } catch (_: Exception) {
            val uri = Uri.fromFile(file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            context.startActivity(Intent.createChooser(intent, "Share via"))
        }
    }

    private fun updateItem(id: String, block: (DownloadItem) -> DownloadItem) {
        _state.update { state ->
            state.copy(downloads = state.downloads.map { if (it.id == id) block(it) else it })
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        var count = 1
        val base = name.substringBeforeLast(".")
        val ext = name.substringAfterLast(".", "")
        while (file.exists()) {
            file = File(dir, if (ext.isNotEmpty()) "$base($count).$ext" else "$base($count)")
            count++
        }
        return file
    }
}
