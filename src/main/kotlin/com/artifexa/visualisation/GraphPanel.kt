package com.artifexa.visualisation

import com.artifexa.graph.ComponentGraph
import com.artifexa.graph.ComponentNode
import com.intellij.openapi.project.Project
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel

/**
 * Interactive Swing canvas for the component graph.
 * Supports pan (drag empty space), node drag (drag on a node), zoom (scroll),
 * click-to-navigate, hover tooltip.
 */
class GraphPanel(private val project: Project) : JPanel(), VisualizationView {

    override val component: javax.swing.JComponent get() = this

    private val renderer = GraphRenderer()
    private var graph: ComponentGraph? = null
    private var layout: GraphRenderer.Layout? = null
    private var scale: Double = 1.0
    private var offsetX: Double = 0.0
    private var offsetY: Double = 0.0
    private var dragLast: java.awt.Point? = null
    private var draggedNode: ComponentNode? = null
    private var didDrag: Boolean = false
    private var hoveredNode: ComponentNode? = null

    override var onNodeSelected: ((ComponentNode) -> Unit)? = null

    init {
        background = Color(0x1E, 0x1F, 0x22)
        preferredSize = Dimension(600, 500)
        installMouseHandlers()
        toolTipText = "" // enable tooltip lookup
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                // Recompute layout only when we don't have one yet; otherwise just refit the view
                // so the existing positions stay stable and the graph remains visible.
                val g = graph ?: return
                if (layout == null && g.nodes.isNotEmpty() && width > 0 && height > 0) {
                    layout = renderer.computeLayout(g, width, height)
                }
                fitToWindow()
                repaint()
            }
        })
    }

    override fun setGraph(graph: ComponentGraph?) {
        val g = graph
        if (g == null || g.nodes.isEmpty()) {
            // Update both fields atomically from the EDT's point of view (we're on EDT here).
            this.graph = g
            this.layout = null
            repaint()
            return
        }
        val w = if (width > 0) width else 600
        val h = if (height > 0) height else 500
        val newLayout = renderer.computeLayout(g, w, h)
        // Set layout BEFORE graph so paintComponent never sees (graph != null, layout == null).
        this.layout = newLayout
        this.graph = g
        fitToWindow()
        repaint()
    }

    fun fitToWindow() {
        val l = layout ?: return
        val b = l.bounds
        if (b.width == 0 || b.height == 0) return
        val sx = (width - 80.0) / b.width
        val sy = (height - 80.0) / b.height
        scale = min(sx, sy).coerceAtLeast(0.2).coerceAtMost(3.0)
        offsetX = width / 2.0 - (b.x + b.width / 2.0) * scale
        offsetY = height / 2.0 - (b.y + b.height / 2.0) * scale
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val layout = this.layout
        val graph = this.graph
        if (layout == null || graph == null || graph.nodes.isEmpty()) {
            drawEmptyState(g as Graphics2D)
            return
        }
        // Use a scratch Graphics2D so our transform/stroke/color mutations cannot leak
        // into the parent's graphics state (which previously caused occasional flicker
        // on HiDPI / tooltip-repaint events).
        val scratch = g.create() as Graphics2D
        try {
            scratch.translate(offsetX, offsetY)
            scratch.scale(scale, scale)
            renderer.paint(scratch, graph, layout)
        } finally {
            scratch.dispose()
        }
        drawLegend(g as Graphics2D)
    }

    private fun drawEmptyState(g: Graphics2D) {
        g.color = Color(150, 150, 160)
        val msg = "No graph yet — click \"Analyse Branch\" to start."
        val w = g.fontMetrics.stringWidth(msg)
        g.drawString(msg, (width - w) / 2, height / 2)
    }

    private fun drawLegend(g: Graphics2D) {
        val legend = renderer.colorLegend()
        var y = 20
        g.font = g.font.deriveFont(11f)
        for ((label, color) in legend) {
            g.color = color
            g.fillOval(16, y - 10, 10, 10)
            g.color = Color(220, 220, 228)
            g.drawString(label, 32, y)
            y += 16
        }
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val g = graph ?: return null
        this.layout ?: return null
        val node = nodeAtScreen(event.x, event.y) ?: return null
        val inbound = g.inboundCount(node.id)
        val outbound = g.outboundCount(node.id)
        return """
            <html><b>${escape(node.id)}</b><br/>
            Layer: ${node.layer}<br/>
            +${node.linesAdded} / -${node.linesRemoved}<br/>
            Inbound edges: $inbound<br/>
            Outbound edges: $outbound
            </html>
        """.trimIndent()
    }

    private fun nodeAtScreen(x: Int, y: Int): ComponentNode? {
        val g = graph ?: return null
        val l = layout ?: return null
        val modelX = (x - offsetX) / scale
        val modelY = (y - offsetY) / scale
        return renderer.nodeAt(l, modelX.toInt(), modelY.toInt(), g)
    }

    private fun installMouseHandlers() {
        val handler = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragLast = e.point
                draggedNode = nodeAtScreen(e.x, e.y)
                didDrag = false
                cursor = if (draggedNode != null) java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR)
                         else java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            }
            override fun mouseReleased(e: MouseEvent) {
                val node = draggedNode
                val wasDrag = didDrag
                dragLast = null
                draggedNode = null
                didDrag = false
                cursor = java.awt.Cursor.getDefaultCursor()
                // Treat a press+release on a node with no meaningful drag as a click.
                if (!wasDrag && node != null && nodeAtScreen(e.x, e.y) === node) {
                    onNodeSelected?.invoke(node)
                    openNodeInEditor(project, node)
                }
            }
            override fun mouseDragged(e: MouseEvent) {
                val last = dragLast ?: return
                val dx = e.x - last.x
                val dy = e.y - last.y
                if (!didDrag && (dx * dx + dy * dy) > DRAG_THRESHOLD_SQ) didDrag = true
                val node = draggedNode
                if (node != null) {
                    // Move node in model space — convert screen delta to model delta via scale.
                    val p = layout?.positions?.get(node.id)
                    if (p != null) {
                        p.x += (dx / scale).toInt()
                        p.y += (dy / scale).toInt()
                    }
                } else {
                    offsetX += dx
                    offsetY += dy
                }
                dragLast = e.point
                repaint()
            }
            override fun mouseMoved(e: MouseEvent) {
                // The tooltip system handles hover UX on its own via getToolTipText; we
                // intentionally do NOT trigger repaint here — earlier builds did, and the
                // unnecessary redraw caused the graph to flicker on every cursor move.
                hoveredNode = nodeAtScreen(e.x, e.y)
                cursor = if (hoveredNode != null) java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                         else java.awt.Cursor.getDefaultCursor()
            }
            override fun mouseWheelMoved(e: MouseWheelEvent) {
                val factor = if (e.wheelRotation < 0) 1.1 else 1 / 1.1
                val preMx = (e.x - offsetX) / scale
                val preMy = (e.y - offsetY) / scale
                scale = (scale * factor).coerceIn(0.1, 5.0)
                offsetX = e.x - preMx * scale
                offsetY = e.y - preMy * scale
                repaint()
            }
        }
        addMouseListener(handler)
        addMouseMotionListener(handler)
        addMouseWheelListener(handler)
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun min(a: Double, b: Double): Double = if (a < b) a else b

    private companion object {
        // 4-pixel slop before a press becomes a drag — stops jitter on quick clicks.
        const val DRAG_THRESHOLD_SQ = 16
    }
}
