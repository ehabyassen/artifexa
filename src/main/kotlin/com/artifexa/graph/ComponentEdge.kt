package com.artifexa.graph

data class ComponentEdge(
    val fromId: String,
    val toId: String,
    val type: EdgeType,
    val isLayerViolation: Boolean = false,
    val weight: Int = 1,
)

enum class EdgeType { CALLS, INHERITS, IMPLEMENTS, INJECTS, IMPORTS }

data class ComponentGraph(
    val nodes: List<ComponentNode>,
    val edges: List<ComponentEdge>,
) {
    val nodesById: Map<String, ComponentNode> by lazy { nodes.associateBy { it.id } }

    fun neighbours(id: String): List<ComponentNode> {
        val neighbourIds = buildSet {
            edges.forEach {
                if (it.fromId == id) add(it.toId)
                if (it.toId == id) add(it.fromId)
            }
        }
        return neighbourIds.mapNotNull { nodesById[it] }
    }

    fun inboundCount(id: String): Int = edges.count { it.toId == id }
    fun outboundCount(id: String): Int = edges.count { it.fromId == id }
    fun layerViolations(): List<ComponentEdge> = edges.filter { it.isLayerViolation }
}
