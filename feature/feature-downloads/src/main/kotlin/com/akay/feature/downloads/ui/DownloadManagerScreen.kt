package com.akay.feature.downloads.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
    onPlayInApp: (filePath: String, title: String) -> Unit = { _, _ -> },
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
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
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("yt-dlp not available", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(state.setupError ?: "Restart the app to initialize yt-dlp.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f))
                        }
                        TextButton(onClick = { viewModel.retryYtDlpCheck() }) { Text("Retry", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            ScrollableTabRow(selectedTabIndex = pagerState.currentPage, containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.primary) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = pagerState.currentPage == index, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } }, text = { Text(title, fontWeight = FontWeight.SemiBold) })
                }
            }

            HorizontalPager(state = pagerState) { page ->
                val list = when (page) { 0 -> state.active; 1 -> state.completed; else -> state.failed }
                if (list.isEmpty()) {
                    EmptyDownloadsPlaceholder(page)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(list, key = { it.id }) { item ->
                            AnimatedVisibility(visible = true, enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 }) {
                                DownloadCard(
                                    item = item,
                                    onPause = { viewModel.pause(it) },
                                    onResume = { viewModel.resume(it) },
                                    onCancel = { viewModel.cancel(it) },
                                    onRetry = { viewModel.retry(it) },
                                    onDelete = { viewModel.delete(it) },
                                    onOpen = { viewModel.openFile(it, context, onPlayInApp) },
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
        border = BorderStroke(1.dp, GlassStroke),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.thumbnailPath != null) {
                        val bitmap = remember(item.thumbnailPath) {
                            runCatching {
                                android.graphics.BitmapFactory.decodeFile(item.thumbnailPath)?.let { androidx.compose.ui.graphics.asImageBitmap(it) }
                            }.getOrNull()
                        }
                        if (bitmap != null) {
                            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                        } else {
                            MediaTypeIcon(item.displayName)
                        }
                    } else {
                        MediaTypeIcon(item.displayName)
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(item.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when (item.status) {
                            ItemStatus.RUNNING -> item.speedStr.ifBlank { "Downloading..." }
                            ItemStatus.PAUSED -> "Paused at ${"%.0f".format(item.progress)}%"
                            ItemStatus.COMPLETED -> "Completed"
                            ItemStatus.FAILED -> "Failed: ${item.errorMsg?.take(60) ?: "Unknown"}"
                            ItemStatus.QUEUED -> "Waiting..."
                            ItemStatus.CANCELLED -> "Cancelled"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (item.status) {
                            ItemStatus.FAILED, ItemStatus.CANCELLED -> MaterialTheme.colorScheme.error
                            ItemStatus.COMPLETED -> Color(0xFF4CAF50)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2
                    )
                }

                Spacer(Modifier.width(8.dp))
                StatusBadge(status = item.status)
            }

            if (item.status == ItemStatus.RUNNING || item.status == ItemStatus.PAUSED) {
                Spacer(Modifier.height(10.dp))
                val animatedProgress by animateFloatAsState(targetValue = item.progress / 100f, animationSpec = tween(500, easing = LinearEasing), label = "prog_${item.id}")
                LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = if (item.status == ItemStatus.PAUSED) MaterialTheme.colorScheme.secondary else Primary, trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.25f))
                Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${"%.1f".format(item.progress)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.totalStr.isNotBlank()) Text(item.totalStr.take(40), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                when (item.status) {
                    ItemStatus.RUNNING -> {
                        IconButton(onClick = { onPause(item.id) }) { Icon(Icons.Default.Pause, "Pause", tint = Primary) }
                        IconButton(onClick = { onCancel(item.id) }) { Icon(Icons.Default.Cancel, "Cancel", tint = MaterialTheme.colorScheme.error) }
                    }
                    ItemStatus.PAUSED -> {
                        IconButton(onClick = { onResume(item.id) }) { Icon(Icons.Default.PlayArrow, "Resume", tint = Primary) }
                        IconButton(onClick = { onCancel(item.id) }) { Icon(Icons.Default.Cancel, "Cancel") }
                    }
                    ItemStatus.FAILED -> {
                        TextButton(onClick = { onRetry(item.id) }) { Text("Retry") }
                        IconButton(onClick = { onDelete(item.id) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                    ItemStatus.COMPLETED -> {
                        IconButton(onClick = { onOpen(item.id) }) { Icon(Icons.Default.PlayArrow, "Play") }
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
private fun MediaTypeIcon(filename: String) {
    val ext = filename.substringAfterLast(".").lowercase()
    val (icon, tint) = when (ext) {
        "mp4", "mkv", "avi", "mov", "webm" -> Icons.Default.PlayCircle to Color(0xFF6C63FF)
        "mp3", "m4a", "aac", "opus", "ogg" -> Icons.Default.MusicNote to Color(0xFF00D9F5)
        "flac", "wav" -> Icons.Default.GraphicEq to Color(0xFF00D9F5)
        else -> Icons.Default.Download to Color(0xFF9E9E9E)
    }
    Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
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
    Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = color, fontSize = 12.sp)
    }
}

@Composable
fun EmptyDownloadsPlaceholder(page: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = when (page) { 0 -> Icons.Default.Download; 1 -> Icons.Default.CheckCircle; else -> Icons.Default.Warning }, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text(text = when (page) { 0 -> "No active downloads"; 1 -> "No completed downloads"; else -> "No failed downloads" }, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}
