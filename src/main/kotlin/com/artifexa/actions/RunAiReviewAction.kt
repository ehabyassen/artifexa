package com.artifexa.actions

import com.artifexa.ai.AiReviewEngine
import com.artifexa.ai.ClassDetailsExtractor
import com.artifexa.ai.LlmClient
import com.artifexa.ai.LocalReviewEngine
import com.artifexa.ai.ReviewHistory
import com.artifexa.ai.ReviewHistoryStore
import com.artifexa.ai.ReviewResult
import com.artifexa.ai.StandardsFetcher
import com.artifexa.settings.ArtifexaSettings
import com.artifexa.ui.ArtifexaToolWindowFactory
import com.artifexa.ui.ArtifexaToolWindowPanel
import com.artifexa.vcs.BranchAnalyser
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
import git4idea.repo.GitRepositoryManager
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

        object : Task.Backgroundable(project, "Artifexa: running review…", true) {
            override fun run(indicator: ProgressIndicator) {
                val settings = ArtifexaSettings.getInstance()
                val isLocal = settings.state.localOnlyMode
                val standardsUrl = settings.state.codeReviewStandardsUrl.trim()
                var standards: String? = null
                var standardsSource: String? = null

                val allowFetch = !isLocal ||
                    standardsUrl.startsWith("file://") || standardsUrl.startsWith("/")
                if (standardsUrl.isNotEmpty() && allowFetch) {
                    indicator.text = "Loading code review standards…"
                    StandardsFetcher().fetch(standardsUrl).onSuccess { fetched ->
                        standards = if (fetched.truncated) fetched.content + "\n\n[… truncated]" else fetched.content
                        standardsSource = fetched.sourceUrl
                    }.onFailure { err ->
                        notify(project,
                            "Could not load code review standards from $standardsUrl: ${err.message}. Proceeding without them.",
                            NotificationType.WARNING)
                    }
                } else if (standardsUrl.isNotEmpty() && isLocal) {
                    notify(project,
                        "Standards URL is remote but Local-only mode is on — skipping the standards document.",
                        NotificationType.INFORMATION)
                }

                indicator.text = "Extracting class details and diff hunks…"
                val classDetails = ClassDetailsExtractor(project).extract(graph, branchDiff)

                // Collect structured diff hunks per directly-changed component.
                val hunksByFqn = collectHunksPerComponent(project, branchDiff, graph)

                // Each review is run against the current code state. The only thing we
                // carry across runs is the developer's explicit discard list — comments
                // they have actively dismissed should not reappear. Anything else is up to
                // the AI to re-emit (or not) based on what is in the diff right now, so
                // fixed issues naturally drop off without us needing to track them.
                val historyStore = ReviewHistoryStore(project)
                val discardedSignatures = historyStore.load(branchDiff.branchName).discardedSignatures

                indicator.text = if (isLocal) "Running offline review…" else "Running AI review (line-by-line)…"
                try {
                    val rawResult: ReviewResult = if (isLocal) {
                        LocalReviewEngine().review(branchDiff, graph, classDetails, task, standards, standardsSource)
                    } else {
                        runBlocking {
                            AiReviewEngine().runReview(
                                branchDiff, graph, task, standards, standardsSource, classDetails, hunksByFqn,
                            )
                        }
                    }

                    val result = filterDiscarded(rawResult, discardedSignatures)

                    // Persist only the discard list — no carry-forward of past comments,
                    // so the next run starts from a clean slate against the current code.
                    historyStore.save(
                        branchDiff.branchName,
                        ReviewHistory(branchDiff.branchName, emptyList(), discardedSignatures),
                    )

                    if (discardedSignatures.isNotEmpty()) {
                        notify(project,
                            "Suppressed ${discardedSignatures.size} previously discarded comment(s).",
                            NotificationType.INFORMATION)
                    }

                    ApplicationManager.getApplication().invokeLater {
                        panel.reviewPanel.renderResult(result, hunksByFqn, branchDiff.branchName)
                    }
                } catch (e: LlmClient.LlmException) {
                    ApplicationManager.getApplication().invokeLater {
                        panel.reviewPanel.renderError(e.message ?: "AI review failed.")
                    }
                    notify(project, e.message ?: "AI review failed.", NotificationType.ERROR)
                } catch (e: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        panel.reviewPanel.renderError("Review failed: ${e.message}")
                    }
                    notify(project, "Review failed: ${e.message}", NotificationType.ERROR)
                }
            }
        }.queue()
    }

    private fun filterDiscarded(result: ReviewResult, discarded: Set<String>): ReviewResult {
        if (discarded.isEmpty()) return result
        val filteredReviews = result.componentReviews.map { cr ->
            cr.copy(lineComments = cr.lineComments.filter { lc ->
                ReviewHistoryStore.signatureOf(cr.fqn, lc.comment) !in discarded
            })
        }.filter { it.lineComments.isNotEmpty() || it.summary.isNotBlank() }
        val filteredFindings = result.bestPracticesFindings.filter { f ->
            val fq = f.affectedComponent ?: return@filter true
            ReviewHistoryStore.signatureOf(fq, "${f.title} ${f.description}") !in discarded
        }
        return result.copy(
            bestPracticesFindings = filteredFindings,
            componentReviews = filteredReviews,
        )
    }

    private fun collectHunksPerComponent(
        project: Project,
        branchDiff: BranchDiff,
        graph: com.artifexa.graph.ComponentGraph,
    ): Map<String, List<BranchAnalyser.DiffHunk>> {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return emptyMap()
        val branchPoint = branchDiff.branchPoint ?: return emptyMap()
        val analyser = BranchAnalyser(project)
        val out = mutableMapOf<String, List<BranchAnalyser.DiffHunk>>()
        for (node in graph.nodes.filter { it.isDirectlyChanged }) {
            val path = node.filePath ?: continue
            val hunks = analyser.readStructuredDiff(repo, branchPoint, path)
            if (hunks.isNotEmpty()) out[node.id] = hunks
        }
        return out
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Artifexa")
            .createNotification("Artifexa", message, type)
            .notify(project)
    }
}
