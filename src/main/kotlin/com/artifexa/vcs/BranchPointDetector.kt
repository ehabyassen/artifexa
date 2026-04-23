package com.artifexa.vcs

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/**
 * Detects the merge-base between the currently checked-out branch and its target branch.
 *
 * Uses `git merge-base <target> HEAD` since IntelliJ's Git4Idea API does not expose merge-base
 * directly.
 */
class BranchPointDetector(private val project: Project) {

    data class BranchPoint(val repo: GitRepository, val commitHash: String, val targetBranch: String)

    fun currentRepository(): GitRepository? =
        GitRepositoryManager.getInstance(project).repositories.firstOrNull()

    fun detectBranchPoint(repo: GitRepository, targetBranch: String): BranchPoint? {
        val currentBranch = repo.currentBranch?.name ?: run {
            log.warn("Artifexa: repository is in detached HEAD — cannot detect branch point")
            return null
        }
        if (currentBranch == targetBranch) {
            log.info("Artifexa: current branch equals target branch ($targetBranch); no divergence")
            return null
        }

        val resolvedTarget = resolveTargetRef(repo, targetBranch) ?: return null

        val handler = GitLineHandler(project, repo.root, GitCommand.MERGE_BASE)
        handler.addParameters(resolvedTarget, "HEAD")
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) {
            log.warn("Artifexa: git merge-base failed: ${result.errorOutputAsJoinedString}")
            return null
        }
        val hash = result.output.firstOrNull()?.trim().orEmpty()
        if (hash.isEmpty()) return null
        return BranchPoint(repo, hash, resolvedTarget)
    }

    /** Resolves the target branch to a usable ref, preferring a local branch then origin/<name>. */
    private fun resolveTargetRef(repo: GitRepository, targetBranch: String): String? {
        val branches = repo.branches
        if (branches.findLocalBranch(targetBranch) != null) return targetBranch
        val remoteCandidate = "origin/$targetBranch"
        if (branches.findRemoteBranch(remoteCandidate) != null) return remoteCandidate
        log.warn("Artifexa: could not find branch '$targetBranch' (local or origin)")
        return null
    }

    companion object {
        private val log = logger<BranchPointDetector>()
    }
}
