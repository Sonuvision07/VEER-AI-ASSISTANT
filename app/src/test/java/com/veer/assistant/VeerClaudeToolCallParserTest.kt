package com.veer.assistant

import org.junit.Assert.*
import org.junit.Test

class VeerClaudeToolCallParserTest {
 @Test fun parsesOnlyAllowlistedToolUse(){
  val body="""{"content":[{"type":"tool_use","id":"id-123","name":"android.launch_app","input":{"app":"youtube"}}]}"""
  val decision=VeerClaudeToolCallParser.parse(body)!!
  assertFalse(decision.finished);assertEquals("id-123",decision.providerCallId)
  assertEquals("youtube",decision.toolCall!!.arguments["app"])
 }
 @Test fun rejectsUnknownAppAndMultipleCalls(){
  val bad="""{"content":[{"type":"tool_use","id":"a","name":"android.launch_app","input":{"app":"unknown"}}]}"""
  assertNull(VeerClaudeToolCallParser.parse(bad))
  val multi="""{"content":[{"type":"tool_use","id":"a","name":"android.launch_app","input":{"app":"youtube"}},{"type":"tool_use","id":"b","name":"android.launch_app","input":{"app":"browser"}}]}"""
  assertTrue(VeerClaudeToolCallParser.parse(multi)!!.finished)
 }
 @Test fun parsesTextResponse(){
  val result=VeerClaudeToolCallParser.parse("""{"content":[{"type":"text","text":"Namaste"}]}""")
  assertEquals("Namaste",result!!.message)
 }
}
