package com.artifexa.visualisation

import com.artifexa.ai.ClassDetails
import com.artifexa.ai.ClassDetailsExtractor
import com.artifexa.ai.FieldSummary
import com.artifexa.ai.MethodSummary
import com.artifexa.graph.ComponentEdge
import com.artifexa.graph.ComponentGraph
import com.artifexa.graph.ComponentNode
import com.artifexa.graph.EdgeType
import com.artifexa.graph.LayerType
import com.artifexa.graph.NodeStatus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * UML class diagram. Each box has three compartments (header, fields, methods) sized
 * to fit the actual class structure pulled from PSI via [ClassDetailsExtractor].
 *
 * Interaction:
 *   - Pan: drag empty space.
 *   - Drag: press on a box and drag to reposition that single box.
 *   - Zoom: scroll wheel.
 *   - Click: opens the source file.
 *
 * Class details are extracted off the EDT and applied as soon as they arrive, so the
 * diagram appears instantly with placeholder boxes and rebuilds when the structural
 * data is ready. User-positioned boxes keep their location across re-layouts.
 */
class ClassDiagramPanel(private val project: Project) : JPanel(), VisualizationView {

    override val component: JComponent get() = this
    override var onNodeSelected: ((ComponentNode) -> Unit)? = null

    private var graph: ComponentGraph? = null
    private var detailsByFqn: Map<String, ClassDetails> = emptyMap()
    private val boxes: MutableMap<String, Rectangle> = LinkedHashMap()
    private val userMoved: MutableSet<String> = mutableSetOf()
    private var bounds: Rectangle = Rectangle(0, 0, 1, 1)

    private var scale: Double = 1.0
    private var offsetX: Double = 0.0
    private var offsetY: Double = 0.0
    private var dragLast: java.awt.Point? = null
    private var draggedNode: ComponentNode? = null
    private var didDrag: Boolean = false

    /** Bumped each time a graph is set so stale background extractions are ignored. */
    @Volatile private var loadGeneration: Int = 0

    init {
        background = Color(0x1E, 0x1F, 0x22)
        preferredSize = Dimension(600, 500)
        toolTipText = ""
        installMouseHandlers()
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                if (boxes.isEmpty()) relayout(preserveUserMoved = false)
                fitToWindow()
                repaint()
            }
        })
    }

    override fun setGraph(graph: ComponentGraph?) {
        this.graph = graph
        this.detailsByFqn = emptyMap()
        this.userMoved.clear()
        relayout(preserveUserMoved = false)
        fitToWindow()
        repaint()
        scheduleDetailsExtraction(graph)
    }

    private fun scheduleDetailsExtraction(graph: ComponentGraph?) {
        val g = graph ?: return
        if (g.nodes.isEmpty()) return
        val generation = ++loadGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = runCatching { ClassDetailsExtractor(project).extractFor(g.nodes) }
                .getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater {
                if (generation != loadGeneration) return@invokeLater
                detailsByFqn = results.associateBy { it.fqn }
                relayout(preserveUserMoved = true)
                fitToWindow()
                repaint()
            }
        }
    }

    private fun relayout(preserveUserMoved: Boolean) {
        val g = graph
        if (g == null || g.nodes.isEmpty()) {
            boxes.clear()
            bounds = Rectangle(0, 0, 1, 1)
            return
        }
        val previousPositions = if (preserveUserMoved) {
            userMoved.mapNotNull { id -> boxes[id]?.let { id to java.awt.Point(it.x, it.y) } }.toMap()
        } else emptyMap()

        val columnsOrder = listOf(
            LayerType.CONTROLLER, LayerType.SERVICE, LayerType.REPOSITORY,
            LayerType.DOMAIN, LayerType.UTIL, LayerType.TEST, LayerType.UNKNOWN,
        )
        val byLayer = g.nodes.groupBy { it.layer }
        val present = columnsOrder.filter { byLayer[it]?.isNotEmpty() == true }

        // First pass: compute each box's natural width/height. Width is then promoted
        // to the column max so each column is uniform — looks tidier than ragged columns.
        val sized = present.associateWith { layer ->
            byLayer[layer]!!.sortedBy { it.simpleName.lowercase() }.map { node ->
                node to measureBox(node, detailsByFqn[node.id])
            }
        }
        val columnWidths = sized.mapValues { (_, list) ->
            list.maxOfOrNull { it.second.first }?.coerceAtMost(MAX_BOX_W) ?: MIN_BOX_W
        }

        boxes.clear()
        var x = MARGIN
        for (layer in present) {
            val colWidth = columnWidths[layer] ?: MIN_BOX_W
            var y = MARGIN + COL_HEADER_H
            for ((node, dims) in sized[layer].orEmpty()) {
                val (_, h) = dims
                val box = Rectangle(x, y, colWidth, h)
                val saved = previousPositions[node.id]
                if (saved != null) {
                    box.x = saved.x
                    box.y = saved.y
                }
                boxes[node.id] = box
                y += h + ROW_GAP
            }
            x += colWidth + COL_GAP
        }
        bounds = computeBounds(boxes)
    }

    private fun measureBox(node: ComponentNode, details: ClassDetails?): Pair<Int, Int> {
        // We don't have a Graphics2D here, so use a font metrics from a scratch image.
        // Cheap — only called during relayout.
        val img = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            val titleFont = baseFont().deriveFont(13f).deriveFont(Font.BOLD)
            val stereoFont = baseFont().deriveFont(11f).deriveFont(Font.ITALIC)
            val bodyFont = baseFont().deriveFont(11f)
            val titleFm = g.getFontMetrics(titleFont)
            val stereoFm = g.getFontMetrics(stereoFont)
            val bodyFm = g.getFontMetrics(bodyFont)

            val stereotype = stereotypeOf(details)
            val nameWidth = titleFm.stringWidth(node.simpleName)
            val stereoWidth = if (stereotype != null) stereoFm.stringWidth(stereotype) else 0
            var contentWidth = maxOf(nameWidth, stereoWidth)

            val fields = (details?.fields ?: emptyList()).take(MAX_FIELDS)
            val methods = (details?.methods ?: emptyList()).take(MAX_METHODS)
            for (f in fields) contentWidth = maxOf(contentWidth, bodyFm.stringWidth(formatField(f)))
            for (m in methods) contentWidth = maxOf(contentWidth, bodyFm.stringWidth(formatMethod(m)))

            val width = (contentWidth + PAD_X * 2).coerceIn(MIN_BOX_W, MAX_BOX_W)

            val titleHeight = TITLE_PAD_TOP +
                (if (stereotype != null) stereoFm.height else 0) +
                titleFm.height + TITLE_PAD_BOTTOM
            val fieldsHeight = if (fields.isEmpty() && details != null) BODY_EMPTY_H
                else if (fields.isEmpty()) 0
                else SECTION_PAD_TOP + fields.size * (bodyFm.height + LINE_PAD) + SECTION_PAD_BOTTOM
            val methodsHeight = if (methods.isEmpty() && details != null) BODY_EMPTY_H
                else if (methods.isEmpty()) 0
                else SECTION_PAD_TOP + methods.size * (bodyFm.height + LINE_PAD) + SECTION_PAD_BOTTOM

            val height = (titleHeight + fieldsHeight + methodsHeight + 2)
                .coerceAtLeast(MIN_BOX_H)
            return width to height
        } finally {
            g.dispose()
        }
    }

    private fun computeBounds(boxes: Map<String, Rectangle>): Rectangle {
        if (boxes.isEmpty()) return Rectangle(0, 0, 1, 1)
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        for (b in boxes.values) {
            if (b.x < minX) minX = b.x
            if (b.y < minY) minY = b.y
            if (b.x + b.width > maxX) maxX = b.x + b.width
            if (b.y + b.height > maxY) maxY = b.y + b.height
        }
        return Rectangle(minX - MARGIN, minY - COL_HEADER_H, maxX - minX + 2 * MARGIN, maxY - minY + COL_HEADER_H + MARGIN)
    }

    private fun fitToWindow() {
        if (boxes.isEmpty() || bounds.width == 0 || bounds.height == 0) return
        val sx = (width - 40.0) / bounds.width
        val sy = (height - 40.0) / bounds.height
        scale = minOf(sx, sy).coerceAtLeast(0.2).coerceAtMost(2.0)
        offsetX = width / 2.0 - (bounds.x + bounds.width / 2.0) * scale
        offsetY = height / 2.0 - (bounds.y + bounds.height / 2.0) * scale
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val graph = this.graph
        if (graph == null || graph.nodes.isEmpty()) {
            drawEmptyState(g as Graphics2D)
            return
        }
        val scratch = (g as Graphics2D).create() as Graphics2D
        try {
            scratch.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            scratch.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            scratch.translate(offsetX, offsetY)
            scratch.scale(scale, scale)
            drawColumnHeaders(scratch, graph)
            drawEdges(scratch, graph)
            drawBoxes(scratch, graph)
        } finally {
            scratch.dispose()
        }
    }

    private fun drawEmptyState(g: Graphics2D) {
        g.color = Color(150, 150, 160)
        val msg = "No graph yet — click \"Analyse Branch\" to start."
        val w = g.fontMetrics.stringWidth(msg)
        g.drawString(msg, (width - w) / 2, height / 2)
    }

    private fun drawColumnHeaders(g: Graphics2D, graph: ComponentGraph) {
        val byColumn = boxes.entries
            .groupBy { graph.nodesById[it.key]?.layer ?: LayerType.UNKNOWN }
        g.font = baseFont().deriveFont(13f).deriveFont(Font.BOLD)
        for ((layer, entries) in byColumn) {
            val firstBox = entries.minByOrNull { it.value.y } ?: continue
            val box = firstBox.value
            g.color = Color(180, 190, 210)
            g.drawString(layer.name, box.x, box.y - 12)
            g.color = Color(70, 75, 85)
            g.stroke = BasicStroke(1f)
            g.drawLine(box.x, box.y - 6, box.x + box.width, box.y - 6)
        }
    }

    private fun drawEdges(g: Graphics2D, graph: ComponentGraph) {
        for (edge in graph.edges) {
            val from = boxes[edge.fromId] ?: continue
            val to = boxes[edge.toId] ?: continue
            drawEdge(g, edge, from, to)
        }
    }

    private fun drawEdge(g: Graphics2D, edge: ComponentEdge, from: Rectangle, to: Rectangle) {
        val (sx, sy) = sideExitPoint(from, to)
        val (tx, ty) = sideExitPoint(to, from)
        g.color = if (edge.isLayerViolation) Color(0xEF, 0x44, 0x44) else Color(140, 145, 160)
        g.stroke = strokeFor(edge.type, edge.isLayerViolation)
        g.drawLine(sx, sy, tx, ty)
        drawArrowHead(g, sx, sy, tx, ty, edge.type)
    }

    private fun sideExitPoint(from: Rectangle, to: Rectangle): Pair<Int, Int> {
        val fcx = from.x + from.width / 2
        val fcy = from.y + from.height / 2
        val tcx = to.x + to.width / 2
        val tcy = to.y + to.height / 2
        val dx = tcx - fcx
        val dy = tcy - fcy
        return if (Math.abs(dx) * from.height > Math.abs(dy) * from.width) {
            if (dx > 0) (from.x + from.width) to fcy else from.x to fcy
        } else {
            if (dy > 0) fcx to (from.y + from.height) else fcx to from.y
        }
    }

    private fun drawArrowHead(g: Graphics2D, sx: Int, sy: Int, tx: Int, ty: Int, type: EdgeType) {
        val angle = atan2((ty - sy).toDouble(), (tx - sx).toDouble())
        val size = 9.0
        val ax = (tx - size * cos(angle - Math.PI / 7))
        val ay = (ty - size * sin(angle - Math.PI / 7))
        val bx = (tx - size * cos(angle + Math.PI / 7))
        val by = (ty - size * sin(angle + Math.PI / 7))
        val tri = Path2D.Double()
        tri.moveTo(tx.toDouble(), ty.toDouble())
        tri.lineTo(ax, ay)
        tri.lineTo(bx, by)
        tri.closePath()
        when (type) {
            EdgeType.INHERITS, EdgeType.IMPLEMENTS -> {
                val prev = g.color
                g.color = background
                g.fill(tri)
                g.color = prev
                g.draw(tri)
            }
            else -> g.fill(tri)
        }
    }

    private fun strokeFor(type: EdgeType, violation: Boolean): BasicStroke {
        val w = if (violation) 2.0f else 1.3f
        return when (type) {
            EdgeType.CALLS -> BasicStroke(w)
            EdgeType.INHERITS, EdgeType.IMPLEMENTS ->
                BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(6f, 4f), 0f)
            EdgeType.INJECTS ->
                BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(2f, 3f), 0f)
            EdgeType.IMPORTS -> BasicStroke(w * 0.8f)
        }
    }

    private fun drawBoxes(g: Graphics2D, graph: ComponentGraph) {
        for (node in graph.nodes) {
            val r = boxes[node.id] ?: continue
            drawBox(g, node, r, detailsByFqn[node.id])
        }
    }

    private fun drawBox(g: Graphics2D, node: ComponentNode, r: Rectangle, details: ClassDetails?) {
        val shape = RoundRectangle2D.Double(r.x.toDouble(), r.y.toDouble(), r.width.toDouble(), r.height.toDouble(), 10.0, 10.0)
        g.color = fillFor(node)
        g.fill(shape)
        g.color = strokeColorFor(node)
        g.stroke = BasicStroke(if (node.isDirectlyChanged) 2.2f else 1.2f)
        g.draw(shape)

        val accent = accentFor(node.layer)
        g.color = accent
        g.fillRect(r.x + 1, r.y + 1, r.width - 2, 6)

        val titleFont = baseFont().deriveFont(13f).deriveFont(Font.BOLD)
        val stereoFont = baseFont().deriveFont(11f).deriveFont(Font.ITALIC)
        val bodyFont = baseFont().deriveFont(11f)
        val titleFm = g.getFontMetrics(titleFont)
        val stereoFm = g.getFontMetrics(stereoFont)
        val bodyFm = g.getFontMetrics(bodyFont)

        val stereotype = stereotypeOf(details)
        var cursorY = r.y + TITLE_PAD_TOP + 6 // +6 because of the accent stripe
        if (stereotype != null) {
            g.font = stereoFont
            g.color = Color(170, 178, 196)
            val sw = stereoFm.stringWidth(stereotype)
            g.drawString(stereotype, r.x + (r.width - sw) / 2, cursorY + stereoFm.ascent)
            cursorY += stereoFm.height
        }

        val deleted = node.status == NodeStatus.DELETED
        g.font = titleFont
        g.color = if (deleted) Color(0xEF, 0x44, 0x44) else Color(235, 235, 240)
        val name = truncate(node.simpleName, titleFm, r.width - PAD_X * 2)
        val nw = titleFm.stringWidth(name)
        val nameX = r.x + (r.width - nw) / 2
        val nameY = cursorY + titleFm.ascent
        g.drawString(name, nameX, nameY)
        if (deleted) {
            val strikeY = nameY - titleFm.ascent / 3
            g.stroke = BasicStroke(1.4f)
            g.drawLine(nameX - 2, strikeY, nameX + nw + 2, strikeY)
        }
        cursorY = nameY + TITLE_PAD_BOTTOM

        // Subtitle line under the name (layer + change stats)
        g.font = baseFont().deriveFont(10f)
        g.color = Color(150, 156, 172)
        val subtitle = subtitleFor(node)
        val subFm = g.fontMetrics
        val subW = subFm.stringWidth(subtitle)
        g.drawString(subtitle, r.x + (r.width - subW) / 2, cursorY + subFm.ascent - 2)
        cursorY += subFm.height

        // Compartment separator
        g.color = Color(80, 86, 100)
        g.stroke = BasicStroke(1f)
        g.drawLine(r.x + 2, cursorY + 2, r.x + r.width - 2, cursorY + 2)
        cursorY += 4

        // Fields
        val fields = (details?.fields ?: emptyList()).take(MAX_FIELDS)
        if (fields.isNotEmpty()) {
            g.font = bodyFont
            g.color = Color(220, 224, 236)
            for (f in fields) {
                cursorY += bodyFm.ascent + LINE_PAD / 2
                g.drawString(truncate(formatField(f), bodyFm, r.width - PAD_X * 2), r.x + PAD_X, cursorY)
                cursorY += bodyFm.descent + LINE_PAD / 2
            }
            g.color = Color(80, 86, 100)
            g.drawLine(r.x + 2, cursorY + 2, r.x + r.width - 2, cursorY + 2)
            cursorY += 4
        } else if (details != null) {
            cursorY += BODY_EMPTY_H
            g.color = Color(80, 86, 100)
            g.drawLine(r.x + 2, cursorY + 2, r.x + r.width - 2, cursorY + 2)
            cursorY += 4
        }

        // Methods
        val methods = (details?.methods ?: emptyList()).take(MAX_METHODS)
        if (methods.isNotEmpty()) {
            g.font = bodyFont
            for (m in methods) {
                cursorY += bodyFm.ascent + LINE_PAD / 2
                g.color = if (m.changed) Color(0xFB, 0xBF, 0x24) else Color(220, 224, 236)
                g.drawString(truncate(formatMethod(m), bodyFm, r.width - PAD_X * 2), r.x + PAD_X, cursorY)
                cursorY += bodyFm.descent + LINE_PAD / 2
            }
        }
    }

    private fun subtitleFor(node: ComponentNode): String {
        val deleted = node.status == NodeStatus.DELETED
        return if (deleted) "deleted  ·  -${node.linesRemoved}"
            else "${node.layer.name.lowercase()}  ·  +${node.linesAdded}/-${node.linesRemoved}"
    }

    private fun stereotypeOf(details: ClassDetails?): String? = when (details?.kind) {
        "interface" -> "«interface»"
        "enum" -> "«enum»"
        "annotation" -> "«annotation»"
        "object" -> "«object»"
        else -> null
    }

    private fun formatField(f: FieldSummary): String {
        val visibility = visibilityMark(f.modifiers)
        val typeText = f.type.ifBlank { "?" }
        return "$visibility ${f.name} : $typeText"
    }

    private fun formatMethod(m: MethodSummary): String {
        val visibility = visibilityMark(m.modifiers)
        // Trim signature "name(p1, p2)" to "(p1, p2)" — name lives separately for clarity.
        val params = m.signature.substringAfter('(').substringBefore(')')
        val ret = m.returnType.ifBlank { "void" }
        val changedMark = if (m.changed) "● " else ""
        return "$changedMark$visibility ${m.name}($params) : $ret"
    }

    private fun visibilityMark(modifiers: List<String>): String = when {
        "public" in modifiers -> "+"
        "private" in modifiers -> "-"
        "protected" in modifiers -> "#"
        else -> "~"
    }

    private fun fillFor(node: ComponentNode): Color = when (node.status) {
        NodeStatus.CHANGED -> Color(0x2A, 0x3A, 0x55)
        NodeStatus.NEIGHBOUR -> Color(0x3A, 0x33, 0x22)
        NodeStatus.TEST -> Color(0x1F, 0x36, 0x32)
        NodeStatus.DELETED -> Color(0x46, 0x22, 0x22)
    }

    private fun strokeColorFor(node: ComponentNode): Color = when (node.status) {
        NodeStatus.CHANGED -> Color(0x4F, 0x8E, 0xF7)
        NodeStatus.NEIGHBOUR -> Color(0xF5, 0x9E, 0x0B)
        NodeStatus.TEST -> Color(0x22, 0xD3, 0xA5)
        NodeStatus.DELETED -> Color(0xEF, 0x44, 0x44)
    }

    private fun accentFor(layer: LayerType): Color = when (layer) {
        LayerType.CONTROLLER -> Color(0x60, 0xA5, 0xFA)
        LayerType.SERVICE -> Color(0xA7, 0x8B, 0xFA)
        LayerType.REPOSITORY -> Color(0x34, 0xD3, 0x99)
        LayerType.DOMAIN -> Color(0xFB, 0xBF, 0x24)
        LayerType.UTIL -> Color(0x9C, 0xA3, 0xAF)
        LayerType.TEST -> Color(0x22, 0xD3, 0xA5)
        LayerType.UNKNOWN -> Color(0x6B, 0x72, 0x80)
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
        val node = nodeAtScreen(event.x, event.y) ?: return null
        val g = graph ?: return null
        val details = detailsByFqn[node.id]
        val structureLine = if (details != null) "Fields: ${details.fields.size} · Methods: ${details.methods.size}<br/>" else ""
        return """
            <html><b>${escape(node.id)}</b><br/>
            Layer: ${node.layer}<br/>
            +${node.linesAdded} / -${node.linesRemoved}<br/>
            Inbound edges: ${g.inboundCount(node.id)}<br/>
            Outbound edges: ${g.outboundCount(node.id)}<br/>
            ${structureLine}<i>drag to reposition</i>
            </html>
        """.trimIndent()
    }

    private fun nodeAtScreen(x: Int, y: Int): ComponentNode? {
        val graph = graph ?: return null
        val mx = ((x - offsetX) / scale).toInt()
        val my = ((y - offsetY) / scale).toInt()
        for (node in graph.nodes.asReversed()) {
            val r = boxes[node.id] ?: continue
            if (r.contains(mx, my)) return node
        }
        return null
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
                val wasDrag = didDrag
                val node = draggedNode
                dragLast = null
                draggedNode = null
                didDrag = false
                cursor = java.awt.Cursor.getDefaultCursor()
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
                    // Convert screen delta to model delta via the current scale.
                    val box = boxes[node.id]
                    if (box != null) {
                        box.x += (dx / scale).toInt()
                        box.y += (dy / scale).toInt()
                        userMoved += node.id
                        bounds = computeBounds(boxes)
                    }
                } else {
                    offsetX += dx
                    offsetY += dy
                }
                dragLast = e.point
                repaint()
            }
            override fun mouseMoved(e: MouseEvent) {
                cursor = if (nodeAtScreen(e.x, e.y) != null)
                    java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                else java.awt.Cursor.getDefaultCursor()
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

    private fun baseFont(): Font = font ?: Font("SansSerif", Font.PLAIN, 12)

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val MIN_BOX_W = 180
        const val MAX_BOX_W = 320
        const val MIN_BOX_H = 64
        const val COL_GAP = 70
        const val ROW_GAP = 28
        const val MARGIN = 24
        const val COL_HEADER_H = 28
        const val PAD_X = 10
        const val TITLE_PAD_TOP = 8
        const val TITLE_PAD_BOTTOM = 4
        const val SECTION_PAD_TOP = 4
        const val SECTION_PAD_BOTTOM = 4
        const val LINE_PAD = 2
        const val BODY_EMPTY_H = 8
        const val MAX_FIELDS = 12
        const val MAX_METHODS = 14
        const val DRAG_THRESHOLD_SQ = 16
    }
}
