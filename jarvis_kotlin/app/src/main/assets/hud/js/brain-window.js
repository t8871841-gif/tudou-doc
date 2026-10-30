'use strict';
/* <<brain-window */
/* ---- the brain window: Jarvis's deep-thinking workspace (DeepSeek Harness), on the right of the screen ----
   Voice stays quick; this is where bigger jobs are typed, watched step by step, approved and installed. */
const BW = { open: false, busy: false, msgs: {}, tools: {}, asks: {}, el: null, tab: 'chat',
             images: [], usage: null, rotatePending: false, draft: '',
             tabs: new Map(), order: [], current: null, closed: new Set() };

function bwCur() { return BW.tabs.get(BW.current) || null; }
function bwTab(id) { return id ? BW.tabs.get(id) : null; }
function bwInput() { return BW.el ? BW.el.querySelector('#bwIn') : null; }
function bwPaintTab(id) {
  const t = bwTab(id); if (!t || !BW.el) return;
  const b = BW.el.querySelector(`#bwTabs .bw-conv-tab[data-tab="${id}"]`);
  if (!b) return;
  b.setAttribute('aria-selected', String(id === BW.current));
  b.classList.toggle('working', !!t.working);
  b.classList.toggle('unread', !!t.unread && id !== BW.current);
  const name = b.querySelector('.name'); if (name) name.textContent = t.title || '对话';
}
function bwRenderTabs() {
  if (!BW.el) return;
  const bar = BW.el.querySelector('#bwTabs'); if (!bar) return;
  bar.innerHTML = '';
  BW.order.forEach((id, i) => {
    const t = BW.tabs.get(id); if (!t) return;
    const b = document.createElement('button');
    b.type = 'button'; b.className = 'bw-conv-tab'; b.dataset.tab = id;
    b.setAttribute('role', 'tab'); b.setAttribute('aria-selected', String(id === BW.current));
    b.title = t.title || `对话 ${i + 1}`;
    b.innerHTML = '<span class="dot"></span><span class="name"></span><span class="x" role="button" tabindex="0" title="关闭">×</span>';
    b.querySelector('.name').textContent = t.title || `对话 ${i + 1}`;
    b.onclick = () => bwSelectTab(id);
    const x = b.querySelector('.x');
    x.onclick = e => { e.stopPropagation(); bwCloseTab(id); };
    x.onkeydown = e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); e.stopPropagation(); bwCloseTab(id); } };
    bar.appendChild(b);
  });
  const add = document.createElement('button');
  add.type = 'button'; add.id = 'bwNewTab'; add.className = 'bw-conv-new';
  add.title = '新开一个对话'; add.setAttribute('aria-label', '新开一个对话'); add.textContent = '+';
  add.onclick = () => bwNewTab({ announce: true });
  bar.appendChild(add);
  BW.order.forEach(bwPaintTab);
}
function bwTopView(t) {
  BW.tab = t;
  if (!BW.el) return;
  BW.el.querySelectorAll('.tabs button').forEach(b => b.setAttribute('aria-selected', String(b.dataset.tab === t)));
  const chat = BW.el.querySelector('#bwChat'), ab = BW.el.querySelector('#bwAb');
  if (chat) chat.hidden = t !== 'chat';
  if (ab) ab.hidden = t !== 'ab';
}
function bwApplyPanes() {
  if (!BW.el) return;
  BW.order.forEach(id => { const t = BW.tabs.get(id); if (t && t.el) t.el.hidden = id !== BW.current; });
}
function bwEffortName(level) { return level === 'low' ? '低' : level === 'max' ? '高' : '中'; }
function bwShortTokens(n) {
  if (typeof n !== 'number' || !isFinite(n)) return '—';
  if (n < 1000) return String(Math.round(n));
  if (n < 1e4) return (n / 1e3).toFixed(2) + 'K';
  if (n < 1e6) return (n / 1e3).toFixed(1) + 'K';
  if (n < 1e7) return (n / 1e6).toFixed(2) + 'M';
  return (n / 1e6).toFixed(1) + 'M';
}
function bwMoney(v, currency) {
  if (typeof v !== 'number' || !isFinite(v)) return '—';
  const sign = currency === 'USD' ? '$' : '¥';
  if (v === 0) return sign + '0.00';
  return sign + (v < 0.01 ? v.toFixed(4) : v.toFixed(2));
}
function bwPaintUsage(t) {
  if (!BW.el) return;
  const bar = BW.el.querySelector('#bwUsage'); if (!bar) return;
  const s = t && t.stats;
  const tok = bar.querySelector('[data-k="tok"]'), cost = bar.querySelector('[data-k="cost"]'), bal = bar.querySelector('[data-k="bal"]');
  if (!s) {
    if (tok) tok.textContent = '—'; if (cost) cost.textContent = '—'; if (bal) bal.textContent = '—';
    bar.title = '本对话的 token、花费和账户余额；点击刷新';
    return;
  }
  if (tok) tok.textContent = bwShortTokens(s.tokens);
  if (cost) cost.textContent = bwMoney(s.cost, s.currency);
  const b = s.balance;
  if (bal) bal.textContent = b && typeof b.total === 'number' ? bwMoney(b.total, b.currency || s.currency) : '—';
  const exact = Number(s.tokens || 0).toLocaleString('en-US');
  const n = v => Number(v || 0).toLocaleString('en-US');
  bar.title = `本对话 ${exact} tok\n输入 ${n(s.input)} · 缓存命中 ${n(s.cacheRead)} · 输出 ${n(s.output)}\n`
    + `花费 ${bwMoney(s.cost, s.currency)}${s.peak ? '（含高峰时段）' : ''}\n`
    + `账户余额 ${b && typeof b.total === 'number' ? bwMoney(b.total, b.currency || s.currency) : '未知'}`
    + (s.error ? `\n用量读取失败：${s.error}` : '')
    + '\n点击刷新';
}
function bwPaintEffort(t) {
  if (!BW.el) return;
  const level = (t && t.effort) || 'high';
  BW.el.querySelectorAll('#bwEffort button').forEach(b => {
    const on = b.dataset.effort === level;
    b.setAttribute('aria-pressed', String(on));
    if (on) b.title = '模型档位：' + bwEffortName(level) + (level === 'low' ? '（快，省）' : level === 'max' ? '（最深，慢）' : '（默认）');
  });
}
function bwUpdateHeader() {
  const t = bwCur(); if (!BW.el || !t) return;
  const state = t.busy ? 'working' : (t.state === 'off' ? 'off' : 'idle');
  BW.el.dataset.state = state;
  const sub = BW.el.querySelector('#bwSub');
  if (sub) sub.textContent = 'DeepSeek V4.1 Flash · ' + bwEffortName(t.effort) + '档 · ' +
    (state === 'working' ? '工作中' : state === 'off' ? '已断开' : '待命') + (t.usage ? ` · 上下文 ${t.usage}` : '');
  bwPaintEffort(t);
  bwPaintUsage(t);
  BW.busy = !!t.busy; BW.msgs = t.msgs; BW.tools = t.tools; BW.asks = t.asks; BW.usage = t.usage;
  BW.rotatePending = !!t.rotatePending; BW.images = t.images; BW.draft = t.draft;
}
function bwRenderImages(t) {
  if (!BW.el) return;
  const box = BW.el.querySelector('#bwImgs'); if (!box) return;
  box.innerHTML = '';
  t.images.forEach(i => {
    const img = document.createElement('img');
    img.src = i.src || `data:${i.mime || 'image/png'};base64,${i.data}`;
    box.appendChild(img);
  });
}
function bwSelectTab(id, opts) {
  const t = bwTab(id); if (!t) return;
  opts = opts || {};
  const prev = bwCur(), inp = bwInput();
  if (prev && prev !== t && inp) prev.draft = inp.value;
  BW.current = id;
  if (inp) inp.value = t.draft || '';
  bwRenderImages(t);
  t.unread = false;
  if (opts.view !== false) bwTopView('chat');
  bwApplyPanes();
  bwUpdateHeader();
  bwRenderTabs();
  if (BW.open) liveSend({ type: 'brain_usage', tab: id });
  if (opts.focus && inp) inp.focus({ preventScroll: true });
}
function bwNewTab(opts) {
  opts = opts || {};
  if (!BW.el) return null;
  const id = opts.id || ('t' + Date.now().toString(36).slice(-5) + Math.random().toString(36).slice(2, 5));
  if (BW.tabs.has(id)) { bwSelectTab(id, opts); return BW.tabs.get(id); }
  BW.closed.delete(id);
  const t = { id, title: opts.title || `对话 ${BW.order.length + 1}`, msgs: {}, tools: {}, asks: {},
              usage: null, busy: false, rotatePending: false, draft: '', images: [], unread: false,
              working: false, state: 'idle', effort: 'high', stats: null, freshNote: null, el: null };
  t.el = document.createElement('div');
  t.el.className = 'bw-chat-pane'; t.el.dataset.tab = id; t.el.hidden = true;
  t.el.innerHTML = '<div class="bw-note">' + escapeHtml(opts.note || '新对话') + '</div>';
  BW.el.querySelector('#bwChat').appendChild(t.el);
  BW.tabs.set(id, t); BW.order.push(id);
  if (opts.select !== false) bwSelectTab(id, { focus: !!opts.focus });
  else bwRenderTabs();
  if (opts.announce) liveSend({ type: 'brain_new', tab: id });
  return t;
}
function bwCloseTab(id) {
  const t = bwTab(id); if (!t) return;
  liveSend({ type: 'brain_close', tab: id });
  BW.closed.add(id);
  const was = BW.current === id, i = BW.order.indexOf(id);
  if (t.el) t.el.remove();
  BW.tabs.delete(id);
  if (i >= 0) BW.order.splice(i, 1);
  if (!BW.order.length) { bwNewTab({ announce: true, note: '新对话' }); return; }
  if (was) bwSelectTab(BW.order[Math.min(i, BW.order.length - 1)], { focus: true });
  else { bwUpdateHeader(); bwRenderTabs(); }
}
(function brainWindow() {
  const css = document.createElement('style');
  css.textContent = `
#brainWin{position:fixed;top:64px;right:16px;bottom:16px;width:min(460px,calc(100vw - 32px));z-index:70;display:flex;flex-direction:column;
  border:1px solid var(--line-2);border-radius:14px;background:rgba(var(--sk-bg),.86);backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px);
  box-shadow:0 0 40px rgba(0,0,0,.6),0 0 24px rgba(var(--sk-pri),.08) inset;transform:translateX(calc(100% + 40px));opacity:0;
  transition:transform .45s cubic-bezier(.2,.8,.2,1),opacity .35s;pointer-events:none;font-size:13.5px;color:var(--ink)}
#brainWin.on{transform:none;opacity:1;pointer-events:auto}
#brainWin header{display:flex;align-items:center;gap:10px;padding:12px 14px 10px;border-bottom:1px solid var(--line)}
#brainWin header .dot{width:7px;height:7px;border-radius:50%;background:var(--ink-3)}
#brainWin[data-state="working"] header .dot{background:var(--amber);box-shadow:0 0 10px var(--amber);animation:bwPulse 1s infinite}
#brainWin[data-state="idle"] header .dot{background:var(--ok);box-shadow:0 0 8px var(--ok)}
@keyframes bwPulse{50%{opacity:.35}}
#brainWin header b{font-family:var(--f-latin);font-weight:500;letter-spacing:2px;font-size:12px}
#brainWin header .sub{color:var(--ink-3);font-size:11px;letter-spacing:.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0}
#brainWin header .sp{flex:1}
#brainWin .bw-effort{display:flex;gap:2px;padding:2px;border:1px solid var(--line);border-radius:7px;background:rgba(0,0,0,.25);flex:none}
#brainWin .bw-effort button{padding:1px 6px;border-radius:5px;font-size:11px;line-height:1.5;color:var(--ink-3);border:1px solid transparent;background:none;cursor:pointer;font-family:inherit}
#brainWin .bw-effort button:hover{color:var(--ink);border-color:var(--line)}
#brainWin .bw-effort button[aria-pressed="true"]{color:var(--cyan);background:rgba(var(--sk-pri),.12);border-color:var(--line-2)}
#brainWin .bw-usage{display:flex;align-items:center;gap:6px;padding:5px 14px 6px;border-bottom:1px solid var(--line);background:rgba(var(--sk-pri),.025);color:var(--ink-3);font-size:11px;line-height:1.45;white-space:nowrap;overflow:hidden;cursor:pointer;font-variant-numeric:tabular-nums;user-select:none}
#brainWin .bw-usage:hover{background:rgba(var(--sk-pri),.06)}
#brainWin .bw-usage .u{display:flex;align-items:baseline;gap:3px;min-width:0}
#brainWin .bw-usage .u i{font-style:normal;color:var(--ink-3)}
#brainWin .bw-usage .u b{font-weight:500;color:var(--ink-2);font-size:11.5px}
#brainWin .bw-usage .u em{font-style:normal;color:var(--ink-3);font-size:10px}
#brainWin .bw-usage .sep{color:var(--line-2)}
#brainWin .bw-usage .rf{margin-left:auto;color:var(--ink-3);font-size:12px;padding-left:8px}
#brainWin .bw-usage.loading .rf{animation:bwSpin .8s linear infinite}
@keyframes bwSpin{to{transform:rotate(360deg)}}
#brainWin header button,#brainWin .tabs button{background:none;border:1px solid transparent;color:var(--ink-2);border-radius:6px;padding:3px 8px;cursor:pointer;font-size:12px}
#brainWin header button:hover,#brainWin .tabs button:hover{border-color:var(--line-2);color:var(--ink)}
#brainWin .tabs{display:flex;gap:4px;padding:6px 10px;border-bottom:1px solid var(--line)}
#brainWin .tabs button[aria-selected="true"]{color:var(--cyan);border-color:var(--line-2)}
#brainWin .pane{flex:1;overflow:auto;padding:12px 14px;display:flex;flex-direction:column;gap:9px;scrollbar-width:thin}
#brainWin .pane[hidden]{display:none}
#brainWin .bw-conv-tabs{display:flex;gap:4px;padding:6px 10px;border-bottom:1px solid var(--line);overflow-x:auto;scrollbar-width:none}
#brainWin .bw-conv-tabs::-webkit-scrollbar{display:none}
#brainWin .bw-conv-tab{display:flex;align-items:center;gap:6px;max-width:150px;padding:4px 7px;border:1px solid transparent;border-radius:7px;background:none;color:var(--ink-2);cursor:pointer;font-size:12px;white-space:nowrap;font-family:inherit}
#brainWin .bw-conv-tab[aria-selected="true"]{color:var(--ink);border-color:var(--line-2);background:rgba(var(--sk-pri),.06)}
#brainWin .bw-conv-tab .dot{width:5px;height:5px;border-radius:50%;background:var(--ink-3);flex:none}
#brainWin .bw-conv-tab.working .dot{background:var(--amber);box-shadow:0 0 6px var(--amber)}
#brainWin .bw-conv-tab.unread:not(.working) .dot{background:var(--cyan);box-shadow:0 0 6px var(--cyan)}
#brainWin .bw-conv-tab .name{overflow:hidden;text-overflow:ellipsis;max-width:105px}
#brainWin .bw-conv-tab .x{color:var(--ink-3);font-size:13px;line-height:1;padding:0 2px;border-radius:3px}
#brainWin .bw-conv-tab .x:hover{color:var(--ink);background:rgba(255,255,255,.08)}
#brainWin .bw-conv-new{border:1px solid transparent;background:none;color:var(--ink-3);border-radius:6px;width:24px;height:24px;line-height:1;cursor:pointer;font-size:16px;flex:none}
#brainWin .bw-conv-new:hover{border-color:var(--line-2);color:var(--ink)}
#brainWin #bwChat{padding:0;display:flex;flex-direction:column;overflow:hidden}
#brainWin .bw-chat-pane{flex:1;overflow:auto;padding:12px 14px;display:flex;flex-direction:column;gap:9px;scrollbar-width:thin}
#brainWin .bw-chat-pane[hidden]{display:none}
.bw-you{align-self:flex-end;max-width:86%;background:rgba(var(--sk-pri),.1);border:1px solid var(--line-2);border-radius:10px 10px 2px 10px;padding:7px 10px;white-space:pre-wrap}
.bw-say{line-height:1.6;white-space:normal}.bw-say p{margin:0 0 6px}.bw-say code{font-family:ui-monospace,Menlo,monospace;font-size:12px;background:rgba(var(--sk-pri),.08);padding:1px 4px;border-radius:4px}
.bw-say pre{background:rgba(0,0,0,.35);border:1px solid var(--line);border-radius:6px;padding:8px;overflow:auto;font-size:11.5px;margin:4px 0}
.bw-think{color:var(--ink-3);font-size:12px;border-left:2px solid var(--line-2);padding-left:8px;max-height:3.2em;overflow:hidden;cursor:pointer;white-space:pre-wrap}
.bw-think.open{max-height:none}
.bw-tool{border:1px solid var(--line);border-radius:8px;padding:6px 9px;font-size:12px;background:rgba(var(--sk-pri),.03)}
.bw-tool .h{display:flex;gap:7px;align-items:center;cursor:pointer}.bw-tool .h i{font-style:normal;width:12px;text-align:center}
.bw-tool .h b{font-family:ui-monospace,Menlo,monospace;font-weight:400;color:var(--cyan)}.bw-tool .h span{color:var(--ink-3);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;flex:1}
.bw-tool .d{display:none;margin-top:6px;font-family:ui-monospace,Menlo,monospace;font-size:11px;color:var(--ink-2);white-space:pre-wrap;max-height:220px;overflow:auto}
.bw-tool.open .d{display:block}
.bw-tool[data-s="failed"] .h i{color:#FF7A7A}.bw-tool[data-s="completed"] .h i{color:var(--ok)}.bw-tool[data-s="in_progress"] .h i{color:var(--amber)}
/* the working steps (thinking + tool calls) fold into one line per turn; the answer is what is read */
.bw-trace{border-left:1px solid var(--line);padding-left:8px;margin:2px 0;font-size:12px;color:var(--ink-3)}
.bw-trace .th{display:flex;gap:6px;align-items:center;cursor:pointer;user-select:none;color:var(--ink-3)}
.bw-trace .th i{font-style:normal;font-size:9px;color:var(--ink-3)}
.bw-trace.working .th i{color:var(--cyan);animation:bwPulse 1.4s ease-in-out infinite}
.bw-trace .th .s{flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.bw-trace .th em{font-style:normal;font-size:11px;color:var(--ink-3);white-space:nowrap}
.bw-trace .tb{display:none;margin-top:6px;flex-direction:column;gap:6px;max-height:340px;overflow:auto}
.bw-trace.open .tb{display:flex}
.bw-trace .bw-think{max-height:none;overflow:visible;border-left:0;padding-left:0;cursor:auto}
@keyframes bwPulse{0%,100%{opacity:.35}50%{opacity:1}}
.bw-ask{border:1px solid rgba(var(--sk-acc),.6);background:rgba(var(--sk-wbg),.55);border-radius:9px;padding:9px 11px;display:flex;flex-direction:column;gap:7px}
.bw-ask .q{color:rgb(var(--sk-accp))}.bw-ask .w{font-family:ui-monospace,Menlo,monospace;font-size:11px;color:var(--ink-2);white-space:pre-wrap;max-height:120px;overflow:auto}
.bw-ask .row{display:flex;gap:8px}.bw-ask button{flex:1;border-radius:7px;padding:6px;cursor:pointer;border:1px solid var(--line-2);background:rgba(var(--sk-pri),.08);color:var(--ink)}
.bw-ask button.no{background:transparent;color:var(--ink-2)}.bw-ask.done{opacity:.55}.bw-ask.done .row{display:none}
.bw-err{color:#FF9B9B;font-size:12px}
.bw-note{color:var(--ink-3);font-size:11.5px;text-align:center;padding:2px 0}
#brainWin footer{border-top:1px solid var(--line);padding:9px 10px 10px;display:flex;flex-direction:column;gap:6px}
#brainWin textarea{width:100%;min-height:58px;max-height:180px;resize:vertical;background:rgba(0,0,0,.3);border:1px solid var(--line-2);border-radius:9px;color:var(--ink);padding:8px 10px;font:inherit;font-size:13.5px;outline:none}
#brainWin textarea:focus{border-color:var(--cyan)}
#brainWin .row2{display:flex;gap:6px;align-items:center}#brainWin .row2 .imgs{flex:1;display:flex;gap:4px;overflow:hidden}
#brainWin .row2 .imgs img{height:28px;border-radius:4px;border:1px solid var(--line-2)}
#brainWin .row2 button{border-radius:8px;padding:6px 14px;cursor:pointer;border:1px solid var(--line-2);background:rgba(var(--sk-pri),.12);color:var(--ink)}
#brainWin .row2 .stop{display:none;width:38px;height:38px;min-width:38px;padding:0;border-radius:50%;background:rgba(255,120,120,.16);border-color:rgba(255,120,120,.55);place-items:center;color:#FFB3B3}
#brainWin .row2 .stop .sq{width:12px;height:12px;background:currentColor;border-radius:2px;display:block}
#brainWin[data-state="working"] .row2 .stop{display:grid}#brainWin[data-state="working"] .row2 .send{display:none}
.bw-ab h4{margin:10px 0 6px;font-weight:500;font-size:12px;color:var(--ink-2);letter-spacing:1px}
.bw-card{border:1px solid var(--line);border-radius:8px;padding:8px 10px;display:flex;flex-direction:column;gap:4px}
.bw-card .t{display:flex;gap:8px;align-items:center}.bw-card .t b{font-weight:500}.bw-card .t em{font-style:normal;color:var(--ink-3);font-size:11px;margin-left:auto}
.bw-card p{margin:0;color:var(--ink-2);font-size:12px}.bw-card .acts{display:flex;gap:6px}
.bw-card .acts button{font-size:11.5px;border-radius:6px;padding:3px 9px;cursor:pointer;border:1px solid var(--line-2);background:none;color:var(--ink-2)}
.bw-card .acts button.go{color:var(--ink);background:rgba(var(--sk-pri),.12)}
.icon-btn#btnBrain[aria-pressed="true"]{color:var(--cyan)}`;
  document.head.appendChild(css);
  const w = document.createElement('section');
  w.id = 'brainWin'; w.setAttribute('aria-label', '大脑窗口'); w.dataset.state = 'off';
  w.innerHTML = `<header><span class="dot"></span><b>BRAIN</b><span class="sub" id="bwSub">DeepSeek V4.1 Flash · 中档 · 未连接</span><span class="sp"></span>
      <span class="bw-effort" id="bwEffort" role="group" aria-label="模型档位"><button type="button" data-effort="low" title="低档：快而省，适合简单活">低</button><button type="button" data-effort="high" title="中档：默认" aria-pressed="true">中</button><button type="button" data-effort="max" title="高档：最深，适合难活，慢一些">高</button></span>
      <button type="button" id="bwNew" title="新开一个对话">新对话</button><button type="button" id="bwClose" aria-label="收起大脑窗口">×</button></header>
    <div id="bwUsage" class="bw-usage" role="button" tabindex="0" aria-label="本对话用量与账户余额，点击刷新" title="本对话的 token、花费和账户余额">
      <span class="u"><i>本对话</i><b data-k="tok">—</b><em>tok</em></span><span class="sep">·</span>
      <span class="u"><i>花费</i><b data-k="cost">—</b></span><span class="sep">·</span>
      <span class="u"><i>余额</i><b data-k="bal">—</b></span><span class="rf" aria-hidden="true">↻</span>
    </div>
    <div id="bwTabs" class="bw-conv-tabs" role="tablist" aria-label="对话标签"></div>
    <div class="tabs" role="tablist"><button type="button" role="tab" data-tab="chat" aria-selected="true">对话</button><button type="button" role="tab" data-tab="ab" aria-selected="false">能力与学习</button></div>
    <div class="pane" id="bwChat"></div>
    <div class="pane bw-ab" id="bwAb" hidden></div>
    <footer><textarea id="bwIn" placeholder="让大脑做什么？可以粘贴截图。⌘↩ 发送"></textarea>
      <div class="row2"><span class="imgs" id="bwImgs"></span><button type="button" class="stop" id="bwStop" aria-label="停止"><span class="sq"></span></button><button type="button" class="send" id="bwSend">发送</button></div></footer>`;
  document.body.appendChild(w);
  BW.el = w;
  const $b = s => w.querySelector(s), chat = $b('#bwChat'), ab = $b('#bwAb'), ta = $b('#bwIn');
  /* keys typed here belong to the brain window, never to Jarvis's own shortcuts (space to talk, arrows for subtitles) */
  w.addEventListener('keydown', e => {
    if (e.key === 'Escape') { e.stopPropagation(); return brainClose(); }
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); brainSend(); }
    e.stopPropagation();
  });
  w.addEventListener('keyup', e => e.stopPropagation());
  w.addEventListener('wheel', e => e.stopPropagation(), { passive: true });
  w.addEventListener('pointerdown', e => e.stopPropagation());
  $b('#bwClose').onclick = brainClose;
  $b('#bwSend').onclick = () => brainSend();
  $b('#bwStop').onclick = () => { const t = bwCur(); if (t) liveSend({ type: 'brain_cancel', tab: t.id }); };
  $b('#bwNew').onclick = () => bwNewTab({ announce: true });
  /* reasoning tier for the model: 低 / 中 / 高, remembered per conversation */
  w.querySelectorAll('#bwEffort button').forEach(b => b.onclick = () => {
    const t = bwCur(); if (!t) return;
    t.effort = b.dataset.effort;
    bwPaintEffort(t); bwUpdateHeader();
    if (!liveSend({ type: 'brain_effort', level: t.effort, tab: t.id }))
      brainAdd('bw-note', '语音服务没有连接，档位切换将在下次连接时生效。', null, t);
  });
  w.querySelectorAll('.tabs button').forEach(b => b.onclick = () => brainTab(b.dataset.tab));
  const usageBar = $b('#bwUsage');
  const refreshUsage = () => {
    const t = bwCur(); if (!t) return;
    usageBar.classList.add('loading');
    if (liveSend({ type: 'brain_usage', tab: t.id })) setTimeout(() => usageBar.classList.remove('loading'), 1500);
    else setTimeout(() => usageBar.classList.remove('loading'), 300);
  };
  usageBar.addEventListener('click', refreshUsage);
  usageBar.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); e.stopPropagation(); refreshUsage(); } });
  ta.addEventListener('input', () => { const t = bwCur(); if (t) t.draft = ta.value; });
  ta.addEventListener('paste', e => { for (const it of e.clipboardData.items) if (it.type.startsWith('image/')) { e.preventDefault(); brainImage(it.getAsFile()); } });
  ta.addEventListener('dragover', e => e.preventDefault());
  ta.addEventListener('drop', e => { e.preventDefault(); [...e.dataTransfer.files].filter(f => f.type.startsWith('image/')).forEach(brainImage); });
  /* a button in the top bar, and ⌘B */
  const icons = document.querySelector('.top .icons');
  if (icons) {
    const btn = document.createElement('button');
    btn.className = 'icon-btn'; btn.type = 'button'; btn.id = 'btnBrain'; btn.setAttribute('aria-label', '大脑窗口'); btn.setAttribute('aria-pressed', 'false');
    btn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M9 4.5a3 3 0 0 0-3 3 3 3 0 0 0-2 5.2A3 3 0 0 0 6 17.5a3 3 0 0 0 6 .5V5.5a2.5 2.5 0 0 0-3-1z"/><path d="M15 4.5a3 3 0 0 1 3 3 3 3 0 0 1 2 5.2 3 3 0 0 1-2 4.8 3 3 0 0 1-6 .5"/></svg>';
    btn.onclick = () => BW.open ? brainClose() : brainOpen();
    icons.insertBefore(btn, icons.firstChild);
  }
  addEventListener('keydown', e => { if (e.key === 'b' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); BW.open ? brainClose() : brainOpen(); } });
  setInterval(() => { if (BW.open && BW.current) liveSend({ type: 'brain_usage', tab: BW.current }); }, 30000);
  bwNewTab({ id: 'default', note: '复杂的事在这里交给大脑：学新本事、做东西、修问题。每一步都会显示在这里，需要你同意的操作会先问你。', select: true, announce: false });
})();
function brainOpen(task) {
  if (!BW.tabs.size) bwNewTab({ id: 'default', note: '复杂的事在这里交给大脑：学新本事、做东西、修问题。每一步都会显示在这里，需要你同意的操作会先问你。', select: true });
  BW.open = true; BW.el.classList.add('on');
  const b = document.getElementById('btnBrain'); if (b) b.setAttribute('aria-pressed', 'true');
  try { dormHold = false; wakeUI(); } catch (_) {}
  const t = bwCur();
  if (t) { liveSend({ type: 'brain_usage', tab: t.id }); }
  if (task) {
    bwTopView('chat');
    if (t) t.draft = task;
    const inp = bwInput(); if (inp) inp.value = task;
    bwApplyPanes();
  }
  const ta = bwInput();
  if (ta) ta.focus({ preventScroll: true });
  setTimeout(() => { const x = bwInput(); if (BW.open && x && document.activeElement !== x) x.focus({ preventScroll: true }); }, 350);
  if (BW.tab === 'ab') liveSend({ type: 'brain_abilities' });
}
function brainClose() {
  BW.open = false; BW.el.classList.remove('on');
  const b = document.getElementById('btnBrain'); if (b) b.setAttribute('aria-pressed', 'false');
  const ta = bwInput(); if (ta) ta.blur();
}
function brainTab(t) {
  bwTopView(t);
  if (t === 'ab') liveSend({ type: 'brain_abilities' });
  else bwApplyPanes();
}
function brainImage(file) {
  const t = bwCur(); if (!t || !file || t.images.length >= 4) return;
  const r = new FileReader();
  r.onload = () => {
    const [head, data] = String(r.result).split(',');
    t.images.push({ data, mime: (head.match(/data:([^;]+)/) || [])[1] || 'image/png', src: r.result });
    bwRenderImages(t);
  };
  r.readAsDataURL(file);
}
function brainSend() {
  const t = bwCur(), ta = bwInput(); if (!t || !ta) return;
  const text = ta.value.trim();
  if (!text || t.busy) return;
  const images = t.images.map(i => ({ data: i.data, mime: i.mime }));
  if (!liveSend({ type: 'brain_prompt', text, images, tab: t.id })) { brainAdd('bw-err', '贾维斯的语音服务没有连接，大脑暂时用不了。', null, t); return; }
  brainAdd('bw-you', text, null, t);
  if (/^对话 \d+$/.test(t.title)) { t.title = text.slice(0, 14) + (text.length > 14 ? '…' : ''); bwPaintTab(t.id); }
  ta.value = ''; t.draft = ''; t.images = []; bwRenderImages(t);
  t.busy = true; t.working = true; t.state = 'working'; t.unread = false;
  t.trace = null; t.traceN = 0;      // the next turn folds its own steps into a new line
  bwUpdateHeader(); bwPaintTab(t.id);
}
function brainFresh(note, t) {
  t = t || bwCur(); if (!t) return;
  t.el.innerHTML = '<div class="bw-note">' + escapeHtml(note || '新对话') + '</div>';
  t.msgs = {}; t.tools = {}; t.asks = {}; t.usage = null; t.busy = false;
  t.rotatePending = false; t.working = false; t.state = 'idle'; t.unread = false;
  t.trace = null; t.traceN = 0;
  if (t === bwCur()) bwUpdateHeader();
  bwPaintTab(t.id);
}
function brainAdd(cls, text, html, t) {
  t = t || bwCur(); if (!t) return null;
  const chat = t.el;
  const d = document.createElement('div'); d.className = cls;
  if (html != null) d.innerHTML = html; else d.textContent = text;
  const stick = chat.scrollHeight - chat.scrollTop - chat.clientHeight < 60;
  chat.appendChild(d);
  if (stick) chat.scrollTop = chat.scrollHeight;
  return d;
}
function brainMd(s) {
  const parts = String(s).split(/```(?:[a-z]*)\n?/);
  return parts.map((p, i) => i % 2 ? `<pre>${escapeHtml(p)}</pre>` :
    escapeHtml(p).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>').split(/\n{2,}/).map(x => `<p>${x.replace(/\n/g, '<br>')}</p>`).join('')).join('');
}
/* The working steps (thoughts, tool calls, the code Jarvis runs) fold into one line per turn.
   Only the Chinese answer is meant to be read; the line says what it is doing while it works,
   then reads 过程 · N 步 and opens on a click. */
function bwTrace(t) {
  if (t.trace && t.trace.isConnected) return t.trace;
  const el = brainAdd('bw-trace', '', '<div class="th"><i>●</i><span class="s"></span><em></em></div><div class="tb"></div>', t);
  el.querySelector('.th').title = '点击展开或收起过程';
  el.querySelector('.th').onclick = () => el.classList.toggle('open');
  t.trace = el; t.traceN = 0;
  return el;
}
function bwStep(t, label, count) {
  const el = bwTrace(t);
  el.classList.add('working');
  if (label != null) el.querySelector('.s').textContent = label;
  if (count !== false) el.querySelector('em').textContent = (++t.traceN) + ' 步';
  return el.querySelector('.tb');
}
function bwStepsDone(t) {
  const el = t.trace; if (!el) return;
  el.classList.remove('working');
  if (t.traceN) el.querySelector('.s').textContent = '过程';
}
function brainEvent(m) {
  const k = m.kind;
  if (k === 'abilities') return brainAbilities(m);
  if (k === 'open') {
    let t = m.tab ? bwTab(m.tab) : bwCur();
    if (!t) t = bwNewTab({ id: m.tab || undefined, note: '新对话', select: true });
    bwSelectTab(t.id, { view: true });
    return brainOpen(m.task);
  }
  if (k === 'status' && m.state === 'stopped' && !m.tab) {
    BW.tabs.forEach(t => { t.busy = false; t.working = false; t.state = 'off'; bwPaintTab(t.id); });
    bwUpdateHeader();
    return;
  }
  if (m.tab && BW.closed.has(m.tab)) return;
  const id = m.tab || BW.current;
  let t = bwTab(id);
  if (k === 'status' && m.fresh) {
    if (t) { if (m.effort) t.effort = m.effort; brainFresh(t.freshNote || '新对话', t); t.freshNote = null; }
    return;
  }
  if (!t) t = bwNewTab({ id, note: '新对话', select: false });
  const isCur = () => t === bwCur();
  if (k === 'usage_stats') {
    t.stats = m;
    if (isCur()) bwPaintUsage(t);
    return;
  }
  if (k === 'effort') {
    if (m.level) t.effort = m.level;
    if (isCur()) bwUpdateHeader(); else bwPaintTab(t.id);
    if (m.ok === false) brainAdd('bw-note', '档位没能切换（这个 Harness 版本可能不支持）：' + (m.error || ''), null, t);
    return;
  }
  if (k === 'status') {
    t.busy = m.state === 'working'; t.working = t.busy;
    t.state = m.state === 'stopped' ? 'off' : (t.busy ? 'working' : 'idle');
    if (!t.busy) bwStepsDone(t);
    if (m.effort) t.effort = m.effort;
    if (t.busy && !isCur()) t.unread = true;
    if (isCur()) bwUpdateHeader(); else bwPaintTab(t.id);
    if (m.state === 'idle' && t.rotatePending && !t.busy) {
      t.freshNote = '上下文接近满，旧对话已归档，开启新对话';
      liveSend({ type: 'brain_new', tab: t.id });
      brainFresh(t.freshNote, t);
      return;
    }
    if (m.state === 'idle' && m.stop && m.stop !== 'end_turn') brainAdd('bw-note', m.stop === 'cancelled' ? '已停止' : '结束：' + m.stop, null, t);
    return;
  }
  if (k === 'say') {
    const key = 'say:' + (m.id || 'x');
    let e = t.msgs[key];
    if (!e) { e = brainAdd('bw-say', '', null, t); e._raw = ''; t.msgs[key] = e; }
    e._raw += m.text || '';
    e.innerHTML = brainMd(e._raw);
    bwStepsDone(t);
    if (t.el.scrollHeight - t.el.scrollTop - t.el.clientHeight < 120) t.el.scrollTop = t.el.scrollHeight;
    if (!isCur()) { t.unread = true; bwPaintTab(t.id); }
    return;
  }
  if (k === 'think') {
    const key = 'think:' + (m.id || 'x');
    let e = t.msgs[key];
    const body0 = t.trace ? t.trace.querySelector('.tb') : null;
    const fresh = !e || e.parentNode !== body0 || body0.lastElementChild !== e;
    const body = bwStep(t, '思考中…', fresh);
    if (fresh) {
      e = document.createElement('div'); e.className = 'bw-think';
      body.appendChild(e); e._raw = ''; t.msgs[key] = e;
    }
    e._raw += m.text || '';
    e.textContent = e._raw;
    if (t.el.scrollHeight - t.el.scrollTop - t.el.clientHeight < 120) t.el.scrollTop = t.el.scrollHeight;
    return;
  }
  if (k === 'tool') {
    let e = t.tools[m.id];
    if (!e) {
      const body = bwStep(t, m.title || '执行');
      e = document.createElement('div'); e.className = 'bw-tool';
      e.innerHTML = `<div class="h"><i>●</i><b></b><span></span></div><div class="d"></div>`;
      e.querySelector('.h').onclick = ev => { ev.stopPropagation(); e.classList.toggle('open'); };
      body.appendChild(e);
      t.tools[m.id] = e; e._in = ''; e._out = '';
    }
    if (m.title) { e.querySelector('b').textContent = m.title; if (t.trace) t.trace.querySelector('.s').textContent = m.title; }
    if (m.input) { e._in = m.input; e.querySelector('.h span').textContent = m.input; }
    if (m.output) e._out = m.output;
    if (m.status) { e.dataset.s = m.status; e.querySelector('i').textContent = m.status === 'completed' ? '✓' : m.status === 'failed' ? '✕' : '●'; }
    e.querySelector('.d').textContent = (e._in ? '输入 ' + e._in : '') + (e._out ? '\n\n结果 ' + e._out : '');
    if (!isCur()) { t.unread = true; bwPaintTab(t.id); }
    return;
  }
  if (k === 'ask') {
    const tool = t.tools[m.tool], what = tool ? `${tool.querySelector('b').textContent}\n${tool._in}` : '（一项操作）';
    const e = brainAdd('bw-ask', '', `<div class="q">大脑想执行一个需要你同意的操作：</div><div class="w"></div><div class="row"><button type="button" class="yes">允许这一次</button><button type="button" class="no">拒绝</button></div>`, t);
    e.querySelector('.w').textContent = what;
    const go = allow => { liveSend({ type: 'brain_answer', ask: m.ask, allow }); e.classList.add('done'); };
    e.querySelector('.yes').onclick = () => go(true); e.querySelector('.no').onclick = () => go(false);
    t.asks[m.ask] = e;
    if (!isCur()) bwSelectTab(t.id, { view: true });
    else bwTopView('chat');
    brainOpen();
    return;
  }
  if (k === 'answered') {
    let a = m.tab ? bwTab(m.tab) : null;
    if (!a) a = [...BW.tabs.values()].find(x => x.asks[m.ask]);
    if (a) { const e = a.asks[m.ask]; if (e) { e.classList.add('done'); e.querySelector('.q').textContent = m.allow ? '已允许' : '已拒绝'; } }
    return;
  }
  if (k === 'usage') {
    if (m.used && m.size) {
      const ratio = m.used / m.size;
      t.usage = Math.round(ratio * 100) + '%';
      if (ratio >= 0.9) t.rotatePending = true;
      if (isCur()) bwUpdateHeader();
    }
    return;
  }
  if (k === 'error') { bwStepsDone(t); brainAdd('bw-err', m.text, null, t); t.busy = false; t.working = false; t.state = 'idle'; if (isCur()) bwUpdateHeader(); else { t.unread = true; bwPaintTab(t.id); } return; }
  if (k === 'report') { brainAdd('bw-note', `早上好。复盘建议 ${m.ideas || 0} 条${(m.learned || []).length ? '，昨晚学会：' + m.learned.join('、') : ''}。在“能力与学习”里查看。`, null, t); return; }
  if (k === 'installed') { if (!m.ok) brainAdd('bw-err', '没能装上：' + (m.error || ''), null, t); liveSend({ type: 'brain_abilities' }); return; }
}
function brainAbilities(m) {
  const ab = BW.el.querySelector('#bwAb');
  const card = (x, acts) => `<div class="bw-card"><div class="t"><b>${escapeHtml(x.name)}</b><em>${escapeHtml(x.changed || x.last || '')}</em></div><p>${escapeHtml(x.description || x.what || '')}</p>${acts ? `<div class="acts">${acts}</div>` : ''}</div>`;
  const btn = (act, name, label, go) => `<button type="button" class="${go ? 'go' : ''}" data-act="${act}" data-name="${escapeHtml(name)}">${label}</button>`;
  const learn = (m.learning || []).filter(i => ['new', 'approved', 'building', 'trial', 'stuck'].includes(i.state));
  const STATE = { new: '待你决定', approved: '已批准，今晚去学', building: '大脑正在做', trial: '做好了，在试用区', stuck: '试了两次没学会' };
  const KIND = { learn: '学新本事', habit: '改习惯', fix: '需要改程序' };
  const ideas = m.ideas || [];
  ab.innerHTML =
    (ideas.length ? `<h4>复盘建议 · 等你决定（${ideas.length}）</h4>` + ideas.map(p => card({ name: `${KIND[p.kind] || p.kind} · ${p.title}`, description: `${p.why}${p.evidence ? ' · 证据：' + p.evidence : ''}${p.plan ? ' · 打算：' + p.plan : ''}`, last: p.day },
      p.kind === 'fix' ? btn('approve_proposal', p.id, '知道了', true) + btn('reject_proposal', p.id, '忽略') : btn('approve_proposal', p.id, '同意', true) + btn('reject_proposal', p.id, '不用'))).join('') : '') +
    `<h4>试用区 · 等你批准（${m.trial.length}）</h4>` + (m.trial.map(x => card(x, btn('install', x.folder, '装上', true))).join('') || '<div class="bw-note">暂无</div>') +
    `<h4>想学的 · 学习清单（${learn.length}）</h4>` + (learn.map(i => card({ name: i.what, description: `${STATE[i.state] || i.state} · 被问过 ${i.times} 次${i.asked ? ' · “' + i.asked + '”' : ''}`, last: i.last },
      i.state === 'new' ? btn('approve_learning', i.id, '批准学习', true) + btn('reject_learning', i.id, '不用学')
        : i.state === 'approved' || i.state === 'stuck' ? btn('learn_now', i.id, '现在就学', true) + btn('reject_learning', i.id, '不学了') : '')).join('') || '<div class="bw-note">暂无</div>') +
    `<h4>已学会（${m.installed.length}）</h4>` + (m.installed.map(x => card(x, btn('rollback', x.folder, '回退上一版') + btn('remove', x.folder, '移除'))).join('') || '<div class="bw-note">还没有自己学会的本事</div>');
  ab.querySelectorAll('button[data-act]').forEach(b => b.onclick = () => {
    if (b.dataset.act === 'install') liveSend({ type: 'brain_install', name: b.dataset.name });
    else liveSend({ type: 'brain_manage', action: b.dataset.act, name: b.dataset.name });
  });
}
/* brain-window>> */
