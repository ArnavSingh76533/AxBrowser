package com.akay.feature.browser.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.akay.core.ui.theme.Primary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSheet(
    controller: AgentChatController,
    apiKey: String?,
    model: String,
    onMinimize: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    // Intercept the sheet's own hide transition (swipe-down / scrim tap) and
    // turn it into a minimize instead of letting Compose fully tear it down -
    // the conversation lives in `controller`, outside this composable, so
    // nothing is lost either way, but we still want the "still active" chip
    // to appear rather than the sheet just vanishing without a trace.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            if (value == SheetValue.Hidden) {
                onMinimize()
                false
            } else true
        }
    )

    fun send() {
        val goal = input
        input = ""
        controller.send(goal, apiKey.orEmpty(), model) {
            scope.launch {
                if (controller.bubbles.isNotEmpty()) listState.animateScrollToItem(controller.bubbles.size - 1)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onMinimize, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp, max = 620.dp).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Icon(Icons.Default.AutoAwesome, null, tint = Primary)
                Spacer(Modifier.width(8.dp))
                Text("AI Agent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { controller.newChat() }, enabled = !controller.isRunning) {
                    Icon(Icons.Default.AddComment, "New chat")
                }
                IconButton(onClick = onMinimize) { Icon(Icons.Default.Close, "Close") }
            }

            if (apiKey.isNullOrBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Add a free OpenRouter API key in Settings to use the AI agent.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onOpenSettings) { Text("Open Settings") }
                }
            } else {
                if (controller.bubbles.isEmpty()) {
                    Text(
                        "Try: \u201COpen YouTube and search lofi hip hop, then download the first result\u201D or \u201CDownload this video\u201D while one is open",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(controller.bubbles) { bubble ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (bubble.isUser) Arrangement.End else Arrangement.Start
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = when {
                                    bubble.isUser -> Primary.copy(alpha = 0.85f)
                                    bubble.isSystemNote -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                modifier = Modifier.widthIn(max = 280.dp)
                            ) {
                                Text(
                                    bubble.text,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = if (bubble.isSystemNote) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                                    color = if (bubble.isUser) Color.White else LocalContentColor.current
                                )
                            }
                        }
                    }
                    if (controller.isRunning) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Working\u2026", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask the agent to do something\u2026") },
                        singleLine = true,
                        enabled = !controller.isRunning,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() })
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { send() }, enabled = !controller.isRunning && input.isNotBlank()) {
                        Icon(Icons.Default.Send, "Send", tint = Primary)
                    }
                }
            }
        }
    }
}

/** Small floating chip shown when the agent has an active conversation but the sheet is minimized. */
@Composable
fun AgentMinimizedChip(isRunning: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = Primary,
        shadowElevation = 6.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRunning) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (isRunning) "Agent working\u2026" else "Agent chat active",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
