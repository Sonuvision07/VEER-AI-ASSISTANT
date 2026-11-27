package com.veer.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VeerClaudeAgentRequestTest {
    @Test fun requestUsesAnthropicToolSchemaAndHeadersContract(){
        val request=VeerClaudeAgentRequest.build(AgentTurn("open youtube",emptyList(),emptyList()))
        assertEquals(VeerClaudeAgentRequest.DEFAULT_MODEL,request.getString("model"))
        assertEquals(1200,request.getInt("max_tokens"))
        val tools=request.getJSONArray("tools")
        assertEquals(1,tools.length())
        assertTrue(tools.getJSONObject(0).has("input_schema"))
        assertFalse(tools.getJSONObject(0).has("parameters"))
    }
    @Test fun continuationPairsAssistantToolUseWithToolResult(){
        val context="""[{"type":"tool_use","id":"call-1","name":"android.launch_app","input":{"app":"youtube"}}]""
        val turn=AgentTurn("open youtube",emptyList(),emptyList(),"opened",context,"call-1",AiProvider.ANTHROPIC)
        val messages=VeerClaudeAgentRequest.build(turn).getJSONArray("messages")
        assertEquals("assistant",messages.getJSONObject(1).getString("role"))
        assertEquals("user",messages.getJSONObject(2).getString("role"))
        assertEquals("call-1",messages.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("tool_use_id"))
    }
}
