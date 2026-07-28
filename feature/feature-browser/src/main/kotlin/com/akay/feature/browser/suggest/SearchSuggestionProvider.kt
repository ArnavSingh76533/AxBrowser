package com.akay.feature.browser.suggest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

enum class SuggestionType { HISTORY, BOOKMARK, REMOTE }

data class SearchSuggestion(
    val text: String,
    val subtitle: String? = null,
    val url: String? = null,
    val type: SuggestionType
)

/**
 * Fetches query completions from the OpenSearch-style suggest endpoint used
 * by most browsers ("client=firefox" returns a plain JSON array format that
 * needs no API key). Fails silently (returns an empty list) on any network
 * or parsing error so the address bar never blocks on this.
 */
@Singleton
class SearchSuggestionProvider @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun fetchRemoteSuggestions(query: String): List<SearchSuggestion> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val request = Request.Builder()
                    .url("https://www.google.com/complete/search?client=firefox&q=$encoded")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val arr = JSONArray(body)
                    val completions = arr.optJSONArray(1) ?: return@withContext emptyList()
                    (0 until completions.length()).mapNotNull { i ->
                        completions.optString(i)?.takeIf { it.isNotBlank() }?.let {
                            SearchSuggestion(text = it, type = SuggestionType.REMOTE)
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
