package com.veer.assistant

import org.junit.Assert.*
import org.junit.Test

class VeerClaudeToolCallParserTest {
 @Test fun parsesOnlyAllowlistedToolUse(){
  val body="""{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"id-123","name":"android.launch_app","input":{"app":"youtube"}}]}"""
  val decision=VeerClaudeToolCallParser.parse(body)!!
  assertFalse(decision.finished);assertEquals("id-123",decision.providerCallId)
  assertEquals("youtube",decision.toolCall!!.arguments["app"])
 }
 @Test fun rejectsUnknownAppAndMultipleCalls(){
  val bad="""{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"a","name":"android.launch_app","input":{"app":"unknown"}}]}"""
  assertTrue(VeerClaudeToolCallParser.parse(bad)!!.finished)
  val multi="""{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"a","name":"android.launch_app","input":{"app":"youtube"}},{"type":"tool_use","id":"b","name":"android.launch_app","input":{"app":"browser"}}]}"""
  assertTrue(VeerClaudeToolCallParser.parse(multi)!!.finished)
 }
 @Test fun parsesTextResponse(){
  val result=VeerClaudeToolCallParser.parse("""{"content":[{"type":"text","text":"Namaste"}]}""")
  assertEquals("Namaste",result!!.message)
 }

 @Test fun blocksToolUseWhenAnthropicDidNotStopForToolUse(){
  val body="""{"stop_reason":"end_turn","content":[{"type":"tool_use","id":"a","name":"android.launch_app","input":{"app":"youtube"}}]}"""
  assertTrue(VeerClaudeToolCallParser.parse(body)!!.finished)
 }

 @Test fun rejectsNonStringAndUnexpectedArguments(){
  val nonString="""{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"a","name":"android.launch_app","input":{"app":7}}]}"""
  assertTrue(VeerClaudeToolCallParser.parse(nonString)!!.finished)
  val extra="""{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"a","name":"android.launch_app","input":{"app":"youtube","package":"com.attacker.app"}}]}"""
  assertTrue(VeerClaudeToolCallParser.parse(extra)!!.finished)
 }
 @Test fun rejectsMissingToolIdentityAndOversizedReplay(){
  val missingId="""{"stop_reason":"tool_use","content":[{"type":"tool_use","name":"android.launch_app","input":{"app":"youtube"}}]}"""
  assertTrue(VeerClaudeToolCallParser.parse(missingId)!!.finished)
  val huge="x".repeat(VeerClaudeAgentRequest.MAX_REPLAY_CHARS+1)
  val oversized="""{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"a","name":"android.launch_app","input":{"app":"youtube"}},{"type":"text","text":"$huge"}]}"""
  assertTrue(VeerClaudeToolCallParser.parse(oversized)!!.finished)
 }
}
