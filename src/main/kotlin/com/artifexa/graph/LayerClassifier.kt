package com.artifexa.graph

import com.intellij.psi.PsiClass

/**
 * Classifies a [PsiClass] into a [LayerType] using (1) Spring stereotype annotations
 * and (2) configurable package-name patterns.
 *
 * Annotation wins over package pattern.
 */
class LayerClassifier(
    private val patterns: Map<LayerType, List<String>> = DEFAULT_PATTERNS,
) {

    fun classify(psiClass: PsiClass?, fqName: String?): LayerType {
        if (psiClass != null) {
            val annotationLayer = psiClass.annotations
                .mapNotNull { it.qualifiedName }
                .firstNotNullOfOrNull { ANNOTATION_LAYER[it] }
            if (annotationLayer != null) return annotationLayer
        }
        val name = (fqName ?: psiClass?.qualifiedName ?: return LayerType.UNKNOWN).lowercase()
        if (name.endsWith("test") || name.contains(".test.") || name.endsWith("tests")) return LayerType.TEST

        for ((layer, fragments) in patterns) {
            if (fragments.any { name.contains(it.lowercase()) }) return layer
        }
        return LayerType.UNKNOWN
    }

    /**
     * Expected dependency flow: CONTROLLER → SERVICE → REPOSITORY → DOMAIN
     * (higher layer depends on lower layer). An edge is a violation when
     * a lower layer depends on a higher one (e.g. DOMAIN → CONTROLLER).
     *
     * Higher rank = upper layer, so violation when target rank > source rank.
     */
    fun isViolation(from: LayerType, to: LayerType): Boolean {
        val fi = LAYER_RANK[from] ?: return false
        val ti = LAYER_RANK[to] ?: return false
        return ti > fi
    }

    companion object {
        private val ANNOTATION_LAYER: Map<String, LayerType> = mapOf(
            "org.springframework.web.bind.annotation.RestController" to LayerType.CONTROLLER,
            "org.springframework.stereotype.Controller" to LayerType.CONTROLLER,
            "org.springframework.stereotype.Service" to LayerType.SERVICE,
            "org.springframework.stereotype.Repository" to LayerType.REPOSITORY,
            "org.springframework.stereotype.Component" to LayerType.SERVICE,
            "jakarta.inject.Named" to LayerType.SERVICE,
            "javax.inject.Named" to LayerType.SERVICE,
        )

        /** Higher rank = upper layer. Lower-layer → upper-layer edge is a violation. */
        private val LAYER_RANK: Map<LayerType, Int> = mapOf(
            LayerType.CONTROLLER to 4,
            LayerType.SERVICE to 3,
            LayerType.REPOSITORY to 2,
            LayerType.DOMAIN to 1,
            LayerType.UTIL to 1,
        )

        val DEFAULT_PATTERNS: Map<LayerType, List<String>> = mapOf(
            LayerType.CONTROLLER to listOf(".controller", ".web", ".rest", ".api"),
            LayerType.SERVICE to listOf(".service", ".usecase", ".application"),
            LayerType.REPOSITORY to listOf(".repository", ".dao", ".persistence"),
            LayerType.DOMAIN to listOf(".domain", ".model", ".entity", ".dto"),
            LayerType.UTIL to listOf(".util", ".common", ".shared"),
        )
    }
}
