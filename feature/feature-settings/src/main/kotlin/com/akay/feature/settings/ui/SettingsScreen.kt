package com.akay.feature.settings.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.akay.core.ui.components.GalaxyBackground
import com.akay.core.ui.theme.AccentColors
import com.akay.core.ui.theme.Primary
import com.akay.core.ui.theme.accentByName
import com.akay.feature.settings.cookies.CookieTransfer
import com.akay.core.data.ai.AI_PROVIDER_PRESETS
import com.akay.core.data.ai.presetForId
import com.akay.feature.settings.viewmodel.SEARCH_ENGINES
import com.akay.feature.settings.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenExtensions: () -> Unit = {},
    onOpenFilterLists: () -> Unit = {},
    onOpenPasswords: () -> Unit = {},
    onOpenProxySettings: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    updateViewModel: com.akay.feature.settings.viewmodel.UpdateViewModel = hiltViewModel(),
    downloadViewModel: com.akay.feature.downloads.viewmodel.DownloadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showSearchEngineDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showExportCookiesDialog by remember { mutableStateOf(false) }
    var showHeadersDialog by remember { mutableStateOf(false) }
    var showAccentDialog by remember { mutableStateOf(false) }
    var exportSite by remember { mutableStateOf("") }
    var pendingCookieExport by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = pendingCookieExport
        if (uri != null && text != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray())
                }
            }.onSuccess {
                Toast.makeText(context, "Cookies exported", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
        pendingCookieExport = null
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                } ?: ""
            }.onSuccess { content ->
                val count = CookieTransfer.importCookies(content)
                Toast.makeText(
                    context,
                    if (count > 0) "Imported $count cookies" else "No cookies found in file",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
      GalaxyBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = "Appearance") {
                SettingsNavigationItem(
                    title = "Accent color",
                    subtitle = uiState.accentName,
                    trailing = {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(accentByName(uiState.accentName), CircleShape)
                        )
                    },
                    onClick = { showAccentDialog = true }
                )
                SettingsSwitchItem(
                    title = "AMOLED black",
                    subtitle = "Pure-black background to save battery",
                    checked = uiState.amoled,
                    onCheckedChange = { viewModel.setAmoled(it) }
                )
                SettingsSwitchItem(
                    title = "Dark mode",
                    checked = uiState.isDarkMode,
                    onCheckedChange = { viewModel.setDarkMode(it) }
                )
                SettingsSwitchItem(
                    title = "Animated galaxy background",
                    subtitle = "Twinkling starfield and nebulas",
                    checked = uiState.galaxyEnabled,
                    onCheckedChange = { viewModel.setGalaxyEnabled(it) }
                )
                if (uiState.galaxyEnabled) {
                    LabeledSlider(
                        label = "Animation intensity",
                        value = uiState.animationIntensity,
                        range = 20..100,
                        suffix = "%",
                        onChange = { viewModel.setAnimationIntensity(it) }
                    )
                }
                SettingsSwitchItem(
                    title = "Dark mode for websites",
                    subtitle = "Ask pages to render dark",
                    checked = uiState.darkModeForWebsites,
                    onCheckedChange = { viewModel.setDarkModeForWebsites(it) }
                )
                SettingsSwitchItem(
                    title = "Desktop mode",
                    subtitle = "Request desktop version of websites",
                    checked = uiState.isDesktopMode,
                    onCheckedChange = { viewModel.setDesktopMode(it) }
                )
                FontSizeItem(
                    fontSize = uiState.fontSize,
                    onFontSizeChange = { viewModel.setFontSize(it) }
                )
            }

            SettingsSection(title = "Search") {
                SettingsNavigationItem(
                    title = "Search engine",
                    subtitle = uiState.searchEngineName,
                    onClick = { showSearchEngineDialog = true }
                )
            }

            SettingsSection(title = "Privacy & Security") {
                SettingsSwitchItem(
                    title = "Ad Blocker",
                    subtitle = "Block ads and trackers, like Brave shields",
                    checked = uiState.isAdBlockerEnabled,
                    onCheckedChange = { viewModel.setAdBlockerEnabled(it) }
                )
                SettingsNavigationItem(
                    title = "Filter lists",
                    subtitle = "Subscribe to community ad/tracker block lists",
                    onClick = onOpenFilterLists
                )
                SettingsNavigationItem(
                    title = "Saved passwords",
                    subtitle = "Manage logins AxBrowser has saved for you",
                    onClick = onOpenPasswords
                )
                SettingsNavigationItem(
                    title = "Proxy",
                    subtitle = "HTTP/HTTPS/SOCKS proxies, rotation, exceptions",
                    onClick = onOpenProxySettings
                )
                SettingsSwitchItem(
                    title = "Device fingerprint protection",
                    subtitle = "Randomize signals sites use to recognize this device as new vs. returning",
                    checked = uiState.fingerprintProtectionEnabled,
                    onCheckedChange = { viewModel.setFingerprintProtectionEnabled(it) }
                )
                if (uiState.fingerprintProtectionEnabled) {
                    SettingsSwitchItem(
                        title = "Spoof canvas fingerprint",
                        checked = uiState.fingerprintSpoofCanvas,
                        onCheckedChange = { viewModel.setFingerprintSpoofCanvas(it) }
                    )
                    SettingsSwitchItem(
                        title = "Spoof WebGL renderer",
                        checked = uiState.fingerprintSpoofWebGl,
                        onCheckedChange = { viewModel.setFingerprintSpoofWebGl(it) }
                    )
                    SettingsSwitchItem(
                        title = "Spoof hardware info",
                        subtitle = "CPU core count, memory, plugin list",
                        checked = uiState.fingerprintSpoofHardware,
                        onCheckedChange = { viewModel.setFingerprintSpoofHardware(it) }
                    )
                    SettingsNavigationItem(
                        title = "New device identity",
                        subtitle = "Reset the spoofed profile - sites will see this as a brand-new device",
                        onClick = { viewModel.regenerateFingerprintIdentity() }
                    )
                }
                var showPinDialog by remember { mutableStateOf(false) }
                var pinInput by remember { mutableStateOf("") }
                SettingsSwitchItem(
                    title = "App Lock",
                    subtitle = if (uiState.appLockPinSet) "Require PIN/biometric to open AxBrowser" else "Set a PIN first to enable",
                    checked = uiState.appLockEnabled,
                    onCheckedChange = { enable ->
                        if (enable && !uiState.appLockPinSet) {
                            pinInput = ""
                            showPinDialog = true
                        } else {
                            viewModel.setAppLockEnabled(enable)
                        }
                    }
                )
                if (uiState.appLockEnabled || uiState.appLockPinSet) {
                    SettingsSwitchItem(
                        title = "Use biometric unlock",
                        subtitle = "Fall back to fingerprint/face where available",
                        checked = uiState.appLockUseBiometric,
                        onCheckedChange = { viewModel.setAppLockUseBiometric(it) }
                    )
                    SettingsNavigationItem(
                        title = "Change PIN",
                        subtitle = "Update your 4-digit app-lock PIN",
                        onClick = { pinInput = ""; showPinDialog = true }
                    )
                }
                if (showPinDialog) {
                    AlertDialog(
                        onDismissRequest = { showPinDialog = false },
                        title = { Text("Set a 4-digit PIN") },
                        text = {
                            OutlinedTextField(
                                value = pinInput,
                                onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) pinInput = it },
                                label = { Text("PIN") },
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = pinInput.length == 4,
                                onClick = {
                                    viewModel.setAppLockPin(pinInput)
                                    viewModel.setAppLockEnabled(true)
                                    showPinDialog = false
                                }
                            ) { Text("Save") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPinDialog = false }) { Text("Cancel") }
                        }
                    )
                }
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
                SettingsNavigationItem(
                    title = "Clear browsing data",
                    subtitle = "History, cookies and cache",
                    onClick = { showClearDataDialog = true }
                )
            }

            SettingsSection(title = "Advanced") {
                SettingsNavigationItem(
                    title = "Extensions (userscripts)",
                    subtitle = "Run custom scripts on pages, import .user.js",
                    onClick = onOpenExtensions
                )
                SettingsNavigationItem(
                    title = "Custom request headers",
                    subtitle = if (uiState.customHeaders.isBlank()) "None set"
                               else "${uiState.customHeaders.lineSequence().count { it.isNotBlank() }} header(s)",
                    onClick = { showHeadersDialog = true }
                )
            }

            SettingsSection(title = "Cookies") {
                SettingsNavigationItem(
                    title = "Export site cookies",
                    subtitle = "Save a website's cookies as cookies.txt",
                    onClick = { showExportCookiesDialog = true }
                )
                SettingsNavigationItem(
                    title = "Import cookies",
                    subtitle = "Load cookies from a cookies.txt file",
                    onClick = {
                        importLauncher.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
                    }
                )
            }

            SettingsSection(title = "Developer") {
                SettingsSwitchItem(
                    title = "Dev Console / Eruda",
                    subtitle = "Inspect console, network requests and elements on pages",
                    checked = uiState.isErudaEnabled,
                    onCheckedChange = { viewModel.setErudaEnabled(it) }
                )
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
                                ?: com.akay.feature.downloads.engine.YtDlpSetup.statusString(context),
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

            SettingsSection(title = "Storage") {
                val storageLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri -> if (uri != null) viewModel.onStorageFolderPicked(uri) }

                SettingsNavigationItem(
                    title = "Save location",
                    subtitle = if (uiState.storageFolderConfigured) {
                        "Saving to \u201c${uiState.storageFolderName}\u201d \u2022 tap to change"
                    } else {
                        "Saving inside app storage \u2022 tap to choose a visible folder instead"
                    },
                    onClick = { storageLauncher.launch(null) }
                )
                if (uiState.storageFolderConfigured) {
                    Text(
                        "Screenshots, exports, and files the agent saves go in subfolders here (Screenshots, Downloads, Agent, Other) - visible in any file manager, and kept if you ever uninstall the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    TextButton(
                        onClick = { viewModel.clearStorageFolder() },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) { Text("Stop using this folder", color = MaterialTheme.colorScheme.error) }
                } else {
                    Text(
                        "Entirely optional - everything works fine without this, just saved somewhere only this app can see. Pick a folder (or create a new one, e.g. \"AxBrowser\") to make saved files visible and keep them if you uninstall.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            SettingsSection(title = "AI Agent") {
                var showApiKeyDialog by remember { mutableStateOf(false) }
                var showModelDialog by remember { mutableStateOf(false) }
                var showProviderDialog by remember { mutableStateOf(false) }
                var showBaseUrlDialog by remember { mutableStateOf(false) }
                var apiKeyInput by remember { mutableStateOf("") }
                var baseUrlInput by remember { mutableStateOf("") }
                var modelInput by remember { mutableStateOf("") }

                val activePreset = remember(uiState.aiProvider) { presetForId(uiState.aiProvider) }

                SettingsNavigationItem(
                    title = "AI provider",
                    subtitle = activePreset.displayName,
                    onClick = { showProviderDialog = true }
                )
                if (uiState.aiProvider == "custom") {
                    SettingsNavigationItem(
                        title = "Custom base URL",
                        subtitle = uiState.aiBaseUrl.ifBlank { "Not set - required before the agent will work" },
                        onClick = { baseUrlInput = uiState.aiBaseUrl; showBaseUrlDialog = true }
                    )
                }
                SettingsNavigationItem(
                    title = "${activePreset.displayName} API key",
                    subtitle = if (uiState.aiApiKeySet) "Key saved \u2022 tap to change" else activePreset.apiKeyHint,
                    onClick = { apiKeyInput = ""; showApiKeyDialog = true }
                )
                SettingsNavigationItem(
                    title = "AI model",
                    subtitle = uiState.aiModel,
                    onClick = {
                        modelInput = uiState.aiModel
                        showModelDialog = true
                        if (uiState.aiFreeModels.isEmpty()) viewModel.refreshFreeModels()
                    }
                )

                if (showProviderDialog) {
                    AlertDialog(
                        onDismissRequest = { showProviderDialog = false },
                        title = { Text("AI provider") },
                        text = {
                            Column {
                                AI_PROVIDER_PRESETS.forEach { preset ->
                                    TextButton(
                                        onClick = {
                                            viewModel.setAiProvider(preset.id)
                                            showProviderDialog = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(preset.displayName, style = MaterialTheme.typography.bodyMedium)
                                                if (preset.id == uiState.aiProvider) {
                                                    Icon(Icons.Default.Check, contentDescription = "Selected")
                                                }
                                            }
                                            if (preset.baseUrl.isNotBlank()) {
                                                Text(
                                                    preset.baseUrl,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { showProviderDialog = false }) { Text("Close") } }
                    )
                }

                if (showBaseUrlDialog) {
                    AlertDialog(
                        onDismissRequest = { showBaseUrlDialog = false },
                        title = { Text("Custom base URL") },
                        text = {
                            Column {
                                Text(
                                    "Any server speaking the OpenAI-compatible /chat/completions API - self-hosted, Groq, Together, etc. " +
                                        "No trailing slash, no /chat/completions suffix, e.g. https://api.example.com/v1",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = baseUrlInput,
                                    onValueChange = { baseUrlInput = it },
                                    label = { Text("https://...") },
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = baseUrlInput.isNotBlank(),
                                onClick = {
                                    viewModel.setAiBaseUrl(baseUrlInput.trim().trimEnd('/'))
                                    showBaseUrlDialog = false
                                }
                            ) { Text("Save") }
                        },
                        dismissButton = { TextButton(onClick = { showBaseUrlDialog = false }) { Text("Cancel") } }
                    )
                }

                if (showApiKeyDialog) {
                    AlertDialog(
                        onDismissRequest = { showApiKeyDialog = false },
                        title = { Text("${activePreset.displayName} API key") },
                        text = {
                            Column {
                                Text(
                                    activePreset.apiKeyHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = apiKeyInput,
                                    onValueChange = { apiKeyInput = it },
                                    label = { Text("API key") },
                                    singleLine = true,
                                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = apiKeyInput.isNotBlank(),
                                onClick = {
                                    viewModel.setAiApiKey(apiKeyInput.trim())
                                    showApiKeyDialog = false
                                }
                            ) { Text("Save") }
                        },
                        dismissButton = {
                            Row {
                                if (uiState.aiApiKeySet) {
                                    TextButton(onClick = {
                                        viewModel.clearAiApiKey()
                                        showApiKeyDialog = false
                                    }) { Text("Remove key", color = MaterialTheme.colorScheme.error) }
                                }
                                TextButton(onClick = { showApiKeyDialog = false }) { Text("Cancel") }
                            }
                        }
                    )
                }

                if (showModelDialog) {
                    var modelSearch by remember(showModelDialog) { mutableStateOf("") }
                    val filteredModels = remember(uiState.aiFreeModels, modelSearch) {
                        if (modelSearch.isBlank()) uiState.aiFreeModels
                        else uiState.aiFreeModels.filter {
                            it.name.contains(modelSearch, ignoreCase = true) || it.id.contains(modelSearch, ignoreCase = true)
                        }
                    }
                    AlertDialog(
                        onDismissRequest = { showModelDialog = false },
                        title = { Text("Choose a model") },
                        text = {
                            Column {
                                OutlinedTextField(
                                    value = modelInput,
                                    onValueChange = { modelInput = it },
                                    label = { Text("Model name") },
                                    singleLine = true,
                                    trailingIcon = {
                                        TextButton(onClick = {
                                            if (modelInput.isNotBlank()) {
                                                viewModel.setAiModel(modelInput.trim())
                                                showModelDialog = false
                                            }
                                        }) { Text("Use") }
                                    }
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Type an exact model ID above, or fetch and pick one below:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(6.dp))
                                if (uiState.aiFreeModels.isNotEmpty()) {
                                    OutlinedTextField(
                                        value = modelSearch,
                                        onValueChange = { modelSearch = it },
                                        label = { Text("Filter ${uiState.aiFreeModels.size} models\u2026") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        trailingIcon = if (modelSearch.isNotBlank()) {
                                            { IconButton(onClick = { modelSearch = "" }) { Icon(Icons.Default.Close, "Clear filter") } }
                                        } else null
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                                when {
                                    uiState.aiModelsLoading -> {
                                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator()
                                        }
                                    }
                                    uiState.aiModelsError != null -> {
                                        Text(uiState.aiModelsError ?: "", color = MaterialTheme.colorScheme.error)
                                        TextButton(onClick = { viewModel.refreshFreeModels() }) { Text("Retry fetching models") }
                                    }
                                    uiState.aiFreeModels.isEmpty() -> {
                                        TextButton(onClick = { viewModel.refreshFreeModels() }) { Text("Fetch available models") }
                                    }
                                    filteredModels.isEmpty() -> {
                                        Text(
                                            "No models match \"$modelSearch\".",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(vertical = 12.dp)
                                        )
                                    }
                                    else -> {
                                        LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                                            items(filteredModels, key = { it.id }) { model ->
                                                TextButton(
                                                    onClick = {
                                                        viewModel.setAiModel(model.id)
                                                        showModelDialog = false
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Column(modifier = Modifier.fillMaxWidth()) {
                                                        Text(model.name, style = MaterialTheme.typography.bodyMedium)
                                                        Text(
                                                            model.id,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showModelDialog = false }) { Text("Close") }
                        }
                    )
                }
            }

            SettingsSection(title = "App Updates") {
                val updateState by updateViewModel.uiState.collectAsState()
                val downloadState by downloadViewModel.state.collectAsState()
                var updateDownloadId by remember { mutableStateOf<String?>(null) }
                val updateDownloadItem = updateDownloadId?.let { id -> downloadState.downloads.firstOrNull { it.id == id } }

                LaunchedEffect(updateDownloadItem?.status) {
                    if (updateDownloadItem?.status == com.akay.feature.downloads.viewmodel.ItemStatus.COMPLETED) {
                        updateViewModel.markDownloadComplete(updateDownloadItem.resolvedPath)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Installed: v${updateState.currentVersion}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when {
                                updateState.checking -> "Checking\u2026"
                                updateState.error != null -> updateState.error!!
                                updateState.pendingInstallPath != null -> "v${updateState.latestVersion} downloaded \u2014 ready to install"
                                updateState.updateAvailable -> "Latest: v${updateState.latestVersion} \u2014 update available"
                                updateState.lastChecked != null -> "Latest: v${updateState.latestVersion ?: updateState.currentVersion} \u2014 you're up to date"
                                else -> "Tap to check for the latest version"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (updateState.updateAvailable || updateState.pendingInstallPath != null) Primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (updateState.checking) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { updateViewModel.checkForUpdates() }) { Text("Check") }
                    }
                }

                if (updateState.pendingInstallPath != null) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { com.akay.core.data.util.ApkInstaller.install(context, updateState.pendingInstallPath!!) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) { Text("Install update (v${updateState.latestVersion})") }
                } else if (updateState.updateAvailable) {
                    Spacer(Modifier.height(8.dp))
                    if (!updateState.releaseNotes.isNullOrBlank()) {
                        Text(
                            updateState.releaseNotes!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    when (updateDownloadItem?.status) {
                        null -> {
                            Button(
                                onClick = {
                                    val url = updateState.apkDownloadUrl ?: return@Button
                                    updateDownloadId = downloadViewModel.enqueue(
                                        url = url,
                                        filename = updateState.apkAssetName,
                                        useYtDlp = false
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Primary)
                            ) { Text("Download update") }
                        }
                        com.akay.feature.downloads.viewmodel.ItemStatus.COMPLETED -> {
                            // markDownloadComplete already fired via LaunchedEffect above,
                            // pendingInstallPath branch will take over on next recomposition.
                        }
                        com.akay.feature.downloads.viewmodel.ItemStatus.FAILED,
                        com.akay.feature.downloads.viewmodel.ItemStatus.CANCELLED -> {
                            Text(
                                "Download failed \u2014 open Downloads to retry.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {
                            LinearProgressIndicator(
                                progress = { updateDownloadItem?.progress ?: 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Downloading\u2026 also visible in the notification and Downloads screen if you navigate away",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            val crashLog = remember { viewModel.readLastCrashLog() }
            if (!crashLog.isNullOrBlank()) {
                var showCrashDialog by remember { mutableStateOf(false) }
                SettingsSection(title = "Diagnostics") {
                    SettingsNavigationItem(
                        title = "Last crash detected",
                        subtitle = "AxBrowser closed unexpectedly last time \u2014 tap to view details",
                        onClick = { showCrashDialog = true }
                    )
                }
                if (showCrashDialog) {
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    AlertDialog(
                        onDismissRequest = { showCrashDialog = false },
                        title = { Text("Last crash") },
                        text = {
                            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                                item {
                                    Text(
                                        crashLog,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(crashLog))
                            }) { Text("Copy") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                viewModel.clearLastCrashLog()
                                showCrashDialog = false
                            }) { Text("Dismiss") }
                        }
                    )
                }
            }

            SettingsSection(title = "General") {
                SettingsSwitchItem(
                    title = "Clear cache on exit",
                    checked = uiState.clearCacheOnExit,
                    onCheckedChange = { viewModel.setClearCacheOnExit(it) }
                )
                SettingsSwitchItem(
                    title = "Wi-Fi only downloads",
                    subtitle = "Pause downloads automatically on mobile data",
                    checked = uiState.wifiOnlyDownloads,
                    onCheckedChange = { viewModel.setWifiOnlyDownloads(it) }
                )
                SettingsSwitchItem(
                    title = "Battery saver",
                    subtitle = "Block images and reduce animations on mobile data / low battery",
                    checked = uiState.batterySaverEnabled,
                    onCheckedChange = { viewModel.setBatterySaverEnabled(it) }
                )
            }

            Spacer(Modifier.height(32.dp))
        }
      }
    }

    if (showSearchEngineDialog) {
        AlertDialog(
            onDismissRequest = { showSearchEngineDialog = false },
            title = { Text("Search engine") },
            text = {
                Column {
                    SEARCH_ENGINES.forEach { engine ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = uiState.searchEngineUrl == engine.url,
                                    onClick = {
                                        viewModel.setSearchEngine(engine.url)
                                        showSearchEngineDialog = false
                                    }
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.searchEngineUrl == engine.url,
                                onClick = {
                                    viewModel.setSearchEngine(engine.url)
                                    showSearchEngineDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(engine.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSearchEngineDialog = false }) { Text("Close") }
            }
        )
    }

    if (showClearDataDialog) {
        ClearBrowsingDataDialog(
            onDismiss = { showClearDataDialog = false },
            onConfirm = { history, cookies, cache ->
                showClearDataDialog = false
                viewModel.clearBrowsingData(history, cookies, cache) {
                    Toast.makeText(context, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showAccentDialog) {
        AlertDialog(
            onDismissRequest = { showAccentDialog = false },
            title = { Text("Accent color") },
            text = {
                Column {
                    AccentColors.forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = uiState.accentName == opt.name,
                                    onClick = { viewModel.setAccent(opt.name); showAccentDialog = false }
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(28.dp).background(opt.color, CircleShape))
                            Spacer(Modifier.width(16.dp))
                            Text(opt.name, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f))
                            if (uiState.accentName == opt.name) {
                                Icon(Icons.Default.ChevronRight, null, tint = opt.color)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAccentDialog = false }) { Text("Close") } }
        )
    }

    if (showHeadersDialog) {
        CustomHeadersDialog(
            initial = uiState.customHeaders,
            onDismiss = { showHeadersDialog = false },
            onSave = {
                viewModel.setCustomHeaders(it)
                showHeadersDialog = false
                Toast.makeText(context, "Custom headers saved", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showExportCookiesDialog) {
        AlertDialog(
            onDismissRequest = { showExportCookiesDialog = false },
            title = { Text("Export site cookies") },
            text = {
                Column {
                    Text(
                        "Enter the website whose cookies you want to export. " +
                            "The file uses the standard cookies.txt format.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = exportSite,
                        onValueChange = { exportSite = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("example.com") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = exportSite.isNotBlank(),
                    onClick = {
                        showExportCookiesDialog = false
                        val text = CookieTransfer.exportCookies(exportSite)
                        if (text == null) {
                            Toast.makeText(context, "No cookies found for that site", Toast.LENGTH_SHORT).show()
                        } else {
                            pendingCookieExport = text
                            val host = CookieTransfer.hostOf(exportSite) ?: "site"
                            exportLauncher.launch("cookies_$host.txt")
                        }
                        exportSite = ""
                    }
                ) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { showExportCookiesDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CustomHeadersDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom request headers") },
        text = {
            Column {
                Text(
                    "One header per line as \"Name: value\". These are sent with page " +
                        "loads. Example:\nX-Requested-With: AxBrowser\nDNT: 1",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    placeholder = { Text("Header-Name: value") },
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ClearBrowsingDataDialog(
    onDismiss: () -> Unit,
    onConfirm: (history: Boolean, cookies: Boolean, cache: Boolean) -> Unit
) {
    var clearHistory by remember { mutableStateOf(true) }
    var clearCookies by remember { mutableStateOf(false) }
    var clearCache by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear browsing data") },
        text = {
            Column {
                ClearDataRow("Browsing history", clearHistory) { clearHistory = it }
                ClearDataRow("Cookies and site data", clearCookies) { clearCookies = it }
                ClearDataRow("Cached files", clearCache) { clearCache = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = clearHistory || clearCookies || clearCache,
                onClick = { onConfirm(clearHistory, clearCookies, clearCache) }
            ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ClearDataRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FontSizeItem(fontSize: Int, onFontSizeChange: (Int) -> Unit) {
    var sliderValue by remember(fontSize) { mutableFloatStateOf(fontSize.toFloat()) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Text size", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${sliderValue.toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onFontSizeChange(sliderValue.toInt()) },
            valueRange = 75f..150f
        )
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

@Composable
fun SettingsNavigationItem(
    title: String,
    subtitle: String = "",
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank())
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            trailing()
            Spacer(Modifier.width(12.dp))
        }
        Icon(
            Icons.Default.ChevronRight, null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Int,
    range: IntRange,
    suffix: String = "",
    onChange: (Int) -> Unit
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${sliderValue.toInt()}$suffix", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onChange(sliderValue.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat()
        )
    }
}
