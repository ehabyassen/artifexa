package com.artifexa.ui

import com.artifexa.ai.ComponentReview
import com.artifexa.ai.Finding
import com.artifexa.ai.LineComment
import com.artifexa.ai.ReviewHistoryStore
import com.artifexa.ai.ReviewResult
import com.artifexa.ai.Severity
import com.artifexa.vcs.BranchAnalyser
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.datatransfer.StringSelection
import java.net.URLDecoder
import java.net.URLEncoder
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit

/**
 * Renders the AI review as a single HTML document inside a JEditorPane. Component
 * reviews appear as hyperlinks that open a dedicated popup with per-line comments
 * overlaid onto the diff hunks.
 */
class ReviewPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val editor = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        isOpaque = true
        background = UIManager.getColor("Panel.background") ?: Color(0x2B, 0x2D, 0x30)
        border = JBUI.Borders.empty(8)
        editorKit = HTMLEditorKit()
        putClientProperty("JEditorPane.honorDisplayProperties", true)
    }

    private val statusLabel = JLabel("No review yet. Click \"Run AI Review\".").apply {
        border = JBUI.Borders.empty(4, 8)
    }

    private var currentResult: ReviewResult? = null
    private var currentHunksByFqn: Map<String, List<BranchAnalyser.DiffHunk>> = emptyMap()
    private var currentBranch: String? = null

    init {
        add(statusLabel, BorderLayout.NORTH)
        add(JBScrollPane(editor), BorderLayout.CENTER)
        editor.text = baseHtml("<p><i>Run an AI review to see architecture, best-practices, " +
            "task-completion, and per-component line-by-line feedback here.</i></p>")
        editor.addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                handleLink(e.description.orEmpty())
            }
        }
    }

    fun showLoading(message: String = "Running AI review…") {
        statusLabel.text = message
        editor.text = baseHtml("<p><i>${escape(message)}</i></p>")
        editor.caretPosition = 0
    }

    fun renderResult(
        result: ReviewResult,
        hunksByFqn: Map<String, List<BranchAnalyser.DiffHunk>> = emptyMap(),
        branch: String? = null,
    ) {
        currentResult = result
        currentHunksByFqn = hunksByFqn
        if (branch != null) currentBranch = branch
        statusLabel.text = "Review complete"
        val body = buildString {
            if (result.overallSummary.isNotBlank()) {
                append(section("Overall Summary", escape(result.overallSummary)))
            }
            if (result.architectureAssessment.isNotBlank()) {
                append(section("Architecture Assessment", escape(result.architectureAssessment)))
            }
            if (result.bestPracticesFindings.isNotEmpty()) {
                val items = result.bestPracticesFindings
                    .mapIndexed { i, f -> findingHtml(i, f) }
                    .joinToString("")
                append(section("Best-Practices Findings", items))
            }
            if (result.taskCompletionScore != null) {
                append(section("Task Completion", taskCompletionHtml(result)))
            }
            val actionableReviews = result.componentReviews.filter { it.lineComments.isNotEmpty() }
            if (actionableReviews.isNotEmpty()) {
                append(section("Component Reviews",
                    "<p><i>Components with reviewer comments. " +
                        "Click a component to see its diff with line-by-line comments.</i></p>" +
                        componentReviewsHtml(actionableReviews)))
            }
        }
        editor.text = baseHtml(body)
        editor.caretPosition = 0
    }

    fun renderError(message: String) {
        currentResult = null
        statusLabel.text = "Review failed"
        editor.text = baseHtml("<p><font color='#EF4444'>${escape(message)}</font></p>")
        editor.caretPosition = 0
    }

    // ---------------------------------------------------------------

    private fun handleLink(desc: String) {
        if (!desc.startsWith("artifexa://")) return
        val rest = desc.removePrefix("artifexa://")
        when {
            rest == "copy/reviews" -> copyReviewsToClipboard()
            rest.startsWith("discard/component/") -> {
                // discard/component/<encodedFqn>/<line>
                val parts = rest.removePrefix("discard/component/").split("/")
                if (parts.size < 2) return
                val fqn = URLDecoder.decode(parts[0], Charsets.UTF_8)
                val line = parts[1].toIntOrNull() ?: return
                discardLineComment(fqn, line)
            }
            rest.startsWith("discard/finding/") -> {
                val idx = rest.removePrefix("discard/finding/").toIntOrNull() ?: return
                discardFinding(idx)
            }
            rest.startsWith("component/") -> {
                val fqn = URLDecoder.decode(rest.removePrefix("component/"), Charsets.UTF_8)
                val review = currentResult?.componentReviews?.firstOrNull { it.fqn == fqn } ?: return
                val hunks = currentHunksByFqn[fqn].orEmpty()
                ComponentReviewDialog(project, review, hunks).show()
            }
            rest.startsWith("finding/") -> {
                val idx = rest.removePrefix("finding/").toIntOrNull() ?: return
                val finding = currentResult?.bestPracticesFindings?.getOrNull(idx) ?: return
                val fqn = finding.affectedComponent ?: return
                val hunks = currentHunksByFqn[fqn].orEmpty()
                val existingReview = currentResult?.componentReviews?.firstOrNull { it.fqn == fqn }
                val synthetic = ComponentReview(
                    fqn = fqn,
                    filePath = existingReview?.filePath.orEmpty(),
                    summary = "${finding.severity} — ${finding.title}\n\n${finding.description}",
                    lineComments = finding.line?.let { listOf(LineComment(it, finding.description)) }.orEmpty(),
                )
                ComponentReviewDialog(project, synthetic, hunks, highlightLine = finding.line).show()
            }
        }
    }

    private fun discardLineComment(fqn: String, line: Int) {
        val result = currentResult ?: return
        val comment = result.componentReviews.firstOrNull { it.fqn == fqn }
            ?.lineComments?.firstOrNull { it.line == line } ?: return
        persistDiscard(fqn, comment.comment)
        val updatedReviews = result.componentReviews.map { cr ->
            if (cr.fqn != fqn) cr
            else cr.copy(lineComments = cr.lineComments.filterNot { it.line == line && it.comment == comment.comment })
        }
        currentResult = result.copy(componentReviews = updatedReviews)
        renderResult(currentResult!!, currentHunksByFqn, currentBranch)
        statusLabel.text = "Discarded comment on $fqn:$line (won't return on this branch)"
    }

    private fun discardFinding(index: Int) {
        val result = currentResult ?: return
        val finding = result.bestPracticesFindings.getOrNull(index) ?: return
        val fqn = finding.affectedComponent ?: "generic"
        persistDiscard(fqn, "${finding.title} ${finding.description}")
        val updatedFindings = result.bestPracticesFindings.toMutableList().also { it.removeAt(index) }
        currentResult = result.copy(bestPracticesFindings = updatedFindings)
        renderResult(currentResult!!, currentHunksByFqn, currentBranch)
        statusLabel.text = "Discarded finding '${finding.title}' (won't return on this branch)"
    }

    private fun persistDiscard(fqn: String, text: String) {
        val branch = currentBranch ?: return
        val store = ReviewHistoryStore(project)
        val history = store.load(branch)
        val signature = ReviewHistoryStore.signatureOf(fqn, text)
        val updated = history.copy(
            discardedSignatures = history.discardedSignatures + signature,
            openComments = history.openComments.filterNot { it.signature == signature },
        )
        store.save(branch, updated)
    }

    private fun componentReviewsHtml(reviews: List<ComponentReview>): String = buildString {
        // Top-of-section copy control. HTMLEditorKit doesn't render <button>, so we use a
        // styled hyperlink that the HyperlinkListener picks up.
        append("<p><a href='artifexa://copy/reviews'>")
        append("<font face='sans-serif' color='#8FBAFF'>&#91;Copy all&#93;</font></a></p>")
        append("<table cellpadding='4' cellspacing='0' border='0'>")
        for (cr in reviews) {
            val encoded = URLEncoder.encode(cr.fqn, Charsets.UTF_8)
            val href = "artifexa://component/$encoded"
            append("<tr><td colspan='2'>")
            append("<a href='").append(href).append("'><b>").append(escape(cr.fqn)).append("</b></a>")
            append(" <font color='#9CA3AF'>&nbsp;—&nbsp;").append(cr.lineComments.size).append(" line comment(s)</font>")
            append("</td></tr>")
            if (cr.summary.isNotBlank()) {
                append("<tr><td colspan='2'>").append(escape(cr.summary)).append("</td></tr>")
            }
            // Inline comment list: "line: comment   [discard]"
            append("<tr><td colspan='2'><ul>")
            for (lc in cr.lineComments.sortedBy { it.line }) {
                val fqnEnc = URLEncoder.encode(cr.fqn, Charsets.UTF_8)
                append("<li><font face='monospace' color='#9CA3AF'>")
                append(lc.line).append(":</font>&nbsp;")
                append(escape(lc.comment))
                append("&nbsp;&nbsp;<a href='artifexa://discard/component/").append(fqnEnc).append("/").append(lc.line).append("'>")
                append("<font size='-1' color='#EF4444'>[discard]</font></a>")
                append("</li>")
            }
            append("</ul></td></tr>")
        }
        append("</table>")
    }

    private fun copyReviewsToClipboard() {
        val result = currentResult ?: return
        val actionable = result.componentReviews.filter { it.lineComments.isNotEmpty() }
        if (actionable.isEmpty()) return
        val text = buildReviewsPlainText(actionable)
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        statusLabel.text = "Component reviews copied to clipboard"
    }

    private fun buildReviewsPlainText(reviews: List<ComponentReview>): String = buildString {
        appendLine("Component Reviews")
        appendLine("=================")
        appendLine()
        for (cr in reviews) {
            append(cr.fqn)
            if (cr.filePath.isNotBlank()) append(" (").append(cr.filePath).append(")")
            appendLine()
            if (cr.summary.isNotBlank()) {
                appendLine("Summary: ${cr.summary}")
            }
            for (lc in cr.lineComments.sortedBy { it.line }) {
                appendLine("  ${lc.line}: ${lc.comment}")
            }
            appendLine()
        }
    }

    private fun baseHtml(inner: String): String {
        val fg = UIManager.getColor("Label.foreground")?.rgbHex() ?: "#E6E6EC"
        return """
            <html>
            <head><style>
              body { font-family: sans-serif; color: $fg; font-size: 12px; }
              h3 { color: $fg; font-size: 13px; margin-top: 10px; margin-bottom: 4px; }
              p  { margin-top: 4px; margin-bottom: 4px; }
              ul { margin-top: 4px; margin-bottom: 4px; }
              a  { color: #8FBAFF; }
            </style></head>
            <body>
            $inner
            </body>
            </html>
        """.trimIndent()
    }

    private fun section(title: String, inner: String): String =
        "<h3>${escape(title)}</h3>$inner<hr>"

    private fun findingHtml(index: Int, f: Finding): String {
        val accent = when (f.severity) {
            Severity.ERROR -> "#EF4444"
            Severity.WARNING -> "#F59E0B"
            Severity.INFO -> "#4F8EF7"
        }
        val cmp = f.affectedComponent?.let { fqn ->
            val lineSuffix = f.line?.let { ":$it" }.orEmpty()
            val href = "artifexa://finding/$index"
            "<div><a href='${href}'><font face='monospace' size='-1' color='#22D3A5'>" +
                escape(fqn) + escape(lineSuffix) + "</font></a></div>"
        }.orEmpty()
        val discard = "<div><a href='artifexa://discard/finding/$index'>" +
            "<font size='-1' color='#EF4444'>[discard]</font></a></div>"
        return """
            <table cellpadding='6' cellspacing='0'>
              <tr>
                <td bgcolor='$accent' width='3'>&nbsp;</td>
                <td>
                  <b><font color='$accent'>${f.severity.name}</font></b> &mdash; <b>${escape(f.title)}</b>
                  <p>${escape(f.description)}</p>
                  $cmp
                  $discard
                </td>
              </tr>
            </table>
        """.trimIndent()
    }

    private fun taskCompletionHtml(r: ReviewResult): String {
        val score = r.taskCompletionScore ?: 0
        val pct = score.coerceIn(0, 100)
        val filled = (pct * 2).coerceAtMost(200)
        val empty  = 200 - filled
        val completed = if (r.completedAspects.isEmpty()) "" else
            "<p><b><font color='#22D3A5'>Completed</font></b></p><ul>" +
                r.completedAspects.joinToString("") { "<li>${escape(it)}</li>" } + "</ul>"
        val missing = if (r.missingAspects.isEmpty()) "" else
            "<p><b><font color='#F59E0B'>Missing</font></b></p><ul>" +
                r.missingAspects.joinToString("") { "<li>${escape(it)}</li>" } + "</ul>"
        val bar = """
            <table cellpadding='0' cellspacing='0' border='0'>
              <tr>
                <td bgcolor='#4F8EF7' width='$filled' height='12'>&nbsp;</td>
                <td bgcolor='#3C3F41' width='$empty' height='12'>&nbsp;</td>
              </tr>
            </table>
        """.trimIndent()
        return """
            <p><b>$pct% complete</b></p>
            $bar
            $completed
            $missing
        """.trimIndent()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\n", "<br/>")

    private fun Color.rgbHex(): String = "#%02X%02X%02X".format(red, green, blue)
}
