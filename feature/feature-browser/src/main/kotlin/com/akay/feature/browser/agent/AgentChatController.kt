package com.akay.feature.browser.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.akay.core.data.ai.OpenRouterClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class AgentChatBubble(
    val text: String,
    val isUser: Boolean,
    val isSystemNote: Boolean = false
)

/**
 * Owns the agent conversation independently of whether the chat sheet is
 * currently shown on screen, so minimizing it (swiping down, tapping
 * outside) never loses history or interrupts an in-flight task - only
 * "New chat" resets it.
 */
class AgentChatController(
    private val scope: CoroutineScope,
    private val openRouterClient: OpenRouterClient,
    private val tools: AgentToolExecutor
) {
    val bubbles = mutableStateListOf<AgentChatBubble>()

    var isRunning by mutableStateOf(false)
        private set

    var hasSession by mutableStateOf(false)
        private set

    private var engine: AgentEngine? = null

    fun send(goal: String, apiKey: String, model: String, onUpdated: () -> Unit = {}) {
        val trimmed = goal.trim()
        if (trimmed.isBlank() || isRunning || apiKey.isBlank()) return
        hasSession = true
        bubbles.add(AgentChatBubble(trimmed, isUser = true))
        isRunning = true
        onUpdated()
        scope.launch {
            val activeEngine = engine ?: AgentEngine(openRouterClient, apiKey, model, tools).also { engine = it }
            activeEngine.run(trimmed) { event ->
                val bubble = when (event) {
                    is AgentEvent.Thinking -> AgentChatBubble("\uD83D\uDCAD ${event.thought}", isUser = false, isSystemNote = true)
                    is AgentEvent.ToolCall -> AgentChatBubble("\u2699\uFE0F ${event.action}(${event.input})", isUser = false, isSystemNote = true)
                    is AgentEvent.ToolResult -> AgentChatBubble("\u2192 ${event.observation.take(300)}", isUser = false, isSystemNote = true)
                    is AgentEvent.FinalAnswer -> AgentChatBubble(event.text, isUser = false)
                    is AgentEvent.Error -> AgentChatBubble("\u26A0\uFE0F ${event.message}", isUser = false)
                }
                bubbles.add(bubble)
                onUpdated()
            }
            isRunning = false
            onUpdated()
        }
    }

    fun newChat() {
        bubbles.clear()
        engine = null
        hasSession = false
    }
}
