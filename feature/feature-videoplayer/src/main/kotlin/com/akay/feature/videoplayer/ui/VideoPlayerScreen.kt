package com.akay.feature.videoplayer.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.ui.PlayerView
import com.akay.feature.videoplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

@Composable
fun VideoPlayerScreen(
    videoUrl: String,
    title: String = "",
    viewModel: PlayerViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    var isFullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible && uiState.isPlaying) {
            delay(3000)
            controlsVisible = false
        }
    }

    LaunchedEffect(videoUrl) { viewModel.loadVideo(videoUrl, title) }
    DisposableEffect(Unit) { onDispose { viewModel.release() } }

    DisposableEffect(isFullscreen) {
        activity?.requestedOrientation = if (isFullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { controlsVisible = !controlsVisible }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = viewModel.exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
        }

        uiState.error?.let { error ->
            Text(text = error, modifier = Modifier.align(Alignment.Center).padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(tween(200)), exit = fadeOut(tween(300)), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(0.7f), Color.Transparent)))
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                        Text(uiState.title.ifEmpty { "Now Playing" }, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                        IconButton(onClick = { isFullscreen = !isFullscreen }) {
                            Icon(if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Fullscreen", tint = Color.White)
                        }
                    }
                }

                Row(modifier = Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.seekRelative(-10_000L) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Replay10, "Rewind 10s", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    Surface(onClick = { viewModel.playPause() }, shape = CircleShape, color = Color.White.copy(alpha = 0.2f), modifier = Modifier.size(64.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (uiState.isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                    }
                    IconButton(onClick = { viewModel.seekRelative(10_000L) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Forward10, "Forward 10s", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.75f))))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    val sliderPos = if (uiState.duration > 0) uiState.playbackPosition.toFloat() / uiState.duration.toFloat() else 0f
                    Slider(
                        value = sliderPos,
                        onValueChange = { viewModel.seekTo((it * uiState.duration).toLong()); controlsVisible = true },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${formatTime(uiState.playbackPosition)} / ${formatTime(uiState.duration)}", color = Color.White, fontSize = 12.sp)
                        var speedExpanded by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { speedExpanded = true }) { Text("${uiState.playbackSpeed}x", color = Color.White, fontSize = 12.sp) }
                            DropdownMenu(expanded = speedExpanded, onDismissRequest = { speedExpanded = false }) {
                                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { speed ->
                                    DropdownMenuItem(text = { Text("${speed}x") }, onClick = { viewModel.setSpeed(speed); speedExpanded = false })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSecs = ms / 1000
    val hours = totalSecs / 3600
    val minutes = (totalSecs % 3600) / 60
    val seconds = totalSecs % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
