package com.akay.feature.browser.devconsole

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akay.core.domain.model.HttpTransaction
import com.akay.feature.pentest.repeater.RepeaterBridge
import com.akay.feature.pentest.ui.AuditTab
import com.akay.feature.pentest.ui.DecoderTab
import com.akay.feature.pentest.ui.InterceptTab
import com.akay.feature.pentest.ui.IntruderTab
import com.akay.feature.pentest.ui.RepeaterTab

private enum class NetFilter(val label: String) {
    ALL("All"), XHR("XHR/Fetch"), JS("JS"), CSS("CSS"), IMG("Img"), MEDIA("Media"), DOC("Doc"), BLOCKED("Blocked")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevConsolePanel(
    isVisible: Boolean,
    currentPageUrl: String,
    currentPageHtml: String = "",
    onDismiss: () -> Unit
) {
    val requests by NetworkInterceptor.requests.collectAsState()
    val rules by NetworkInterceptor.rules.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedRequest by remember { mutableStateOf<NetworkRequest?>(null) }
    val tabs = listOf(
        "Network (${requests.size})",
        "Intercept",
        "Repeater",
        "Intruder",
        "Decoder",
        "Audit",
        "Rules (${rules.size})",
        "Elements",
        "Info"
    )
    // The pentest Audit tab speaks the shared HttpTransaction shape. Build it from what this
    // session actually captured so it reports real findings instead of sitting empty.
    val auditTransactions = remember(requests) { requests.map { it.toTransaction() } }

    if (isVisible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.BugReport, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Dev Tools", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (selectedTab == 0) {
                        IconButton(onClick = { NetworkInterceptor.clear() }) {
                            Icon(Icons.Default.DeleteSweep, "Clear")
                        }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                }

                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    edgePadding = 0.dp
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(selected = selectedTab == index, onClick = { selectedTab = index }) {
                            Text(title, modifier = Modifier.padding(12.dp), fontSize = 12.sp)
                        }
                    }
                }

                HorizontalDivider()

                when (selectedTab) {
                    0 -> NetworkTab(requests = requests, onSelectRequest = { selectedRequest = it })
                    1 -> InterceptTab()
                    2 -> RepeaterTab()
                    3 -> IntruderTab()
                    4 -> DecoderTab()
                    5 -> AuditTab(transactions = auditTransactions)
                    6 -> RulesTab(rules = rules)
                    7 -> ElementsTab(html = currentPageHtml)
                    8 -> InfoTab(url = currentPageUrl, requestCount = requests.size, ruleCount = rules.size)
                }
            }

            selectedRequest?.let { req ->
                RequestDetailSheet(
                    request = req,
                    onDismiss = { selectedRequest = null },
                    onSendToRepeater = { selectedTab = 2 }
                )
            }
        }
    }
}

private fun classify(req: NetworkRequest): NetFilter {
    if (req.isBlocked) return NetFilter.BLOCKED
    val u = req.url.lowercase()
    val ct = req.mimeType?.lowercase() ?: ""
    return when {
        req.isMedia -> NetFilter.MEDIA
        req.source == "xhr" || req.source == "fetch" || ct.contains("json") -> NetFilter.XHR
        u.contains(".js") || ct.contains("javascript") -> NetFilter.JS
        u.contains(".css") || ct.contains("css") -> NetFilter.CSS
        ct.startsWith("image/") || Regex("\\.(png|jpe?g|gif|webp|svg|ico)").containsMatchIn(u) -> NetFilter.IMG
        ct.contains("html") -> NetFilter.DOC
        else -> NetFilter.ALL
    }
}

@Composable
fun NetworkTab(requests: List<NetworkRequest>, onSelectRequest: (NetworkRequest) -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(NetFilter.ALL) }

    val filtered = requests.asReversed().filter { req ->
        (query.isBlank() || req.url.contains(query, ignoreCase = true)) &&
            (filter == NetFilter.ALL || classify(req) == filter ||
                (filter == NetFilter.BLOCKED && req.isBlocked))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
            placeholder = { Text("Filter by URL", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NetFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label, fontSize = 11.sp) }
                )
            }
        }
        HorizontalDivider()
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No requests captured yet.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { req ->
                    NetworkRequestRow(request = req, onClick = { onSelectRequest(req) })
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
fun NetworkRequestRow(request: NetworkRequest, onClick: () -> Unit) {
    val statusColor = when {
        request.isBlocked -> Color(0xFFFF5252)
        request.responseStatus == null -> Color(0xFF9E9E9E)
        request.responseStatus in 200..299 -> Color(0xFF4CAF50)
        request.responseStatus in 300..399 -> Color(0xFF2196F3)
        request.responseStatus in 400..499 -> Color(0xFFFF9800)
        else -> Color(0xFFFF5252)
    }

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).background(statusColor, shape = RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = request.url.removePrefix("https://").removePrefix("http://").let {
                    if (it.length > 60) it.take(57) + "..." else it
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(request.method, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                if (request.source == "xhr" || request.source == "fetch") {
                    Text(request.source.uppercase(), fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)
                }
                if (request.mimeType != null) {
                    Text(request.mimeType.take(24), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (request.durationMs > 0) {
                    Text("${request.durationMs}ms", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (request.isBlocked) {
                    Text("BLOCKED", fontSize = 10.sp, color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                }
            }
        }

        if (request.responseStatus != null) {
            Text("${request.responseStatus}", color = statusColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestDetailSheet(
    request: NetworkRequest,
    onDismiss: () -> Unit,
    onSendToRepeater: () -> Unit = {}
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Request Detail", fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                request.responseStatus?.let {
                    Text("HTTP $it", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { copyToClipboard(context, "cURL", request.toCurl()) },
                    label = { Text("Copy as cURL", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) }
                )
                AssistChip(
                    onClick = {
                        // Hand the captured request to the pentest Repeater and switch the dev
                        // console over to it, so capture -> tamper -> resend is one tap.
                        RepeaterBridge.offer(request.toTransaction())
                        Toast.makeText(context, "Sent to Repeater", Toast.LENGTH_SHORT).show()
                        onSendToRepeater()
                        onDismiss()
                    },
                    label = { Text("Send to Repeater", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Send, null, modifier = Modifier.size(16.dp)) }
                )
                AssistChip(
                    onClick = {
                        val host = NetworkInterceptor.suggestRule(request.url)
                        NetworkInterceptor.addRule(InterceptorRule(pattern = host, action = RuleAction.BLOCK))
                        Toast.makeText(context, "Blocking $host", Toast.LENGTH_SHORT).show()
                    },
                    label = { Text("Block", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Block, null, modifier = Modifier.size(16.dp)) }
                )
            }
            Spacer(Modifier.height(12.dp))

            Text("URL", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(request.url, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }

            if (request.requestHeaders.isNotEmpty()) {
                SectionHeader("Request Headers")
                request.requestHeaders.forEach { (k, v) -> HeaderRow(k, v) }
            }
            if (request.requestBody.isNotBlank()) {
                SectionHeader("Request Body")
                BodyBlock(request.requestBody)
            }
            if (request.responseHeaders.isNotEmpty()) {
                SectionHeader("Response Headers")
                request.responseHeaders.forEach { (k, v) -> HeaderRow(k, v) }
            }
            if (request.responseBody.isNotBlank()) {
                SectionHeader("Response Body")
                BodyBlock(request.responseBody)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun BodyBlock(body: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        SelectionContainer {
            Text(body, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.padding(10.dp))
        }
    }
}

@Composable
fun HeaderRow(name: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(name, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(140.dp))
        SelectionContainer {
            Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun RulesTab(rules: List<InterceptorRule>) {
    var pattern by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(RuleAction.BLOCK) }
    var value by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Intercept requests whose URL contains a pattern: block them, redirect to " +
                "another URL, or inject a header. Applied live to every request.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = pattern,
            onValueChange = { pattern = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("URL contains…") },
            placeholder = { Text("e.g. ads.example.com or /track") },
            textStyle = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuleAction.entries.forEach { a ->
                FilterChip(
                    selected = action == a,
                    onClick = { action = a },
                    label = {
                        Text(when (a) {
                            RuleAction.BLOCK -> "Block"; RuleAction.REDIRECT -> "Redirect"; RuleAction.ADD_HEADER -> "Add header"
                        }, fontSize = 11.sp)
                    }
                )
            }
        }
        if (action != RuleAction.BLOCK) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (action == RuleAction.REDIRECT) "Redirect to URL" else "Header (Name: Value)") },
                textStyle = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                NetworkInterceptor.addRule(InterceptorRule(pattern = pattern.trim(), action = action, value = value.trim()))
                pattern = ""; value = ""
            },
            enabled = pattern.isNotBlank() && (action == RuleAction.BLOCK || value.isNotBlank()),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Add rule") }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        if (rules.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No interceptor rules yet.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn {
                items(rules, key = { it.id }) { rule ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val tint = when (rule.action) {
                            RuleAction.BLOCK -> Color(0xFFFF5252)
                            RuleAction.REDIRECT -> Color(0xFF4C8DFF)
                            RuleAction.ADD_HEADER -> Color(0xFF2DD4A7)
                        }
                        Icon(Icons.Default.Block, null, tint = tint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rule.pattern, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(rule.summary, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(checked = rule.enabled, onCheckedChange = { NetworkInterceptor.toggleRule(rule.id) })
                        IconButton(onClick = { NetworkInterceptor.removeRule(rule.id) }) {
                            Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(18.dp))
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
fun ElementsTab(html: String) {
    if (html.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Code, null, modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(0.5f))
                Spacer(Modifier.height(8.dp))
                Text("Page source will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        val scroll = rememberScrollState()
        Box(modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
            SelectionContainer {
                Text(html, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
fun InfoTab(url: String, requestCount: Int, ruleCount: Int) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoRow("Current URL", url)
        InfoRow("Captured requests", requestCount.toString())
        InfoRow("Active block rules", ruleCount.toString())
        InfoRow("fetch / XHR capture", "Bodies captured when Dev Console is ON")
        InfoRow("Interceptor", "Add rules under the Rules tab to block requests")
        InfoRow("Eruda", "Full JS console injected when Dev Console is ON")
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(160.dp), fontSize = 13.sp)
        SelectionContainer {
            Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Adapter from the dev console's captured request into the pentest module's shared
 * request/response shape, so Repeater and Audit can consume live traffic directly instead of
 * waiting on a persistence step that does not exist yet.
 */
private fun NetworkRequest.toTransaction(): HttpTransaction = HttpTransaction(
    method = method,
    url = url,
    headers = requestHeaders.toList(),
    body = requestBody.takeIf { it.isNotBlank() }?.toByteArray(),
    responseStatus = responseStatus,
    responseHeaders = responseHeaders.toList(),
    responseBody = responseBody.takeIf { it.isNotBlank() }?.toByteArray(),
    responseTimeMs = durationMs,
    source = "capture"
)

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied $label", Toast.LENGTH_SHORT).show()
}
