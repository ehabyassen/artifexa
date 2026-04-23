package com.artifexa.vcs

/** Metadata for a single commit on the feature branch. */
data class CommitInfo(
    val hash: String,
    val shortHash: String,
    val author: String,
    val message: String,
    val timestamp: Long,
)

/** Aggregated per-file diff across all commits on the branch. */
data class FileDiff(
    val path: String,
    val status: ChangeStatus,
    val linesAdded: Int,
    val linesRemoved: Int,
    val commits: List<String>,
)

/** Per-method change detected by intersecting diff hunks with PSI element ranges. */
data class MethodDiff(
    val filePath: String,
    val fqClassName: String?,
    val methodName: String,
    val methodSignature: String,
    val linesAdded: Int,
    val linesRemoved: Int,
)

enum class ChangeStatus { ADDED, MODIFIED, DELETED, RENAMED }

/** Full branch diff snapshot: commits + aggregated file diffs + per-method diffs. */
data class BranchDiff(
    val branchName: String,
    val targetBranch: String,
    val branchPoint: String?,
    val commits: List<CommitInfo>,
    val fileDiffs: List<FileDiff>,
    val methodDiffs: List<MethodDiff>,
) {
    val totalLinesAdded: Int get() = fileDiffs.sumOf { it.linesAdded }
    val totalLinesRemoved: Int get() = fileDiffs.sumOf { it.linesRemoved }
}
