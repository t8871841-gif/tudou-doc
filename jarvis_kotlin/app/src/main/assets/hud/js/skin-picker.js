'use strict';

function applySkin(id, now) {
  const sk = SKINS.find(k => k.id === id) || SKINS[0];
  SK.id = sk.id; document.body.dataset.skin = sk.id;
  try { localStorage.setItem('jarvis.skin', sk.id); } catch (_) {}
  const root = document.documentElement.style;
  for (const [r, v] of Object.entries(sk.roles)) { root.setProperty('--sk-' + r, v); SKC[r] = v; }
  skinBgBuild(sk.bg);
  skCoreSet(sk.coreKind || null);
  /* three.js colours: ease the core over ~1.2 s so a switch feels like the core changing mood, not a cut */
  const from = { w: U.uSkW.value.toArray(), c: U.uSkC.value.toArray(), n: U.uSkN.value.toArray(), rib: U.uC.value.map(v => v.toArray()), a: U.uA.value.map(v => v.toArray()) };
  const to = { w: sk.core.warm, c: sk.core.cool, n: sk.core.neb, rib: SK_RIB0.map(c => skMap(c, sk)), a: SK_A0.map(a => [a[0], a[1] * (sk.core.flat ? 0.5 : 1), a[2] * (sk.core.flat || 1), a[3]]) };
  const t0 = performance.now(), dur = now ? 1 : 1200;
  cancelAnimationFrame(SK.anim);
  const lerp = (a, b, k) => a.map((v, i) => v + (b[i] - v) * k);
  const step = () => {
    const k = Math.min(1, (performance.now() - t0) / dur), e = k * k * (3 - 2 * k);
    U.uSkW.value.fromArray(lerp(from.w, to.w, e)); U.uSkC.value.fromArray(lerp(from.c, to.c, e)); U.uSkN.value.fromArray(lerp(from.n, to.n, e));
    U.uC.value.forEach((v, i) => v.fromArray(lerp(from.rib[i], to.rib[i], e)));
    U.uA.value.forEach((v, i) => v.fromArray(lerp(from.a[i], to.a[i], e)));
    if (k < 1) SK.anim = requestAnimationFrame(step);
  };
  step();
  /* plain three.js materials (orbits, their nodes, rain): remember the old colour once, re-tint from it */
  const tint = (mat) => { if (!mat || !mat.color) return; if (!SK.orig.has(mat)) SK.orig.set(mat, mat.color.toArray()); mat.color.fromArray(skMap(SK.orig.get(mat), sk)); };
  orbits.traverse(o => { if (o.material) tint(o.material); });
  tint(rainM); tint(rpM);
  /* agent rows bake their colour in at build time */
  try { AGENTS.forEach(a => { if (!a.el) return; a.el.style.setProperty('--c', a.c); const r = a.el.querySelector('.ring'); if (r) r.style.color = a.c; }); } catch (_) {}
  skinPickSync();
}
/* skins whose centre is still the embedded original stay out of the rotation and picker until they are rewritten */
const SK_DONE = ['forge', 'neural', 'nebula', 'ion', 'works', 'factory', 'spider', 'ops'];
const skReady = sk => !sk.coreKind || SK_DONE.includes(sk.coreKind);
function skinNext(dir = 1) { const L = SKINS.filter(skReady); const i = L.findIndex(k => k.id === SK.id); const sk = L[(i + dir + L.length) % L.length]; applySkin(sk.id); skinToast(sk); }
function skinToast(sk) { let el = $('#skinToast'); if (!el) { el = Object.assign(document.createElement('div'), { id: 'skinToast' }); document.body.appendChild(el); }
  el.textContent = `皮肤 · ${sk.name}  ${sk.en}`; el.classList.add('on'); clearTimeout(el._t); el._t = setTimeout(() => el.classList.remove('on'), 1600); }
function skinCommand(m) {
  if (m.id === 'next') return skinNext(1);
  const sk = SKINS.find(k => k.id === m.id || k.name === m.id);
  if (sk && skReady(sk)) { applySkin(sk.id); skinToast(sk); }
}
/* picker: menu > 皮肤 */
const skinPick = Object.assign(document.createElement('div'), { id: 'skinPick', hidden: true });
skinPick.innerHTML = '<div class="hd"><span>皮肤</span><small>按 T 键轮换 · 选择会被记住</small></div>' + SKINS.filter(skReady).map(sk => {
  const r = sk.roles;
  return `<button type="button" data-skin="${sk.id}" aria-pressed="false"><span class="sw"><i style="background:rgb(${r.bg})"></i><i style="background:rgb(${r.pri})"></i><i style="background:rgb(${r.acc})"></i><i style="background:rgb(${r.ink0})"></i></span><b>${sk.name}</b><em>${sk.en}</em></button>`;
}).join('');
document.body.appendChild(skinPick);
function skinPickSync() { skinPick.querySelectorAll('button').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.skin === SK.id))); const mb = $('#btnSkin b'); if (mb) mb.textContent = (SKINS.find(k => k.id === SK.id) || SKINS[0]).name; }
skinPick.addEventListener('click', e => { const b = e.target.closest('button[data-skin]'); if (b) { applySkin(b.dataset.skin); skinPickSync(); } });
{
  const btn = Object.assign(document.createElement('button'), { type: 'button', id: 'btnSkin' });
  btn.innerHTML = '皮肤<b>默认</b>';
  $('#menuPop').appendChild(btn);
  btn.addEventListener('click', e => { e.stopPropagation(); skinPick.hidden = !skinPick.hidden; $('#menuPop').hidden = true; });
  document.addEventListener('click', e => { if (!skinPick.hidden && !e.target.closest('#skinPick') && !e.target.closest('#btnSkin')) skinPick.hidden = true; });
}
addEventListener('keydown', e => {
  if (e.metaKey || e.ctrlKey || e.altKey || e.repeat) return;
  const a = document.activeElement; if (a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA' || a.isContentEditable)) return;
  if (e.key === 't' || e.key === 'T') skinNext(e.shiftKey ? -1 : 1);
  else if (e.key === 'Escape') skinPick.hidden = true;
});
window.applySkin = applySkin;
{ let id = 'jarvis'; try { id = localStorage.getItem('jarvis.skin') || 'jarvis'; } catch (_) {} if (!skReady(SKINS.find(k => k.id === id) || SKINS[0])) id = 'jarvis'; setTimeout(() => applySkin(id, true), 0); }
/* skins>> */
