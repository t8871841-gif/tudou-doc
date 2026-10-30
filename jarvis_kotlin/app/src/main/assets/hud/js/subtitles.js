'use strict';
/* <<paged-subtitles */
/* ---- paged subtitles with memory: English in at most two lines, Chinese in one, long replies turn their own pages
   in step with the voice; ↑ ↓ (or the wheel, or the arrows beside it) page back through the whole conversation ---- */
const SUBS = { log: [], at: -1, page: 0, live: true, pageT: 0, busy: false, browsedAt: 0, el: null };
const SUBS_KEY = 'jarvis.subtitles.v1', SUBS_KEEP = 80;
try { const saved = JSON.parse(localStorage.getItem(SUBS_KEY) || '[]'); if (Array.isArray(saved)) SUBS.log = saved.slice(-SUBS_KEEP); } catch (_) {}
function subsSave() { try { localStorage.setItem(SUBS_KEY, JSON.stringify(SUBS.log.filter(e => !e.boot).slice(-SUBS_KEEP).map(({ who, en, zh, t }) => ({ who, en, zh, t })))); } catch (_) {} }
const subsCanvas = document.createElement('canvas').getContext('2d');
function subsWidth(text, px, latin) {
  subsCanvas.font = `${px}px ${latin ? getComputedStyle(document.body).getPropertyValue('--f-latin') || 'sans-serif' : getComputedStyle(sub).fontFamily}`;
  return subsCanvas.measureText(text).width;
}
/* break a text into clauses at punctuation, keeping the mark with the clause before it */
function subsClauses(text, zh) {
  const re = zh ? /[^，。！？；：、—…]+[，。！？；：、—…]*/g : /[^,.;:!?—]+[,.;:!?—]*\s*/g;
  const out = (text.match(re) || [text]).map(s => s).filter(s => s.trim());
  /* a clause longer than a page is split again at words (English) or characters (Chinese) */
  return out;
}
function subsSplit(text, n, zh) {
  /* n pages with roughly equal length, cut only at clause ends, then words, then characters */
  if (n <= 1) return [text.trim()];
  let units = subsClauses(text, zh);
  if (units.length < n) units = zh ? [...text] : text.split(/(?<=\s)/);
  const total = units.reduce((a, u) => a + u.length, 0), pages = [];
  let cur = '', acc = 0, k = 1;
  units.forEach((u, i) => {
    const left = units.length - i, need = n - pages.length;
    if (cur && (acc + u.length / 2 > total * k / n || left < need) && pages.length < n - 1) { pages.push(cur.trim()); cur = ''; k++; }
    cur += u; acc += u.length;
  });
  if (cur.trim()) pages.push(cur.trim());
  return pages;
}
function subsPaginate(entry) {
  const W = Math.min(720, sub.clientWidth || 720) - 70;                 /* room for the speaker tag and the pager */
  const fitsEn = s => subsWidth(s, 17, true) <= W * 1.82;               /* two lines, with slack for word wrap */
  const fitsZh = s => subsWidth(s, 14, false) <= W * 0.97;              /* one line */
  const en = entry.en || '', zh = entry.zh || '';
  for (let n = 1; n <= 24; n++) {
    const a = subsSplit(en, n, false), b = zh ? subsSplit(zh, n, true) : [];
    if (a.every(fitsEn) && b.every(fitsZh)) return Array.from({ length: Math.max(a.length, b.length, 1) }, (_, i) => ({ en: a[i] || '', zh: b[i] || '' }));
  }
  return [{ en, zh }];
}
function subsRender(typed) {
  const e = SUBS.log[SUBS.at]; if (!e) { sub.innerHTML = ''; return; }
  const pages = e.pages || (e.pages = subsPaginate(e)), p = pages[SUBS.page] || pages[0];
  const tag = e.who === 'you' ? '<span class="who you">YOU</span>' : '<span class="who">JARVIS</span>';
  const enText = typed == null ? p.en : p.en.slice(0, typed);
  const caret = typed != null && typed < p.en.length ? '<span class="caret"></span>' : '';
  const back = !SUBS.live ? `<em class="sub-hist">${SUBS.at + 1}/${SUBS.log.length}</em>` : '';
  const pager = pages.length > 1 || SUBS.log.length > 1
    ? `<span class="sub-pager"><button type="button" data-d="-1" aria-label="上一页">▲</button><b>${pages.length > 1 ? `${SUBS.page + 1}/${pages.length}` : ''}</b><button type="button" data-d="1" aria-label="下一页">▼</button></span>` : '';
  sub.innerHTML = `<div class="sub-box" data-subs="1"><span class="sub-en">${tag}${escapeHtml(enText)}${caret}</span>${p.zh ? `<span class="zh">${escapeHtml(p.zh)}</span>` : ''}${back}</div>${pager}`;
  SUBS.el = sub.firstElementChild;
}
function subsOwned() { return SUBS.el && sub.contains(SUBS.el); }
function subsPlay(speed, done) {
  /* type the current page, hold it as long as it takes to say, then turn to the next one */
  clearInterval(typeTimer); clearTimeout(SUBS.pageT);
  const e = SUBS.log[SUBS.at], pages = e.pages || (e.pages = subsPaginate(e)), p = pages[SUBS.page];
  let i = 0; SUBS.busy = true; subsRender(0);
  typeTimer = setInterval(() => {
    if (!subsOwned()) { clearInterval(typeTimer); SUBS.busy = false; return; }
    i = Math.min(p.en.length, i + 1); subsRender(i);
    if (i >= p.en.length) {
      clearInterval(typeTimer);
      if (SUBS.page < pages.length - 1) {
        const words = (p.en.match(/\S+/g) || []).length, hold = e.who === 'you' ? 1600 : Math.max(1800, words / 2.6 * 1000 - p.en.length * speed);
        SUBS.pageT = setTimeout(() => { if (!SUBS.live || !subsOwned()) { SUBS.busy = false; return; } SUBS.page++; subsPlay(speed, done); }, hold);
      } else { SUBS.busy = false; done && done(); }
    }
  }, speed);
}
function typeOut(who, text, speed = 45, done, zh) {
  const entry = { who, en: String(text || ''), zh: zh ? String(zh) : '', t: Date.now() };
  const last = SUBS.log[SUBS.log.length - 1];
  if (last && last.who === who && last.en === entry.en && Date.now() - last.t < 4000) SUBS.log.pop();   /* the same line sent twice */
  if (/说 “Hey Jarvis” 就可以跟我说话/.test(entry.en)) entry.boot = true;   /* the start-up greeting is shown, not kept */
  SUBS.log.push(entry); if (SUBS.log.length > SUBS_KEEP) SUBS.log.shift();
  subsSave();
  SUBS.at = SUBS.log.length - 1; SUBS.page = 0; SUBS.live = true;
  subsPlay(speed, done);
}
/* step one page through the conversation: back past the first page goes to the previous line */
function subsStep(d) {
  if (!SUBS.log.length) return;
  clearInterval(typeTimer); clearTimeout(SUBS.pageT); SUBS.busy = false;
  if (SUBS.at < 0 || !subsOwned()) { SUBS.at = SUBS.log.length - 1; SUBS.page = 0; }
  const e = SUBS.log[SUBS.at], pages = e.pages || (e.pages = subsPaginate(e));
  if (d < 0) {
    if (SUBS.page > 0) SUBS.page--;
    else if (SUBS.at > 0) { SUBS.at--; const p = SUBS.log[SUBS.at]; SUBS.page = (p.pages || (p.pages = subsPaginate(p))).length - 1; }
  } else {
    if (SUBS.page < pages.length - 1) SUBS.page++;
    else if (SUBS.at < SUBS.log.length - 1) { SUBS.at++; SUBS.page = 0; }
  }
  const lastE = SUBS.log[SUBS.log.length - 1];
  SUBS.live = SUBS.at === SUBS.log.length - 1 && SUBS.page === (lastE.pages || (lastE.pages = subsPaginate(lastE))).length - 1;
  SUBS.browsedAt = Date.now();
  openTalk(); subsRender(); scheduleClose(12000);
}
sub.addEventListener('click', e => { const b = e.target.closest('.sub-pager button'); if (b) subsStep(Number(b.dataset.d)); });
sub.addEventListener('wheel', e => { e.preventDefault(); if (Math.abs(e.deltaY) < 4) return; const now = Date.now(); if (now - (sub._w || 0) < 160) return; sub._w = now; subsStep(e.deltaY > 0 ? 1 : -1); }, { passive: false });
addEventListener('keydown', e => {
  if (e.metaKey || e.ctrlKey || e.altKey) return;
  const typing = document.activeElement === input && input.value;
  if (typing) return;
  if (e.key === 'ArrowUp' || e.key === 'PageUp') { e.preventDefault(); subsStep(-1); }
  else if (e.key === 'ArrowDown' || e.key === 'PageDown') { e.preventDefault(); subsStep(1); }
});
/* the talk panel stays open while a reply is still turning pages, or while the user reads back */
const subsClose = scheduleClose;
scheduleClose = (ms = 6000) => {
  if (SUBS.busy || Date.now() - SUBS.browsedAt < 10000) { clearTimeout(closeT); closeT = setTimeout(() => scheduleClose(ms), 1500); return; }
  return subsClose(ms);
};
/* the voice service keeps the real record on disk (~/.jarvis/memory); it sends it when the window connects */
function subsHistory(items) {
  if (!Array.isArray(items) || !items.length) return;
  const disk = items.map(r => ({ who: r.who === 'you' ? 'you' : 'jarvis', en: r.en || '', zh: r.zh || '', t: Math.round((r.t || 0) * 1000) }));
  const last = disk[disk.length - 1].t;
  SUBS.log = [...disk, ...SUBS.log.filter(e => e.boot || e.t > last + 2000)].slice(-SUBS_KEEP);
  SUBS.at = SUBS.log.length - 1; SUBS.page = 0; SUBS.live = true;
  subsSave();
}
addEventListener('resize', () => { SUBS.log.forEach(e => { delete e.pages; }); if (subsOwned()) subsRender(); });
/* paged-subtitles>> */
