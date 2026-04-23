package com.artifexa.vcs

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import java.io.File

private const val FS = "\u0001"   // field separator for git log format
private const val RS = "\u0002"   // record separator for per-commit blocks

/**
 * Walks commits from branch-point → HEAD and produces an aggregated [BranchDiff].
 *
 * Uses raw git commands (via IntelliJ's Git4Idea `GitLineHandler`) rather than
 * `GitHistoryUtils.history` to keep the dependency surface small and avoid API
 * drift between IntelliJ versions.
 */
class BranchAnalyser(private val project: Project) {

    fun analyse(repo: GitRepository, branchPointHash: String, targetBranch: String): BranchDiff {
        val currentBranch = repo.currentBranch?.name ?: "HEAD"
        val range = "$branchPointHash..HEAD"

        val commits = readCommits(repo, range)
        val fileDiffs = readAggregatedFileDiffs(repo, range)
        val methodDiffs = resolveMethodDiffs(repo, range, fileDiffs)

        return BranchDiff(
            branchName = currentBranch,
            targetBranch = targetBranch,
            branchPoint = branchPointHash,
            commits = commits,
            fileDiffs = fileDiffs,
            methodDiffs = methodDiffs,
        )
    }

    // ---- commits ----------------------------------------------------------

    private fun readCommits(repo: GitRepository, range: String): List<CommitInfo> {
        val handler = GitLineHandler(project, repo.root, GitCommand.LOG)
        handler.addParameters("--pretty=format:%H${FS}%h${FS}%an${FS}%ct${FS}%s", range)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) {
            log.warn("git log failed: ${result.errorOutputAsJoinedString}")
            return emptyList()
        }
        return result.output.mapNotNull { line ->
            val parts = line.split(FS)
            if (parts.size < 5) null
            else CommitInfo(
                hash = parts[0],
                shortHash = parts[1],
                author = parts[2],
                timestamp = parts[3].toLongOrNull()?.times(1000) ?: 0L,
                message = parts[4],
            )
        }
    }

    // ---- file diffs -------------------------------------------------------

    private fun readAggregatedFileDiffs(repo: GitRepository, range: String): List<FileDiff> {
        val numstat = runGitLines(
            repo, GitCommand.DIFF, listOf("--numstat", "-M", range),
        )
        val nameStatus = runGitLines(
            repo, GitCommand.DIFF, listOf("--name-status", "-M", range),
        )
        val perCommit = readPerCommitFiles(repo, range)

        val statusByPath = nameStatus.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.isEmpty()) return@mapNotNull null
            val code = parts[0].firstOrNull() ?: return@mapNotNull null
            val path = parts.last()
            val status = when (code) {
                'A' -> ChangeStatus.ADDED
                'D' -> ChangeStatus.DELETED
                'R' -> ChangeStatus.RENAMED
                else -> ChangeStatus.MODIFIED
            }
            path to status
        }.toMap()

        log.info("Artifexa: git diff --numstat returned ${numstat.size} lines. Sample: ${numstat.take(3)}")
        return numstat.mapNotNull { raw ->
            val line = raw.trimEnd('\r', '\n', ' ')
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            val added = parts[0].toIntOrNull() ?: 0
            val removed = parts[1].toIntOrNull() ?: 0
            val rawPath = parts.drop(2).lastOrNull { it.isNotBlank() } ?: return@mapNotNull null
            val path = unwrapRenamePath(rawPath).trim()
            FileDiff(
                path = path,
                status = statusByPath[path] ?: ChangeStatus.MODIFIED,
                linesAdded = added,
                linesRemoved = removed,
                commits = perCommit[path].orEmpty(),
            )
        }
    }

    /** Resolve "src/{old => new}/File.java" → "src/new/File.java". */
    private fun unwrapRenamePath(raw: String): String {
        val braceIdx = raw.indexOf('{')
        if (braceIdx < 0) return raw
        val closeIdx = raw.indexOf('}', braceIdx)
        if (closeIdx < 0) return raw
        val inner = raw.substring(braceIdx + 1, closeIdx)
        val arrowIdx = inner.indexOf("=>")
        if (arrowIdx < 0) return raw
        val newName = inner.substring(arrowIdx + 2).trim()
        return raw.substring(0, braceIdx) + newName + raw.substring(closeIdx + 1)
    }

    private fun readPerCommitFiles(repo: GitRepository, range: String): Map<String, List<String>> {
        val handler = GitLineHandler(project, repo.root, GitCommand.LOG)
        handler.addParameters("--name-only", "--pretty=format:${RS}%H", range)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return emptyMap()

        val map = mutableMapOf<String, MutableList<String>>()
        var currentHash: String? = null
        for (raw in result.output) {
            if (raw.startsWith(RS)) {
                currentHash = raw.substring(1)
            } else if (raw.isNotBlank() && currentHash != null) {
                map.getOrPut(raw) { mutableListOf() }.add(currentHash)
            }
        }
        return map
    }

    // ---- method-level diffs ----------------------------------------------

    private fun resolveMethodDiffs(
        repo: GitRepository,
        range: String,
        fileDiffs: List<FileDiff>,
    ): List<MethodDiff> {
        // Pre-compute hunk ranges OUTSIDE the read action — these shell out to git and
        // must not hold the read lock.
        val hunkByPath = mutableMapOf<String, List<HunkRange>>()
        for (fd in fileDiffs) {
            if (fd.status == ChangeStatus.DELETED) continue
            if (!isJvmSource(fd.path)) continue
            val hunks = readHunkLineRanges(repo, range, fd.path)
            if (hunks.isNotEmpty()) hunkByPath[fd.path] = hunks
        }
        if (hunkByPath.isEmpty()) return emptyList()

        return ReadAction.compute<List<MethodDiff>, RuntimeException> {
            val result = mutableListOf<MethodDiff>()
            val psiManager = PsiManager.getInstance(project)
            for ((path, hunkLines) in hunkByPath) {
                val vFile = LocalFileSystem.getInstance()
                    .findFileByIoFile(File(repo.root.path, path)) ?: continue
                val psiFile = psiManager.findFile(vFile) ?: continue
                val methodsTouched = methodsInRanges(psiFile, hunkLines)
                for (touched in methodsTouched) {
                    result += MethodDiff(
                        filePath = path,
                        fqClassName = touched.method.containingClass?.qualifiedName,
                        methodName = touched.method.name,
                        methodSignature = touched.method.signatureText(),
                        linesAdded = touched.linesAdded,
                        linesRemoved = touched.linesRemoved,
                    )
                }
            }
            result
        }
    }

    private fun readHunkLineRanges(
        repo: GitRepository,
        range: String,
        path: String,
    ): List<HunkRange> {
        val handler = GitLineHandler(project, repo.root, GitCommand.DIFF)
        handler.addParameters("--unified=0", range, "--", path)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return emptyList()

        val ranges = mutableListOf<HunkRange>()
        val hunkRegex = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
        for (line in result.output) {
            val m = hunkRegex.find(line) ?: continue
            val removed = m.groupValues[2].toIntOrNull() ?: 1
            val newStart = m.groupValues[3].toInt()
            val added = m.groupValues[4].toIntOrNull() ?: 1
            ranges += HunkRange(newStart, newStart + (added - 1).coerceAtLeast(0), added, removed)
        }
        return ranges
    }

    private data class HunkRange(
        val startLine: Int,
        val endLine: Int,
        val linesAdded: Int,
        val linesRemoved: Int,
    )

    private data class MethodTouched(
        val method: PsiMethod,
        val linesAdded: Int,
        val linesRemoved: Int,
    )

    private fun methodsInRanges(file: PsiFile, ranges: List<HunkRange>): List<MethodTouched> {
        val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return emptyList()
        val allMethods = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java)
        if (allMethods.isEmpty()) return emptyList()

        val acc = mutableMapOf<PsiMethod, IntArray>()
        for (range in ranges) {
            for (method in allMethods) {
                val tr = method.textRange ?: continue
                val startLine = doc.getLineNumber(tr.startOffset) + 1
                val endLine = doc.getLineNumber(tr.endOffset.coerceAtMost(doc.textLength - 1)) + 1
                if (range.startLine <= endLine && range.endLine >= startLine) {
                    val v = acc.getOrPut(method) { intArrayOf(0, 0) }
                    v[0] += range.linesAdded
                    v[1] += range.linesRemoved
                }
            }
        }
        return acc.map { MethodTouched(it.key, it.value[0], it.value[1]) }
    }

    private fun PsiMethod.signatureText(): String {
        val params = parameterList.parameters.joinToString(", ") { it.type.presentableText }
        return "$name($params)"
    }

    private fun isJvmSource(path: String): Boolean =
        path.endsWith(".kt") || path.endsWith(".java")

    private fun runGitLines(
        repo: GitRepository,
        command: GitCommand,
        params: List<String>,
    ): List<String> {
        val handler = GitLineHandler(project, repo.root, command)
        handler.addParameters(*params.toTypedArray())
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) {
            log.debug("git ${command.name()} ${params.joinToString(" ")} failed: ${result.errorOutputAsJoinedString}")
            return emptyList()
        }
        return result.output
    }

    private val PsiMethod.containingClass: PsiClass?
        get() = PsiTreeUtil.getParentOfType(this, PsiClass::class.java)

    companion object {
        private val log = logger<BranchAnalyser>()
    }
}
