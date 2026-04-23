package com.artifexa.visualisation

import com.artifexa.graph.ComponentEdge
import com.artifexa.graph.ComponentGraph
import com.artifexa.graph.ComponentNode
import com.artifexa.graph.EdgeType
import com.artifexa.graph.NodeStatus
import java.awt.Color
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Force-directed layout + 2D painter for the component graph.
 * Deterministic seeding so the layout stays stable across renders.
 */
class GraphRenderer {

    data class Layout(
        val positions: Map<String, Point>,
        val bounds: java.awt.Rectangle,
    )

    private val colorChanged = Color(0x4F, 0x8E, 0xF7)
    private val colorNeighbour = Color(0xF5, 0x9E, 0x0B)
    private val colorTest = Color(0x22, 0xD3, 0xA5)
    private val colorDeleted = Color(0xEF, 0x44, 0x44)
    private val colorEdge = Color(120, 120, 130)
    private val colorEdgeViolation = Color(0xEF, 0x44, 0x44)
    private val colorLabel = Color(230, 230, 236)

    fun computeLayout(graph: ComponentGraph, width: Int, height: Int): Layout {
        val positions = mutableMapOf<String, DoublePoint>()
        val rnd = java.util.Random(graph.nodes.sumOf { it.id.hashCode().toLong() })
        val cx = width / 2.0
        val cy = height / 2.0
        val radius = min(width, height) * 0.35

        for ((i, node) in graph.nodes.withIndex()) {
            val angle = 2 * Math.PI * i / max(1, graph.nodes.size)
            val jitter = 0.7 + rnd.nextDouble() * 0.6
            positions[node.id] = DoublePoint(cx + radius * Math.cos(angle) * jitter, cy + radius * Math.sin(angle) * jitter)
        }

        // Simple spring relaxation — repulsion between all nodes, attraction along edges.
        val iterations = if (graph.nodes.size > 80) 40 else 120
        val k = sqrt((width * height).toDouble() / max(graph.nodes.size, 1))
        for (step in 0 until iterations) {
            val temperature = (1.0 - step.toDouble() / iterations) * (k * 0.3)
            val disp = mutableMapOf<String, DoublePoint>()
            for (v in graph.nodes) disp[v.id] = DoublePoint(0.0, 0.0)

            // Repulsion
            for (i in graph.nodes.indices) {
                for (j in i + 1 until graph.nodes.size) {
                    val a = graph.nodes[i]; val b = graph.nodes[j]
                    val pa = positions[a.id]!!; val pb = positions[b.id]!!
                    var dx = pa.x - pb.x
                    var dy = pa.y - pb.y
                    var d = sqrt(dx * dx + dy * dy)
                    if (d < 0.01) { dx = rnd.nextDouble() * 0.1; dy = rnd.nextDouble() * 0.1; d = 0.1 }
                    val force = (k * k) / d
                    val fx = dx / d * force; val fy = dy / d * force
                    disp[a.id]!!.let { it.x += fx; it.y += fy }
                    disp[b.id]!!.let { it.x -= fx; it.y -= fy }
                }
            }
            // Attraction along edges
            for (e in graph.edges) {
                val pa = positions[e.fromId] ?: continue
                val pb = positions[e.toId] ?: continue
                val dx = pa.x - pb.x
                val dy = pa.y - pb.y
                val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01)
                val force = (d * d) / k
                val fx = dx / d * force; val fy = dy / d * force
                disp[e.fromId]!!.let { it.x -= fx; it.y -= fy }
                disp[e.toId]!!.let { it.x += fx; it.y += fy }
            }
            // Apply, clamped to temperature
            for (v in graph.nodes) {
                val d = disp[v.id]!!
                val mag = sqrt(d.x * d.x + d.y * d.y).coerceAtLeast(0.01)
                val dx = d.x / mag * min(mag, temperature)
                val dy = d.y / mag * min(mag, temperature)
                val p = positions[v.id]!!
                p.x = (p.x + dx).coerceIn(30.0, (width - 30).toDouble())
                p.y = (p.y + dy).coerceIn(30.0, (height - 30).toDouble())
            }
        }

        val rounded = positions.mapValues { Point(it.value.x.toInt(), it.value.y.toInt()) }
        val bounds = computeBounds(rounded)
        return Layout(rounded, bounds)
    }

    fun paint(g: Graphics2D, graph: ComponentGraph, layout: Layout) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Edges first
        for (edge in graph.edges) {
            val a = layout.positions[edge.fromId] ?: continue
            val b = layout.positions[edge.toId] ?: continue
            g.color = if (edge.isLayerViolation) colorEdgeViolation else colorEdge
            g.stroke = strokeFor(edge.type, edge.isLayerViolation)
            g.draw(Line2D.Float(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat()))
        }

        // Nodes on top
        for (node in graph.nodes) {
            val p = layout.positions[node.id] ?: continue
            val radius = nodeRadius(node)
            val shape = Ellipse2D.Double(p.x - radius, p.y - radius, radius * 2, radius * 2)
            g.color = colorFor(node)
            g.fill(shape)
            g.color = Color.BLACK
            g.stroke = BasicStroke(1f)
            g.draw(shape)

            g.color = colorLabel
            g.font = g.font.deriveFont(11f)
            val label = node.simpleName
            val width = g.fontMetrics.stringWidth(label)
            g.drawString(label, p.x - width / 2, (p.y + radius + 12).toInt())
        }
    }

    fun nodeRadius(node: ComponentNode): Double =
        6.0 + node.changeIntensity * 18.0

    fun nodeAt(layout: Layout, x: Int, y: Int, graph: ComponentGraph): ComponentNode? {
        for (node in graph.nodes.reversed()) {
            val p = layout.positions[node.id] ?: continue
            val r = nodeRadius(node)
            val dx = x - p.x; val dy = y - p.y
            if (dx * dx + dy * dy <= r * r) return node
        }
        return null
    }

    private fun colorFor(node: ComponentNode): Color = when (node.status) {
        NodeStatus.CHANGED -> colorChanged
        NodeStatus.NEIGHBOUR -> colorNeighbour
        NodeStatus.TEST -> colorTest
        NodeStatus.DELETED -> colorDeleted
    }

    private fun strokeFor(type: EdgeType, violation: Boolean): BasicStroke {
        val w = if (violation) 2.0f else 1.3f
        return when (type) {
            EdgeType.CALLS -> BasicStroke(w)
            EdgeType.INHERITS -> BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(6f, 4f), 0f)
            EdgeType.IMPLEMENTS -> BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(6f, 4f), 0f)
            EdgeType.INJECTS -> BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(2f, 3f), 0f)
            EdgeType.IMPORTS -> BasicStroke(w * 0.8f)
        }
    }

    private fun computeBounds(positions: Map<String, Point>): java.awt.Rectangle {
        if (positions.isEmpty()) return java.awt.Rectangle(0, 0, 1, 1)
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        for (p in positions.values) {
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        return java.awt.Rectangle(minX, minY, maxX - minX, maxY - minY)
    }

    private class DoublePoint(var x: Double, var y: Double)

    // unused but referenced by @JvmField-style external callers
    fun colorLegend(): Map<String, Color> = mapOf(
        "Directly changed" to colorChanged,
        "Neighbour" to colorNeighbour,
        "Test" to colorTest,
        "Deleted" to colorDeleted,
    )
}
