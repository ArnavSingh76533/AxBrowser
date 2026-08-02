package com.akay.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.akay.core.domain.model.ProxyRotationMode
import com.akay.core.domain.model.ProxyServer
import com.akay.core.domain.model.ProxyType
import com.akay.core.ui.theme.Primary
import com.akay.feature.settings.viewmodel.ProxySettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxySettingsScreen(
    onBack: () -> Unit,
    viewModel: ProxySettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var editingProxy by remember { mutableStateOf<ProxyServer?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newBypassRule by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Proxy") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editingProxy = null; showAddDialog = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add proxy") },
                containerColor = Primary
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                ProxyMasterToggleCard(
                    enabled = uiState.enabled,
                    hasAnyProxy = uiState.proxies.isNotEmpty(),
                    activeLabel = uiState.proxies.filter { it.enabled }.getOrNull(uiState.activeIndex)?.label,
                    onToggle = { viewModel.setProxyEnabled(it) }
                )
                Spacer(Modifier.height(16.dp))
            }

            if (uiState.proxies.isEmpty()) {
                item {
                    Text(
                        "No proxies added yet. Add one below - HTTP, HTTPS, SOCKS4, and SOCKS5 are all supported.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(uiState.proxies, key = { it.id }) { proxy ->
                ProxyCard(
                    proxy = proxy,
                    onToggle = { viewModel.toggleProxyEnabled(proxy.id, it) },
                    onEdit = { editingProxy = proxy; showAddDialog = true },
                    onDelete = { viewModel.deleteProxy(proxy.id) }
                )
                Spacer(Modifier.height(8.dp))
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("Rotation", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.selectableGroup()) {
                    RotationOption(
                        title = "Manual",
                        subtitle = "Switch proxies yourself with \u201CRotate now\u201D",
                        selected = uiState.rotationMode == ProxyRotationMode.MANUAL.name,
                        onSelect = { viewModel.setRotationMode(ProxyRotationMode.MANUAL) }
                    )
                    RotationOption(
                        title = "Every navigation",
                        subtitle = "Pick a new proxy each time you go to a new page",
                        selected = uiState.rotationMode == ProxyRotationMode.PER_NAVIGATION.name,
                        onSelect = { viewModel.setRotationMode(ProxyRotationMode.PER_NAVIGATION) }
                    )
                    RotationOption(
                        title = "Timed",
                        subtitle = "Automatically rotate every ${uiState.rotationIntervalMinutes} min",
                        selected = uiState.rotationMode == ProxyRotationMode.TIMED.name,
                        onSelect = { viewModel.setRotationMode(ProxyRotationMode.TIMED) }
                    )
                }
                if (uiState.rotationMode == ProxyRotationMode.TIMED.name) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
                        listOf(5, 10, 30, 60).forEach { minutes ->
                            FilterChip(
                                selected = uiState.rotationIntervalMinutes == minutes,
                                onClick = { viewModel.setRotationInterval(minutes) },
                                label = { Text("${minutes}m") },
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("Bypass list (always direct, no proxy)", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = newBypassRule,
                        onValueChange = { newBypassRule = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("e.g. *.bank.com or 192.168.0.0/16") },
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        viewModel.addBypassRule(newBypassRule.trim())
                        newBypassRule = ""
                    }) { Icon(Icons.Default.Add, "Add") }
                }
                Spacer(Modifier.height(6.dp))
                uiState.bypassRules.forEach { rule ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(rule, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.removeBypassRule(rule) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
        }
    }

    if (showAddDialog) {
        ProxyEditDialog(
            existing = editingProxy,
            onDismiss = { showAddDialog = false },
            onSave = { id, label, type, host, port, username, password, enabled ->
                viewModel.saveProxy(id, label, type, host, port, username, password, enabled)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun ProxyMasterToggleCard(
    enabled: Boolean,
    hasAnyProxy: Boolean,
    activeLabel: String?,
    onToggle: (Boolean) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Wifi, null, tint = if (enabled) Primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Use proxy", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(
                    if (enabled && activeLabel != null) "Active: $activeLabel"
                    else if (enabled) "Enabled \u2014 add a proxy below" else "Off \u2014 browsing directly",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle, enabled = hasAnyProxy || !enabled)
        }
    }
}

@Composable
private fun ProxyCard(
    proxy: ProxyServer,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                        color = Primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            proxy.type.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(proxy.label.ifBlank { proxy.host }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "${proxy.host}:${proxy.port}${if (!proxy.username.isNullOrBlank()) " \u2022 auth" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
            Switch(checked = proxy.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun RotationOption(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProxyEditDialog(
    existing: ProxyServer?,
    onDismiss: () -> Unit,
    onSave: (id: String?, label: String, type: ProxyType, host: String, port: Int, username: String?, password: String?, enabled: Boolean) -> Unit
) {
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: ProxyType.HTTP) }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf(existing?.port?.toString() ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var typeMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add proxy" else "Edit proxy") },
        text = {
            Column {
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("Label (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedTextField(
                        value = type.name, onValueChange = {}, readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    // A transparent box on top of the whole field reliably
                    // captures the tap - a `.clickable` directly on a
                    // readOnly OutlinedTextField can get swallowed by its
                    // internal text-selection touch handling instead.
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { typeMenuExpanded = true }
                    )
                    DropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                        ProxyType.values().forEach { t ->
                            DropdownMenuItem(text = { Text(t.name) }, onClick = { type = t; typeMenuExpanded = false })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        value = host, onValueChange = { host = it },
                        label = { Text("Host") }, singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = port, onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text("Port") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Username (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password (optional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank() && port.toIntOrNull() != null,
                onClick = {
                    onSave(
                        existing?.id, label.ifBlank { host }, type, host.trim(),
                        port.toIntOrNull() ?: 0,
                        username.ifBlank { null }, password.ifBlank { null },
                        existing?.enabled ?: true
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
