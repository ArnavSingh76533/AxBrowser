package com.akay.feature.browser.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.combinedClickable
import com.akay.core.domain.model.Tab
import com.akay.core.ui.components.GalaxyBackground
import com.akay.core.ui.theme.LocalAccentColor

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TabSwitcherOverlay(
    tabs: List<Tab>,
    activeTabId: String?,
    onTabClick: (Tab) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: (incognito: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    groupColors: List<Long> = listOf(0xFFB388FF, 0xFF80D8FF, 0xFFFF8A80, 0xFFFFD180, 0xFFA7FFEB, 0xFFCCFF90),
    onGroupTabs: (tabIds: List<String>, name: String, color: Long) -> Unit = { _, _, _ -> },
    onAddToGroup: (tabId: String, groupId: String, name: String, color: Int) -> Unit = { _, _, _, _ -> },
    onRemoveFromGroup: (tabId: String) -> Unit = {}
) {
    val accent = LocalAccentColor.current
    var showPrivate by remember { mutableStateOf(false) }
    val normalTabs = tabs.filter { !it.isIncognito }
    val privateTabs = tabs.filter { it.isIncognito }
    // Group tabs so members of the same group sit next to each other.
    val shown = (if (showPrivate) privateTabs else normalTabs)
        .sortedBy { it.groupId == null } // grouped first
        .let { list -> list.sortedBy { it.groupId ?: "" } }

    var groupMenuTab by remember { mutableStateOf<Tab?>(null) }
    var newGroupDialog by remember { mutableStateOf<Tab?>(null) }
    var newGroupName by remember { mutableStateOf("") }

    val existingGroups = normalTabs.filter { it.groupId != null }
        .distinctBy { it.groupId }
        .map { Triple(it.groupId!!, it.groupName ?: "Group", it.groupColor ?: groupColors.first().toInt()) }

    GalaxyBackground(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Segmented Tabs / Private control
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White.copy(alpha = 0.06f),
                border = BorderStroke(1.dp, Color.White.copy(0.12f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(4.dp)) {
                    SegTab("Tabs", Icons.Default.Public, normalTabs.size, !showPrivate, accent,
                        Modifier.weight(1f)) { showPrivate = false }
                    SegTab("Private", Icons.Default.VisibilityOff, privateTabs.size, showPrivate, accent,
                        Modifier.weight(1f)) { showPrivate = true }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (shown.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (showPrivate) Icons.Default.VisibilityOff else Icons.Default.Public,
                            null, tint = Color.White.copy(0.25f), modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (showPrivate) "No private tabs" else "No open tabs",
                            color = Color.White.copy(0.5f)
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(shown, key = { it.id }) { tab ->
                        TabCard(
                            tab = tab,
                            isActive = tab.id == activeTabId,
                            accent = accent,
                            onClick = { onTabClick(tab) },
                            onClose = { onCloseTab(tab.id) },
                            onLongPress = { groupMenuTab = tab }
                        )
                    }
                }
            }

            Button(
                onClick = { onNewTab(showPrivate) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(if (showPrivate) "New private tab" else "New tab", fontWeight = FontWeight.SemiBold)
            }
        }
    }

    groupMenuTab?.let { tab ->
        AlertDialog(
            onDismissRequest = { groupMenuTab = null },
            title = { Text(if (tab.groupId != null) "Tab group" else "Group this tab") },
            text = {
                Column {
                    if (tab.groupId != null) {
                        TextButton(onClick = { onRemoveFromGroup(tab.id); groupMenuTab = null }) {
                            Text("Remove from \"${tab.groupName}\"")
                        }
                    }
                    existingGroups.filter { it.first != tab.groupId }.forEach { (id, name, color) ->
                        TextButton(onClick = {
                            onAddToGroup(tab.id, id, name, color)
                            groupMenuTab = null
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(Color(color), RoundedCornerShape(50)))
                                Spacer(Modifier.width(8.dp))
                                Text("Add to \"$name\"")
                            }
                        }
                    }
                    TextButton(onClick = {
                        newGroupDialog = tab
                        newGroupName = ""
                        groupMenuTab = null
                    }) { Text("Start new group\u2026") }
                }
            },
            confirmButton = {
                TextButton(onClick = { groupMenuTab = null }) { Text("Close") }
            }
        )
    }

    newGroupDialog?.let { tab ->
        AlertDialog(
            onDismissRequest = { newGroupDialog = null },
            title = { Text("New tab group") },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("Group name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newGroupName.isNotBlank(),
                    onClick = {
                        onGroupTabs(listOf(tab.id), newGroupName.trim(), groupColors.random())
                        newGroupDialog = null
                    }
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newGroupDialog = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SegTab(
    label: String,
    icon: ImageVector,
    count: Int,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) accent.copy(alpha = 0.22f) else Color.Transparent,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = if (selected) accent else Color.White.copy(0.6f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "$label ($count)",
                color = if (selected) Color.White else Color.White.copy(0.6f),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TabCard(
    tab: Tab,
    isActive: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    val scale by animateFloatAsState(if (isActive) 1f else 0.98f, spring(stiffness = Spring.StiffnessMedium), label = "tc")
    val groupColor = tab.groupColor?.let { Color(it) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .scale(scale)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.05f),
        border = BorderStroke(
            if (isActive) 2.dp else 1.dp,
            if (isActive) accent else (groupColor ?: Color.White.copy(0.1f))
        )
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            if (groupColor != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(groupColor, RoundedCornerShape(50)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tab.groupName ?: "Group",
                        style = MaterialTheme.typography.labelSmall,
                        color = groupColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (tab.isIncognito) Icons.Default.VisibilityOff else Icons.Default.Public,
                    null, tint = accent, modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    tab.title.ifEmpty { "New tab" },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier.size(22.dp).clip(RoundedCornerShape(50)).clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White.copy(0.6f), modifier = Modifier.size(15.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    tab.url.removePrefix("https://").removePrefix("http://").ifBlank { "about:blank" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(0.45f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}
