package com.akay.feature.browser.extensions.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.akay.feature.browser.extensions.*
import com.akay.feature.browser.extensions.api.CapabilityRegistry
import org.json.JSONObject

/** Native UI owns installation and permission consent. Extension HTML cannot forge these dialogs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsPanel(vm: ExtensionViewModel, onOpenStore: () -> Unit, onOpenUserScripts: () -> Unit) {
    val visible by vm.showManager.collectAsState()
    val extensions by vm.manager.extensions.collectAsState()
    val busy by vm.busy.collectAsState()
    val status by vm.status.collectAsState()
    val pending by vm.pending.collectAsState()
    val page by vm.page.collectAsState()
    val permission by vm.permissionPrompt.collectAsState()
    val errors by vm.runtime.errors.collectAsState()
    val revision by vm.runtime.ui.revision.collectAsState()
    var remove by remember { mutableStateOf<Extension?>(null) }
    var capabilities by remember { mutableStateOf(false) }
    val packagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importPackage) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::importFolder) }
    if (visible) Dialog(onDismissRequest = { vm.showManager.value = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TopAppBar(title = { Text("Extensions", fontWeight = FontWeight.Bold) }, navigationIcon = {
                    IconButton(onClick = { vm.showManager.value = false }) { Icon(Icons.Default.Close, "Close extensions") }
                }, actions = { IconButton(onClick = { capabilities = true }) { Icon(Icons.Default.Info, "Compatibility details") } })
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)), RoundedCornerShape(24.dp)).padding(24.dp)) {
                        Icon(Icons.Default.Extension, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text("Make space for more.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Add tools you love. Review what they can access. Stay in control.", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { vm.showManager.value = false; onOpenStore() }, enabled = !busy) {
                            Icon(Icons.Default.Storefront, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Explore Chrome Web Store")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { packagePicker.launch(arrayOf("*/*")) }, enabled = !busy && pending == null, modifier = Modifier.weight(1f)) { Icon(Icons.Default.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import ZIP / CRX") }
                        OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !busy && pending == null, modifier = Modifier.weight(1f)) { Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Load folder") }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (status != null) Notice(status!!)
                    if (!vm.runtime.isolatedSupported) Notice("This Android System WebView cannot isolate content scripts, so website scripts stay disabled. Update Android System WebView to enable them. Extension pages also require a compatible WebView version.")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Your extensions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${extensions.count { it.enabled }} active", color = MaterialTheme.colorScheme.primary)
                    }
                    if (extensions.isEmpty()) OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("A little extra goes a long way", style = MaterialTheme.typography.titleMedium)
                            Text("Browse the Store or import a Manifest V3 extension to get started. Compatibility varies by extension.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    extensions.forEach { ext ->
                        key(ext.id, revision) {
                            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                            Icon(Icons.Default.Extension, null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                        }
                                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                            Text(ext.manifest.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                            Text("v${ext.manifest.version} · ${if (ext.enabled) "Enabled" else "Disabled"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(checked = ext.enabled, enabled = !busy, onCheckedChange = { vm.setEnabled(ext, it) })
                                    }
                                    if (ext.manifest.description.isNotBlank()) Text(ext.manifest.description, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                                    val badge = vm.runtime.ui.action(ext).optString("badgeText")
                                    if (badge.isNotBlank()) SuggestionChip(onClick = { vm.action(ext) }, label = { Text(badge) })
                                    Text("${ext.manifest.permissions.size} permissions · ${ext.manifest.hosts.size} host rules", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    errors[ext.id]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilledTonalButton(onClick = { vm.action(ext) }, enabled = ext.enabled && !busy) { Text("Open") }
                                        if (ext.manifest.options != null) TextButton(onClick = { vm.page.value = ExtensionPage(ext, ext.manifest.options!!) }, enabled = ext.enabled && !busy) { Text("Options") }
                                        Spacer(Modifier.weight(1f))
                                        IconButton(onClick = { remove = ext }, enabled = !busy) { Icon(Icons.Default.DeleteOutline, "Remove ${ext.manifest.name}") }
                                    }
                                    if (ext.enabled) {
                                        vm.runtime.ui.menus(ext.id).filter { it.optBoolean("visible", true) }.forEach { item ->
                                            TextButton(onClick = { vm.pageAction(ext, item) }, enabled = item.optBoolean("enabled", true)) { Text(item.getString("title")) }
                                        }
                                        ext.manifest.json.optJSONObject("commands")?.let { commands ->
                                            commands.keys().forEach { name -> TextButton(onClick = { vm.command(ext, name) }) { Text(commands.getJSONObject(name).optString("description").ifBlank { name }) } }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = { vm.showManager.value = false; onOpenUserScripts() }) { Icon(Icons.Default.Code, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Manage existing userscripts") }
                    Text("Manifest V3 compatibility bridge · Extensions are disabled in private tabs. Some Chrome APIs and Store packages are unavailable in WebView.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
    pending?.let { item ->
        AlertDialog(onDismissRequest = { if (!busy) vm.cancelInstall() }, icon = { Icon(Icons.Default.Shield, null) }, title = { Text("Add ${item.manifest.name}?") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Version ${item.manifest.version}\n${item.source}")
                Text("Allow access to", fontWeight = FontWeight.SemiBold)
                Text((item.manifest.permissions + item.manifest.hosts.map { it.value } + item.manifest.scripts.flatMap { it.matches }.map { "Read/change pages: ${it.value}" }).joinToString("\n").ifBlank { "No privileged permissions requested" })
                val unsupported = item.manifest.permissions - CapabilityRegistry.permissions
                if (unsupported.isNotEmpty()) Text("Unavailable APIs: ${unsupported.joinToString()}. Features depending on them will not work.", color = MaterialTheme.colorScheme.error)
                Text("Compatibility is partial. Background tasks stop when AxBrowser closes. Store retrieval and signature verification do not guarantee extension safety.", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { Button(onClick = vm::confirmInstall, enabled = !busy) { Text("Add extension") } }, dismissButton = { TextButton(onClick = vm::cancelInstall, enabled = !busy) { Text("Cancel") } })
    }
    permission?.let { prompt -> AlertDialog(onDismissRequest = { prompt.decision.complete(false) }, title = { Text("Additional access requested") }, text = {
        Text("${prompt.extension.manifest.name} requests:\n\n${(prompt.permissions + prompt.origins).joinToString("\n")}")
    }, confirmButton = { Button(onClick = { prompt.decision.complete(true) }) { Text("Allow") } }, dismissButton = { TextButton(onClick = { prompt.decision.complete(false) }) { Text("Deny") } }) }
    remove?.let { ext -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove ${ext.manifest.name}?") }, text = { Text("Its extension data will also be deleted. Reload open pages to clear changes it made.") }, confirmButton = { TextButton(onClick = { vm.uninstall(ext); remove = null }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("Keep") } }) }
    if (capabilities) AlertDialog(onDismissRequest = { capabilities = false }, title = { Text("Compatibility") }, text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        Text("Supported methods use a partial WebView implementation. Unsupported methods return runtime.lastError or reject their Promise.\n")
        val info = CapabilityRegistry.json()
        info.keys().forEach { key -> Text("$key\n${info.getString(key)}\n", style = MaterialTheme.typography.bodySmall) }
    } }, confirmButton = { TextButton(onClick = { capabilities = false }) { Text("Done") } })
    page?.let { request -> key(request.extension.id, request.path) {
        Dialog(onDismissRequest = { vm.page.value = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                    TopAppBar(title = { Text(request.extension.manifest.name) }, navigationIcon = { IconButton(onClick = { vm.page.value = null }) { Icon(Icons.Default.Close, "Close extension page") } })
                    val result = remember { runCatching { ExtensionWebViewBridge(vm.runtime).create(request.extension, request.path) } }
                    val view = result.getOrNull()
                    if (view != null) {
                        DisposableEffect(view) { onDispose { vm.runtime.invalidateView(view); view.stopLoading(); view.destroy() } }
                        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().weight(1f))
                    } else Text(result.exceptionOrNull()?.message ?: "Extension page unavailable", Modifier.padding(24.dp))
                }
            }
        }
    } }
}

@Composable
private fun Notice(message: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Info, null, Modifier.size(20.dp)); Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
