'use strict';
/* <<voiceprint */
/* ---- voiceprint enrolment: six lines on screen, each lights up when it has been heard ---- */
const ENR = { el: null, lines: [], got: 0 };
function enrollUpdate(m) {
  if (!ENR.el) {
    const css = document.createElement('style');
    css.textContent = `#enrollCard{position:fixed;left:50%;top:46%;transform:translate(-50%,-50%) scale(.97);z-index:65;width:min(640px,90vw);padding:22px 26px 18px;
      border:1px solid var(--line-2);border-radius:14px;background:rgba(var(--sk-bg),.88);backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);opacity:0;pointer-events:none;transition:opacity .4s,transform .4s}
      #enrollCard.on{opacity:1;transform:translate(-50%,-50%);pointer-events:auto}
      #enrollCard h3{margin:0 0 4px;font-weight:500;font-size:15px;letter-spacing:1px}#enrollCard p.s{margin:0 0 14px;color:var(--ink-3);font-size:12.5px}
      #enrollCard ol{margin:0;padding:0;list-style:none;display:flex;flex-direction:column;gap:8px}
      #enrollCard li{padding:9px 12px;border:1px solid var(--line);border-radius:9px;color:var(--ink-3);font-size:15px;transition:all .3s;display:flex;gap:10px;align-items:center}
      #enrollCard li i{font-style:normal;font-family:var(--f-latin);font-size:11px;width:16px;color:var(--ink-3)}
      #enrollCard li.en-now{color:var(--ink);border-color:var(--cyan);box-shadow:0 0 18px rgba(var(--sk-pri),.18);font-size:17px}
      #enrollCard li.en-done{color:var(--ok);border-color:rgba(111,227,184,.35)}#enrollCard li.en-done i{color:var(--ok)}
      #enrollCard .f{margin-top:12px;font-size:12px;color:var(--ink-3);display:flex;justify-content:space-between}`;
    document.head.appendChild(css);
    ENR.el = document.createElement('div'); ENR.el.id = 'enrollCard'; document.body.appendChild(ENR.el);
  }
  const el = ENR.el;
  if (m.start) {
    ENR.lines = m.lines || []; ENR.got = 0;
    try { dormHold = false; wakeUI(); } catch (_) {}
    el.innerHTML = `<h3>录入声纹</h3><p class="s">请用平时说话的音量，一句一句读出亮着的那一行。读完一句停一下，它会自动跳到下一句。</p><ol>${ENR.lines.map((l, i) => `<li><i>${i + 1}</i>${escapeHtml(l)}</li>`).join('')}</ol><div class="f"><span id="enrollMsg">在听…</span><span id="enrollCount">0 / ${ENR.lines.length}</span></div>`;
    el.classList.add('on');
  }
  if (!el.classList.contains('on')) return;
  if (m.got != null) ENR.got = m.got;
  if (m.done) ENR.got = ENR.lines.length;
  el.querySelectorAll('li').forEach((li, i) => { li.classList.toggle('en-done', i < ENR.got); li.classList.toggle('en-now', i === ENR.got); });
  const msg = el.querySelector('#enrollMsg'), cnt = el.querySelector('#enrollCount');
  if (cnt) cnt.textContent = `${ENR.got} / ${ENR.lines.length}`;
  if (m.short && msg) msg.textContent = '这句太短了，请把整句读完';
  else if (m.ok && msg && !m.done) msg.textContent = '好的，下一句';
  if (m.done) { if (msg) msg.textContent = '完成：以后贾维斯只回应你的声音'; setTimeout(() => el.classList.remove('on'), 4000); }
  if (m.error) { if (msg) msg.textContent = m.error === 'timeout' ? '太久没听到，先停下了。随时可以再说“录入声纹”。' : '没录成：' + m.error; setTimeout(() => el.classList.remove('on'), 5000); }
}
function notYou(m) {
  try { meterHeard.textContent = '不是你的声音，已忽略'; } catch (_) {}
}
/* voiceprint>> */
