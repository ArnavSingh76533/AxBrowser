package com.akay.core.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class OpenRouterModel(
    val id: String,
    val name: String,
    val contextLength: Int
)

data class ChatTurn(val role: String, val content: String)

/**
 * Thin OpenAI-compatible client for OpenRouter (https://openrouter.ai). Only
 * plain chat completions are used - tool/function calling support varies a
 * lot across OpenRouter's free-tier models, so the agent feature drives
 * models with a plain-text ReAct-style prompt instead of relying on native
 * function calling (see AgentEngine).
 */
@Singleton
class OpenRouterClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun chat(apiKey: String, model: String, messages: List<ChatTurn>, temperature: Double = 0.4): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val messagesJson = JSONArray().apply {
                    messages.forEach { turn ->
                        put(JSONObject().apply { put("role", turn.role); put("content", turn.content) })
                    }
                }
                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messagesJson)
                    put("temperature", temperature)
                }.toString().toRequestBody(jsonMedia)

                val request = Request.Builder()
                    .url("https://openrouter.ai/api/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("HTTP-Referer", "https://github.com/akborana3/AxBrowser")
                    .addHeader("X-Title", "AxBrowser")
                    .post(body)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // Always surface something concrete: the API's own error message when it
                        // parses as JSON, otherwise the raw body itself (truncated) - never just a
                        // bare status code, since that alone rarely explains WHY it failed (bad key,
                        // rate limit, model unavailable, moderation, invalid params, ...).
                        val errMsg = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
                            ?.takeIf { it.isNotBlank() }
                        val bodySnippet = raw.take(500).ifBlank { "(empty response body)" }
                        error("OpenRouter HTTP ${response.code}: ${errMsg ?: bodySnippet}")
                    }
                    val json = runCatching { JSONObject(raw) }.getOrElse {
                        error("OpenRouter returned a non-JSON response (HTTP ${response.code}): ${raw.take(500).ifBlank { "(empty body)" }}")
                    }
                    val choices = json.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) {
                        // Distinguish "no choices key at all" from "choices was empty" and show the
                        // raw JSON either way - this used to just say "Empty response from model"
                        // with no way to tell if it was a moderation block, a truncation, etc.
                        val errInBody = json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                        error(errInBody ?: "OpenRouter returned no choices. Raw response: ${raw.take(500)}")
                    }
                    val message = choices.getJSONObject(0).optJSONObject("message")
                        ?: error("OpenRouter response was missing a message object. Raw response: ${raw.take(500)}")
                    val content = message.optString("content").trim()
                    if (content.isBlank()) {
                        val finishReason = choices.getJSONObject(0).optString("finish_reason").ifBlank { "unknown" }
                        error("Model returned an empty message (finish_reason: $finishReason). Raw response: ${raw.take(500)}")
                    }
                    content
                }
            }.recoverCatching { throwable ->
                // Give network/IO failures (timeout, no connection, DNS, TLS, ...) a clearer label
                // too, since okhttp exception messages alone (e.g. "timeout") are easy to misread as
                // "the model" failing rather than the network call itself.
                if (throwable.message?.startsWith("OpenRouter") == true || throwable.message?.startsWith("Model returned") == true) {
                    throw throwable
                }
                throw IllegalStateException("Network error contacting OpenRouter (${throwable::class.simpleName}): ${throwable.message ?: "no details"}", throwable)
            }
        }

    /** Lists models with a $0 prompt/completion price - i.e. OpenRouter's free tier. */
    suspend fun fetchFreeModels(apiKey: String? = null): Result<List<OpenRouterModel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val requestBuilder = Request.Builder().url("https://openrouter.ai/api/v1/models")
                if (!apiKey.isNullOrBlank()) requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) error("Failed to load models (${response.code})")
                    val raw = response.body?.string().orEmpty()
                    val data = JSONObject(raw).optJSONArray("data") ?: JSONArray()
                    (0 until data.length()).mapNotNull { i ->
                        val m = data.getJSONObject(i)
                        val pricing = m.optJSONObject("pricing")
                        val prompt = pricing?.optString("prompt")?.toDoubleOrNull() ?: 1.0
                        val completion = pricing?.optString("completion")?.toDoubleOrNull() ?: 1.0
                        val id = m.optString("id")
                        if ((prompt == 0.0 && completion == 0.0) || id.endsWith(":free")) {
                            OpenRouterModel(
                                id = id,
                                name = m.optString("name", id),
                                contextLength = m.optInt("context_length", 0)
                            )
                        } else null
                    }.sortedBy { it.name }
                }
            }
        }
}
