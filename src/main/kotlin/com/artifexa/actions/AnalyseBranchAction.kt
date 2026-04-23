package com.artifexa.actions

import com.artifexa.graph.ComponentGraphBuilder
import com.artifexa.graph.LayerClassifier
import com.artifexa.settings.ArtifexaSettings
import com.artifexa.ui.ArtifexaToolWindowFactory
import com.artifexa.ui.ArtifexaToolWindowPanel
import com.artifexa.vcs.BranchAnalyser
import com.artifexa.vcs.BranchDiff
import com.artifexa.vcs.BranchPointDetector
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

class AnalyseBranchAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = project.getUserData(ArtifexaToolWindowFactory.TOOL_WINDOW_PANEL)
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Artifexa")
        toolWindow?.show()

        object : Task.Backgroundable(project, "Artifexa: analysing branch…", true) {
            override fun run(indicator: ProgressIndicator) {
                runAnalysis(project, panel, indicator)
            }
        }.queue()
    }

    private fun runAnalysis(project: Project, panel: ArtifexaToolWindowPanel?, indicator: ProgressIndicator) {
        val settings = ArtifexaSettings.getInstance()
        val detector = BranchPointDetector(project)
        val repo = detector.currentRepository() ?: run {
            notify(project, "No Git repository found in this project.", NotificationType.WARNING)
            return
        }

        indicator.text = "Detecting branch point…"
        val bp = detector.detectBranchPoint(repo, settings.state.targetBranch) ?: run {
            notify(project, "Could not detect branch point relative to '${settings.state.targetBranch}'. " +
                "Ensure the target branch exists locally or as origin/${settings.state.targetBranch}.", NotificationType.WARNING)
            return
        }

        indicator.text = "Walking commits and aggregating diffs…"
        val branchDiff: BranchDiff = BranchAnalyser(project).analyse(repo, bp.commitHash, settings.state.targetBranch)

        indicator.text = "Building component graph…"
        val classifier = LayerClassifier(settings.state.layerPatterns.mapKeys { (k, _) ->
            runCatching { com.artifexa.graph.LayerType.valueOf(k) }.getOrDefault(com.artifexa.graph.LayerType.UNKNOWN)
        }.filterKeys { it != com.artifexa.graph.LayerType.UNKNOWN })
        val builder = ComponentGraphBuilder(project, classifier, settings.state.maxGraphNodes)
        val graph = builder.build(branchDiff)
        val diag = builder.lastDiagnostics

        ApplicationManager.getApplication().invokeLater {
            panel?.let {
                it.currentGraph = graph
                it.putClientProperty(BRANCH_DIFF_KEY, branchDiff)
                // Only hydrate the task description from disk when the user hasn't typed anything yet,
                // so that Analyse Branch never erases in-progress input.
                if (it.taskPanel.text().isBlank()) {
                    it.taskPanel.loadForBranch(branchDiff.branchName)
                }
            }
        }

        if (graph.nodes.isEmpty()) {
            val detail = buildString {
                append("No changed components detected on '${branchDiff.branchName}' relative to '${branchDiff.targetBranch}'. ")
                append("Diagnostics: ${diag.totalFileDiffs} changed files | ${diag.jvmFileDiffs} JVM source(s)")
                if (diag.vFileMisses > 0) append(" | ${diag.vFileMisses} could not be located on disk (path mismatch)")
                if (diag.psiMisses > 0) append(" | ${diag.psiMisses} had no PSI entry")
                if (diag.plainTextFiles > 0) append(" | ${diag.plainTextFiles} treated as plain text (project not imported as Java/Maven?)")
                if (diag.nonClassOwnerFiles > 0) append(" | ${diag.nonClassOwnerFiles} non-class files")
                if (diag.jvmFileDiffs == 0 && diag.totalFileDiffs > 0) {
                    append(". First few changed paths: ${diag.samplePaths.take(5).joinToString(" | ")}")
                } else if (diag.plainTextFiles > 0 || diag.psiMisses > 0) {
                    append(". Try: import the project as Maven/Gradle, wait for indexing to finish, then re-run.")
                }
            }
            notify(project, detail, NotificationType.WARNING)
        }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Artifexa")
            .createNotification("Artifexa", message, type)
            .notify(project)
    }

    companion object {
        const val BRANCH_DIFF_KEY: String = "artifexa.currentBranchDiff"
    }
}
