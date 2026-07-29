package com.akay.feature.browser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.akay.core.ui.theme.Primary
import com.akay.feature.browser.adblock.AdBlockEngine
import com.akay.feature.browser.devconsole.DevConsolePanel
import com.akay.feature.browser.devconsole.NetworkInterceptor
import com.akay.feature.browser.reader.ReaderMode
import com.akay.feature.browser.gesture.edgeSwipeNavigation
import com.akay.feature.browser.viewmodel.BrowserViewModel
import com.akay.feature.browser.webview.AxNetBridge
import com.akay.feature.browser.webview.PasswordCaptureBridge
import com.akay.feature.browser.webview.AxWebChromeClient
import com.akay.feature.browser.webview.AxWebViewClient
import com.akay.feature.browser.webview.HttpHeaderUtil
import com.akay.feature.downloads.ui.DetectedMediaUi
import com.akay.feature.downloads.ui.MediaBottomSheet
import com.akay.feature.downloads.viewmodel.DownloadViewModel

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private val INCOGNITO_BG = Color(0xFF201A2E)

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = hiltViewModel(),
    downloadViewModel: DownloadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var isEditingUrl by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var lastNavigatedUrl by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showMediaSheet by remember { mutableStateOf(false) }
    var showPasteLinkDialog by remember { mutableStateOf(false) }
    var pasteUrl by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }

    // Fullscreen video state
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    // Find-in-page state
    var findBarVisible by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findActiveMatch by remember { mutableIntStateOf(0) }
    var findTotalMatches by remember { mutableIntStateOf(0) }

    // Reader mode state
    var readerModeActive by remember { mutableStateOf(false) }
    var readerModeLoading by remember { mutableStateOf(false) }
    var preReaderUrl by remember { mutableStateOf<String?>(null) }

    val networkMedia by NetworkInterceptor.detectedMedia.collectAsState()
    val blockedCount by AdBlockEngine.blockedCount.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()

    // Preferences
    val erudaEnabled by viewModel.erudaEnabled.collectAsState(initial = false)
    val adBlockOn by viewModel.adBlockEnabled.collectAsState(initial = true)
    val httpsUpgradeOn by viewModel.httpsUpgradeEnabled.collectAsState(initial = true)
    val jsEnabled by viewModel.javascriptEnabled.collectAsState(initial = true)
    val desktopMode by viewModel.desktopMode.collectAsState(initial = false)
    val fontSize by viewModel.fontSize.collectAsState(initial = 100)
    val darkWebsites by viewModel.darkModeForWebsites.collectAsState(initial = false)
    val customHeadersRaw by viewModel.customHeaders.collectAsState(initial = "")
    val userScriptsRaw by viewModel.userScripts.collectAsState(initial = "")
    val userScripts = remember(userScriptsRaw) {
        com.akay.core.data.userscript.UserScriptCodec.decode(userScriptsRaw).filter { it.enabled }
    }
    val userScriptsState = rememberUpdatedState(userScripts)

    val isIncognito = uiState.activeTab?.isIncognito == true

    val erudaEnabledState = rememberUpdatedState(erudaEnabled)
    val adBlockOnState = rememberUpdatedState(adBlockOn)
    val httpsUpgradeOnState = rememberUpdatedState(httpsUpgradeOn)
    val incognitoState = rememberUpdatedState(isIncognito)
    val customHeaders = remember(customHeadersRaw) { HttpHeaderUtil.parse(customHeadersRaw) }
    val customHeadersState = rememberUpdatedState(customHeaders)

    var appliedDesktopMode by remember { mutableStateOf<Boolean?>(null) }
    // Default mobile UA with the "wv" WebView marker stripped so DRM/streaming
    // sites (Netflix, etc.) and WebView-blocking sites treat us as real Chrome.
    var mobileUserAgent by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { AdBlockEngine.ensureLoaded(context) }

    val mediaCount = uiState.detectedMediaCount + networkMedia.size
    val isNewTab = uiState.url.isBlank() || uiState.url == "about:blank"

    fun exitFullscreen() {
        val cb = fullscreenCallback
        fullscreenView = null
        fullscreenCallback = null
        cb?.onCustomViewHidden()
        activity?.let {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.getInsetsController(it.window, it.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Enter/exit immersive fullscreen when a custom view is presented.
    LaunchedEffect(fullscreenView) {
        val act = activity ?: return@LaunchedEffect
        if (fullscreenView != null) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.getInsetsController(act.window, act.window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // System back: exit fullscreen, close overlays, then navigate page back.
    BackHandler(enabled = fullscreenView != null || findBarVisible || uiState.showTabSwitcher || uiState.canGoBack) {
        when {
            fullscreenView != null -> exitFullscreen()
            findBarVisible -> { findBarVisible = false; webView?.clearMatches() }
            uiState.showTabSwitcher -> viewModel.toggleTabSwitcher()
            webView?.canGoBack() == true -> webView?.goBack()
        }
    }

    Scaffold(
        topBar = {
            if (fullscreenView == null) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isIncognito) INCOGNITO_BG else MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { webView?.goBack() },
                            enabled = uiState.canGoBack,
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
                                    onValueChange = { viewModel.onAddressQueryChanged(it) },
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
                                        imageVector = when {
                                            isIncognito -> Icons.Default.VisibilityOff
                                            uiState.displayUrl.startsWith("https") -> Icons.Default.Lock
                                            else -> Icons.Default.LockOpen
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(11.dp),
                                        tint = when {
                                            isIncognito -> MaterialTheme.colorScheme.onSurfaceVariant
                                            uiState.displayUrl.startsWith("https") -> Color(0xFF4CAF50)
                                            else -> MaterialTheme.colorScheme.error
                                        }
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = prettifyUrl(uiState.displayUrl),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (adBlockOn && blockedCount > 0) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = Primary.copy(alpha = 0.15f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.Shield, null,
                                                    modifier = Modifier.size(10.dp), tint = Primary
                                                )
                                                Spacer(Modifier.width(3.dp))
                                                Text("$blockedCount", fontSize = 10.sp, color = Primary,
                                                    fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
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

                        Surface(
                            onClick = { viewModel.toggleTabSwitcher() },
                            shape = RoundedCornerShape(7.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.6.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)),
                            modifier = Modifier.size(26.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = uiState.tabs.size.coerceAtLeast(1).toString(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.MoreVert, "Menu", modifier = Modifier.size(20.dp))
                            }
                            BrowserOverflowMenu(
                                expanded = showMenu,
                                onDismiss = { showMenu = false },
                                canGoForward = uiState.canGoForward,
                                desktopMode = desktopMode,
                                blockedCount = blockedCount,
                                adBlockOn = adBlockOn,
                                isIncognito = isIncognito,
                                onForward = { webView?.goForward() },
                                onNewTab = { viewModel.createNewTab() },
                                onNewIncognitoTab = { viewModel.createNewTab(incognito = true) },
                                onAddBookmark = {
                                    viewModel.bookmarkCurrentPage { ok ->
                                        Toast.makeText(
                                            context,
                                            if (ok) "Bookmark added" else "Nothing to bookmark",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onShare = {
                                    val url = uiState.displayUrl
                                    if (url.isNotBlank() && url != "about:blank") {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, url)
                                            putExtra(Intent.EXTRA_SUBJECT, uiState.title)
                                        }
                                        context.startActivity(Intent.createChooser(send, "Share page"))
                                    }
                                },
                                onFindInPage = {
                                    findBarVisible = true
                                    findQuery = ""
                                    findActiveMatch = 0
                                    findTotalMatches = 0
                                },
                                readerModeActive = readerModeActive,
                                onToggleReaderMode = {
                                    val wv = webView
                                    if (wv == null) {
                                        // no-op
                                    } else if (readerModeActive) {
                                        readerModeActive = false
                                        preReaderUrl?.let { wv.loadUrl(it) }
                                    } else {
                                        readerModeLoading = true
                                        wv.evaluateJavascript(ReaderMode.EXTRACTION_JS) { result ->
                                            readerModeLoading = false
                                            val article = ReaderMode.parse(result)
                                            if (article == null) {
                                                Toast.makeText(context, "Couldn't extract article text", Toast.LENGTH_SHORT).show()
                                            } else {
                                                preReaderUrl = uiState.displayUrl
                                                readerModeActive = true
                                                val html = ReaderMode.buildReaderHtml(
                                                    article,
                                                    fontSizePercent = fontSize,
                                                    darkMode = true
                                                )
                                                wv.loadDataWithBaseURL(uiState.displayUrl, html, "text/html", "UTF-8", null)
                                            }
                                        }
                                    }
                                },
                                onToggleDesktop = { viewModel.setDesktopMode(!desktopMode) },
                                onScreenshot = {
                                    val ok = webView?.let { captureAndShare(it, context) } ?: false
                                    if (!ok) Toast.makeText(context, "Nothing to capture", Toast.LENGTH_SHORT).show()
                                },
                                onTranslate = {
                                    val url = uiState.displayUrl
                                    if (url.isNotBlank() && url != "about:blank") {
                                        val enc = java.net.URLEncoder.encode(url, "UTF-8")
                                        viewModel.navigateToUrl("https://translate.google.com/translate?sl=auto&tl=en&u=$enc")
                                    }
                                },
                                onDevConsole = { viewModel.toggleDevConsole() },
                                siteAdBlockAllowlisted = viewModel.isAdBlockAllowlistedForCurrentSite(),
                                onToggleSiteAdBlock = { viewModel.toggleAdBlockForCurrentSite() }
                            )
                        }
                    }

                    AnimatedVisibility(visible = isEditingUrl && suggestions.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                        ) {
                            suggestions.take(6).forEach { suggestion ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val target = suggestion.url ?: suggestion.text
                                            isEditingUrl = false
                                            keyboardController?.hide()
                                            viewModel.navigateToUrl(target)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when (suggestion.type) {
                                            com.akay.feature.browser.suggest.SuggestionType.BOOKMARK -> Icons.Default.Star
                                            com.akay.feature.browser.suggest.SuggestionType.HISTORY -> Icons.Default.History
                                            com.akay.feature.browser.suggest.SuggestionType.REMOTE -> Icons.Default.Search
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            suggestion.text,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!suggestion.subtitle.isNullOrBlank()) {
                                            Text(
                                                suggestion.subtitle,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = findBarVisible) {
                        FindInPageBar(
                            query = findQuery,
                            activeMatch = findActiveMatch,
                            totalMatches = findTotalMatches,
                            onQueryChange = { q ->
                                findQuery = q
                                if (q.isBlank()) webView?.clearMatches() else webView?.findAllAsync(q)
                            },
                            onPrev = { webView?.findNext(false) },
                            onNext = { webView?.findNext(true) },
                            onClose = { findBarVisible = false; webView?.clearMatches() }
                        )
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
                            settings.databaseEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.setSupportMultipleWindows(false)
                            // Allow https pages to load http sub-resources some
                            // players need; EME/Widevine is enabled by default.
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            // Present as real Chrome (drop the "; wv" token).
                            val baseUa = settings.userAgentString ?: ""
                            mobileUserAgent = baseUa.replace(" wv)", ")").replace("; wv", "")
                            if (!desktopMode) settings.userAgentString = mobileUserAgent
                            WebView.setWebContentsDebuggingEnabled(true)
                            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                            addJavascriptInterface(AxNetBridge(), "AxNet")
                            addJavascriptInterface(
                                PasswordCaptureBridge { origin, username, password ->
                                    viewModel.onCredentialCaptured(origin, username, password)
                                },
                                PasswordCaptureBridge.INTERFACE_NAME
                            )

                            // Incognito: don't persist cache/cookies for this session.
                            if (incognitoState.value) {
                                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            }

                            setDownloadListener { url, _, contentDisposition, mimetype, _ ->
                                val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
                                downloadViewModel.enqueue(url = url, filename = filename, useYtDlp = false)
                                Toast.makeText(ctx, "Downloading $filename", Toast.LENGTH_SHORT).show()
                            }

                            setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                                findActiveMatch = if (numberOfMatches > 0) activeMatchOrdinal + 1 else 0
                                findTotalMatches = numberOfMatches
                            }

                            webViewClient = AxWebViewClient(
                                context = ctx,
                                onPageStarted = { url ->
                                    AdBlockEngine.resetCounter()
                                    viewModel.updateUrl(url)
                                    viewModel.updateNavigationState(
                                        isLoading = true,
                                        canGoBack = canGoBack(),
                                        canGoForward = canGoForward()
                                    )
                                    if (erudaEnabledState.value) {
                                        runCatching {
                                            ctx.assets.open("js/net_capture.js").bufferedReader().readText()
                                        }.getOrNull()?.let { evaluateJavascript(it, null) }
                                    }
                                    // document-start userscripts
                                    userScriptsState.value.forEach { script ->
                                        if (!script.runAtEnd && script.matches(url)) {
                                            evaluateJavascript("(function(){try{${script.code}}catch(e){console.error(e)}})();", null)
                                        }
                                    }
                                },
                                onPageFinished = { url, title ->
                                    viewModel.updateProgress(100)
                                    viewModel.updateNavigationState(
                                        isLoading = false,
                                        canGoBack = canGoBack(),
                                        canGoForward = canGoForward()
                                    )
                                    title?.let { viewModel.updateTitle(it) }
                                    if (url.isNotBlank() && url != "about:blank" && !incognitoState.value) {
                                        viewModel.recordHistory(url, title ?: url)
                                    }
                                    if (adBlockOnState.value) {
                                        runCatching {
                                            ctx.assets.open("js/adblock_cosmetic.js").bufferedReader().readText()
                                        }.getOrNull()?.let { evaluateJavascript(it, null) }
                                    }
                                    if (erudaEnabledState.value) {
                                        val lib = runCatching {
                                            ctx.assets.open("js/eruda.min.js").bufferedReader().readText()
                                        }.getOrNull()
                                        val init = runCatching {
                                            ctx.assets.open("js/eruda_init.js").bufferedReader().readText()
                                        }.getOrNull()
                                        if (lib != null && init != null) {
                                            evaluateJavascript(lib) { evaluateJavascript(init, null) }
                                        }
                                    }
                                    runCatching {
                                        ctx.assets.open("js/media_scanner.js").bufferedReader().readText()
                                    }.getOrNull()?.let { script ->
                                        evaluateJavascript(script) { result ->
                                            if (!result.isNullOrEmpty() && result != "null") {
                                                parseAndReportDomMedia(result)
                                            }
                                        }
                                    }
                                    // document-end userscripts
                                    userScriptsState.value.forEach { script ->
                                        if (script.runAtEnd && script.matches(url)) {
                                            evaluateJavascript("(function(){try{${script.code}}catch(e){console.error(e)}})();", null)
                                        }
                                    }
                                    evaluateJavascript(PasswordCaptureBridge.CAPTURE_JS, null)
                                    viewModel.onPageOriginLoaded(url)
                                },
                                onError = { viewModel.updateTitle("Error") },
                                adBlockerEnabled = { adBlockOnState.value },
                                httpsUpgradeEnabled = { httpsUpgradeOnState.value },
                                onMediaDetected = { mUrl, mime ->
                                    NetworkInterceptor.onRequest(
                                        com.akay.feature.browser.devconsole.NetworkRequest(url = mUrl, mimeType = mime)
                                    )
                                }
                            )

                            webChromeClient = AxWebChromeClient(
                                onProgressChange = { viewModel.updateProgress(it) },
                                onTitleChange = { viewModel.updateTitle(it) },
                                onUrlChange = { },
                                onShowFullscreen = { view, cb ->
                                    fullscreenCallback = cb
                                    fullscreenView = view
                                },
                                onHideFullscreen = { exitFullscreen() }
                            )

                            webView = this
                            loadUrl(uiState.url, customHeadersState.value)
                        }
                    },
                    update = { wv ->
                        wv.settings.javaScriptEnabled = jsEnabled
                        if (wv.settings.textZoom != fontSize) {
                            wv.settings.textZoom = fontSize
                        }
                        run {
                            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
                            val onWifi = caps == null || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                            wv.settings.blockNetworkImage = uiState.batterySaverEnabled && !onWifi
                        }
                        @Suppress("DEPRECATION")
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            wv.settings.forceDark = if (darkWebsites)
                                android.webkit.WebSettings.FORCE_DARK_ON
                            else android.webkit.WebSettings.FORCE_DARK_OFF
                        }
                        if (appliedDesktopMode != desktopMode) {
                            val firstApply = appliedDesktopMode == null
                            appliedDesktopMode = desktopMode
                            wv.settings.userAgentString =
                                if (desktopMode) DESKTOP_USER_AGENT else mobileUserAgent
                            if (!firstApply) wv.reload()
                        }
                        if (uiState.url.isNotEmpty() && uiState.url != lastNavigatedUrl) {
                            lastNavigatedUrl = uiState.url
                            if (customHeadersState.value.isEmpty()) wv.loadUrl(uiState.url)
                            else wv.loadUrl(uiState.url, customHeadersState.value)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .edgeSwipeNavigation(
                            enabled = !readerModeActive,
                            canGoBack = uiState.canGoBack,
                            canGoForward = uiState.canGoForward,
                            onSwipeBack = { webView?.goBack() },
                            onSwipeForward = { webView?.goForward() }
                        )
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
                    onNewTab = { incognito -> viewModel.createNewTab(incognito = incognito) },
                    modifier = Modifier.fillMaxSize(),
                    onGroupTabs = { ids, name, color -> viewModel.groupTabs(ids, name, color) },
                    onAddToGroup = { tabId, groupId, name, color -> viewModel.addTabToExistingGroup(tabId, groupId, name, color) },
                    onRemoveFromGroup = { tabId -> viewModel.removeTabFromGroup(tabId) }
                )
            }

            if (!uiState.showTabSwitcher && !isNewTab && fullscreenView == null) {
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
                }
            }

            // Fullscreen video host
            if (fullscreenView != null) {
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { frame ->
                        frame.removeAllViews()
                        fullscreenView?.let { v ->
                            (v.parent as? ViewGroup)?.removeView(v)
                            frame.addView(
                                v,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                )
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .zIndex(10f)
                )
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
                        downloadViewModel.enqueueWithQualityPicker(pasteUrl)
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
            error     = dlState.formatError,
            status    = dlState.fetchStatus,
            onSelect  = { fmt -> downloadViewModel.downloadWithFormat(fmt) },
            onBestQuality = { downloadViewModel.downloadBestQuality() },
            onDismiss = { downloadViewModel.dismissQualityPicker() }
        )
    }
    val pendingCredentialSave by viewModel.pendingCredentialSave.collectAsState()
    pendingCredentialSave?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissSaveCredential() },
            icon = { Icon(Icons.Default.Password, null) },
            title = { Text("Save password?") },
            text = { Text("Save this password for ${prettifyUrl(pending.origin)}${if (pending.username.isNotBlank()) " (${pending.username})" else ""}?") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmSaveCredential() }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSaveCredential() }) { Text("Never") }
            }
        )
    }

    val fillableCredential by viewModel.fillableCredential.collectAsState()
    fillableCredential?.let { credential ->
        Box(modifier = Modifier.fillMaxSize().padding(bottom = 16.dp), contentAlignment = Alignment.BottomCenter) {
            ElevatedCard(
                onClick = {
                    webView?.evaluateJavascript(
                        PasswordCaptureBridge.fillCredentialJs(credential.username, credential.password),
                        null
                    )
                    viewModel.clearFillableCredential()
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Password, null, tint = Primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Fill saved login for ${credential.username.ifBlank { "this site" }}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(10.dp))
                    IconButton(onClick = { viewModel.clearFillableCredential() }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
    expanded: Boolean,
    onDismiss: () -> Unit,
    canGoForward: Boolean,
    desktopMode: Boolean,
    blockedCount: Int,
    adBlockOn: Boolean,
    isIncognito: Boolean,
    onForward: () -> Unit,
    onNewTab: () -> Unit,
    onNewIncognitoTab: () -> Unit,
    onAddBookmark: () -> Unit,
    onShare: () -> Unit,
    onFindInPage: () -> Unit,
    readerModeActive: Boolean,
    onToggleReaderMode: () -> Unit,
    onToggleDesktop: () -> Unit,
    onScreenshot: () -> Unit,
    onTranslate: () -> Unit,
    onDevConsole: () -> Unit,
    siteAdBlockAllowlisted: Boolean,
    onToggleSiteAdBlock: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (adBlockOn) {
            DropdownMenuItem(
                text = { Text(if (siteAdBlockAllowlisted) "Ad-block disabled on this site" else "Ad-block enabled on this site") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Shield,
                        null,
                        tint = if (siteAdBlockAllowlisted) MaterialTheme.colorScheme.onSurfaceVariant else Primary
                    )
                },
                onClick = { onDismiss(); onToggleSiteAdBlock() }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        if (blockedCount == 1) "1 ad blocked on this page"
                        else "$blockedCount ads blocked on this page",
                        style = MaterialTheme.typography.bodySmall,
                        color = Primary
                    )
                },
                leadingIcon = { Icon(Icons.Default.Shield, null, tint = Primary) },
                onClick = onDismiss,
                enabled = false
            )
            HorizontalDivider()
        }
        DropdownMenuItem(
            text = { Text("Forward") },
            leadingIcon = { Icon(Icons.Default.ArrowForward, null) },
            enabled = canGoForward,
            onClick = { onDismiss(); onForward() }
        )
        DropdownMenuItem(
            text = { Text("New tab") },
            leadingIcon = { Icon(Icons.Default.Add, null) },
            onClick = { onDismiss(); onNewTab() }
        )
        DropdownMenuItem(
            text = { Text(if (isIncognito) "New incognito tab (active)" else "New incognito tab") },
            leadingIcon = { Icon(Icons.Default.VisibilityOff, null) },
            onClick = { onDismiss(); onNewIncognitoTab() }
        )
        DropdownMenuItem(
            text = { Text("Add bookmark") },
            leadingIcon = { Icon(Icons.Default.StarBorder, null) },
            onClick = { onDismiss(); onAddBookmark() }
        )
        DropdownMenuItem(
            text = { Text("Share page") },
            leadingIcon = { Icon(Icons.Default.Share, null) },
            onClick = { onDismiss(); onShare() }
        )
        DropdownMenuItem(
            text = { Text("Find in page") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            onClick = { onDismiss(); onFindInPage() }
        )
        DropdownMenuItem(
            text = { Text(if (readerModeActive) "Exit reader mode" else "Reader mode") },
            leadingIcon = { Icon(Icons.Default.Article, null, tint = if (readerModeActive) Primary else LocalContentColor.current) },
            onClick = { onDismiss(); onToggleReaderMode() }
        )
        DropdownMenuItem(
            text = { Text("Translate page") },
            leadingIcon = { Icon(Icons.Default.Translate, null) },
            onClick = { onDismiss(); onTranslate() }
        )
        DropdownMenuItem(
            text = { Text("Screenshot page") },
            leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
            onClick = { onDismiss(); onScreenshot() }
        )
        DropdownMenuItem(
            text = { Text("Desktop site") },
            leadingIcon = { Icon(Icons.Default.Computer, null) },
            trailingIcon = { if (desktopMode) Icon(Icons.Default.Check, null, tint = Primary) },
            onClick = { onDismiss(); onToggleDesktop() }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Dev console") },
            leadingIcon = { Icon(Icons.Default.BugReport, null) },
            onClick = { onDismiss(); onDevConsole() }
        )
    }
}

@Composable
private fun FindInPageBar(
    query: String,
    activeMatch: Int,
    totalMatches: Int,
    onQueryChange: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Find in page", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (totalMatches > 0) "$activeMatch/$totalMatches" else "0/0",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = onPrev, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.KeyboardArrowUp, "Previous", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, "Next", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Close, "Close", modifier = Modifier.size(20.dp))
        }
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
                Text("Enter a URL to open, or download it with quality selection",
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

/** Renders the visible WebView to a PNG in cache and opens a share sheet. */
private fun captureAndShare(webView: WebView, context: Context): Boolean {
    if (webView.width <= 0 || webView.height <= 0) return false
    return try {
        val bitmap = android.graphics.Bitmap.createBitmap(
            webView.width, webView.height, android.graphics.Bitmap.Config.ARGB_8888
        )
        webView.draw(android.graphics.Canvas(bitmap))
        val dir = java.io.File(context.cacheDir, "screenshots").apply { mkdirs() }
        val file = java.io.File(dir, "shot_${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Share screenshot"))
        true
    } catch (_: Exception) {
        false
    }
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
