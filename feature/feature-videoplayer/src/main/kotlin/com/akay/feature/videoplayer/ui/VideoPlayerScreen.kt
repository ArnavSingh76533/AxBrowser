package com.akay.feature.videoplayer.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.akay.core.ui.theme.LocalAccentColor
import com.akay.feature.videoplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

@Composable
fun VideoPlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val accent = LocalAccentColor.current
    var isFullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    LaunchedEffect(controlsVisible, uiState.isPlaying) {
        if (controlsVisible && uiState.isPlaying && !uiState.isAudio) {
            delay(3500)
            controlsVisible = false
        }
    }

    LaunchedEffect(Unit) { viewModel.loadPending() }
    DisposableEffect(Unit) { onDispose { viewModel.release() } }

    // Keep the screen awake during playback.
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    DisposableEffect(isFullscreen) {
        activity?.requestedOrientation = if (isFullscreen)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // Apply resize mode to the PlayerView.
    LaunchedEffect(uiState.resizeMode, playerViewRef) {
        playerViewRef?.resizeMode = when (uiState.resizeMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                controlsVisible = !controlsVisible
            }
    ) {
        if (uiState.isAudio) {
            AudioArtwork(title = uiState.title, isPlaying = uiState.isPlaying, accent = accent)
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = viewModel.exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    playerViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize().then(if (uiState.isAudio) Modifier.alpha(0f) else Modifier)
        )

        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = accent)
        }

        // Replay button when finished
        if (uiState.isEnded) {
            Surface(
                onClick = { viewModel.replay() },
                shape = CircleShape,
                color = accent.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.Center).size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Replay, "Replay", tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
        }

        uiState.error?.let { error ->
            Text(error, modifier = Modifier.align(Alignment.Center).padding(16.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        AnimatedVisibility(
            visible = controlsVisible || uiState.isAudio,
            enter = fadeIn(tween(200)), exit = fadeOut(tween(300)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(0.7f), Color.Transparent)))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                    Text(
                        uiState.title.ifEmpty { if (uiState.isAudio) "Now Playing" else "Video" },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    IconButton(onClick = { viewModel.toggleMute() }) {
                        Icon(if (uiState.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            "Mute", tint = Color.White)
                    }
                    if (!uiState.isAudio) {
                        IconButton(onClick = { viewModel.cycleResizeMode() }) {
                            Icon(Icons.Default.AspectRatio, "Aspect ratio", tint = Color.White)
                        }
                        IconButton(onClick = { isFullscreen = !isFullscreen }) {
                            Icon(if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                "Fullscreen", tint = Color.White)
                        }
                    }
                }

                // Center transport
                if (!uiState.isEnded) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.seekRelative(-10_000L) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Replay10, "Rewind 10s", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                        Surface(
                            onClick = { viewModel.playPause() }, shape = CircleShape,
                            color = accent.copy(alpha = 0.85f), modifier = Modifier.size(68.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    if (uiState.isPlaying) "Pause" else "Play", tint = Color.White,
                                    modifier = Modifier.size(42.dp))
                            }
                        }
                        IconButton(onClick = { viewModel.seekRelative(10_000L) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Forward10, "Forward 10s", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                }

                // Bottom seek + speed
                Column(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.8f))))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    val sliderPos = if (uiState.duration > 0)
                        uiState.playbackPosition.toFloat() / uiState.duration.toFloat() else 0f
                    Slider(
                        value = sliderPos.coerceIn(0f, 1f),
                        onValueChange = { viewModel.seekTo((it * uiState.duration).toLong()); controlsVisible = true },
                        colors = SliderDefaults.colors(
                            thumbColor = accent, activeTrackColor = accent,
                            inactiveTrackColor = Color.White.copy(0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${formatTime(uiState.playbackPosition)} / ${formatTime(uiState.duration)}",
                            color = Color.White, fontSize = 12.sp)
                        var speedExpanded by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { speedExpanded = true }) {
                                Icon(Icons.Default.Speed, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("${uiState.playbackSpeed}x", color = Color.White, fontSize = 12.sp)
                            }
                            DropdownMenu(expanded = speedExpanded, onDismissRequest = { speedExpanded = false }) {
                                listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { speed ->
                                    DropdownMenuItem(text = { Text("${speed}x") },
                                        onClick = { viewModel.setSpeed(speed); speedExpanded = false })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioArtwork(title: String, isPlaying: Boolean, accent: Color) {
    val infinite = rememberInfiniteTransition(label = "audio")
    val pulse by infinite.animateFloat(
        initialValue = 1f, targetValue = if (isPlaying) 1.08f else 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = EaseInOut), RepeatMode.Reverse),
        label = "pulse"
    )
    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(accent.copy(0.25f), Color(0xFF07070F), Color.Black))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier.size(220.dp).scale(pulse)
                        .background(Brush.radialGradient(listOf(accent.copy(0.4f), Color.Transparent)), CircleShape)
                )
                Surface(shape = RoundedCornerShape(28.dp), color = accent.copy(alpha = 0.18f),
                    modifier = Modifier.size(150.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(72.dp))
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(title.ifEmpty { "Now Playing" },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 40.dp))
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSecs = ms / 1000
    val hours = totalSecs / 3600
    val minutes = (totalSecs % 3600) / 60
    val seconds = totalSecs % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
