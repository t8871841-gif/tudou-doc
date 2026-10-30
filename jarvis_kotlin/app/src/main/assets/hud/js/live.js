'use strict';
/* ---- live link to the local voice service (ws://127.0.0.1:8765) ----
   When the service is running, the HUD shows what Jarvis really hears and says;
   typed input goes to the same brain. Without it, the demo replies above keep working. */
let live = null;
function connectVoice() {
  let ws;
  try { ws = new WebSocket('ws://127.0.0.1:8765'); } catch (_) { return setTimeout(connectVoice, 3000); }
  ws.onopen = () => { live = ws; document.body.dataset.live = '1'; input.placeholder = '说 “Hey Jarvis”，或在这里打字'; if (state === 'idle') stateText.textContent = '待命中 · 说 "Hey Jarvis"'; };
  ws.onclose = () => { if (live === ws) { live = null; delete document.body.dataset.live; } setTimeout(connectVoice, 3000); };
  ws.onerror = () => { try { ws.close(); } catch (_) {} };
  ws.onmessage = ev => {
    let m; try { m = JSON.parse(ev.data); } catch (_) { return; }
    if (SK.core && SK.core.msg) try { SK.core.msg(m); } catch (_) {}   /* skins: the core reacts to what Jarvis is doing */
    if (m.type === 'state') {
      const s = m.state === 'listening' ? 'listening' : m.state === 'thinking' ? 'thinking' : m.state === 'speaking' ? 'speaking' : 'idle';
      setState(s);
      if (s === 'listening' && !sub.textContent.trim()) sub.innerHTML = '<span class="who you">YOU</span><span style="color:var(--ink-3)">正在聆听…</span>';
      if (s === 'idle') stateText.textContent = '待命中 · 说 "Hey Jarvis"';
    }
    else if (m.type === 'screen') { if (m.mode === 'awake') { dormHold = false; dormAsked = false; wakeUI(); sleepUI(30000); } else if (state === 'speaking' || state === 'thinking') dormAsked = true; else dormNow(); }
    else if (m.type === 'wake') { dormHold = false; wakeUI(); clearInterval(typeTimer); openTalk(); sub.innerHTML = '<span class="who you">YOU</span><span style="color:var(--ink-3)">正在聆听…</span>'; }
    else if (m.type === 'you') typeOut('you', m.text, 12);
    else if (m.type === 'jarvis') typeOut('jarvis', m.text, 40, null, m.zh);
    else if (m.type === 'stop' || m.type === 'barge_in') clearInterval(typeTimer);
    else if (m.type === 'level') meter(m.mic, m.wake);
    else if (m.type === 'sys') sysUpdate(m);
    else if (m.type === 'schedule') applySchedule(m.items || []);
    else if (m.type === 'weather') wxUpdate(m);
    else if (m.type === 'trip') tripUpdate(m);
    else if (m.type === 'stats') statsShow(m);
    else if (m.type === 'enroll') enrollUpdate(m);
    else if (m.type === 'not_you') notYou(m);
    else if (m.type === 'brain') brainEvent(m);
    else if (m.type === 'history') subsHistory(m.items);
    else if (m.type === 'machine') { if ((m.action || 'show') === 'show') { if (SHIP) SHIP.hide(); if (OP && OP.state.on) OP.hide(); } machineCommand(m); }
    else if (m.type === 'starship') shipCommand(m);
    else if (m.type === 'skin') skinCommand(m);
    else if (m.type === 'roadster') roadsterCommand(m);
    else if (m.type === 'optimus') optimusCommand(m);
    else if (m.type === 'hand') handMsg(m);
    else if (m.type === 'hand_frame') handFrame(m);
    else if (m.type === 'heard') { meterHeard.textContent = '听到：' + (m.text || '…'); }
    else if (m.type === 'rejected') { meterHeard.textContent += '（不是唤醒词）'; }
    else if (m.type === 'mic') { meterHeard.textContent = m.ok ? '' : m.text; meterHeard.classList.toggle('warn', !m.ok); }
    else if (m.type === 'agents') agentsUpdate(m);
    else if (m.type === 'llm') { if (m.name) window.JV_NAME = m.name === 'sir' ? '' : m.name; if (window.ONBOARD) ONBOARD.msg(m); }
  };
}
/* small live meter: shows the mic is picking up sound and how close it came to "Hey Jarvis" */
const meterBox = document.createElement('div');
meterBox.id = 'voiceMeter';
meterBox.innerHTML = '<span class="ml">MIC</span><span class="mbar"><i id="micBar"></i></span><span class="ml">WAKE</span><span class="mbar"><i id="wakeBar"></i></span><span id="meterHeard"></span>';
document.body.appendChild(meterBox);
const micBar = meterBox.querySelector('#micBar'), wakeBar = meterBox.querySelector('#wakeBar'), meterHeard = meterBox.querySelector('#meterHeard');
let wakeHold = 0;
function meter(mic, wake) {
  micBar.style.width = Math.min(100, Math.sqrt(mic || 0) * 140) + '%';
  wakeHold = Math.max(wake || 0, wakeHold * 0.85);
  wakeBar.style.width = Math.min(100, wakeHold * 100) + '%';
  wakeBar.classList.toggle('hot', wakeHold >= 0.25);
}
function liveSend(obj) { if (live && live.readyState === 1) { live.send(JSON.stringify(obj)); return true; } return false; }
window.liveSend = liveSend;
const demoHandle = handle;
handle = q => { q = q.trim(); if (!q) return; if (liveSend({ type: 'text', text: q })) { openTalk(); typeOut('you', q, 12); } else demoHandle(q); };
addEventListener('keydown', e => { if (e.key === 'Escape') liveSend({ type: 'interrupt' }); }, true);
const demoStart = startListen, demoStop = stopListen;
startListen = () => { if (!live) return demoStart(); openTalk(); clearInterval(typeTimer); sub.innerHTML = '<span class="who">JARVIS</span>直接说 “Hey Jarvis”，我一直在听。'; };
stopListen = () => { if (!live) demoStop(); };
connectVoice();
