'use strict';
/* <<core:spider */
/* 作战室 · Jarvis core (Claude, 2026-10-29). Rewritten for the Jarvis scene after studying the SPIDER WAR ROOM theme (a
   command room where a chief dispatches agents to their desks). A pixel office does not fit the 3D scene, so its spirit
   becomes a holographic war table: Jarvis's headquarters in the middle of a contoured terrain, and the thirteen abilities as
   posts around it, each with its own pin and name.
   idle = a slow radar sweep, the terrain breathes; listening = sonar rings leave HQ with the mic level and the hearing post
   lights; thinking = the sweep speeds up and HQ plots a line to each post in turn; an ability put to use = a dispatch arc
   flies from HQ to that post, its pin rises and rings spread over the ground around it; speaking = the ground ripples out
   from HQ with each syllable and the voice post glows. */
SKCORES.spider = function () {
  const TAU = Math.PI * 2, lite = typeof small !== 'undefined' && small;
  const grp = new THREE.Group(); scene.add(grp);
  const tilt = new THREE.Group(); grp.add(tilt);
  const table = new THREE.Group(); table.position.y = -0.05; tilt.add(table);
  const rng = skSeeded(20261102), lerp = (a, b, t) => a + (b - a) * t, clamp = (v, a, b) => v < a ? a : v > b ? b : v;
  const mix3 = (a, b, t) => [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)];
  const NA = SKAB.length, AC = SKAB.map(a => skHex(a.c)), WHITE = [1, 1, 1];
  const PRI = (typeof SKC !== 'undefined' && SKC.pri ? SKC.pri : '236,79,174').split(',').map(v => +v / 255), CY = skHex('#45e6f5');
  const TR = 1.15, HMAX = 0.16;
  /* terrain height: a few octaves of value noise, flattened near HQ and at the rim */
  const hsh = (x, y) => { const s = Math.sin(x * 127.1 + y * 311.7) * 43758.5453; return s - Math.floor(s); };
  const vn = (x, y) => { const xi = Math.floor(x), yi = Math.floor(y), xf = x - xi, yf = y - yi, u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf);
    return lerp(lerp(hsh(xi, yi), hsh(xi + 1, yi), u), lerp(hsh(xi, yi + 1), hsh(xi + 1, yi + 1), u), v); };
  const height = (x, z) => { let h = 0, a = 0.55, f = 1.6; for (let o = 0; o < 4; o++) { h += vn(x * f + 11, z * f + 7) * a; a *= 0.5; f *= 2.1; }
    const r = Math.hypot(x, z) / TR; return Math.max(0, h - 0.42) * HMAX * 2.4 * smoothstep(0.12, 0.35, r) * (1 - smoothstep(0.86, 1, r)); };
  function smoothstep(a, b, x) { const t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); }
  /* the posts: spread round HQ at two radii, each with its own spot on the map */
  const MK = SKAB.map((_, i) => { const a = -Math.PI / 2 + i / NA * TAU + (rng() - 0.5) * 0.18, r = (i % 2 ? 0.58 : 0.86) * TR * (0.95 + rng() * 0.08); const x = Math.cos(a) * r, z = Math.sin(a) * r; return [x, height(x, z), z]; });
  const FU = { uTime: { value: 0 }, uPixel: U.uPixel, uFade: { value: 0 }, uZoomP: { value: 1 }, uSweep: { value: 0 }, uMk: { value: MK.map(p => new THREE.Vector3(...p)) },
    uMa: { value: new Float32Array(NA) }, uMf: { value: new Float32Array(NA).fill(-1) }, uSonar: { value: new Float32Array(3).fill(-1) }, uWave: { value: -1 },
    uPri: { value: new THREE.Vector3(...PRI) }, uCy: { value: new THREE.Vector3(...CY) }, uEnergy: { value: 0.5 }, uCol: { value: AC.map(c => new THREE.Vector3(...c)) } };

  /* ---- 1. the terrain: a dense point grid, contour lines picked out, lit by the sweep and by the posts ---- */
  const NG = lite ? 110 : 170, tv = [];
  for (let i = 0; i < NG; i++) for (let j = 0; j < NG; j++) { const x = (i / (NG - 1) * 2 - 1) * TR, z = (j / (NG - 1) * 2 - 1) * TR; if (x * x + z * z > TR * TR) continue; tv.push(x, height(x, z), z); }
  const tg = new THREE.BufferGeometry(); tg.setAttribute('position', new THREE.Float32BufferAttribute(tv, 3)); tg.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 3);
  const TER_V = `uniform float uTime,uPixel,uFade,uZoomP,uSweep,uWave,uEnergy; uniform vec3 uMk[${NA}]; uniform float uMa[${NA}]; uniform float uMf[${NA}]; uniform float uSonar[3]; uniform vec3 uPri,uCy; uniform vec3 uCol[${NA}];
    varying vec3 vC; varying float vA;
    void main(){ vec3 p=position; float r=length(p.xz)/${TR.toFixed(2)};
      float wr=uWave*1.3, wave=uWave>=0.0?exp(-pow((r-wr)/0.05,2.0))*(1.0-uWave):0.0; p.y+=wave*0.03;
      vec4 mv=modelViewMatrix*vec4(p,1.0); gl_Position=projectionMatrix*mv;
      float h=position.y/${HMAX.toFixed(2)}, cont=exp(-pow(fract(h*5.0+0.02)-0.5,2.0)*0.0+0.0)*0.0+smoothstep(0.08,0.0,abs(fract(h*5.0)-0.5)-0.42);
      float a=atan(p.z,p.x), d=mod(uSweep-a,6.28318), sw=exp(-d*2.2)*0.9;
      float son=0.0; for(int i=0;i<3;i++){ float s=uSonar[i]; if(s>=0.0) son+=exp(-pow((r-s*1.05)/0.025,2.0))*(1.0-s); }
      vec3 col=mix(uPri*0.75,uCy,0.25+h*0.6); float mk=0.0; vec3 mc=vec3(0.0);
      for(int i=0;i<${NA};i++){ float dd=length(p.xz-uMk[i].xz); float g=exp(-dd*dd/0.012)*uMa[i]; float f=uMf[i];
        float ring=f>=0.0?exp(-pow((dd-f*0.32)/0.012,2.0))*(1.0-f):0.0; mk+=g+ring; mc+=uCol[i]*(g+ring); }
      vC=mix(col,vec3(1.0),cont*0.25+sw*0.3)+mc*0.8; vC=mix(vC,vec3(1.0),wave*0.5+son*0.5);
      gl_PointSize=(1.2+cont*0.9+mk*1.4+son*1.5)*uPixel*(6.4/-mv.z)*uZoomP;
      vA=(0.08+h*0.18+cont*0.22+sw*0.35+son*0.7+wave*0.6+mk*0.6)*(0.85+uEnergy*0.2)*uFade*(1.0-smoothstep(0.95,1.0,r)); }`;
  const terrain = new THREE.Points(tg, skAdd(FU, TER_V, SK_SOFT_F)); terrain.renderOrder = 900; table.add(terrain);
  /* a sparse wire grid draped over the ground */
  { const v = [], o = [], step = TR * 2 / 24;
    for (let k = 0; k <= 24; k++) for (let s = 0; s < 60; s++) { for (const ax of [0, 1]) { const c = -TR + k * step, a0 = -TR + s / 60 * TR * 2, a1 = -TR + (s + 1) / 60 * TR * 2;
      const [x0, z0, x1, z1] = ax ? [c, a0, c, a1] : [a0, c, a1, c]; if (x0 * x0 + z0 * z0 > TR * TR * 0.97 || x1 * x1 + z1 * z1 > TR * TR * 0.97) continue;
      v.push(x0, height(x0, z0) + 0.002, z0, x1, height(x1, z1) + 0.002, z1); o.push(0.06, 0.06); } }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(v, 3)); g.setAttribute('aO', new THREE.Float32BufferAttribute(o, 1));
    const L = new THREE.LineSegments(g, skAdd(FU, `attribute float aO; varying float vO; void main(){ vO=aO; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
      `uniform float uFade; uniform vec3 uCy; varying float vO; void main(){ gl_FragColor=vec4(uCy*vO*uFade,1.0); }`)); L.renderOrder = 899; table.add(L); }

  /* ---- 2. the bezel: rings, bearings, a sweep line, compass letters ---- */
  const lineMat = skAdd(FU, `attribute float aO; varying float vO; void main(){ vO=aO; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
    `uniform float uFade; uniform vec3 uPri; varying float vO; void main(){ gl_FragColor=vec4(mix(uPri,vec3(1.0),0.35)*vO*uFade,1.0); }`);
  const lineSet = (fn, ro = 902) => { const v = [], o = []; fn((x0, y0, z0, x1, y1, z1, a) => { v.push(x0, y0, z0, x1, y1, z1); o.push(a, a); });
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(v, 3)); g.setAttribute('aO', new THREE.Float32BufferAttribute(o, 1));
    const L = new THREE.LineSegments(g, lineMat); L.renderOrder = ro; return L; };
  const circ = (r, a, n = 256, dash = 0, y = 0) => (add) => { for (let i = 0; i < n; i++) { if (dash && i % dash >= dash / 2) continue; const u0 = i / n * TAU, u1 = (i + 1) / n * TAU;
    add(Math.cos(u0) * r, y, Math.sin(u0) * r, Math.cos(u1) * r, y, Math.sin(u1) * r, a); } };
  const bezel = lineSet((add) => { circ(TR * 1.01, 0.45)(add); circ(TR * 1.05, 0.2, 360, 6)(add); circ(TR * 1.13, 0.3)(add); circ(TR * 1.01, 0.18, 256, 0, -0.06)(add);
    for (let i = 0; i < 360; i++) { const a = i / 360 * TAU, l = i % 30 === 0 ? 0.07 : i % 5 === 0 ? 0.035 : 0.015; add(Math.cos(a) * TR * 1.06, 0, Math.sin(a) * TR * 1.06, Math.cos(a) * (TR * 1.06 + l), 0, Math.sin(a) * (TR * 1.06 + l), i % 30 === 0 ? 0.5 : 0.18); }
    for (let i = 0; i < 64; i++) { const a = i / 64 * TAU; add(Math.cos(a) * TR * 1.01, 0, Math.sin(a) * TR * 1.01, Math.cos(a) * TR * 1.01, -0.06, Math.sin(a) * TR * 1.01, 0.12); } });
  table.add(bezel);
  const rangeRings = lineSet((add) => { [0.25, 0.5, 0.75].forEach(f => circ(TR * f, 0.1, 200, 4, 0.004)(add)); for (let i = 0; i < 4; i++) { const a = i * TAU / 4; add(0, 0.004, 0, Math.cos(a) * TR, 0.004, Math.sin(a) * TR, 0.07); } }, 899);
  table.add(rangeRings);
  const sweepL = lineSet((add) => { for (let i = 0; i < 40; i++) { const a = -i / 40 * 0.9; add(0, 0.01, 0, Math.cos(a) * TR, 0.01, Math.sin(a) * TR, 0.4 * Math.pow(1 - i / 40, 3)); } }, 901);
  table.add(sweepL);
  const letter = (ch) => { const cv = document.createElement('canvas'); cv.width = cv.height = 64; const x = cv.getContext('2d'); x.fillStyle = '#fff'; x.font = '600 40px Michroma,"JetBrains Mono",monospace';
    x.textAlign = 'center'; x.textBaseline = 'middle'; x.fillText(ch, 32, 34); return new THREE.CanvasTexture(x.canvas); };
  const texs = [];
  const spr = (map, ro, col) => { const s = new THREE.Sprite(new THREE.SpriteMaterial({ map, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false })); if (col) s.material.color.setRGB(...col);
    s.renderOrder = ro; table.add(s); return s; };
  const compass = ['E', 'S', 'W', 'N'].map((ch, i) => { const tx = letter(ch); texs.push(tx); const s = spr(tx, 905, mix3(PRI, WHITE, 0.5)); const a = i * TAU / 4; s.position.set(Math.cos(a) * TR * 1.22, 0, Math.sin(a) * TR * 1.22); s.scale.setScalar(0.09); return s; });

  /* ---- 3. HQ and the posts ---- */
  const GLOWT = (() => { const cv = document.createElement('canvas'); cv.width = cv.height = 64; const x = cv.getContext('2d'), g = x.createRadialGradient(32, 32, 0, 32, 32, 32);
    g.addColorStop(0, 'rgba(255,255,255,1)'); g.addColorStop(0.25, 'rgba(255,255,255,.45)'); g.addColorStop(1, 'rgba(255,255,255,0)'); x.fillStyle = g; x.fillRect(0, 0, 64, 64); return new THREE.CanvasTexture(x.canvas); })();
  const DIAT = (() => { const cv = document.createElement('canvas'); cv.width = cv.height = 64; const x = cv.getContext('2d'); x.strokeStyle = '#fff'; x.lineWidth = 4;
    x.beginPath(); x.moveTo(32, 6); x.lineTo(58, 32); x.lineTo(32, 58); x.lineTo(6, 32); x.closePath(); x.stroke(); x.fillStyle = 'rgba(255,255,255,.35)'; x.fill(); return new THREE.CanvasTexture(x.canvas); })();
  texs.push(GLOWT, DIAT);
  const hqTex = skLabelTex('贾维斯', 'J.A.R.V.I.S HQ'); texs.push(hqTex);
  const hqGlow = spr(GLOWT, 904, mix3(PRI, WHITE, 0.5)), hqLab = spr(hqTex, 906, WHITE); hqLab.position.set(0, 0.36, 0); hqLab.scale.set(0.34, 0.13, 1);
  const hqTower = lineSet((add) => { for (let i = 0; i < 6; i++) { const a = i / 6 * TAU, b = a + TAU / 6; add(Math.cos(a) * 0.05, 0, Math.sin(a) * 0.05, Math.cos(a) * 0.012, 0.3, Math.sin(a) * 0.012, 0.5);
    add(Math.cos(a) * 0.05, 0, Math.sin(a) * 0.05, Math.cos(b) * 0.05, 0, Math.sin(b) * 0.05, 0.4); [0.1, 0.2].forEach(y => { const r = lerp(0.05, 0.012, y / 0.3); add(Math.cos(a) * r, y, Math.sin(a) * r, Math.cos(b) * r, y, Math.sin(b) * r, 0.3); }); } }, 903);
  table.add(hqTower);
  /* pins: a beam per post (height follows activity), a diamond on top, its name */
  const pinV = new Float32Array(NA * 6), pinA = new Float32Array(NA * 2), pinC = new Float32Array(NA * 6);
  const pg = new THREE.BufferGeometry(); pg.setAttribute('position', new THREE.BufferAttribute(pinV, 3)); pg.setAttribute('aA', new THREE.BufferAttribute(pinA, 1)); pg.setAttribute('aCol', new THREE.BufferAttribute(pinC, 3));
  pg.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 3);
  const pins = new THREE.LineSegments(pg, skAdd(FU, `attribute float aA; attribute vec3 aCol; varying vec3 vC; varying float vA; void main(){ vC=aCol; vA=aA; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
    `uniform float uFade; varying vec3 vC; varying float vA; void main(){ gl_FragColor=vec4(vC*vA*uFade,1.0); }`)); pins.renderOrder = 903; table.add(pins);
  const posts = SKAB.map((ab, i) => { const tx = skLabelTex(ab.zh, ab.en); texs.push(tx); const dia = spr(DIAT, 905, AC[i]), lab = spr(tx, 906, mix3(AC[i], WHITE, 0.45)); lab.scale.set(0.27, 0.1, 1); dia.scale.setScalar(0.045); return { dia, lab }; });

  /* ---- 4. dispatch arcs and plot lines (points along a lifted arc, with a running head) ---- */
  const NAR = 8, ARN = 60, arP = new Float32Array(NAR * ARN * 3), arA = new Float32Array(NAR * ARN), arC = new Float32Array(NAR * ARN * 3);
  const ag = new THREE.BufferGeometry(); ag.setAttribute('position', new THREE.BufferAttribute(arP, 3)); ag.setAttribute('aA', new THREE.BufferAttribute(arA, 1)); ag.setAttribute('aCol', new THREE.BufferAttribute(arC, 3));
  ag.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 3);
  const arcs = new THREE.Points(ag, skAdd(FU, `uniform float uPixel,uFade,uZoomP; attribute float aA; attribute vec3 aCol; varying vec3 vC; varying float vA; void main(){ vec4 mv=modelViewMatrix*vec4(position,1.0); gl_Position=projectionMatrix*mv;
      gl_PointSize=(1.5+aA*5.0)*uPixel*(6.4/-mv.z)*uZoomP; vC=aCol; vA=aA*uFade; }`, SK_SOFT_F)); arcs.renderOrder = 904; table.add(arcs);
  const flights = [];
  const dispatch = (i, lift, dur, plot) => { if (flights.length >= NAR) flights.shift(); flights.push({ i, t: 0, dur, lift, plot, col: mix3(AC[i], WHITE, 0.3) }); };

  /* ---- what Jarvis is doing ---- */
  const ma = FU.uMa.value, mf = FU.uMf.value, son = FU.uSonar.value, ev = new Float32Array(NA);
  let mic = 0, sonAcc = 0, wave = -1, lastWave = 0, scan = 0, scanT = 0;
  const fire = (i) => { ev[i] = 1; mf[i] = -1; dispatch(i, 0.35, 0.8, false); };
  const onMsg = (m) => { const e = skEvent(m); if (!e) return;
    if (e.k === 'wake') { ev[SKAB_I.ear] = 1; const k = son.indexOf(-1); son[k < 0 ? 0 : k] = 0; }
    else if (e.k === 'heard') { ev[SKAB_I.ear] = 1; dispatch(SKAB_I.ear, 0.2, 0.6, true); }
    else if (e.k === 'reply') fire(SKAB_I.voice);
    else if (e.k === 'level') mic = Math.max(mic, e.v);
    else if (e.k === 'sys') ev[SKAB_I.sys] = Math.max(ev[SKAB_I.sys], 0.35);
    else if (e.k === 'use') fire(e.i); };

  /* ---- motion ---- */
  const ctl = skControl();
  let t = 0, fade = 0, sweep = 0, sweepSp = 0.6, energy = 0.5;
  return {
    msg: onMsg,
    frame(dt) {
      dt = Math.min(dt, 0.1); const st = typeof state === 'string' ? state : 'idle', vib = st === 'speaking' ? U.uVib.value : 0;
      t += dt; fade = Math.min(1, fade + dt / 0.9); mic *= Math.pow(0.15, dt);
      /* sonar while listening, ripples while speaking */
      if (st === 'listening') { sonAcc += dt * (0.6 + mic * 2.5); if (sonAcc > 1) { sonAcc = 0; const k = son.indexOf(-1); if (k >= 0) son[k] = 0; } }
      for (let k = 0; k < 3; k++) if (son[k] >= 0) { son[k] += dt / 2.2; if (son[k] >= 1) son[k] = -1; }
      if (st === 'speaking' && vib > 0.55 && wave < 0 && t - lastWave > 0.6) { wave = 0; lastWave = t; }
      if (wave >= 0) { wave += dt / 1.5; if (wave >= 1) wave = -1; }
      /* thinking: HQ plots a line to each post in turn */
      if (st === 'thinking') { scanT -= dt; if (scanT <= 0) { scanT = 0.45; scan = (scan + 1) % NA; dispatch(scan, 0.12, 0.45, true); } }
      for (let i = 0; i < NA; i++) { ev[i] *= Math.pow(0.4, dt); let b = 0.05;
        if (st === 'listening' && i === SKAB_I.ear) b = 0.4 + mic; if (st === 'speaking' && i === SKAB_I.voice) b = 0.4 + vib * 0.6; if (st === 'thinking' && i === scan) b = 0.45;
        ma[i] = skEase(ma[i], Math.max(b, ev[i]), 0.03, dt); if (mf[i] >= 0) { mf[i] += dt / 1.4; if (mf[i] >= 1) mf[i] = -1; }
        const P = MK[i], hgt = 0.05 + ma[i] * 0.28, c = mix3(AC[i], WHITE, 0.2);
        pinV.set([P[0], P[1], P[2], P[0], P[1] + hgt, P[2]], i * 6); pinA[i * 2] = 0.15 + ma[i] * 0.6; pinA[i * 2 + 1] = 0.6 + ma[i] * 0.4; pinC.set([...c, ...c], i * 6);
        const S = posts[i]; S.dia.position.set(P[0], P[1] + hgt, P[2]); S.dia.scale.setScalar(0.04 + ma[i] * 0.03); S.dia.material.opacity = (0.5 + ma[i] * 0.5) * fade * U.uGain.value;
        S.lab.position.set(P[0], P[1] + hgt + 0.08, P[2]); S.lab.material.opacity = (0.35 + ma[i] * 0.8) * fade * U.uGain.value; }
      pg.attributes.position.needsUpdate = pg.attributes.aA.needsUpdate = pg.attributes.aCol.needsUpdate = true;
      /* flights */
      arA.fill(0);
      flights.forEach((f, n) => { f.t += dt / f.dur; const P = MK[f.i], head = Math.min(1, f.t), fd = f.t > 1 ? Math.max(0, 1 - (f.t - 1) / 0.6) : 1;
        if (f.t >= 1 && !f.hit) { f.hit = true; if (!f.plot) { mf[f.i] = 0; ev[f.i] = 1; } }
        for (let q = 0; q < ARN; q++) { const u = q / (ARN - 1); if (u > head) break; const x = P[0] * u, z = P[2] * u, y = P[1] * u + Math.sin(Math.PI * u) * f.lift, o = n * ARN + q;
          arP.set([x, y + 0.01, z], o * 3); arC.set(f.col, o * 3); arA[o] = (f.plot ? 0.25 : 0.4) * fd + Math.exp(-Math.pow((u - head) / 0.04, 2)) * fd * (f.t < 1 ? 1 : 0); } });
      for (let n = flights.length - 1; n >= 0; n--) if (flights[n].t > 1.6) flights.splice(n, 1);
      ag.attributes.position.needsUpdate = ag.attributes.aA.needsUpdate = ag.attributes.aCol.needsUpdate = true;
      /* HQ */
      const beat = st === 'speaking' ? vib : st === 'listening' ? mic : st === 'thinking' ? 0.5 + 0.5 * Math.sin(t * 8) : 0.2 + 0.1 * Math.sin(t * 2);
      hqGlow.position.set(0, 0.3, 0); hqGlow.scale.setScalar(0.12 + beat * 0.08); hqGlow.material.opacity = 0.45 * fade * U.uGain.value; hqLab.material.opacity = 0.8 * fade * U.uGain.value;
      compass.forEach(s => s.material.opacity = 0.5 * fade * U.uGain.value);
      /* sweep */
      sweepSp = skEase(sweepSp, st === 'thinking' ? 3.2 : st === 'listening' ? 1.0 : 0.6, 0.2, dt); sweep += dt * sweepSp; sweepL.rotation.y = -sweep;
      energy = skEase(energy, st === 'thinking' ? 1.2 : st === 'idle' ? 0.45 + 0.1 * Math.sin(t * TAU / 6) : 0.9, 0.2, dt);
      /* view */
      ctl.step(dt); grp.scale.setScalar(ctl.zoom); FU.uZoomP.value = Math.sqrt(ctl.zoom);
      tilt.rotation.set(0.62 + ctl.pitch, ctl.yaw, 0, 'XYZ'); table.rotation.y = t * 0.025;
      FU.uTime.value = t; FU.uFade.value = fade * U.uGain.value; FU.uSweep.value = ((sweep % TAU) + TAU) % TAU; FU.uWave.value = wave; FU.uEnergy.value = energy;
      grp.visible = U.uGain.value > 0.02;
      skQuiet(0.93);
    },
    dispose() { ctl.dispose(); scene.remove(grp); grp.traverse(o => { if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); }); texs.forEach(x => x.dispose()); }
  };
};
/* core:spider>> */
