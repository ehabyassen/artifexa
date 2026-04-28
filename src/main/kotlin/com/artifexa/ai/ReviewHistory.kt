package com.artifexa.ai

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-branch persisted review state. Used to suppress repeating comments that the
 * developer has already addressed (identified by the line content changing between reviews).
 *
 * We intentionally store only the bits needed for identity + addressed-detection —
 * no raw source code beyond the single line each comment was anchored to at the time.
 */
@Serializable
data class ReviewHistory(
    val branch: String = "",
    val openComments: List<HistoryComment> = emptyList(),
    /**
     * Signatures of comments the developer explicitly discarded. These are suppressed
     * permanently on this branch — the LLM will still produce them, but the plugin
     * filters them out before display.
     */
    val discardedSignatures: Set<String> = emptySet(),
)

@Serializable
data class HistoryComment(
    val fqn: String,
    val line: Int,
    /** Trimmed content of the new-file line at the time the comment was produced. */
    val lineContent: String,
    val text: String,
    /** Normalised signature used to match duplicate comments across runs. */
    val signature: String,
    val createdAt: Long,
)

class ReviewHistoryStore(private val project: Project) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(branch: String): ReviewHistory {
        val f = fileFor(branch)
        if (!f.isFile) return ReviewHistory(branch)
        return runCatching { json.decodeFromString<ReviewHistory>(f.readText()) }
            .onFailure { log.warn("Artifexa: failed to read review history from ${f.absolutePath}: ${it.message}") }
            .getOrDefault(ReviewHistory(branch))
    }

    fun save(branch: String, history: ReviewHistory) {
        val f = fileFor(branch)
        f.parentFile?.mkdirs()
        runCatching { f.writeText(json.encodeToString(history)) }
            .onFailure { log.warn("Artifexa: failed to write review history to ${f.absolutePath}: ${it.message}") }
    }

    private fun fileFor(branch: String): File {
        val safe = branch.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val base = project.basePath ?: System.getProperty("user.home")
        return File("$base/.idea/artifexa/review-history-$safe.json")
    }

    companion object {
        private val log = logger<ReviewHistoryStore>()

        /** Signature for matching a comment across runs. FQN-bound, lowercased, punctuation-stripped. */
        fun signatureOf(fqn: String, text: String): String {
            val normalised = text.lowercase()
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(120)
            return "$fqn|$normalised"
        }
    }
}
