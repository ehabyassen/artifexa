package com.artifexa.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiReviewEngineParseTest {

    private val engine = AiReviewEngine()

    @Test
    fun `parses well-formed review JSON`() {
        val raw = """
        {
          "overallSummary": "Nice refactor.",
          "architectureAssessment": "Layers respected.",
          "bestPracticesFindings": [
            {"severity":"WARNING","title":"Long method","description":"Consider splitting","affectedComponent":"com.acme.Foo"}
          ],
          "taskCompletionScore": 80,
          "completedAspects": ["added service"],
          "missingAspects": ["tests"]
        }
        """.trimIndent()
        val r = engine.parse(raw)
        assertEquals("Nice refactor.", r.overallSummary)
        assertEquals(1, r.bestPracticesFindings.size)
        assertEquals(Severity.WARNING, r.bestPracticesFindings[0].severity)
        assertEquals(80, r.taskCompletionScore)
        assertEquals(listOf("tests"), r.missingAspects)
    }

    @Test
    fun `strips markdown code fences before parsing`() {
        val raw = """
        ```json
        {"overallSummary":"ok","architectureAssessment":"","bestPracticesFindings":[],"taskCompletionScore":null,"completedAspects":[],"missingAspects":[]}
        ```
        """.trimIndent()
        val r = engine.parse(raw)
        assertEquals("ok", r.overallSummary)
        assertNull(r.taskCompletionScore)
    }

    @Test
    fun `tolerates surrounding prose`() {
        val raw = "Here you go:\n{\"overallSummary\":\"fine\",\"architectureAssessment\":\"\",\"bestPracticesFindings\":[],\"completedAspects\":[],\"missingAspects\":[]}\nThanks."
        val r = engine.parse(raw)
        assertEquals("fine", r.overallSummary)
        assertTrue(r.bestPracticesFindings.isEmpty())
    }
}
