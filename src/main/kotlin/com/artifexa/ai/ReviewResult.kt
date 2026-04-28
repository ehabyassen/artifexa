package com.artifexa.ai

import kotlinx.serialization.Serializable

@Serializable
data class ReviewResult(
    val overallSummary: String = "",
    val architectureAssessment: String = "",
    val bestPracticesFindings: List<Finding> = emptyList(),
    val taskCompletionScore: Int? = null,
    val completedAspects: List<String> = emptyList(),
    val missingAspects: List<String> = emptyList(),
    /** Per-component line-by-line reviews. Populated when hunks were sent to the LLM. */
    val componentReviews: List<ComponentReview> = emptyList(),
)

@Serializable
data class ComponentReview(
    val fqn: String,
    val filePath: String = "",
    val summary: String = "",
    val lineComments: List<LineComment> = emptyList(),
)

@Serializable
data class LineComment(
    /** New-file line number the comment attaches to. */
    val line: Int,
    val comment: String,
)

@Serializable
data class Finding(
    val severity: Severity = Severity.INFO,
    val title: String,
    val description: String,
    val affectedComponent: String? = null,
    /** Optional new-file line number the finding applies to, when it is specific to one line. */
    val line: Int? = null,
)

enum class Severity { ERROR, WARNING, INFO }
