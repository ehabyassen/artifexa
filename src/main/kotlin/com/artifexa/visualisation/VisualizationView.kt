package com.artifexa.visualisation

import com.artifexa.graph.ComponentGraph
import com.artifexa.graph.ComponentNode
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import javax.swing.JComponent

/**
 * One pluggable rendering of a [ComponentGraph]. The container swaps between
 * implementations via a dropdown.
 */
interface VisualizationView {
    val component: JComponent
    fun setGraph(graph: ComponentGraph?)
    var onNodeSelected: ((ComponentNode) -> Unit)?
}

enum class VisualizationKind(val displayName: String) {
    GRAPH("Force-directed graph"),
    CLASS_DIAGRAM("Class diagram"),
    DEPENDENCY_MATRIX("Dependency matrix");

    override fun toString(): String = displayName
}

internal fun openNodeInEditor(project: Project, node: ComponentNode) {
    val path = node.filePath ?: return
    val projectBase = project.basePath ?: return
    val resolved = if (path.startsWith("/")) path else "$projectBase/$path"
    val vFile = LocalFileSystem.getInstance().findFileByPath(resolved) ?: return
    OpenFileDescriptor(project, vFile).navigate(true)
}
