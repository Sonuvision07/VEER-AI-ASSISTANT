import{useState}from"react";
type Props={language:string;rate:number;onLanguageChange:(value:string)=>void;onRateChange:(value:number)=>void};
export default function SettingsPanel({language,rate,onLanguageChange,onRateChange}:Props){
 const[open,setOpen]=useState(false);
 return <section className="settings-panel">
  <button className="settings-toggle" onClick={()=>setOpen(v=>!v)} aria-expanded={open}>⚙ VEER SETTINGS <span>{open?"Hide":"Customize voice"}　⌄</span></button>
  {open&&<div className="settings-content">
   <label className="setting-field"><span>Voice language</span><select value={language} onChange={e=>onLanguageChange(e.target.value)}><option value="hi-IN">Hindi (India)</option><option value="en-IN">English (India)</option><option value="en-US">English (US)</option></select></label>
   <label className="setting-field"><span>Speaking speed <b>{rate.toFixed(1)}×</b></span><input type="range" min="0.7" max="1.3" step="0.1" value={rate} onChange={e=>onRateChange(Number(e.target.value))}/><small>Applies to VEER voice replies and microphone recognition language.</small></label>
  </div>}
 </section>
}