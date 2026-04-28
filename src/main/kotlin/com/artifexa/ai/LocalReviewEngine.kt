package com.artifexa.ai

import com.artifexa.graph.ComponentEdge
import com.artifexa.graph.ComponentGraph
import com.artifexa.graph.ComponentNode
import com.artifexa.graph.EdgeType
import com.artifexa.graph.LayerType
import com.artifexa.vcs.BranchDiff

/**
 * Rules-based, entirely offline review engine. Produces a [ReviewResult] from the graph,
 * the branch diff, extracted class details (metadata only — no source code), and an
 * optional standards document. Intended for the "Local-only mode" setting where no data
 * may leave the machine.
 *
 * Heuristics:
 * - Layer violations  → architecture findings
 * - High fan-in/fan-out → coupling warnings
 * - Long methods      → complexity warnings
 * - Public mutable fields → encapsulation warnings
 * - Missing test coverage → information findings
 * - Standards keyword matching → best-practice findings referencing specific lines
 */
class LocalReviewEngine(
    private val longMethodLines: Int = 40,
    private val veryLongMethodLines: Int = 80,
    private val highCouplingEdges: Int = 8,
) {

    fun review(
        branchDiff: BranchDiff,
        graph: ComponentGraph,
        classDetails: List<ClassDetails>,
        taskDescription: String?,
        standardsText: String?,
        standardsSource: String?,
    ): ReviewResult {
        val findings = buildList {
            addAll(architectureFindings(graph))
            addAll(couplingFindings(graph))
            addAll(classDetailFindings(classDetails))
            addAll(testCoverageFindings(graph))
            addAll(standardsFindings(classDetails, standardsText, standardsSource))
        }.distinctBy { "${it.severity}|${it.title}|${it.affectedComponent}" }

        val architecture = architectureAssessment(graph)
        val summary = summary(branchDiff, graph, findings.size, standardsText != null)

        val (score, completed, missing) = taskCompletion(taskDescription, graph, classDetails)

        return ReviewResult(
            overallSummary = summary,
            architectureAssessment = architecture,
            bestPracticesFindings = findings,
            taskCompletionScore = score,
            completedAspects = completed,
            missingAspects = missing,
        )
    }

    // ---- architecture ------------------------------------------------------

    private fun architectureFindings(graph: ComponentGraph): List<Finding> {
        val violations = graph.layerViolations()
        return violations.map { edge ->
            val from = graph.nodesById[edge.fromId]
            val to = graph.nodesById[edge.toId]
            Finding(
                severity = Severity.WARNING,
                title = "Layer boundary violation",
                description = "The ${edgeVerb(edge.type)} from '${from?.simpleName ?: edge.fromId}' (${from?.layer}) " +
                    "to '${to?.simpleName ?: edge.toId}' (${to?.layer}) crosses layer boundaries the wrong way. " +
                    "Consider introducing an abstraction in the correct direction or moving the caller.",
                affectedComponent = edge.fromId,
            )
        }
    }

    private fun couplingFindings(graph: ComponentGraph): List<Finding> =
        graph.nodes.filter { it.isDirectlyChanged }.mapNotNull { node ->
            val degree = graph.inboundCount(node.id) + graph.outboundCount(node.id)
            if (degree >= highCouplingEdges)
                Finding(
                    severity = Severity.INFO,
                    title = "High coupling on changed component",
                    description = "'${node.simpleName}' has $degree direct relationships " +
                        "(${graph.inboundCount(node.id)} inbound, ${graph.outboundCount(node.id)} outbound). " +
                        "Consider whether its responsibilities could be split.",
                    affectedComponent = node.id,
                )
            else null
        }

    private fun testCoverageFindings(graph: ComponentGraph): List<Finding> =
        graph.nodes.filter { it.isDirectlyChanged && !it.isTestComponent && !it.hasTests }.map { node ->
            Finding(
                severity = Severity.INFO,
                title = "No test changes accompany production change",
                description = "No corresponding test file for '${node.simpleName}' was modified on this branch. " +
                    "Consider adding or updating tests.",
                affectedComponent = node.id,
            )
        }

    private fun classDetailFindings(details: List<ClassDetails>): List<Finding> = buildList {
        for (cls in details) {
            for (method in cls.methods) {
                if (!method.changed) continue
                if (method.lineCount >= veryLongMethodLines) {
                    add(Finding(
                        severity = Severity.WARNING,
                        title = "Method is very long (${method.lineCount} lines)",
                        description = "'${cls.simpleName}.${method.name}' spans ${method.lineCount} lines. " +
                            "Consider extracting helper methods to reduce complexity.",
                        affectedComponent = cls.fqn,
                    ))
                } else if (method.lineCount >= longMethodLines) {
                    add(Finding(
                        severity = Severity.INFO,
                        title = "Method is getting long (${method.lineCount} lines)",
                        description = "'${cls.simpleName}.${method.name}' has ${method.lineCount} lines; " +
                            "watch for responsibility creep.",
                        affectedComponent = cls.fqn,
                    ))
                }
                // Naming heuristic: camelCase required
                if (method.name.isNotEmpty() && !method.name[0].isLowerCase()) {
                    add(Finding(
                        severity = Severity.INFO,
                        title = "Method name does not start with lowercase",
                        description = "'${cls.simpleName}.${method.name}' — convention in JVM projects is camelCase " +
                            "beginning with a lowercase letter.",
                        affectedComponent = cls.fqn,
                    ))
                }
            }
            // Public mutable field
            for (field in cls.fields) {
                val isPublic = "public" in field.modifiers
                val isFinal = "final" in field.modifiers
                val isStatic = "static" in field.modifiers
                if (isPublic && !isFinal && !isStatic) {
                    add(Finding(
                        severity = Severity.WARNING,
                        title = "Public mutable field",
                        description = "'${cls.simpleName}.${field.name}' is public and non-final. " +
                            "Prefer encapsulation via accessors, or mark the field as final.",
                        affectedComponent = cls.fqn,
                    ))
                }
            }
            // Class name capitalisation
            if (cls.simpleName.isNotEmpty() && !cls.simpleName[0].isUpperCase()) {
                add(Finding(
                    severity = Severity.INFO,
                    title = "Class name should begin with an uppercase letter",
                    description = "'${cls.simpleName}' does not follow JVM PascalCase convention.",
                    affectedComponent = cls.fqn,
                ))
            }
        }
    }

    // ---- standards keyword matching ---------------------------------------

    private fun standardsFindings(
        details: List<ClassDetails>,
        standardsText: String?,
        standardsSource: String?,
    ): List<Finding> {
        if (standardsText.isNullOrBlank()) return emptyList()
        val rules = extractRules(standardsText)
        if (rules.isEmpty()) return emptyList()

        return buildList {
            for (cls in details) {
                val haystack = buildSearchableText(cls)
                for (rule in rules) {
                    val matched = rule.keywords.any { kw -> haystack.contains(kw, ignoreCase = true) }
                    if (rule.kind == RuleKind.MUST && !matched) {
                        add(Finding(
                            severity = Severity.WARNING,
                            title = "Possible standards gap",
                            description = "'${cls.simpleName}' does not appear to satisfy this rule from " +
                                "your standards${standardsSource?.let { " ($it)" }.orEmpty()}: " +
                                "\"${rule.text.take(240)}\"",
                            affectedComponent = cls.fqn,
                        ))
                    } else if (rule.kind == RuleKind.MUST_NOT && matched) {
                        add(Finding(
                            severity = Severity.WARNING,
                            title = "Possible standards violation",
                            description = "'${cls.simpleName}' may violate this rule from your standards" +
                                "${standardsSource?.let { " ($it)" }.orEmpty()}: \"${rule.text.take(240)}\"",
                            affectedComponent = cls.fqn,
                        ))
                    }
                }
            }
        }.take(20) // cap so we don't spam the review panel
    }

    private data class Rule(val text: String, val kind: RuleKind, val keywords: List<String>)
    private enum class RuleKind { MUST, MUST_NOT }

    /** Very simple rule extractor: pick bullet lines that look prescriptive. */
    private fun extractRules(text: String): List<Rule> {
        val lines = text.lines().map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.removePrefix("-").removePrefix("*").removePrefix("•").trim() }
            .filter { it.length in 12..280 }
            .take(60)
        val mustPattern = Regex("""\b(must|shall|should|required|always)\b""", RegexOption.IGNORE_CASE)
        val mustNotPattern = Regex("""\b(must not|shall not|never|avoid|do not)\b""", RegexOption.IGNORE_CASE)
        return lines.mapNotNull { line ->
            val kind = when {
                mustNotPattern.containsMatchIn(line) -> RuleKind.MUST_NOT
                mustPattern.containsMatchIn(line) -> RuleKind.MUST
                else -> null
            } ?: return@mapNotNull null
            val keywords = line.split(Regex("""[^A-Za-z0-9_]+"""))
                .filter { it.length >= 4 }
                .filterNot { it.lowercase() in STOP_WORDS }
                .distinct()
                .take(6)
            if (keywords.isEmpty()) null else Rule(line, kind, keywords)
        }
    }

    private fun buildSearchableText(cls: ClassDetails): String = buildString {
        append(cls.simpleName); append(' '); append(cls.fqn); append(' ')
        append(cls.kind); append(' ')
        cls.annotations.forEach { append(it); append(' ') }
        cls.imports.forEach { append(it); append(' ') }
        cls.fields.forEach { append(it.name); append(' '); append(it.type); append(' ') }
        cls.methods.forEach { append(it.name); append(' '); append(it.signature); append(' '); append(it.returnType); append(' ') }
    }

    // ---- narrative outputs ------------------------------------------------

    private fun architectureAssessment(graph: ComponentGraph): String {
        val v = graph.layerViolations().size
        val layers = graph.nodes.filter { it.isDirectlyChanged }
            .groupingBy { it.layer }.eachCount()
        val layerDesc = layers.entries.joinToString(", ") { "${it.value} ${it.key}" }
        return if (v == 0)
            "Change spans: $layerDesc. No layer-boundary violations detected by the offline heuristic."
        else
            "Change spans: $layerDesc. Detected $v layer-boundary violation(s) — see findings below."
    }

    private fun summary(branchDiff: BranchDiff, graph: ComponentGraph, findingCount: Int, hasStandards: Boolean): String {
        val changed = graph.nodes.count { it.isDirectlyChanged }
        val stdClause = if (hasStandards) " against the provided standards document" else ""
        return "Offline review of '${branchDiff.branchName}': $changed changed component(s), " +
            "${branchDiff.commits.size} commit(s), +${branchDiff.totalLinesAdded}/-${branchDiff.totalLinesRemoved} lines. " +
            "Produced $findingCount best-practice finding(s)$stdClause. No data left your machine."
    }

    private fun taskCompletion(
        task: String?,
        graph: ComponentGraph,
        details: List<ClassDetails>,
    ): Triple<Int?, List<String>, List<String>> {
        if (task.isNullOrBlank()) return Triple(null, emptyList(), emptyList())
        val tokens = task.split(Regex("""[^A-Za-z0-9]+"""))
            .filter { it.length >= 4 }
            .filterNot { it.lowercase() in STOP_WORDS }
            .map { it.lowercase() }
            .distinct()
        if (tokens.isEmpty()) return Triple(null, emptyList(), emptyList())

        val searchable = graph.nodes.joinToString(" ") { it.id } + " " +
            details.joinToString(" ") { buildSearchableText(it) }
        val lower = searchable.lowercase()

        val covered = tokens.filter { lower.contains(it) }
        val score = (covered.size * 100 / tokens.size).coerceIn(0, 100)
        return Triple(
            score,
            covered.take(10).map { "Mentions '$it' in changed code" },
            (tokens - covered.toSet()).take(10).map { "'$it' not found in changed component names" },
        )
    }

    private fun edgeVerb(type: EdgeType): String = when (type) {
        EdgeType.CALLS -> "call"
        EdgeType.INHERITS -> "inheritance"
        EdgeType.IMPLEMENTS -> "implementation"
        EdgeType.INJECTS -> "injection"
        EdgeType.IMPORTS -> "import"
    }

    companion object {
        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "that", "this", "from", "into", "over", "under", "when",
            "then", "there", "here", "should", "must", "have", "been", "will", "shall", "some", "each",
            "their", "while", "does", "does", "than", "what", "which", "where", "about", "also",
        )
    }
}
