package com.artifexa.ai

import com.artifexa.graph.ComponentEdge
import com.artifexa.graph.ComponentGraph
import com.artifexa.graph.ComponentNode
import com.artifexa.graph.EdgeType
import com.artifexa.graph.LayerType
import com.artifexa.graph.NodeStatus
import com.artifexa.vcs.BranchDiff
import com.artifexa.vcs.CommitInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptBuilderTest {

    private val sampleNode = ComponentNode(
        id = "com.acme.InvoiceService",
        simpleName = "InvoiceService",
        filePath = "src/main/kotlin/com/acme/InvoiceService.kt",
        layer = LayerType.SERVICE,
        isDirectlyChanged = true,
        isTestComponent = false,
        linesAdded = 12,
        linesRemoved = 3,
        changeIntensity = 1.0,
        hasTests = false,
        status = NodeStatus.CHANGED,
    )

    private val sampleGraph = ComponentGraph(
        nodes = listOf(sampleNode),
        edges = emptyList(),
    )

    private val sampleBranchDiff = BranchDiff(
        branchName = "feat/invoices",
        targetBranch = "main",
        branchPoint = "abc123",
        commits = listOf(CommitInfo("abc", "ab", "Alice", "add invoices", 0L)),
        fileDiffs = emptyList(),
        methodDiffs = emptyList(),
    )

    @Test
    fun `built prompt contains branch metadata`() {
        val p = PromptBuilder().build(sampleBranchDiff, sampleGraph, "Add invoice service")
        assertTrue(p.userPrompt.contains("feat/invoices"))
        assertTrue(p.userPrompt.contains("InvoiceService"))
        assertTrue(p.userPrompt.contains("Add invoice service"))
    }

    @Test
    fun `system prompt instructs structured JSON and forbids source code`() {
        val p = PromptBuilder().build(sampleBranchDiff, sampleGraph, null)
        assertTrue(p.systemPrompt.contains("JSON"))
        assertTrue(p.systemPrompt.contains("Do not reproduce source code"))
    }

    @Test
    fun `compression triggers when context exceeds limit`() {
        val bigGraph = ComponentGraph(
            nodes = (1..100).map { sampleNode.copy(id = "com.acme.Foo$it", simpleName = "Foo$it", linesAdded = it) },
            edges = emptyList(),
        )
        val p = PromptBuilder(maxContextChars = 500).build(sampleBranchDiff, bigGraph, null)
        // The summary placeholder is emitted when we compress.
        assertTrue(p.userPrompt.contains("additional") || p.userPrompt.length <= 2_000)
    }

    @Test
    fun `no task description yields null in serialised context`() {
        val p = PromptBuilder().build(sampleBranchDiff, sampleGraph, null)
        // Either field omitted or serialised as null — both acceptable.
        assertTrue(p.userPrompt.contains("\"taskDescription\":null") ||
            !p.userPrompt.contains("\"taskDescription\":\""))
    }

    @Test
    fun `violation edges appear in context`() {
        val graph = ComponentGraph(
            nodes = listOf(
                sampleNode.copy(id = "com.acme.Domain", layer = LayerType.DOMAIN),
                sampleNode.copy(id = "com.acme.Ctrl", layer = LayerType.CONTROLLER),
            ),
            edges = listOf(ComponentEdge("com.acme.Domain", "com.acme.Ctrl", EdgeType.CALLS, isLayerViolation = true)),
        )
        val p = PromptBuilder().build(sampleBranchDiff, graph, null)
        assertTrue(p.userPrompt.contains("com.acme.Domain"))
        assertTrue(p.userPrompt.contains("com.acme.Ctrl"))
    }
}
