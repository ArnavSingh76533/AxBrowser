package com.akay.core.data.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Installs a global uncaught-exception handler that writes the crash's full
 * stack trace to a file before the process dies AND copies it straight to
 * the clipboard, so it can be pasted immediately without hunting through
 * Settings - this app has no remote crash reporting, so without this a
 * crash is otherwise a dead end to debug.
 */
object CrashLogger {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val content = runCatching { buildCrashReport(appContext, thread, throwable) }.getOrNull()
            if (content != null) {
                runCatching { File(appContext.filesDir, FILE_NAME).writeText(content) }
                runCatching { copyToClipboard(appContext, content) }
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("AxBrowser crash log", text))
    }

    private fun buildCrashReport(context: Context, thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("AxBrowser crash report")
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine("App version: ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()}")
            appendLine()
            append(sw.toString())
        }
    }

    fun readLastCrash(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    fun clearLastCrash(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE_NAME).delete() }
    }
}
