package com.akay.feature.browser.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.akay.core.ui.theme.Primary
import com.akay.feature.browser.devconsole.DevConsolePanel
import com.akay.feature.browser.devconsole.NetworkInterceptor
import com.akay.feature.browser.viewmodel.BrowserViewModel
import com.akay.feature.browser.webview.AxWebChromeClient
import com.akay.feature.browser.webview.AxWebViewClient
import com.akay.feature.downloads.ui.DetectedMediaUi
import com.akay.feature.downloads.ui.MediaBottomSheet
import com.akay.feature.downloads.viewmodel.DownloadViewModel

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = hiltViewModel(),
    downloadViewModel: DownloadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var isEditingUrl by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var lastNavigatedUrl by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showMediaSheet by remember { mutableStateOf(false) }
    var showPasteLinkDialog by remember { mutableStateOf(false) }
    var pasteUrl by remember { mutableStateOf("") }

    val networkMedia by NetworkInterceptor.detectedMedia.collectAsState()
    val erudaEnabled by viewModel.erudaEnabled.collectAsState(initial = false)
    val erudaEnabledState = remember { mutableStateOf(false) }
    LaunchedEffect(erudaEnabled) { erudaEnabledState.value = erudaEnabled }
    val mediaCount = uiState.detectedMediaCount + networkMedia.size

    val isNewTab = uiState.url.isBlank() || uiState.url == "about:blank"

    Scaffold(
        topBar = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { webView?.goBack() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowBack, "Back",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (uiState.canGoBack) 1f else 0.3f
                            )
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        onClick = { isEditingUrl = true }
                    ) {
                        if (isEditingUrl) {
                            OutlinedTextField(
                                value = uiState.displayUrl,
                                onValueChange = { viewModel.updateUrl(it) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = {
                                    Text("Search or enter URL",
                                        style = MaterialTheme.typography.bodySmall)
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor   = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent
                                ),
                                textStyle = MaterialTheme.typography.bodySmall,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = {
                                    viewModel.navigateToUrl(uiState.displayUrl)
                                    isEditingUrl = false
                                    keyboardController?.hide()
                                })
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (uiState.displayUrl.startsWith("https"))
                                        Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(11.dp),
                                    tint = if (uiState.displayUrl.startsWith("https"))
                                        Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = prettifyUrl(uiState.displayUrl),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { webView?.goForward() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowForward, "Forward",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (uiState.canGoForward) 1f else 0.3f
                            )
                        )
                    }

                    IconButton(
                        onClick = {
                            if (uiState.isLoading) webView?.stopLoading() else webView?.reload()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Crossfade(targetState = uiState.isLoading, label = "refresh") { loading ->
                            Icon(
                                if (loading) Icons.Default.Close else Icons.Default.Refresh,
                                null, modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { viewModel.toggleTabSwitcher() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Menu, "Menu", modifier = Modifier.size(20.dp))
                    }
                }

                AnimatedVisibility(
                    visible = uiState.isLoading,
                    enter = fadeIn(tween(100)),
                    exit  = fadeOut(tween(300))
                ) {
                    val animatedProgress by animateFloatAsState(
                        targetValue = uiState.progress / 100f,
                        animationSpec = tween(200),
                        label = "progress"
                    )
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = Primary,
                        trackColor = Color.Transparent
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isNewTab) {
                NewTabPage(onSearch = { viewModel.navigateToUrl(it) })
            } else {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                            webViewClient = AxWebViewClient(
                                context = ctx,
                                onPageStarted = { url ->
                                    viewModel.updateUrl(url)
                                    viewModel.updateNavigationState(
                                        isLoading = true,
                                        canGoBack = canGoBack(),
                                        canGoForward = canGoForward()
                                    )
                                },
                                onPageFinished = { url, title ->
                                    viewModel.updateProgress(100)
                                    viewModel.updateNavigationState(
                                        isLoading = false,
                                        canGoBack = canGoBack(),
                                        canGoForward = canGoForward()
                                    )
                                    title?.let { viewModel.updateTitle(it) }
                                    if (url.isNotBlank() && url != "about:blank") {
                                        viewModel.recordHistory(url, title ?: url)
                                    }
                                    if (erudaEnabledState.value) {
                                        val js = runCatching {
                                            ctx.assets.open("js/eruda_init.js").bufferedReader().readText()
                                        }.getOrNull()
                                        js?.let { evaluateJavascript(it, null) }
                                    }
                                    val scanJs = runCatching {
                                        ctx.assets.open("js/media_scanner.js").bufferedReader().readText()
                                    }.getOrNull()
                                    scanJs?.let { script ->
                                        evaluateJavascript(script) { result ->
                                            if (!result.isNullOrEmpty() && result != "null") {
                                                parseAndReportDomMedia(result)
                                            }
                                        }
                                    }
                                },
                                onError = { viewModel.updateTitle("Error") },
                                onMediaDetected = { url, mime ->
                                    NetworkInterceptor.onRequest(
                                        com.akay.feature.browser.devconsole.NetworkRequest(url = url, mimeType = mime)
                                    )
                                }
                            )

                            webChromeClient = AxWebChromeClient(
                                onProgressChange = { viewModel.updateProgress(it) },
                                onTitleChange = { viewModel.updateTitle(it) },
                                onUrlChange = { }
                            )

                            webView = this
                            loadUrl(uiState.url)
                        }
                    },
                    update = { wv ->
                        if (uiState.url.isNotEmpty() && uiState.url != lastNavigatedUrl) {
                            lastNavigatedUrl = uiState.url
                            wv.loadUrl(uiState.url)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            AnimatedVisibility(
                visible = uiState.showTabSwitcher,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                TabSwitcherOverlay(
                    tabs = uiState.tabs,
                    activeTabId = uiState.activeTab?.id,
                    onTabClick = { viewModel.setActiveTab(it) },
                    onCloseTab = { viewModel.closeTab(it) },
                    onNewTab = { viewModel.createNewTab() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (!uiState.showTabSwitcher && !isNewTab) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    AnimatedVisibility(
                        visible = mediaCount > 0,
                        enter = scaleIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                        exit  = scaleOut(tween(200)) + fadeOut()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                            val pulseScale by infiniteTransition.animateFloat(
                                initialValue = 1f, targetValue = 1.25f,
                                animationSpec = infiniteRepeatable(
                                    animation  = tween(900, easing = EaseInOut),
                                    repeatMode = RepeatMode.Reverse
                                ), label = "ring"
                            )
                            val pulseAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.4f, targetValue = 0f,
                                animationSpec = infiniteRepeatable(
                                    animation  = tween(900),
                                    repeatMode = RepeatMode.Reverse
                                ), label = "ringAlpha"
                            )
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .scale(pulseScale)
                                    .background(Primary.copy(alpha = pulseAlpha), CircleShape)
                            )
                            ExtendedFloatingActionButton(
                                onClick = { showMediaSheet = true },
                                containerColor = Primary,
                                contentColor = Color.White,
                                icon = { Icon(Icons.Default.Download, null) },
                                text = {
                                    Text(
                                        if (mediaCount == 1) "1 media" else "$mediaCount media",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    SmallFloatingActionButton(
                        onClick = { showPasteLinkDialog = true },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Link, "Paste Link",
                            tint = MaterialTheme.colorScheme.primary)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    FloatingActionButton(
                        onClick = { viewModel.createNewTab() },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Add, "New Tab",
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        DevConsolePanel(
            isVisible = uiState.devConsoleVisible,
            currentPageUrl = uiState.displayUrl,
            currentPageHtml = uiState.pageHtml,
            onDismiss = { viewModel.toggleDevConsole() }
        )

        if (showMediaSheet) {
            val allMedia = networkMedia.map {
                DetectedMediaUi(it.id, it.url, it.filename, it.mimeType, it.isVideo)
            }
            if (allMedia.isNotEmpty()) {
                MediaBottomSheet(
                    detectedUrls = allMedia,
                    onDownloadDirect = { url, filename ->
                        showMediaSheet = false
                        downloadViewModel.enqueue(url = url, filename = filename, useYtDlp = false)
                    },
                    onDownloadWithYtDlp = { url ->
                        showMediaSheet = false
                        downloadViewModel.enqueueWithQualityPicker(url)
                    },
                    onDismiss = { showMediaSheet = false }
                )
            }
        }

        if (showPasteLinkDialog) {
            PasteLinkDialog(
                url = pasteUrl,
                onUrlChange = { pasteUrl = it },
                onOpenInBrowser = {
                    showPasteLinkDialog = false
                    if (pasteUrl.isNotBlank()) {
                        viewModel.navigateToUrl(pasteUrl)
                        pasteUrl = ""
                    }
                },
                onDownload = {
                    showPasteLinkDialog = false
                    if (pasteUrl.isNotBlank()) {
                        downloadViewModel.enqueue(url = pasteUrl, filename = "%(title)s.%(ext)s", useYtDlp = true)
                        pasteUrl = ""
                    }
                },
                onDismiss = { showPasteLinkDialog = false }
            )
        }
    }

    val dlState by downloadViewModel.state.collectAsState()
    if (dlState.showQualityPicker) {
        com.akay.feature.downloads.ui.QualityPickerSheet(
            title     = dlState.qualityPickerTitle,
            formats   = dlState.qualityFormats,
            isLoading = dlState.isFetchingFormats,
            onSelect  = { fmt -> downloadViewModel.downloadWithFormat(fmt) },
            onDismiss = { downloadViewModel.dismissQualityPicker() }
        )
    }
}

@Composable
fun PasteLinkDialog(
    url: String,
    onUrlChange: (String) -> Unit,
    onOpenInBrowser: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Link, contentDescription = null) },
        title = { Text("Paste Link") },
        text = {
            Column {
                Text("Enter a URL to open or download",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onDownload, enabled = url.isNotBlank()) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download")
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onOpenInBrowser, enabled = url.isNotBlank()) {
                    Text("Open")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun prettifyUrl(url: String): String =
    url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        .let { if (it.length > 45) it.take(42) + "..." else it }

private fun parseAndReportDomMedia(json: String) {
    try {
        val cleaned = json.trim().removeSurrounding("\"")
            .replace("\\\"", "\"").replace("\\n", "\n").replace("\\/", "/")
            .replace("\\u003C", "<")
        if (!cleaned.startsWith("[")) return
        var i = 0
        while (i < cleaned.length) {
            val urlStart = cleaned.indexOf("\"url\"", i).takeIf { it >= 0 } ?: break
            val u1 = cleaned.indexOf("\"", cleaned.indexOf(":", urlStart) + 1) + 1
            val u2 = cleaned.indexOf("\"", u1)
            val url = cleaned.substring(u1, u2)
            val typeStart = cleaned.indexOf("\"type\"", u2).takeIf { it >= 0 } ?: break
            val t1 = cleaned.indexOf("\"", cleaned.indexOf(":", typeStart) + 1) + 1
            val t2 = cleaned.indexOf("\"", t1)
            val type = cleaned.substring(t1, t2)
            if (url.startsWith("http")) NetworkInterceptor.addDomMedia(url, type)
            i = t2 + 1
        }
    } catch (_: Exception) {}
}
