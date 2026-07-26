package com.akay.axbrowser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.akay.core.ui.theme.AxTheme
import com.akay.core.ui.theme.GalaxyConfig
import com.akay.core.ui.theme.accentByName
import com.akay.feature.browser.ui.BrowserNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (perm, granted) ->
            Log.d("Permissions", "$perm -> ${if (granted) "GRANTED" else "DENIED"}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRequiredPermissions()

        setContent {
            val themeViewModel: AppThemeViewModel = hiltViewModel()
            val theme by themeViewModel.state.collectAsState()
            AxTheme(
                darkTheme = theme.isDarkMode,
                amoled = theme.amoled,
                accent = accentByName(theme.accentName),
                galaxy = GalaxyConfig(
                    enabled = theme.galaxyEnabled,
                    intensity = (theme.animationIntensity / 100f).coerceIn(0.2f, 1.5f)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BrowserNavHost()
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isGranted(Manifest.permission.POST_NOTIFICATIONS))
                needed += Manifest.permission.POST_NOTIFICATIONS
            if (!isGranted(Manifest.permission.READ_MEDIA_VIDEO))
                needed += Manifest.permission.READ_MEDIA_VIDEO
            if (!isGranted(Manifest.permission.READ_MEDIA_AUDIO))
                needed += Manifest.permission.READ_MEDIA_AUDIO
            if (!isGranted(Manifest.permission.READ_MEDIA_IMAGES))
                needed += Manifest.permission.READ_MEDIA_IMAGES
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (!isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (!isGranted(Manifest.permission.READ_EXTERNAL_STORAGE))
                needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
