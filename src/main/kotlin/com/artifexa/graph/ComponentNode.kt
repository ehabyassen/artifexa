package com.artifexa.graph

/** A node in the component graph — a class, interface, object, or top-level function. */
data class ComponentNode(
    val id: String,                   // fully-qualified name
    val simpleName: String,
    val filePath: String?,
    val layer: LayerType,
    val isDirectlyChanged: Boolean,
    val isTestComponent: Boolean,
    val linesAdded: Int,
    val linesRemoved: Int,
    val changeIntensity: Double,      // normalised 0.0..1.0
    val hasTests: Boolean,
    val status: NodeStatus,
) {
    val totalLinesChanged: Int get() = linesAdded + linesRemoved
}

enum class NodeStatus { CHANGED, NEIGHBOUR, TEST, DELETED }

enum class LayerType { CONTROLLER, SERVICE, REPOSITORY, DOMAIN, UTIL, TEST, UNKNOWN }
