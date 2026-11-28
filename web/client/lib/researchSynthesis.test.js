import test from "node:test";
import assert from "node:assert/strict";
import { synthesizeResearch, validateResearchInput } from "./researchSynthesis.js";
const source={title:"Example",url:"https://example.org",snippet:"A source snippet"};
test("validates source list and rejects malformed or excessive input",()=>{
 const v=validateResearchInput({query:"question",sources:[source]});
 assert.equal(v.sources[0].id,"S1");
 assert.throws(()=>validateResearchInput({query:"q",sources:[]}),{status:400});
 assert.throws(()=>validateResearchInput({query:"q",sources:[{...source,url:"javascript:bad"}]}),{status:400});
});
test("returns answer and only citations mapped to supplied source IDs",async()=>{
 const result=await synthesizeResearch({query:"q",sources:[source]},{
 env:{GEMINI_API_KEY:"secret"},
 fetchImpl:async(url,options)=>{assert.equal(options.redirect,"error");return {ok:true,text:async()=>JSON.stringify({candidates:[{content:{parts:[{text:"Fact [S1] and [S9]."}]}}]})};}
 });
 assert.equal(result.answer,"Fact [S1] and .");
 assert.deepEqual(result.citations.map(x=>x.id),["S1"]);
});
test("fails closed if key absent",async()=>{await assert.rejects(synthesizeResearch({query:"q",sources:[source]},{env:{}}),{status:503});});
