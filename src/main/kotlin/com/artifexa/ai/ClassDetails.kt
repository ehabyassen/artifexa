package com.artifexa.ai

import kotlinx.serialization.Serializable

/** Full structural snapshot of a directly-changed class for AI review. */
@Serializable
data class ClassDetails(
    val fqn: String,
    val simpleName: String,
    val kind: String,                  // "class", "interface", "enum", "object", "annotation"
    val annotations: List<String>,
    val imports: List<String>,
    val fields: List<FieldSummary>,
    val methods: List<MethodSummary>,
)

@Serializable
data class FieldSummary(
    val name: String,
    val type: String,
    val modifiers: List<String>,
    val annotations: List<String>,
)

@Serializable
data class MethodSummary(
    val name: String,
    val signature: String,
    val returnType: String,
    val modifiers: List<String>,
    val annotations: List<String>,
    val lineCount: Int,
    val changed: Boolean,
    val linesAdded: Int,
    val linesRemoved: Int,
)
