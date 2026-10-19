const {app,BrowserWindow,session,ipcMain}=require('electron');
const http=require('http'),fs=require('fs'),path=require('path'),{spawn}=require('child_process');
const T={'.html':'text/html; charset=utf-8','.js':'text/javascript','.mjs':'text/javascript','.wasm':'application/wasm','.json':'application/json'};
/* ---------- 配置与记忆（只在主进程，页面拿不到密钥） ---------- */
const cfgPath=path.join(__dirname,'config.json'),dataDir=path.join(__dirname,'data'),memPath=path.join(dataDir,'memory.json');
const cfg=()=>{try{return JSON.parse(fs.readFileSync(cfgPath,'utf8'))}catch{return{}}};
fs.mkdirSync(dataDir,{recursive:true});
let mem={facts:[],hist:[]};try{mem=JSON.parse(fs.readFileSync(memPath,'utf8'))}catch{}
const save=()=>fs.writeFileSync(memPath,JSON.stringify(mem,null,1));
async function claude(model,system,messages,max=400){
  const c=cfg();if(!c.apiKey)throw new Error('未配置 apiKey，请编辑 config.json');
  const r=await fetch('https://apihub.agnes-ai.com/v1/chat/completions',{method:'POST',
    headers:{'content-type':'application/json','Authorization':'Bearer '+c.apiKey},
    body:JSON.stringify({model,messages:[{role:'system',content:system},...messages],max_tokens:max})});
  const j=await r.json();if(!r.ok)throw new Error(j.error?.message||j.message||('HTTP '+r.status));
  return j.choices?.[0]?.message?.content||'';
}
async function updateMemory(u,a){
  const out=await claude(cfg().memModel||'agnes-3.0-flash',
    '你负责维护语音助手对用户的长期记忆。只输出 JSON：{"add":[...],"remove":[...]}。add 是从这轮对话得到的关于用户的持久事实（身份、偏好、计划、人际关系、习惯），写成简短陈述句；remove 是用户要求忘记、或已被新信息推翻的现有条目（必须与现有条目逐字一致）。不要记录一次性问题、闲聊、天气等易变信息；除非用户明确要求记住，不要记录密码、证件号、银行卡等敏感信息。没有变化就返回空数组。',
    [{role:'user',content:`现有记忆：\n${JSON.stringify(mem.facts)}\n\n用户：${u}\n助手：${a}`}],300);
  const m=out.match(/\{[\s\S]*\}/);if(!m)return;const j=JSON.parse(m[0]);
  mem.facts=mem.facts.filter(f=>!(j.remove||[]).includes(f));
  for(const f of j.add||[])if(typeof f==='string'&&f&&!mem.facts.includes(f))mem.facts.push(f);
  mem.facts=mem.facts.slice(-200);save();
}
ipcMain.handle('mem',()=>mem.facts);
ipcMain.handle('ask',async(e,text)=>{
  const sys=`你是 J.A.R.V.I.S.，用户的桌面语音助手，语气沉稳、简洁、略带幽默，称呼用户为“${cfg().userName||'先生'}”。你的回答会被朗读出来：用口语，不超过三句话，不用 Markdown、列表或表情符号。当前时间：${new Date().toLocaleString('zh-CN')}。\n你记得的关于用户的事：\n${mem.facts.map(f=>'- '+f).join('\n')||'（暂无）'}`;
  mem.hist.push({role:'user',content:text});
  let msgs=mem.hist.slice(-20);while(msgs.length&&msgs[0].role!=='user')msgs.shift();
  try{
    const reply=await claude(cfg().model||'agnes-3.0-flash',sys,msgs);
    mem.hist.push({role:'assistant',content:reply});mem.hist=mem.hist.slice(-40);save();
    updateMemory(text,reply).catch(()=>{});
    return reply;
  }catch(err){mem.hist.pop();throw err}
});
/* ---------- 语音识别：Windows 自带 System.Speech ---------- */
function startStt(win){
  if(process.platform!=='win32')return;
  const p=spawn('powershell',['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(__dirname,'stt.ps1'),cfg().sttCulture||'zh-CN'],{windowsHide:true});
  let buf='';p.stdout.setEncoding('utf8');
  p.stdout.on('data',d=>{buf+=d;let i;while((i=buf.indexOf('\n'))>=0){const l=buf.slice(0,i).trim();buf=buf.slice(i+1);
    if(l.startsWith('T:'))win.webContents.send('stt',l.slice(2));else if(l)win.webContents.send('stt-state',l)}});
  p.stderr.on('data',d=>win.webContents.send('stt-state','ERR:'+String(d).slice(0,160)));
  app.on('quit',()=>p.kill());
}
/* ---------- 本地静态服务（不公开 config 与 data） ---------- */
const srv=http.createServer((q,r)=>{
  let p=decodeURIComponent(q.url.split('?')[0]);if(p==='/')p='/index.html';
  const f=path.join(__dirname,p),rel=path.relative(__dirname,f);
  if(rel.startsWith('..')||/^(config|data)/i.test(rel)||!fs.existsSync(f)||fs.statSync(f).isDirectory()){r.writeHead(404);return r.end();}
  r.writeHead(200,{'Content-Type':T[path.extname(f)]||'application/octet-stream'});
  if(q.method==='HEAD')return r.end();
  fs.createReadStream(f).pipe(r);
});
app.commandLine.appendSwitch('autoplay-policy','no-user-gesture-required');
app.whenReady().then(()=>srv.listen(0,'127.0.0.1',()=>{
  session.defaultSession.setPermissionRequestHandler((w,perm,cb)=>cb(perm==='media'));
  const win=new BrowserWindow({fullscreen:true,frame:false,backgroundColor:'#02070c',
    webPreferences:{preload:path.join(__dirname,'preload.js'),sandbox:false}});
  win.setMenuBarVisibility(false);
  win.loadURL('http://127.0.0.1:'+srv.address().port+'/');
  win.webContents.once('did-finish-load',()=>startStt(win));
  win.webContents.on('before-input-event',(e,i)=>{if(i.key==='Escape')app.quit();});
}));
app.on('window-all-closed',()=>app.quit());
