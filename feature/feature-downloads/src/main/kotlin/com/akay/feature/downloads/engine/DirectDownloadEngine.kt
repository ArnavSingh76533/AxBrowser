package com.akay.feature.downloads.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

class DirectDownloadEngine(private val client: OkHttpClient) {

    private val pausedOffsets = mutableMapOf<String, Long>()
    private val activeJobs = mutableMapOf<String, Boolean>()

    fun download(
        downloadId: String,
        url: String,
        destFile: File,
        extraHeaders: Map<String, String> = emptyMap()
    ): Flow<DownloadProgressUnified> = flow {
        val resumeFrom = pausedOffsets.remove(downloadId) ?: 0L
        activeJobs[downloadId] = false

        val requestBuilder = Request.Builder().url(url)
        extraHeaders.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        if (resumeFrom > 0) requestBuilder.addHeader("Range", "bytes=$resumeFrom-")

        val response = client.newCall(requestBuilder.build()).execute()
        val body = response.body ?: run {
            emit(DownloadProgressUnified.Failed("Empty response")); return@flow
        }

        val contentLength = body.contentLength()
        val totalBytes = if (resumeFrom > 0 && contentLength > 0) contentLength + resumeFrom else contentLength
        var downloadedBytes = resumeFrom
        val startTime = System.currentTimeMillis()
        var lastSpeedCheck = startTime
        var bytesAtLastCheck = downloadedBytes

        val raf = RandomAccessFile(destFile, "rw")
        if (resumeFrom > 0) raf.seek(resumeFrom) else raf.setLength(0)

        try {
            body.byteStream().buffered(1 shl 16).use { input ->
                val buffer = ByteArray(1 shl 16) // 64 KiB
                var bytes: Int
                while (input.read(buffer).also { bytes = it } != -1) {
                    if (activeJobs[downloadId] == true) {
                        raf.close()
                        emit(DownloadProgressUnified.Failed("Cancelled")); return@flow
                    }
                    raf.write(buffer, 0, bytes)
                    downloadedBytes += bytes

                    val now = System.currentTimeMillis()
                    if (now - lastSpeedCheck >= 500) {
                        val speed = ((downloadedBytes - bytesAtLastCheck) * 1000L) / (now - lastSpeedCheck)
                        val percent = if (totalBytes > 0) (downloadedBytes * 100f) / totalBytes else 0f
                        emit(DownloadProgressUnified.Running(
                            percent = percent,
                            totalBytesStr = formatSize(totalBytes),
                            speedStr = "${formatSize(speed)}/s"
                        ))
                        bytesAtLastCheck = downloadedBytes
                        lastSpeedCheck = now
                    }
                }
            }
            raf.close()
            emit(DownloadProgressUnified.Completed)
        } catch (e: Exception) {
            raf.close()
            emit(DownloadProgressUnified.Failed(e.message ?: "Unknown error"))
        } finally {
            activeJobs.remove(downloadId)
        }
    }.flowOn(Dispatchers.IO)

    fun pause(downloadId: String, bytesDownloaded: Long) {
        pausedOffsets[downloadId] = bytesDownloaded
    }

    fun cancel(downloadId: String) {
        activeJobs[downloadId] = true
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024f)}KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024f * 1024f))}MB"
        else -> "${"%.2f".format(bytes / (1024f * 1024f * 1024f))}GB"
    }
}

sealed class DirectDownloadProgress {
    data class Running(val percent: Float, val totalBytesStr: String, val speedStr: String) : DirectDownloadProgress()
    data object Completed : DirectDownloadProgress()
    data class Failed(val reason: String) : DirectDownloadProgress()
}
