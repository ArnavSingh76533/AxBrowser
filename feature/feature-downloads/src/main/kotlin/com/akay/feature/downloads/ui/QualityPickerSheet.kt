package com.akay.feature.downloads.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text("Select Quality", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            if (title.isNotBlank()) {
                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(0.6f), modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), maxLines = 2)
            }
            HorizontalDivider()

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Primary)
                        Spacer(Modifier.height(12.dp))
                        Text("Fetching available qualities...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(0.6f))
                    }
                }
            } else {
                LazyColumn {
                    items(formats) { fmt ->
                        ListItem(
                            headlineContent = { Text(fmt.label, fontWeight = FontWeight.Medium) },
                            supportingContent = { Text(fmt.displaySize, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(0.5f)) },
                            leadingContent = {
                                Icon(imageVector = if (fmt.isAudioOnly) Icons.Default.AudioFile else Icons.Default.VideoFile, contentDescription = null, tint = Primary)
                            },
                            trailingContent = {
                                FilledTonalButton(onClick = { onSelect(fmt) }) { Text("Download") }
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}
