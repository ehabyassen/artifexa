package com.artifexa.actions

import com.artifexa.ai.AiReviewEngine
import com.artifexa.ai.LlmClient
import com.artifexa.ai.ReviewResult
import com.artifexa.ai.StandardsFetcher
import com.artifexa.settings.ArtifexaSettings
import com.artifexa.ui.ArtifexaToolWindowFactory
import com.artifexa.ui.ArtifexaToolWindowPanel
import com.artifexa.vcs.BranchDiff
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.runBlocking

class RunAiReviewAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = project.getUserData(ArtifexaToolWindowFactory.TOOL_WINDOW_PANEL) ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Artifexa")
        toolWindow?.show()

        val graph = panel.currentGraph
        val branchDiff = panel.getClientProperty(AnalyseBranchAction.BRANCH_DIFF_KEY) as? BranchDiff
        if (graph == null || branchDiff == null) {
            notify(project, "Run \"Analyse Branch\" first to build the component graph.", NotificationType.INFORMATION)
            return
        }

        panel.reviewPanel.showLoading()
        panel.taskPanel.saveForBranch(branchDiff.branchName)
        val task = panel.taskPanel.text()

        object : Task.Backgroundable(project, "Artifexa: running AI review…", true) {
            override fun run(indicator: ProgressIndicator) {
                val engine = AiReviewEngine()
                val settings = ArtifexaSettings.getInstance()
                val standardsUrl = settings.state.codeReviewStandardsUrl.trim()
                var standards: String? = null
                var standardsSource: String? = null
                if (standardsUrl.isNotEmpty()) {
                    indicator.text = "Fetching code review standards…"
                    StandardsFetcher().fetch(standardsUrl).onSuccess { fetched ->
                        standards = if (fetched.truncated) fetched.content + "\n\n[… truncated]" else fetched.content
                        standardsSource = fetched.sourceUrl
                    }.onFailure { err ->
                        notify(project,
                            "Could not fetch code review standards from $standardsUrl: ${err.message}. Proceeding without them.",
                            NotificationType.WARNING)
                    }
                }
                indicator.text = "Running AI review…"
                try {
                    val result: ReviewResult = runBlocking {
                        engine.runReview(branchDiff, graph, task, standards, standardsSource)
                    }
                    ApplicationManager.getApplication().invokeLater {
                        panel.reviewPanel.renderResult(result)
                    }
                } catch (e: LlmClient.LlmException) {
                    ApplicationManager.getApplication().invokeLater {
                        panel.reviewPanel.renderError(e.message ?: "AI review failed.")
                    }
                    notify(project, e.message ?: "AI review failed.", NotificationType.ERROR)
                } catch (e: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        panel.reviewPanel.renderError("Could not parse AI response: ${e.message}")
                    }
                    notify(project, "AI review failed: ${e.message}", NotificationType.ERROR)
                }
            }
        }.queue()
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Artifexa")
            .createNotification("Artifexa", message, type)
            .notify(project)
    }
}
