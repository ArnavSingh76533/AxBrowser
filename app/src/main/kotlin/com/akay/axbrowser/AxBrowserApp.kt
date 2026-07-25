package com.akay.axbrowser

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AxBrowserApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initYoutubeDL()
        createNotificationChannels()
    }

    private fun initYoutubeDL() {
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
            Log.d("AxBrowser", "yt-dlp initialized successfully")
        } catch (e: YoutubeDLException) {
            Log.e("AxBrowser", "Failed to initialize yt-dlp: ${e.message}")
        } catch (e: Exception) {
            Log.e("AxBrowser", "Unexpected error initializing yt-dlp: ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_DOWNLOAD_PROGRESS, "Download Progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setSound(null, null); enableVibration(false) })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_DOWNLOAD_COMPLETE, "Download Complete",
            NotificationManager.IMPORTANCE_DEFAULT
        ))
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_DOWNLOAD_FAILED, "Download Failed",
            NotificationManager.IMPORTANCE_HIGH
        ))
    }

    companion object {
        const val CHANNEL_DOWNLOAD_PROGRESS = "ax_dl_progress"
        const val CHANNEL_DOWNLOAD_COMPLETE = "ax_dl_complete"
        const val CHANNEL_DOWNLOAD_FAILED   = "ax_dl_failed"
    }
}
