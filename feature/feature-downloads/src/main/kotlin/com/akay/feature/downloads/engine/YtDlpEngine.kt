package com.akay.feature.downloads.engine

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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
            // Speed: download HLS/DASH fragments in parallel and use a large
            // HTTP chunk size for progressive files.
            addOption("--concurrent-fragments", "5")
            addOption("--buffer-size", "16K")
            addOption("--http-chunk-size", "10M")
            addOption("--no-warnings")
            addOption("--no-check-certificates")
            addOption("--newline")

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
                if (line != null) {
                    val resolvedPath = parseActualPath(line)
                    if (resolvedPath != null) {
                        trySend(DownloadProgressUnified.FileResolved(resolvedPath))
                    }
                }

                val displayLine = when {
                    line == null -> ""
                    line.contains("Deleting") -> ""
                    line.contains("ffmpeg") && line.contains("Merging") -> "Merging..."
                    line.contains("[download]") && line.contains("%") -> line.trim().take(50)
                    else -> ""
                }

                val etaStr = if (etaInSeconds > 0) "ETA ${etaInSeconds}s" else ""
                trySend(
                    DownloadProgressUnified.Running(
                        percent       = progress,
                        speedStr      = etaStr.ifBlank { if (progress > 0f) "Downloading" else "Starting..." },
                        totalBytesStr = displayLine
                    )
                )
            }
            trySend(DownloadProgressUnified.Completed)
        } catch (e: com.yausername.youtubedl_android.YoutubeDLException) {
            trySend(DownloadProgressUnified.Failed("yt-dlp error: ${e.message ?: "Unknown error"}"))
        } catch (e: InterruptedException) {
            trySend(DownloadProgressUnified.Failed("Download cancelled"))
        } catch (e: Exception) {
            trySend(DownloadProgressUnified.Failed("Download failed: ${e.message ?: "Unknown error"}"))
        } finally {
            awaitClose {
                runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun parseActualPath(line: String): String? {
        val destRegex = Regex("""\[download\] Destination: (.+)""")
        val destMatch = destRegex.find(line)
        if (destMatch != null) {
            val path = destMatch.groupValues[1].trim()
            if (File(path).parentFile?.exists() == true) return path
        }

        val mergeRegex = Regex("""Merging formats into "(.+)"""")
        val mergeMatch = mergeRegex.find(line)
        if (mergeMatch != null) {
            val path = mergeMatch.groupValues[1].trim()
            if (File(path).parentFile?.exists() == true) return path
        }

        val extractRegex = Regex("""\[ExtractAudio\] Destination: (.+)""")
        val extractMatch = extractRegex.find(line)
        if (extractMatch != null) {
            val path = extractMatch.groupValues[1].trim()
            if (File(path).parentFile?.exists() == true) return path
        }

        return null
    }

    suspend fun getInfo(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val info = YoutubeDL.getInstance().getInfo(url)
            VideoInfo(
                title     = info.title ?: "Unknown",
                thumbnail = info.thumbnail ?: "",
                duration  = (info.duration ?: 0).toDouble(),
                url       = info.url ?: url
            )
        }.getOrNull()
    }

    /**
     * Fetches the title and available formats in a SINGLE yt-dlp call, on the
     * IO dispatcher, while streaming yt-dlp's own extraction log lines to
     * [onLog] so the UI can show what's happening in real time. A timeout
     * guards against hangs on unsupported URLs.
     */
    suspend fun getVideoData(
        url: String,
        onLog: (String) -> Unit = {}
    ): VideoData = withContext(Dispatchers.IO) {
        runCatching {
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--no-check-certificates")
            }
            val response = kotlinx.coroutines.withTimeout(90_000) {
                YoutubeDL.getInstance().execute(request, null) { _, _, line ->
                    if (!line.isNullOrBlank()) onLog(line.trim().take(80))
                }
            }
            parseVideoJson(response.out)
        }.getOrElse { VideoData(title = "", formats = emptyList(), error = it.message) }
    }

    private fun parseVideoJson(jsonStr: String): VideoData {
        val obj = org.json.JSONObject(jsonStr)
        val title = obj.optString("title", "")
        val formatsArr = obj.optJSONArray("formats") ?: org.json.JSONArray()
        val formats = (0 until formatsArr.length()).mapNotNull { i ->
            val f = formatsArr.optJSONObject(i) ?: return@mapNotNull null
            val id = f.optString("format_id", "").ifBlank { return@mapNotNull null }
            val ext = f.optString("ext", "mp4")
            val h = f.optInt("height", 0)
            val vcodec = f.optString("vcodec", "none")
            val acodec = f.optString("acodec", "none")
            val sizeBytes = f.optLong("filesize", f.optLong("filesize_approx", 0L))
            val isVideoOnly = vcodec != "none" && acodec == "none"
            val isAudioOnly = vcodec == "none" && acodec != "none"
            val label = when {
                isAudioOnly -> "Audio only - ${ext.uppercase()}"
                h > 0 -> "${h}p${if (!isVideoOnly) " - $ext" else " (video only) - $ext"}"
                else -> "${f.optString("format", id)} - $ext"
            }
            VideoFormat(id, label, ext, sizeBytes, h, isAudioOnly)
        }
            .distinctBy { it.label }
            .sortedWith(compareByDescending<VideoFormat> { it.height }.thenBy { it.isAudioOnly })
        return VideoData(title = title, formats = formats)
    }

    suspend fun getFormats(url: String): List<VideoFormat> = getVideoData(url).formats

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
                YoutubeDL.UpdateStatus.DONE              -> onSuccess()
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

data class VideoData(
    val title: String,
    val formats: List<VideoFormat>,
    val error: String? = null
)

data class VideoFormat(
    val formatId: String,
    val label: String,
    val ext: String,
    val fileSizeBytes: Long,
    val height: Int,
    val isAudioOnly: Boolean
) {
    val displaySize: String get() = when {
        fileSizeBytes > 0 -> "${"%.1f".format(fileSizeBytes / (1024f * 1024f))} MB"
        else -> "Size unknown"
    }
}
