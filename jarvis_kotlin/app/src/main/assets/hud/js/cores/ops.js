'use strict';
/* <<core:ops */
/* 图纸 · Jarvis core (Claude, 2026-10-29). Rewritten for the Jarvis scene after studying the PAPER OPS theme (an engineering
   drawing on dark paper with a running log). Here Jarvis is the drawing: a technical drawing of Jarvis's own core, a hub with a
   ring of thirteen modules (one per ability), dimensioned and projected onto the sheet below, with a log band running round it
   that records what Jarvis actually just did.
   idle = the finished drawing turns slowly; listening = a measuring ring with a vernier opens round the hub with the mic level and
   the hearing module is called out; thinking = the drawing is redrawn from construction lines up, part by part, the modules
   lighting as their lines go down; an ability put to use = its module gets a callout with a check mark and a line in the log;
   speaking = a section plane sweeps through the core in time with the voice. */
SKCORES.ops = function () {
  const TAU = Math.PI * 2, lite = typeof small !== 'undefined' && small;
  const grp = new THREE.Group(); scene.add(grp);
  const tilt = new THREE.Group(); grp.add(tilt);
  const model = new THREE.Group(); tilt.add(model);
  const rng = skSeeded(20261104), lerp = (a, b, t) => a + (b - a) * t;
  const mix3 = (a, b, t) => [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)];
  const NA = SKAB.length, AC = SKAB.map(a => skHex(a.c)), WHITE = [1, 1, 1];
  const PRI = (typeof SKC !== 'undefined' && SKC.pri ? SKC.pri : '226,224,216').split(',').map(v => +v / 255);
  const HUB = 0.22, RING = 0.72, BASE = 0.9, BASE_Y = -0.32, SHEET_Y = -0.55, SHEET = 1.35, LAB_R = 1.14, LAB_Y = 0.26;
  const FU = { uTime: { value: 0 }, uPixel: U.uPixel, uFade: { value: 0 }, uZoomP: { value: 1 }, uDraw: { value: 1 }, uCons: { value: 0.3 },
    uSec: { value: -2 }, uSecOn: { value: 0 }, uHi: { value: new Float32Array(NA) }, uPri: { value: new THREE.Vector3(...PRI) },
    uCol: { value: AC.map(c => new THREE.Vector3(...c)) } };

  /* ---- 1. the drawing, as line segments: kind 0 part, 1 construction (dashed), 2 dimension, 3 callout leader ---- */
  const S = { v: [], o: [], k: [], m: [], u: [] };
  const seg = (a, b, ord, kind = 0, mod = -1) => { const L = Math.hypot(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
    S.v.push(...a, ...b); S.o.push(ord, ord); S.k.push(kind, kind); S.m.push(mod, mod); S.u.push(0, L); };
  const circle = (r, y, ord0, ord1, kind = 0, mod = -1, n = 128, cx = 0, cz = 0) => { for (let i = 0; i < n; i++) { const u0 = i / n * TAU, u1 = (i + 1) / n * TAU;
    seg([cx + Math.cos(u0) * r, y, cz + Math.sin(u0) * r], [cx + Math.cos(u1) * r, y, cz + Math.sin(u1) * r], lerp(ord0, ord1, i / n), kind, mod); } };
  const ang = (k) => -Math.PI / 2 + k / NA * TAU;
  const P = (r, a, y) => [Math.cos(a) * r, y, Math.sin(a) * r];
  /* construction first, as a draftsman would: centre lines, plan circles on the sheet, projectors */
  seg([-SHEET * 0.9, SHEET_Y, 0], [SHEET * 0.9, SHEET_Y, 0], 0.0, 1); seg([0, SHEET_Y, -SHEET * 0.9], [0, SHEET_Y, SHEET * 0.9], 0.01, 1);
  seg([0, SHEET_Y, 0], [0, 0.62, 0], 0.02, 1);
  [HUB, RING - 0.06, RING + 0.06, BASE].forEach((r, i) => circle(r, SHEET_Y, 0.03 + i * 0.03, 0.06 + i * 0.03, 1, -1, 160));
  for (let k = 0; k < NA; k++) { seg(P(RING, ang(k), -0.05), P(RING, ang(k), SHEET_Y), 0.12 + k * 0.004, 1, k); seg([0, SHEET_Y, 0], P(BASE, ang(k), SHEET_Y), 0.13 + k * 0.004, 1, k); }
  /* the hub: a drum with a domed cap and a mast */
  [-0.25, -0.12, 0.12, 0.25].forEach((y, i) => circle(HUB, y, 0.2 + i * 0.012, 0.212 + i * 0.012, 0, -1, 96));
  circle(HUB * 0.62, 0.25, 0.25, 0.26, 0, -1, 64); circle(HUB * 0.62, -0.25, 0.25, 0.26, 0, -1, 64);
  for (let i = 0; i < 24; i++) { const a = i / 24 * TAU; seg(P(HUB, a, -0.25), P(HUB, a, 0.25), 0.26 + i * 0.001); }
  for (let j = 1; j <= 4; j++) { const ph = j / 5 * Math.PI / 2; circle(HUB * Math.cos(ph), 0.25 + HUB * Math.sin(ph), 0.28 + j * 0.006, 0.286 + j * 0.006, 0, -1, 72); }
  for (let i = 0; i < 12; i++) { const a = i / 12 * TAU; for (let j = 0; j < 10; j++) { const p0 = j / 10 * Math.PI / 2, p1 = (j + 1) / 10 * Math.PI / 2;
    seg(P(HUB * Math.cos(p0), a, 0.25 + HUB * Math.sin(p0)), P(HUB * Math.cos(p1), a, 0.25 + HUB * Math.sin(p1)), 0.31 + i * 0.002); } }
  seg([0, 0.47, 0], [0, 0.56, 0], 0.33); circle(0.012, 0.56, 0.33, 0.335, 0, -1, 16);
  /* spokes, the main ring, ribs between the modules */
  for (let k = 0; k < NA; k++) { const a = ang(k); [-0.012, 0.012].forEach(d => { seg(P(HUB, a + d * 3, 0.02), P(RING - 0.07, a + d, 0.02), 0.35 + k * 0.004, 0, k); seg(P(HUB, a + d * 3, -0.02), P(RING - 0.07, a + d, -0.02), 0.35 + k * 0.004, 0, k); }); }
  circle(RING - 0.07, 0, 0.42, 0.47, 0, -1, 208); circle(RING + 0.07, 0, 0.43, 0.48, 0, -1, 208);
  circle(RING, 0.055, 0.44, 0.49, 0, -1, 208); circle(RING, -0.055, 0.45, 0.5, 0, -1, 208);
  for (let k = 0; k < NA; k++) { const a = ang(k + 0.5); seg(P(RING - 0.07, a, 0), P(RING + 0.07, a, 0), 0.5); seg(P(RING, a, -0.055), P(RING, a, 0.055), 0.5); }
  /* the thirteen modules: a box on the ring with a lens on its outer face and a cross on top */
  for (let k = 0; k < NA; k++) { const a = ang(k), o = 0.52 + k * 0.022, c = [Math.cos(a), Math.sin(a)], tg = [-c[1], c[0]];
    const ht = 0.07, hr = 0.075, y0 = -0.06, y1 = 0.08;
    const C = (st, sr, y) => [c[0] * (RING + sr * hr) + tg[0] * st * ht, y, c[1] * (RING + sr * hr) + tg[1] * st * ht];
    [y0, y1].forEach(y => { seg(C(-1, -1, y), C(1, -1, y), o, 0, k); seg(C(1, -1, y), C(1, 1, y), o, 0, k); seg(C(1, 1, y), C(-1, 1, y), o, 0, k); seg(C(-1, 1, y), C(-1, -1, y), o, 0, k); });
    [[-1, -1], [1, -1], [1, 1], [-1, 1]].forEach(([a1, b1]) => seg(C(a1, b1, y0), C(a1, b1, y1), o + 0.005, 0, k));
    seg(C(-1, -1, y1), C(1, 1, y1), o + 0.01, 0, k); seg(C(1, -1, y1), C(-1, 1, y1), o + 0.01, 0, k);
    const fc = [c[0] * (RING + hr), 0.01, c[1] * (RING + hr)];
    for (let i = 0; i < 20; i++) { const u0 = i / 20 * TAU, u1 = (i + 1) / 20 * TAU, r = 0.032;
      seg([fc[0] + tg[0] * Math.cos(u0) * r, fc[1] + Math.sin(u0) * r, fc[2] + tg[1] * Math.cos(u0) * r], [fc[0] + tg[0] * Math.cos(u1) * r, fc[1] + Math.sin(u1) * r, fc[2] + tg[1] * Math.cos(u1) * r], o + 0.012, 0, k); }
    /* a strut down to the base */
    seg(P(RING, a - 0.04, y0), P(BASE - 0.03, a - 0.02, BASE_Y), o + 0.014, 0, k); seg(P(RING, a + 0.04, y0), P(BASE - 0.03, a + 0.02, BASE_Y), o + 0.014, 0, k);
    /* the callout leader: up, then out to the name */
    seg(C(0, 0, y1), P(RING + 0.18, a, LAB_Y), 0.92 + k * 0.004, 3, k); seg(P(RING + 0.18, a, LAB_Y), P(LAB_R - 0.1, a, LAB_Y), 0.93 + k * 0.004, 3, k); }
  /* the base plate with its graduations */
  circle(BASE, BASE_Y, 0.82, 0.86, 0, -1, 240); circle(BASE - 0.04, BASE_Y, 0.83, 0.87, 0, -1, 240);
  for (let i = 0; i < 180; i++) { const a = i / 180 * TAU, l = i % 15 === 0 ? 0.05 : i % 5 === 0 ? 0.03 : 0.016; seg(P(BASE, a, BASE_Y), P(BASE + l, a, BASE_Y), 0.86 + i / 180 * 0.03, 2); }
  /* dimensions: the ring's diameter across the top, the overall height at the side */
  { const y = 0.66, x = RING + 0.07, ar = 0.03;
    seg([-x, y, 0], [x, y, 0], 0.88, 2); seg([-x, 0.08, 0], [-x, y + 0.03, 0], 0.88, 2); seg([x, 0.08, 0], [x, y + 0.03, 0], 0.88, 2);
    seg([-x, y, 0], [-x + ar, y + ar * 0.4, 0], 0.89, 2); seg([-x, y, 0], [-x + ar, y - ar * 0.4, 0], 0.89, 2);
    seg([x, y, 0], [x - ar, y + ar * 0.4, 0], 0.89, 2); seg([x, y, 0], [x - ar, y - ar * 0.4, 0], 0.89, 2);
    const hx = -(BASE + 0.12), y0 = BASE_Y, y1 = 0.56;
    seg([hx, y0, 0], [hx, y1, 0], 0.9, 2); seg([-BASE, y0, 0], [hx - 0.03, y0, 0], 0.9, 2); seg([-0.02, y1, 0], [hx - 0.03, y1, 0], 0.9, 2);
    seg([hx, y0, 0], [hx - ar * 0.4, y0 + ar, 0], 0.91, 2); seg([hx, y0, 0], [hx + ar * 0.4, y0 + ar, 0], 0.91, 2);
    seg([hx, y1, 0], [hx - ar * 0.4, y1 - ar, 0], 0.91, 2); seg([hx, y1, 0], [hx + ar * 0.4, y1 - ar, 0], 0.91, 2); }

  const LV = `uniform float uTime,uFade,uDraw,uCons,uSec,uSecOn; uniform float uHi[${NA}]; uniform vec3 uCol[${NA}]; uniform vec3 uPri;
    attribute float aO,aK,aM,aU; varying vec3 vC; varying float vA,vK,vU;
    void main(){ gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); vK=aK; vU=aU;
      float o=aK>0.5&&aK<1.5?aO:aO, drawn=smoothstep(o-0.015,o,uDraw), front=exp(-pow((o-uDraw)/0.012,2.0))*step(uDraw,0.999);
      int m=int(max(aM,0.0)+0.5); float hi=aM>-0.5?uHi[m]:0.0; vec3 mc=aM>-0.5?uCol[m]:uPri;
      float sec=uSecOn*exp(-pow((position.x-uSec)/0.025,2.0));
      float base=aK<0.5?0.5:aK<1.5?0.2*uCons:aK<2.5?0.34:0.08+hi*0.75;
      vec3 ink=mix(uPri,vec3(1.0),0.25);
      vC=mix(mix(ink,mc,hi*0.85),vec3(1.0),front*0.7+sec*0.4); vA=(base*(1.0+hi*(aK<0.5?0.9:0.0))*drawn+front*0.9+sec*0.8*drawn)*uFade; }`;
  const LF = `varying vec3 vC; varying float vA,vK,vU; void main(){ if(vK>0.5&&vK<1.5&&fract(vU*45.0)>0.55) discard; if(vK>2.5&&fract(vU*70.0)>0.7) discard; gl_FragColor=vec4(vC*vA,1.0); }`;
  { const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(S.v, 3));
    [['aO', S.o], ['aK', S.k], ['aM', S.m], ['aU', S.u]].forEach(([n, a]) => g.setAttribute(n, new THREE.Float32BufferAttribute(a, 1)));
    const L = new THREE.LineSegments(g, skAdd(FU, LV, LF)); L.renderOrder = 902; model.add(L); }
  /* fine ink grains along the part lines: the drawing's weight, and a bright pen point where it is being drawn */
  { const v = [], at = []; for (let i = 0; i < S.k.length; i += 2) { if (S.k[i] !== 0) continue; const L = S.u[i + 1], n = Math.max(1, Math.round(L / (lite ? 0.02 : 0.011)));
      const a = S.v.slice(i * 3, i * 3 + 3), b = S.v.slice(i * 3 + 3, i * 3 + 6);
      for (let j = 0; j < n; j++) { const u = (j + rng()) / n; v.push(lerp(a[0], b[0], u), lerp(a[1], b[1], u), lerp(a[2], b[2], u)); at.push(S.o[i], S.m[i], rng()); } }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(v, 3)); g.setAttribute('aP', new THREE.Float32BufferAttribute(at, 3));
    const pts = new THREE.Points(g, skAdd(FU, `uniform float uTime,uPixel,uFade,uZoomP,uDraw,uSec,uSecOn; uniform float uHi[${NA}]; uniform vec3 uCol[${NA}]; uniform vec3 uPri; attribute vec3 aP; varying vec3 vC; varying float vA;
        void main(){ vec4 mv=modelViewMatrix*vec4(position,1.0); gl_Position=projectionMatrix*mv;
          float drawn=smoothstep(aP.x-0.015,aP.x,uDraw), front=exp(-pow((aP.x-uDraw)/0.01,2.0))*step(uDraw,0.999);
          int m=int(max(aP.y,0.0)+0.5); float hi=aP.y>-0.5?uHi[m]:0.0; vec3 mc=aP.y>-0.5?uCol[m]:uPri;
          float sec=uSecOn*exp(-pow((position.x-uSec)/0.02,2.0)), tw=0.75+0.25*sin(uTime*1.7+aP.z*50.0);
          gl_PointSize=(1.3+front*3.0+sec*2.2+hi*0.8)*uPixel*(6.4/-mv.z)*uZoomP;
          vC=mix(mix(uPri,vec3(1.0),0.3),mc,hi*0.8); vC=mix(vC,vec3(1.0),front*0.6+sec*0.5); vA=((0.22+hi*0.35)*tw*drawn+front+sec*0.8*drawn)*uFade; }`, SK_SOFT_F));
    pts.renderOrder = 903; model.add(pts); }

  /* ---- 2. the sheet: a graph-paper disc under the drawing, fading to the edge ---- */
  { const v = [], o = [], st = 0.05; for (let i = -27; i <= 27; i++) { const x = i * st, a = i % 10 === 0 ? 1 : i % 5 === 0 ? 0.55 : 0.25;
      v.push(x, 0, -SHEET, x, 0, SHEET, -SHEET, 0, x, SHEET, 0, x); o.push(a, a, a, a); }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(v, 3)); g.setAttribute('aO', new THREE.Float32BufferAttribute(o, 1));
    const sheet = new THREE.LineSegments(g, skAdd(FU, `attribute float aO; varying float vO; varying vec2 vP; void main(){ vO=aO; vP=position.xz; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
      `uniform float uFade; uniform vec3 uPri; varying float vO; varying vec2 vP; void main(){ float r=length(vP)/${SHEET.toFixed(2)}; gl_FragColor=vec4(uPri*vO*0.13*smoothstep(1.0,0.55,r)*uFade,1.0); }`));
    sheet.renderOrder = 899; sheet.position.y = SHEET_Y; model.add(sheet); }
  /* the title block, lying on the sheet */
  const TB = (() => { const cv = document.createElement('canvas'); cv.width = 512; cv.height = 176; const x = cv.getContext('2d');
    x.strokeStyle = '#fff'; x.fillStyle = '#fff'; x.lineWidth = 2; x.strokeRect(4, 4, 504, 168); x.lineWidth = 1; x.beginPath(); x.moveTo(4, 70); x.lineTo(508, 70); x.moveTo(4, 122); x.lineTo(508, 122); x.moveTo(300, 70); x.lineTo(300, 172); x.stroke();
    x.textBaseline = 'middle'; x.font = '600 34px Michroma,"JetBrains Mono",monospace'; x.fillText('J.A.R.V.I.S  CORE', 18, 38);
    x.font = '600 30px "PingFang SC","Hiragino Sans GB",sans-serif'; x.fillText('图纸 · 核心总装', 18, 97);
    x.font = '500 17px "JetBrains Mono",monospace'; x.fillText('13 MODULES', 316, 97); x.fillText('SCALE 1:1', 18, 148); x.fillText('REV 2026-10-29', 316, 148);
    const t = new THREE.CanvasTexture(cv); t.anisotropy = 4; return t; })();
  const tbM = new THREE.Mesh(new THREE.PlaneGeometry(0.62, 0.213), new THREE.MeshBasicMaterial({ map: TB, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false }));
  tbM.rotation.x = -Math.PI / 2; tbM.position.set(0.66, SHEET_Y + 0.002, -0.98); tbM.renderOrder = 900; model.add(tbM);

  /* ---- 3. names, dimension figures, the hub's light ---- */
  const GLOWT = (() => { const cv = document.createElement('canvas'); cv.width = cv.height = 64; const x = cv.getContext('2d'), g = x.createRadialGradient(32, 32, 0, 32, 32, 32);
    g.addColorStop(0, 'rgba(255,255,255,1)'); g.addColorStop(0.25, 'rgba(255,255,255,.4)'); g.addColorStop(1, 'rgba(255,255,255,0)'); x.fillStyle = g; x.fillRect(0, 0, 64, 64); return new THREE.CanvasTexture(cv); })();
  const spr = (map, ro, parent = model) => { const s = new THREE.Sprite(new THREE.SpriteMaterial({ map, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false })); s.renderOrder = ro; parent.add(s); return s; };
  const textTex = (txt, w = 256, h = 64, px = 30) => { const cv = document.createElement('canvas'); cv.width = w; cv.height = h; const x = cv.getContext('2d');
    x.fillStyle = '#fff'; x.textAlign = 'center'; x.textBaseline = 'middle'; x.font = `500 ${px}px "JetBrains Mono",Michroma,monospace`; x.fillText(txt, w / 2, h / 2); const t = new THREE.CanvasTexture(cv); t.anisotropy = 4; return t; };
  const dimT = [textTex('Ø 1580'), textTex('H 880')], dims = [spr(dimT[0], 905), spr(dimT[1], 905)];
  dims[0].position.set(0, 0.71, 0); dims[1].position.set(-(BASE + 0.22), 0.12, 0); dims.forEach(d => d.scale.set(0.34, 0.085, 1));
  const labs = SKAB.map((ab, k) => { const a = ang(k), lt = skLabelTex(ab.zh, ab.en), ct = skLabelTex(ab.zh + ' ✓', ab.en), s = spr(lt, 906);
    s.position.set(...P(LAB_R, a, LAB_Y)); s.scale.set(0.36, 0.135, 1); return { s, lt, ct }; });
  const core = spr(GLOWT, 901); core.position.y = 0;
  /* ---- 4. the measuring ring (listening) and the section plane (speaking) ---- */
  const simple = (u) => skAdd(u, `attribute float aO; varying float vO; void main(){ vO=aO; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
    `uniform float uA; uniform vec3 uC; varying float vO; void main(){ gl_FragColor=vec4(uC*vO*uA,1.0); }`);
  const mkLines = (fn, u) => { const v = [], o = []; fn((a, b, al) => { v.push(...a, ...b); o.push(al, al); });
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(v, 3)); g.setAttribute('aO', new THREE.Float32BufferAttribute(o, 1));
    const L = new THREE.LineSegments(g, simple(u)); L.renderOrder = 904; model.add(L); return L; };
  const MU = { uA: { value: 0 }, uC: { value: new THREE.Vector3(...mix3(AC[SKAB_I.ear], WHITE, 0.3)) } };
  const meas = mkLines((add) => { for (let i = 0; i < 256; i++) { const u0 = i / 256 * TAU, u1 = (i + 1) / 256 * TAU; add([Math.cos(u0), 0, Math.sin(u0)], [Math.cos(u1), 0, Math.sin(u1)], 0.8); }
    for (let i = 0; i < 120; i++) { const u = i / 120 * TAU, l = i % 10 === 0 ? 0.09 : 0.04; add([Math.cos(u), 0, Math.sin(u)], [Math.cos(u) * (1 + l), 0, Math.sin(u) * (1 + l)], i % 10 === 0 ? 0.9 : 0.5); }
    add([1, 0, 0], [1.25, 0, 0], 0.8); }, MU);
  const SU = { uA: { value: 0 }, uC: { value: new THREE.Vector3(...mix3(AC[SKAB_I.voice], WHITE, 0.35)) } };
  const secL = mkLines((add) => { const y0 = BASE_Y, y1 = 0.56, z = BASE + 0.05;
    add([0, y0, -z], [0, y0, z], 0.8); add([0, y0, z], [0, y1, z], 0.8); add([0, y1, z], [0, y1, -z], 0.8); add([0, y1, -z], [0, y0, -z], 0.8);
    for (let i = 1; i < 26; i++) { const zz = -z + i / 26 * 2 * z; add([0, y0, zz], [0, Math.min(y1, y0 + (zz + z) * 0.25), Math.max(-z, zz - (y1 - y0) * 0.6)], 0.15); } }, SU);

  /* ---- 5. the log band: what Jarvis actually did, written round the drawing ---- */
  const LOG = []; const logCv = document.createElement('canvas'); logCv.width = 2048; logCv.height = 64; const logT = new THREE.CanvasTexture(logCv); logT.anisotropy = 4;
  const hhmm = () => { const d = new Date(); return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'); };
  const drawLog = () => { const x = logCv.getContext('2d'); x.clearRect(0, 0, 2048, 64); x.fillStyle = '#fff'; x.textBaseline = 'middle';
    x.font = '500 26px "JetBrains Mono","PingFang SC",monospace'; let px = 12;
    for (let i = LOG.length - 1; i >= 0 && px < 2000; i--) { const e = LOG[i]; x.globalAlpha = 0.95 - (LOG.length - 1 - i) * 0.07; x.fillText(e, px, 32); px += x.measureText(e).width + 26; x.globalAlpha = 0.5; x.fillText('·', px - 18, 32); }
    logT.needsUpdate = true; };
  const log = (s) => { LOG.push(hhmm() + '  ' + s); if (LOG.length > 12) LOG.shift(); drawLog(); };
  log('图纸 BLUEPRINT  ONLINE');
  const band = new THREE.Mesh(new THREE.CylinderGeometry(1.3, 1.3, 0.075, 120, 1, true, 0, Math.PI * 1.25), new THREE.MeshBasicMaterial({ map: logT, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false, side: THREE.FrontSide }));
  band.position.y = -0.43; band.renderOrder = 905; tilt.add(band);

  /* ---- what Jarvis is doing -> the drawing ---- */
  const hi = FU.uHi.value, ev = new Float32Array(NA), call = new Float32Array(NA);
  let mic = 0, draw = 1, redraw = false;
  const onMsg = (m) => { const e = skEvent(m); if (!e) return;
    if (e.k === 'wake') { ev[SKAB_I.ear] = 1; log('唤醒 WAKE'); }
    else if (e.k === 'heard') { ev[SKAB_I.ear] = 1; log('听到 HEARD'); }
    else if (e.k === 'reply') { ev[SKAB_I.voice] = 1; log('回答 REPLY'); }
    else if (e.k === 'level') mic = Math.max(mic, e.v);
    else if (e.k === 'sys') ev[SKAB_I.sys] = Math.max(ev[SKAB_I.sys], 0.3);
    else if (e.k === 'use') { ev[e.i] = 1; call[e.i] = 4.5; log(SKAB[e.i].zh + ' ' + SKAB[e.i].en + ' ✓'); } };

  /* ---- motion ---- */
  const ctl = skControl();
  let t = 0, fade = 0, prevSt = 'idle', cons = 0.3, measR = 0.3, measA = 0, secX = -1.2, secA = 0, spin = 0, spinSp = 0.05;
  return {
    msg: onMsg,
    frame(dt) {
      dt = Math.min(dt, 0.1); const st = typeof state === 'string' ? state : 'idle', vib = st === 'speaking' ? U.uVib.value : 0;
      t += dt; fade = Math.min(1, fade + dt / 0.9); mic *= Math.pow(0.15, dt);
      if (st !== prevSt) { if (st === 'thinking') { draw = 0; redraw = true; } prevSt = st; }
      /* thinking redraws the core from its construction lines up, again and again until the answer comes */
      if (st === 'thinking') { draw += dt / 2.6; if (draw > 1.12) draw = 0; }
      else draw = Math.min(1, draw + dt / (redraw ? 1.2 : 0.6));
      if (draw >= 1 && st !== 'thinking') redraw = false;
      cons = skEase(cons, st === 'thinking' ? 1 : st === 'listening' ? 0.6 : 0.3, 0.1, dt);
      /* listening: the measuring ring opens with the voice */
      measA = skEase(measA, st === 'listening' ? 1 : 0, 0.05, dt); measR = skEase(measR, 0.98 + mic * 0.3 + 0.015 * Math.sin(t * 3), 0.01, dt);
      meas.scale.setScalar(measR); meas.rotation.y = t * 0.4; MU.uA.value = measA * fade * U.uGain.value;
      /* speaking: a section plane sweeps through, faster with a louder voice */
      secA = skEase(secA, st === 'speaking' ? 1 : 0, 0.05, dt);
      if (secA > 0.01) { secX += dt * (0.5 + vib * 1.4); if (secX > BASE + 0.1) secX = -(BASE + 0.1); } else secX = -(BASE + 0.1);
      secL.position.x = secX; SU.uA.value = secA * (0.55 + vib * 0.5) * fade * U.uGain.value;
      /* modules: lit when used, when drawn during a redraw, the ear while listening, the voice while speaking */
      for (let k = 0; k < NA; k++) { ev[k] *= Math.pow(0.3, dt); call[k] = Math.max(0, call[k] - dt); let b = 0;
        if (st === 'listening' && k === SKAB_I.ear) b = 0.5 + mic * 0.5;
        if (st === 'speaking' && k === SKAB_I.voice) b = 0.45 + vib * 0.5;
        if (st === 'thinking') { const o = 0.52 + k * 0.022; b = Math.max(b, 0.6 * Math.exp(-Math.pow((draw - o - 0.02) / 0.04, 2))); }
        hi[k] = skEase(hi[k], Math.max(b, ev[k], Math.min(1, call[k] / 1.5)), 0.03, dt);
        const L = labs[k], chk = call[k] > 0; if ((L.s.material.map === L.ct) !== chk) { L.s.material.map = chk ? L.ct : L.lt; L.s.material.needsUpdate = true; }
        L.s.material.color.setRGB(...mix3(AC[k], WHITE, 0.55 - hi[k] * 0.35)); L.s.material.opacity = (0.28 + hi[k] * 0.72) * Math.min(1, draw * 1.2) * fade * U.uGain.value;
        L.s.scale.set(0.36 * (1 + hi[k] * 0.2), 0.135 * (1 + hi[k] * 0.2), 1); }
      dims.forEach(d => { d.material.color.setRGB(...mix3(PRI, WHITE, 0.3)); d.material.opacity = 0.55 * Math.max(0, Math.min(1, (draw - 0.88) * 12)) * fade * U.uGain.value; });
      core.material.color.setRGB(...mix3(PRI, WHITE, 0.5)); core.scale.setScalar(0.16 + vib * 0.12 + mic * 0.1); core.material.opacity = (0.2 + vib * 0.4 + (st === 'thinking' ? 0.15 : 0)) * fade * U.uGain.value;
      tbM.material.opacity = 0.5 * fade * U.uGain.value; band.material.opacity = 0.9 * fade * U.uGain.value;
      /* view: three-quarter view of the drawing, slowly turning, plus drag / palm */
      spinSp = skEase(spinSp, st === 'thinking' ? 0.1 : 0.05, 0.2, dt); spin += dt * spinSp;
      ctl.step(dt); grp.scale.setScalar(ctl.zoom * 1.12); FU.uZoomP.value = Math.sqrt(ctl.zoom * 1.12);
      tilt.rotation.set(0.5 + ctl.pitch, ctl.yaw, 0, 'XYZ'); model.rotation.y = spin; band.rotation.y = -t * 0.035;
      FU.uTime.value = t; FU.uFade.value = fade * U.uGain.value; FU.uDraw.value = Math.min(1, draw); FU.uCons.value = cons;
      FU.uSec.value = secX; FU.uSecOn.value = secA;
      grp.visible = U.uGain.value > 0.02;
      skQuiet(0.9);
    },
    dispose() { ctl.dispose(); scene.remove(grp); grp.traverse(o => { if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); });
      [GLOWT, TB, logT, ...dimT, ...labs.flatMap(l => [l.lt, l.ct])].forEach(x => x.dispose()); }
  };
};
/* core:ops>> */
