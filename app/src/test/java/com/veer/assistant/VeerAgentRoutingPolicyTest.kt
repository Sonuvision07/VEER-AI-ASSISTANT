package com.veer.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerAgentRoutingPolicyTest {
    @Test fun routesTextOnlyGeminiRequestsThroughAgentBrain() {
        assertTrue(shouldUseAgentBrainForGeminiRequest(null, null))
        assertTrue(shouldUseAgentBrainForGeminiRequest(null, "   "))
    }

    @Test fun keepsImageRequestsOnMultimodalGeminiPath() {
        assertFalse(shouldUseAgentBrainForGeminiRequest("base64-image", null))
    }

    @Test fun keepsDocumentRequestsOnExistingDocumentPath() {
        assertFalse(shouldUseAgentBrainForGeminiRequest(null, "extracted document text"))
    }

    @Test fun keepsCombinedImageAndDocumentRequestsOnExistingPath() {
        assertFalse(shouldUseAgentBrainForGeminiRequest("base64-image", "document text"))
    }
}
