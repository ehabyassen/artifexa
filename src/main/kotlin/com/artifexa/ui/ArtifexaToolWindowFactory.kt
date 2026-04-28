package com.artifexa.ui

import com.artifexa.graph.ComponentGraph
import com.artifexa.visualisation.GraphPanel
import com.artifexa.visualisation.VisualizationPanel
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel

class ArtifexaToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ArtifexaToolWindowPanel(project)
        project.putUserData(TOOL_WINDOW_PANEL, panel)

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        val TOOL_WINDOW_PANEL: Key<ArtifexaToolWindowPanel> = Key.create("artifexa.toolWindow.panel")
    }
}

/**
 * Root Swing content for the Artifexa tool window:
 * toolbar + task description panel + graph canvas + review panel.
 */
class ArtifexaToolWindowPanel(val project: Project) : JPanel(BorderLayout()) {

    val visualizationPanel: VisualizationPanel = VisualizationPanel(project)
    /** Kept as a convenience alias so existing call sites that reach for the graph view still work. */
    val graphPanel: GraphPanel get() = visualizationPanel.graphPanel
    val reviewPanel: ReviewPanel = ReviewPanel(project)
    val taskPanel: TaskDescriptionPanel = TaskDescriptionPanel(project)
    val statusLabel: JLabel = JLabel(" Ready.")

    var currentGraph: ComponentGraph? = null
        set(value) {
            field = value
            visualizationPanel.setGraph(value)
            if (value != null) {
                statusLabel.text = " ${value.nodes.size} nodes, ${value.edges.size} edges" +
                    if (value.layerViolations().isNotEmpty()) " · ${value.layerViolations().size} layer violation(s)" else ""
            }
        }

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        add(buildCentre(), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    private fun buildToolbar(): JPanel {
        val actionManager = ActionManager.getInstance()
        val group = DefaultActionGroup()
        actionManager.getAction("Artifexa.AnalyseBranch")?.let { group.add(it) }
        actionManager.getAction("Artifexa.RunAiReview")?.let { group.add(it) }
        val toolbar: ActionToolbar = actionManager.createActionToolbar("ArtifexaToolbar", group, true)
        toolbar.targetComponent = this

        val wrap = JPanel(BorderLayout())
        wrap.add(toolbar.component, BorderLayout.CENTER)
        return wrap
    }

    private fun buildCentre(): OnePixelSplitter {
        val top = OnePixelSplitter(true, 0.18f)
        top.firstComponent = taskPanel
        val lower = OnePixelSplitter(false, 0.65f)
        lower.firstComponent = visualizationPanel
        lower.secondComponent = reviewPanel
        top.secondComponent = lower
        return top
    }
}
