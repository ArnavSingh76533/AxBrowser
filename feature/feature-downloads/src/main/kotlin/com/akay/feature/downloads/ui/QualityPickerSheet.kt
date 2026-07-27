package com.akay.feature.downloads.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.akay.core.ui.theme.Primary
import com.akay.feature.downloads.engine.VideoFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityPickerSheet(
    title: String,
    formats: List<VideoFormat>,
    isLoading: Boolean,
    onSelect: (VideoFormat) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
    status: String = "",
    onBestQuality: () -> Unit = {}
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.HighQuality, null, tint = Primary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Choose quality", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (title.isNotBlank()) {
                        Text(
                            title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.6f),
                            maxLines = 2
                        )
                    }
                }
            }
            HorizontalDivider()

            when {
                isLoading -> LoadingState(status = status)
                error != null -> ErrorState(error = error, onBestQuality = onBestQuality, onDismiss = onDismiss)
                else -> {
                    val video = formats.filter { !it.isAudioOnly }
                    val audio = formats.filter { it.isAudioOnly }
                    LazyColumn {
                        item {
                            QuickBestRow(onClick = onBestQuality)
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        if (video.isNotEmpty()) {
                            item { GroupHeader("Video") }
                            items(video) { fmt -> FormatRow(fmt, onSelect) }
                        }
                        if (audio.isNotEmpty()) {
                            item { GroupHeader("Audio only") }
                            items(audio) { fmt -> FormatRow(fmt, onSelect) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingState(status: String) {
    // Elapsed-seconds counter so the user always sees forward motion.
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(1000); elapsed++ }
    }
    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Primary)
            Spacer(Modifier.height(16.dp))
            Text("Analyzing link…  ${elapsed}s", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                status.ifBlank { "Contacting site and extracting available formats" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(0.6f),
                maxLines = 2
            )
        }
    }
}

@Composable
private fun ErrorState(error: String, onBestQuality: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("Couldn't load qualities", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(error, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBestQuality, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Bolt, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Try best quality anyway")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDismiss) { Text("Cancel") }
    }
}

@Composable
private fun QuickBestRow(onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text("Best available", fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text("Auto-selects highest quality (video + audio)", style = MaterialTheme.typography.bodySmall) },
        leadingContent = {
            Surface(color = Primary.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Default.Bolt, null, tint = Primary, modifier = Modifier.padding(8.dp))
            }
        },
        trailingContent = { FilledTonalButton(onClick = onClick) { Text("Download") } },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    )
}

@Composable
private fun GroupHeader(label: String) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun FormatRow(fmt: VideoFormat, onSelect: (VideoFormat) -> Unit) {
    ListItem(
        headlineContent = { Text(fmt.label, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Text(fmt.displaySize, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
        },
        leadingContent = {
            Icon(
                imageVector = if (fmt.isAudioOnly) Icons.Default.AudioFile else Icons.Default.VideoFile,
                contentDescription = null,
                tint = if (fmt.isAudioOnly) Color(0xFF00D9F5) else Primary
            )
        },
        trailingContent = {
            if (fmt.height >= 1080) {
                Surface(color = Primary.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                    Text("HD", color = Primary, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable { onSelect(fmt) }
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}
