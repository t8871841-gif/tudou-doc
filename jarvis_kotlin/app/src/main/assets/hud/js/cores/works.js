'use strict';
/* <<core:works */
/* 织造 · Jarvis core (Claude, 2026-10-29). Rewritten for the Jarvis scene after studying the LOOM theme (looms weaving a model
   out of light threads, section by section). Here Jarvis weaves its answer: a loom ring above carries one spindle per ability,
   light threads run down from the spindles to a round fabric below, and the fabric is the answer being made.
   idle = the last fabric turns slowly, threads hang slack; listening = the fabric unravels and fresh warp is strung, the
   hearing spindle feeds it with the mic level; thinking = shuttles fly and the weave grows outward from the centre;
   an ability put to use = its spindle lights and a band of its colour is woven in, so the finished cloth shows what went
   into the answer; speaking = the weave completes and the cloth pulses outward with the voice. */
SKCORES.works = function () {
  const TAU = Math.PI * 2, lite = typeof small !== 'undefined' && small;
  const grp = new THREE.Group(); scene.add(grp);
  const tilt = new THREE.Group(); grp.add(tilt);
  const loom = new THREE.Group(); tilt.add(loom);
  const rng = skSeeded(20261031), lerp = (a, b, t) => a + (b - a) * t, clamp = (v, a, b) => v < a ? a : v > b ? b : v;
  const mix3 = (a, b, t) => [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)];
  const NA = SKAB.length, AC = SKAB.map(a => skHex(a.c)), WHITE = [1, 1, 1];
  const PRI = (typeof SKC !== 'undefined' && SKC.pri ? SKC.pri : '156,208,255').split(',').map(v => +v / 255), ACC = skHex('#ffb98a');
  const RING_Y = 0.78, RING_R = 0.98, FAB_Y = -0.52, FAB_R = 0.9, NR = 44, NW = lite ? 120 : 168, SUB = 4;
  /* band colours of the weave, one texel per weft ring; written as abilities are used */
  const bandD = new Uint8Array(NR * 4), bandT = new THREE.DataTexture(bandD, NR, 1, THREE.RGBAFormat); bandT.needsUpdate = true;
  const setBand = (i, c, k) => { const o = i * 4; bandD[o] = c[0] * 255; bandD[o + 1] = c[1] * 255; bandD[o + 2] = c[2] * 255; bandD[o + 3] = k * 255; bandT.needsUpdate = true; };
  const FU = { uTime: { value: 0 }, uPixel: U.uPixel, uFade: { value: 0 }, uZoomP: { value: 1 }, uWeave: { value: 0.85 }, uSpin: { value: 0 }, uBand: { value: bandT },
    uPri: { value: new THREE.Vector3(...PRI) }, uAcc: { value: new THREE.Vector3(...ACC) }, uSp: { value: new Float32Array(NA) }, uFlow: { value: 0 },
    uWarp: { value: 0.3 }, uVib: { value: 0 }, uPulse: { value: -1 }, uGlow: { value: 0.5 } };

  /* ---- 1. the fabric: weft rings crossing warp spokes in a twill, woven out to uWeave ---- */
  { const N = NR * NW * SUB, a = new Float32Array(N * 3), pos = new Float32Array(N * 3); let n = 0;
    for (let i = 0; i < NR; i++) for (let j = 0; j < NW; j++) for (let s = 0; s < SUB; s++, n++) a.set([i, j + s / SUB, rng()], n * 3);
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(pos, 3)); g.setAttribute('aW', new THREE.BufferAttribute(a, 3));
    g.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 3);
    const fab = new THREE.Points(g, skAdd(FU, `uniform float uTime,uPixel,uFade,uZoomP,uWeave,uSpin,uVib,uPulse,uGlow; uniform sampler2D uBand; uniform vec3 uPri,uAcc; attribute vec3 aW; varying vec3 vC; varying float vA;
        void main(){ float i=aW.x, j=aW.y, rf=(i+1.0)/${NR}.0, r=${FAB_R.toFixed(2)}*(0.08+0.92*rf);
          float ang=j/${NW}.0*6.28318+uSpin*(1.0+(1.0-rf)*0.6);
          float over=sin(j*3.14159+i*3.14159);                                   /* over / under the warp */
          float pr=uPulse>=0.0?exp(-pow((rf-uPulse)/0.06,2.0))*(1.0-uPulse):0.0;
          vec3 p=vec3(cos(ang)*r,0.012*over+pr*0.05+uVib*0.02*sin(rf*30.0-uTime*12.0),sin(ang)*r);
          vec4 mv=modelViewMatrix*vec4(p,1.0); gl_Position=projectionMatrix*mv;
          float woven=smoothstep(rf-0.004,rf+0.004,uWeave), front=exp(-pow((rf-uWeave)/0.025,2.0))*step(uWeave,0.995);
          float tw=mod(floor(j)+i,4.0)<2.0?1.0:0.55;                                  /* twill diagonal */
          vec4 b=texture2D(uBand,vec2((i+0.5)/${NR}.0,0.5));
          vec3 c=mix(mix(uPri,vec3(1.0),0.35),b.rgb,b.a);
          gl_PointSize=(1.6+front*2.5+b.a*0.6)*uPixel*(6.4/-mv.z)*uZoomP;
          vC=mix(c,vec3(1.0),front*0.6+pr*0.5); vA=(woven*(0.2+0.16*tw+b.a*0.18)*(0.7+uGlow*0.6)+front*0.7+pr*0.7*woven)*uFade*(0.85+0.15*sin(uTime*2.0+aW.z*40.0)); }`, SK_SOFT_F));
    fab.renderOrder = 901; loom.add(fab); fab.position.y = FAB_Y; }
  /* warp spokes: strung while listening, then woven over */
  { const v = [], o = []; for (let j = 0; j < NW; j += 2) { const a = j / NW * TAU; v.push(Math.cos(a) * 0.07, 0, Math.sin(a) * 0.07, Math.cos(a) * FAB_R * 1.02, 0, Math.sin(a) * FAB_R * 1.02); o.push(0, 1); }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(v, 3)); g.setAttribute('aO', new THREE.Float32BufferAttribute(o, 1));
    const warp = new THREE.LineSegments(g, skAdd(FU, `attribute float aO; varying float vO; void main(){ vO=aO; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
      `uniform float uFade,uWarp,uWeave; uniform vec3 uPri; varying float vO; void main(){ float a=uWarp*(0.25+0.75*smoothstep(uWeave-0.05,uWeave+0.1,vO)); gl_FragColor=vec4(mix(uPri,vec3(1.0),0.3)*a*0.22*uFade,1.0); }`));
    warp.renderOrder = 900; warp.position.y = FAB_Y; loom.add(warp); }
  /* the fabric's rim and a fine vernier under it */
  const lineMat = skAdd(FU, `attribute float aO; varying float vO; void main(){ vO=aO; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
    `uniform float uFade; uniform vec3 uPri; varying float vO; void main(){ gl_FragColor=vec4(mix(uPri,vec3(1.0),0.3)*vO*uFade,1.0); }`);
  const lineSet = (fn) => { const v = [], o = []; fn((x0, y0, z0, x1, y1, z1, a) => { v.push(x0, y0, z0, x1, y1, z1); o.push(a, a); });
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(v, 3)); g.setAttribute('aO', new THREE.Float32BufferAttribute(o, 1));
    const L = new THREE.LineSegments(g, lineMat); L.renderOrder = 902; return L; };
  const circ = (r, y, a, n = 256, gap = 0) => (add) => { for (let i = 0; i < n; i++) { if (gap && i % gap >= gap / 2) continue; const u0 = i / n * TAU, u1 = (i + 1) / n * TAU;
    add(Math.cos(u0) * r, y, Math.sin(u0) * r, Math.cos(u1) * r, y, Math.sin(u1) * r, a); } };
  const ticks = (r, y, n, l, a, every, la) => (add) => { for (let i = 0; i < n; i++) { const u = i / n * TAU, big = i % every === 0, ll = big ? l * 2.2 : l;
    add(Math.cos(u) * r, y, Math.sin(u) * r, Math.cos(u) * (r + ll), y, Math.sin(u) * (r + ll), big ? la : a); } };
  const fabRim = lineSet((add) => { circ(FAB_R * 1.03, 0, 0.3)(add); circ(FAB_R * 1.07, 0, 0.12, 180, 6)(add); ticks(FAB_R * 1.1, 0, 240, 0.02, 0.12, 10, 0.3)(add); });
  fabRim.position.y = FAB_Y; loom.add(fabRim);

  /* ---- 2. the loom ring above: one spindle per ability, named, with a vernier ---- */
  const ringL = lineSet((add) => { circ(RING_R, 0, 0.35)(add); circ(RING_R * 0.97, 0, 0.12, 360, 4)(add); ticks(RING_R * 1.02, 0, 360, 0.012, 0.1, 30, 0.28)(add); });
  ringL.position.y = RING_Y; loom.add(ringL);
  const spAng = (k) => -Math.PI / 2 + k / NA * TAU;
  const GLOWT = (() => { const cv = document.createElement('canvas'); cv.width = cv.height = 64; const x = cv.getContext('2d'), g = x.createRadialGradient(32, 32, 0, 32, 32, 32);
    g.addColorStop(0, 'rgba(255,255,255,1)'); g.addColorStop(0.25, 'rgba(255,255,255,.45)'); g.addColorStop(1, 'rgba(255,255,255,0)'); x.fillStyle = g; x.fillRect(0, 0, 64, 64); return new THREE.CanvasTexture(cv); })();
  const spr = (map, ro) => { const s = new THREE.Sprite(new THREE.SpriteMaterial({ map, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false })); s.renderOrder = ro; loom.add(s); return s; };
  const spindles = SKAB.map((ab, k) => { const a = spAng(k), x = Math.cos(a) * RING_R, z = Math.sin(a) * RING_R;
    const glow = spr(GLOWT, 904); glow.position.set(x, RING_Y, z); glow.material.color.setRGB(...AC[k]);
    const lt = skLabelTex(ab.zh, ab.en), lab = spr(lt, 905); lab.position.set(x * 1.16, RING_Y + 0.16, z * 1.16); lab.scale.set(0.38, 0.145, 1); lab.material.color.setRGB(...mix3(AC[k], WHITE, 0.4));
    return { glow, lab, lt, x, z }; });
  const spBars = lineSet((add) => SKAB.forEach((_, k) => { const a = spAng(k), x = Math.cos(a) * RING_R, z = Math.sin(a) * RING_R;
    add(x, -0.07, z, x, 0.07, z, 0.5); add(x * 0.985, 0.07, z * 0.985, x * 1.015, 0.07, z * 1.015, 0.4); add(x * 0.985, -0.07, z * 0.985, x * 1.015, -0.07, z * 1.015, 0.4); }));
  spBars.position.y = RING_Y; loom.add(spBars);

  /* ---- 3. threads: three per spindle, sagging down to the fabric's rim; light runs down the busy ones ---- */
  { const TPS = 3, PTS = 46, N = NA * TPS * PTS, pos = new Float32Array(N * 3), at = new Float32Array(N * 3); let n = 0;
    for (let k = 0; k < NA; k++) for (let q = 0; q < TPS; q++) { const a0 = spAng(k) + (q - 1) * 0.02, a1 = spAng(k) + (q - 1) * 0.16 + 0.35 + rng() * 0.3, sag = 0.12 + rng() * 0.18, ph = rng();
      const p0 = [Math.cos(a0) * RING_R, RING_Y, Math.sin(a0) * RING_R], p2 = [Math.cos(a1) * FAB_R, FAB_Y, Math.sin(a1) * FAB_R];
      const p1 = [(p0[0] + p2[0]) / 2 * (1 - sag), (p0[1] + p2[1]) / 2, (p0[2] + p2[2]) / 2 * (1 - sag)];
      for (let i = 0; i < PTS; i++, n++) { const u = i / (PTS - 1), v = 1 - u; pos.set([v * v * p0[0] + 2 * v * u * p1[0] + u * u * p2[0], v * v * p0[1] + 2 * v * u * p1[1] + u * u * p2[1], v * v * p0[2] + 2 * v * u * p1[2] + u * u * p2[2]], n * 3); at.set([k, u, ph], n * 3); } }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(pos, 3)); g.setAttribute('aT', new THREE.BufferAttribute(at, 3));
    const idx = []; for (let s = 0; s < NA * TPS; s++) for (let i = 0; i < PTS - 1; i++) idx.push(s * PTS + i, s * PTS + i + 1);
    const gl2 = g.clone(); gl2.setIndex(idx);
    const COLS = new Float32Array(NA * 3); AC.forEach((c, k) => COLS.set(c, k * 3));
    const TV = (pts) => `uniform float uTime,uPixel,uFade,uZoomP,uFlow; uniform float uSp[${NA}]; uniform vec3 uCol[${NA}]; uniform vec3 uPri; attribute vec3 aT; varying vec3 vC; varying float vA;
      void main(){ int k=int(aT.x+0.5); float s=uSp[k], u=aT.y; vec3 p=position; p.x+=sin(uTime*0.9+aT.z*20.0+u*3.0)*0.006*(1.0-s); p.z+=cos(uTime*0.8+aT.z*17.0)*0.006*(1.0-s);
        vec4 mv=modelViewMatrix*vec4(p,1.0); gl_Position=projectionMatrix*mv;
        float run=pow(0.5+0.5*sin(u*22.0-uFlow*(3.0+aT.z*2.0)+aT.z*30.0),10.0)*s;
        vC=mix(mix(uPri,vec3(1.0),0.3),uCol[k],0.3+s*0.7); vC=mix(vC,vec3(1.0),run*0.5);
        ${pts ? 'gl_PointSize=(1.5+run*5.0)*uPixel*(6.4/-mv.z)*uZoomP; vA=(run*0.9)*uFade;' : 'vA=(0.05+s*0.35+run*0.5)*uFade;'} }`;
    const U2 = Object.assign({}, FU, { uCol: { value: AC.map(c => new THREE.Vector3(...c)) } });
    const tl = new THREE.LineSegments(gl2, skAdd(U2, TV(false), `varying vec3 vC; varying float vA; void main(){ gl_FragColor=vec4(vC*vA,1.0); }`)); tl.renderOrder = 902;
    const tp = new THREE.Points(g, skAdd(U2, TV(true), SK_SOFT_F)); tp.renderOrder = 903; loom.add(tl, tp); }

  /* ---- 4. shuttles at the weave front, a soft core glow, slow dust ---- */
  const shuttles = [0, 1, 2].map(() => spr(GLOWT, 906));
  const hub = spr(GLOWT, 900); hub.position.y = FAB_Y; hub.scale.setScalar(0.5);
  { const N = lite ? 400 : 900, v = []; for (let i = 0; i < N; i++) { const a = rng() * TAU, r = 0.2 + Math.pow(rng(), 0.6) * 1.8; v.push(Math.cos(a) * r, -1 + rng() * 2.2, Math.sin(a) * r); }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(v, 3));
    const d = new THREE.Points(g, skAdd(FU, `uniform float uTime,uPixel,uFade,uZoomP; uniform vec3 uPri; varying vec3 vC; varying float vA; void main(){ vec3 p=position; p.y=mod(p.y+uTime*0.03+1.0,2.2)-1.0;
        vec4 mv=modelViewMatrix*vec4(p,1.0); gl_Position=projectionMatrix*mv; gl_PointSize=1.6*uPixel*(6.4/-mv.z)*uZoomP; vC=mix(uPri,vec3(1.0),0.5); vA=0.28*uFade*smoothstep(1.2,0.6,abs(p.y-0.1)); }`, SK_SOFT_F));
    d.renderOrder = 898; loom.add(d); }

  /* ---- what Jarvis is doing -> the weave ---- */
  const sp = FU.uSp.value, ev = new Float32Array(NA);
  let weave = 0.85, mic = 0, unravel = false, pulse = -1, lastPulse = 0, used = 0;
  [[10, 'weather'], [22, 'plan'], [34, 'holo']].forEach(([i, id]) => { for (let q = 0; q < 3; q++) setBand(i + q, AC[SKAB_I[id]], 0.9); });   /* the last cloth, until a new one is woven */
  const weaveBand = (k) => { const f = Math.max(0, Math.floor(weave * NR) - 1); for (let i = f; i < Math.min(NR, f + 3); i++) setBand(i, AC[k], 0.9); used++; };
  const onMsg = (m) => { const e = skEvent(m); if (!e) return;
    if (e.k === 'wake') { ev[SKAB_I.ear] = 1; }
    else if (e.k === 'heard') { ev[SKAB_I.ear] = 1; }
    else if (e.k === 'reply') { ev[SKAB_I.voice] = 1; }
    else if (e.k === 'level') mic = Math.max(mic, e.v);
    else if (e.k === 'sys') ev[SKAB_I.sys] = Math.max(ev[SKAB_I.sys], 0.35);
    else if (e.k === 'use') { ev[e.i] = 1; weaveBand(e.i); } };

  /* ---- motion ---- */
  const ctl = skControl();
  let t = 0, fade = 0, flow = 0, spin = 0, spinSp = 0.05, prevSt = 'idle', warpA = 0.3, glow = 0.5;
  return {
    msg: onMsg,
    frame(dt) {
      dt = Math.min(dt, 0.1); const st = typeof state === 'string' ? state : 'idle', vib = st === 'speaking' ? U.uVib.value : 0;
      t += dt; fade = Math.min(1, fade + dt / 0.9); mic *= Math.pow(0.15, dt);
      if (st !== prevSt) { if (st === 'listening' && prevSt !== 'listening') unravel = true; prevSt = st; }
      /* the weave */
      if (unravel) { weave = Math.max(0, weave - dt * 1.4); if (weave <= 0) { unravel = false; for (let i = 0; i < NR; i++) setBand(i, PRI, 0); used = 0; } }
      else if (st === 'thinking') weave += (0.82 - weave) * (1 - Math.pow(0.55, dt)) + dt * 0.01;
      else if (st === 'speaking') weave = Math.min(1, weave + dt * 0.35);
      else if (st === 'idle' && weave < 1 && weave > 0.05) weave = Math.min(1, weave + dt * 0.25);
      if (st === 'listening' && !unravel) weave = Math.min(0.06, weave + dt * 0.02);
      if (st === 'speaking' && vib > 0.55 && pulse < 0 && t - lastPulse > 0.7) { pulse = 0; lastPulse = t; }
      if (pulse >= 0) { pulse += dt / 1.2; if (pulse >= 1) pulse = -1; }
      /* spindles */
      for (let k = 0; k < NA; k++) { ev[k] *= Math.pow(0.35, dt); let b = 0.06;
        if (st === 'listening' && k === SKAB_I.ear) b = 0.4 + mic;
        if (st === 'speaking' && k === SKAB_I.voice) b = 0.45 + vib * 0.6;
        if (st === 'thinking') b = Math.max(b, 0.25 * Math.max(0, Math.sin(t * 3 - k * 0.9)));
        sp[k] = skEase(sp[k], Math.max(b, ev[k]), 0.03, dt);
        const S = spindles[k]; S.glow.scale.setScalar(0.07 + sp[k] * 0.12); S.glow.material.opacity = (0.35 + sp[k]) * fade * U.uGain.value; S.lab.material.opacity = (0.3 + sp[k] * 0.9) * fade * U.uGain.value; }
      warpA = skEase(warpA, st === 'listening' ? 1 : 0.3, 0.1, dt); glow = skEase(glow, st === 'speaking' ? 0.9 + vib * 0.5 : st === 'thinking' ? 0.7 : 0.5, 0.1, dt);
      spinSp = skEase(spinSp, st === 'thinking' ? 0.5 : st === 'speaking' ? 0.18 : 0.05, 0.2, dt); spin += dt * spinSp; flow += dt * (1 + (st === 'thinking' ? 2 : 0) + vib * 2);
      /* shuttles run along the weave front while thinking */
      const front = FAB_R * (0.08 + 0.92 * weave), on = st === 'thinking' ? 1 : 0;
      shuttles.forEach((s, i) => { const a = t * 2.6 + i * TAU / 3; s.position.set(Math.cos(a) * front, FAB_Y + 0.02, Math.sin(a) * front); s.scale.setScalar(0.12);
        s.material.opacity = skEase(s.material.opacity, on * 0.9, 0.05, dt) * fade * U.uGain.value; s.material.color.setRGB(...mix3(PRI, WHITE, 0.5)); });
      hub.material.color.setRGB(...mix3(PRI, WHITE, 0.4)); hub.material.opacity = (0.15 + glow * 0.2 + vib * 0.25) * fade * U.uGain.value;
      /* view: looking down at the loom from the side, slowly turning, plus drag / palm */
      ctl.step(dt); grp.scale.setScalar(ctl.zoom); FU.uZoomP.value = Math.sqrt(ctl.zoom);
      tilt.rotation.set(0.42 + ctl.pitch, ctl.yaw, 0, 'XYZ'); loom.rotation.y = t * 0.04;
      ringL.rotation.y = -t * 0.03; fabRim.rotation.y = t * 0.02;
      Object.assign(FU.uWeave, { value: weave }); FU.uSpin.value = spin; FU.uTime.value = t; FU.uFlow.value = flow; FU.uFade.value = fade * U.uGain.value;
      FU.uWarp.value = warpA; FU.uVib.value = vib; FU.uPulse.value = pulse; FU.uGlow.value = glow;
      grp.visible = U.uGain.value > 0.02;
      skQuiet(0.93);
    },
    dispose() { ctl.dispose(); scene.remove(grp); grp.traverse(o => { if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); });
      [bandT, GLOWT, ...spindles.map(s => s.lt)].forEach(x => x.dispose()); }
  };
};
/* core:works>> */
