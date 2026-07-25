package com.akay.feature.settings.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.akay.core.ui.theme.Primary
import com.akay.feature.settings.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = "Appearance") {
                SettingsSwitchItem(
                    title = "Dark Mode",
                    checked = uiState.isDarkMode,
                    onCheckedChange = { viewModel.setDarkMode(it) }
                )
                SettingsSwitchItem(
                    title = "Desktop Mode",
                    checked = uiState.isDesktopMode,
                    onCheckedChange = { viewModel.setDesktopMode(it) }
                )
            }

            SettingsSection(title = "Privacy & Security") {
                SettingsSwitchItem(
                    title = "Ad Blocker",
                    subtitle = "Block ads and trackers",
                    checked = uiState.isAdBlockerEnabled,
                    onCheckedChange = { viewModel.setAdBlockerEnabled(it) }
                )
                SettingsSwitchItem(
                    title = "HTTPS Upgrade",
                    subtitle = "Force HTTPS where possible",
                    checked = uiState.isHttpsUpgrade,
                    onCheckedChange = { viewModel.setHttpsUpgrade(it) }
                )
                SettingsSwitchItem(
                    title = "JavaScript",
                    subtitle = "Enable JavaScript on pages",
                    checked = uiState.isJavascriptEnabled,
                    onCheckedChange = { viewModel.setJavascriptEnabled(it) }
                )
            }

            SettingsSection(title = "Downloads") {
                Text(
                    text = "Max concurrent downloads: ${uiState.maxConcurrentDownloads}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            SettingsSection(title = "Developer") {
                SettingsSwitchItem(
                    title = "Dev Console / Eruda",
                    subtitle = "Enable JavaScript console overlay",
                    checked = uiState.isErudaEnabled,
                    onCheckedChange = { viewModel.setErudaEnabled(it) }
                )
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("yt-dlp Engine", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = uiState.ytDlpUpdateStatus
                                ?: com.akay.feature.downloads.engine.YtDlpSetup.statusString(ctx),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.ytDlpInstalled) Color(0xFF4CAF50)
                                    else MaterialTheme.colorScheme.error
                        )
                    }
                    TextButton(onClick = { viewModel.updateYtDlp() }) {
                        Text("Update")
                    }
                }
            }

            SettingsSection(title = "General") {
                SettingsSwitchItem(
                    title = "Clear cache on exit",
                    checked = uiState.clearCacheOnExit,
                    onCheckedChange = { viewModel.setClearCacheOnExit(it) }
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text     = title.uppercase(),
            style    = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
            color    = Primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
        Surface(
            shape  = MaterialTheme.shapes.large,
            color  = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(0.15f))
        ) {
            Column { content() }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    subtitle: String = "",
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank())
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked         = checked,
            onCheckedChange = onCheckedChange,
            colors          = SwitchDefaults.colors(
                checkedThumbColor  = Color.White,
                checkedTrackColor  = Primary
            )
        )
    }
}
