const {contextBridge,ipcRenderer}=require('electron');const os=require('os');
let last=os.cpus();
contextBridge.exposeInMainWorld('sys',{read(){
  const c=os.cpus();let idle=0,tot=0;
  c.forEach((x,i)=>{for(const k in x.times)tot+=x.times[k]-last[i].times[k];idle+=x.times.idle-last[i].times.idle});
  last=c;
  return{cpu:tot?1-idle/tot:0,mem:1-os.freemem()/os.totalmem(),up:os.uptime(),host:os.hostname(),cores:c.length}}});
contextBridge.exposeInMainWorld('jarvis',{
  ask:t=>ipcRenderer.invoke('ask',t),mem:()=>ipcRenderer.invoke('mem'),
  onSpeech:cb=>ipcRenderer.on('stt',(e,t)=>cb(t)),onState:cb=>ipcRenderer.on('stt-state',(e,s)=>cb(s))});
