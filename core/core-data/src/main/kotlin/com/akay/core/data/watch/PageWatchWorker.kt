package com.akay.core.data.watch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.akay.core.data.db.AxDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Checks one watched URL, hashes its raw HTTP response body, and notifies if the hash changed
 * since last check. This is a plain HTTP fetch, not a rendered WebView load - it sees the initial
 * HTML response only, not anything that only appears after client-side JS runs. Driving an actual
 * WebView from a background Worker would need the main looper and a real (even if 0x0) window,
 * which is a much larger and riskier undertaking than this covers; most "did this change" checks
 * (price text, a status field, article body) are present in the raw HTML regardless.
 *
 * Opens its own [AxDatabase] instance rather than being Hilt-injected - WorkManager's default
 * WorkerFactory instantiates workers via reflection with no DI, and adding a full
 * HiltWorkerFactory + Configuration.Provider wiring for this one worker wasn't worth the extra
 * surface area. Room supports multiple instances safely; this only writes at most once every 15+
 * minutes (Android's periodic work minimum), so read/write contention is a non-issue in practice.
 */
class PageWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val label = inputData.getString(KEY_LABEL) ?: return@withContext Result.failure()
        val db = Room.databaseBuilder(applicationContext, AxDatabase::class.java, "axbrowser_database")
            .fallbackToDestructiveMigration()
            .build()
        try {
            val watch = db.watchDao().getByLabel(label) ?: return@withContext Result.success()
            val body = runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                client.newCall(Request.Builder().url(watch.url).build()).execute().use { it.body?.string().orEmpty() }
            }.getOrElse { return@withContext Result.retry() }

            val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
            val now = System.currentTimeMillis()
            val changed = watch.lastContentHash != null && watch.lastContentHash != hash
            db.watchDao().updateHash(label, hash, now)
            if (changed) notifyChanged(applicationContext, watch.label, watch.url)
            Result.success()
        } finally {
            db.close()
        }
    }

    private fun notifyChanged(context: Context, label: String, url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Page watch alerts", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(context.packageName) ?: Intent().apply { `package` = context.packageName }
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pendingIntent = PendingIntent.getActivity(
            context, label.hashCode(), launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("\"$label\" changed")
            .setContentText(url)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        nm.notify(WATCH_NOTIF_BASE_ID + (label.hashCode() and 0x00FFFFFF), notification)
    }

    companion object {
        private const val KEY_LABEL = "label"
        private const val CHANNEL_ID = "ax_watch_alerts"
        private const val WATCH_NOTIF_BASE_ID = 9500
        private const val MIN_INTERVAL_MINUTES = 15L // Android's PeriodicWorkRequest floor

        /** Schedules (or replaces) a periodic check for [label]. Interval is clamped up to 15
         *  minutes since that's the OS-enforced minimum for periodic work - a shorter request
         *  would silently be coerced to 15 anyway, so we clamp explicitly and say so. */
        fun schedule(context: Context, label: String, intervalMinutes: Int) {
            val clamped = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES.toInt())
            val request = PeriodicWorkRequestBuilder<PageWatchWorker>(clamped.toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_LABEL to label))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                workNameFor(label), ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        fun cancel(context: Context, label: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workNameFor(label))
        }

        private fun workNameFor(label: String) = "page_watch_$label"
    }
}
