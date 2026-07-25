package com.akay.feature.downloads.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues

data class DetectedMediaUi(
    val id: String,
    val url: String,
    val filename: String,
    val mimeType: String?,
    val isVideo: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaBottomSheet(
    detectedUrls: List<DetectedMediaUi>,
    onDownloadDirect: (url: String, filename: String) -> Unit,
    onDownloadWithYtDlp: (url: String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                "Detected Media",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )
            HorizontalDivider()
            LazyColumn {
                items(detectedUrls) { media ->
                    MediaItemRow(
                        media = media,
                        onDownloadDirect = { onDownloadDirect(media.url, media.filename) },
                        onDownloadYtDlp = { onDownloadWithYtDlp(media.url) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
        }
    }
}

@Composable
fun MediaItemRow(
    media: DetectedMediaUi,
    onDownloadDirect: () -> Unit,
    onDownloadYtDlp: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (media.isVideo) Icons.Default.VideoFile else Icons.Default.AudioFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(media.filename, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(media.mimeType ?: media.url, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            OutlinedButton(onClick = onDownloadDirect, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text("Direct", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(onClick = onDownloadYtDlp, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text("Smart DL", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
