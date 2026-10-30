'use strict';
/* <<core:factory */
/* 工坊 · Jarvis core (Claude, 2026-10-29). Rewritten for the Jarvis scene after studying the FACTORY theme (a robot workshop
   building machines). Here the workshop builds Jarvis itself: a mechanical core of armour plates round a glowing reactor, on a
   platform, with four robot arms. The thirteen plates of the equator are Jarvis's abilities, each named on its plate.
   idle = the core turns, plates shut, arms parked, the reactor breathes; listening = the equator plates lift a little in a
   wave, the hearing plate glows and the reactor pulses with the mic; thinking = the plate rings turn against each other and
   the arms weld at the core, sparks flying; an ability put to use = its plate swings out, the arms converge on it and weld it
   back in its colour; speaking = the plates breathe out with each syllable and the voice plate glows. */
SKCORES.factory = function () {
  const TAU = Math.PI * 2, lite = typeof small !== 'undefined' && small;
  const grp = new THREE.Group(); scene.add(grp);
  const tilt = new THREE.Group(); grp.add(tilt);
  const rig = new THREE.Group(); rig.position.y = 0.08; tilt.add(rig);
  const rng = skSeeded(20261101), lerp = (a, b, t) => a + (b - a) * t, clamp = (v, a, b) => v < a ? a : v > b ? b : v;
  const mix3 = (a, b, t) => [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)];
  const NA = SKAB.length, AC = SKAB.map(a => skHex(a.c)), WHITE = [1, 1, 1];
  const PRI = (typeof SKC !== 'undefined' && SKC.pri ? SKC.pri : '255,194,26').split(',').map(v => +v / 255), STEEL = [0.55, 0.78, 0.95], HOT = [1, 0.62, 0.22];
  const R = 0.5, FLOOR = -0.78;

  /* ---- 1. the armour: three bands of plates (8 / 13 / 8); the equator plates are the abilities ---- */
  const BANDS = [{ n: 8, la0: 0.24, la1: 0.62 }, { n: NA, la0: -0.2, la1: 0.2 }, { n: 8, la0: -0.62, la1: -0.24 }];
  const PL = []; BANDS.forEach((b, bi) => { for (let i = 0; i < b.n; i++) PL.push({ b: bi, lo0: i / b.n * TAU + 0.025, lo1: (i + 1) / b.n * TAU - 0.025, la0: b.la0, la1: b.la1, ab: bi === 1 ? i : -1 }); });
  const NP = PL.length;
  const FU = { uTime: { value: 0 }, uPixel: U.uPixel, uFade: { value: 0 }, uZoomP: { value: 1 }, uOpen: { value: new Float32Array(NP) }, uGlowP: { value: new Float32Array(NP) },
    uBandRot: { value: new Float32Array(3) }, uR: { value: R }, uPri: { value: new THREE.Vector3(...PRI) }, uReact: { value: 0.5 }, uVib: { value: 0 } };
  const dir = (lo, la) => [Math.cos(la) * Math.cos(lo), Math.sin(la), Math.cos(la) * Math.sin(lo)];
  const LV = [], LA = [], LC = [], PV = [], PA = [], PC = [];
  PL.forEach((p, k) => { const col = p.ab >= 0 ? mix3(AC[p.ab], STEEL, 0.55) : STEEL;
    const seg = (a, b, w) => { LV.push(...a, ...b); LA.push(k, p.b, w, k, p.b, w); LC.push(...col, ...col); };
    const edge = (f, n, w, rivets) => { for (let i = 0; i < n; i++) { const a = f(i / n), b = f((i + 1) / n); seg(a, b, w); if (rivets && i % 2 === 0) { PV.push(...a); PA.push(k, p.b, 1.3); PC.push(...col); } } };
    const at = (u, v, inset = 0) => dir(lerp(p.lo0 + inset, p.lo1 - inset, u), lerp(p.la0 + inset * 0.8, p.la1 - inset * 0.8, v));
    edge(u => at(u, 0), 12, 1, true); edge(u => at(u, 1), 12, 1, true); edge(v => at(0, v), 6, 1, true); edge(v => at(1, v), 6, 1, true);
    const ins = (p.lo1 - p.lo0) * 0.14;
    edge(u => at(u, 0, ins), 10, 0.45); edge(u => at(u, 1, ins), 10, 0.45); edge(v => at(0, v, ins), 5, 0.45); edge(v => at(1, v, ins), 5, 0.45);
    for (let s = 1; s < 3; s++) edge(u => at(u, s / 3, ins), 10, 0.22);             /* panel seams */
    for (let i = 0; i < (lite ? 30 : 70); i++) { PV.push(...at(rng(), rng(), ins)); PA.push(k, p.b, 0.55); PC.push(...col); } });   /* surface grain */
  const PLATE_V = (pts) => `uniform float uTime,uPixel,uFade,uZoomP,uR,uVib; uniform float uOpen[${NP}]; uniform float uGlowP[${NP}]; uniform float uBandRot[3];
    attribute vec3 aP,aCol; varying vec3 vC; varying float vA;
    void main(){ int k=int(aP.x+0.5), b=int(aP.y+0.5); float r=uBandRot[b], c=cos(r), s=sin(r);
      vec3 d=vec3(position.x*c-position.z*s,position.y,position.x*s+position.z*c);
      float o=uOpen[k], gl=uGlowP[k]; vec3 p=d*uR*(1.0+o);
      vec4 mv=modelViewMatrix*vec4(p,1.0); gl_Position=projectionMatrix*mv;
      float face=0.45+0.55*clamp(-normalize(mv.xyz).z*0.0+dot(normalize((modelViewMatrix*vec4(d,0.0)).xyz),vec3(0.0,0.0,1.0))*0.8+0.4,0.0,1.0);
      vC=mix(aCol,vec3(1.0),gl*0.35);
      ${pts ? 'gl_PointSize=(1.2+aP.z*1.4)*uPixel*(6.4/-mv.z)*uZoomP*(1.0+gl*0.5); vA=(0.18+gl*0.7)*face*uFade*(aP.z>1.0?1.6:0.7);' : 'vA=(0.1+aP.z*0.32+gl*0.8*aP.z)*face*uFade;'} }`;
  const plg = new THREE.BufferGeometry(); plg.setAttribute('position', new THREE.Float32BufferAttribute(LV, 3)); plg.setAttribute('aP', new THREE.Float32BufferAttribute(LA, 3)); plg.setAttribute('aCol', new THREE.Float32BufferAttribute(LC, 3));
  const ppg = new THREE.BufferGeometry(); ppg.setAttribute('position', new THREE.Float32BufferAttribute(PV, 3)); ppg.setAttribute('aP', new THREE.Float32BufferAttribute(PA, 3)); ppg.setAttribute('aCol', new THREE.Float32BufferAttribute(PC, 3));
  [plg, ppg].forEach(g => g.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 3));
  const plates = new THREE.LineSegments(plg, skAdd(FU, PLATE_V(false), `varying vec3 vC; varying float vA; void main(){ gl_FragColor=vec4(vC*vA,1.0); }`)); plates.renderOrder = 902;
  const rivets = new THREE.Points(ppg, skAdd(FU, PLATE_V(true), SK_SOFT_F)); rivets.renderOrder = 903; rig.add(plates, rivets);
  /* the ability names, floating just off their plates (the far side fades) */
  const labels = SKAB.map((ab, i) => { const tx = skLabelTex(ab.zh, ab.en); const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: tx, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false }));
    s.scale.set(0.34, 0.13, 1); s.renderOrder = 906; s.material.color.setRGB(...mix3(AC[i], WHITE, 0.45)); rig.add(s); return { s, tx }; });

  /* ---- 2. the reactor: a dense glowing ball, three gyroscope rings ---- */
  { const N = lite ? 1400 : 3200, v = [], r = []; for (let i = 0; i < N; i++) { const u = rng() * 2 - 1, a = rng() * TAU, rr = 0.27 * Math.pow(rng(), 0.35), q = Math.sqrt(1 - u * u);
      v.push(Math.cos(a) * q * rr, u * rr, Math.sin(a) * q * rr); r.push(rng()); }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(v, 3)); g.setAttribute('aR', new THREE.Float32BufferAttribute(r, 1));
    const m = new THREE.Points(g, skAdd(FU, `uniform float uTime,uPixel,uFade,uZoomP,uReact; uniform vec3 uPri; attribute float aR; varying vec3 vC; varying float vA;
        void main(){ float l=length(position); vec3 p=position*(1.0+0.06*uReact*sin(uTime*6.0+aR*30.0)); vec4 mv=modelViewMatrix*vec4(p,1.0); gl_Position=projectionMatrix*mv;
          gl_PointSize=(1.2+aR*1.6)*uPixel*(6.4/-mv.z)*uZoomP; vC=mix(vec3(1.0),uPri,smoothstep(0.05,0.27,l)); vA=(0.12+uReact*0.25)*(0.6+0.4*sin(uTime*3.0+aR*50.0))*uFade; }`, SK_SOFT_F));
    m.renderOrder = 900; rig.add(m); }
  const lineMat = skAdd(FU, `attribute float aO; varying float vO; void main(){ vO=aO; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
    `uniform float uFade; uniform vec3 uPri; varying float vO; void main(){ gl_FragColor=vec4(mix(uPri,vec3(1.0),0.35)*vO*uFade,1.0); }`);
  const lineSet = (fn) => { const v = [], o = []; fn((x0, y0, z0, x1, y1, z1, a) => { v.push(x0, y0, z0, x1, y1, z1); o.push(a, a); });
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(v, 3)); g.setAttribute('aO', new THREE.Float32BufferAttribute(o, 1));
    const L = new THREE.LineSegments(g, lineMat); L.renderOrder = 901; return L; };
  const circ = (r, a, n = 160, dash = 0) => (add) => { for (let i = 0; i < n; i++) { if (dash && i % dash >= dash / 2) continue; const u0 = i / n * TAU, u1 = (i + 1) / n * TAU; add(Math.cos(u0) * r, 0, Math.sin(u0) * r, Math.cos(u1) * r, 0, Math.sin(u1) * r, a); } };
  const gyros = [0.34, 0.37, 0.4].map((r, i) => { const L = lineSet((add) => { circ(r, 0.4)(add); for (let k = 0; k < 24; k++) { const a = k / 24 * TAU; add(Math.cos(a) * r, 0, Math.sin(a) * r, Math.cos(a) * (r - 0.015), 0, Math.sin(a) * (r - 0.015), 0.5); } });
    const h = new THREE.Group(); h.add(L); h.rotation.set(i * 1.1 + 0.3, 0, i * 0.7); rig.add(h); return h; });

  /* ---- 3. the platform: rings, hazard band, radial ticks ---- */
  const floor = lineSet((add) => { circ(1.05, 0.35)(add); circ(1.0, 0.15, 200, 4)(add); circ(0.72, 0.2)(add); circ(0.3, 0.25)(add);
    for (let i = 0; i < 180; i++) { const a = i / 180 * TAU, l = i % 15 === 0 ? 0.06 : 0.025; add(Math.cos(a) * 1.05, 0, Math.sin(a) * 1.05, Math.cos(a) * (1.05 + l), 0, Math.sin(a) * (1.05 + l), i % 15 === 0 ? 0.4 : 0.15); }
    for (let i = 0; i < 48; i++) { const a = i / 48 * TAU, b = a + 0.05; add(Math.cos(a) * 0.86, 0, Math.sin(a) * 0.86, Math.cos(b) * 0.92, 0, Math.sin(b) * 0.92, 0.22); }
    for (let i = 0; i < 12; i++) { const a = i / 12 * TAU; add(Math.cos(a) * 0.3, 0, Math.sin(a) * 0.3, Math.cos(a) * 0.72, 0, Math.sin(a) * 0.72, 0.08); } });
  floor.position.y = FLOOR; rig.add(floor);
  const beam = lineSet((add) => { for (let i = 0; i < 24; i++) { const a = i / 24 * TAU; add(Math.cos(a) * 0.3, 0, Math.sin(a) * 0.3, Math.cos(a) * 0.12, -FLOOR - 0.38, Math.sin(a) * 0.12, 0.1); } });
  beam.position.y = FLOOR; rig.add(beam);

  /* ---- 4. four robot arms: base, shoulder, upper arm, forearm, welding head (two-bone IK) ---- */
  const GLOWT = (() => { const cv = document.createElement('canvas'); cv.width = cv.height = 64; const x = cv.getContext('2d'), g = x.createRadialGradient(32, 32, 0, 32, 32, 32);
    g.addColorStop(0, 'rgba(255,255,255,1)'); g.addColorStop(0.25, 'rgba(255,255,255,.45)'); g.addColorStop(1, 'rgba(255,255,255,0)'); x.fillStyle = g; x.fillRect(0, 0, 64, 64); return new THREE.CanvasTexture(cv); })();
  const armMat = new THREE.ShaderMaterial({ uniforms: { uC: { value: new THREE.Color(...mix3(PRI, WHITE, 0.3)) }, uFade: FU.uFade }, transparent: true, depthWrite: false, depthTest: false, blending: THREE.AdditiveBlending,
    vertexShader: `varying vec3 vN; varying vec3 vV; void main(){ vec4 mv=modelViewMatrix*vec4(position,1.0); vN=normalize(normalMatrix*normal); vV=normalize(-mv.xyz); gl_Position=projectionMatrix*mv; }`,
    fragmentShader: `uniform vec3 uC; uniform float uFade; varying vec3 vN; varying vec3 vV; void main(){ float f=1.0-abs(dot(vN,vV)); gl_FragColor=vec4(uC*(pow(f,2.0)*0.9+0.08)*uFade,1.0); }` });
  const cyl = new THREE.CylinderGeometry(1, 1, 1, 12, 1, true), UPV = new THREE.Vector3(0, 1, 0), tA = [0, 0, 0, 0].map(() => new THREE.Vector3());
  const setSeg = (m, a, b, r) => { const d = tA[3].subVectors(b, a), len = d.length(); m.position.addVectors(a, b).multiplyScalar(0.5); m.quaternion.setFromUnitVectors(UPV, d.divideScalar(len || 1)); m.scale.set(r, len, r); };
  const sprite = (c, ro) => { const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: GLOWT, color: c, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false })); s.renderOrder = ro; rig.add(s); return s; };
  const L1 = 0.62, L2 = 0.55, L3 = 0.12;
  const arms = [0.25, 0.75, 1.25, 1.75].map((q, i) => { const a = q * Math.PI, base = new THREE.Vector3(Math.cos(a) * 0.92, FLOOR, Math.sin(a) * 0.92);
    const segs = [0, 1, 2, 3].map(() => { const m = new THREE.Mesh(cyl, armMat); m.renderOrder = 904; m.frustumCulled = false; rig.add(m); return m; });
    const joints = [0, 1, 2].map(() => sprite(0xbfe6ff, 905)), tip = sprite(0xffd9a8, 907);
    const park = new THREE.Vector3(Math.cos(a) * 0.82, FLOOR + 0.62, Math.sin(a) * 0.82);
    return { a, base, segs, joints, tip, tgt: park.clone(), cur: park.clone(), park, weld: 0, next: rng() * 2, P: [0, 0, 0, 0, 0].map(() => new THREE.Vector3()) }; });
  const solveArm = (A) => {
    const sh = A.P[0].copy(A.base).setY(FLOOR + 0.16);                            /* shoulder on its column */
    const toC = tA[0].copy(A.cur).sub(sh), wristBack = toC.clone().normalize().multiplyScalar(L3);
    const W = A.P[3].copy(A.cur).sub(wristBack);                                    /* wrist sits L3 behind the tool tip */
    const d = tA[1].subVectors(W, sh); let dl = d.length(); d.divideScalar(dl || 1); dl = clamp(dl, 0.1, (L1 + L2) * 0.995);
    const ca = clamp((L1 * L1 + dl * dl - L2 * L2) / (2 * L1 * dl), -1, 1), sa = Math.sqrt(1 - ca * ca);
    const perp = tA[2].copy(UPV).addScaledVector(d, -UPV.dot(d)).normalize();       /* elbow up */
    const E = A.P[2].copy(sh).addScaledVector(d, ca * L1).addScaledVector(perp, sa * L1); W.copy(sh).addScaledVector(d, dl);
    A.P[1].copy(A.base); A.P[4].copy(W).add(wristBack);
    setSeg(A.segs[0], A.P[1], sh, 0.035); setSeg(A.segs[1], sh, E, 0.026); setSeg(A.segs[2], E, W, 0.02); setSeg(A.segs[3], W, A.P[4], 0.012);
    A.joints[0].position.copy(sh); A.joints[1].position.copy(E); A.joints[2].position.copy(W); A.joints.forEach(j => j.scale.setScalar(0.07));
    A.tip.position.copy(A.P[4]); };

  /* ---- 5. sparks ---- */
  const NS = 500, spP = new Float32Array(NS * 3), spA = new Float32Array(NS), spV = new Float32Array(NS * 3), spL = new Float32Array(NS); let spN = 0;
  const spg = new THREE.BufferGeometry(); spg.setAttribute('position', new THREE.BufferAttribute(spP, 3)); spg.setAttribute('aA', new THREE.BufferAttribute(spA, 1)); spg.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 4);
  const sparks = new THREE.Points(spg, skAdd(FU, `uniform float uPixel,uFade,uZoomP; attribute float aA; varying vec3 vC; varying float vA; void main(){ vec4 mv=modelViewMatrix*vec4(position,1.0); gl_Position=projectionMatrix*mv;
      gl_PointSize=(1.5+aA*2.5)*uPixel*(6.4/-mv.z)*uZoomP; vC=mix(vec3(1.0,0.45,0.12),vec3(1.0,0.95,0.8),aA); vA=aA*uFade; }`, SK_SOFT_F)); sparks.renderOrder = 908; rig.add(sparks);
  const emit = (p, n) => { for (let i = 0; i < n; i++) { const k = spN = (spN + 1) % NS; spP.set([p.x, p.y, p.z], k * 3); const a = rng() * TAU, u = rng() * 2 - 1, s = 0.3 + rng() * 0.9, q = Math.sqrt(1 - u * u);
    spV.set([Math.cos(a) * q * s, Math.abs(u) * s * 0.8 + 0.2, Math.sin(a) * q * s], k * 3); spL[k] = 0.4 + rng() * 0.6; } };

  /* ---- what Jarvis is doing ---- */
  const open = FU.uOpen.value, glowP = FU.uGlowP.value, ev = new Float32Array(NA);
  const eqK = (i) => BANDS[0].n + i;                                          /* plate index of ability i */
  let mic = 0, build = -1, buildT = 0;
  const plateCentre = (k, out) => { const p = PL[k], r = FU.uBandRot.value[p.b], lo = (p.lo0 + p.lo1) / 2, la = (p.la0 + p.la1) / 2, d = dir(lo, la), c = Math.cos(r), s = Math.sin(r);
    return out.set(d[0] * c - d[2] * s, d[1], d[0] * s + d[2] * c); };
  const onMsg = (m) => { const e = skEvent(m); if (!e) return;
    if (e.k === 'wake' || e.k === 'heard') ev[SKAB_I.ear] = 1;
    else if (e.k === 'reply') ev[SKAB_I.voice] = 1;
    else if (e.k === 'level') mic = Math.max(mic, e.v);
    else if (e.k === 'sys') ev[SKAB_I.sys] = Math.max(ev[SKAB_I.sys], 0.35);
    else if (e.k === 'use') { ev[e.i] = 1; build = e.i; buildT = 0; } };

  /* ---- motion ---- */
  const ctl = skControl();
  let t = 0, fade = 0, react = 0.5, spin = 0, rot = [0, 0, 0], rotSp = [0, 0, 0];
  const v3 = new THREE.Vector3(), cam = new THREE.Vector3();
  return {
    msg: onMsg,
    frame(dt) {
      dt = Math.min(dt, 0.1); const st = typeof state === 'string' ? state : 'idle', vib = st === 'speaking' ? U.uVib.value : 0;
      t += dt; fade = Math.min(1, fade + dt / 0.9); mic *= Math.pow(0.15, dt);
      /* plate rings turn against each other while thinking */
      const want = st === 'thinking' ? [0.5, -0.12, -0.45] : [0.04, 0.02, -0.03];
      for (let b = 0; b < 3; b++) { rotSp[b] = skEase(rotSp[b], want[b], 0.3, dt); rot[b] += rotSp[b] * dt; FU.uBandRot.value[b] = rot[b]; }
      /* building an ability's plate: out 0.5 s, arms weld 1.2 s, back in */
      if (build >= 0) { buildT += dt; if (buildT > 2.4) build = -1; }
      for (let k = 0; k < NP; k++) { const p = PL[k], ab = p.ab; let o = 0, g = 0;
        if (st === 'listening' && p.b === 1) o = 0.03 + 0.03 * Math.sin(t * 5 - k * 0.7) * (0.4 + mic);
        if (st === 'speaking') o = vib * (p.b === 1 ? 0.09 : 0.05) * (0.6 + 0.4 * Math.sin(k * 1.7));
        if (st === 'thinking') o = 0.015 * Math.sin(t * 4 + k);
        if (ab >= 0) { ev[ab] *= Math.pow(0.4, dt); g = ev[ab];
          if (st === 'listening' && ab === SKAB_I.ear) g = Math.max(g, 0.5 + mic * 0.8);
          if (st === 'speaking' && ab === SKAB_I.voice) g = Math.max(g, 0.5 + vib * 0.6);
          if (ab === build) { const u = buildT; o = u < 0.5 ? 0.4 * (u / 0.5) : u < 1.7 ? 0.4 - (u - 0.5) / 1.2 * 0.36 : Math.max(0, 0.04 - (u - 1.7) * 0.06); g = 1; } }
        open[k] = skEase(open[k], o, 0.004, dt); glowP[k] = skEase(glowP[k], g, 0.02, dt); }
      react = skEase(react, st === 'thinking' ? 1.2 : st === 'listening' ? 0.6 + mic : st === 'speaking' ? 0.6 + vib : 0.4 + 0.15 * Math.sin(t * TAU / 6), 0.1, dt);
      /* arms */
      arms.forEach((A, i) => {
        if (build >= 0 && buildT > 0.35 && buildT < 2.0) {                         /* all four on the plate being built */
          plateCentre(eqK(build), v3); const off = new THREE.Vector3(Math.cos(i * 1.7) * 0.06, Math.sin(i * 2.3) * 0.05, 0);
          A.tgt.copy(v3).multiplyScalar(R * (1 + open[eqK(build)] + 0.04)).add(off); A.weld = 1; }
        else if (st === 'thinking') { A.next -= dt; if (A.next <= 0) { A.next = 0.7 + rng() * 0.8;
            const lo = A.a + (rng() - 0.5) * 1.4, la = (rng() - 0.5) * 1.1, d = dir(lo, la); A.tgt.set(d[0], d[1], d[2]).multiplyScalar(R * 1.05); }
          A.weld = A.cur.distanceTo(A.tgt) < 0.05 ? 1 : 0; }
        else { A.tgt.copy(A.park); A.tgt.y += 0.03 * Math.sin(t * 0.8 + i); A.weld = 0; }
        A.cur.lerp(A.tgt, 1 - Math.pow(0.012, dt)); solveArm(A);
        const w = A.weld && A.cur.distanceTo(A.tgt) < 0.06;
        A.tip.scale.setScalar(w ? 0.09 + rng() * 0.08 : 0.05); A.tip.material.opacity = (w ? 1 : 0.5) * fade * U.uGain.value;
        A.joints.forEach(j => j.material.opacity = 0.6 * fade * U.uGain.value);
        if (w && rng() < dt * 40) emit(A.P[4], 3); });
      /* sparks fall */
      for (let k = 0; k < NS; k++) { if (spL[k] <= 0) { spA[k] = 0; continue; } spL[k] -= dt; spV[k * 3 + 1] -= dt * 2.2;
        spP[k * 3] += spV[k * 3] * dt; spP[k * 3 + 1] = Math.max(FLOOR, spP[k * 3 + 1] + spV[k * 3 + 1] * dt); spP[k * 3 + 2] += spV[k * 3 + 2] * dt; spA[k] = Math.max(0, spL[k]); }
      spg.attributes.position.needsUpdate = spg.attributes.aA.needsUpdate = true;
      /* labels sit off their plates; the far side fades */
      camera.getWorldPosition(cam); rig.worldToLocal(cam); cam.normalize();
      labels.forEach((L, i) => { plateCentre(eqK(i), v3); const facing = v3.dot(cam); L.s.position.copy(v3).multiplyScalar(R * (1.34 + open[eqK(i)]));
        L.s.material.opacity = Math.sqrt(Math.max(0, facing)) * (0.6 + glowP[eqK(i)] * 0.8) * fade * U.uGain.value; });
      gyros.forEach((g, i) => { g.rotation.y += dt * (0.3 + i * 0.15) * (st === 'thinking' ? 3 : 1); g.rotation.x += dt * 0.05 * (i - 1); });
      /* view */
      ctl.step(dt); grp.scale.setScalar(ctl.zoom); FU.uZoomP.value = Math.sqrt(ctl.zoom);
      spin += dt * 0.06; tilt.rotation.set(0.3 + ctl.pitch, ctl.yaw, 0, 'XYZ'); rig.rotation.y = spin;
      FU.uTime.value = t; FU.uFade.value = fade * U.uGain.value; FU.uReact.value = react; FU.uVib.value = vib;
      grp.visible = U.uGain.value > 0.02;
      skQuiet(0.93);
    },
    dispose() { ctl.dispose(); scene.remove(grp); grp.traverse(o => { if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); });
      [GLOWT, ...labels.map(l => l.tx)].forEach(x => x.dispose()); }
  };
};
/* core:factory>> */
