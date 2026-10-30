/* 「接入大脑」: connect a language model. Opens by itself the first time (no model saved yet), and from menu > 大脑.
   Talks to jarvis/onboard.py over the voice service's socket: llm_status / llm_models / llm_test / llm_save.
   The saved key never comes back to this page; leaving the key field empty keeps the saved one. */
(() => {
  const css = `
#llmPanel{position:fixed;inset:0;z-index:2000;display:flex;align-items:center;justify-content:center;background:rgba(var(--sk-bg),.72);backdrop-filter:blur(6px)}
#llmPanel[hidden]{display:none}
#llmPanel .box{width:min(560px,calc(100vw - 32px));max-height:calc(100vh - 48px);overflow:auto;background:rgba(var(--sk-pnl),.92);border:1px solid var(--line-2);border-radius:14px;padding:22px 24px;box-shadow:0 0 60px rgba(var(--sk-pri),.12);color:var(--ink);font-family:var(--f-cn)}
#llmPanel h2{margin:0 0 4px;font-weight:400;font-size:18px;letter-spacing:.1em}
#llmPanel h2 small{font-family:var(--f-latin);font-size:10px;letter-spacing:.3em;color:rgb(var(--sk-pri));margin-left:10px}
#llmPanel p.lead{margin:0 0 16px;color:var(--ink-2);font-size:13px;line-height:1.6}
#llmPanel .chips{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:14px}
#llmPanel .chips button{background:transparent;border:1px solid var(--line-2);color:var(--ink-2);border-radius:999px;padding:5px 12px;font:inherit;font-size:12.5px;cursor:pointer}
#llmPanel .chips button[aria-pressed=true]{border-color:rgb(var(--sk-pri));color:rgb(var(--sk-hi));background:rgba(var(--sk-pri),.12)}
#llmPanel label{display:block;font-size:12px;color:var(--ink-3);margin:10px 0 4px;letter-spacing:.05em}
#llmPanel label a{color:rgb(var(--sk-pri));margin-left:8px;text-decoration:none}
#llmPanel input{width:100%;box-sizing:border-box;background:rgba(var(--sk-bg),.6);border:1px solid var(--line-2);border-radius:8px;color:var(--ink);padding:8px 10px;font:13px var(--f-latin)}
#llmPanel input:focus{outline:none;border-color:rgb(var(--sk-pri))}
#llmPanel .row{display:flex;gap:8px;align-items:center}
#llmPanel .row input{flex:1}
#llmPanel .small{background:transparent;border:1px solid var(--line-2);color:var(--ink-2);border-radius:8px;padding:7px 10px;font:inherit;font-size:12px;cursor:pointer;white-space:nowrap}
#llmPanel .result{min-height:20px;margin:14px 0 4px;font-size:13px;line-height:1.6;color:var(--ink-2)}
#llmPanel .result .ok{color:var(--ok)} #llmPanel .result .bad{color:var(--amber)}
#llmPanel .acts{display:flex;justify-content:flex-end;gap:10px;margin-top:14px}
#llmPanel .acts button{border-radius:8px;padding:8px 18px;font:inherit;font-size:13px;cursor:pointer;border:1px solid var(--line-2);background:transparent;color:var(--ink-2)}
#llmPanel .acts .go{border-color:rgb(var(--sk-pri));color:rgb(var(--sk-hi));background:rgba(var(--sk-pri),.16)}
#llmPanel .acts button:disabled{opacity:.4;cursor:default}`;
  const st = document.createElement('style'); st.textContent = css; document.head.appendChild(st);

  const el = document.createElement('div');
  el.id = 'llmPanel'; el.hidden = true;
  el.innerHTML = `<div class="box" role="dialog" aria-labelledby="llmTitle">
  <h2 id="llmTitle">接入大脑<small>CONNECT A BRAIN</small></h2>
  <p class="lead">贾维斯听、说、显示都在本机完成，理解和决策交给你选的大模型。选一个服务商，填上密钥，测试通过就能用。密钥只保存在这台电脑上。</p>
  <div class="chips" id="llmChips"></div>
  <label>接口地址 Base URL</label><input id="llmBase" spellcheck="false" placeholder="https://.../v1">
  <label>密钥 API Key<a id="llmKeyLink" target="_blank" rel="noopener" hidden>去申请 ↗</a></label>
  <input id="llmKey" type="password" spellcheck="false" autocomplete="off">
  <label>模型 Model</label>
  <div class="row"><input id="llmModel" list="llmModels" spellcheck="false"><datalist id="llmModels"></datalist><button class="small" id="llmList" type="button">获取模型列表</button></div>
  <label>贾维斯怎么称呼你</label><input id="llmName" placeholder="sir">
  <div class="result" id="llmResult"></div>
  <div class="acts"><button type="button" id="llmLater">稍后</button><button type="button" id="llmTest">测试连接</button><button type="button" class="go" id="llmSave" disabled>保存</button></div>
</div>`;
  document.body.appendChild(el);
  const $ = s => el.querySelector(s);
  const S = { presets: [], provider: '', hasKey: false, tested: false, asked: false };
  const send = o => (window.liveSend ? window.liveSend(o) : false);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const form = () => ({ provider: S.provider, base_url: $('#llmBase').value.trim(), api_key: $('#llmKey').value.trim(), model: $('#llmModel').value.trim() });

  function pick(id, keepFields) {
    const p = S.presets.find(x => x.id === id) || S.presets[S.presets.length - 1];
    if (!p) return;
    S.provider = p.id; S.tested = false; $('#llmSave').disabled = true;
    el.querySelectorAll('#llmChips button').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.id === p.id)));
    if (!keepFields) { $('#llmBase').value = p.base_url; $('#llmModel').value = p.model; $('#llmKey').value = ''; }
    const link = $('#llmKeyLink'); link.hidden = !p.key_url; link.href = p.key_url || '#';
    link.textContent = p.id === 'ollama' ? '下载 Ollama ↗' : '去申请 ↗';
    $('#llmKey').placeholder = !p.key ? '本地模型不需要密钥' : S.hasKey ? '已保存，留空则不修改' : '粘贴密钥';
    $('#llmResult').textContent = '';
  }

  function status(m) {
    S.presets = m.presets || S.presets; S.hasKey = !!m.has_key;
    $('#llmChips').innerHTML = S.presets.map(p => `<button type="button" data-id="${esc(p.id)}">${esc(p.name)}</button>`).join('');
    el.querySelectorAll('#llmChips button').forEach(b => b.onclick = () => pick(b.dataset.id));
    $('#llmName').value = m.name && m.name !== 'sir' ? m.name : '';
    if (m.configured) { pick(m.provider || 'custom', true); $('#llmBase').value = m.base_url; $('#llmModel').value = m.model; }
    else pick('deepseek');
    if (!m.configured && !S.asked) { S.asked = true; open(); }
  }

  function result(m) {
    $('#llmTest').disabled = false;
    if (m.kind === 'test') {
      if (!m.ok) { $('#llmResult').innerHTML = `<span class="bad">✕ ${esc(m.error)}</span>`; return; }
      S.tested = true; $('#llmSave').disabled = false;
      $('#llmResult').innerHTML = `<span class="ok">✓ 对话正常</span>（${(m.ms / 1000).toFixed(1)} 秒）　` +
        (m.tools ? '<span class="ok">✓ 工具调用正常</span>' : `<span class="bad">△ 不支持工具调用</span><br>${esc(m.warning || '')}`);
    } else if (m.kind === 'models') {
      if (!m.ok) { $('#llmResult').innerHTML = `<span class="bad">✕ ${esc(m.error)}</span>`; return; }
      $('#llmModels').innerHTML = m.models.map(x => `<option value="${esc(x)}">`).join('');
      $('#llmResult').textContent = `找到 ${m.models.length} 个模型，点模型框选择。`;
    } else if (m.kind === 'saved') {
      S.hasKey = !!m.has_key;
      $('#llmResult').innerHTML = '<span class="ok">✓ 已保存。说 “Hey Jarvis” 试试吧。</span>';
      setTimeout(close, 1200);
    } else if (m.kind === 'status') status(m);
    else if (m.kind === 'error') $('#llmResult').innerHTML = `<span class="bad">✕ ${esc(m.error)}</span>`;
  }

  function open() { el.hidden = false; setTimeout(() => $('#llmKey').focus(), 50); }
  function close() { el.hidden = true; }

  ['#llmBase', '#llmKey', '#llmModel'].forEach(s => $(s).addEventListener('input', () => { S.tested = false; $('#llmSave').disabled = true; }));
  $('#llmTest').onclick = () => {
    if (!send({ type: 'llm_test', ...form() })) { $('#llmResult').innerHTML = '<span class="bad">✕ 语音服务还没启动，稍等几秒再试。</span>'; return; }
    $('#llmTest').disabled = true; $('#llmResult').textContent = '正在测试：先问一句话，再试一次工具调用…';
  };
  $('#llmList').onclick = () => { send({ type: 'llm_models', base_url: form().base_url, api_key: form().api_key }); $('#llmResult').textContent = '正在获取模型列表…'; };
  $('#llmSave').onclick = () => send({ type: 'llm_save', ...form(), name: $('#llmName').value.trim() });
  $('#llmLater').onclick = close;
  el.addEventListener('keydown', e => { if (e.key === 'Escape') { e.stopPropagation(); close(); } });

  /* menu > 大脑 */
  const pop = document.querySelector('#menuPop');
  if (pop) {
    const b = document.createElement('button'); b.type = 'button'; b.innerHTML = '大脑<b>模型</b>';
    b.onclick = e => { e.stopPropagation(); pop.hidden = true; send({ type: 'llm_status' }); open(); };
    pop.appendChild(b);
  }
  window.ONBOARD = { msg: result, open, close };
})();
