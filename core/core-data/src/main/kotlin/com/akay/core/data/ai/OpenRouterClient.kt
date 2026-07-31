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
                        val errMsg = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
                        error(errMsg ?: "OpenRouter error ${response.code}")
                    }
                    val json = JSONObject(raw)
                    val choices = json.optJSONArray("choices") ?: error("Empty response from model")
                    if (choices.length() == 0) error("Empty response from model")
                    choices.getJSONObject(0).getJSONObject("message").optString("content").trim()
                }
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
