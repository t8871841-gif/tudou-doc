'use strict';
/* <<skins: 换肤 : 8 looks in the visual language of the 'Agent 战术大屏' project
   (github.com/CYBERDADDYLEO/agent-display): only the look is borrowed, all code here is our own.
   A skin = colour roles (CSS variables --sk-*, also SKC for canvas code) + core colours (uniforms uSkW/uSkC/uSkN, ribbon
   colours) + a few CSS rules under body[data-skin] + an optional background layer. Layout, voice and features never change.
   Switch: menu > 皮肤, the T key, or the voice service sending {type:'skin', id|'next'}. The choice is remembered. */
const SKIN_FONTS = document.createElement('style');
SKIN_FONTS.textContent = `
@font-face{font-family:'Michroma';font-weight:400;src:url(vendor/fonts/michroma-latin-400-normal.woff2) format('woff2')}
@font-face{font-family:'Syncopate';font-weight:400;src:url(vendor/fonts/syncopate-latin-400-normal.woff2) format('woff2')}
@font-face{font-family:'Syncopate';font-weight:700;src:url(vendor/fonts/syncopate-latin-700-normal.woff2) format('woff2')}
@font-face{font-family:'JetBrains Mono';font-weight:400;src:url(vendor/fonts/jetbrains-mono-latin-400-normal.woff2) format('woff2')}
@font-face{font-family:'JetBrains Mono';font-weight:500;src:url(vendor/fonts/jetbrains-mono-latin-500-normal.woff2) format('woff2')}
#skinBg{position:fixed;inset:0;pointer-events:none;overflow:hidden;transition:opacity .8s}
body.demo #skinBg{opacity:0}
#gaugeArc{stroke:rgb(var(--sk-pri))}
#lk stop{stop-color:rgb(var(--sk-pri))}
#skinBg > *{position:absolute;inset:0}
#skinPick{position:fixed;right:max(24px,2vw);top:62px;z-index:40;width:min(560px,calc(100vw - 48px));padding:14px;border:1px solid var(--line-2);
  background:rgba(var(--sk-pnl),.94);backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);display:grid;grid-template-columns:repeat(3,1fr);gap:10px}
#skinPick[hidden]{display:none}
#skinPick .hd{grid-column:1/-1;display:flex;justify-content:space-between;align-items:baseline;font-size:13px;color:var(--ink-2);letter-spacing:1px}
#skinPick .hd small{font-size:11px;color:var(--ink-3)}
#skinPick button{position:relative;display:flex;flex-direction:column;gap:6px;align-items:flex-start;padding:10px 10px 9px;border:1px solid var(--line);background:rgba(255,255,255,.02);
  color:var(--ink);cursor:pointer;text-align:left;transition:border-color .2s,background .2s}
#skinPick button:hover{border-color:var(--line-2);background:rgba(255,255,255,.05)}
#skinPick button[aria-pressed="true"]{border-color:rgb(var(--sk-pri));box-shadow:0 0 14px rgba(var(--sk-pri),.25) inset}
#skinPick .sw{display:flex;gap:3px;height:22px;width:100%}
#skinPick .sw i{flex:1}
#skinPick b{font-weight:400;font-size:13.5px;letter-spacing:1px}
#skinPick em{font-style:normal;font-size:10.5px;color:var(--ink-3);letter-spacing:.5px}
#skinToast{position:fixed;left:50%;top:84px;transform:translateX(-50%);z-index:60;padding:7px 18px;border:1px solid rgba(var(--sk-pri),.45);background:rgba(var(--sk-pnl),.8);
  color:var(--ink);font-size:13px;letter-spacing:3px;opacity:0;transition:opacity .35s;pointer-events:none}
#skinToast.on{opacity:1}

/* ---------- 星环 · after NEURAL: an energy-cell HUD ring around the core, segmented headers, wide display face ---------- */
body[data-skin="ring"] .brand b,body[data-skin="ring"] .clock b{font-family:'Michroma',var(--f-latin);letter-spacing:3px}
body[data-skin="ring"] .brand b{font-size:14px}
body[data-skin="ring"] .clock b{font-size:21px;letter-spacing:1.5px}
body[data-skin="ring"] .panel h2{border-left:0;padding-left:16px;letter-spacing:2px}
body[data-skin="ring"] .panel h2::before{content:"";position:absolute;left:0;top:1px;bottom:1px;width:4px;
  background:repeating-linear-gradient(180deg,rgb(var(--sk-pri)) 0 3px,transparent 3px 5px);box-shadow:0 0 8px rgba(var(--sk-pri),.6)}
body[data-skin="ring"] .panel h2::after{background:linear-gradient(90deg,rgba(var(--sk-pri),.55),rgba(var(--sk-pri),.12) 45%,transparent)}
.sk-ring svg{position:absolute;left:50%;top:50%;width:min(96vh,62vw);height:min(96vh,62vw);transform:translate(-50%,-50%)}
.sk-ring .rot{transform-origin:500px 500px;animation:skspin 240s linear infinite}
.sk-ring .rot2{transform-origin:500px 500px;animation:skspin 160s linear infinite reverse}
@keyframes skspin{to{transform:rotate(360deg)}}
body.dormant .sk-ring{opacity:.55}

/* ---------- 熔炉 · after FORGE: an event horizon in the core, molten-gold corner brackets, Syncopate ---------- */
body[data-skin="forge"] .brand b,body[data-skin="forge"] .clock b{font-family:'Syncopate',var(--f-latin);font-weight:700;letter-spacing:4px}
body[data-skin="forge"] .brand b{font-size:13px}
body[data-skin="forge"] .clock b{font-size:20px;letter-spacing:2px}
body[data-skin="forge"] .panel h2{border-left-color:rgb(var(--sk-acc));letter-spacing:2px}
body[data-skin="forge"] .panel::before{content:"";position:absolute;inset:-8px -10px -4px -10px;pointer-events:none;opacity:.6;
  background:linear-gradient(rgb(var(--sk-acc)),rgb(var(--sk-acc))) 0 0/12px 1px no-repeat,linear-gradient(rgb(var(--sk-acc)),rgb(var(--sk-acc))) 0 0/1px 12px no-repeat,
  linear-gradient(rgb(var(--sk-pri)),rgb(var(--sk-pri))) 100% 0/12px 1px no-repeat,linear-gradient(rgb(var(--sk-pri)),rgb(var(--sk-pri))) 100% 0/1px 12px no-repeat,
  linear-gradient(rgb(var(--sk-pri)),rgb(var(--sk-pri))) 0 100%/12px 1px no-repeat,linear-gradient(rgb(var(--sk-pri)),rgb(var(--sk-pri))) 0 100%/1px 12px no-repeat,
  linear-gradient(rgb(var(--sk-acc)),rgb(var(--sk-acc))) 100% 100%/12px 1px no-repeat,linear-gradient(rgb(var(--sk-acc)),rgb(var(--sk-acc))) 100% 100%/1px 12px no-repeat}
body[data-skin="forge"] .panel h2::after{background:linear-gradient(90deg,rgba(var(--sk-acc),.5),rgba(var(--sk-pri),.15) 50%,transparent)}
.sk-forge .hz{position:absolute;left:50%;top:50%;width:25vh;height:25vh;transform:translate(-50%,-50%);border-radius:50%;
  background:radial-gradient(circle,#000 0 52%,rgba(0,0,0,.92) 60%,rgba(0,0,0,0) 71%);
  box-shadow:0 0 0 1px rgba(var(--sk-acc),.35),0 0 24px 4px rgba(var(--sk-acc),.28),0 0 90px 20px rgba(var(--sk-acch),.12)}
.sk-forge .hz::after{content:"";position:absolute;inset:-7%;border-radius:50%;border:1.5px solid rgba(var(--sk-accp),.55);filter:blur(1.2px);
  box-shadow:0 0 18px rgba(var(--sk-acc),.55),inset 0 0 18px rgba(var(--sk-acc),.35);animation:skbreath 6s ease-in-out infinite}
.sk-forge .lens{position:absolute;left:50%;top:50%;width:58vh;height:14vh;transform:translate(-50%,-50%) rotate(-8deg);border-radius:50%;
  background:radial-gradient(ellipse,rgba(var(--sk-acc),.10),rgba(var(--sk-acch),.05) 45%,transparent 70%);filter:blur(4px)}
@keyframes skbreath{50%{transform:scale(1.035);opacity:.8}}
body.dormant .sk-forge .lens{opacity:.6}

/* ---------- 回路 · after ION: a circuit floor under the core with ions racing along it, pad-and-trace headers ---------- */
body[data-skin="ion"] .brand b,body[data-skin="ion"] .clock b{font-family:'Syncopate',var(--f-latin);font-weight:700;letter-spacing:3px}
body[data-skin="ion"] .brand b{font-size:13px}
body[data-skin="ion"] .clock b{font-size:20px;letter-spacing:2px}
body[data-skin="ion"] .panel h2{border-left:0;padding-left:20px;letter-spacing:2px}
body[data-skin="ion"] .panel h2::before{content:"";position:absolute;left:2px;top:50%;width:8px;height:8px;margin-top:-4px;border-radius:50%;
  border:1.5px solid rgb(var(--sk-acc));box-shadow:0 0 8px rgba(var(--sk-acc),.7)}
body[data-skin="ion"] .panel h2::after{left:6px;background:linear-gradient(90deg,rgba(var(--sk-acc),.7),rgba(var(--sk-pri),.35) 40%,transparent)}
.sk-ion .floor{position:absolute;left:50%;bottom:-6vh;width:150vw;height:62vh;transform:translateX(-50%) perspective(70vh) rotateX(64deg);transform-origin:50% 100%;
  -webkit-mask:linear-gradient(0deg,#000 30%,transparent);mask:linear-gradient(0deg,#000 30%,transparent)}
.sk-ion .floor svg{width:100%;height:100%}
.sk-ion .ion{animation:skion linear infinite}
@keyframes skion{from{stroke-dashoffset:0}to{stroke-dashoffset:-1000}}
body.dormant .sk-ion{opacity:.5}
`;
document.head.appendChild(SKIN_FONTS);
const skinBg = Object.assign(document.createElement('div'), { id: 'skinBg' });
skinBg.setAttribute('aria-hidden', 'true');
$('#stage').after(skinBg);

/* colour roles as r,g,b. pri = main hue, line = hairlines, soft = mid tone, hi = near-white tint, deep = haze, ink = light text tint,
   acc = second hue, accl = its light tone, acch = its hot tone, accp = its pale tone, bg / pnl / wbg = backgrounds,
   ink0..3 = text levels. core: warm / cool = the two families of particle colours, neb = nebula tint */
const SKINS = [
  { id: 'jarvis', name: '默认', en: 'J.A.R.V.I.S',
    roles: { pri: '127,219,255', line: '140,200,225', soft: '159,221,245', hi: '228,248,255', deep: '60,140,200', ink: '220,239,247',
      acc: '255,174,107', accl: '255,194,122', acch: '255,140,60', accp: '255,227,200', bg: '2,5,9', pnl: '4,12,18', wbg: '22,12,4',
      ink0: '232,245,250', ink2: '163,192,204', ink3: '94,127,141' },
    core: { warm: [1.0, 0.58, 0.30], cool: [0.55, 0.82, 1.0], neb: [1, 1, 1] } },
  { id: 'ring', name: '星环', en: 'NEURAL RING',
    roles: { pri: '166,241,255', line: '150,215,235', soft: '170,225,240', hi: '232,252,255', deep: '50,150,190', ink: '214,236,244',
      acc: '255,181,71', accl: '255,205,120', acch: '255,150,40', accp: '255,232,190', bg: '3,5,10', pnl: '4,9,16', wbg: '20,13,3',
      ink0: '228,234,242', ink2: '169,180,195', ink3: '107,118,133' },
    core: { warm: [1.0, 0.70, 0.28], cool: [0.62, 0.94, 1.0], neb: [0.7, 1.05, 1.25] }, coreKind: 'neural' },
  { id: 'forge', name: '熔炉', en: 'EVENT HORIZON',
    roles: { pri: '156,208,255', line: '150,180,210', soft: '170,195,220', hi: '228,240,255', deep: '70,110,170', ink: '216,224,234',
      acc: '255,173,94', accl: '255,200,130', acch: '255,120,40', accp: '255,226,190', bg: '5,7,10', pnl: '8,11,16', wbg: '24,12,4',
      ink0: '216,224,234', ink2: '151,163,178', ink3: '91,102,116' },
    core: { warm: [1.0, 0.60, 0.22], cool: [0.62, 0.80, 1.0], neb: [1.25, 0.85, 0.7] }, coreKind: 'forge' },
  { id: 'ion', name: '回路', en: 'ION CIRCUIT',
    roles: { pri: '255,96,56', line: '200,120,110', soft: '235,160,140', hi: '255,236,214', deep: '160,40,30', ink: '240,226,222',
      acc: '255,210,63', accl: '255,225,120', acch: '255,61,31', accp: '255,240,200', bg: '7,6,11', pnl: '11,9,17', wbg: '24,14,4',
      ink0: '236,232,246', ink2: '164,159,182', ink3: '105,100,124' },
    core: { warm: [1.0, 0.80, 0.24], cool: [1.0, 0.30, 0.14], neb: [1.5, 0.55, 0.6] }, coreKind: 'ion' },
  skMake('blueprint', '图纸', 'PAPER OPS', '226,224,216', '255,255,255', '8,8,9', 'ops'),
  skMake('warroom', '作战室', 'SPIDER WAR ROOM', '236,79,174', '69,230,245', '7,8,13', 'spider'),
  skMake('workshop', '工坊', 'ROBOT FACTORY', '255,194,26', '69,230,245', '7,9,13', 'factory'),
  skMake('nebula', '星云', 'NEBULA SPIDER', '159,212,255', '255,180,143', '3,5,12', 'nebula'),
  skMake('weave', '织造', 'HEAVENLY LOOM', '156,208,255', '255,185,138', '4,6,10', 'works')
];
/* a full role set from a main hue, a second hue and a background (for the skins whose look is mostly their own core) */
function skMake(id, name, en, pri, acc, bg, coreKind) {
  const P = pri.split(',').map(Number), A = acc.split(',').map(Number), G = bg.split(',').map(Number);
  const mix = (a, b, k) => a.map((v, i) => Math.round(v + (b[i] - v) * k)).join(',');
  const W = [255, 255, 255];
  return { id, name, en, coreKind,
    roles: { pri, line: mix(P, [150, 170, 185], 0.4), soft: mix(P, W, 0.3), hi: mix(P, W, 0.85), deep: mix(P, G, 0.5), ink: mix(P, W, 0.8),
      acc, accl: mix(A, W, 0.3), acch: mix(A, [255, 60, 20], 0.3), accp: mix(A, W, 0.75), bg, pnl: mix(G, P, 0.03), wbg: mix(G, A, 0.08),
      ink0: '232,236,242', ink2: '165,174,188', ink3: '100,108,122' },
    core: { warm: A.map(v => v / 255), cool: P.map(v => v / 255), neb: [1, 1, 1] } };
}
const SK_W0 = [1.0, 0.58, 0.30], SK_C0 = [0.55, 0.82, 1.0];
const SK_RIB0 = RIB.map(r => r[2].slice()), SK_A0 = RIB.map(r => r[0].slice());
const SK = { id: '', anim: null, orig: new Map(), core: null };
const skRole = (rgb) => rgb.split(',').map(v => +v / 255);
/* one colour of the old look, re-tinted into the new skin's family (keeps its own light/dark variation) */
function skMap(c, sk) {
  const warm = c[0] > c[2], base = warm ? SK_W0 : SK_C0, to = warm ? sk.core.warm : sk.core.cool;
  return c.map((v, k) => Math.min(1, v / Math.max(0.05, base[k]) * to[k]));
}
function skinBgBuild(kind) {
  skinBg.innerHTML = '';
  if (kind === 'ring') {
    /* 72 sectors x 6 tracks of energy cells, a tick ring and a text band, two counter-rotating layers */
    let cells = '';
    for (let s = 0; s < 72; s++) for (let t = 0; t < 6; t++) {
      const r0 = 405 + t * 13, r1 = r0 + 9, a0 = (s * 5 + 0.6) * Math.PI / 180, a1 = (s * 5 + 4.2) * Math.PI / 180;
      const p = (r, a) => `${(500 + r * Math.cos(a)).toFixed(1)} ${(500 + r * Math.sin(a)).toFixed(1)}`;
      cells += `<path data-s="${s}" data-t="${t}" d="M${p(r0, a0)}A${r0} ${r0} 0 0 1 ${p(r0, a1)}L${p(r1, a1)}A${r1} ${r1} 0 0 0 ${p(r1, a0)}Z"/>`;
    }
    let ticks = '';
    for (let k = 0; k < 180; k++) { const a = k * 2 * Math.PI / 180, r0 = k % 15 ? 390 : 382; ticks += `<line x1="${500 + r0 * Math.cos(a)}" y1="${500 + r0 * Math.sin(a)}" x2="${500 + 396 * Math.cos(a)}" y2="${500 + 396 * Math.sin(a)}"/>`; }
    skinBg.innerHTML = `<div class="sk-ring"><svg viewBox="0 0 1000 1000">
      <defs><path id="skTxt" d="M500 500m-488 0a488 488 0 1 1 976 0a488 488 0 1 1 -976 0"/></defs>
      <g class="rot"><g class="cells" fill="rgba(${SKC.pri},.07)" stroke="none">${cells}</g></g>
      <g class="rot2" stroke="rgba(${SKC.pri},.35)" stroke-width="1">${ticks}<circle cx="500" cy="500" r="399" fill="none" stroke="rgba(${SKC.pri},.18)"/>
        <text font-family="Michroma, sans-serif" font-size="11" letter-spacing="6" fill="rgba(${SKC.pri},.45)" stroke="none"><textPath href="#skTxt">J.A.R.V.I.S · NEURAL LINK · CORE SYNC · 72 × 6 · TOKEN FLOW · SYNAPSE BUS · HUD RING · STANDING BY · </textPath></text></g>
      <circle cx="500" cy="500" r="486" fill="none" stroke="rgba(${SKC.acc},.25)" stroke-dasharray="2 10"/></svg></div>`;
    const paths = [...skinBg.querySelectorAll('.cells path')], lvl = new Float32Array(72).fill(0.2);
    let sweep = 0;
    SK.tick = () => {                                   /* cells fill like a level meter per sector, a bright sweep runs around */
      sweep = (sweep + 1) % 72;
      const busy = /thinking|speaking|listening/.test(document.body.className) ? 0.35 : 0;
      for (let s = 0; s < 72; s++) lvl[s] += ((Math.random() * (0.55 + busy) + 0.1) - lvl[s]) * 0.25;
      paths.forEach(el => {
        const s = +el.dataset.s, t = +el.dataset.t, on = t / 6 < lvl[s], hot = Math.abs(((s - sweep + 72) % 72)) < 3;
        el.setAttribute('fill', hot && on ? `rgba(${SKC.acc},.6)` : on ? `rgba(${SKC.pri},${(0.09 + 0.22 * (1 - t / 6)).toFixed(2)})` : `rgba(${SKC.pri},.05)`);
      });
    };
  } else if (kind === 'forge') {
    skinBg.innerHTML = '';
    SK.tick = null;
  } else if (kind === 'ion') {
    /* a circuit board seen at a slant: traces on a 40px grid with pads, ions racing along a few of them */
    let tr = '', ions = '', pads = '';
    const rnd = (() => { let x = 7; return () => (x = (x * 16807) % 2147483647) / 2147483647; })();
    for (let k = 0; k < 46; k++) {
      let x = Math.round(rnd() * 50) * 40, y = 1000, d = `M${x} ${y}`;
      for (let j = 0; j < 6; j++) { y -= 80 + Math.round(rnd() * 4) * 40; d += `L${x} ${y}`; if (rnd() < 0.6) { x += (rnd() < 0.5 ? -1 : 1) * 40 * (1 + Math.floor(rnd() * 3)); d += `L${x} ${y - 40}`; y -= 40; } }
      tr += `<path d="${d}"/>`; pads += `<circle cx="${x}" cy="${y}" r="5"/>`;
      if (k % 4 === 0) ions += `<path class="ion" d="${d}" stroke="rgba(${k % 8 ? SKC.acc : SKC.hi},.95)" stroke-width="3" stroke-dasharray="14 986" style="animation-duration:${(3 + rnd() * 5).toFixed(1)}s;animation-delay:-${(rnd() * 5).toFixed(1)}s"/>`;
    }
    skinBg.innerHTML = `<div class="sk-ion"><div class="floor"><svg viewBox="0 0 2000 1000" preserveAspectRatio="none">
      <g fill="none" stroke="rgba(${SKC.pri},.4)" stroke-width="2">${tr}</g><g fill="none" stroke="rgba(${SKC.acc},.6)" stroke-width="2">${pads}</g>
      <g fill="none" stroke-linecap="round" style="filter:drop-shadow(0 0 6px rgba(${SKC.acc},.9))">${ions}</g></svg></div></div>`;
    SK.tick = null;
  } else SK.tick = null;
}
setInterval(() => { if (SK.tick && !document.hidden && !document.body.classList.contains('demo')) SK.tick(); }, 450);

/* ---- skin cores: a skin can replace the silk core with its own centrepiece. The silk core (fibers + sparkles) is hidden
   while one is active; holograms still dim it through U.uGain, standby through U.uDorm. ---- */
const SKCORES = {};
function skCoreSet(kind) {
  if (SK.core && SK.core.kind === kind) return;
  if (SK.core) { SK.core.dispose(); SK.core = null; }
  if (kind && SKCORES[kind]) SK.core = Object.assign(SKCORES[kind](), { kind });
  fibers.visible = core.visible = !SK.core;
  if (!SK.core) { orbits.visible = true; labelBox.style.opacity = ''; }
}
/* 熔炉 · Jarvis core (Claude, 2026-10-28). Rewritten for the Jarvis scene after studying the FORGE theme: a black hole
   wrapped in fourteen particle ribbons, dust on tilted orbits, a slowly turning tick ring.
   idle = ribbons swirl round the hole and breathe; listening = brighter, cooler, ribbons draw in a little;
   thinking = the ribbons collapse into one flat glowing ring; speaking = ribbons shimmer with the voice and a shock ring
   leaves the horizon on each strong syllable. Everything eases, nothing jumps. */
SKCORES.forge = function () {
  const TAU = Math.PI * 2, SC = 0.95;                                 /* orb units -> world units */
  const grp = new THREE.Group(); scene.add(grp);
  const orb = new THREE.Group(); orb.scale.setScalar(SC); grp.add(orb);   /* turns with the view: pitch + slow yaw */
  const bb = new THREE.Group(); grp.add(bb);                         /* faces the camera: horizon, ticks */
  const FU = { uTime: { value: 0 }, uRing: { value: 0 }, uEnergy: { value: 0.5 }, uPulse: { value: -1 }, uWarm: { value: 0 },
    uOrbS: { value: 1 }, uFade: { value: 0 }, uPixel: U.uPixel, uVib: { value: 0 }, uDT: { value: 0 }, uHR: { value: 0.42 },
    uCool: { value: 0 }, uMid: { value: 1 }, uZoomP: { value: 1 } };
  const seeded = (a) => () => { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };

  /* ---- 1. ribbons: 14 ribbons x 10 lanes, each lane a closed loop of points (also drawn as hairlines) ---- */
  const RB = 18, LN = 14, PER = (typeof small !== 'undefined' && small) ? 140 : 260, N = RB * LN * PER;   /* ~65k grains: fine enough to read as silk */
  const aA = new Float32Array(N * 4), aB = new Float32Array(N * 4), aC = new Float32Array(N * 4), aD = new Float32Array(N * 4);
  { const R = seeded(20261024); let i = 0;
    for (let r = 0; r < RB; r++) {
      const ex = R() * TAU, ey = R() * TAU, ez = R() * TAU, wid = 0.06 + R() * 0.11;
      const f1 = 1 + Math.floor(R() * 3), ph = R() * TAU, amp = 0.05 + R() * 0.15, tw = (1 + Math.floor(R() * 2)) * (R() < 0.5 ? -1 : 1);
      const rx = (R() - 0.5) * 0.22, ry = (R() - 0.5) * 0.3, cm = R() < 0.42 ? 0.72 + R() * 0.28 : R() * 0.3, rad = 0.72 + R() * 0.34;
      const flow = (0.16 + R() * 0.22) * (R() < 0.5 ? -1 : 1);
      for (let l = 0; l < LN; l++) { const lo = l / (LN - 1) * 2 - 1;
        for (let k = 0; k < PER; k++, i++) {
          aA.set([k / PER * TAU, lo, flow, R()], i * 4); aB.set([ex, ey, ez, wid], i * 4);
          aC.set([f1, ph, amp, tw], i * 4); aD.set([rx, ry, cm, rad], i * 4);
        } } } }
  const rg = new THREE.BufferGeometry();
  rg.setAttribute('position', new THREE.BufferAttribute(new Float32Array(N * 3), 3));
  rg.setAttribute('aA', new THREE.BufferAttribute(aA, 4)); rg.setAttribute('aB', new THREE.BufferAttribute(aB, 4));
  rg.setAttribute('aC', new THREE.BufferAttribute(aC, 4)); rg.setAttribute('aD', new THREE.BufferAttribute(aD, 4));
  rg.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 10);
  const lineIdx = new Uint32Array(N * 2);
  for (let lane = 0, j = 0; lane < RB * LN; lane++) for (let k = 0; k < PER; k++) { lineIdx[j++] = lane * PER + k; lineIdx[j++] = lane * PER + (k + 1) % PER; }
  const rgL = rg.clone(); rgL.setIndex(new THREE.BufferAttribute(lineIdx, 1)); rgL.boundingSphere = rg.boundingSphere;
  const RIB_V = `
    uniform float uTime,uRing,uEnergy,uOrbS,uFade,uPixel,uVib,uWarm,uCool,uMid,uZoomP; uniform float uLineMode;
    attribute vec4 aA,aB,aC,aD; varying vec3 vC; varying float vA;
    mat3 rX(float a){ float c=cos(a),s=sin(a); return mat3(1.0,0.0,0.0, 0.0,c,s, 0.0,-s,c); }
    mat3 rY(float a){ float c=cos(a),s=sin(a); return mat3(c,0.0,-s, 0.0,1.0,0.0, s,0.0,c); }
    mat3 rZ(float a){ float c=cos(a),s=sin(a); return mat3(c,s,0.0, -s,c,0.0, 0.0,0.0,1.0); }
    void main(){
      float s=aA.x+uTime*aA.z;
      float r=aD.w*(1.0+aC.z*sin(aC.x*s+aC.y));
      vec3 p=vec3(cos(s)*r,sin(s)*r,aC.z*0.9*sin((aC.x+1.0)*s+aC.y*1.7));
      float tw=aC.w*s+aC.y;
      vec3 n=vec3(cos(s),sin(s),0.0)*cos(tw)+vec3(0.0,0.0,1.0)*sin(tw);
      p+=n*aA.y*aB.w*(1.0+uVib*0.35*sin(uTime*31.0+s*9.0));       /* speaking: the ribbons shimmer across their width */
      p=rZ(aB.z)*rY(aB.y+uTime*aD.y)*rX(aB.x+uTime*aD.x)*p;
      p*=uOrbS;
      float rr=1.0+(aD.w-0.89)*0.55+aA.y*0.05;
      vec3 pr=vec3(cos(s)*rr,aA.y*0.022+sin(s*2.0+uTime*1.7)*0.014,sin(s)*rr);
      pr=rX(0.16)*pr;
      float d=aA.w*0.35;
      float kr=clamp((uRing-d)/0.65,0.0,1.0); kr=kr*kr*(3.0-2.0*kr);
      vec3 pos=mix(p,pr,kr);
      pos=rY(sin(kr*3.14159)*0.7)*pos;                               /* the collapse swirls on its way in */
      vec4 mv=modelViewMatrix*vec4(pos,1.0);
      gl_Position=projectionMatrix*mv;
      float lc=1.0-abs(aA.y);
      vec3 cOrb=mix(mix(vec3(0.52,0.74,1.0),vec3(1.0,0.70,0.52),aD.z),vec3(0.95,0.97,1.0),lc*0.38);
      cOrb=mix(cOrb,vec3(0.62,0.86,1.0),uCool*0.5);
      vec3 cRing=mix(vec3(1.0,0.84,0.64),vec3(1.0,0.97,0.92),lc*0.6);
      vC=mix(cOrb,cRing,kr); vC=mix(vC,vec3(1.0,0.66,0.34),uWarm*0.5);
      float depthF=0.62+0.38*clamp((mv.z+7.2)*0.4+0.5,0.0,1.0);
      vA=mix(0.36,0.12,kr)*depthF*(0.75+0.25*uEnergy)*uFade*0.9;   /* the ring packs every grain into a thin band: dim each one */
      if(uLineMode>0.5) vA*=0.42*(1.0-kr*0.8)*uMid;   /* mid-collapse the hairlines would cut chords across the ring */
      gl_PointSize=1.15*uPixel*(7.2/-mv.z)*(0.75+aA.w*0.5)*uZoomP;
    }`;
  const SOFT_F = `uniform float uLineMode; varying vec3 vC; varying float vA;
    void main(){ float a=vA; if(uLineMode<0.5){ vec2 q=gl_PointCoord-0.5; float d=dot(q,q)*4.0; a*=exp(-d*3.2)*step(d,1.0); } gl_FragColor=vec4(vC*a,1.0); }`;
  const mkMat = (line) => new THREE.ShaderMaterial({ uniforms: Object.assign({ uLineMode: { value: line ? 1 : 0 } }, FU), vertexShader: RIB_V, fragmentShader: SOFT_F,
    transparent: true, depthWrite: false, depthTest: true, blending: THREE.AdditiveBlending });
  const ribP = new THREE.Points(rg, mkMat(false)), ribL = new THREE.LineSegments(rgL, mkMat(true));
  ribP.renderOrder = ribL.renderOrder = 901; orb.add(ribL, ribP);

  /* ---- 2. dust on tilted orbits: 58 faint rings and 1100 grains riding them ---- */
  const R2 = seeded(4242), M = 90, SEG = 256, RINGS = [];
  for (let i = 0; i < M; i++) RINGS.push({ ix: (R2() - 0.5) * 1.55, iz: (R2() - 0.5) * 0.95, r: 1.12 + Math.pow(R2(), 0.85), a: 0.022 + R2() * 0.045 });
  const ov = new Float32Array(M * SEG * 4), om = new Float32Array(M * SEG * 2), oi = new Uint32Array(M * SEG * 2);
  for (let i = 0, q = 0; i < M; i++) for (let j = 0; j < SEG; j++) { const v = i * SEG + j, g = RINGS[i];
    ov.set([g.ix, g.iz, g.r, j / SEG * TAU], v * 4); om.set([0, g.a], v * 2); oi[q++] = v; oi[q++] = i * SEG + (j + 1) % SEG; }
  const DN = (typeof small !== 'undefined' && small) ? 1500 : 4200, dv = new Float32Array(DN * 4), dm = new Float32Array(DN * 2);
  for (let i = 0; i < DN; i++) { const g = RINGS[Math.floor(R2() * M)]; dv.set([g.ix, g.iz, g.r, R2() * TAU], i * 4); dm.set([(0.22 + R2() * 0.3) / g.r, Math.pow(R2(), 3)], i * 2); }
  const ORB_V = `uniform float uTime,uDust,uPixel,uFade,uDT,uZoomP; attribute vec4 aO; attribute vec2 aM; varying vec3 vC; varying float vA;
    mat3 rX(float a){ float c=cos(a),s=sin(a); return mat3(1.0,0.0,0.0, 0.0,c,s, 0.0,-s,c); }
    mat3 rY(float a){ float c=cos(a),s=sin(a); return mat3(c,0.0,-s, 0.0,1.0,0.0, s,0.0,c); }
    mat3 rZ(float a){ float c=cos(a),s=sin(a); return mat3(c,s,0.0, -s,c,0.0, 0.0,0.0,1.0); }
    void main(){
      float a=aO.w+uDust*uDT*aM.x;
      vec3 p=vec3(cos(a)*aO.z,0.0,sin(a)*aO.z);
      p=rY(uTime*0.025*(0.6+aO.x))*rZ(aO.y)*rX(aO.x)*p;
      vec4 mv=modelViewMatrix*vec4(p,1.0); gl_Position=projectionMatrix*mv;
      vA=(uDust>0.5?(0.3+0.7*aM.y):aM.y)*uFade*(0.5+0.5*smoothstep(-2.2,2.2,mv.z+7.2));
      vC=mix(vec3(0.72,0.8,0.9),vec3(0.95,0.97,1.0),aM.y);
      gl_PointSize=(0.9+aM.y*2.0)*uPixel*(7.2/-mv.z)*uZoomP;
    }`;
  const orbMat = (dust) => new THREE.ShaderMaterial({ uniforms: Object.assign({}, FU, { uDust: { value: dust ? 1 : 0 }, uLineMode: { value: dust ? 0 : 1 } }),
    vertexShader: ORB_V, fragmentShader: SOFT_F, transparent: true, depthWrite: false, depthTest: true, blending: THREE.AdditiveBlending });
  const og = new THREE.BufferGeometry(); og.setAttribute('position', new THREE.BufferAttribute(new Float32Array(M * SEG * 3), 3));
  og.setAttribute('aO', new THREE.BufferAttribute(ov, 4)); og.setAttribute('aM', new THREE.BufferAttribute(om, 2)); og.setIndex(new THREE.BufferAttribute(oi, 1));
  og.boundingSphere = rg.boundingSphere;
  const dg = new THREE.BufferGeometry(); dg.setAttribute('position', new THREE.BufferAttribute(new Float32Array(DN * 3), 3));
  dg.setAttribute('aO', new THREE.BufferAttribute(dv, 4)); dg.setAttribute('aM', new THREE.BufferAttribute(dm, 2)); dg.boundingSphere = rg.boundingSphere;
  const orbLines = new THREE.LineSegments(og, orbMat(false)), dust = new THREE.Points(dg, orbMat(true));
  orbLines.renderOrder = dust.renderOrder = 901; orb.add(orbLines, dust);

  /* ---- 3. the horizon: a black disc that hides what is behind it, ringed by light (faces the camera) ---- */
  const shadow = new THREE.Mesh(new THREE.CircleGeometry(1, 96), new THREE.MeshBasicMaterial({ color: 0x000000, transparent: true, opacity: 1, depthWrite: true, depthTest: false }));
  shadow.renderOrder = 900; bb.add(shadow);
  const halo = new THREE.Mesh(new THREE.PlaneGeometry(12, 12), new THREE.ShaderMaterial({
    uniforms: FU, transparent: true, depthWrite: false, depthTest: false, blending: THREE.AdditiveBlending,
    vertexShader: `varying vec2 vQ; void main(){ vQ=position.xy; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
    fragmentShader: `uniform float uTime,uEnergy,uPulse,uWarm,uFade,uCool; varying vec2 vQ;
      float sq(float x){ return x*x; }
      void main(){
        float r=length(vQ); float ang=atan(vQ.y,vQ.x);
        if(r<0.985) discard;
        vec3 tint=mix(vec3(0.80,0.88,1.0),vec3(1.0,0.80,0.62),uWarm); tint=mix(tint,vec3(0.7,0.9,1.0),uCool*0.6);
        float beam=0.5+0.5*cos(ang-2.3);
        float glow=exp(-max(r-1.0,0.0)*2.5)*0.17*(0.75+uEnergy*0.7);
        float s1=0.5+0.5*sin(ang*9.0+r*13.0-uTime*1.2), s2=0.5+0.5*sin(ang*17.0-r*21.0+uTime*0.8+1.7);
        float s3=0.5+0.5*sin(ang*61.0-r*90.0+uTime*2.3); float acc=exp(-sq((r-1.28)/0.34))*(s1*0.5+s2*0.3+s3*0.2)*0.11*(0.6+uEnergy);
        float ring=exp(-sq((r-1.0)/0.011))*(0.5+1.0*beam)*(0.85+uEnergy*0.6)+exp(-sq((r-1.022)/0.006))*0.35*beam;   /* razor photon ring + its second image */
        float ring2=exp(-sq((r-1.05)/0.07))*0.22*(0.4+beam);
        float halo=exp(-sq((r-1.62)/0.045))*0.05;
        float pr=1.0+max(uPulse,0.0)*3.6;
        float shock=uPulse>=0.0?exp(-sq((r-pr)/(0.05+uPulse*0.12)))*(1.0-uPulse)*0.55:0.0;
        gl_FragColor=vec4(tint*(glow*(0.6+0.6*beam)+acc+ring+ring2+halo+shock)*uFade*(1.0-smoothstep(4.5,6.0,r)),1.0);
      }` }));
  halo.renderOrder = 902; bb.add(halo);
  /* ---- 4. the instrument ring: 120 ticks, every tenth long, three short arcs turning the other way, corner brackets ---- */
  const tickV = [], tickA = [];
  for (let i = 0; i < 120; i++) { const a = i / 120 * TAU, l = i % 10 === 0 ? 0.07 : 0.03, o = i % 10 === 0 ? 0.22 : 0.09;
    tickV.push(Math.cos(a) * 1.3, Math.sin(a) * 1.3, 0, Math.cos(a) * (1.3 + l), Math.sin(a) * (1.3 + l), 0); tickA.push(o, o); }
  const tg = new THREE.BufferGeometry(); tg.setAttribute('position', new THREE.Float32BufferAttribute(tickV, 3)); tg.setAttribute('aO', new THREE.Float32BufferAttribute(tickA, 1));
  const tickMat = new THREE.ShaderMaterial({ uniforms: FU, transparent: true, depthWrite: false, depthTest: false, blending: THREE.AdditiveBlending,
    vertexShader: `attribute float aO; varying float vO; void main(){ vO=aO; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
    fragmentShader: `uniform float uFade; varying float vO; void main(){ gl_FragColor=vec4(vec3(0.667,0.784,0.922)*vO*uFade,1.0); }` });
  const ticks = new THREE.LineSegments(tg, tickMat); ticks.renderOrder = 903;
  const arcV = [], arcA = [];
  for (let k = 0; k < 3; k++) for (let j = 0; j < 24; j++) { const a0 = k * TAU / 3 + j / 24 * 0.32, a1 = k * TAU / 3 + (j + 1) / 24 * 0.32;
    arcV.push(Math.cos(a0) * 1.275, Math.sin(a0) * 1.275, 0, Math.cos(a1) * 1.275, Math.sin(a1) * 1.275, 0); arcA.push(0.28, 0.28); }
  const ag = new THREE.BufferGeometry(); ag.setAttribute('position', new THREE.Float32BufferAttribute(arcV, 3)); ag.setAttribute('aO', new THREE.Float32BufferAttribute(arcA, 1));
  const arcs = new THREE.LineSegments(ag, tickMat); arcs.renderOrder = 903;
  const brV = [], brA = [], br = 2.05, bl = 0.09;
  [[-1, -1], [1, -1], [1, 1], [-1, 1]].forEach(([qx, qy]) => { const x = qx * br, y = qy * br * 0.82;
    brV.push(x, y - qy * bl, 0, x, y, 0, x, y, 0, x - qx * bl, y, 0); brA.push(0.18, 0.18, 0.18, 0.18); });
  const bg2 = new THREE.BufferGeometry(); bg2.setAttribute('position', new THREE.Float32BufferAttribute(brV, 3)); bg2.setAttribute('aO', new THREE.Float32BufferAttribute(brA, 1));
  const brackets = new THREE.LineSegments(bg2, tickMat); brackets.renderOrder = 903;
  const hud = new THREE.Group(); hud.scale.setScalar(SC); hud.add(brackets); bb.add(hud);
  const dial = new THREE.Group(); dial.rotation.x = Math.PI / 2; dial.add(ticks, arcs); orb.add(dial);   /* the instrument ring lies in the orb's equator */
  { const fv = [], fa = [];                                           /* a fine 360-tick vernier just inside it */
    for (let i = 0; i < 360; i++) { const a = i / 360 * TAU, l = i % 30 === 0 ? 0.035 : i % 5 === 0 ? 0.018 : 0.009;
      fv.push(Math.cos(a) * 1.22, Math.sin(a) * 1.22, 0, Math.cos(a) * (1.22 - l), Math.sin(a) * (1.22 - l), 0); fa.push(0.16, 0.16); }
    const fg = new THREE.BufferGeometry(); fg.setAttribute('position', new THREE.Float32BufferAttribute(fv, 3)); fg.setAttribute('aO', new THREE.Float32BufferAttribute(fa, 1));
    const vern = new THREE.LineSegments(fg, tickMat); vern.renderOrder = 903; dial.add(vern); }

  /* ---- motion ---- */
  let uYaw = 0, uPitch = 0, zoom = 1, zoomGoal = 1;
  const onWheel = (e) => { if (typeof machLive === 'function' && machLive()) return; e.preventDefault(); zoomGoal = Math.min(2.6, Math.max(0.55, zoomGoal * Math.exp(-e.deltaY * 0.0016))); };
  const onDbl = () => { zoomGoal = 1; uPitch = 0; };
  canvas.addEventListener('wheel', onWheel, { passive: false }); canvas.addEventListener('dblclick', onDbl);
  let yaw = 0, pitch = 0.36, ring = 0, energy = 0.5, holeK = 1, warm = 0, cool = 0, pulse = -1, lastPulse = 0, t = 0, dustT = 0, fade = 0, orbS = 1, spin = 0.07;
  const ease = (x, y, k, dt) => x + (y - x) * (1 - Math.pow(k, dt));
  return {
    frame(dt) {
      const st = typeof state === 'string' ? state : 'idle', vib = U.uVib.value;
      t += dt; fade = Math.min(1, fade + dt / 0.9);
      const T = { ring: 0, energy: 0.45 + 0.4 * (0.5 + 0.5 * Math.sin(t * TAU / 6)), holeK: 1, warm: 0, cool: 0, spin: 0.07, pitch: 0.36, orbS: 1 };
      if (st === 'listening') Object.assign(T, { energy: 1.05, cool: 1, spin: 0.04, orbS: 0.94 });
      else if (st === 'thinking') Object.assign(T, { ring: 1, energy: 1.2, holeK: 1.12, warm: 0.2, spin: 0.16, pitch: 0.5 });
      else if (st === 'speaking') Object.assign(T, { energy: 0.8 + 0.6 * vib, spin: 0.09 });
      ring += Math.sign(T.ring - ring) * Math.min(Math.abs(T.ring - ring), dt / 2.2);   /* the original's 2.2 s collapse, eased below */ energy = ease(energy, T.energy, 0.2, dt); holeK = ease(holeK, T.holeK, 0.2, dt);
      warm = ease(warm, T.warm, 0.3, dt); cool = ease(cool, T.cool, 0.2, dt); spin = ease(spin, T.spin, 0.3, dt);
      pitch = ease(pitch, T.pitch, 0.3, dt); orbS = ease(orbS, T.orbS, 0.2, dt);
      yaw += dt * spin; dustT += dt * (1 + energy * 0.6);
      if (st === 'speaking' && vib > 0.6 && pulse < 0 && t - lastPulse > 1.2) { pulse = 0; lastPulse = t; }
      if (pulse >= 0) { pulse += dt / 1.6; if (pulse >= 1) pulse = -1; }
      uYaw += rotV.y * 1.8; uPitch = Math.max(-0.6, Math.min(1.0, uPitch + rotV.x * 1.4));   /* drag / palm: turn it in 3D */
      zoom = ease(zoom, zoomGoal, 0.05, dt); grp.scale.setScalar(zoom); FU.uZoomP.value = Math.sqrt(zoom);
      orb.rotation.set(pitch + uPitch, yaw + uYaw, 0);
      bb.quaternion.copy(camera.quaternion);
      const hr = 0.42 * holeK * SC; shadow.scale.setScalar(hr); halo.scale.setScalar(hr);
      dial.rotation.z = -t * 0.05; arcs.rotation.z = t * 0.18;
      const g = U.uGain.value;
      Object.assign(FU, {}); FU.uTime.value = t; FU.uRing.value = ring < 0.5 ? 4 * ring * ring * ring : 1 - Math.pow(-2 * ring + 2, 3) / 2; FU.uMid.value = 1 - 4 * ring * (1 - ring); FU.uEnergy.value = energy; FU.uPulse.value = pulse; FU.uWarm.value = warm;
      FU.uCool.value = cool; FU.uVib.value = st === 'speaking' ? vib : 0; FU.uDT.value = dustT;
      FU.uOrbS.value = orbS * (1 + Math.sin(t * 1.3) * 0.025 + (energy - 0.6) * 0.05); FU.uFade.value = fade * g;
      grp.visible = g > 0.02;
      orbits.visible = false; labelBox.style.opacity = '0';
      rainM.opacity *= 0.2; rpM.opacity *= 0.2;               /* the default look's light rain would cut through the fine threads */   /* the silk core's orbit labels belong to the default look */
      bloom.strength *= 0.96;                              /* the original has no bloom; keep ours light so the threads stay fine */
    },
    dispose() { canvas.removeEventListener('wheel', onWheel); canvas.removeEventListener('dblclick', onDbl); scene.remove(grp); [rg, rgL, og, dg, tg, ag, bg2, shadow.geometry, halo.geometry].forEach(x => x.dispose());
      [ribP.material, ribL.material, orbLines.material, dust.material, shadow.material, halo.material, tickMat].forEach(m => m.dispose()); }
  };
};
