package com.artifexa.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Per-branch task description input. Persisted to `.idea/artifexa/task-<branch>.txt`
 * (user should add `.idea/artifexa/` to .gitignore).
 */
class TaskDescriptionPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val textArea = JBTextArea()

    init {
        textArea.rows = 4
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        add(JLabel("Task description (describe what this branch should accomplish):"), BorderLayout.NORTH)
        add(JBScrollPane(textArea), BorderLayout.CENTER)
    }

    fun text(): String = textArea.text.orEmpty()

    fun setText(s: String) { textArea.text = s }

    fun loadForBranch(branch: String) {
        val f = fileFor(branch)
        textArea.text = if (f.isFile) f.readText() else ""
    }

    fun saveForBranch(branch: String) {
        val f = fileFor(branch)
        f.parentFile?.let { FileUtil.createDirectory(it) }
        f.writeText(textArea.text.orEmpty())
    }

    private fun fileFor(branch: String): File {
        val safe = branch.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val base = project.basePath ?: System.getProperty("user.home")
        return File("$base/.idea/artifexa/task-$safe.txt")
    }
}
