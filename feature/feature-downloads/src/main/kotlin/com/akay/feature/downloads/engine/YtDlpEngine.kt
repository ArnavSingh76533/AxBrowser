package com.akay.feature.downloads.engine

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.awaitClose
import java.io.File

class YtDlpEngine(private val context: Context) {

    fun download(
        url: String,
        outputPath: String,
        formatId: String? = null
    ): Flow<DownloadProgressUnified> = callbackFlow {
        val request = YoutubeDLRequest(url).apply {
            addOption("-o", outputPath)
            addOption("--no-playlist")
            addOption("--retries", "3")
            addOption("--fragment-retries", "3")
            addOption("--no-warnings")
            addOption("--no-check-certificates")

            val format = formatId ?: "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"
            addOption("-f", format)
            addOption("--merge-output-format", "mp4")
        }

        val processId = "ax_${System.currentTimeMillis()}"

        try {
            YoutubeDL.getInstance().execute(
                request,
                processId
            ) { progress, etaInSeconds, line ->
                val etaStr = if (etaInSeconds > 0) "${etaInSeconds}s" else ""
                trySend(
                    DownloadProgressUnified.Running(
                        percent       = progress,
                        speedStr      = etaStr.ifBlank { "..." },
                        totalBytesStr = line?.trim()?.take(40) ?: ""
                    )
                )
            }
            trySend(DownloadProgressUnified.Completed)
        } catch (e: com.yausername.youtubedl_android.YoutubeDLException) {
            trySend(DownloadProgressUnified.Failed(
                "yt-dlp error: ${e.message ?: "Unknown error"}"
            ))
        } catch (e: InterruptedException) {
            trySend(DownloadProgressUnified.Failed("Download cancelled"))
        } catch (e: Exception) {
            trySend(DownloadProgressUnified.Failed(
                "Download failed: ${e.message ?: "Unknown error"}"
            ))
        } finally {
            awaitClose {
                runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun getInfo(url: String): VideoInfo? = runCatching {
        val info = YoutubeDL.getInstance().getInfo(url)
        VideoInfo(
            title     = info.title ?: "Unknown",
            thumbnail = info.thumbnail ?: "",
            duration  = (info.duration ?: 0).toDouble(),
            url       = info.url ?: url
        )
    }.getOrNull()

    fun updateYtDlp(
        onProgress: (String) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(
                context,
                YoutubeDL.UpdateChannel.STABLE
            )
            when (status) {
                YoutubeDL.UpdateStatus.DONE         -> onSuccess()
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> onSuccess()
                else -> onError("Update status: $status")
            }
        } catch (e: Exception) {
            onError("Update failed: ${e.message}")
        }
    }
}

data class VideoInfo(
    val title: String,
    val thumbnail: String,
    val duration: Double,
    val url: String
)
