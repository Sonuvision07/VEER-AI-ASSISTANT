import{useState}from"react";
type ApprovedAction={label:string;url:string;description:string};
const ALLOWED_HOSTS=new Set(["google.com","www.google.com","youtube.com","www.youtube.com","mail.google.com","maps.google.com"]);
function safeAction(action:ApprovedAction):boolean{try{const u=new URL(action.url);return u.protocol==="https:"&&ALLOWED_HOSTS.has(u.hostname)&&!u.username&&!u.password}catch{return false}}
function resolveCommand(value:string):ApprovedAction|null{
 const input=value.trim().replace(/[.!?]+$/,"").trim();
 const open=input.match(/^(?:open|launch|kholo|khol do)\s+(google maps|google|youtube|gmail|maps|weather)$/i);
 if(open){const site=open[1].toLowerCase();const targets:Record<string,ApprovedAction>={
 google:{label:"Google",url:"https://www.google.com",description:"Open Google search"},
 youtube:{label:"YouTube",url:"https://www.youtube.com",description:"Open YouTube"},
 gmail:{label:"Gmail",url:"https://mail.google.com",description:"Open Gmail"},
 maps:{label:"Google Maps",url:"https://maps.google.com",description:"Open Google Maps"},
 "google maps":{label:"Google Maps",url:"https://maps.google.com",description:"Open Google Maps"},
 weather:{label:"Weather search",url:"https://www.google.com/search?q=weather",description:"Search for current weather"}};
 return targets[site]||null}
 const search=input.match(/^(?:search|find)\s+(google|youtube)(?:\s+for)?\s+(.+)$/i);
 const hinglish=input.match(/^(google|youtube)\s+(?:par|pe)\s+(.+?)\s+(?:search karo|search kar|dhundo)$/i);
 const match=search||hinglish;if(!match)return null;
 const engine=match[1].toLowerCase(),query=match[2].trim();if(!query)return null;
 const url=engine==="youtube"?"https://www.youtube.com/results?search_query="+encodeURIComponent(query):"https://www.google.com/search?q="+encodeURIComponent(query);
 return{label:engine==="youtube"?"YouTube results":"Google results",url,description:"Search for “"+query+"”"}}
export default function CommandCenter(){
 const[command,setCommand]=useState(""),[pending,setPending]=useState<ApprovedAction|null>(null),[message,setMessage]=useState("");
 function prepare(event:React.FormEvent<HTMLFormElement>){event.preventDefault();const action=resolveCommand(command);if(!action||!safeAction(action)){setPending(null);setMessage("Command invalid ya unsupported hai. Examples use karo.");return}setPending(action);setMessage("")}
 function approve(){if(!pending||!safeAction(pending)){setPending(null);setMessage("Safety check failed. Action blocked.");return}const opened=window.open(pending.url,"_blank","noopener,noreferrer");if(opened){setMessage("Browser ne link opening request accept ki. Tab check kar lo.");}else{setMessage("Browser ne popup block kiya. Popup permission allow karke retry karo.");}setPending(null)}
 return <section className="command-center" aria-label="VEER command center">
 <div className="command-heading"><b>VEER COMMAND CENTER</b><span>Approval required</span></div>
 <form className="command-form" onSubmit={prepare}><input aria-label="VEER command" value={command} onChange={e=>{setCommand(e.target.value);setPending(null)}} placeholder="Try: open YouTube or search Google for solar energy"/><button type="submit" disabled={!command.trim()}>Prepare</button></form>
 <div className="command-examples"><span>Try:</span><code>open YouTube</code><code>open Gmail</code><code>search Google for news</code><code>YouTube par music search karo</code></div>
 {message&&<p className="command-message" role="status">{message}</p>}
 {pending&&<div className="command-approval" role="status"><div><b>Confirm action</b><span>{pending.description}</span><small>Destination: {new URL(pending.url).hostname}</small></div><button type="button" onClick={approve}>Approve &amp; open ↗</button><button type="button" onClick={()=>{setPending(null);setMessage("Action cancelled.")}}>Cancel</button></div>}
 <p className="command-safety">Safety: allowlisted HTTPS destinations only; command URLs are validated before approval. This panel cannot execute shell commands, send messages, buy items or delete data.</p>
 </section>}
