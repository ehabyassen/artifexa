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
)

@Serializable
data class Finding(
    val severity: Severity = Severity.INFO,
    val title: String,
    val description: String,
    val affectedComponent: String? = null,
)

enum class Severity { ERROR, WARNING, INFO }
