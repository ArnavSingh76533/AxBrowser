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

/** Suspends until the call completes, but - unlike plain .execute() - actually responds to
 *  coroutine cancellation by cancelling the underlying OkHttp call (which closes the socket),
 *  instead of leaving a blocking call running until it naturally times out. This is what makes
 *  the agent chat's Stop button actually abort a hung request instead of just hiding it. */
private suspend fun okhttp3.Call.await(): okhttp3.Response = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    enqueue(object : okhttp3.Callback {
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            if (cont.isActive) cont.resume(response) { _, _, _ -> response.close() } else response.close()
        }
        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            if (!call.isCanceled() && cont.isActive) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

data class OpenRouterModel(
    val id: String,
    val name: String,
    val contextLength: Int
)

data class ChatTurn(val role: String, val content: String)

/** A model reply plus token usage for this one call, when OpenRouter reports it (not all
 *  providers behind OpenRouter include `usage` on every response, so these default to 0). */
data class ChatCompletionResult(
    val content: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0
)

/**
 * Thin OpenAI-compatible client for OpenRouter (https://openrouter.ai). Only
 * plain chat completions are used - tool/function calling support varies a
 * lot across OpenRouter's free-tier models, so the agent feature drives
 * models with a plain-text ReAct-style prompt instead of relying on native
 * function calling (see AgentEngine).
 */
@Singleton
class OpenRouterClient @Inject constructor(
    sharedOkHttpClient: OkHttpClient
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // The app-wide shared client uses a snappy 30s timeout everywhere, which is right for page
    // loads/downloads (fail fast on a dead server) but wrong here: a custom/self-hosted OpenAI-
    // compatible endpoint can legitimately take much longer to even establish a connection
    // (slower infra, waking from cold start) and a model's actual generation - especially longer
    // agent runs or reasoning models - can run well past 30s of read time. Deriving a
    // longer-timeout client here (rather than raising the global 30s everywhere) keeps ordinary
    // browsing snappy while giving slow model backends the room they actually need.
    private val okHttpClient: OkHttpClient = sharedOkHttpClient.newBuilder()
        .connectTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun chat(
        apiKey: String,
        model: String,
        messages: List<ChatTurn>,
        temperature: Double = 0.4,
        baseUrl: String = "https://openrouter.ai/api/v1"
    ): Result<ChatCompletionResult> =
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
                    .url("${baseUrl.trimEnd('/')}/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    // Harmless on non-OpenRouter endpoints - they just ignore headers they don't
                    // recognize, same as any other OpenAI-compatible server would.
                    .addHeader("HTTP-Referer", "https://github.com/akborana3/AxBrowser")
                    .addHeader("X-Title", "AxBrowser")
                    .post(body)
                    .build()

                okHttpClient.newCall(request).await().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // Always surface something concrete: the API's own error message when it
                        // parses as JSON, otherwise the raw body itself (truncated) - never just a
                        // bare status code, since that alone rarely explains WHY it failed (bad key,
                        // rate limit, model unavailable, moderation, invalid params, ...).
                        val errMsg = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
                            ?.takeIf { it.isNotBlank() }
                        val bodySnippet = raw.take(500).ifBlank { "(empty response body)" }
                        error("Model API HTTP ${response.code}: ${errMsg ?: bodySnippet}")
                    }
                    val json = runCatching { JSONObject(raw) }.getOrElse {
                        error("Model API returned a non-JSON response (HTTP ${response.code}): ${raw.take(500).ifBlank { "(empty body)" }}")
                    }
                    val choices = json.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) {
                        // Distinguish "no choices key at all" from "choices was empty" and show the
                        // raw JSON either way - this used to just say "Empty response from model"
                        // with no way to tell if it was a moderation block, a truncation, etc.
                        val errInBody = json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                        error(errInBody ?: "Model API returned no choices. Raw response: ${raw.take(500)}")
                    }
                    val message = choices.getJSONObject(0).optJSONObject("message")
                        ?: error("Model API response was missing a message object. Raw response: ${raw.take(500)}")
                    val content = message.optString("content").trim()
                    if (content.isBlank()) {
                        val finishReason = choices.getJSONObject(0).optString("finish_reason").ifBlank { "unknown" }
                        error("Model returned an empty message (finish_reason: $finishReason). Raw response: ${raw.take(500)}")
                    }
                    val usage = json.optJSONObject("usage")
                    ChatCompletionResult(
                        content = content,
                        promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
                        completionTokens = usage?.optInt("completion_tokens", 0) ?: 0
                    )
                }
            }.recoverCatching { throwable ->
                // Give network/IO failures (timeout, no connection, DNS, TLS, ...) a clearer label
                // too, since okhttp exception messages alone (e.g. "timeout") are easy to misread as
                // "the model" failing rather than the network call itself.
                if (throwable.message?.startsWith("Model API") == true || throwable.message?.startsWith("Model returned") == true) {
                    throw throwable
                }
                val isTimeout = throwable is java.net.SocketTimeoutException
                val hint = if (isTimeout) " - the model API at this base URL didn't respond in time. If this is a custom/self-hosted endpoint, check it's actually reachable and not just slow to cold-start." else ""
                throw IllegalStateException("Network error contacting the model API (${throwable::class.simpleName}): ${throwable.message ?: "no details"}$hint", throwable)
            }
        }

    /** Lists models with a $0 prompt/completion price - i.e. OpenRouter's free tier. OpenRouter-
     *  specific: it's the only provider whose /models response includes per-model pricing. */
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

    /** Generic "list every model this key can see" for any OpenAI-compatible /models endpoint
     *  (OpenAI, NVIDIA NIM, self-hosted, etc.) - no pricing filter, since most providers other
     *  than OpenRouter don't report per-model pricing in this response at all. Best-effort field
     *  parsing: if a provider's /models shape differs slightly, this still returns whatever it
     *  can read (id required, everything else optional) rather than failing outright. */
    suspend fun fetchModels(apiKey: String?, baseUrl: String): Result<List<OpenRouterModel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "${baseUrl.trimEnd('/')}/models"
                val requestBuilder = Request.Builder().url(url)
                if (!apiKey.isNullOrBlank()) requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val errMsg = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
                            ?.takeIf { it.isNotBlank() }
                        error("Failed to load models from $url (HTTP ${response.code}): ${errMsg ?: raw.take(300).ifBlank { "(empty body)" }}")
                    }
                    val parsed = runCatching { JSONObject(raw) }.getOrElse {
                        error("$url didn't return JSON - this endpoint may not support listing models even though chat works.")
                    }
                    val data = parsed.optJSONArray("data") ?: parsed.optJSONArray("models") ?: JSONArray()
                    (0 until data.length()).mapNotNull { i ->
                        val entry = data.opt(i)
                        val m = entry as? JSONObject ?: return@mapNotNull if (entry is String) OpenRouterModel(entry, entry, 0) else null
                        val id = m.optString("id").ifBlank { m.optString("name") }
                        if (id.isBlank()) return@mapNotNull null
                        OpenRouterModel(
                            id = id,
                            name = m.optString("name", id),
                            contextLength = m.optInt("context_length", m.optInt("context_window", 0))
                        )
                    }.sortedBy { it.name }
                }
            }
        }
}
