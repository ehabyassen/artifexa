package com.artifexa.visualisation

import com.artifexa.graph.ComponentGraph
import com.artifexa.graph.ComponentNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Container that hosts every available [VisualizationView] in a [CardLayout]
 * and lets the user pick which one to show via a dropdown at the top.
 *
 * The same [ComponentGraph] is pushed to every view, so switching is instant
 * and the previously chosen view's pan/zoom state is preserved.
 */
class VisualizationPanel(project: Project) : JPanel(BorderLayout()) {

    val graphPanel: GraphPanel = GraphPanel(project)
    val classDiagramPanel: ClassDiagramPanel = ClassDiagramPanel(project)
    val matrixPanel: DependencyMatrixPanel = DependencyMatrixPanel(project)

    private val views: Map<VisualizationKind, VisualizationView> = linkedMapOf(
        VisualizationKind.GRAPH to graphPanel,
        VisualizationKind.CLASS_DIAGRAM to classDiagramPanel,
        VisualizationKind.DEPENDENCY_MATRIX to matrixPanel,
    )

    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)

    var onNodeSelected: ((ComponentNode) -> Unit)? = null
        set(value) {
            field = value
            views.values.forEach { it.onNodeSelected = value }
        }

    init {
        background = Color(0x1E, 0x1F, 0x22)
        for ((kind, view) in views) cards.add(view.component, kind.name)
        add(buildHeader(), BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)
    }

    fun setGraph(graph: ComponentGraph?) {
        views.values.forEach { it.setGraph(graph) }
    }

    private fun buildHeader(): JPanel {
        val combo = ComboBox(VisualizationKind.values())
        combo.selectedItem = VisualizationKind.GRAPH
        combo.addActionListener {
            val sel = combo.selectedItem as? VisualizationKind ?: return@addActionListener
            cardLayout.show(cards, sel.name)
        }

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        bar.background = Color(0x26, 0x28, 0x2C)
        bar.border = BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0x33, 0x36, 0x3C))
        val label = JLabel("View:")
        label.foreground = Color(220, 224, 232)
        bar.add(label)
        bar.add(combo)
        return bar
    }
}
