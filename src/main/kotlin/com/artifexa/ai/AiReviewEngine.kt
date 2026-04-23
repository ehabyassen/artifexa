package com.artifexa.ai

import com.artifexa.graph.ComponentGraph
import com.artifexa.settings.ArtifexaSettings
import com.artifexa.vcs.BranchDiff
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Orchestrates PromptBuilder + LlmClient and incrementally parses the streamed JSON response. */
class AiReviewEngine(
    private val promptBuilder: PromptBuilder = PromptBuilder(),
    private val client: LlmClient = LlmClient(),
    private val settingsProvider: () -> ArtifexaSettings = { ArtifexaSettings.getInstance() },
) {

    /** Streams raw text chunks — UI can concatenate and show a spinner until the flow completes. */
    fun streamReview(
        branchDiff: BranchDiff,
        graph: ComponentGraph,
        taskDescription: String?,
        standards: String? = null,
        standardsSource: String? = null,
    ): Flow<String> {
        val prompt = promptBuilder.build(branchDiff, graph, taskDescription, standards, standardsSource)
        return client.stream(prompt, settingsProvider())
    }

    /**
     * Convenience: collect the full streamed response and parse the final JSON into a [ReviewResult].
     * Throws [LlmClient.LlmException] on network failure or [SerializationException] on unparseable JSON.
     */
    suspend fun runReview(
        branchDiff: BranchDiff,
        graph: ComponentGraph,
        taskDescription: String?,
        standards: String? = null,
        standardsSource: String? = null,
    ): ReviewResult {
        val sb = StringBuilder()
        streamReview(branchDiff, graph, taskDescription, standards, standardsSource).map {
            sb.append(it); it
        }.collect {}
        return parse(sb.toString())
    }

    fun parse(raw: String): ReviewResult {
        val extracted = extractJsonObject(raw)
        val parsed = json.parseToJsonElement(extracted).jsonObject

        return ReviewResult(
            overallSummary = parsed.str("overallSummary"),
            architectureAssessment = parsed.str("architectureAssessment"),
            bestPracticesFindings = parsed["bestPracticesFindings"]?.let { el ->
                runCatching { el.jsonArray }.getOrNull()
            }?.mapNotNull { f ->
                val obj = runCatching { f.jsonObject }.getOrNull() ?: return@mapNotNull null
                Finding(
                    severity = runCatching { Severity.valueOf(obj.str("severity").uppercase()) }.getOrDefault(Severity.INFO),
                    title = obj.str("title"),
                    description = obj.str("description"),
                    affectedComponent = obj.nullableStr("affectedComponent"),
                )
            }.orEmpty(),
            taskCompletionScore = (parsed["taskCompletionScore"] as? JsonPrimitive)?.content?.toIntOrNull(),
            completedAspects = parsed.strList("completedAspects"),
            missingAspects = parsed.strList("missingAspects"),
        )
    }

    private fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        val withoutFence = if (trimmed.startsWith("```")) {
            trimmed.substringAfter("```").substringAfter('\n').substringBeforeLast("```")
        } else trimmed
        val start = withoutFence.indexOf('{')
        val end = withoutFence.lastIndexOf('}')
        if (start < 0 || end <= start) return "{}"
        return withoutFence.substring(start, end + 1)
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.nullableStr(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        return if (p.isString) p.content else null
    }

    private fun JsonObject.strList(key: String): List<String> =
        runCatching { this[key]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content } }.getOrNull().orEmpty()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
