package com.artifexa.visualisation

import com.artifexa.graph.ComponentGraph
import com.artifexa.graph.ComponentNode
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel

/**
 * Interactive Swing canvas for the component graph.
 * Supports pan (click-drag), zoom (scroll), click-to-navigate, hover tooltip.
 */
class GraphPanel(private val project: Project) : JPanel() {

    private val renderer = GraphRenderer()
    private var graph: ComponentGraph? = null
    private var layout: GraphRenderer.Layout? = null
    private var scale: Double = 1.0
    private var offsetX: Double = 0.0
    private var offsetY: Double = 0.0
    private var dragLast: java.awt.Point? = null
    private var hoveredNode: ComponentNode? = null

    var onNodeSelected: ((ComponentNode) -> Unit)? = null

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

    fun setGraph(g: ComponentGraph?) {
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
        val g2 = g as Graphics2D
        val layout = this.layout
        val graph = this.graph
        if (layout == null || graph == null || graph.nodes.isEmpty()) {
            drawEmptyState(g2)
            return
        }
        val original = g2.transform
        val t = AffineTransform()
        t.translate(offsetX, offsetY)
        t.scale(scale, scale)
        g2.transform = t
        renderer.paint(g2, graph, layout)
        g2.transform = original

        drawLegend(g2)
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
            override fun mousePressed(e: MouseEvent) { dragLast = e.point }
            override fun mouseReleased(e: MouseEvent) { dragLast = null }
            override fun mouseDragged(e: MouseEvent) {
                val last = dragLast ?: return
                offsetX += e.x - last.x
                offsetY += e.y - last.y
                dragLast = e.point
                repaint()
            }
            override fun mouseMoved(e: MouseEvent) {
                val hit = nodeAtScreen(e.x, e.y)
                if (hit != hoveredNode) { hoveredNode = hit; repaint() }
            }
            override fun mouseClicked(e: MouseEvent) {
                val node = nodeAtScreen(e.x, e.y) ?: return
                onNodeSelected?.invoke(node)
                openInEditor(node)
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

    private fun openInEditor(node: ComponentNode) {
        val path = node.filePath ?: return
        val projectBase = project.basePath ?: return
        val resolved = if (path.startsWith("/")) path else "$projectBase/$path"
        val vFile = LocalFileSystem.getInstance().findFileByPath(resolved) ?: return
        OpenFileDescriptor(project, vFile).navigate(true)
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun min(a: Double, b: Double): Double = if (a < b) a else b
}
