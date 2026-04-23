package com.artifexa.graph

import com.artifexa.vcs.BranchDiff
import com.artifexa.vcs.ChangeStatus
import com.artifexa.vcs.FileDiff
import com.artifexa.vcs.MethodDiff
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import java.io.File

/**
 * Builds a [ComponentGraph] from a [BranchDiff] by resolving changed files to PSI classes,
 * searching for callers/callees, and emitting typed edges.
 */
data class BuildDiagnostics(
    val totalFileDiffs: Int = 0,
    val jvmFileDiffs: Int = 0,
    val vFileMisses: Int = 0,
    val psiMisses: Int = 0,
    val nonClassOwnerFiles: Int = 0,
    val plainTextFiles: Int = 0,
    val resolvedClasses: Int = 0,
    val samplePaths: List<String> = emptyList(),
)

class ComponentGraphBuilder(
    private val project: Project,
    private val classifier: LayerClassifier = LayerClassifier(),
    private val maxNodes: Int = 150,
) {

    var lastDiagnostics: BuildDiagnostics = BuildDiagnostics()
        private set

    fun build(branchDiff: BranchDiff): ComponentGraph = ReadAction.compute<ComponentGraph, RuntimeException> {
        val repoRootPath = project.basePath.orEmpty()
        val psiManager = PsiManager.getInstance(project)
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)

        val nodeMap = mutableMapOf<String, NodeBuilder>()
        val edges = mutableListOf<ComponentEdge>()

        var jvmCount = 0
        var vFileMisses = 0
        var psiMisses = 0
        var nonClassOwner = 0
        var plainText = 0
        var resolved = 0

        // Step 1: build directly-changed nodes from changed JVM files (one per containing class).
        val changedClasses = mutableListOf<PsiClass>()
        for (fd in branchDiff.fileDiffs) {
            if (!isJvmSource(fd.path)) continue
            jvmCount++
            if (fd.status == ChangeStatus.DELETED) {
                val fqn = guessFqnFromPath(fd.path) ?: fd.path
                nodeMap[fqn] = NodeBuilder(
                    id = fqn, simpleName = fqn.substringAfterLast('.'), filePath = fd.path,
                    layer = LayerType.UNKNOWN, isDirectlyChanged = true, isTestComponent = false,
                    linesAdded = fd.linesAdded, linesRemoved = fd.linesRemoved,
                    hasTests = false, status = NodeStatus.DELETED,
                )
                continue
            }
            val vFile = LocalFileSystem.getInstance().findFileByIoFile(File(repoRootPath, fd.path))
            if (vFile == null) { vFileMisses++; continue }
            val psiFile = psiManager.findFile(vFile)
            if (psiFile == null) { psiMisses++; continue }
            if (psiFile is com.intellij.psi.PsiPlainTextFile) { plainText++; continue }
            if (psiFile !is PsiClassOwner) { nonClassOwner++; continue }
            for (cls in psiFile.classes) {
                val fqn = cls.qualifiedName ?: continue
                resolved++
                val methodLines = branchDiff.methodDiffs.filter { it.fqClassName == fqn }
                val linesAdded = if (methodLines.isNotEmpty()) methodLines.sumOf { it.linesAdded } else fd.linesAdded
                val linesRemoved = if (methodLines.isNotEmpty()) methodLines.sumOf { it.linesRemoved } else fd.linesRemoved
                val isTest = isTestFile(fd.path) || isTestClass(cls)
                nodeMap[fqn] = NodeBuilder(
                    id = fqn, simpleName = cls.name ?: fqn.substringAfterLast('.'),
                    filePath = fd.path,
                    layer = if (isTest) LayerType.TEST else classifier.classify(cls, fqn),
                    isDirectlyChanged = true,
                    isTestComponent = isTest,
                    linesAdded = linesAdded,
                    linesRemoved = linesRemoved,
                    hasTests = false,
                    status = if (isTest) NodeStatus.TEST else NodeStatus.CHANGED,
                )
                changedClasses += cls
            }
        }

        annotateHasTests(nodeMap, branchDiff.fileDiffs)

        // Step 2: first-order neighbours — callers and inheritors/injectors of each changed class.
        for (cls in changedClasses) {
            val sourceFqn = cls.qualifiedName ?: continue
            addCallerNeighbours(cls, sourceFqn, nodeMap, edges)
            addInheritanceEdges(cls, sourceFqn, facade, scope, nodeMap, edges)
            addInjectionAndImportEdges(cls, sourceFqn, facade, nodeMap, edges)
        }

        // Step 3: finalise nodes with change-intensity normalisation and layer-violation flags.
        val finalNodes = finaliseNodes(nodeMap.values)
        val finalEdges = finaliseEdges(edges, finalNodes)

        val (trimmedNodes, trimmedEdges) = trim(finalNodes, finalEdges, maxNodes)

        lastDiagnostics = BuildDiagnostics(
            totalFileDiffs = branchDiff.fileDiffs.size,
            jvmFileDiffs = jvmCount,
            vFileMisses = vFileMisses,
            psiMisses = psiMisses,
            nonClassOwnerFiles = nonClassOwner,
            plainTextFiles = plainText,
            resolvedClasses = resolved,
            samplePaths = branchDiff.fileDiffs.take(10).map { it.path },
        )
        log.info("Artifexa graph build diagnostics: $lastDiagnostics")

        ComponentGraph(trimmedNodes, trimmedEdges)
    }

    // --------------------------------------------------------------------

    private fun addCallerNeighbours(
        cls: PsiClass,
        sourceFqn: String,
        nodeMap: MutableMap<String, NodeBuilder>,
        edges: MutableList<ComponentEdge>,
    ) {
        try {
            for (method in cls.methods) {
                ReferencesSearch.search(method).forEach { ref ->
                    val callerClass = findEnclosingClass(ref.element) ?: return@forEach
                    val callerFqn = callerClass.qualifiedName ?: return@forEach
                    if (callerFqn == sourceFqn) return@forEach
                    ensureNeighbourNode(callerClass, callerFqn, nodeMap)
                    edges += ComponentEdge(callerFqn, sourceFqn, EdgeType.CALLS)
                }
            }
        } catch (e: Exception) {
            log.debug("CALLS lookup failed for $sourceFqn: ${e.message}")
        }
    }

    private fun addInheritanceEdges(
        cls: PsiClass,
        sourceFqn: String,
        facade: JavaPsiFacade,
        scope: GlobalSearchScope,
        nodeMap: MutableMap<String, NodeBuilder>,
        edges: MutableList<ComponentEdge>,
    ) {
        // parents
        for (parent in cls.supers) {
            val parentFqn = parent.qualifiedName ?: continue
            if (parentFqn.startsWith("java.") || parentFqn == "kotlin.Any") continue
            if (facade.findClass(parentFqn, scope) != null) {
                ensureNeighbourNode(parent, parentFqn, nodeMap)
                val type = if (parent.isInterface) EdgeType.IMPLEMENTS else EdgeType.INHERITS
                edges += ComponentEdge(sourceFqn, parentFqn, type)
            }
        }
        // inheritors (children) — first-order only
        try {
            ClassInheritorsSearch.search(cls, scope, false).forEach { child ->
                val childFqn = child.qualifiedName ?: return@forEach
                ensureNeighbourNode(child, childFqn, nodeMap)
                val type = if (cls.isInterface) EdgeType.IMPLEMENTS else EdgeType.INHERITS
                edges += ComponentEdge(childFqn, sourceFqn, type)
            }
        } catch (e: Exception) {
            log.debug("Inheritors search failed for $sourceFqn: ${e.message}")
        }
    }

    private fun addInjectionAndImportEdges(
        cls: PsiClass,
        sourceFqn: String,
        facade: JavaPsiFacade,
        nodeMap: MutableMap<String, NodeBuilder>,
        edges: MutableList<ComponentEdge>,
    ) {
        for (field in cls.fields) {
            val injected = field.annotations.any { it.isInjectAnnotation() }
            val typeClassFqn = (field.type as? com.intellij.psi.PsiClassType)?.resolve()?.qualifiedName ?: continue
            if (typeClassFqn.startsWith("java.") || typeClassFqn.startsWith("kotlin.")) continue
            if (injected) {
                val scope = GlobalSearchScope.projectScope(project)
                val target = facade.findClass(typeClassFqn, scope) ?: continue
                ensureNeighbourNode(target, typeClassFqn, nodeMap)
                edges += ComponentEdge(sourceFqn, typeClassFqn, EdgeType.INJECTS)
            }
        }
        // imports are implicit in parent/injected edges; we do not enumerate raw imports here to
        // avoid exploding the graph with trivial Java-std dependencies.
    }

    private fun ensureNeighbourNode(
        cls: PsiClass,
        fqn: String,
        nodeMap: MutableMap<String, NodeBuilder>,
    ) {
        if (nodeMap.containsKey(fqn)) return
        nodeMap[fqn] = NodeBuilder(
            id = fqn,
            simpleName = cls.name ?: fqn.substringAfterLast('.'),
            filePath = cls.containingFile?.virtualFile?.path,
            layer = classifier.classify(cls, fqn),
            isDirectlyChanged = false,
            isTestComponent = isTestClass(cls),
            linesAdded = 0,
            linesRemoved = 0,
            hasTests = false,
            status = NodeStatus.NEIGHBOUR,
        )
    }

    private fun finaliseNodes(builders: Collection<NodeBuilder>): List<ComponentNode> {
        val maxChanged = builders.maxOfOrNull { it.linesAdded + it.linesRemoved }?.coerceAtLeast(1) ?: 1
        return builders.map { b ->
            val intensity = (b.linesAdded + b.linesRemoved).toDouble() / maxChanged
            ComponentNode(
                id = b.id,
                simpleName = b.simpleName,
                filePath = b.filePath,
                layer = b.layer,
                isDirectlyChanged = b.isDirectlyChanged,
                isTestComponent = b.isTestComponent,
                linesAdded = b.linesAdded,
                linesRemoved = b.linesRemoved,
                changeIntensity = if (b.isDirectlyChanged) intensity.coerceIn(0.1, 1.0) else 0.2,
                hasTests = b.hasTests,
                status = b.status,
            )
        }
    }

    private fun finaliseEdges(edges: List<ComponentEdge>, nodes: List<ComponentNode>): List<ComponentEdge> {
        val layerOf = nodes.associate { it.id to it.layer }
        val deduped = edges.filter { e -> e.fromId != e.toId && layerOf.containsKey(e.fromId) && layerOf.containsKey(e.toId) }
            .distinctBy { "${it.fromId}|${it.toId}|${it.type}" }
        return deduped.map { e ->
            val fromLayer = layerOf[e.fromId] ?: LayerType.UNKNOWN
            val toLayer = layerOf[e.toId] ?: LayerType.UNKNOWN
            e.copy(isLayerViolation = classifier.isViolation(fromLayer, toLayer))
        }
    }

    private fun trim(
        nodes: List<ComponentNode>,
        edges: List<ComponentEdge>,
        maxNodes: Int,
    ): Pair<List<ComponentNode>, List<ComponentEdge>> {
        if (nodes.size <= maxNodes) return nodes to edges
        // Keep all directly-changed nodes, then top neighbours by connectivity.
        val (changed, rest) = nodes.partition { it.isDirectlyChanged }
        val degree = mutableMapOf<String, Int>()
        for (e in edges) {
            degree.merge(e.fromId, 1, Int::plus)
            degree.merge(e.toId, 1, Int::plus)
        }
        val remainingSlots = (maxNodes - changed.size).coerceAtLeast(0)
        val keptNeighbours = rest.sortedByDescending { degree[it.id] ?: 0 }.take(remainingSlots)
        val kept = (changed + keptNeighbours)
        val keptIds = kept.map { it.id }.toSet()
        val keptEdges = edges.filter { it.fromId in keptIds && it.toId in keptIds }
        return kept to keptEdges
    }

    private fun annotateHasTests(nodeMap: MutableMap<String, NodeBuilder>, fileDiffs: List<FileDiff>) {
        val testBaseNames = fileDiffs
            .filter { isTestFile(it.path) }
            .map { it.path.substringAfterLast('/').substringBeforeLast('.').removeSuffix("Tests").removeSuffix("Test") }
            .toSet()
        nodeMap.values.forEach { builder ->
            if (testBaseNames.contains(builder.simpleName)) {
                nodeMap[builder.id] = builder.copy(hasTests = true)
            }
        }
    }

    private fun findEnclosingClass(element: com.intellij.psi.PsiElement): PsiClass? {
        var current: com.intellij.psi.PsiElement? = element
        while (current != null) {
            if (current is PsiClass) return current
            current = current.parent
        }
        return null
    }

    private fun PsiAnnotation.isInjectAnnotation(): Boolean {
        val qn = qualifiedName ?: return false
        return qn == "org.springframework.beans.factory.annotation.Autowired" ||
            qn == "javax.inject.Inject" ||
            qn == "jakarta.inject.Inject"
    }

    private fun isTestClass(cls: PsiClass): Boolean {
        val name = cls.name ?: return false
        if (name.endsWith("Test") || name.endsWith("Tests")) return true
        return cls.annotations.any {
            val qn = it.qualifiedName ?: return@any false
            qn.startsWith("org.junit.") || qn.startsWith("kotlin.test.")
        }
    }

    private fun isTestFile(path: String): Boolean =
        path.contains("/test/") || path.contains("/tests/") ||
            path.endsWith("Test.kt") || path.endsWith("Tests.kt") ||
            path.endsWith("Test.java") || path.endsWith("Tests.java")

    private fun isJvmSource(path: String): Boolean =
        path.endsWith(".kt") || path.endsWith(".java")

    private fun guessFqnFromPath(path: String): String? {
        val marker = when {
            "/src/main/java/" in path -> "/src/main/java/"
            "/src/main/kotlin/" in path -> "/src/main/kotlin/"
            "/src/test/java/" in path -> "/src/test/java/"
            "/src/test/kotlin/" in path -> "/src/test/kotlin/"
            else -> return null
        }
        val relative = path.substringAfter(marker)
        return relative.removeSuffix(".kt").removeSuffix(".java").replace('/', '.')
    }

    private data class NodeBuilder(
        val id: String,
        val simpleName: String,
        val filePath: String?,
        val layer: LayerType,
        val isDirectlyChanged: Boolean,
        val isTestComponent: Boolean,
        val linesAdded: Int,
        val linesRemoved: Int,
        val hasTests: Boolean,
        val status: NodeStatus,
    )

    companion object {
        private val log = logger<ComponentGraphBuilder>()
    }
}
