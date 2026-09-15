package com.akay.feature.browser.agent

import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/** pending -> in_progress -> done, matching the plan vocabulary models actually use. */
enum class TodoStatus {
    PENDING, IN_PROGRESS, DONE;

    companion object {
        fun from(raw: String?): TodoStatus = when (raw?.trim()?.lowercase()) {
            "in_progress", "in-progress", "inprogress", "doing", "active", "started" -> IN_PROGRESS
            "done", "complete", "completed", "finished", "closed" -> DONE
            else -> PENDING
        }
    }
}

data class AgentTodo(val id: Int, val text: String, val status: TodoStatus)

/**
 * The agent's working plan for the current chat session.
 *
 * AgentEngine owns no UI, so the list lives here and is shared: the executor's
 * `todo_write` / `todo_read` tools read and replace it, the agent sheet renders it as a live
 * checklist, and the engine prompt instructs the model to keep it current. That is what turns
 * a 25-step pentest run from "a model thrashing" into a plan the operator can watch progress
 * against - and it survives across turns of the same session, because the engine (and this
 * list) are cached by AgentChatController rather than rebuilt per message.
 */
class AgentTodoList {

    val items = mutableStateListOf<AgentTodo>()

    val total: Int get() = items.size
    val doneCount: Int get() = items.count { it.status == TodoStatus.DONE }

    /** Replaces the whole plan. [entries] are (text, status) pairs straight from the model. */
    fun replace(entries: List<Pair<String, String>>): String {
        items.clear()
        entries.forEach { (text, status) ->
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                items.add(AgentTodo(items.size + 1, trimmed, TodoStatus.from(status)))
            }
        }
        return render()
    }

    /** Flips a single item, matched by 1-based index or by a substring of its text. */
    fun mark(match: String, status: TodoStatus): Boolean {
        val asIndex = match.trim().toIntOrNull()
        val target = if (asIndex != null) {
            items.firstOrNull { it.id == asIndex }
        } else {
            items.firstOrNull { it.text.contains(match.trim(), ignoreCase = true) }
        } ?: return false
        val idx = items.indexOf(target)
        if (idx < 0) return false
        items[idx] = target.copy(status = status)
        return true
    }

    fun clear() = items.clear()

    fun render(): String {
        if (items.isEmpty()) return "Task list is empty (nothing planned yet)."
        val body = items.joinToString("\n") { "${marker(it.status)} ${it.id}. ${it.text}" }
        return "Plan ($doneCount/$total done):\n$body"
    }

    fun toJson(): String {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("status", item.status.name.lowercase())
            })
        }
        return arr.toString()
    }

    private fun marker(status: TodoStatus) = when (status) {
        TodoStatus.DONE -> "[x]"
        TodoStatus.IN_PROGRESS -> "[>]"
        TodoStatus.PENDING -> "[ ]"
    }
}
