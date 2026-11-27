package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Anthropic Messages API request builder for VEER's constrained structured tools. */
internal object VeerClaudeAgentRequest {
    const val DEFAULT_MODEL = "claude-sonnet-4-5-20250929"
    const val MAX_CONTEXT_CHARS = 12_000
    const val MAX_REPLAY_CHARS = 40_000
    private const val SYSTEM = "You are VEER, a personal AI assistant. Reply in Hindi/Hinglish. Only use declared tools. Never claim an action was performed unless its result confirms it. Device actions require approval."

    fun build(turn: AgentTurn, model: String = DEFAULT_MODEL): JSONObject {
        require(turn.userRequest.isNotBlank() && model.isNotBlank())
        val prompt = StringBuilder("User request:\n").append(turn.userRequest.take(3500))
        appendSection(prompt, "Saved context", turn.memory, 2500)
        appendSection(prompt, "Recent conversation", turn.recentConversation, 2500)
        if (turn.previousToolResult != null && turn.providerContext == null)
            prompt.append("\n\nPrevious tool result:\n").append(turn.previousToolResult.take(1500))
        require(prompt.length <= MAX_CONTEXT_CHARS)
        val messages = JSONArray()
        if (turn.providerContext == null && turn.previousToolCallId == null) {
            messages.put(JSONObject().put("role","user").put("content",prompt.toString()))
        } else {
            require(!turn.providerContext.isNullOrBlank() && !turn.previousToolCallId.isNullOrBlank() && !turn.previousToolResult.isNullOrBlank())
            val replay = JSONArray(turn.providerContext)
            require(replay.length() > 0 && turn.providerContext.length <= MAX_REPLAY_CHARS)
            val toolUseBlocks = (0 until replay.length()).mapNotNull { index ->
                replay.optJSONObject(index)?.takeIf { it.optString("type") == "tool_use" }
            }
            require(toolUseBlocks.size == 1)
            require(toolUseBlocks.single().optString("id") == turn.previousToolCallId)
            messages.put(JSONObject().put("role","user").put("content",prompt.toString()))
            messages.put(JSONObject().put("role","assistant").put("content",replay))
            messages.put(JSONObject().put("role","user").put("content",JSONArray().put(
                JSONObject().put("type","tool_result").put("tool_use_id",turn.previousToolCallId)
                    .put("content",turn.previousToolResult.take(4000))
            )))
        }
        return JSONObject().put("model",model).put("max_tokens",1200).put("system",SYSTEM)
            .put("messages",messages).put("tools",tools())
    }

    internal fun tools(): JSONArray {
        val array=JSONArray()
        VeerAgentToolCatalog.definitions().forEach { tool ->
            val props=JSONObject(); val required=JSONArray()
            tool.parameters.forEach { p ->
                val schema=JSONObject().put("type",p.type.lowercase()).put("description",p.description)
                if(p.allowedValues.isNotEmpty()) schema.put("enum",JSONArray(p.allowedValues))
                props.put(p.name,schema); if(p.required) required.put(p.name)
            }
            array.put(JSONObject().put("name",tool.name).put("description",tool.description)
                .put("input_schema",JSONObject().put("type","object").put("properties",props)
                    .put("required",required).put("additionalProperties",false)))
        }
        return array
    }
    private fun appendSection(target:StringBuilder,title:String,values:List<String>,limit:Int){
        var left=limit;if(values.isEmpty())return
        target.append("\n\n").append(title).append(":\n")
        values.asReversed().forEach { if(left>0){val v=it.take(left);target.append("- ").append(v).append('\n');left-=v.length} }
    }
}