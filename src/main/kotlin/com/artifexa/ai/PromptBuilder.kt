package com.artifexa.ai

import com.artifexa.graph.ComponentEdge
import com.artifexa.graph.ComponentGraph
import com.artifexa.graph.ComponentNode
import com.artifexa.vcs.BranchDiff
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Serialises the component graph + branch diff into a **structured summary** (not source code)
 * suitable for LLM consumption. Large graphs are compressed to stay within context limits.
 */
class PromptBuilder(private val maxContextChars: Int = 60_000) {

    data class BuiltPrompt(val systemPrompt: String, val userPrompt: String)

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    fun build(
        branchDiff: BranchDiff,
        graph: ComponentGraph,
        taskDescription: String?,
    ): BuiltPrompt {
        val context = buildContext(branchDiff, graph, taskDescription)
        var payload = json.encodeToString(context)
        if (payload.length > maxContextChars) {
            payload = json.encodeToString(compress(context))
        }
        return BuiltPrompt(SYSTEM_PROMPT, "$USER_PROMPT_HEADER\n\n$payload")
    }

    private fun buildContext(
        branchDiff: BranchDiff,
        graph: ComponentGraph,
        taskDescription: String?,
    ): ReviewContext {
        val components = graph.nodes.map {
            ComponentSummary(
                fqn = it.id,
                simpleName = it.simpleName,
                layer = it.layer.name,
                linesAdded = it.linesAdded,
                linesRemoved = it.linesRemoved,
                directlyChanged = it.isDirectlyChanged,
                inbound = graph.inboundCount(it.id),
                outbound = graph.outboundCount(it.id),
                hasTests = it.hasTests,
            )
        }
        val violations = graph.layerViolations().map { ViolationSummary(it.fromId, it.toId, it.type.name) }
        val commits = branchDiff.commits.map { CommitSummary(it.shortHash, it.author, it.message.take(160)) }
        return ReviewContext(
            branchName = branchDiff.branchName,
            targetBranch = branchDiff.targetBranch,
            commitCount = branchDiff.commits.size,
            totalLinesAdded = branchDiff.totalLinesAdded,
            totalLinesRemoved = branchDiff.totalLinesRemoved,
            taskDescription = taskDescription?.trim().takeIf { !it.isNullOrEmpty() },
            changedComponentsSummary = components,
            layerViolations = violations,
            commits = commits,
        )
    }

    /** Compress by keeping top 20 directly-changed nodes and summarising the rest per-layer. */
    private fun compress(ctx: ReviewContext): ReviewContext {
        val top = ctx.changedComponentsSummary
            .sortedByDescending { it.linesAdded + it.linesRemoved }
            .take(20)
        val remainderByLayer = ctx.changedComponentsSummary
            .filterNot { it in top }
            .groupBy { it.layer }
            .mapValues { (_, v) -> v.size }
        val summaryPlaceholders = remainderByLayer.map { (layer, count) ->
            ComponentSummary(
                fqn = "summary:$layer",
                simpleName = "$count additional $layer components changed",
                layer = layer,
                linesAdded = 0,
                linesRemoved = 0,
                directlyChanged = true,
                inbound = 0,
                outbound = 0,
                hasTests = false,
            )
        }
        return ctx.copy(changedComponentsSummary = top + summaryPlaceholders)
    }

    companion object {
        const val SYSTEM_PROMPT: String = """You are a senior software architect reviewing a Git feature branch. You will receive a STRUCTURED SUMMARY of a semantic component graph and diff metadata — NO source code. Your job is to assess architecture quality, best-practice adherence, and task completion.

Return ONLY a valid JSON object with this exact shape:
{
  "overallSummary": "<1-3 sentence executive summary>",
  "architectureAssessment": "<layer violations, coupling concerns, SOLID observations>",
  "bestPracticesFindings": [
    { "severity": "ERROR|WARNING|INFO", "title": "<short>", "description": "<actionable, constructive>", "affectedComponent": "<fully-qualified class name or null>" }
  ],
  "taskCompletionScore": <integer 0..100 or null if no task description provided>,
  "completedAspects": [ "<short bullet>" ],
  "missingAspects": [ "<short bullet>" ]
}

Rules:
- Do not reproduce source code. Use component names only.
- Keep findings constructive: "Consider…", "This could be improved by…". Never accusatory.
- If no task description is provided, set taskCompletionScore to null and leave completedAspects / missingAspects empty.
- Output must be valid JSON. Do not wrap in Markdown code fences."""

        const val USER_PROMPT_HEADER: String =
            "Review the following branch change summary and return the JSON object described above."
    }

    // --------- serialisable shapes ---------

    @Serializable
    data class ReviewContext(
        val branchName: String,
        val targetBranch: String,
        val commitCount: Int,
        val totalLinesAdded: Int,
        val totalLinesRemoved: Int,
        val taskDescription: String?,
        val changedComponentsSummary: List<ComponentSummary>,
        val layerViolations: List<ViolationSummary>,
        val commits: List<CommitSummary>,
    )

    @Serializable
    data class ComponentSummary(
        val fqn: String,
        val simpleName: String,
        val layer: String,
        val linesAdded: Int,
        val linesRemoved: Int,
        val directlyChanged: Boolean,
        val inbound: Int,
        val outbound: Int,
        val hasTests: Boolean,
    )

    @Serializable
    data class ViolationSummary(val from: String, val to: String, val edgeType: String)

    @Serializable
    data class CommitSummary(val hash: String, val author: String, val message: String)
}
