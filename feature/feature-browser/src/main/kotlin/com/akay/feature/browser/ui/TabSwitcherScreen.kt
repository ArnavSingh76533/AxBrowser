package com.akay.feature.browser.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.akay.core.domain.model.Tab
import com.akay.core.ui.components.GalaxyBackground
import com.akay.core.ui.theme.LocalAccentColor

private val DEFAULT_GROUP_PALETTE = listOf(0xFFB388FF, 0xFF80D8FF, 0xFFFF8A80, 0xFFFFD180, 0xFFA7FFEB, 0xFFCCFF90, 0xFFFF80AB, 0xFF82B1FF)

private sealed class GridEntry(val key: String) {
    data class Single(val tab: Tab) : GridEntry(tab.id)
    data class Collapsed(val groupId: String, val name: String, val color: Int, val tabs: List<Tab>) : GridEntry("group:$groupId")
    data class Header(val groupId: String, val name: String, val color: Int, val count: Int) : GridEntry("header:$groupId")
}

/**
 * A Chrome-style tab grid: ungrouped tabs render as normal cards, grouped
 * tabs collapse into a single "stacked windows" card (fanned mini previews,
 * matching group color, tap to expand/collapse). Long-press and drag any
 * tab card onto another tab or a group stack to merge them - dropping two
 * ungrouped tabs together spins up a brand-new auto-named, auto-colored
 * group, exactly like Chrome's mobile tab groups.
 */
@Composable
fun TabSwitcherOverlay(
    tabs: List<Tab>,
    activeTabId: String?,
    onTabClick: (Tab) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: (incognito: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    groupColors: List<Long> = DEFAULT_GROUP_PALETTE,
    onGroupTabs: (tabIds: List<String>, name: String, color: Long) -> Unit = { _, _, _ -> },
    onAddToGroup: (tabId: String, groupId: String, name: String, color: Int) -> Unit = { _, _, _, _ -> },
    onRemoveFromGroup: (tabId: String) -> Unit = {},
    onRenameGroup: (groupId: String, newName: String) -> Unit = { _, _ -> },
    onUngroupAll: (groupId: String) -> Unit = {},
    onCloseGroup: (groupId: String) -> Unit = {}
) {
    val accent = LocalAccentColor.current
    val haptic = LocalHapticFeedback.current
    var showPrivate by remember { mutableStateOf(false) }
    val normalTabs = tabs.filter { !it.isIncognito }
    val privateTabs = tabs.filter { it.isIncognito }
    val shown = if (showPrivate) privateTabs else normalTabs

    var expandedGroups by remember { mutableStateOf(setOf<String>()) }
    var groupOptionsFor by remember { mutableStateOf<String?>(null) }
    var renameDialogGroup by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    // ---- Build the display list: ordered by first appearance, grouped tabs collapsed unless expanded ----
    val entries = remember(shown, expandedGroups) {
        buildList {
            val seenGroups = mutableSetOf<String>()
            shown.forEach { tab ->
                val gid = tab.groupId
                when {
                    gid == null -> add(GridEntry.Single(tab))
                    gid in seenGroups -> {
                        if (gid in expandedGroups) add(GridEntry.Single(tab))
                    }
                    else -> {
                        seenGroups += gid
                        val members = shown.filter { it.groupId == gid }
                        val color = tab.groupColor ?: DEFAULT_GROUP_PALETTE.first().toInt()
                        val name = tab.groupName ?: "Group"
                        if (gid in expandedGroups) {
                            add(GridEntry.Header(gid, name, color, members.size))
                            add(GridEntry.Single(tab))
                        } else {
                            add(GridEntry.Collapsed(gid, name, color, members))
                        }
                    }
                }
            }
        }
    }

    val existingGroupCount = shown.mapNotNull { it.groupId }.distinct().size

    // ---- Drag & drop state ----
    val boundsMap = remember { mutableStateMapOf<String, Rect>() }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dropTargetKey by remember { mutableStateOf<String?>(null) }

    fun performDrop(sourceTabId: String, targetKey: String) {
        val sourceTab = shown.firstOrNull { it.id == sourceTabId } ?: return
        when {
            targetKey.startsWith("group:") || targetKey.startsWith("header:") -> {
                val gid = targetKey.substringAfter(":")
                val groupTab = shown.firstOrNull { it.groupId == gid } ?: return
                if (sourceTab.groupId == gid) return
                onAddToGroup(sourceTab.id, gid, groupTab.groupName ?: "Group", groupTab.groupColor ?: DEFAULT_GROUP_PALETTE.first().toInt())
            }
            else -> {
                val targetTab = shown.firstOrNull { it.id == targetKey } ?: return
                if (targetTab.id == sourceTab.id) return
                if (targetTab.groupId != null) {
                    if (sourceTab.groupId == targetTab.groupId) return
                    onAddToGroup(sourceTab.id, targetTab.groupId, targetTab.groupName ?: "Group", targetTab.groupColor ?: DEFAULT_GROUP_PALETTE.first().toInt())
                } else {
                    val color = groupColors[existingGroupCount % groupColors.size]
                    onGroupTabs(listOf(targetTab.id, sourceTab.id), "Group ${existingGroupCount + 1}", color)
                }
            }
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    GalaxyBackground(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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

            if (!showPrivate && normalTabs.size > 1) {
                Text(
                    "Tip: press and hold a tab, then drag it onto another to group them",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(0.35f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

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
                    items(entries, key = { it.key }, span = { entry ->
                        if (entry is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                    }) { entry ->
                        val isDropTarget = dropTargetKey == entry.key && draggingKey != entry.key
                        when (entry) {
                            is GridEntry.Header -> GroupHeaderRow(
                                name = entry.name,
                                color = Color(entry.color),
                                count = entry.count,
                                highlighted = isDropTarget,
                                onCollapse = { expandedGroups = expandedGroups - entry.groupId },
                                onOptions = { groupOptionsFor = entry.groupId }
                            )

                            is GridEntry.Collapsed -> GroupStackCard(
                                name = entry.name,
                                color = Color(entry.color),
                                members = entry.tabs,
                                highlighted = isDropTarget,
                                modifier = Modifier
                                    .onGloballyPositioned { boundsMap[entry.key] = it.boundsInWindow() }
                                    .zIndex(if (isDropTarget) 0.5f else 0f),
                                onClick = { expandedGroups = expandedGroups + entry.groupId },
                                onOptions = { groupOptionsFor = entry.groupId }
                            )

                            is GridEntry.Single -> {
                                val tab = entry.tab
                                val isDragging = draggingKey == entry.key
                                Box(
                                    modifier = Modifier
                                        .onGloballyPositioned { boundsMap[entry.key] = it.boundsInWindow() }
                                        .graphicsLayer {
                                            if (isDragging) {
                                                translationX = dragOffset.x
                                                translationY = dragOffset.y
                                                scaleX = 1.06f; scaleY = 1.06f
                                                shadowElevation = 32f
                                                alpha = 0.96f
                                            }
                                        }
                                        .zIndex(if (isDragging) 1f else 0f)
                                        .pointerInputDrag(
                                            key = entry.key,
                                            onDragStart = {
                                                draggingKey = entry.key
                                                dragOffset = Offset.Zero
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDrag = { amount ->
                                                dragOffset += amount
                                                val startCenter = boundsMap[entry.key]?.center ?: Offset.Zero
                                                val currentPos = startCenter + dragOffset
                                                dropTargetKey = boundsMap.entries
                                                    .filter { it.key != entry.key }
                                                    .firstOrNull { it.value.contains(currentPos) }?.key
                                            },
                                            onDragEnd = {
                                                dropTargetKey?.let { performDrop(tab.id, it) }
                                                draggingKey = null
                                                dragOffset = Offset.Zero
                                                dropTargetKey = null
                                            },
                                            onDragCancel = {
                                                draggingKey = null
                                                dragOffset = Offset.Zero
                                                dropTargetKey = null
                                            }
                                        )
                                ) {
                                    TabCard(
                                        tab = tab,
                                        isActive = tab.id == activeTabId,
                                        accent = accent,
                                        highlighted = isDropTarget,
                                        showGroupChip = false,
                                        onClick = { if (draggingKey == null) onTabClick(tab) },
                                        onClose = { onCloseTab(tab.id) },
                                        onUngroup = if (tab.groupId != null) ({ onRemoveFromGroup(tab.id) }) else null
                                    )
                                }
                            }
                        }
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

    groupOptionsFor?.let { groupId ->
        val groupTab = shown.firstOrNull { it.groupId == groupId }
        AlertDialog(
            onDismissRequest = { groupOptionsFor = null },
            title = { Text(groupTab?.groupName ?: "Group") },
            text = {
                Column {
                    TextButton(onClick = {
                        renameText = groupTab?.groupName ?: ""
                        renameDialogGroup = groupId
                        groupOptionsFor = null
                    }) { Text("Rename group") }
                    TextButton(onClick = {
                        onUngroupAll(groupId)
                        groupOptionsFor = null
                    }) { Text("Ungroup all tabs") }
                    TextButton(onClick = {
                        onCloseGroup(groupId)
                        groupOptionsFor = null
                    }) { Text("Close group", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { groupOptionsFor = null }) { Text("Cancel") }
            }
        )
    }

    renameDialogGroup?.let { groupId ->
        AlertDialog(
            onDismissRequest = { renameDialogGroup = null },
            title = { Text("Rename group") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Group name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        onRenameGroup(groupId, renameText.trim())
                        renameDialogGroup = null
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameDialogGroup = null }) { Text("Cancel") } }
        )
    }
}

/** Small helper so the drag pointerInput block reads cleanly at the call site above. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.pointerInputDrag(
    key: Any,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
): Modifier = this.then(
    Modifier.pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { onDragStart() },
            onDrag = { change, amount -> change.consume(); onDrag(amount) },
            onDragEnd = { onDragEnd() },
            onDragCancel = { onDragCancel() }
        )
    }
)

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

/** The full-width pill shown above an expanded group's member tabs. */
@Composable
private fun GroupHeaderRow(
    name: String,
    color: Color,
    count: Int,
    highlighted: Boolean,
    onCollapse: () -> Unit,
    onOptions: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = if (highlighted) 0.35f else 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).clickable { onCollapse() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(10.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text("$count", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(0.6f))
            IconButton(onClick = onOptions, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.MoreVert, "Group options", tint = Color.White.copy(0.7f), modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onCollapse, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.ExpandLess, "Collapse", tint = Color.White.copy(0.7f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * A collapsed group: rendered as a real fanned stack of mini browser-window
 * silhouettes (the "really show two windows" look) rather than just a label,
 * so it visually reads as a folder of tabs the way Chrome's group tiles do.
 */
@Composable
private fun GroupStackCard(
    name: String,
    color: Color,
    members: List<Tab>,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onOptions: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
    ) {
        // Back window (furthest, most rotated/offset) - only when there's a real 3rd+ tab behind
        if (members.size > 2) {
            MiniWindow(
                color = color.copy(alpha = 0.35f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .fillMaxWidth(0.86f)
                    .height(110.dp)
                    .rotate(-4f)
            )
        }
        // Middle window - always shown when there are 2+ tabs, this IS the "second window"
        if (members.size > 1) {
            MiniWindow(
                color = color.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 7.dp)
                    .fillMaxWidth(0.93f)
                    .height(118.dp)
                    .rotate(3f)
            )
        }
        // Front window with real content
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1B1626),
            border = BorderStroke(if (highlighted) 2.5.dp else 1.5.dp, if (highlighted) color else color.copy(alpha = 0.85f)),
            modifier = Modifier.fillMaxWidth().height(126.dp).align(Alignment.BottomCenter)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(color.copy(alpha = 0.25f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onOptions, modifier = Modifier.size(22.dp)) {
                        Icon(Icons.Default.MoreVert, "Group options", tint = Color.White.copy(0.7f), modifier = Modifier.size(14.dp))
                    }
                }
                Column(modifier = Modifier.padding(10.dp)) {
                    members.take(2).forEach { tab ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Icon(
                                if (tab.isIncognito) Icons.Default.VisibilityOff else Icons.Default.Language,
                                null, tint = Color.White.copy(0.5f), modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                tab.title.ifEmpty { "New tab" },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (members.size > 2) {
                        Text(
                            "+${members.size - 2} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniWindow(color: Color, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(14.dp), color = color, modifier = modifier) {}
}

@Composable
fun TabCard(
    tab: Tab,
    isActive: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onClose: () -> Unit,
    highlighted: Boolean = false,
    showGroupChip: Boolean = true,
    onUngroup: (() -> Unit)? = null
) {
    val scale by animateFloatAsState(if (isActive) 1f else 0.98f, spring(stiffness = Spring.StiffnessMedium), label = "tc")
    val groupColor = tab.groupColor?.let { Color(it) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .scale(scale)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (highlighted) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f),
        border = BorderStroke(
            if (isActive || highlighted) 2.dp else 1.dp,
            if (highlighted) accent else if (isActive) accent else (groupColor ?: Color.White.copy(0.1f))
        )
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            if (showGroupChip && groupColor != null) {
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
                if (onUngroup != null) {
                    Box(
                        modifier = Modifier.size(20.dp).clip(RoundedCornerShape(50)).clickable { onUngroup() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ExpandMore, "Remove from group", tint = Color.White.copy(0.5f), modifier = Modifier.size(13.dp))
                    }
                    Spacer(Modifier.width(2.dp))
                }
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
