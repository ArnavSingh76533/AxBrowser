package com.akay.feature.settings.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.akay.core.data.userscript.UserScript
import com.akay.core.ui.theme.Primary
import com.akay.feature.settings.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScriptsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val scripts by viewModel.userScripts.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<UserScript?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extensions") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { showImport = true }) {
                        Icon(Icons.Default.CloudDownload, "Import from URL")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = UserScript(name = "", code = "") },
                containerColor = Primary,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New script") }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Extension, null, tint = Primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Userscripts run custom JavaScript on matching pages — the WebView " +
                            "equivalent of extensions. Import .user.js files or write your own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (scripts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Extension, null, modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(0.25f))
                        Spacer(Modifier.height(12.dp))
                        Text("No scripts yet", color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(scripts, key = { it.id }) { script ->
                        ScriptCard(
                            script = script,
                            onToggle = { viewModel.toggleScript(script.id) },
                            onEdit = { editing = script },
                            onDelete = { viewModel.deleteScript(script.id) }
                        )
                    }
                }
            }
        }
    }

    editing?.let { script ->
        ScriptEditorDialog(
            initial = script,
            onDismiss = { editing = null },
            onSave = {
                if (it.name.isBlank() || it.code.isBlank()) {
                    Toast.makeText(context, "Name and code are required", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.saveScript(it)
                    editing = null
                }
            }
        )
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("Import from URL") },
            text = {
                Column {
                    Text("Paste a .user.js URL. The name and first @match rule are read from the file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importUrl,
                        onValueChange = { importUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://…/script.user.js") }
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = importUrl.isNotBlank(), onClick = {
                    val u = importUrl
                    showImport = false
                    importUrl = ""
                    viewModel.importScriptFromUrl(u) { ok, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ScriptCard(
    script: UserScript,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f))) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(script.name.ifBlank { "Untitled" }, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(script.urlPattern, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(checked = script.enabled, onCheckedChange = { onToggle() })
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("Edit") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
}

@Composable
private fun ScriptEditorDialog(
    initial: UserScript,
    onDismiss: () -> Unit,
    onSave: (UserScript) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var pattern by remember { mutableStateOf(initial.urlPattern) }
    var code by remember { mutableStateOf(initial.code) }
    var runAtEnd by remember { mutableStateOf(initial.runAtEnd) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "New script" else "Edit script") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pattern, onValueChange = { pattern = it },
                    label = { Text("URL match (glob, e.g. *://*.youtube.com/*)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Run at page end", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = runAtEnd, onCheckedChange = { runAtEnd = it })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text("JavaScript") },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(name = name.trim(), urlPattern = pattern.trim().ifBlank { "*" },
                    code = code, runAtEnd = runAtEnd))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
