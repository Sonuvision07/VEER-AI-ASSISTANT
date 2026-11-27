package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts VEER's provider-neutral tool catalog to Gemini functionDeclarations.
 * The output describes capabilities only; every returned call must still pass
 * VeerAgentToolPolicy and the approval flow before execution.
 */
internal object VeerGeminiToolSchema {
    fun functionDeclarations(): JSONArray {
        val declarations = JSONArray()
        VeerAgentToolCatalog.definitions().forEach { tool ->
            val properties = JSONObject()
            val required = JSONArray()
            tool.parameters.forEach { parameter ->
                val property = JSONObject()
                    .put("type", parameter.type.uppercase())
                    .put("description", parameter.description)
                if (parameter.allowedValues.isNotEmpty()) {
                    property.put("enum", JSONArray(parameter.allowedValues))
                }
                properties.put(parameter.name, property)
                if (parameter.required) required.put(parameter.name)
            }
            val parameters = JSONObject()
                .put("type", "OBJECT")
                .put("properties", properties)
            if (required.length() > 0) parameters.put("required", required)

            declarations.put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("parameters", parameters)
            )
        }
        return declarations
    }
}
