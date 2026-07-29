package com.akay.feature.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.akay.core.domain.model.SavedCredential
import com.akay.core.ui.security.BiometricAuth
import com.akay.core.ui.theme.Primary
import com.akay.feature.settings.viewmodel.PasswordManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordManagerScreen(
    onBack: () -> Unit,
    viewModel: PasswordManagerViewModel = hiltViewModel()
) {
    val credentials by viewModel.credentials.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var revealedIds by remember { mutableStateOf(setOf<String>()) }
    var pendingDelete by remember { mutableStateOf<SavedCredential?>(null) }

    fun reveal(credential: SavedCredential) {
        if (BiometricAuth.isAvailable(context)) {
            BiometricAuth.authenticate(
                context = context,
                title = "Unlock to view password",
                subtitle = credential.origin,
                onSuccess = { revealedIds = revealedIds + credential.id },
                onFailure = { /* stays masked */ }
            )
        } else {
            revealedIds = revealedIds + credential.id
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Passwords") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        if (credentials.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Password, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No saved passwords yet. AxBrowser will offer to save logins as you sign in to sites.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(credentials, key = { it.id }) { credential ->
                    val revealed = credential.id in revealedIds
                    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                credential.origin,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                credential.username.ifBlank { "(no username)" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (revealed) credential.password else "•".repeat(credential.password.length.coerceIn(6, 12)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    if (revealed) revealedIds = revealedIds - credential.id else reveal(credential)
                                }) {
                                    Icon(if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle visibility")
                                }
                                IconButton(onClick = { clipboard.setText(AnnotatedString(credential.password)) }) {
                                    Icon(Icons.Default.ContentCopy, "Copy password")
                                }
                                IconButton(onClick = { pendingDelete = credential }) {
                                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { credential ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete saved login?") },
            text = { Text("This will remove the saved password for ${credential.origin}.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(credential)
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}
