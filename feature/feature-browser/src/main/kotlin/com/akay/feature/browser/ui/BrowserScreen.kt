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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import com.akay.feature.browser.gesture.EdgeSwipeOverlay
import com.akay.feature.browser.viewmodel.BrowserViewModel
import com.akay.feature.browser.webview.AxNetBridge
import com.akay.feature.browser.agent.normalizeDownloadUrl
import com.akay.feature.browser.webview.DialogBridge
import com.akay.feature.browser.webview.PasswordCaptureBridge
import com.akay.feature.browser.webview.evalJs
import com.akay.feature.browser.webview.unwrapJsString
import com.akay.feature.browser.webview.AxWebChromeClient
import com.akay.feature.browser.webview.AxWebViewClient
import com.akay.feature.browser.webview.HttpHeaderUtil
import com.akay.feature.downloads.ui.DetectedMediaUi
import com.akay.feature.downloads.ui.MediaBottomSheet
import com.akay.feature.downloads.viewmodel.DownloadViewModel
import kotlin.math.roundToInt

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
    downloadViewModel: DownloadViewModel = hiltViewModel(),
    onOpenSettings: () -> Unit = {}
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
    // Draggable floating controls (media button + paste-link button). NaN = "use the default
    // bottom-right corner", so the position is only pinned once the operator actually drags.
    var floatingX by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var floatingY by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var floatingAreaSize by remember { mutableStateOf(IntSize.Zero) }
    var floatingSize by remember { mutableStateOf(IntSize.Zero) }
    var pasteUrl by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var agentSheetVisible by remember { mutableStateOf(false) }
    // Park JS dialogs for the agent while its sheet is open; release any held dialog when it closes.
    DisposableEffect(agentSheetVisible) {
        DialogBridge.setEnabled(agentSheetVisible)
        onDispose { DialogBridge.setEnabled(false) }
    }
    // The intercept decision channel targets one WebView; drop it when this screen goes away
    // so a decision can never be pushed into a destroyed view.
    DisposableEffect(Unit) {
        onDispose { com.akay.feature.pentest.intercept.PushChannel.unregister() }
    }
    // Mirror the Intercept switch and Match&Replace rules into the page the moment they change,
    // so flipping the switch in the Intercept tab affects calls the current page makes next -
    // without it, the toggle would only take effect after the next navigation.
    val interceptEnabledNow by com.akay.feature.pentest.intercept.InterceptController.enabled.collectAsState()
    val interceptRulesNow by com.akay.feature.pentest.intercept.InterceptController.rules.collectAsState()
    LaunchedEffect(interceptEnabledNow, webView) {
        webView?.evaluateJavascript(
            "window.__axSetIntercept && window.__axSetIntercept($interceptEnabledNow);",
            null
        )
    }
    LaunchedEffect(interceptRulesNow, webView) {
        val rulesJson = org.json.JSONObject.quote(com.akay.feature.pentest.intercept.InterceptController.rulesForJs())
        webView?.evaluateJavascript(
            "window.__axSetMatchReplace && window.__axSetMatchReplace($rulesJson);",
            null
        )
    }

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
    val fingerprintScript by viewModel.fingerprintScript.collectAsState()
    var appliedFingerprintScript by remember { mutableStateOf<String?>(null) }
    var fingerprintScriptHandler by remember { mutableStateOf<androidx.webkit.ScriptHandler?>(null) }

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
                            onClick = { agentSheetVisible = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, "AI Agent", modifier = Modifier.size(20.dp), tint = Primary)
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
                            setLayerType(View.LAYER_TYPE_HARDWARE, null)
                            overScrollMode = View.OVER_SCROLL_ALWAYS
                            isNestedScrollingEnabled = true
                            viewModel.onWebViewReady()
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
                                activeProxy = { viewModel.activeProxy.value },
                                onPageStarted = { url ->
                                    AdBlockEngine.resetCounter()
                                    viewModel.updateUrl(url)
                                    viewModel.updateNavigationState(
                                        isLoading = true,
                                        canGoBack = canGoBack(),
                                        canGoForward = canGoForward()
                                    )
                                    // Network capture is ALWAYS on. It is the only capture path
                                    // that can read request/response BODIES - Android's
                                    // shouldInterceptRequest (which feeds the dev console for
                                    // everything else) has no body API at all. Gating this behind
                                    // the Eruda dev-tools toggle meant that with Eruda off (the
                                    // default) no POST body was ever captured, so get_curl and the
                                    // Network tab showed body-less requests forever.
                                    runCatching {
                                        ctx.assets.open("js/net_capture.js").bufferedReader().readText()
                                    }.getOrNull()?.let { captureScript ->
                                        evaluateJavascript(captureScript) {
                                            // Page-JS state dies with every document, so re-publish
                                            // the intercept switch and Match&Replace rules into
                                            // each freshly loaded page.
                                            evaluateJavascript(
                                                "window.__axSetIntercept && window.__axSetIntercept(${com.akay.feature.pentest.intercept.InterceptController.enabled.value});",
                                                null
                                            )
                                            evaluateJavascript(
                                                "window.__axSetMatchReplace && window.__axSetMatchReplace(${org.json.JSONObject.quote(com.akay.feature.pentest.intercept.InterceptController.rulesForJs())});",
                                                null
                                            )
                                        }
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
                            // Native -> page channel for Intercept decisions. InterceptController
                            // holds the parked request; this pushes the operator's Forward/Drop back
                            // into the fetch/XHR promise that is still waiting in net_capture.js.
                            val interceptTarget = this
                            com.akay.feature.pentest.intercept.PushChannel.register { json ->
                                val quoted = org.json.JSONObject.quote(json)
                                interceptTarget.evaluateJavascript(
                                    "window.__axInterceptDecide && window.__axInterceptDecide($quoted);",
                                    null
                                )
                            }
                            loadUrl(uiState.url, customHeadersState.value)
                        }
                    },
                    update = { wv ->
                        wv.settings.javaScriptEnabled = jsEnabled
                        if (wv.settings.textZoom != fontSize) {
                            wv.settings.textZoom = fontSize
                        }
                        if (fingerprintScript != appliedFingerprintScript) {
                            appliedFingerprintScript = fingerprintScript
                            runCatching { fingerprintScriptHandler?.remove() }
                            fingerprintScriptHandler = null
                            if (fingerprintScript != null &&
                                androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)
                            ) {
                                fingerprintScriptHandler = runCatching {
                                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                                        wv, fingerprintScript!!, setOf("*")
                                    )
                                }.getOrNull()
                            }
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
                    modifier = Modifier.fillMaxSize()
                )

                EdgeSwipeOverlay(
                    canGoBack = uiState.canGoBack && !readerModeActive,
                    canGoForward = uiState.canGoForward && !readerModeActive,
                    onSwipeBack = { webView?.goBack() },
                    onSwipeForward = { webView?.goForward() },
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
                    onNewTab = { incognito -> viewModel.createNewTab(incognito = incognito) },
                    modifier = Modifier.fillMaxSize(),
                    onGroupTabs = { ids, name, color -> viewModel.groupTabs(ids, name, color) },
                    onAddToGroup = { tabId, groupId, name, color -> viewModel.addTabToExistingGroup(tabId, groupId, name, color) },
                    onRemoveFromGroup = { tabId -> viewModel.removeTabFromGroup(tabId) },
                    onRenameGroup = { groupId, name -> viewModel.renameGroup(groupId, name) },
                    onUngroupAll = { groupId -> viewModel.ungroupAll(groupId) },
                    onCloseGroup = { groupId -> viewModel.closeGroup(groupId) }
                )
            }

            if (!uiState.showTabSwitcher && !isNewTab && fullscreenView == null) {
                // Floating controls sit in a drag-anywhere overlay. Default is the bottom-right
                // corner (as before); once dragged, the stack stays where it was put so it stops
                // covering the page being inspected. Clamped to the content area so a button can
                // never be moved off-screen. Position survives rotation via rememberSaveable.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { floatingAreaSize = it }
                ) {
                    val maxX = (floatingAreaSize.width - floatingSize.width).coerceAtLeast(0).toFloat()
                    val maxY = (floatingAreaSize.height - floatingSize.height).coerceAtLeast(0).toFloat()
                    val floatingPosX = if (floatingX.isNaN()) maxX else floatingX.coerceIn(0f, maxX)
                    val floatingPosY = if (floatingY.isNaN()) maxY else floatingY.coerceIn(0f, maxY)
                    Column(
                        modifier = Modifier
                            .offset { IntOffset(floatingPosX.roundToInt(), floatingPosY.roundToInt()) }
                            .onSizeChanged { floatingSize = it }
                            // Drag anywhere on the stack to move it. Taps still reach the buttons:
                            // detectDragGestures only consumes the pointer past the touch slop, so a
                            // plain tap is never swallowed by the drag handler.
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val limitX = (floatingAreaSize.width - floatingSize.width)
                                        .coerceAtLeast(0).toFloat()
                                    val limitY = (floatingAreaSize.height - floatingSize.height)
                                        .coerceAtLeast(0).toFloat()
                                    val baseX = if (floatingX.isNaN()) limitX else floatingX
                                    val baseY = if (floatingY.isNaN()) limitY else floatingY
                                    floatingX = (baseX + dragAmount.x).coerceIn(0f, limitX)
                                    floatingY = (baseY + dragAmount.y).coerceIn(0f, limitY)
                                }
                            }
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
            onDismiss = { viewModel.toggleDevConsole() },
            // Console tab REPL: same evaluation path the agent's run_js uses, so a snippet that
            // works for the agent works for the operator typing it by hand.
            onEvaluateJs = { code ->
                val wv = webView
                if (wv == null) "No page is currently loaded."
                else unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.runJs(code)))
            }
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

    // The agent's live plan. Shared by the executor (todo_write/todo_read), the sheet that
    // renders it, and the engine prompt that is told to keep it current.
    val agentTodo = remember { com.akay.feature.browser.agent.AgentTodoList() }

    val agentTools = remember(webView) {
        object : com.akay.feature.browser.agent.AgentToolExecutor {
            private suspend fun waitForLoad() {
                kotlinx.coroutines.delay(400)
                var waited = 0
                while (viewModel.uiState.value.isLoading && waited < 6000) {
                    kotlinx.coroutines.delay(300)
                    waited += 300
                }
                kotlinx.coroutines.delay(300)
            }

            override suspend fun navigate(url: String) {
                val target = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                viewModel.navigateToUrl(target)
                waitForLoad()
            }

            override suspend fun searchAndOpen(engine: String, query: String) {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val searchUrl = when (engine.lowercase()) {
                    "youtube" -> "https://www.youtube.com/results?search_query=$encoded"
                    else -> "https://www.google.com/search?q=$encoded"
                }
                viewModel.navigateToUrl(searchUrl)
                waitForLoad()
            }

            override suspend fun getPageText(): String {
                val wv = webView ?: return "No page is currently loaded."
                return unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.GET_PAGE_TEXT))
            }

            override suspend fun getPageLinks(): List<com.akay.feature.browser.agent.AgentLink> {
                val wv = webView ?: return emptyList()
                val raw = unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.GET_LINKS))
                return runCatching {
                    val arr = org.json.JSONArray(raw)
                    (0 until arr.length()).map {
                        val o = arr.getJSONObject(it)
                        com.akay.feature.browser.agent.AgentLink(o.optString("text"), o.optString("href"))
                    }
                }.getOrDefault(emptyList())
            }

            override suspend fun clickLinkContaining(text: String): Boolean {
                val wv = webView ?: return false
                val clicked = unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.clickLinkContaining(text))) == "true"
                if (clicked) waitForLoad()
                return clicked
            }

            override suspend fun startDownload(url: String): String {
                val normalized = normalizeDownloadUrl(url, webView?.url ?: viewModel.uiState.value.displayUrl)
                if (normalized.isBlank()) return "No URL was provided to download and no page is currently open."
                downloadViewModel.enqueueWithQualityPicker(normalized)
                return "Started a download for $normalized \u2014 check the Downloads screen for progress."
            }

            override suspend fun currentUrl(): String = webView?.url ?: viewModel.uiState.value.displayUrl

            override suspend fun goBack(): Boolean {
                val wv = webView ?: return false
                if (!wv.canGoBack()) return false
                wv.goBack()
                waitForLoad()
                return true
            }

            override suspend fun goForward(): Boolean {
                val wv = webView ?: return false
                if (!wv.canGoForward()) return false
                wv.goForward()
                waitForLoad()
                return true
            }

            override suspend fun scrape(selector: String, attribute: String?): List<String> {
                val wv = webView ?: return emptyList()
                if (selector.isBlank()) return emptyList()
                val raw = unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.scrape(selector, attribute)))
                return runCatching {
                    val arr = org.json.JSONArray(raw)
                    (0 until arr.length()).map { arr.optString(it) }
                }.getOrDefault(emptyList())
            }

            override suspend fun scrapeStructured(itemSelector: String, fields: Map<String, String>): List<Map<String, String>> {
                val wv = webView ?: return emptyList()
                if (itemSelector.isBlank() || fields.isEmpty()) return emptyList()
                val raw = unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.scrapeStructured(itemSelector, fields)))
                return runCatching {
                    val arr = org.json.JSONArray(raw)
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        o.keys().asSequence().associateWith { k -> o.optString(k) }
                    }
                }.getOrDefault(emptyList())
            }

            override suspend fun runJs(code: String): String {
                val wv = webView ?: return "No page is currently loaded."
                if (code.isBlank()) return "No code provided."
                return unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.runJs(code)))
            }

            override suspend fun getNetworkRequests(filter: String?): List<String> {
                val all = com.akay.feature.browser.devconsole.NetworkInterceptor.requests.value
                val matched = if (filter.isNullOrBlank()) all else all.filter { it.url.contains(filter, ignoreCase = true) }
                return matched.takeLast(30).map { req ->
                    "${req.method} ${req.url} \u2192 ${req.responseStatus ?: "?"} ${req.mimeType ?: ""} ${if (req.sizeBytes > 0) "${req.sizeBytes / 1024}KB" else ""}".trim()
                }
            }

            override suspend fun findApiRequests(filter: String?, method: String?): List<String> {
                val all = com.akay.feature.browser.devconsole.NetworkInterceptor.requests.value
                val apiOnly = all.filter { it.isApiLike }
                val matched = apiOnly
                    .let { if (filter.isNullOrBlank()) it else it.filter { r -> r.url.contains(filter, ignoreCase = true) } }
                    .let { if (method.isNullOrBlank()) it else it.filter { r -> r.method.equals(method, ignoreCase = true) } }
                return matched.takeLast(30).map { req ->
                    "${req.method} ${req.url} \u2192 ${req.responseStatus ?: "?"} ${req.mimeType ?: ""}".trim()
                }
            }

            override suspend fun getCurlForRequest(urlFilter: String, sanitized: Boolean, method: String?): String? {
                val all = com.akay.feature.browser.devconsole.NetworkInterceptor.requests.value
                val candidates = all
                    .filter { it.url.contains(urlFilter, ignoreCase = true) }
                    .let { if (method.isNullOrBlank()) it else it.filter { r -> r.method.equals(method, ignoreCase = true) } }
                    // OPTIONS preflight is never the request the user actually wants - drop it
                    // unless it's literally the only thing that matched (still better than nothing).
                    .let { list -> list.filterNot { it.isPreflight }.ifEmpty { list } }
                if (candidates.isEmpty()) return null
                // Prefer the most recent match that actually carries a body for methods that are
                // expected to have one (POST/PUT/PATCH/DELETE) - a body-less entry for those methods
                // means only the pre-response placeholder was captured (Android's WebView API can't
                // read POST bodies natively; the real payload comes from the JS fetch/XHR bridge a
                // moment later). Falling back to plain lastOrNull only when nothing better exists
                // keeps GET requests (which legitimately have no body) working exactly as before.
                val match = candidates.lastOrNull { req ->
                    req.method.equals("GET", ignoreCase = true) || req.requestBody.isNotBlank()
                } ?: candidates.last()
                val cookie = runCatching { CookieManager.getInstance().getCookie(match.url) }.getOrNull()
                val extra = if (!cookie.isNullOrBlank() && match.requestHeaders.keys.none { it.equals("cookie", ignoreCase = true) }) {
                    mapOf("Cookie" to cookie)
                } else emptyMap()
                val curl = match.toCurl(extra, sanitize = sanitized)
                val authNote = match.authHeaderNames
                val bodyNote = if (!match.method.equals("GET", ignoreCase = true) && match.requestBody.isBlank()) {
                    "\n\n# NOTE: no request body was captured for this call. Either it was a plain HTML <form> submit " +
                        "(Android's WebView API can't read POST bodies at all for those), OR - if this looks like it " +
                        "should have a JSON body - the site likely issues this fetch() from inside a Web Worker rather " +
                        "than the main page. Only main-page fetch/XHR calls are visible to this capture; a worker has " +
                        "its own separate JS scope this app has no way to hook into or read from."
                } else ""
                val antiReplayNote = if (match.hasLikelyAntiReplayHeaders && !sanitized) {
                    "\n\n# NOTE: this request also carries what looks like a solved anti-bot challenge or a short-lived " +
                        "signed/rotating token, not a stable reusable key. Even with the full body, this exact command " +
                        "may be REJECTED if you run it later or from a different device/IP - that's the site's anti-" +
                        "automation working as intended, not a capture bug. It's most likely to still work if you run " +
                        "it again immediately, from the same network."
                } else ""
                if (authNote.isNotEmpty() && !sanitized) {
                    return "$curl\n\n# NOTE: this request carries auth via: ${authNote.joinToString(", ")} \u2014 " +
                        "tied to your own logged-in session on this site. Treat it like a password: don't post it " +
                        "publicly or share it with anyone else.$bodyNote$antiReplayNote"
                }
                return curl + bodyNote + antiReplayNote
            }

            override suspend fun getResponseBody(urlFilter: String): String? {
                val all = com.akay.feature.browser.devconsole.NetworkInterceptor.requests.value
                val match = all.lastOrNull { it.url.contains(urlFilter, ignoreCase = true) && it.responseBody.isNotBlank() } ?: return null
                return match.responseBody.take(3000)
            }

            override suspend fun getDetectedMedia(): List<String> {
                return com.akay.feature.browser.devconsole.NetworkInterceptor.detectedMedia.value.map { media ->
                    "${if (media.isVideo) "video" else "audio"}: ${media.filename} \u2192 ${media.url}"
                }
            }

            override suspend fun listTabs(): List<String> {
                return viewModel.uiState.value.tabs.map { tab ->
                    "${tab.title.ifBlank { "New tab" }} \u2192 ${tab.url}${if (tab.id == viewModel.uiState.value.activeTab?.id) " (active)" else ""}"
                }
            }

            override suspend fun findAuthFlow(): String {
                val all = com.akay.feature.browser.devconsole.NetworkInterceptor.requests.value
                if (all.isEmpty()) return "No requests captured yet - browse/log in on the page first."

                // A request "grants" auth if its response sets a cookie, or its JSON body contains
                // a token-shaped field (access_token, token, jwt, session_id, ...).
                val tokenFieldNames = listOf("access_token", "token", "jwt", "session_id", "sessionid", "auth_token", "id_token")
                val grantors = all.filter { req ->
                    val setsCookie = req.responseHeaders.keys.any { it.equals("set-cookie", ignoreCase = true) }
                    val bodyHasToken = tokenFieldNames.any { field -> req.responseBody.contains("\"$field\"", ignoreCase = true) }
                    setsCookie || bodyHasToken
                }
                if (grantors.isEmpty()) return "No request in the captured log looks like it granted a session (no Set-Cookie, no token field in a response body)."

                val sb = StringBuilder("Requests that appear to establish the session:\n")
                grantors.take(5).forEach { g ->
                    sb.append("- ${g.method} ${g.url} \u2192 ${g.responseStatus ?: "?"}")
                    if (g.responseHeaders.keys.any { it.equals("set-cookie", ignoreCase = true) }) sb.append(" (sets a cookie)")
                    val foundField = tokenFieldNames.firstOrNull { g.responseBody.contains("\"$it\"", ignoreCase = true) }
                    if (foundField != null) sb.append(" (response body contains \"$foundField\")")
                    sb.append("\n")
                }

                val dependents = all.filter { req -> req.authHeaderNames.isNotEmpty() }
                    .distinctBy { it.url.substringBefore('?') }
                if (dependents.isNotEmpty()) {
                    sb.append("\nLater requests that then send auth (cookie/token) back:\n")
                    dependents.take(8).forEach { d ->
                        sb.append("- ${d.method} ${d.url} \u2192 sends ${d.authHeaderNames.joinToString(", ")}\n")
                    }
                }
                return sb.toString().trim()
            }

            override suspend fun diffRequests(filterA: String, filterB: String): String {
                val all = com.akay.feature.browser.devconsole.NetworkInterceptor.requests.value
                val a = all.lastOrNull { it.url.contains(filterA, ignoreCase = true) }
                    ?: return "No captured request matched \"$filterA\"."
                val b = all.lastOrNull { it.url.contains(filterB, ignoreCase = true) && it !== a }
                    ?: return "No second captured request matched \"$filterB\" (distinct from the first match)."

                fun parseQuery(url: String): Map<String, String> =
                    runCatching { java.net.URI(url) }.getOrNull()?.query
                        ?.split("&")?.mapNotNull { p ->
                            val i = p.indexOf('=')
                            if (i < 0) null else java.net.URLDecoder.decode(p.substring(0, i), "UTF-8") to java.net.URLDecoder.decode(p.substring(i + 1), "UTF-8")
                        }?.toMap() ?: emptyMap()

                val qa = parseQuery(a.url); val qb = parseQuery(b.url)
                val queryDiffKeys = (qa.keys + qb.keys).filter { qa[it] != qb[it] }

                val ha = a.requestHeaders; val hb = b.requestHeaders
                val headerDiffKeys = (ha.keys + hb.keys).filter { ha[it] != hb[it] }

                val sb = StringBuilder("Comparing:\nA: ${a.method} ${a.url}\nB: ${b.method} ${b.url}\n\n")
                if (queryDiffKeys.isEmpty()) sb.append("Query params: identical.\n")
                else {
                    sb.append("Query params that differ:\n")
                    queryDiffKeys.forEach { k -> sb.append("  - $k: \"${qa[k] ?: "(absent)"}\" \u2192 \"${qb[k] ?: "(absent)"}\"\n") }
                }
                if (headerDiffKeys.isNotEmpty()) {
                    sb.append("Headers that differ:\n")
                    headerDiffKeys.forEach { k -> sb.append("  - $k: \"${ha[k] ?: "(absent)"}\" \u2192 \"${hb[k] ?: "(absent)"}\"\n") }
                }
                if (a.requestBody != b.requestBody && (a.requestBody.isNotBlank() || b.requestBody.isNotBlank())) {
                    sb.append("Request body differs (A: ${a.requestBody.take(150)} | B: ${b.requestBody.take(150)})\n")
                }
                return sb.toString().trim()
            }

            override suspend fun inferSchema(urlFilter: String): String {
                val all = com.akay.feature.browser.devconsole.NetworkInterceptor.requests.value
                val match = all.lastOrNull { it.url.contains(urlFilter, ignoreCase = true) && it.responseBody.isNotBlank() }
                    ?: return "No captured response body found matching \"$urlFilter\"."
                val parsed = runCatching { org.json.JSONTokener(match.responseBody).nextValue() }.getOrNull()
                    ?: return "Response body for this request isn't valid JSON, can't infer a schema."

                fun typeOf(v: Any?): String = when (v) {
                    null, org.json.JSONObject.NULL -> "String?"
                    is String -> "String"
                    is Boolean -> "Boolean"
                    is Int, is Long -> "Long"
                    is Double, is Float -> "Double"
                    is org.json.JSONArray -> {
                        val first = if (v.length() > 0) v.get(0) else null
                        "List<${typeOf(first)}>"
                    }
                    is org.json.JSONObject -> "Object"
                    else -> "Any"
                }

                fun renderObject(obj: org.json.JSONObject, name: String, sb: StringBuilder, seen: MutableSet<String>) {
                    if (!seen.add(name)) return
                    val fields = obj.keys().asSequence().map { k -> k to obj.get(k) }.toList()
                    sb.append("data class $name(\n")
                    fields.forEach { (k, v) ->
                        val t = typeOf(v)
                        sb.append("    val $k: $t,\n")
                    }
                    sb.append(")\n")
                    fields.forEach { (k, v) ->
                        if (v is org.json.JSONObject) renderObject(v, k.replaceFirstChar { it.uppercase() }, sb, seen)
                        if (v is org.json.JSONArray && v.length() > 0 && v.get(0) is org.json.JSONObject) {
                            renderObject(v.getJSONObject(0), k.replaceFirstChar { it.uppercase() }.removeSuffix("s"), sb, seen)
                        }
                    }
                }

                val sb = StringBuilder()
                when (parsed) {
                    is org.json.JSONObject -> renderObject(parsed, "Response", sb, mutableSetOf())
                    is org.json.JSONArray -> {
                        if (parsed.length() > 0 && parsed.get(0) is org.json.JSONObject) {
                            renderObject(parsed.getJSONObject(0), "ResponseItem", sb, mutableSetOf())
                            sb.insert(0, "// top-level response is a List<ResponseItem>\n")
                        } else sb.append("// top-level response is a List<${typeOf(if (parsed.length() > 0) parsed.get(0) else null)}>\n")
                    }
                    else -> sb.append("// top-level response is a single ${typeOf(parsed)}\n")
                }
                return sb.toString().trim()
            }

            override suspend fun getGraphQlQueries(filter: String?): List<String> {
                val all = com.akay.feature.browser.devconsole.NetworkInterceptor.requests.value
                val gql = all.filter { it.isGraphQl }
                val matched = if (filter.isNullOrBlank()) gql else gql.filter { it.url.contains(filter, ignoreCase = true) }
                return matched.takeLast(15).map { req ->
                    val body = runCatching { org.json.JSONObject(req.requestBody) }.getOrNull()
                    val opName = body?.optString("operationName")?.ifBlank { null }
                    val query = body?.optString("query")?.trim()
                    val variables = body?.optJSONObject("variables")?.toString()
                    buildString {
                        append("POST ${req.url}")
                        if (opName != null) append(" \u2192 operation: $opName")
                        append("\n")
                        if (query != null) append("query:\n${query.take(1200)}\n")
                        if (!variables.isNullOrBlank() && variables != "{}") append("variables: $variables\n")
                    }.trim()
                }
            }

            override suspend fun exportHar(): String {
                val all = com.akay.feature.browser.devconsole.NetworkInterceptor.requests.value
                if (all.isEmpty()) return "No requests captured yet - nothing to export."
                return runCatching {
                    val har = com.akay.feature.browser.devconsole.NetworkInterceptor.toHar()
                    val filename = "axbrowser_${System.currentTimeMillis()}.har"
                    val result = viewModel.axStorage.writeText(
                        com.akay.core.data.storage.AxStorageCategory.AGENT, filename, har, "application/json"
                    )
                    "Exported ${all.size} requests to ${result.displayPath} \u2014 pull it via adb or share it, then open in Chrome DevTools / Postman / Insomnia (File > Import)."
                }.getOrElse { "Failed to export HAR: ${it.message}" }
            }

            override suspend fun exportPostman(): String {
                val all = com.akay.feature.browser.devconsole.NetworkInterceptor.requests.value.filter { it.isApiLike }
                if (all.isEmpty()) return "No API-like requests captured yet - nothing to export."
                return runCatching {
                    val items = org.json.JSONArray()
                    all.forEach { req ->
                        val urlObj = runCatching {
                            val u = java.net.URI(req.url)
                            org.json.JSONObject().apply {
                                put("raw", req.url)
                                put("protocol", u.scheme ?: "https")
                                put("host", org.json.JSONArray((u.host ?: "").split(".")))
                                put("path", org.json.JSONArray((u.path ?: "").trim('/').split("/").filter { it.isNotBlank() }))
                            }
                        }.getOrDefault(org.json.JSONObject().apply { put("raw", req.url) })
                        val headers = org.json.JSONArray().apply {
                            req.requestHeaders.forEach { (k, v) -> put(org.json.JSONObject().apply { put("key", k); put("value", v) }) }
                        }
                        val request = org.json.JSONObject().apply {
                            put("method", req.method)
                            put("header", headers)
                            put("url", urlObj)
                            if (req.requestBody.isNotBlank()) {
                                put("body", org.json.JSONObject().apply {
                                    put("mode", "raw")
                                    put("raw", req.requestBody)
                                    put("options", org.json.JSONObject().apply {
                                        put("raw", org.json.JSONObject().apply { put("language", "json") })
                                    })
                                })
                            }
                        }
                        items.put(org.json.JSONObject().apply {
                            put("name", "${req.method} ${req.url.substringAfter("://").take(60)}")
                            put("request", request)
                        })
                    }
                    val collection = org.json.JSONObject().apply {
                        put("info", org.json.JSONObject().apply {
                            put("name", "AxBrowser capture ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date())}")
                            put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json")
                        })
                        put("item", items)
                    }
                    val filename = "axbrowser_${System.currentTimeMillis()}.postman_collection.json"
                    val result = viewModel.axStorage.writeText(
                        com.akay.core.data.storage.AxStorageCategory.AGENT, filename, collection.toString(2), "application/json"
                    )
                    "Exported ${all.size} requests to ${result.displayPath} \u2014 open Postman/Insomnia and File > Import that file."
                }.getOrElse { "Failed to export Postman collection: ${it.message}" }
            }

            override suspend fun getCookies(): String {
                val url = webView?.url ?: viewModel.uiState.value.displayUrl
                if (url.isBlank()) return "No page loaded."
                val raw = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                if (raw.isNullOrBlank()) return "No cookies set for this page's domain."
                val pairs = raw.split(";").map { it.trim() }.filter { it.contains("=") }
                return "Cookies for $url:\n" + pairs.joinToString("\n") { p ->
                    val (k, v) = p.split("=", limit = 2)
                    "- $k = $v"
                } + "\n\n# NOTE: like a captured curl, these are tied to your own logged-in session - don't share them."
            }

            override suspend fun findRateLimits(): String {
                val all = com.akay.feature.browser.devconsole.NetworkInterceptor.requests.value.filter { it.isApiLike }
                val limitHeaderNames = setOf(
                    "x-ratelimit-limit", "x-ratelimit-remaining", "x-ratelimit-reset",
                    "ratelimit-limit", "ratelimit-remaining", "ratelimit-reset", "retry-after"
                )
                val withLimits = all.filter { req -> req.responseHeaders.keys.any { it.lowercase() in limitHeaderNames } }
                if (withLimits.isEmpty()) return "No rate-limit headers seen in captured responses yet (site may not expose them, or none have been hit)."
                return withLimits.takeLast(15).joinToString("\n\n") { req ->
                    val relevant = req.responseHeaders.filterKeys { it.lowercase() in limitHeaderNames }
                    "${req.method} ${req.url}\n" + relevant.entries.joinToString("\n") { "  ${it.key}: ${it.value}" }
                }
            }

            override suspend fun listEndpoints(): String {
                val all = com.akay.feature.browser.devconsole.NetworkInterceptor.requests.value.filter { it.isApiLike }
                if (all.isEmpty()) return "No API-like requests captured yet."
                data class Key(val method: String, val path: String)
                fun pathOf(url: String) = runCatching { java.net.URI(url).let { "${it.host}${it.path}" } }.getOrDefault(url)
                val grouped = all.groupBy { Key(it.method.uppercase(), pathOf(it.url)) }
                val wasmSeen = all.any { it.url.lowercase().endsWith(".wasm") || (it.mimeType ?: "").contains("wasm") }
                val lines = grouped.entries.sortedByDescending { it.value.size }.joinToString("\n") { (key, reqs) ->
                    "- ${key.method} ${key.path}  (${reqs.size} call${if (reqs.size == 1) "" else "s"}, e.g. ${reqs.last().url})"
                }
                val wasmNote = if (wasmSeen) {
                    "\n\n# NOTE: this page loads WebAssembly. If the real logic (signing, hashing, obfuscation) lives in " +
                        "that .wasm module, it won't be visible from network traffic alone - reverse-engineering it needs " +
                        "actual wasm disassembly, which is outside what this agent can do from the browser."
                } else ""
                return "Distinct endpoints this session:\n$lines$wasmNote"
            }

            override suspend fun detectPagination(): String {
                val all = com.akay.feature.browser.devconsole.NetworkInterceptor.requests.value.filter { it.isApiLike }
                fun pathOf(url: String) = runCatching { java.net.URI(url).let { "${it.host}${it.path}" } }.getOrDefault(url)
                val groups = all.groupBy { pathOf(it.url) }.filterValues { it.size >= 3 }
                if (groups.isEmpty()) return "No endpoint has been called 3+ times yet - browse more (e.g. scroll/next page a couple of times) so there's enough to compare."
                val findings = groups.entries.mapNotNull { (path, reqs) ->
                    fun queryParams(url: String) = runCatching {
                        (java.net.URI(url).query ?: "").split("&").filter { it.contains("=") }
                            .associate { it.substringBefore("=") to it.substringAfter("=") }
                    }.getOrDefault(emptyMap())
                    val paramSets = reqs.map { queryParams(it.url) }
                    val allKeys = paramSets.flatMap { it.keys }.toSet()
                    val changingKeys = allKeys.filter { key -> paramSets.map { it[key] }.distinct().size > 1 }
                    if (changingKeys.isEmpty()) return@mapNotNull null
                    val examples = changingKeys.associateWith { key -> paramSets.map { it[key] } }
                    "$path \u2014 varies by: " + examples.entries.joinToString(", ") { (k, vs) -> "$k (${vs.joinToString(" \u2192 ")})" }
                }
                return if (findings.isEmpty()) "Called endpoints 3+ times but no query param changed between calls - pagination (if any) may be driven by request body or headers instead; try diff_requests on two specific calls."
                else "Likely pagination/cursor params found:\n" + findings.joinToString("\n")
            }

            override suspend fun clickElement(selector: String): Boolean {
                val wv = webView ?: return false
                val clicked = unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.clickSelector(selector))) == "true"
                if (clicked) waitForLoad()
                return clicked
            }

            override suspend fun typeIntoElement(selector: String, text: String): Boolean {
                val wv = webView ?: return false
                return unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.typeIntoSelector(selector, text))) == "true"
            }

            override suspend fun scrollTo(selector: String?, pixels: Int?): Boolean {
                val wv = webView ?: return false
                kotlinx.coroutines.delay(150)
                return unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.scrollTo(selector, pixels))) == "true"
            }

            override suspend fun waitForElement(selector: String): Boolean {
                val wv = webView ?: return false
                repeat(10) {
                    if (unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.elementExists(selector))) == "true") return true
                    kotlinx.coroutines.delay(500)
                }
                return false
            }

            override suspend fun takeScreenshot(): String {
                val wv = webView ?: return "No page is currently loaded."
                if (wv.width <= 0 || wv.height <= 0) return "Page has no visible size yet, try again after it finishes loading."
                return runCatching {
                    val bitmap = android.graphics.Bitmap.createBitmap(wv.width, wv.height, android.graphics.Bitmap.Config.ARGB_8888)
                    wv.draw(android.graphics.Canvas(bitmap))
                    val out = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    bitmap.recycle()
                    val filename = "shot_${System.currentTimeMillis()}.png"
                    val result = viewModel.axStorage.writeFile(
                        com.akay.core.data.storage.AxStorageCategory.SCREENSHOTS, filename, out.toByteArray(), "image/png"
                    )
                    "Saved screenshot (${wv.width}x${wv.height}) to ${result.displayPath}"
                }.getOrElse { "Failed to capture screenshot: ${it.message}" }
            }

            private fun domainOf(url: String) = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

            override suspend fun switchTab(filter: String): Boolean {
                val match = viewModel.uiState.value.tabs.firstOrNull {
                    it.title.contains(filter, ignoreCase = true) || it.url.contains(filter, ignoreCase = true)
                } ?: return false
                viewModel.setActiveTab(match)
                waitForLoad()
                return true
            }

            override suspend fun saveRequest(label: String, curl: String): String {
                val domain = domainOf(webView?.url ?: viewModel.uiState.value.displayUrl)
                viewModel.saveRequest(label, curl, domain)
                return "Saved as \"$label\" (domain: $domain). Recall it later with get_saved_request."
            }

            override suspend fun listSavedRequests(): List<String> = viewModel.listSavedRequests().map { r ->
                "${r.label} (${r.domain}, saved ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(r.createdAt))})"
            }

            override suspend fun getSavedRequest(label: String): String? = viewModel.getSavedRequest(label)?.curl

            override suspend fun rememberSiteNote(note: String): String {
                val domain = domainOf(webView?.url ?: viewModel.uiState.value.displayUrl)
                viewModel.addSiteNote(domain, note)
                return "Noted for $domain - this will be recalled automatically next time you're on this site."
            }

            override suspend fun recallSiteNotes(domain: String?): List<String> {
                val d = domain ?: domainOf(webView?.url ?: viewModel.uiState.value.displayUrl)
                return viewModel.getSiteNotes(d).map { it.note }
            }

            override suspend fun watchPage(label: String, url: String, intervalMinutes: Int): String {
                val clamped = intervalMinutes.coerceAtLeast(15)
                viewModel.createWatch(label, url, clamped)
                com.akay.core.data.watch.PageWatchWorker.schedule(context, label, clamped)
                val note = if (clamped != intervalMinutes) " (interval raised to $clamped min - Android's minimum for background checks)" else ""
                return "Watching \"$url\" as \"$label\", checking every $clamped min$note. You'll get a notification if its content changes."
            }

            override suspend fun listWatches(): List<String> = viewModel.listWatches().map { w ->
                val lastChecked = w.lastCheckedAt?.let { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(it)) } ?: "not yet"
                "${w.label} \u2192 ${w.url} (every ${w.intervalMinutes} min, last checked: $lastChecked)"
            }

            override suspend fun cancelWatch(label: String): Boolean {
                val existed = viewModel.listWatches().any { it.label == label }
                viewModel.cancelWatch(label)
                com.akay.core.data.watch.PageWatchWorker.cancel(context, label)
                return existed
            }

            override suspend fun exportOpenApi(): String {
                val all = com.akay.feature.browser.devconsole.NetworkInterceptor.requests.value.filter { it.isApiLike }
                if (all.isEmpty()) return "No API-like requests captured yet - nothing to export."
                return runCatching {
                    // --- shared JSON -> OpenAPI schema inference, used for both request and response bodies ---
                    fun jsonToSchema(v: Any?): org.json.JSONObject = org.json.JSONObject().apply {
                        when (v) {
                            null, org.json.JSONObject.NULL -> put("type", "string").also { put("nullable", true) }
                            is String -> put("type", "string")
                            is Boolean -> put("type", "boolean")
                            is Int, is Long -> put("type", "integer")
                            is Double, is Float -> put("type", "number")
                            is org.json.JSONArray -> {
                                put("type", "array")
                                if (v.length() > 0) put("items", jsonToSchema(v.get(0))) else put("items", org.json.JSONObject())
                            }
                            is org.json.JSONObject -> {
                                put("type", "object")
                                val props = org.json.JSONObject()
                                v.keys().forEach { k -> props.put(k, jsonToSchema(v.get(k))) }
                                put("properties", props)
                            }
                            else -> put("type", "string")
                        }
                    }
                    fun bodySchema(bodyText: String): org.json.JSONObject? {
                        if (bodyText.isBlank()) return null
                        val parsed = runCatching { org.json.JSONTokener(bodyText).nextValue() }.getOrNull() ?: return null
                        return jsonToSchema(parsed)
                    }

                    data class Key(val method: String, val path: String)
                    val grouped = all.groupBy { Key(it.method.uppercase(), domainOf(it.url) + runCatching { java.net.URI(it.url).path }.getOrDefault("")) }
                    val paths = org.json.JSONObject()
                    val servers = org.json.JSONArray()
                    all.map { "${runCatching { java.net.URI(it.url).scheme }.getOrNull() ?: "https"}://${domainOf(it.url)}" }
                        .distinct().forEach { servers.put(org.json.JSONObject().apply { put("url", it) }) }

                    var usesBearer = false
                    var usesCookie = false

                    grouped.entries.groupBy { it.key.path }.forEach { (path, entries) ->
                        val pathItem = org.json.JSONObject()
                        entries.forEach { (key, reqs) ->
                            // Merge query params across EVERY captured call to this endpoint, not just the
                            // most recent one - a param only some calls used is real signal (it's optional
                            // and does something), not noise to drop.
                            val paramOccurrences = reqs.map { req ->
                                runCatching { java.net.URI(req.url).query }.getOrNull()
                                    ?.split("&")?.filter { it.contains("=") }
                                    ?.map { it.substringBefore("=") }?.toSet() ?: emptySet()
                            }
                            val allParamNames = paramOccurrences.flatten().toSet()
                            val params = org.json.JSONArray()
                            allParamNames.sorted().forEach { name ->
                                val presentInAll = paramOccurrences.all { name in it }
                                params.put(org.json.JSONObject().apply {
                                    put("name", name)
                                    put("in", "query")
                                    put("required", presentInAll)
                                    put("schema", org.json.JSONObject().apply { put("type", "string") })
                                })
                            }

                            val example = reqs.last()
                            val exampleWithBody = reqs.lastOrNull { it.requestBody.isNotBlank() } ?: example
                            val exampleWithResponse = reqs.lastOrNull { it.responseBody.isNotBlank() } ?: example

                            val hasAuth = reqs.any { it.authHeaderNames.isNotEmpty() }
                            val bearerHere = reqs.any { r -> r.requestHeaders.entries.any { it.key.equals("authorization", true) && it.value.startsWith("Bearer", true) } }
                            val cookieHere = reqs.any { it.authHeaderNames.any { h -> h.equals("cookie", true) } }
                            if (bearerHere) usesBearer = true
                            if (cookieHere) usesCookie = true

                            val op = org.json.JSONObject().apply {
                                put("summary", "Captured from AxBrowser session (${reqs.size} call${if (reqs.size == 1) "" else "s"} observed)")
                                put("parameters", params)
                                if (exampleWithBody.requestBody.isNotBlank()) {
                                    val schema = bodySchema(exampleWithBody.requestBody)
                                    put("requestBody", org.json.JSONObject().apply {
                                        put("content", org.json.JSONObject().apply {
                                            put("application/json", org.json.JSONObject().apply {
                                                if (schema != null) put("schema", schema)
                                                put("example", runCatching { org.json.JSONObject(exampleWithBody.requestBody) }.getOrNull()
                                                    ?: runCatching { org.json.JSONArray(exampleWithBody.requestBody) }.getOrNull() ?: exampleWithBody.requestBody)
                                            })
                                        })
                                    })
                                }
                                put("responses", org.json.JSONObject().apply {
                                    put((example.responseStatus ?: 200).toString(), org.json.JSONObject().apply {
                                        put("description", "Captured response")
                                        if (exampleWithResponse.responseBody.isNotBlank()) {
                                            val respSchema = bodySchema(exampleWithResponse.responseBody)
                                            put("content", org.json.JSONObject().apply {
                                                put("application/json", org.json.JSONObject().apply {
                                                    if (respSchema != null) put("schema", respSchema)
                                                })
                                            })
                                        }
                                    })
                                })
                                if (hasAuth) {
                                    val schemes = org.json.JSONArray()
                                    if (bearerHere) schemes.put(org.json.JSONObject().apply { put("bearerAuth", org.json.JSONArray()) })
                                    if (cookieHere) schemes.put(org.json.JSONObject().apply { put("cookieAuth", org.json.JSONArray()) })
                                    if (schemes.length() > 0) put("security", schemes)
                                }
                            }
                            pathItem.put(key.method.lowercase(), op)
                        }
                        paths.put(path.ifBlank { "/" }, pathItem)
                    }

                    val securitySchemes = org.json.JSONObject()
                    if (usesBearer) securitySchemes.put("bearerAuth", org.json.JSONObject().apply {
                        put("type", "http"); put("scheme", "bearer")
                    })
                    if (usesCookie) securitySchemes.put("cookieAuth", org.json.JSONObject().apply {
                        put("type", "apiKey"); put("in", "cookie"); put("name", "session")
                    })

                    val doc = org.json.JSONObject().apply {
                        put("openapi", "3.0.3")
                        put("info", org.json.JSONObject().apply {
                            put("title", "AxBrowser capture ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date())}")
                            put("version", "1.0.0")
                        })
                        put("servers", servers)
                        put("paths", paths)
                        if (securitySchemes.length() > 0) {
                            put("components", org.json.JSONObject().apply { put("securitySchemes", securitySchemes) })
                        }
                    }
                    val filename = "axbrowser_${System.currentTimeMillis()}.openapi.json"
                    val result = viewModel.axStorage.writeText(
                        com.akay.core.data.storage.AxStorageCategory.AGENT, filename, doc.toString(2), "application/json"
                    )
                    "Exported ${grouped.values.sumOf { it.size }} requests across ${paths.length()} paths to ${result.displayPath} \u2014 " +
                        "params/schemas are merged across every call observed to each endpoint (not just one example), " +
                        "but this is still inferred from observed traffic, not a real spec from the site \u2014 treat field " +
                        "types and which params are truly required as informed guesses to verify."
                }.getOrElse { "Failed to export OpenAPI doc: ${it.message}" }
            }

            override suspend fun beautifyJs(urlOrCode: String): String {
                val looksLikeUrl = urlOrCode.trimStart().startsWith("http://") || urlOrCode.trimStart().startsWith("https://")
                val code = if (looksLikeUrl) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            java.net.URL(urlOrCode.trim()).openConnection().apply {
                                connectTimeout = 8000; readTimeout = 8000
                                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AxBrowser")
                            }.getInputStream().bufferedReader().use { it.readText() }
                        }.getOrElse { return@withContext null }
                    } ?: return "Couldn't fetch $urlOrCode - it may require auth/cookies this plain request doesn't carry, or be blocked. " +
                        "If it's a request AxBrowser already captured, try get_response_body instead."
                } else {
                    urlOrCode
                }
                if (code.isBlank()) return "Nothing to beautify - empty input."
                if (code.length > 200_000) {
                    return "That's ${code.length} chars, too large to usefully reformat inline (truncating would just produce " +
                        "broken JS). Try narrowing to a specific function via run_js instead, e.g. by extracting it as a string first."
                }
                return runCatching { beautifyJavaScript(code) }.getOrElse { "Failed to beautify: ${it.message}" } +
                    "\n\n# NOTE: this is bracket/statement-based reformatting only (adds indentation and line breaks), not a real " +
                    "JS parser or a deobfuscator - it won't undo variable renaming, string encoding, or control-flow flattening, " +
                    "and regex literals containing / or quote characters can occasionally confuse it. Good for a first readable " +
                    "look at minified code, not a substitute for careful manual analysis of genuinely obfuscated code."
            }

            private fun categoryFor(name: String?): com.akay.core.data.storage.AxStorageCategory =
                when (name?.lowercase()?.trim()) {
                    "screenshots", "screenshot" -> com.akay.core.data.storage.AxStorageCategory.SCREENSHOTS
                    "downloads", "download" -> com.akay.core.data.storage.AxStorageCategory.DOWNLOADS
                    "other" -> com.akay.core.data.storage.AxStorageCategory.OTHER
                    else -> com.akay.core.data.storage.AxStorageCategory.AGENT
                }

            override suspend fun listSavedFiles(category: String?): List<String> =
                viewModel.axStorage.listFiles(categoryFor(category))

            override suspend fun readSavedFile(category: String?, filename: String): String? =
                viewModel.axStorage.readText(categoryFor(category), filename)

            override suspend fun writeSavedFile(category: String?, filename: String, content: String): String {
                val safeName = filename.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file_${System.currentTimeMillis()}.txt" }
                val result = viewModel.axStorage.writeText(categoryFor(category), safeName, content)
                return "Saved to ${result.displayPath}" + if (!result.savedToSharedStorage) {
                    " (app-private storage - set a save location in Settings > Storage to make files like this visible outside the app)"
                } else ""
            }
            // ---------- Playwright-MCP-shaped tools (v7) ----------

            private val pageSnapshot = com.akay.feature.browser.agent.PageSnapshot()

            override suspend fun browserSnapshot(maxNodes: Int): String {
                val wv = webView ?: return "No page is currently loaded."
                val text = pageSnapshot.take({ script -> unwrapJsString(wv.evalJs(script)) }, maxNodes)
                if (pageSnapshot.refCount() == 0) return "(empty snapshot - the page may have no interactive elements)\n" + text
                return text
            }

            override suspend fun browserClick(ref: String): String {
                val wv = webView ?: return "No page is currently loaded."
                val path = pageSnapshot.pathFor(ref)
                    ?: return "Unknown or stale ref \"$ref\" - take a fresh browser_snapshot first."
                val result = unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.clickByPath(path)))
                if (result == "notfound") {
                    pageSnapshot.invalidate()
                    return "Element for \"$ref\" no longer exists - the DOM changed. Take a fresh browser_snapshot."
                }
                waitForLoad()
                val nowUrl = wv.url ?: ""
                val changed = nowUrl != pageSnapshot.lastUrl
                return if (changed) "Clicked $ref; page navigated to $nowUrl. Take a fresh browser_snapshot."
                else "Clicked $ref. Take browser_snapshot if you need the new state."
            }

            override suspend fun browserType(ref: String, text: String, submit: Boolean): String {
                val wv = webView ?: return "No page is currently loaded."
                val path = pageSnapshot.pathFor(ref)
                    ?: return "Unknown or stale ref \"$ref\" - take a fresh browser_snapshot first."
                val result = unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.typeByPath(path, text)))
                if (result == "notfound") return "Element for \"$ref\" no longer exists. Take a fresh browser_snapshot."
                if (submit) {
                    unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.pressKeyByPath(path, "Enter")))
                    waitForLoad()
                }
                return "Typed ${text.length} chars into $ref" + if (submit) " and pressed Enter." else "."
            }

            override suspend fun browserFillForm(fields: List<Triple<String, String, String>>): String {
                if (fields.isEmpty()) return "No fields provided."
                val results = fields.map { (ref, value, submitFlag) ->
                    browserType(ref, value, submitFlag == "true")
                }
                return "Filled ${fields.size} fields:\n" + results.joinToString("\n") { "- $it" }
            }

            override suspend fun browserSelectOption(ref: String, values: List<String>): String {
                val wv = webView ?: return "No page is currently loaded."
                val path = pageSnapshot.pathFor(ref)
                    ?: return "Unknown or stale ref \"$ref\" - take a fresh browser_snapshot first."
                val result = unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.selectOptionByPath(path, values)))
                return if (result == "ok") "Selected ${values.joinToString()} on $ref." else "Element for \"$ref\" no longer exists."
            }

            override suspend fun browserHover(ref: String): String {
                val wv = webView ?: return "No page is currently loaded."
                val path = pageSnapshot.pathFor(ref) ?: return "Unknown or stale ref \"$ref\"."
                val result = unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.hoverByPath(path)))
                return if (result == "ok") "Hovered $ref." else "Element for \"$ref\" no longer exists."
            }

            override suspend fun browserPressKey(ref: String?, key: String): String {
                val wv = webView ?: return "No page is currently loaded."
                val path = ref?.let { pageSnapshot.pathFor(it) } ?: ""
                val result = unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.pressKeyByPath(path, key)))
                if (key == "Enter") waitForLoad()
                return if (result == "ok") "Pressed $key." else "Could not press key (element not found or no active element)."
            }

            override suspend fun browserWaitFor(text: String?, textGone: String?, timeMs: Int): String {
                val wv = webView ?: return "No page is currently loaded."
                val deadline = System.currentTimeMillis() + timeMs.coerceIn(500, 15000)
                if (!text.isNullOrBlank()) {
                    while (System.currentTimeMillis() < deadline) {
                        val body = unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.GET_PAGE_TEXT))
                        if (body.contains(text, ignoreCase = true)) return "Text appeared: \"$text\""
                        kotlinx.coroutines.delay(300)
                    }
                    return "Timeout waiting for text \"$text\"."
                }
                if (!textGone.isNullOrBlank()) {
                    while (System.currentTimeMillis() < deadline) {
                        val body = unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.GET_PAGE_TEXT))
                        if (!body.contains(textGone, ignoreCase = true)) return "Text gone: \"$textGone\""
                        kotlinx.coroutines.delay(300)
                    }
                    return "Timeout waiting for \"$textGone\" to disappear."
                }
                kotlinx.coroutines.delay(timeMs.coerceIn(0, 15000).toLong())
                return "Waited ${timeMs}ms."
            }

            override suspend fun browserHandleDialog(accept: Boolean, promptText: String?): String =
                com.akay.feature.browser.webview.DialogBridge.respond(accept, promptText)

            override suspend fun browserTabs(action: String, index: Int?): String = when (action.lowercase()) {
                "list" -> {
                    val tabs = listTabs()
                    if (tabs.isEmpty()) "No open tabs." else tabs.joinToString("\n") { "- $it" }
                }
                "new" -> {
                    val url = viewModel.uiState.value.displayUrl
                    viewModel.createNewTab(url, false)
                    "Opened a new tab with $url."
                }
                "close" -> {
                    val active = viewModel.uiState.value.activeTab
                    if (active == null) "No active tab to close."
                    else {
                        viewModel.closeTab(active.id)
                        "Closed the current tab."
                    }
                }
                "select" -> {
                    if (index == null) "Provide an index (from browser_tabs list)."
                    else {
                        val target = viewModel.uiState.value.tabs.getOrNull(index)
                        if (target == null) "No tab at index $index."
                        else {
                            viewModel.setActiveTab(target)
                            "Switched to tab $index: ${target.title.ifBlank { target.url }}."
                        }
                    }
                }
                else -> "Unknown action \"$action\" (list | new | close | select)."
            }

            override suspend fun browserConsoleMessages(): String =
                com.akay.feature.browser.webview.ConsoleRing.recent()

            // ---------- Pentest tools (v7): always available, no gating ----------

            override suspend fun httpSend(rawRequest: String, includeCookies: Boolean, followRedirects: Boolean): String {
                val sender = com.akay.feature.pentest.sender.HttpSender(com.akay.feature.pentest.sender.CookieJarBridge())
                val tx = com.akay.core.domain.model.HttpTransaction.fromRawRequest(rawRequest, sessionId = "agent", source = "manual")
                val result = sender.send(
                    tx,
                    com.akay.feature.pentest.sender.HttpSender.SendOptions(
                        includeBrowserCookies = includeCookies,
                        followRedirects = followRedirects
                    )
                )
                val final = result.final
                if (final == null) return "Send failed: ${result.error ?: "no response"}"
                val head = final.headerText.take(1200)
                val body = final.bodyText.take(3500)
                return "HTTP ${final.statusLine}\n\n" + head +
                    (if (body.isNotBlank()) "\n\n$body" else "\n\n(empty body)")
            }

            override suspend fun encodeDecode(transform: String, direction: String, input: String): String =
                com.akay.feature.pentest.codec.DecoderChain.transform(
                    transform,
                    if (direction.equals("encode", true)) com.akay.feature.pentest.codec.DecoderChain.Direction.ENCODE
                    else com.akay.feature.pentest.codec.DecoderChain.Direction.DECODE,
                    input
                )

            override suspend fun jwtDecode(token: String): String {
                val info = com.akay.feature.pentest.audit.JwtDecoder.decode(token)
                    ?: return "Not a JWT (expected header.payload.signature)."
                return buildString {
                    append("header:  " + (info.header?.toString() ?: "(unparseable)") + "\n")
                    append("payload: " + (info.payload?.toString() ?: "(unparseable)") + "\n")
                    if (info.signature != null) append("signature: present (not verified)\n")
                    if (info.problems.isNotEmpty()) append("\n" + info.problems.joinToString("\n") { "⚠ $it" })
                }
            }

            override suspend fun dumpStorage(): String {
                val wv = webView ?: return "No page is currently loaded."
                val storage = unwrapJsString(wv.evalJs(com.akay.feature.browser.agent.AgentJs.STORAGE_DUMP))
                val cookies = getCookies()
                return storage + "\n\ncookies:\n" + cookies
            }

            override suspend fun auditSecurityHeaders(): String {
                val host = runCatching { java.net.URI(currentUrl()).host }.getOrNull() ?: return "No page loaded."
                val txs = NetworkInterceptor.requests.value.filter { it.responseStatus != null }.map { req ->
                    com.akay.core.domain.model.HttpTransaction(
                        method = req.method, url = req.url,
                        headers = req.requestHeaders.map { it.key to it.value },
                        responseStatus = req.responseStatus,
                        responseHeaders = req.responseHeaders.map { it.key to it.value },
                        source = "capture"
                    )
                }.filter { runCatching { java.net.URI(it.url).host }.getOrNull() == host }
                if (txs.isEmpty()) return "No captured responses for $host yet."
                val audit = com.akay.feature.pentest.audit.SecurityAuditor.auditOrigin(host, txs)
                return buildString {
                    append("${audit.origin}: score ${audit.score}/100\n")
                    audit.findings.forEach { append("• [${it.severity.uppercase()}] ${it.check}: ${it.detail}\n") }
                }
            }

            override suspend fun extractJsEndpoints(maxEndpoints: Int): String {
                val pageUrl = currentUrl()
                if (pageUrl.isBlank()) return "No page loaded."
                val origin = runCatching { java.net.URI(pageUrl).host }.getOrNull() ?: return "No page loaded."
                val scripts = NetworkInterceptor.requests.value
                    .filter { it.url.endsWith(".js") || it.url.contains(".js?") }
                    .map { it.url }
                    .distinct()
                    .filter { runCatching { java.net.URI(it).host }.getOrNull() == origin }
                if (scripts.isEmpty()) return "No same-origin .js files captured yet."
                val endpoints = com.akay.feature.pentest.audit.EndpointExtractor.fetchAndExtract(scripts, origin)
                if (endpoints.isEmpty()) return "No endpoints found in ${scripts.size} scripts."
                return endpoints.take(maxEndpoints).joinToString("\n") {
                    (if (it.isApiLike) "[api] " else "") + it.value
                }
            }

            override suspend fun toggleIntercept(enable: Boolean): String {
                com.akay.feature.pentest.intercept.InterceptController.setEnabled(enable)
                return if (enable) "Intercept ON - fetch/XHR calls will be held for review."
                else "Intercept OFF - any held requests were auto-forwarded."
            }

            override suspend fun setMatchReplace(type: String, match: String, replace: String, isRegex: Boolean): String {
                val valid = listOf("REPLACE_REQ_HEADER", "REPLACE_REQ_BODY", "REPLACE_RESP_BODY")
                val t = valid.firstOrNull { it.equals(type, true) } ?: return "Unknown rule type \"$type\" ($valid)."
                com.akay.feature.pentest.intercept.InterceptController.addRule(t, match, replace, isRegex)
                return "Rule added: $t: ${match.take(40)}"
            }

            // ---------- Agent v8: plan, handoff, captcha ----------

            override suspend fun todoWrite(items: List<Pair<String, String>>): String =
                agentTodo.replace(items)

            override suspend fun todoRead(): String = agentTodo.render()

            override suspend fun captchaDetect(): String {
                val wv = webView ?: return "No page is currently loaded."
                val widgets = com.akay.feature.browser.captcha.CaptchaDetector.parseEnvelope(
                    unwrapJsString(wv.evalJs(com.akay.feature.browser.captcha.CaptchaDetector.DETECT_JS))
                )
                if (widgets.isEmpty()) return "No captcha detected on this page."
                return widgets.joinToString("\n") { w ->
                    val key = if (w.sitekey.isNotBlank()) " sitekey=" + w.sitekey else ""
                    val state = if (w.solved) " [already solved]" else ""
                    "- " + w.label + " (" + w.kind + ")" + key +
                        " tap=(" + w.tapX.toInt() + "," + w.tapY.toInt() + ") visible=" + w.visible + state
                }
            }

            override suspend fun captchaSolve(useSolver: Boolean, timeoutSec: Int): String {
                val wv = webView ?: return "No page is currently loaded."
                val widgets = com.akay.feature.browser.captcha.CaptchaDetector.parseEnvelope(
                    unwrapJsString(wv.evalJs(com.akay.feature.browser.captcha.CaptchaDetector.DETECT_JS))
                )
                if (widgets.isEmpty()) return "No captcha detected on this page - nothing to solve."
                val report = StringBuilder()
                for (w in widgets) {
                    if (w.solved) {
                        report.appendLine(w.label + ": already solved.")
                        continue
                    }
                    if (w.kind == "checkbox" && w.visible && viewModel.captchaAutoCheckbox.value) {
                        // Native touch, not a synthetic DOM click: the checkbox lives inside a
                        // cross-origin iframe, where page JS has no reach whatsoever.
                        tapAt(w.tapX, w.tapY)
                        kotlinx.coroutines.delay(2000)
                        val after = com.akay.feature.browser.captcha.CaptchaDetector.parseEnvelope(
                            unwrapJsString(wv.evalJs(com.akay.feature.browser.captcha.CaptchaDetector.DETECT_JS))
                        )
                        val same = after.firstOrNull { it.type == w.type }
                        if (same == null || same.solved) {
                            report.appendLine(w.label + ": tapped the checkbox -> cleared.")
                            continue
                        }
                        report.appendLine(w.label + ": tapped the checkbox -> still challenging.")
                    } else if (w.kind == "interstitial" && w.visible) {
                        tapAt(w.tapX, w.tapY)
                        kotlinx.coroutines.delay(4000)
                        report.appendLine(w.label + ": tapped the interstitial; if it is still showing it needs a human.")
                    } else if (w.kind != "checkbox") {
                        report.appendLine(
                            w.label + ": a " + w.kind + " captcha cannot be automated on-device - this one needs a human."
                        )
                    }
                    if (useSolver && w.tokenSolvable) {
                        val apiKey = viewModel.captchaSolverApiKey.value.orEmpty()
                        if (apiKey.isBlank()) {
                            report.appendLine(w.label + ": no solver API key configured, so no token could be requested.")
                        } else {
                            val base = viewModel.captchaSolverBaseUrl.value.ifBlank {
                                com.akay.feature.browser.captcha.CaptchaSolver.DEFAULT_BASE_URL
                            }
                            com.akay.feature.browser.captcha.CaptchaSolver(apiKey, base)
                                .solve(w, currentUrl(), timeoutSec)
                                .fold(
                                    onSuccess = { token ->
                                        unwrapJsString(
                                            wv.evalJs(com.akay.feature.browser.captcha.CaptchaSolver.tokenInjectionJs(w, token))
                                        )
                                        report.appendLine(w.label + ": token received and injected into the provider response field.")
                                    },
                                    onFailure = { report.appendLine(w.label + ": solve failed - " + it.message) }
                                )
                        }
                    }
                }
                return report.toString().trim()
            }

            override suspend fun tapAt(x: Float, y: Float): Boolean {
                val wv = webView ?: return false
                return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    runCatching {
                        // Captcha coordinates are viewport CSS pixels; a touch event wants view
                        // pixels, and the viewport maps 1:1 onto the view's own coordinate space
                        // (scrolling included), so the density conversion is the whole job.
                        val density = wv.resources.displayMetrics.density
                        val px = x * density
                        val py = y * density
                        val downTime = android.os.SystemClock.uptimeMillis()
                        val down = android.view.MotionEvent.obtain(
                            downTime, downTime, android.view.MotionEvent.ACTION_DOWN, px, py, 0
                        )
                        val up = android.view.MotionEvent.obtain(
                            downTime, downTime + 60, android.view.MotionEvent.ACTION_UP, px, py, 0
                        )
                        wv.dispatchTouchEvent(down)
                        wv.dispatchTouchEvent(up)
                        down.recycle()
                        up.recycle()
                        true
                    }.getOrDefault(false)
                }
            }

            override suspend fun sendToRepeater(urlFilter: String): String {
                val req = NetworkInterceptor.requests.value.lastOrNull { it.url.contains(urlFilter, true) }
                    ?: return "No captured request matching \"$urlFilter\"."
                val tx = com.akay.core.domain.model.HttpTransaction(
                    method = req.method, url = req.url,
                    headers = req.requestHeaders.map { it.key to it.value },
                    body = req.requestBody.takeIf { it.isNotBlank() }?.toByteArray(),
                    responseStatus = req.responseStatus,
                    responseHeaders = req.responseHeaders.map { it.key to it.value },
                    responseBody = req.responseBody.takeIf { it.isNotBlank() }?.toByteArray(),
                    source = "capture"
                )
                com.akay.feature.pentest.repeater.RepeaterBridge.offer(tx)
                return "Sent to Repeater: ${tx.method} ${tx.url.take(70)}"
            }
        }
    }

    val agentScope = rememberCoroutineScope()
    val agentController = remember {
        com.akay.feature.browser.agent.AgentChatController(agentScope, viewModel.openRouterClient, agentTools, agentTodo)
    }

    if (agentSheetVisible) {
        val aiApiKey by viewModel.aiApiKey.collectAsState()
        val aiModel by viewModel.aiModel.collectAsState()
        val aiBaseUrl by viewModel.aiBaseUrl.collectAsState()

        com.akay.feature.browser.agent.AgentSheet(
            controller = agentController,
            apiKey = aiApiKey,
            model = aiModel,
            baseUrl = aiBaseUrl,
            onMinimize = { agentSheetVisible = false },
            onOpenSettings = {
                agentSheetVisible = false
                onOpenSettings()
            }
        )
    } else if (agentController.hasSession) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomStart) {
            com.akay.feature.browser.agent.AgentMinimizedChip(
                isRunning = agentController.isRunning,
                onClick = { agentSheetVisible = true }
            )
        }
    }
}

@Composable
fun BrowserOverflowMenu(
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

/** Lightweight bracket/statement-based JS reformatter for [beautifyJs] - not a real parser, just
 *  indentation-aware re-flowing so minified code is at least readable. Correctly tracks string
 *  literals (single/double/template) and comments so it never re-flows text inside them; regex
 *  literals aren't specially handled (a `/` inside one can be momentarily mistaken for a comment
 *  start) since telling a regex literal from a division apart from a real parser is genuinely
 *  ambiguous - documented as a known limitation to the caller rather than silently wrong. */
private fun beautifyJavaScript(code: String): String {
    val sb = StringBuilder()
    var indent = 0
    var i = 0
    var inString: Char? = null
    var inLineComment = false
    var inBlockComment = false
    val indentUnit = "  "

    fun trimTrailingBlank() {
        while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\n')) sb.deleteCharAt(sb.length - 1)
    }
    fun newline() {
        trimTrailingBlank()
        sb.append('\n')
        repeat(indent) { sb.append(indentUnit) }
    }

    while (i < code.length) {
        val c = code[i]
        val next = if (i + 1 < code.length) code[i + 1] else '\u0000'

        if (inLineComment) {
            sb.append(c)
            if (c == '\n') inLineComment = false
            i++; continue
        }
        if (inBlockComment) {
            sb.append(c)
            if (c == '*' && next == '/') { sb.append(next); i += 2; inBlockComment = false; continue }
            i++; continue
        }
        if (inString != null) {
            sb.append(c)
            if (c == '\\' && i + 1 < code.length) { sb.append(next); i += 2; continue }
            if (c == inString) inString = null
            i++; continue
        }

        when {
            c == '/' && next == '/' -> { inLineComment = true; sb.append(c) }
            c == '/' && next == '*' -> { inBlockComment = true; sb.append(c) }
            c == '\'' || c == '"' || c == '`' -> { inString = c; sb.append(c) }
            c == '{' -> { sb.append(c); indent++; newline() }
            c == '}' -> {
                indent = maxOf(0, indent - 1)
                newline()
                sb.append(c)
                if (next != ';' && next != ',' && next != ')' && next != '\n') newline()
            }
            c == ';' -> { sb.append(c); if (next != '\n') newline() }
            else -> sb.append(c)
        }
        i++
    }
    return sb.toString().trim()
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
