package com.artifexa.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LayerClassifierTest {

    private val classifier = LayerClassifier()

    @Test
    fun `classifies controller by package fragment`() {
        assertEquals(LayerType.CONTROLLER, classifier.classify(null, "com.acme.billing.controller.InvoiceController"))
        assertEquals(LayerType.CONTROLLER, classifier.classify(null, "com.acme.billing.web.InvoiceEndpoint"))
    }

    @Test
    fun `classifies service by package fragment`() {
        assertEquals(LayerType.SERVICE, classifier.classify(null, "com.acme.billing.service.InvoiceService"))
    }

    @Test
    fun `classifies repository and domain`() {
        assertEquals(LayerType.REPOSITORY, classifier.classify(null, "com.acme.billing.repository.InvoiceRepo"))
        assertEquals(LayerType.DOMAIN, classifier.classify(null, "com.acme.billing.domain.Invoice"))
    }

    @Test
    fun `returns unknown when no pattern matches`() {
        assertEquals(LayerType.UNKNOWN, classifier.classify(null, "com.acme.billing.Foo"))
    }

    @Test
    fun `controller to service is NOT a violation`() {
        assertFalse(classifier.isViolation(LayerType.CONTROLLER, LayerType.SERVICE))
        assertFalse(classifier.isViolation(LayerType.SERVICE, LayerType.REPOSITORY))
        assertFalse(classifier.isViolation(LayerType.REPOSITORY, LayerType.DOMAIN))
    }

    @Test
    fun `lower layer depending on higher layer is a violation`() {
        assertTrue(classifier.isViolation(LayerType.DOMAIN, LayerType.CONTROLLER))
        assertTrue(classifier.isViolation(LayerType.REPOSITORY, LayerType.SERVICE))
        assertTrue(classifier.isViolation(LayerType.SERVICE, LayerType.CONTROLLER))
    }
}
