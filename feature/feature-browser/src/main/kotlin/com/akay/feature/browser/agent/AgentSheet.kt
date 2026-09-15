package com.akay.feature.browser.agent

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
    baseUrl: String = "https://openrouter.ai/api/v1",
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
        controller.send(goal, apiKey.orEmpty(), model, baseUrl) {
            scope.launch {
                if (controller.turns.isNotEmpty()) listState.animateScrollToItem(controller.turns.size - 1)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onMinimize, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp, max = 640.dp).padding(horizontal = 16.dp)) {
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
                if (controller.turns.isEmpty()) {
                    Text(
                        "Try: \u201COpen YouTube and search lofi hip hop, then download the first result\u201D or \u201CDownload this video\u201D while one is open. It can also read network requests and scrape the page for tricky sites.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                // The agent's plan, shown above the conversation: a multi-step job (log in ->
                // enumerate -> fuzz -> report) then reads as visible progress rather than a hang.
                if (controller.todo.items.isNotEmpty()) {
                    AgentTodoPanel(controller.todo)
                    Spacer(Modifier.height(8.dp))
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(controller.turns) { turn ->
                        AgentTurnView(turn)
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
                    if (controller.isRunning) {
                        IconButton(onClick = { controller.stop() }) {
                            Icon(Icons.Default.Stop, "Stop", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(onClick = { send() }, enabled = input.isNotBlank()) {
                            Icon(Icons.Default.Send, "Send", tint = Primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentTurnView(turn: AgentTurn) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // User message
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Primary.copy(alpha = 0.85f),
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Text(
                    turn.userMessage,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
        }

        if (turn.steps.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            ThinkingPanel(turn)
        }

        if (turn.finalText != null || turn.isRunning) {
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                if (turn.finalText != null) {
                    val bubbleContentColor = if (turn.isError) MaterialTheme.colorScheme.onErrorContainer else LocalContentColor.current
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (turn.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(0.92f)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                CopyIconButton(textToCopy = turn.finalText.orEmpty(), tint = bubbleContentColor)
                            }
                            AgentMarkdown(text = turn.finalText.orEmpty(), contentColor = bubbleContentColor)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Working\u2026", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Collapsed-by-default reasoning trace, matching ChatGPT/Gemini's "Thinking"
 * disclosure: a tappable header with a chevron that smoothly expands to
 * reveal every thought/tool-call/observation for this turn.
 */
@Composable
private fun ThinkingPanel(turn: AgentTurn) {
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.widthIn(max = 280.dp).animateContentSize()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Lightbulb, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (turn.isRunning && turn.finalText == null) "Thinking\u2026" else "Thinking",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${turn.steps.size} step${if (turn.steps.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.ExpandMore, null,
                    modifier = Modifier.size(16.dp).rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibilityColumn(visible = expanded) {
                Column(modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                    turn.steps.forEach { step ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            val icon = when (step.kind) {
                                StepKind.THOUGHT -> Icons.Default.Lightbulb
                                StepKind.TOOL_CALL -> Icons.Default.Bolt
                                StepKind.TOOL_RESULT -> Icons.Default.SubdirectoryArrowRight
                            }
                            Icon(icon, null, modifier = Modifier.size(12.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                step.text,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedVisibilityColumn(visible: Boolean, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        content()
    }
}

/**
 * Live task list the agent maintains through its todo_write / todo_read tools. Kept outside the
 * scrollable transcript on purpose - the plan stays visible while the agent works through it.
 */
@Composable
private fun AgentTodoPanel(todo: AgentTodoList) {
    val items = todo.items
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Checklist, null, modifier = Modifier.size(14.dp), tint = Primary)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Plan",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${todo.doneCount}/${todo.total} done",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            items.forEach { item ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    val done = item.status == TodoStatus.DONE
                    Icon(
                        if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        null,
                        modifier = Modifier.size(14.dp).padding(top = 2.dp),
                        tint = when (item.status) {
                            TodoStatus.DONE -> Color(0xFF4CAF50)
                            TodoStatus.IN_PROGRESS -> Primary
                            TodoStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        item.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current,
                        textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                    )
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
