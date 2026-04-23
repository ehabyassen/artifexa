package com.artifexa.ui

import com.artifexa.ai.Finding
import com.artifexa.ai.ReviewResult
import com.artifexa.ai.Severity
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager
import javax.swing.text.html.HTMLEditorKit

/**
 * Renders the AI review as a single HTML document inside a JEditorPane — avoids the
 * well-known BoxLayout + JTextArea preferred-size race that caused text to collapse
 * on resize. Scrolling is handled by JBScrollPane.
 */
class ReviewPanel : JPanel(BorderLayout()) {

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

    init {
        add(statusLabel, BorderLayout.NORTH)
        add(JBScrollPane(editor), BorderLayout.CENTER)
        editor.text = baseHtml("<p><i>Run an AI review to see architecture, best-practices, and task-completion feedback here.</i></p>")
    }

    fun showLoading(message: String = "Running AI review…") {
        statusLabel.text = message
        editor.text = baseHtml("<p><i>$message</i></p>")
        editor.caretPosition = 0
    }

    fun renderResult(result: ReviewResult) {
        statusLabel.text = "Review complete"
        val body = buildString {
            if (result.overallSummary.isNotBlank()) {
                append(section("Overall Summary", escape(result.overallSummary)))
            }
            if (result.architectureAssessment.isNotBlank()) {
                append(section("Architecture Assessment", escape(result.architectureAssessment)))
            }
            if (result.bestPracticesFindings.isNotEmpty()) {
                val items = result.bestPracticesFindings.joinToString("") { findingHtml(it) }
                append(section("Best-Practices Findings", items))
            }
            if (result.taskCompletionScore != null) {
                append(section("Task Completion", taskCompletionHtml(result)))
            }
        }
        editor.text = baseHtml(body)
        editor.caretPosition = 0
    }

    fun renderError(message: String) {
        statusLabel.text = "Review failed"
        editor.text = baseHtml("<p style='color:#EF4444;'>${escape(message)}</p>")
        editor.caretPosition = 0
    }

    // ---------------------------------------------------------------

    private fun baseHtml(inner: String): String {
        val fg = UIManager.getColor("Label.foreground")?.rgbHex() ?: "#E6E6EC"
        // Swing's HTMLEditorKit only supports a tiny CSS subset — avoid shorthand
        // (border, margin) and unsupported properties (line-height, border-radius).
        return """
            <html>
            <head><style>
              body { font-family: sans-serif; color: $fg; font-size: 12px; }
              h3 { color: $fg; font-size: 13px; margin-top: 10px; margin-bottom: 4px; }
              p  { margin-top: 4px; margin-bottom: 4px; }
              ul { margin-top: 4px; margin-bottom: 4px; }
            </style></head>
            <body>
            $inner
            </body>
            </html>
        """.trimIndent()
    }

    private fun section(title: String, inner: String): String =
        "<h3>${escape(title)}</h3>$inner<hr>"

    private fun findingHtml(f: Finding): String {
        val accent = when (f.severity) {
            Severity.ERROR -> "#EF4444"
            Severity.WARNING -> "#F59E0B"
            Severity.INFO -> "#4F8EF7"
        }
        val cmp = f.affectedComponent?.let {
            "<div><font face='monospace' size='-1' color='#22D3A5'>${escape(it)}</font></div>"
        }.orEmpty()
        // bordered card via a 1-column table with coloured left cell
        return """
            <table cellpadding='6' cellspacing='0'>
              <tr>
                <td bgcolor='$accent' width='3'>&nbsp;</td>
                <td>
                  <b><font color='$accent'>${f.severity.name}</font></b> &mdash; <b>${escape(f.title)}</b>
                  <p>${escape(f.description)}</p>
                  $cmp
                </td>
              </tr>
            </table>
        """.trimIndent()
    }

    private fun taskCompletionHtml(r: ReviewResult): String {
        val score = r.taskCompletionScore ?: 0
        val pct = score.coerceIn(0, 100)
        val filled = (pct * 2).coerceAtMost(200)          // pixels
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
