package com.artifexa.visualisation

import com.artifexa.graph.ComponentEdge
import com.artifexa.graph.ComponentGraph
import com.artifexa.graph.ComponentNode
import com.artifexa.graph.LayerType
import com.intellij.openapi.project.Project
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * NxN adjacency-matrix view of the component graph.
 *
 * - Rows are sources (callers), columns are targets (callees).
 * - A filled cell at (row=i, col=j) means there is one or more edges from node i → node j.
 * - Edges flagged as layer violations are drawn red.
 * - Both axes are sorted by layer (Controller → Service → Repository → Domain → Util → Test → Unknown)
 *   then alphabetically by simple name, so dependency clusters by layer become visually obvious.
 */
class DependencyMatrixPanel(private val project: Project) : JPanel(), VisualizationView {

    override val component: JComponent get() = this
    override var onNodeSelected: ((ComponentNode) -> Unit)? = null

    private var graph: ComponentGraph? = null
    private var sortedNodes: List<ComponentNode> = emptyList()
    private var indexById: Map<String, Int> = emptyMap()
    /** edges[from][to] -> list of edges between that pair (usually 0 or 1, can be more for multi-typed) */
    private var edgeMatrix: Array<Array<MutableList<ComponentEdge>>> = emptyArray()

    private var scale: Double = 1.0
    private var offsetX: Double = 0.0
    private var offsetY: Double = 0.0
    private var dragLast: java.awt.Point? = null
    private var didDrag: Boolean = false
    private var pressedCell: Pair<Int, Int>? = null
    private var hoveredCell: Pair<Int, Int>? = null

    init {
        background = Color(0x1E, 0x1F, 0x22)
        preferredSize = Dimension(600, 500)
        toolTipText = ""
        installMouseHandlers()
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                fitToWindow()
                repaint()
            }
        })
    }

    override fun setGraph(graph: ComponentGraph?) {
        this.graph = graph
        rebuildMatrix()
        fitToWindow()
        repaint()
    }

    private fun rebuildMatrix() {
        val g = graph
        if (g == null || g.nodes.isEmpty()) {
            sortedNodes = emptyList()
            indexById = emptyMap()
            edgeMatrix = emptyArray()
            return
        }
        val layerOrder = listOf(
            LayerType.CONTROLLER, LayerType.SERVICE, LayerType.REPOSITORY,
            LayerType.DOMAIN, LayerType.UTIL, LayerType.TEST, LayerType.UNKNOWN,
        )
        val rank = layerOrder.withIndex().associate { (i, v) -> v to i }
        sortedNodes = g.nodes.sortedWith(
            compareBy({ rank[it.layer] ?: Int.MAX_VALUE }, { it.simpleName.lowercase() })
        )
        indexById = sortedNodes.withIndex().associate { (i, n) -> n.id to i }
        val n = sortedNodes.size
        edgeMatrix = Array(n) { Array(n) { mutableListOf<ComponentEdge>() } }
        for (e in g.edges) {
            val i = indexById[e.fromId] ?: continue
            val j = indexById[e.toId] ?: continue
            edgeMatrix[i][j].add(e)
        }
    }

    private fun matrixWidth(): Int = LABEL_W + sortedNodes.size * cellSize
    private fun matrixHeight(): Int = LABEL_H + sortedNodes.size * cellSize

    private fun fitToWindow() {
        if (sortedNodes.isEmpty()) return
        val mw = matrixWidth().toDouble()
        val mh = matrixHeight().toDouble()
        if (mw == 0.0 || mh == 0.0) return
        val sx = (width - 40.0) / mw
        val sy = (height - 40.0) / mh
        scale = minOf(sx, sy).coerceAtLeast(0.2).coerceAtMost(2.5)
        offsetX = (width - mw * scale) / 2.0
        offsetY = (height - mh * scale) / 2.0
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (sortedNodes.isEmpty()) {
            drawEmptyState(g as Graphics2D)
            return
        }
        val scratch = (g as Graphics2D).create() as Graphics2D
        try {
            scratch.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            scratch.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            scratch.translate(offsetX, offsetY)
            scratch.scale(scale, scale)
            drawMatrix(scratch)
            drawLabels(scratch)
        } finally {
            scratch.dispose()
        }
        drawHint(g)
    }

    private fun drawEmptyState(g: Graphics2D) {
        g.color = Color(150, 150, 160)
        val msg = "No graph yet — click \"Analyse Branch\" to start."
        val w = g.fontMetrics.stringWidth(msg)
        g.drawString(msg, (width - w) / 2, height / 2)
    }

    private fun drawHint(g: Graphics2D) {
        g.color = Color(150, 155, 165)
        g.font = g.font.deriveFont(11f)
        g.drawString("Row → Column means \"caller depends on callee\". Red cells are layer violations.", 16, height - 12)
    }

    private fun drawMatrix(g: Graphics2D) {
        val n = sortedNodes.size
        // Background grid.
        g.color = Color(0x26, 0x28, 0x2C)
        g.fillRect(LABEL_W, LABEL_H, n * cellSize, n * cellSize)

        // Layer band stripes — paint a faint background per layer block on the diagonal.
        var blockStart = 0
        while (blockStart < n) {
            val layer = sortedNodes[blockStart].layer
            var blockEnd = blockStart + 1
            while (blockEnd < n && sortedNodes[blockEnd].layer == layer) blockEnd++
            val size = (blockEnd - blockStart) * cellSize
            g.color = layerBand(layer)
            g.fillRect(LABEL_W + blockStart * cellSize, LABEL_H + blockStart * cellSize, size, size)
            blockStart = blockEnd
        }

        // Grid lines.
        g.color = Color(0x33, 0x36, 0x3C)
        g.stroke = BasicStroke(1f)
        for (i in 0..n) {
            val x = LABEL_W + i * cellSize
            val y = LABEL_H + i * cellSize
            g.drawLine(x, LABEL_H, x, LABEL_H + n * cellSize)
            g.drawLine(LABEL_W, y, LABEL_W + n * cellSize, y)
        }

        // Cells.
        for (i in 0 until n) {
            for (j in 0 until n) {
                val edges = edgeMatrix[i][j]
                if (edges.isEmpty()) continue
                val violation = edges.any { it.isLayerViolation }
                g.color = if (violation) Color(0xEF, 0x44, 0x44)
                          else cellColor(edges.size)
                g.fillRect(LABEL_W + j * cellSize + 1, LABEL_H + i * cellSize + 1, cellSize - 1, cellSize - 1)
            }
        }

        // Highlight hovered row/column.
        hoveredCell?.let { (i, j) ->
            g.color = Color(255, 255, 255, 30)
            g.fillRect(LABEL_W, LABEL_H + i * cellSize, n * cellSize, cellSize)
            g.fillRect(LABEL_W + j * cellSize, LABEL_H, cellSize, n * cellSize)
        }
    }

    private fun cellColor(weight: Int): Color {
        // Darker (more saturated) when the same row/column has more edges.
        val base = 0x4F8EF7
        val r = (base shr 16) and 0xFF
        val g = (base shr 8) and 0xFF
        val b = base and 0xFF
        val alpha = (140 + 30 * (weight - 1)).coerceAtMost(255)
        return Color(r, g, b, alpha)
    }

    private fun layerBand(layer: LayerType): Color = when (layer) {
        LayerType.CONTROLLER -> Color(0x60, 0xA5, 0xFA, 18)
        LayerType.SERVICE -> Color(0xA7, 0x8B, 0xFA, 18)
        LayerType.REPOSITORY -> Color(0x34, 0xD3, 0x99, 18)
        LayerType.DOMAIN -> Color(0xFB, 0xBF, 0x24, 18)
        LayerType.UTIL -> Color(0x9C, 0xA3, 0xAF, 14)
        LayerType.TEST -> Color(0x22, 0xD3, 0xA5, 14)
        LayerType.UNKNOWN -> Color(0x6B, 0x72, 0x80, 14)
    }

    private fun drawLabels(g: Graphics2D) {
        g.color = Color(220, 224, 232)
        g.font = g.font.deriveFont(11f)
        val fm = g.fontMetrics
        val rowTextY = (cellSize + fm.ascent) / 2 - 3

        // Row labels (left of the matrix).
        for ((i, node) in sortedNodes.withIndex()) {
            val text = truncate(node.simpleName, fm, LABEL_W - 8)
            g.drawString(text, LABEL_W - fm.stringWidth(text) - 4, LABEL_H + i * cellSize + rowTextY)
        }
        // Column labels (above the matrix, rotated 90° CCW).
        val originalTransform = g.transform
        for ((j, node) in sortedNodes.withIndex()) {
            val text = truncate(node.simpleName, fm, LABEL_H - 8)
            g.transform = originalTransform
            g.translate((LABEL_W + j * cellSize + (cellSize + fm.ascent) / 2 - 3).toDouble(), (LABEL_H - 4).toDouble())
            g.rotate(-Math.PI / 2)
            g.drawString(text, 0, 0)
        }
        g.transform = originalTransform
    }

    private fun truncate(s: String, fm: java.awt.FontMetrics, maxW: Int): String {
        if (fm.stringWidth(s) <= maxW) return s
        var lo = 0; var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fm.stringWidth(s.substring(0, mid) + "…") <= maxW) lo = mid else hi = mid - 1
        }
        return if (lo <= 0) "…" else s.substring(0, lo) + "…"
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val cell = cellAtScreen(event.x, event.y) ?: return null
        val (i, j) = cell
        val from = sortedNodes.getOrNull(i) ?: return null
        val to = sortedNodes.getOrNull(j) ?: return null
        val edges = edgeMatrix[i][j]
        val edgeSummary = if (edges.isEmpty()) "no direct dependency"
                          else edges.joinToString(", ") { it.type.name.lowercase() } +
                              if (edges.any { it.isLayerViolation }) " · LAYER VIOLATION" else ""
        return """
            <html><b>${escape(from.simpleName)}</b> → <b>${escape(to.simpleName)}</b><br/>
            $edgeSummary
            </html>
        """.trimIndent()
    }

    /** Returns (rowIndex, colIndex) for the cell under screen-space (x,y), or null if outside the matrix. */
    private fun cellAtScreen(x: Int, y: Int): Pair<Int, Int>? {
        if (sortedNodes.isEmpty()) return null
        val mx = ((x - offsetX) / scale).toInt()
        val my = ((y - offsetY) / scale).toInt()
        if (mx < LABEL_W || my < LABEL_H) return null
        val col = (mx - LABEL_W) / cellSize
        val row = (my - LABEL_H) / cellSize
        if (row !in sortedNodes.indices || col !in sortedNodes.indices) return null
        return row to col
    }

    private fun installMouseHandlers() {
        val handler = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragLast = e.point
                pressedCell = cellAtScreen(e.x, e.y)
                didDrag = false
            }
            override fun mouseReleased(e: MouseEvent) {
                val wasDrag = didDrag
                val pressed = pressedCell
                dragLast = null
                pressedCell = null
                didDrag = false
                if (!wasDrag && pressed != null && cellAtScreen(e.x, e.y) == pressed) {
                    val (i, _) = pressed
                    val node = sortedNodes.getOrNull(i) ?: return
                    onNodeSelected?.invoke(node)
                    openNodeInEditor(project, node)
                }
            }
            override fun mouseDragged(e: MouseEvent) {
                val last = dragLast ?: return
                val dx = e.x - last.x
                val dy = e.y - last.y
                if (!didDrag && (dx * dx + dy * dy) > DRAG_THRESHOLD_SQ) didDrag = true
                offsetX += dx
                offsetY += dy
                dragLast = e.point
                repaint()
            }
            override fun mouseMoved(e: MouseEvent) {
                val newCell = cellAtScreen(e.x, e.y)
                if (newCell != hoveredCell) {
                    hoveredCell = newCell
                    repaint()
                }
            }
            override fun mouseExited(e: MouseEvent) {
                if (hoveredCell != null) { hoveredCell = null; repaint() }
            }
            override fun mouseWheelMoved(e: MouseWheelEvent) {
                val factor = if (e.wheelRotation < 0) 1.1 else 1 / 1.1
                val preMx = (e.x - offsetX) / scale
                val preMy = (e.y - offsetY) / scale
                scale = (scale * factor).coerceIn(0.1, 4.0)
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

    private val cellSize: Int
        get() {
            val n = sortedNodes.size
            // Adapt cell size to keep small graphs readable but big graphs visible.
            return when {
                n <= 20 -> 28
                n <= 40 -> 22
                n <= 80 -> 16
                else -> 12
            }
        }

    private companion object {
        const val LABEL_W = 160
        const val LABEL_H = 160
        const val DRAG_THRESHOLD_SQ = 16
    }
}
