package com.akay.feature.browser.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.akay.core.data.ai.OpenRouterClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class StepKind { THOUGHT, TOOL_CALL, TOOL_RESULT }

data class AgentStep(val kind: StepKind, val text: String)

/**
 * One user request and everything the agent did to answer it. The
 * step-by-step reasoning/tool trace lives in [steps] and is rendered behind
 * a collapsed-by-default "Thinking" panel; [finalText] is the one line that
 * always shows, like ChatGPT/Gemini's summary-vs-detail split.
 */
class AgentTurn(val userMessage: String) {
    val steps = mutableStateListOf<AgentStep>()
    var finalText by mutableStateOf<String?>(null)
    var isError by mutableStateOf(false)
    var isRunning by mutableStateOf(true)
}

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
    val turns = mutableStateListOf<AgentTurn>()

    var isRunning by mutableStateOf(false)
        private set

    var hasSession by mutableStateOf(false)
        private set

    private var engine: AgentEngine? = null

    fun send(goal: String, apiKey: String, model: String, onUpdated: () -> Unit = {}) {
        val trimmed = goal.trim()
        if (trimmed.isBlank() || isRunning || apiKey.isBlank()) return
        hasSession = true
        val turn = AgentTurn(trimmed)
        turns.add(turn)
        isRunning = true
        onUpdated()
        scope.launch {
            val activeEngine = engine ?: AgentEngine(openRouterClient, apiKey, model, tools).also { engine = it }
            activeEngine.run(trimmed) { event ->
                when (event) {
                    is AgentEvent.Thinking -> turn.steps.add(AgentStep(StepKind.THOUGHT, event.thought))
                    is AgentEvent.ToolCall -> turn.steps.add(AgentStep(StepKind.TOOL_CALL, "${event.action}(${event.input})"))
                    is AgentEvent.ToolResult -> turn.steps.add(AgentStep(StepKind.TOOL_RESULT, event.observation.take(400)))
                    is AgentEvent.FinalAnswer -> turn.finalText = event.text
                    is AgentEvent.Error -> {
                        turn.finalText = event.message
                        turn.isError = true
                    }
                }
                onUpdated()
            }
            if (turn.finalText == null) {
                turn.finalText = "Stopped without a final answer."
                turn.isError = true
            }
            turn.isRunning = false
            isRunning = false
            onUpdated()
        }
    }

    fun newChat() {
        turns.clear()
        engine = null
        hasSession = false
    }
}
