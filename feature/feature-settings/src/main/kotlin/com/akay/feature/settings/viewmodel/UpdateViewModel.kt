package com.akay.feature.settings.viewmodel

import android.content.Context
import com.akay.core.data.datastore.AxPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

data class UpdateUiState(
    val currentVersion: String = "",
    val checking: Boolean = false,
    val latestVersion: String? = null,
    val updateAvailable: Boolean = false,
    val releaseNotes: String? = null,
    val apkDownloadUrl: String? = null,
    val apkAssetName: String = "AxBrowser-update.apk",
    val error: String? = null,
    val lastChecked: Long? = null,
    /** Set once a downloaded update APK is confirmed on disk and ready to install - survives
     *  leaving Settings, backgrounding the app, even the process being killed mid-download. */
    val pendingInstallPath: String? = null
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val preferences: AxPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(UpdateUiState(currentVersion = currentInstalledVersion()))
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val path = preferences.pendingUpdateApkPath.first()
            if (!path.isNullOrBlank() && File(path).exists()) {
                _uiState.value = _uiState.value.copy(pendingInstallPath = path)
            } else if (!path.isNullOrBlank()) {
                // File got cleared/moved since we last recorded it - forget the stale entry.
                preferences.setPendingUpdate(null, null)
            }
        }
    }

    private fun currentInstalledVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

    private fun expectedApkFile(filename: String): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "AxBrowser Downloads/$filename")
    }

    fun checkForUpdates(owner: String = "akborana3", repo: String = "AxBrowser") {
        if (_uiState.value.checking) return
        _uiState.value = _uiState.value.copy(checking = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // "nightly" is a rolling release CI updates on every push to main
                    // (see ci.yml) - it's what actually matches the debug-variant build
                    // most people are running day to day. Fetching by tag (rather than
                    // /releases/latest) works even though it's marked prerelease, which
                    // /releases/latest would otherwise silently exclude.
                    val request = Request.Builder()
                        .url("https://api.github.com/repos/$owner/$repo/releases/tags/nightly")
                        .addHeader("Accept", "application/vnd.github+json")
                        .build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("GitHub returned ${response.code}")
                        val body = response.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val tag = json.optString("tag_name")
                        val notes = json.optString("body").take(500)
                        val assets = json.optJSONArray("assets")
                        var apkUrl: String? = null
                        var apkName = "AxBrowser-update.apk"
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val asset = assets.getJSONObject(i)
                                val name = asset.optString("name")
                                if (name.endsWith(".apk", ignoreCase = true)) {
                                    apkUrl = asset.optString("browser_download_url")
                                    apkName = name
                                    break
                                }
                            }
                        }
                        // The release's own title carries the real "1.0.<run_number>" version
                        // (tag_name is just the constant "nightly"); parse it out of the name.
                        val releaseName = json.optString("name")
                        val versionFromName = Regex("""(\d+\.\d+\.\d+)""").find(releaseName)?.value ?: tag
                        Triple(versionFromName, notes, apkUrl to apkName)
                    }
                }
            }
            result.fold(
                onSuccess = { (latestVersion, notes, apkInfo) ->
                    val (apkUrl, apkName) = apkInfo
                    val isNewer = isVersionNewer(latestVersion, _uiState.value.currentVersion)
                    // Maybe this exact update was already downloaded in a previous session
                    // (app was closed/backgrounded before we could confirm completion in-app)
                    // - if the file's sitting right there, skip straight to "ready to install"
                    // instead of asking to download it all over again.
                    val alreadyDownloaded = isNewer && runCatching { expectedApkFile(apkName).exists() }.getOrDefault(false)
                    _uiState.value = _uiState.value.copy(
                        checking = false,
                        latestVersion = latestVersion,
                        updateAvailable = isNewer && apkUrl != null,
                        releaseNotes = notes,
                        apkDownloadUrl = apkUrl,
                        apkAssetName = apkName,
                        lastChecked = System.currentTimeMillis(),
                        pendingInstallPath = if (alreadyDownloaded) expectedApkFile(apkName).absolutePath else _uiState.value.pendingInstallPath
                    )
                    if (alreadyDownloaded) {
                        val path = expectedApkFile(apkName).absolutePath
                        viewModelScope.launch { preferences.setPendingUpdate(path, latestVersion) }
                    }
                    // We're already on the latest build - any leftover "ready to install"
                    // pointer from before must be stale (e.g. this check just confirmed the
                    // update was already applied), so clear it instead of nagging forever.
                    if (!isNewer && _uiState.value.pendingInstallPath != null) {
                        clearPendingInstall()
                    }
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(checking = false, error = e.message ?: "Couldn't check for updates")
                }
            )
        }
    }

    /** Called once a download of [apkDownloadUrl] is confirmed COMPLETED, with the file's real path on disk. */
    fun markDownloadComplete(path: String) {
        _uiState.value = _uiState.value.copy(pendingInstallPath = path)
        viewModelScope.launch { preferences.setPendingUpdate(path, _uiState.value.latestVersion) }
    }

    fun clearPendingInstall() {
        _uiState.value = _uiState.value.copy(pendingInstallPath = null)
        viewModelScope.launch { preferences.setPendingUpdate(null, null) }
    }

    /** True if [latest] (e.g. "1.4.2") is a newer semantic version than [current]. */
    private fun isVersionNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        val size = maxOf(l.size, c.size)
        for (i in 0 until size) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }
}
