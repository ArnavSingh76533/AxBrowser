package com.akay.feature.browser.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import com.akay.core.data.ai.OpenRouterClient
import com.akay.core.ui.theme.Primary
import kotlinx.coroutines.launch

private data class ChatBubble(
    val text: String,
    val isUser: Boolean,
    val isSystemNote: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSheet(
    onDismiss: () -> Unit,
    openRouterClient: OpenRouterClient,
    apiKey: String?,
    model: String,
    tools: AgentToolExecutor,
    onOpenSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val bubbles = remember { mutableStateListOf<ChatBubble>() }
    var input by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun send() {
        val goal = input.trim()
        if (goal.isBlank() || isRunning || apiKey.isNullOrBlank()) return
        input = ""
        bubbles.add(ChatBubble(goal, isUser = true))
        isRunning = true
        scope.launch {
            val engine = AgentEngine(openRouterClient, apiKey, model, tools)
            engine.run(goal) { event ->
                when (event) {
                    is AgentEvent.Thinking -> bubbles.add(ChatBubble("\uD83D\uDCAD ${event.thought}", isUser = false, isSystemNote = true))
                    is AgentEvent.ToolCall -> bubbles.add(ChatBubble("\u2699\uFE0F ${event.action}(${event.input})", isUser = false, isSystemNote = true))
                    is AgentEvent.ToolResult -> bubbles.add(ChatBubble("\u2192 ${event.observation.take(300)}", isUser = false, isSystemNote = true))
                    is AgentEvent.FinalAnswer -> bubbles.add(ChatBubble(event.text, isUser = false))
                    is AgentEvent.Error -> bubbles.add(ChatBubble("\u26A0\uFE0F ${event.message}", isUser = false))
                }
                if (bubbles.isNotEmpty()) listState.animateScrollToItem(bubbles.size - 1)
            }
            isRunning = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp, max = 620.dp).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Icon(Icons.Default.AutoAwesome, null, tint = Primary)
                Spacer(Modifier.width(8.dp))
                Text("AI Agent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
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
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onOpenSettings) { Text("Open Settings") }
                }
            } else {
                if (bubbles.isEmpty()) {
                    Text(
                        "Try: \u201COpen YouTube and search lofi hip hop, then download the first result\u201D or \u201CSummarize this page\u201D",
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
                    items(bubbles) { bubble ->
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
                    if (isRunning) {
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
                        enabled = !isRunning,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() })
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { send() }, enabled = !isRunning && input.isNotBlank()) {
                        Icon(Icons.Default.Send, "Send", tint = Primary)
                    }
                }
            }
        }
    }
}
