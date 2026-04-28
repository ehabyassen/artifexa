package com.artifexa.ai

import com.artifexa.settings.ArtifexaSettings
import com.artifexa.settings.LlmProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Non-blocking LLM client with SSE streaming and exponential-backoff retry.
 * Supports OpenAI, Anthropic, and OpenAI-compatible custom endpoints.
 */
class LlmClient(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    fun stream(prompt: PromptBuilder.BuiltPrompt, settings: ArtifexaSettings): Flow<String> = channelFlow {
        val state = settings.state
        if (state.localOnlyMode) {
            throw LlmException("Local-only mode is enabled. Disable it in Settings → Tools → Artifexa to run AI review.")
        }

        val request = when (state.llmProvider) {
            LlmProvider.OPENAI -> buildOpenAiRequest(prompt, state.openAiModel, OPENAI_URL, settings.getApiKey(LlmProvider.OPENAI), "OpenAI API key")
            LlmProvider.ANTHROPIC -> buildAnthropicRequest(prompt, state.anthropicModel, settings.getApiKey(LlmProvider.ANTHROPIC))
            LlmProvider.GITHUB_MODELS -> buildOpenAiRequest(
                prompt, state.githubModel, GITHUB_MODELS_URL, settings.getApiKey(LlmProvider.GITHUB_MODELS), "GitHub token",
                extraHeaders = mapOf("X-GitHub-Api-Version" to GITHUB_API_VERSION),
            )
            LlmProvider.CUSTOM -> {
                val url = state.customEndpointUrl.trim().ifEmpty { throw LlmException("Custom endpoint URL is empty.") }
                buildOpenAiRequest(prompt, state.openAiModel, url, settings.getApiKey(LlmProvider.CUSTOM) ?: settings.getApiKey(LlmProvider.OPENAI), "Custom endpoint API key")
            }
        }

        val provider = state.llmProvider
        var attempt = 0
        while (true) {
            attempt++
            try {
                subscribe(request, provider) { chunk -> trySend(chunk) }
                close()
                return@channelFlow
            } catch (e: RetryableStatusException) {
                if (attempt >= MAX_RETRIES) {
                    close(LlmException("LLM request failed after $MAX_RETRIES attempts: ${e.message}", e))
                    return@channelFlow
                }
                delay((1L shl attempt) * 500L)
            } catch (e: Throwable) {
                close(LlmException("LLM request failed: ${e.message}", e))
                return@channelFlow
            }
        }
    }

    // --------------------------------------------------------------------

    private suspend fun subscribe(
        request: Request,
        provider: LlmProvider,
        onChunk: (String) -> Unit,
    ) {
        val completion = CompletableDeferred<Unit>()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") { completion.complete(Unit); return }
                val chunk = runCatching { parseChunk(data, provider) }.getOrDefault("")
                if (chunk.isNotEmpty()) onChunk(chunk)
            }
            override fun onClosed(eventSource: EventSource) {
                if (!completion.isCompleted) completion.complete(Unit)
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                val code = response?.code ?: 0
                val msg = response?.message ?: t?.message ?: "unknown"
                val body = runCatching { response?.peekBody(2048)?.string().orEmpty() }.getOrDefault("")
                val suffix = if (body.isNotBlank()) " — ${body.trim().take(500)}" else ""
                val err: Throwable = if (code == 429 || code in 500..599) {
                    RetryableStatusException("$code $msg$suffix")
                } else {
                    IOException("$code $msg$suffix")
                }
                if (!completion.isCompleted) completion.completeExceptionally(err)
            }
        }
        val source = EventSources.createFactory(client).newEventSource(request, listener)
        try {
            completion.await()
        } finally {
            source.cancel()
        }
    }

    private fun parseChunk(data: String, provider: LlmProvider): String {
        val element = json.parseToJsonElement(data)
        val obj = element as? JsonObject ?: return ""
        return when (provider) {
            LlmProvider.OPENAI, LlmProvider.CUSTOM, LlmProvider.GITHUB_MODELS -> {
                val choices = obj["choices"] as? JsonArray ?: return ""
                val first = choices.firstOrNull() as? JsonObject ?: return ""
                val delta = first["delta"] as? JsonObject ?: return ""
                (delta["content"] as? JsonPrimitive)?.content.orEmpty()
            }
            LlmProvider.ANTHROPIC -> {
                val type = (obj["type"] as? JsonPrimitive)?.content
                if (type == "content_block_delta") {
                    val delta = obj["delta"] as? JsonObject ?: return ""
                    (delta["text"] as? JsonPrimitive)?.content.orEmpty()
                } else ""
            }
        }
    }

    private fun buildOpenAiRequest(
        prompt: PromptBuilder.BuiltPrompt,
        model: String,
        url: String,
        apiKey: String?,
        keyLabel: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Request {
        if (apiKey.isNullOrBlank()) throw LlmException("$keyLabel is not set. Configure it in Settings → Tools → Artifexa.")
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", 0.0)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", prompt.systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", prompt.userPrompt)
                }
            }
        }.toString().toRequestBody(JSON)
        val builder = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body)
        extraHeaders.forEach { (k, v) -> builder.addHeader(k, v) }
        return builder.build()
    }

    private fun buildAnthropicRequest(prompt: PromptBuilder.BuiltPrompt, model: String, apiKey: String?): Request {
        if (apiKey.isNullOrBlank()) throw LlmException("Anthropic API key is not set. Configure it in Settings → Tools → Artifexa.")
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            put("stream", true)
            put("system", prompt.systemPrompt)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", prompt.userPrompt)
                }
            }
        }.toString().toRequestBody(JSON)
        return Request.Builder()
            .url(ANTHROPIC_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body)
            .build()
    }

    private class RetryableStatusException(message: String) : RuntimeException(message)

    companion object {
        private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
        private const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
        private const val GITHUB_MODELS_URL = "https://models.github.ai/inference/chat/completions"
        private const val GITHUB_API_VERSION = "2026-03-10"
        private const val MAX_RETRIES = 3
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
