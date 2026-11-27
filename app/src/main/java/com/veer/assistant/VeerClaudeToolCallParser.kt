package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Parses Anthropic text and one strictly validated tool-use block. */
internal object VeerClaudeToolCallParser {
    fun parse(responseBody:String, previousContext:String?=null, previousCallId:String?=null, previousToolResult:String?=null):AgentDecision? = try {
        val root=JSONObject(responseBody); val blocks=root.optJSONArray("content")?:return null
        val calls=(0 until blocks.length()).mapNotNull { blocks.optJSONObject(it)?.takeIf { b->b.optString("type")=="tool_use" } }
        if(calls.isEmpty()){
            val text=StringBuilder();for(i in 0 until blocks.length()){val b=blocks.optJSONObject(i)?:continue;if(b.optString("type")=="text")text.append(b.optString("text"))}
            return text.toString().trim().takeIf{it.isNotEmpty()}?.let{AgentDecision(it)}
        }
        if(calls.size!=1)return AgentDecision("VEER ne ek se zyada tool calls receive kiye; safety ke liye block kar diye.")
        val block=calls.single();val id=block.optString("id").takeIf{it.isNotBlank()}?:return AgentDecision("VEER ko tool-use ID nahi mila; action block.")
        val name=block.optString("name").takeIf{it.isNotBlank()}?:return AgentDecision("VEER ko tool name invalid mila; action block.")
        val risk=VeerAgentToolPolicy.riskFor(name)?:return AgentDecision("VEER ne unsupported tool block kiya: $name.")
        val input=block.optJSONObject("input")?:return AgentDecision("VEER ko tool input object invalid mila; action block.")
        val args=linkedMapOf<String,String>();val keys=input.keys()
        while(keys.hasNext()){val key=keys.next();val value=input.opt(key);if(value !is String)return AgentDecision("Tool argument type invalid; action block.");args[key]=value}
        val call=AgentToolCall(name,args,risk);if(!VeerAgentToolPolicy.isValid(call))return AgentDecision("VEER ne tool request allowlist se verify nahi kiya.")
        val replay=JSONArray();for(i in 0 until blocks.length())replay.put(blocks.get(i))
        if(replay.toString().length>VeerClaudeAgentRequest.MAX_REPLAY_CHARS)return AgentDecision("VEER tool context size limit cross hua.")
        AgentDecision("Boss, ${args["app"]} open karne ke liye permission chahiye.",call,false,replay.toString(),id)
    }catch(_:Exception){null}
}