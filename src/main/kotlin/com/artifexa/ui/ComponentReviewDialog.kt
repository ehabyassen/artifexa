package com.artifexa.ui

import com.artifexa.ai.ComponentReview
import com.artifexa.vcs.BranchAnalyser
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.text.html.HTMLEditorKit

/**
 * Popup that shows the diff hunks for a single changed component with per-line review
 * comments rendered immediately beneath the referenced lines.
 */
class ComponentReviewDialog(
    private val project: Project,
    private val review: ComponentReview,
    private val hunks: List<BranchAnalyser.DiffHunk>,
    /** Optional new-file line number to highlight with a brighter green background. */
    private val highlightLine: Int? = null,
) : DialogWrapper(project) {

    private val editor = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        editorKit = HTMLEditorKit()
    }

    init {
        title = "Artifexa — ${review.fqn}"
        init()
        editor.text = renderHtml()
        editor.caretPosition = 0
        setSize(900, 650)
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.preferredSize = Dimension(900, 650)
        val scroll = JBScrollPane(editor)
        scroll.border = JBUI.Borders.empty()
        root.add(scroll, BorderLayout.CENTER)
        if (review.filePath.isNotBlank()) {
            val footer = JPanel(BorderLayout())
            footer.border = JBUI.Borders.empty(4, 8)
            val openBtn = JButton("Open file in editor").apply {
                addActionListener { openFile() }
            }
            footer.add(openBtn, BorderLayout.EAST)
            root.add(footer, BorderLayout.SOUTH)
        }
        return root
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun openFile() {
        val path = review.filePath
        val projectBase = project.basePath ?: return
        val abs = if (path.startsWith("/")) path else "$projectBase/$path"
        val vFile = LocalFileSystem.getInstance().findFileByPath(abs) ?: return
        val firstCommentLine = review.lineComments.minByOrNull { it.line }?.line ?: 1
        OpenFileDescriptor(project, vFile, (firstCommentLine - 1).coerceAtLeast(0), 0).navigate(true)
    }

    // --------------------------------------------------------------------

    private fun renderHtml(): String {
        val commentsByLine: Map<Int, List<String>> = review.lineComments
            .groupBy { it.line }
            .mapValues { (_, v) -> v.map { it.comment } }

        val sb = StringBuilder()
        sb.append(baseHead())
        sb.append("<body>")
        sb.append("<h2>").append(esc(review.fqn)).append("</h2>")
        if (review.filePath.isNotBlank()) {
            sb.append("<p><font face='monospace' color='#22D3A5'>")
               .append(esc(review.filePath))
               .append("</font></p>")
        }
        if (review.summary.isNotBlank()) {
            sb.append("<p><b>Summary</b></p>")
            sb.append("<p>").append(esc(review.summary).replace("\n", "<br>")).append("</p>")
            sb.append("<hr>")
        }

        if (hunks.isEmpty()) {
            sb.append("<p><i>No diff hunks available for this component.</i></p>")
        } else {
            sb.append("<p><b>Changed code with review comments</b></p>")
            sb.append("<table cellpadding='0' cellspacing='0' border='0' width='100%'>")
            for (hunk in hunks) {
                // hunk separator row
                sb.append("<tr><td colspan='3' bgcolor='").append(COLOR_HUNK_HEADER).append("'>")
                   .append("<font face='monospace' size='-1' color='#A0A8B4'>")
                   .append("@@ lines ").append(hunk.newStartLine).append("..").append(hunk.newEndLine)
                   .append(" @@</font></td></tr>")
                for (line in hunk.lines) {
                    val lineNum = line.newLine?.toString() ?: ""
                    val isHighlight = highlightLine != null && line.newLine == highlightLine
                    val bg = when {
                        isHighlight -> COLOR_HIGHLIGHT
                        line.prefix == '+' -> COLOR_ADDED
                        line.prefix == '-' -> COLOR_REMOVED
                        else -> COLOR_CONTEXT
                    }
                    val numBg = when {
                        isHighlight -> COLOR_HIGHLIGHT_NUM
                        line.prefix == '+' -> COLOR_ADDED_NUM
                        line.prefix == '-' -> COLOR_REMOVED_NUM
                        else -> COLOR_CONTEXT_NUM
                    }
                    sb.append("<tr>")
                    sb.append("<td width='44' bgcolor='").append(numBg).append("' align='right'>")
                       .append("<font face='monospace' size='-1' color='#6C7280'>&nbsp;")
                       .append(lineNum.ifEmpty { "&nbsp;" }).append("&nbsp;</font></td>")
                    sb.append("<td width='18' bgcolor='").append(bg).append("'>")
                       .append("<font face='monospace' size='-1' color='#E6E6EC'>&nbsp;")
                       .append(line.prefix).append("&nbsp;</font></td>")
                    sb.append("<td bgcolor='").append(bg).append("'>")
                       .append("<font face='monospace' size='-1' color='#E6E6EC'>")
                       .append(escPre(line.content.ifEmpty { " " }))
                       .append("</font></td>")
                    sb.append("</tr>")

                    val lineNumInt = line.newLine
                    if (lineNumInt != null) {
                        val comments = commentsByLine[lineNumInt]
                        if (!comments.isNullOrEmpty()) {
                            for (c in comments) {
                                sb.append("<tr><td bgcolor='").append(COLOR_COMMENT_NUM).append("'>&nbsp;</td>")
                                sb.append("<td colspan='2' bgcolor='").append(COLOR_COMMENT).append("'>")
                                   .append("<font color='#FDE68A'>&raquo;&nbsp;</font>")
                                   .append("<font color='#F5F7FB'>").append(esc(c)).append("</font>")
                                   .append("</td></tr>")
                            }
                        }
                    }
                }
            }
            sb.append("</table>")

            // Comments whose line number doesn't fall in any hunk (fallback list)
            val shownLines = hunks.flatMap { it.lines.mapNotNull { l -> l.newLine } }.toSet()
            val orphans = review.lineComments.filter { it.line !in shownLines }
            if (orphans.isNotEmpty()) {
                sb.append("<br><p><b>Comments referencing lines outside the changed hunks</b></p><ul>")
                orphans.forEach {
                    sb.append("<li>line ").append(it.line).append(": ").append(esc(it.comment)).append("</li>")
                }
                sb.append("</ul>")
            }
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    private fun baseHead(): String = """
        <html>
        <head><style>
          body { font-family: sans-serif; color: #E6E6EC; font-size: 12px; }
          h2 { font-size: 14px; margin-top: 0; margin-bottom: 4px; }
          p  { margin-top: 4px; margin-bottom: 4px; }
        </style></head>
    """.trimIndent()

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Same as [esc] but also renders leading spaces as non-breaking spaces so code indents keep their shape. */
    private fun escPre(s: String): String {
        val escaped = esc(s)
        val leading = escaped.takeWhile { it == ' ' }
        if (leading.isEmpty()) return escaped
        val rest = escaped.substring(leading.length)
        return leading.replace(" ", "&nbsp;") + rest
    }

    companion object {
        private const val COLOR_CONTEXT       = "#1F2329"
        private const val COLOR_CONTEXT_NUM   = "#23272E"
        private const val COLOR_ADDED         = "#193D28"
        private const val COLOR_ADDED_NUM     = "#1A4D2E"
        private const val COLOR_REMOVED       = "#3B1D1D"
        private const val COLOR_REMOVED_NUM   = "#4A2121"
        private const val COLOR_HUNK_HEADER   = "#2A2D34"
        private const val COLOR_COMMENT       = "#3B3519"
        private const val COLOR_COMMENT_NUM   = "#4A4321"
        private const val COLOR_HIGHLIGHT     = "#2E7D4F"  // brighter green for targeted line
        private const val COLOR_HIGHLIGHT_NUM = "#22A06B"
    }
}
