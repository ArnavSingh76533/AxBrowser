package com.akay.feature.downloads.engine

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL

object YtDlpSetup {

    fun isInstalled(context: Context): Boolean = try {
        YoutubeDL.getInstance().version(context) != null
    } catch (e: Exception) {
        false
    }

    fun getVersion(context: Context): String = try {
        YoutubeDL.getInstance().version(context) ?: "Unknown"
    } catch (e: Exception) {
        "Not initialized"
    }

    fun statusString(context: Context): String =
        "yt-dlp ${getVersion(context)} (youtubedl-android)"
}
