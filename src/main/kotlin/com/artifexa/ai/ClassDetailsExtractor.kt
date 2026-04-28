package com.artifexa.ai

import com.artifexa.graph.ComponentGraph
import com.artifexa.graph.ComponentNode
import com.artifexa.vcs.BranchDiff
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiField
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierList

/**
 * Produces [ClassDetails] for each directly-changed class, pulling structural info
 * (methods, fields, imports, annotations) from PSI and optionally attaching diff-hunk
 * text per changed method when the user has opted in.
 *
 * Cheap, read-action-wrapped, and heavily capped so the prompt never explodes.
 */
class ClassDetailsExtractor(
    private val project: Project,
    private val maxClasses: Int = 20,
    private val maxMethodsPerClass: Int = 30,
    private val maxFieldsPerClass: Int = 30,
    private val maxImportsPerClass: Int = 40,
) {

    fun extract(graph: ComponentGraph, branchDiff: BranchDiff): List<ClassDetails> =
        extractInternal(graph.nodes.filter { it.isDirectlyChanged }, branchDiff)

    /**
     * Structural snapshot for any subset of [nodes] (no diff data attached).
     * Used by the class-diagram view to render UML compartments for both directly-
     * changed classes and their first-order neighbours.
     */
    fun extractFor(nodes: List<ComponentNode>): List<ClassDetails> =
        extractInternal(nodes, branchDiff = null)

    private fun extractInternal(
        nodes: List<ComponentNode>,
        branchDiff: BranchDiff?,
    ): List<ClassDetails> {
        val repoRoot = project.basePath.orEmpty()
        val changed = nodes.take(maxClasses)
        if (changed.isEmpty()) return emptyList()

        return ReadAction.compute<List<ClassDetails>, RuntimeException> {
            val psiManager = PsiManager.getInstance(project)
            val docs = PsiDocumentManager.getInstance(project)
            val results = mutableListOf<ClassDetails>()

            for (node in changed) {
                val filePath = node.filePath ?: continue
                val absolute = if (filePath.startsWith("/")) filePath else "$repoRoot/$filePath"
                val vFile = LocalFileSystem.getInstance().findFileByPath(absolute) ?: continue
                val psiFile = psiManager.findFile(vFile) as? PsiClassOwner ?: continue
                val cls = psiFile.classes.firstOrNull { it.qualifiedName == node.id } ?: continue

                val doc = docs.getDocument(psiFile)
                val kind = classKind(cls)

                val imports = if (psiFile is PsiJavaFile) {
                    psiFile.importList?.allImportStatements?.mapNotNull { importName(it) }
                        ?.take(maxImportsPerClass).orEmpty()
                } else emptyList()

                val fields = cls.fields.take(maxFieldsPerClass).map { field ->
                    FieldSummary(
                        name = field.name,
                        type = field.type.presentableText,
                        modifiers = modifiersOf(field.modifierList),
                        annotations = annotationsOf(field.modifierList),
                    )
                }

                val methods = cls.methods.take(maxMethodsPerClass).map { method ->
                    val range = method.textRange
                    val startLine = if (doc != null && range != null) doc.getLineNumber(range.startOffset) + 1 else 0
                    val endLine = if (doc != null && range != null)
                        doc.getLineNumber(range.endOffset.coerceAtMost(doc.textLength - 1)) + 1 else 0
                    val methodDiff = branchDiff?.methodDiffs?.firstOrNull {
                        it.fqClassName == node.id && it.methodSignature == signatureOf(method)
                    }
                    MethodSummary(
                        name = method.name,
                        signature = signatureOf(method),
                        returnType = method.returnType?.presentableText ?: "void",
                        modifiers = modifiersOf(method.modifierList),
                        annotations = annotationsOf(method.modifierList),
                        lineCount = (endLine - startLine + 1).coerceAtLeast(0),
                        changed = methodDiff != null,
                        linesAdded = methodDiff?.linesAdded ?: 0,
                        linesRemoved = methodDiff?.linesRemoved ?: 0,
                    )
                }

                results += ClassDetails(
                    fqn = node.id,
                    simpleName = node.simpleName,
                    kind = kind,
                    annotations = annotationsOf(cls.modifierList),
                    imports = imports,
                    fields = fields,
                    methods = methods,
                )
            }
            results
        }
    }

    // --------------------------------------------------------------------

    private fun classKind(cls: PsiClass): String = when {
        cls.isAnnotationType -> "annotation"
        cls.isEnum -> "enum"
        cls.isInterface -> "interface"
        else -> "class"
    }

    private fun signatureOf(m: PsiMethod): String {
        val params = m.parameterList.parameters.joinToString(", ") { it.type.presentableText }
        return "${m.name}($params)"
    }

    private fun modifiersOf(list: PsiModifierList?): List<String> {
        if (list == null) return emptyList()
        val all = listOf(
            PsiModifier.PUBLIC, PsiModifier.PROTECTED, PsiModifier.PRIVATE,
            PsiModifier.STATIC, PsiModifier.FINAL, PsiModifier.ABSTRACT,
            PsiModifier.SYNCHRONIZED, PsiModifier.VOLATILE, PsiModifier.TRANSIENT, PsiModifier.NATIVE,
        )
        return all.filter { list.hasModifierProperty(it) }
    }

    private fun annotationsOf(list: PsiModifierList?): List<String> =
        list?.annotations?.mapNotNull { (it as? PsiAnnotation)?.qualifiedName }.orEmpty()

    private fun importName(stmt: PsiImportStatementBase): String? =
        stmt.importReference?.qualifiedName
}
