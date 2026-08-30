package com.akay.core.data.ai

/**
 * A quick-select provider for the agent's model settings. All of these speak the same
 * OpenAI-compatible /chat/completions + /models API shape - only the base URL (and which
 * provider actually needs a key) differs. "custom" is the escape hatch for anything else that's
 * OpenAI-compatible but not one of the hardcoded presets (self-hosted vLLM/LM Studio/Ollama's
 * OpenAI-compat endpoint, Groq, Together, Mistral's API, etc.) - just paste its base URL.
 */
data class AiProviderPreset(
    val id: String,
    val displayName: String,
    /** No trailing slash, no /chat/completions or /models suffix. Empty for "custom" - the user
     *  supplies their own. */
    val baseUrl: String,
    /** Whether "fetch models" should filter to $0-priced entries (only OpenRouter's /models
     *  response includes per-model pricing at all) or just list everything the key can see. */
    val filterToFree: Boolean,
    val apiKeyHint: String
)

val AI_PROVIDER_PRESETS = listOf(
    AiProviderPreset(
        id = "openrouter",
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        filterToFree = true,
        apiKeyHint = "From openrouter.ai/keys - free-tier models available with $0 usage"
    ),
    AiProviderPreset(
        id = "openai",
        displayName = "OpenAI (ChatGPT)",
        baseUrl = "https://api.openai.com/v1",
        filterToFree = false,
        apiKeyHint = "From platform.openai.com/api-keys - billed per your OpenAI account"
    ),
    AiProviderPreset(
        id = "nim",
        displayName = "NVIDIA NIM",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        filterToFree = false,
        apiKeyHint = "From build.nvidia.com - many hosted models are free to try"
    ),
    AiProviderPreset(
        id = "custom",
        displayName = "Custom (OpenAI-compatible)",
        baseUrl = "",
        filterToFree = false,
        apiKeyHint = "Any server speaking the OpenAI /chat/completions API - self-hosted, Groq, Together, etc."
    ),
)

fun presetForId(id: String): AiProviderPreset = AI_PROVIDER_PRESETS.firstOrNull { it.id == id } ?: AI_PROVIDER_PRESETS.first()
