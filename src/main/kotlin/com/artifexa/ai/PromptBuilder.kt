package com.artifexa.ai

import com.artifexa.graph.ComponentEdge
import com.artifexa.graph.ComponentGraph
import com.artifexa.graph.ComponentNode
import com.artifexa.vcs.BranchAnalyser
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
        standards: String? = null,
        standardsSource: String? = null,
        classDetails: List<ClassDetails> = emptyList(),
        hunksByFqn: Map<String, List<BranchAnalyser.DiffHunk>> = emptyMap(),
    ): BuiltPrompt {
        val context = buildContext(branchDiff, graph, taskDescription)
        var payload = json.encodeToString(context)
        if (payload.length > maxContextChars) {
            payload = json.encodeToString(compress(context))
        }
        val detailsBlock = if (classDetails.isNotEmpty()) {
            val detailsJson = json.encodeToString(classDetails)
            val capped = if (detailsJson.length > maxContextChars) detailsJson.substring(0, maxContextChars) + "\n... [truncated]" else detailsJson
            "\n\n## CHANGED CLASS METADATA\n\n$capped"
        } else ""
        val hunksBlock = if (hunksByFqn.isNotEmpty()) {
            val rendered = renderHunks(hunksByFqn, classDetails)
            "\n\n## DIFF HUNKS PER COMPONENT\n\nLine numbers shown in the left column are NEW-file line numbers " +
                "(post-change). Lines beginning with '+' were added, '-' removed, ' ' are unchanged context. " +
                "When you return componentReviews[].lineComments[].line, use these NEW-file line numbers.\n\n$rendered"
        } else ""
        val standardsBlock = if (!standards.isNullOrBlank()) {
            val src = standardsSource?.let { " (source: $it)" }.orEmpty()
            "\n\n## CODE REVIEW STANDARDS$src\n\nThe following document describes the coding/review standards " +
                "that MUST be used to grade the branch. Treat every violation of these standards as a finding. " +
                "Do not copy the standards back — cite them by quoting short phrases only.\n\n" +
                "----- BEGIN STANDARDS -----\n$standards\n----- END STANDARDS -----"
        } else ""
        return BuiltPrompt(SYSTEM_PROMPT, "$USER_PROMPT_HEADER\n\n$payload$detailsBlock$hunksBlock$standardsBlock")
    }

    private fun renderHunks(
        hunksByFqn: Map<String, List<BranchAnalyser.DiffHunk>>,
        classDetails: List<ClassDetails>,
    ): String {
        val pathByFqn = classDetails.associate { it.fqn to it.simpleName }
        val budget = maxContextChars
        val out = StringBuilder()
        for ((fqn, hunks) in hunksByFqn) {
            if (out.length >= budget) {
                out.append("\n[... additional components truncated due to context budget ...]\n")
                break
            }
            out.append("\n### Component: $fqn")
            pathByFqn[fqn]?.let { out.append(" ($it)") }
            out.append("\n")
            for (hunk in hunks) {
                out.append("```\n")
                for (line in hunk.lines) {
                    val ln = line.newLine?.toString()?.padStart(5) ?: "     "
                    out.append(ln).append(" ").append(line.prefix).append(line.content).append("\n")
                    if (out.length >= budget) break
                }
                out.append("```\n")
                if (out.length >= budget) break
            }
        }
        return out.toString()
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
        const val SYSTEM_PROMPT: String = """You are a senior software engineer performing a thorough code review of a Git feature branch. You will receive: (1) a STRUCTURED SUMMARY of the component graph and diff metadata, (2) CHANGED CLASS METADATA (method signatures, fields, imports, annotations), (3) DIFF HUNKS PER COMPONENT with new-file line numbers, and (4) an optional CODE REVIEW STANDARDS document.

Your job has TWO deliverables:
A. High-level assessment — architecture, best-practice findings, task completion (same as before).
B. Per-component, line-by-line review — for each directly changed component, produce a short summary AND concrete line-level comments anchored to specific NEW-file line numbers you observed in the DIFF HUNKS section.

Return ONLY a valid JSON object with this EXACT shape:
{
  "overallSummary": "<1-3 sentence executive summary>",
  "architectureAssessment": "<layer violations, coupling, SOLID>",
  "bestPracticesFindings": [
    { "severity": "ERROR|WARNING|INFO", "title": "<short>", "description": "<actionable>", "affectedComponent": "<fqn or null>", "line": <new-file line number or null> }
  ],
  "taskCompletionScore": <0..100 or null>,
  "completedAspects": ["<short bullet>"],
  "missingAspects": ["<short bullet>"],
  "componentReviews": [
    {
      "fqn": "<fully qualified class name>",
      "filePath": "<repo-relative path>",
      "summary": "<1-3 sentence component-level review>",
      "lineComments": [
        { "line": <new-file line number from the hunks>, "comment": "<concrete, actionable comment about THIS line>" }
      ]
    }
  ]
}

Rules:
- Output must be valid JSON. No Markdown fences.
- The 'line' field must match a new-file line number that actually appears in the DIFF HUNKS section for that component.
- Comments must be constructive ("Consider…", "This could…"). Never accusatory.
- Only comment on lines where you can make a concrete, evidence-based observation (correctness, style, complexity, naming, error handling, security, standards compliance). Do not pad.
- When CODE REVIEW STANDARDS are present, cite the relevant rule in the comment (keep the citation short).

CRITICAL EVIDENCE RULES:
1. NEVER raise a finding or line-comment unless the provided material contradicts a best practice. If the code is already correct on that line, stay silent.
2. Before suggesting "mark X static/final/private" or "rename X to camelCase/UPPER_SNAKE_CASE", inspect the actual modifiers list or name in the metadata/hunks. Do not recommend a state that already holds.
3. Each line-comment MUST reference specifics from the hunk on that line (a token, an expression, a missing annotation, etc.) — not a generic admonition.
4. Empty componentReviews[].lineComments is acceptable and preferred over speculative comments.

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
