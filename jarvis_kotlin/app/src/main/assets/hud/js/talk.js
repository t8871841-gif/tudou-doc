'use strict';

let voiceOn = true;
$('#btnVoice').addEventListener('click', e => { voiceOn = !voiceOn; e.currentTarget.setAttribute('aria-pressed', String(voiceOn)); e.currentTarget.querySelector('b').textContent = voiceOn ? '开' : '关'; if (!voiceOn) try { speechSynthesis.cancel(); } catch (_) {} });
function speak(text, onEnd) {
  setState('speaking');
  let ended = false; const finish = () => { if (ended) return; ended = true; if (state === 'speaking') setState('idle'); onEnd && onEnd(); };
  typeOut('jarvis', text, 55);
  const fallback = setTimeout(finish, Math.max(2600, text.length * 190));
  if (voiceOn && 'speechSynthesis' in window) {
    try {
      speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(text); u.lang = 'zh-CN'; u.rate = 1.05; u.pitch = 0.85;
      const v = speechSynthesis.getVoices().find(v => /zh/i.test(v.lang)); if (v) u.voice = v;
      u.onend = () => { clearTimeout(fallback); setTimeout(finish, 200); };
      speechSynthesis.speak(u);
    } catch (_) {}
  }
}

const focusTimers = {};
function focusPanel(id) { const el = document.getElementById(id); if (!el) return; el.classList.remove('is-focus'); void el.offsetWidth; el.classList.add('is-focus'); clearTimeout(focusTimers[id]); focusTimers[id] = setTimeout(() => el.classList.remove('is-focus'), 6000); if (innerWidth < 900) el.scrollIntoView({ behavior: 'smooth', block: 'center' }); }

let approvalBox = null;

function respond(q) {
  const { cur, next } = schedInfo();
  if (/安排|日程|会议|今天.*(干|做)|几点/.test(q)) {
    focusPanel('pSched');
    const left = SCHED.filter((s, i) => i >= (cur >= 0 ? cur : next >= 0 ? next : SCHED.length)).length;
    if (cur >= 0) return `现在是${SCHED[cur][2]}，到 ${SCHED[cur][1]} 结束。今天还剩 ${left} 项安排。`;
    if (next >= 0) return `今天还有 ${left} 项安排。下一项是 ${SCHED[next][0]} 的${SCHED[next][2]}，内容是${SCHED[next][3]}。`;
    return '今天的安排都结束了。要我帮你把明天的计划排一下吗？';
  }
  if (/天气|出行|下雨|温度|穿|路况|通勤/.test(q)) { focusPanel('pWeather'); return '上海今天多云，18 度，空气优。明天 16 到 24 度。去张江科学城现在出发大约 32 分钟，路上畅通。'; }
  if (/大脑|任务|学习|复盘|agent/i.test(q)) { focusPanel('pAgents'); return '语音服务没连上，左边的 Agent 面板暂时没有实时数据。'; }
  if (/汇报|总结|进度|工作|完成/.test(q)) {
    focusPanel('pReport');
    const rest = TASKS.filter(t => t[1] < 100).map(t => t[0].replace(/\s*\(.*\)/, '')).join('、');
    return `今天 12 项任务完成了 ${done} 项，进度 ${Math.round(done / 12 * 100)}%。还没做完的是${rest}。`;
  }
  if (/你好|在吗|贾维斯|jarvis/i.test(q)) return '我在。有什么需要？';
  return '我在。语音服务还没连上，现在只能演示日程和天气。';
}

let busy = false;
let handle = function (q) {
  q = q.trim(); if (!q) return;
  try { speechSynthesis.cancel(); } catch (_) {}
  busy = true;
  typeOut('you', q, 18, () => {
    setState('thinking');
    setTimeout(() => { const ans = respond(q); speak(ans, () => { busy = false; }); }, 1100 + Math.random() * 500);
  });
};

const SAMPLES = ['今天有什么安排？', '明天天气怎么样？', '拆开一台发动机给我看看', '进入待机'];
$('#chips').innerHTML = SAMPLES.map(s => `<button type="button" class="chip">${s}</button>`).join('');
document.querySelectorAll('.chip').forEach(c => c.addEventListener('click', () => handle(c.textContent)));
$('#form').addEventListener('submit', e => { e.preventDefault(); const v = input.value; input.value = ''; handle(v); });
$('#btnSearch').addEventListener('click', () => { openTalk(); input.focus(); });
$('#knob').addEventListener('click', () => { openTalk(); input.focus(); });
input.addEventListener('focus', openTalk); input.addEventListener('blur', () => scheduleClose(4000));
$('#btnMic').addEventListener('click', () => toggleListen());
$('#btnMenu').addEventListener('click', e => { const pop = $('#menuPop'); pop.hidden = !pop.hidden; e.currentTarget.setAttribute('aria-expanded', String(!pop.hidden)); });
document.addEventListener('click', e => { if (!e.target.closest('.menu-wrap')) { $('#menuPop').hidden = true; $('#btnMenu').setAttribute('aria-expanded', 'false'); } });

let listenTimer = null, sampleIdx = 0, heard = '';
function startListen() {
  if (state === 'listening') return;
  try { speechSynthesis.cancel(); } catch (_) {}
  clearInterval(typeTimer); setState('listening');
  heard = SAMPLES[sampleIdx++ % SAMPLES.length];
  sub.innerHTML = '<span class="who you">YOU</span><span style="color:var(--ink-3)">正在聆听…</span>';
  let i = 0; clearInterval(listenTimer);
  setTimeout(() => { listenTimer = setInterval(() => { if (state !== 'listening') return clearInterval(listenTimer); i = Math.min(heard.length, i + 1); sub.innerHTML = '<span class="who you">YOU</span>' + escapeHtml(heard.slice(0, i)) + '<span class="caret"></span>'; }, 90); }, 500);
}
function stopListen() {
  if (state !== 'listening') return; clearInterval(listenTimer);
  setState('thinking');
  sub.innerHTML = '<span class="who you">YOU</span>' + escapeHtml(heard);
  setTimeout(() => speak(respond(heard)), 1100);
}
let clickListen = null;
function toggleListen() { if (state === 'listening') { clearTimeout(clickListen); stopListen(); } else { startListen(); clearTimeout(clickListen); clickListen = setTimeout(stopListen, 3200); } }
$('#mic').addEventListener('click', toggleListen);
addEventListener('keydown', e => {
  const typing = document.activeElement === input;
  if (e.code === 'Space' && !typing && !e.repeat && !(document.activeElement && /BUTTON/.test(document.activeElement.tagName))) { e.preventDefault(); clearTimeout(clickListen); startListen(); }
  else if (e.key === 'Escape') { try { speechSynthesis.cancel(); } catch (_) {} clearInterval(typeTimer); clearInterval(listenTimer); clearTimeout(clickListen); if (approvalBox && approvalBox.isConnected) approvalBox.querySelector('.no').click(); setState('idle'); sub.innerHTML = '<span class="who">JARVIS</span>好的，已停止。'; }
  else if ((e.key === 'k' && (e.metaKey || e.ctrlKey)) || (e.key === '/' && !typing)) { e.preventDefault(); openTalk(); input.focus(); }
});
addEventListener('keyup', e => { if (e.code === 'Space' && state === 'listening' && document.activeElement !== input) stopListen(); });
/* while a machine hologram is live, a click on the core belongs to the hologram (machine.js) */
const machListen = toggleListen;
toggleListen = (...a) => { if (machLive()) return; return machListen(...a); };
