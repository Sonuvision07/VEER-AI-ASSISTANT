import { authenticate, CloudTaskError, sendJson, setCors } from "../../lib/cloudTaskStore.js";
import { ResearchSearchError, synthesizeResearch } from "../../lib/researchSynthesis.js";
const LIMIT=12*1024;
export default async function handler(req,res){
 setCors(req,res);res.setHeader("Access-Control-Allow-Methods","POST, OPTIONS");res.setHeader("Cache-Control","no-store");
 if(req.method==="OPTIONS"){res.statusCode=204;return res.end();}
 if(req.method!=="POST"){res.setHeader("Allow","POST, OPTIONS");return sendJson(res,405,{ok:false,error:"Method not allowed"});}
 try{
  authenticate(req);let body=req.body;
  if(typeof body==="string"){if(Buffer.byteLength(body)>LIMIT)return sendJson(res,413,{ok:false,error:"Request too large"});try{body=JSON.parse(body);}catch{return sendJson(res,400,{ok:false,error:"Invalid JSON"});}}
  if(!body||typeof body!=="object"||Array.isArray(body)||Buffer.byteLength(JSON.stringify(body))>LIMIT)return sendJson(res,413,{ok:false,error:"Request invalid or too large"});
  const result=await synthesizeResearch(body);return sendJson(res,200,{ok:true,...result});
 }catch(e){const status=e instanceof CloudTaskError||e instanceof ResearchSearchError?e.status:500;return sendJson(res,status,{ok:false,error:status===500?"Research synthesis temporarily unavailable":e.message});}
}
