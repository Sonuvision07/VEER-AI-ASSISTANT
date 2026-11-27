package com.veer.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerGeminiFunctionResponseTest {
    private fun modelContent(name: String = "android.launch_app") = JSONObject()
        .put("role", "model")
        .put(
            "parts",
            org.json.JSONArray().put(
                JSONObject()
                    .put("functionCall", JSONObject()
                        .put("name", name)
                        .put("args", JSONObject().put("app", "youtube")))
            )
        )

    @Test fun buildsModelFunctionCallAndMatchingFunctionResponseTurns() {
        val original = modelContent()
        val result = VeerGeminiFunctionResponse.buildContents(
            original,
            "android.launch_app",
            "Approved Android action launched successfully."
        )

        assertNotNull(result)
        assertEquals(2, result!!.length())
        assertEquals("model", result.getJSONObject(0).getString("role"))
        assertEquals(
            "android.launch_app",
            result.getJSONObject(0).getJSONArray("parts")
                .getJSONObject(0).getJSONObject("functionCall").getString("name")
        )
        val functionResponse = result.getJSONObject(1)
            .getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("android.launch_app", functionResponse.getString("name"))
        assertEquals(
            "Approved Android action launched successfully.",
            functionResponse.getJSONObject("response").getString("result")
        )
        assertEquals("model", original.getString("role"))
    }

    @Test fun rejectsFunctionNameThatDoesNotMatchOriginalCall() {
        assertNull(
            VeerGeminiFunctionResponse.buildContents(
                modelContent(),
                "android.delete_data",
                "done"
            )
        )
    }

    @Test fun rejectsMultipleFunctionCallsInModelContent() {
        val content = modelContent()
        content.getJSONArray("parts").put(
            JSONObject().put(
                "functionCall",
                JSONObject().put("name", "android.launch_app").put("args", JSONObject())
            )
        )
        assertNull(
            VeerGeminiFunctionResponse.buildContents(content, "android.launch_app", "done")
        )
    }

    @Test fun boundsLargeToolResultsBeforeReturningThemToGemini() {
        val result = VeerGeminiFunctionResponse.buildContents(
            modelContent(),
            "android.launch_app",
            "x".repeat(VeerGeminiFunctionResponse.MAX_RESULT_CHARS + 100)
        )!!
        val returned = result.getJSONObject(1)
            .getJSONArray("parts").getJSONObject(0)
            .getJSONObject("functionResponse").getJSONObject("response").getString("result")

        assertTrue(returned.length() < VeerGeminiFunctionResponse.MAX_RESULT_CHARS + 100)
        assertTrue(returned.endsWith("[Tool result truncated by VEER]"))
    }

    @Test fun rejectsBlankOrControlCharacterFunctionNames() {
        assertNull(VeerGeminiFunctionResponse.buildContents(modelContent(), " ", "done"))
        assertNull(VeerGeminiFunctionResponse.buildContents(modelContent(), "bad\nname", "done"))
    }
}
