package com.akay.feature.downloads.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.akay.core.ui.theme.Glass
import com.akay.core.ui.theme.GlassStroke
import com.akay.core.ui.theme.Primary
import com.akay.feature.downloads.viewmodel.DownloadItem
import com.akay.feature.downloads.viewmodel.DownloadViewModel
import com.akay.feature.downloads.viewmodel.ItemStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DownloadManagerScreen(
    onBack: () -> Unit,
    viewModel: DownloadViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val tabs = listOf("Active (${state.active.size})", "Completed", "Failed")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!state.ytDlpReady) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("yt-dlp not available",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(state.setupError ?: "Rebuild the app to rebundle yt-dlp.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f))
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { viewModel.retryYtDlpCheck() }) {
                            Text("Retry", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(title, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }

            HorizontalPager(state = pagerState) { page ->
                val list = when (page) {
                    0 -> state.active
                    1 -> state.completed
                    else -> state.failed
                }
                if (list.isEmpty()) {
                    EmptyDownloadsPlaceholder(page)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(list, key = { it.id }) { item ->
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 }
                            ) {
                                DownloadCard(
                                    item = item,
                                    onPause = { viewModel.pause(it) },
                                    onResume = { viewModel.resume(it) },
                                    onCancel = { viewModel.cancel(it) },
                                    onRetry = { viewModel.retry(it) },
                                    onDelete = { viewModel.delete(it) },
                                    onOpen = { viewModel.openFile(it, context) },
                                    onShare = { viewModel.shareFile(it, context) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadCard(
    item: DownloadItem,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Glass),
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassStroke),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when {
                    item.filename.endsWith(".mp4") || item.filename.endsWith(".mkv") -> Icons.Default.Download
                    item.filename.endsWith(".mp3") || item.filename.endsWith(".m4a") -> Icons.Default.AudioFile
                    else -> Icons.Default.Download
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when (item.status) {
                            ItemStatus.RUNNING -> "${item.speedStr} - ${item.totalStr}"
                            ItemStatus.PAUSED -> "Paused"
                            ItemStatus.COMPLETED -> "Completed - ${item.totalStr}"
                            ItemStatus.FAILED -> "Failed: ${item.errorMsg ?: "Unknown"}"
                            ItemStatus.QUEUED -> "Waiting..."
                            ItemStatus.CANCELLED -> "Cancelled"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (item.status) {
                            ItemStatus.FAILED, ItemStatus.CANCELLED -> MaterialTheme.colorScheme.error
                            ItemStatus.COMPLETED -> Color(0xFF4CAF50)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                StatusBadge(status = item.status)
            }

            if (item.status == ItemStatus.RUNNING || item.status == ItemStatus.PAUSED) {
                Spacer(Modifier.height(10.dp))
                val animatedProgress by animateFloatAsState(
                    targetValue   = item.progress / 100f,
                    animationSpec = tween(500, easing = LinearEasing),
                    label         = "progress_${item.id}"
                )
                LinearProgressIndicator(
                    progress      = { animatedProgress },
                    modifier      = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape),
                    color         = if (item.status == ItemStatus.PAUSED)
                                        MaterialTheme.colorScheme.secondary
                                    else Primary,
                    trackColor    = MaterialTheme.colorScheme.surfaceVariant.copy(0.25f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${"%.1f".format(item.progress)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.speedStr.isNotBlank())
                        Text(item.speedStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                when (item.status) {
                    ItemStatus.RUNNING -> {
                        IconButton(onClick = { onPause(item.id) }) { Icon(Icons.Default.Pause, "Pause") }
                        IconButton(onClick = { onCancel(item.id) }) { Icon(Icons.Default.Cancel, "Cancel") }
                    }
                    ItemStatus.PAUSED -> {
                        IconButton(onClick = { onResume(item.id) }) { Icon(Icons.Default.PlayArrow, "Resume") }
                        IconButton(onClick = { onCancel(item.id) }) { Icon(Icons.Default.Cancel, "Cancel") }
                    }
                    ItemStatus.FAILED -> {
                        TextButton(onClick = { onRetry(item.id) }) { Text("Retry") }
                        IconButton(onClick = { onDelete(item.id) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                    ItemStatus.COMPLETED -> {
                        IconButton(onClick = { onOpen(item.id) }) { Icon(Icons.Default.OpenInNew, "Open") }
                        IconButton(onClick = { onShare(item.id) }) { Icon(Icons.Default.Share, "Share") }
                        IconButton(onClick = { onDelete(item.id) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun YtDlpSetupBanner(progress: Int) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Setting up download engine... $progress%", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun StatusBadge(status: ItemStatus) {
    val (color, label) = when (status) {
        ItemStatus.RUNNING -> Pair(Color(0xFF6C63FF), "Downloading")
        ItemStatus.PAUSED -> Pair(Color(0xFFFF9800), "Paused")
        ItemStatus.COMPLETED -> Pair(Color(0xFF4CAF50), "Done")
        ItemStatus.FAILED -> Pair(Color(0xFFFF5252), "Failed")
        ItemStatus.QUEUED -> Pair(Color(0xFF9E9E9E), "Queued")
        ItemStatus.CANCELLED -> Pair(Color(0xFF757575), "Cancelled")
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = color, fontSize = 12.sp)
    }
}

@Composable
fun EmptyDownloadsPlaceholder(page: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = when (page) {
                    0 -> Icons.Default.Download; 1 -> Icons.Default.CheckCircle; else -> Icons.Default.Warning
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = when (page) {
                    0 -> "No active downloads"; 1 -> "No completed downloads"; else -> "No failed downloads"
                },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}
