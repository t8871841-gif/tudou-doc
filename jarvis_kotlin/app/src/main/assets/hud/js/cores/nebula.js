'use strict';
/* <<core:nebula */
/* 星云 · Jarvis core (Claude, 2026-10-29). Rewritten for the Jarvis scene after studying the NEBULA theme: a star core of
   glowing ribbons, thirteen nebulae on three elliptical orbits round it (each one of Jarvis's abilities, named above it),
   and a nebula spider that is Jarvis's attention: wherever the spider sits is what Jarvis is attending to.
   idle = the spider rests on the star core, the nebulae drift on their orbits, system telemetry drifts in as faint comets;
   listening = the spider goes to the hearing nebula, comets of sound stream into the core with the mic level;
   thinking = the core's ribbons quicken and the spider hops from nebula to nebula, feeling each with threads;
   an ability put to use = the spider jumps onto that nebula, it flares, a comet goes out from the core and one comes back;
   speaking = the spider returns to the core, comets stream to the voice nebula, the ribbons swell with each syllable. */
SKCORES.nebula = function () {
  const TAU = Math.PI * 2, HP = Math.PI / 2, K = 0.043;                  /* theme units -> world units */
  const lite = typeof small !== 'undefined' && small;
  const grp = new THREE.Group(); scene.add(grp);
  const tilt = new THREE.Group(); grp.add(tilt);
  const world = new THREE.Group(); world.scale.setScalar(K); world.position.y = -0.08; tilt.add(world);
  const rng = skSeeded(20261029), gauss = () => { let u = 0; while (!u) u = rng(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(TAU * rng()); };
  const lerp = (a, b, t) => a + (b - a) * t, clamp = (v, a, b) => v < a ? a : v > b ? b : v;
  const mix3 = (a, b, t) => [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)];
  const WHITE = [1, 1, 1], ICE = skHex('#9fd4ff'), PEACH = skHex('#ffb48f');
  const NA = SKAB.length, AC = SKAB.map(a => skHex(a.c));
  const FU = { uTime: { value: 0 }, uFlow: { value: 0 }, uPixel: U.uPixel, uFade: { value: 0 }, uZoomP: { value: 1 }, uAf: { value: new Float32Array(NA + 2) },
    uAmpK: { value: 1 }, uVib: { value: 0 } };   /* uAf: 0..12 = the nebulae, 13 = core dust, 14 = spider */

  /* ---- layout: the star core and three elliptical orbits (3 + 4 + 6 nebulae) ---- */
  const ORB = [{ ax: 19.5, az: 11.2, n: 3, R: 4.6, T: 150 }, { ax: 30, az: 16.8, n: 4, R: 5.3, T: 230 }, { ax: 40.5, az: 22.4, n: 6, R: 5.4, T: 320 }];
  const CORE_Y = 2.2, CORE_R = 6.3;
  const CL = [];
  { let k = 0; ORB.forEach((o, oi) => { const ph = rng() * TAU; for (let j = 0; j < o.n; j++, k++) {
    const c = { orb: oi, ph: ph + j * TAU / o.n + (rng() - 0.5) * 0.3, R: o.R * (0.92 + rng() * 0.16), y0: (rng() - 0.5) * 0.9, x: 0, z: 0, cr: 1, sr: 0 };
    c.top = c.R * 0.32; CL.push(c); } }); }
  /* the spider's ability order matches the orbits, so hearing and voice sit on the inner orbit, close to the core */
  let orbT = 37;
  const orbitStep = () => CL.forEach(c => { const o = ORB[c.orb], f = c.ph + orbT * TAU / o.T;
    c.x = Math.cos(f) * o.ax; c.z = Math.sin(f) * o.az; const dx = -Math.sin(f) * o.ax, dz = Math.cos(f) * o.az, l = Math.hypot(dx, dz);
    const ang = Math.atan2(dz / l, dx / l); c.cr = Math.cos(ang); c.sr = Math.sin(ang); });
  orbitStep();
  const surfL = (c, lx, lz) => { const d = Math.hypot(lx / 1.1, lz / 0.82) / (c.R * 0.95); return c.y0 + (d < 1 ? c.top * (1 - d * d) : 0); };
  const anchorW = (a, lx, lz, o) => { if (a < 0) { o.x = lx; o.z = lz; o.y = CORE_Y + Math.sqrt(Math.max(0, CORE_R * CORE_R - lx * lx - lz * lz)); return o; }
    const c = CL[a]; o.x = c.x + lx * c.cr - lz * c.sr; o.z = c.z + lx * c.sr + lz * c.cr; o.y = surfL(c, lx, lz); return o; };
  /* the ground under any point: the core's dome, a nebula's dome, or the orbit plane */
  const surfW = (x, z) => { let y = -0.35; const dc = x * x + z * z; if (dc < CORE_R * CORE_R) y = Math.max(y, CORE_Y + Math.sqrt(CORE_R * CORE_R - dc));
    for (const c of CL) { const dx = x - c.x, dz = z - c.z; if (dx * dx + dz * dz > c.R * c.R * 1.4) continue;
      const lx = dx * c.cr + dz * c.sr, lz = -dx * c.sr + dz * c.cr; y = Math.max(y, surfL(c, lx, lz)); } return y; };

  /* ---- shaders ---- */
  const PT_V = `uniform float uTime,uPixel,uFade,uZoomP; uniform float uAf[${NA + 2}]; attribute vec3 aCol; attribute float aS,aR,aG; varying vec3 vC; varying float vA;
    void main(){ vec4 mv=modelViewMatrix*vec4(position,1.0); gl_Position=projectionMatrix*mv;
      float a=uAf[int(aG+0.5)], tw=0.62+0.38*sin(uTime*(1.1+aR*0.9)+aR*37.0);
      gl_PointSize=max(1.0,aS*44.0*uPixel*(6.4/-mv.z)*uZoomP*(1.0+a*0.3));
      vC=aCol*(1.0+a*0.5); vA=tw*(0.7+a*1.1)*uFade; }`;
  const ptMat = skAdd(FU, PT_V, SK_SOFT_F);
  const pts = (n) => { const g = new THREE.BufferGeometry();
    ['position', 'aCol'].forEach(k => g.setAttribute(k, new THREE.BufferAttribute(new Float32Array(n * 3), 3)));
    ['aS', 'aR', 'aG'].forEach(k => g.setAttribute(k, new THREE.BufferAttribute(new Float32Array(n), 1)));
    const p = new THREE.Points(g, ptMat); p.frustumCulled = false; return p; };
  const setPt = (o, i, x, y, z, c, s, g) => { const A = o.geometry.attributes; A.position.setXYZ(i, x, y, z); A.aCol.setXYZ(i, c[0], c[1], c[2]); A.aS.setX(i, s); A.aR.setX(i, rng()); A.aG.setX(i, g); };
  const GLOW = (() => { const cv = document.createElement('canvas'); cv.width = cv.height = 64; const x = cv.getContext('2d'), g = x.createRadialGradient(32, 32, 0, 32, 32, 32);
    g.addColorStop(0, 'rgba(255,255,255,1)'); g.addColorStop(0.25, 'rgba(255,255,255,.5)'); g.addColorStop(1, 'rgba(255,255,255,0)'); x.fillStyle = g; x.fillRect(0, 0, 64, 64); return new THREE.CanvasTexture(cv); })();
  const disposables = [GLOW];

  /* ---- 1. the nebulae: six shapes in turn (puff, swirl, ring, stream, twin, fiber), soft haze, fibres ---- */
  const STYLE_OF = ['swirl', 'ring', 'puff', 'stream', 'twin', 'fiber', 'ring', 'puff', 'swirl', 'fiber', 'stream', 'twin', 'swirl'];
  const cloudG = [], hazes = [], labels = [];
  const HAZE = (() => { const cv = document.createElement('canvas'); cv.width = cv.height = 128; const x = cv.getContext('2d'), g = x.createRadialGradient(64, 64, 0, 64, 64, 64);
    g.addColorStop(0, 'rgba(255,255,255,.9)'); g.addColorStop(0.35, 'rgba(255,255,255,.38)'); g.addColorStop(0.7, 'rgba(255,255,255,.08)'); g.addColorStop(1, 'rgba(255,255,255,0)');
    x.fillStyle = g; x.fillRect(0, 0, 128, 128); return new THREE.CanvasTexture(cv); })(); disposables.push(HAZE);
  for (let k = 0; k < NA; k++) {
    const c = CL[k], col = AC[k], R = c.R, st = STYLE_OF[k], g = new THREE.Group(); world.add(g); cloudG[k] = g;
    const N = Math.round((st === 'fiber' ? 1700 : 3000) * (lite ? 0.5 : 1)), NF = st === 'fiber' ? 110 : st === 'puff' ? 46 : 24, FP = 34;
    const P = pts(N + NF * FP); let i = 0;
    const arms = 2 + (rng() < 0.5 ? 1 : 0), spin = rng() < 0.5 ? 1 : -1, a0 = rng() * TAU;
    const lobes = st === 'twin' ? [{ x: -R * 0.55, z: (rng() - 0.5) * R * 0.2, r: R * 0.46 }, { x: R * 0.55, z: (rng() - 0.5) * R * 0.2, r: R * 0.42 }]
      : Array.from({ length: 4 }, () => { const a = rng() * TAU, rr = R * rng() * 0.4; return { x: Math.cos(a) * rr * 1.1, z: Math.sin(a) * rr * 0.8, r: R * (0.38 + rng() * 0.22) }; });
    const hz = st === 'ring' ? Array.from({ length: 6 }, (_, q) => { const a = q / 6 * TAU; return { x: Math.cos(a) * R * 0.68, z: Math.sin(a) * R * 0.5, r: R * 0.34 }; })
      : st === 'stream' ? [{ x: R * 0.5, z: 0, r: R * 0.42 }, { x: -R * 0.1, z: 0, r: R * 0.36 }, { x: -R * 0.7, z: 0, r: R * 0.28 }]
      : st === 'swirl' ? [{ x: 0, z: 0, r: R * 0.3 }, { x: 0, z: 0, r: R * 0.75 }] : st === 'fiber' ? [{ x: 0, z: 0, r: R * 0.85 }] : lobes.concat([{ x: 0, z: 0, r: R * 0.8 }]);
    hz.forEach(L => { const h = new THREE.Sprite(new THREE.SpriteMaterial({ map: HAZE, color: new THREE.Color(...mix3(col, WHITE, 0.12)), transparent: true, opacity: 0.07,
      blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false })); h.position.set(L.x, c.y0 + c.top * 0.4, L.z); h.scale.setScalar(L.r * 2.4);
      h.userData = { k, o: st === 'fiber' ? 0.05 : 0.07 }; h.renderOrder = 899; g.add(h); hazes.push(h); });
    for (let n = 0; n < N; n++, i++) {
      let x, z, br = 1;
      if (st === 'swirl') { if (rng() < 0.22) { x = gauss() * R * 0.16; z = gauss() * R * 0.13; br = 1.25; }
        else { const t = Math.pow(rng(), 0.8), a = a0 + ((n % arms) / arms) * TAU + spin * t * 3.4, rr = R * (0.12 + 0.86 * t), wd = R * (0.13 - 0.07 * t);
          x = Math.cos(a) * rr * 1.1 + gauss() * wd; z = Math.sin(a) * rr * 0.82 + gauss() * wd; br = 1.1 - t * 0.4; } }
      else if (st === 'ring') { if (rng() < 0.1) { x = gauss() * R * 0.25; z = gauss() * R * 0.2; br = 0.6; }
        else { const a = rng() * TAU, rr = R * (0.64 + gauss() * 0.08); x = Math.cos(a) * rr * 1.1; z = Math.sin(a) * rr * 0.8; br = 0.9 + 0.3 * Math.max(0, Math.cos(a - a0)); } }
      else if (st === 'stream') { const t = Math.pow(rng(), 1.3), wd = R * (0.34 * (1 - t) + 0.06); x = R * (0.75 - 1.85 * t) + gauss() * R * 0.06; z = gauss() * wd + Math.sin(t * 5 + a0) * R * 0.12 * t; br = 1.15 - t * 0.55; }
      else if (st === 'fiber') { x = gauss() * R * 0.5; z = gauss() * R * 0.38; br = 0.7; }
      else { const L = lobes[(rng() * lobes.length) | 0]; x = L.x + gauss() * L.r * 0.58; z = L.z + gauss() * L.r * (st === 'twin' ? 0.62 : 0.5);
        if (st === 'twin' && rng() < 0.08) { x = gauss() * R * 0.1; z = gauss() * R * 0.3; br = 1.3; } }
      const top = surfL(c, x, z), y = c.y0 - 0.35 + (top - c.y0 + 0.35) * Math.pow(rng(), 0.5) + gauss() * 0.06, w = Math.pow(rng(), 3.5);
      setPt(P, i, x, y, z, mix3(col, WHITE, w * 0.8).map(v => v * Math.min(1.2, (0.5 + rng() * 0.5) * br)), 0.045 + Math.pow(rng(), 9) * 0.34 + w * 0.05, k);
    }
    for (let f = 0; f < NF; f++) {
      let x0, z0, dir;
      if (st === 'ring') { const a = rng() * TAU; x0 = Math.cos(a) * R * 0.7; z0 = Math.sin(a) * R * 0.51; dir = a + HP + (rng() - 0.5) * 0.5; }
      else if (st === 'stream') { x0 = R * (0.6 - 1.5 * rng()); z0 = (rng() - 0.5) * R * 0.4; dir = (rng() - 0.5) * 0.35; }
      else if (st === 'swirl') { const t = rng(), a = a0 + ((f % arms) / arms) * TAU + spin * t * 3.4, rr = R * (0.15 + 0.8 * t); x0 = Math.cos(a) * rr * 1.1; z0 = Math.sin(a) * rr * 0.82; dir = a + spin * HP; }
      else { x0 = (rng() - 0.5) * R * 1.4; z0 = (rng() - 0.5) * R * 0.9; dir = (rng() - 0.5) * (st === 'fiber' ? TAU : 1.8); }
      const len = R * (st === 'fiber' ? 0.4 + rng() * 1.1 : 0.4 + rng() * 0.7), amp = R * (0.05 + rng() * 0.12), fr = 2 + rng() * 4, ph = rng() * TAU, hf = 0.55 + rng() * 0.45;
      const cc0 = mix3(col, WHITE, 0.25 + rng() * 0.4);
      for (let q = 0; q < FP; q++, i++) { const u = q / (FP - 1) - 0.5, bend = Math.sin(u * fr + ph) * amp;
        const x = x0 + Math.cos(dir) * u * len - Math.sin(dir) * bend, z = z0 + Math.sin(dir) * u * len * 0.6 + Math.cos(dir) * bend * 0.6;
        const y = c.y0 + (surfL(c, x, z) - c.y0) * hf + Math.sin(u * 5 + ph) * 0.15, fade = 1 - Math.abs(u) * 1.6;
        setPt(P, i, x + gauss() * 0.03, y, z + gauss() * 0.03, cc0.map(v => v * ((st === 'fiber' ? 0.55 : 0.4) + 0.55 * Math.max(0, fade))), 0.04 + rng() * 0.03, k); }
    }
    P.renderOrder = 900; g.add(P);
    /* the ability's name floats over its nebula */
    const lt = skLabelTex(SKAB[k].zh, SKAB[k].en); disposables.push(lt);
    const lab = new THREE.Sprite(new THREE.SpriteMaterial({ map: lt, color: new THREE.Color(...mix3(col, WHITE, 0.45)), transparent: true, opacity: 0.4,
      blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false }));
    lab.scale.set(11.5, 4.3, 1); lab.renderOrder = 905; world.add(lab); labels.push(lab);
  }
  /* orbits: hairlines + a thin dust lane on each, and a ring of dust round the core */
  { const NO = lite ? 400 : 900, ND = lite ? 700 : 1600, od = pts(NO * 3 + ND); let i = 0;
    const lm = new THREE.ShaderMaterial({ uniforms: Object.assign({ uC: { value: new THREE.Color() } }, FU), transparent: true, depthWrite: false, depthTest: false, blending: THREE.AdditiveBlending,
      vertexShader: `void main(){ gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }`, fragmentShader: `uniform vec3 uC; uniform float uFade; void main(){ gl_FragColor=vec4(uC*0.2*uFade,1.0); }` });
    ORB.forEach((o, oi) => {
      const V = []; for (let j = 0; j <= 240; j++) { const f = j / 240 * TAU; V.push(new THREE.Vector3(Math.cos(f) * o.ax, -0.35, Math.sin(f) * o.az)); }
      const m = lm.clone(); m.uniforms = Object.assign({}, FU, { uC: { value: new THREE.Color(oi === 1 ? 0xffc2a6 : 0x9fd4ff) } });
      const L = new THREE.LineLoop(new THREE.BufferGeometry().setFromPoints(V), m); L.renderOrder = 898; world.add(L);
      for (let n = 0; n < NO; n++, i++) { const f = rng() * TAU, j = 1 + gauss() * 0.035; const cc = (rng() < 0.3 ? PEACH : rng() < 0.5 ? ICE : WHITE).map(v => v * (0.25 + rng() * 0.4));
        setPt(od, i, Math.cos(f) * o.ax * j, -0.35 + gauss() * 0.15, Math.sin(f) * o.az * j, cc, 0.035 + Math.pow(rng(), 8) * 0.18, NA); }
    });
    lm.dispose();
    for (let n = 0; n < ND; n++, i++) { const a = rng() * TAU, r = 6.2 + Math.abs(gauss()) * 3.2; const cc = (rng() < 0.3 ? PEACH : rng() < 0.5 ? ICE : WHITE).map(v => v * (0.4 + rng() * 0.5));
      setPt(od, i, Math.cos(a) * r * 1.15, gauss() * 0.35 + 0.4, Math.sin(a) * r * 0.8, cc, 0.04 + Math.pow(rng(), 6) * 0.22, NA); }
    od.renderOrder = 899; world.add(od); }

  /* ---- 2. the star core: glowing ribbons wound into a ball (shared with the spider's abdomen) ---- */
  const RB_V = `attribute vec2 aUV; attribute vec3 aNrm; uniform float uFlow,uPh,uAmp,uAmpK,uVib; varying vec2 vUV;
    void main(){ vec3 p=position+aNrm*sin(aUV.x*18.85+uFlow*1.5+uPh)*uAmp*uAmpK*(1.0+uVib*0.8*sin(aUV.x*40.0+uFlow*9.0)); vUV=aUV; gl_Position=projectionMatrix*modelViewMatrix*vec4(p,1.0); }`;
  const RB_F = `uniform vec3 uC1,uC2; uniform float uFlow,uPh,uA,uFade; varying vec2 vUV;
    void main(){ float e=abs(vUV.y*2.0-1.0); float edge=pow(e,5.0)*1.1+0.14; float str=0.5+0.5*sin(vUV.y*70.0+vUV.x*30.0);
      float along=0.25+0.75*pow(0.5+0.5*sin(vUV.x*12.566+uFlow*0.6+uPh),2.0);
      vec3 col=mix(uC1,uC2,0.5+0.5*sin(vUV.x*6.2832*2.0+uPh)); gl_FragColor=vec4(col*edge*(0.55+0.45*str)*along*uA*uFade,1.0); }`;
  const ribbonGeo = (R, w0, seg) => {
    const q = new THREE.Quaternion().setFromEuler(new THREE.Euler(rng() * TAU, rng() * TAU, rng() * TAU)), p1 = rng() * TAU, p2 = rng() * TAU, k1 = 2 + ((rng() * 2) | 0);
    const P = []; for (let i = 0; i <= seg; i++) { const u = i / seg * TAU, rr = R * (1 + 0.14 * Math.sin(3 * u + p1)); P.push(new THREE.Vector3(Math.cos(u) * rr, R * 0.22 * Math.sin(k1 * u + p2), Math.sin(u) * rr).applyQuaternion(q)); }
    const pos = new Float32Array((seg + 1) * 6), uv = new Float32Array((seg + 1) * 4), nrm = new Float32Array((seg + 1) * 6), idx = [];
    const T = new THREE.Vector3(), N = new THREE.Vector3(), B = new THREE.Vector3();
    for (let i = 0; i <= seg; i++) { const a = P[(i + seg - 1) % seg], b = P[(i + 1) % seg], p = P[i];
      T.subVectors(b, a).normalize(); N.copy(p).normalize(); B.crossVectors(T, N).normalize();
      const u = i / seg, w = w0 * (0.25 + 0.75 * Math.pow(0.5 + 0.5 * Math.sin(u * TAU * 2 + p1), 1.5));
      for (let s = 0; s < 2; s++) { const sg = s ? 0.5 : -0.5, o = i * 2 + s; pos[o * 3] = p.x + B.x * w * sg; pos[o * 3 + 1] = p.y + B.y * w * sg; pos[o * 3 + 2] = p.z + B.z * w * sg;
        uv[o * 2] = u; uv[o * 2 + 1] = s; nrm[o * 3] = N.x; nrm[o * 3 + 1] = N.y; nrm[o * 3 + 2] = N.z; }
      if (i < seg) { const o = i * 2; idx.push(o, o + 1, o + 2, o + 1, o + 3, o + 2); } }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(pos, 3)); g.setAttribute('aUV', new THREE.BufferAttribute(uv, 2));
    g.setAttribute('aNrm', new THREE.BufferAttribute(nrm, 3)); g.setIndex(idx); return g; };
  const PAL = [['#d9ecff', '#7fb6ff'], ['#ffffff', '#ffc2a6'], ['#9fd4ff', '#ffffff'], ['#ffb48f', '#d9ecff'], ['#7aa8ff', '#e6f2ff']];
  const ribbonBall = (R, n, w0, seg, amp, alpha, own) => { const g = new THREE.Group();
    for (let i = 0; i < n; i++) { const [c1, c2] = PAL[i % PAL.length];
      const m = new THREE.Mesh(ribbonGeo(R * (0.82 + rng() * 0.3), w0 * (0.6 + rng() * 0.8), seg), new THREE.ShaderMaterial({ vertexShader: RB_V, fragmentShader: RB_F,
        uniforms: Object.assign({}, FU, { uPh: { value: rng() * TAU }, uAmp: { value: amp }, uC1: { value: new THREE.Color(c1) }, uC2: { value: new THREE.Color(c2) }, uA: { value: alpha * (0.6 + rng() * 0.5) } }, own || {}),
        transparent: true, depthWrite: false, depthTest: false, side: THREE.DoubleSide, blending: THREE.AdditiveBlending }));
      m.frustumCulled = false; m.renderOrder = 901; m.userData.ax = new THREE.Vector3(rng() - 0.5, rng() - 0.5, rng() - 0.5).normalize(); m.userData.sp = (0.05 + rng() * 0.12) * (rng() < 0.5 ? -1 : 1); g.add(m); }
    return g; };
  const coreBall = ribbonBall(6.1, 24, 1.7, lite ? 140 : 220, 0.22, 0.3); coreBall.position.set(0, CORE_Y, 0); world.add(coreBall);
  const coreGlow = new THREE.Sprite(new THREE.SpriteMaterial({ map: GLOW, color: 0xcfe2ff, transparent: true, opacity: 0.25, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false }));
  coreGlow.scale.setScalar(17); coreGlow.position.copy(coreBall.position); coreGlow.renderOrder = 898; world.add(coreGlow);
  const ringsG = new THREE.Group(); ringsG.position.copy(coreBall.position); world.add(ringsG);
  [[8.0, 0.35, 0.1], [9.4, -0.28, -0.18], [10.6, 0.16, 0.26]].forEach(([r, rx, rz], i) => {
    const V = []; for (let j = 0; j <= 160; j++) { const a = j / 160 * TAU; V.push(new THREE.Vector3(Math.cos(a) * r, 0, Math.sin(a) * r)); }
    const line = new THREE.LineLoop(new THREE.BufferGeometry().setFromPoints(V), new THREE.LineBasicMaterial({ color: i === 1 ? 0xffc2a6 : 0x9fd4ff, transparent: true, opacity: 0.22, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false }));
    const h = new THREE.Group(); h.rotation.set(rx, 0, rz); h.add(line); line.renderOrder = 899;
    const dot = new THREE.Sprite(new THREE.SpriteMaterial({ map: GLOW, color: i === 1 ? 0xffd0b8 : 0xcfe6ff, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false }));
    dot.scale.setScalar(0.9); dot.renderOrder = 902; h.add(dot); h.userData = { r, dot, sp: [0.35, -0.22, 0.16][i], a: rng() * TAU }; ringsG.add(h); });

  /* ---- 3. the nebula spider: particle head, two round eyes, a ribbon abdomen, eight translucent legs ---- */
  const SS = 1.1, HEAD = new THREE.Vector3(0, 1.0, 0.55), HR = 0.72, AR = 1.38, ACp = [0, 1.32, -1.25];
  const spG = new THREE.Group(); spG.scale.setScalar(SS); world.add(spG);
  const darkM = new THREE.MeshBasicMaterial({ color: 0x050a1a, transparent: true, opacity: 0.92, depthWrite: false, depthTest: false });
  const hd = new THREE.Mesh(new THREE.SphereGeometry(HR * 0.8, 24, 18), darkM); hd.position.copy(HEAD); hd.renderOrder = 903; spG.add(hd);
  const ab = new THREE.Mesh(new THREE.SphereGeometry(AR * 0.74, 24, 18), darkM); ab.position.set(...ACp); ab.renderOrder = 903; spG.add(ab);
  { const nH = lite ? 1600 : 3200, nA = lite ? 800 : 1500, sp = pts(nH + nA); let i = 0;
    for (let n = 0; n < nH; n++, i++) { const v = new THREE.Vector3(gauss(), gauss(), gauss()).normalize().multiplyScalar(HR * (n < nH * 0.85 ? 0.9 + rng() * 0.16 : rng() * 0.9));
      setPt(sp, i, HEAD.x + v.x, HEAD.y + v.y, HEAD.z + v.z, (rng() < 0.1 ? PEACH : rng() < 0.45 ? ICE : WHITE).map(q => q * (0.6 + rng() * 0.4)), (0.045 + Math.pow(rng(), 6) * 0.12) * SS, NA + 1); }
    for (let n = 0; n < nA; n++, i++) { const v = new THREE.Vector3(gauss(), gauss() * 0.9, gauss() * 1.08).normalize().multiplyScalar(AR * (n < nA * 0.7 ? 0.97 + rng() * 0.08 : rng() * 0.95));
      setPt(sp, i, ACp[0] + v.x, ACp[1] + v.y, ACp[2] + v.z, (rng() < 0.2 ? PEACH : rng() < 0.55 ? ICE : WHITE).map(q => q * (0.4 + rng() * 0.5)), (0.03 + Math.pow(rng(), 7) * 0.1) * SS, NA + 1); }
    sp.renderOrder = 904; spG.add(sp); }
  const abd = ribbonBall(AR * 0.98, 10, 0.45, 90, 0.05, 1.05, { uAmpK: { value: 1 }, uVib: { value: 0 } }); abd.children.forEach(m => m.renderOrder = 904);
  abd.position.set(...ACp); abd.scale.set(1, 0.92, 1.08); spG.add(abd);
  const eyeT = (() => { const cv = document.createElement('canvas'); cv.width = cv.height = 128; const x = cv.getContext('2d'), c = 64;
    const gl = x.createRadialGradient(c, c, 34, c, c, 62); gl.addColorStop(0, 'rgba(190,225,255,.55)'); gl.addColorStop(1, 'rgba(190,225,255,0)'); x.fillStyle = gl; x.fillRect(0, 0, 128, 128);
    x.beginPath(); x.arc(c, c, 34, 0, TAU); x.fillStyle = '#060c1c'; x.fill(); x.lineWidth = 5; x.strokeStyle = 'rgba(235,245,255,.95)'; x.stroke();
    x.beginPath(); x.arc(c + 6, c - 6, 9, 0, TAU); x.fillStyle = 'rgba(240,248,255,.95)'; x.fill(); return new THREE.CanvasTexture(x.canvas); })(); disposables.push(eyeT);
  [-1, 1].forEach(s => { const e = new THREE.Sprite(new THREE.SpriteMaterial({ map: eyeT, transparent: true, depthWrite: false, depthTest: false })); e.position.set(s * 0.28, HEAD.y + 0.1, HEAD.z + HR * 0.96);
    e.scale.setScalar(0.5); e.renderOrder = 905; spG.add(e); });
  const legMat = new THREE.ShaderMaterial({ uniforms: { uC: { value: new THREE.Color('#b8dcff') }, uA: { value: 0.95 }, uFade: FU.uFade }, transparent: true, depthWrite: false, depthTest: false, blending: THREE.AdditiveBlending,
    vertexShader: `varying vec3 vN; varying vec3 vV; void main(){ vec4 mv=modelViewMatrix*vec4(position,1.0); vN=normalize(normalMatrix*normal); vV=normalize(-mv.xyz); gl_Position=projectionMatrix*mv; }`,
    fragmentShader: `uniform vec3 uC; uniform float uA,uFade; varying vec3 vN; varying vec3 vV; void main(){ float f=1.0-abs(dot(vN,vV)); gl_FragColor=vec4(uC*(pow(f,2.2)*0.95+0.1)*uA*uFade,1.0); }` });
  const segGeo = new THREE.CylinderGeometry(0.72, 1, 1, 10, 1, true), legG = new THREE.Group(); world.add(legG);
  const LEN = [1.5, 1.85, 0.78].map(v => v * SS), legs = [], ANG = [38, 72, 108, 142];
  const jointM = (c) => new THREE.SpriteMaterial({ map: GLOW, color: c, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false });
  for (let s = -1; s <= 1; s += 2) for (let n = 0; n < 4; n++) { const a = ANG[n] * Math.PI / 180;
    const L = { side: s, n, grp: (n + (s > 0 ? 1 : 0)) % 2, rootL: new THREE.Vector3(s * 0.5, 0.98, HEAD.z + 0.32 - n * 0.26), restL: new THREE.Vector3(s * Math.sin(a) * 3.1, 0, HEAD.z + Math.cos(a) * 3.0),
      foot: new THREE.Vector3(), step: null, init: false, segs: [], joints: [], P: [0, 0, 0, 0].map(() => new THREE.Vector3()) };
    for (let q = 0; q < 3; q++) { const m = new THREE.Mesh(segGeo, legMat); m.frustumCulled = false; m.renderOrder = 904; legG.add(m); L.segs.push(m); }
    for (let q = 0; q < 4; q++) { const j = new THREE.Sprite(jointM(q === 3 ? 0xffe2cf : q === 0 ? 0x9fd4ff : 0xffc7a8)); j.userData.s = q === 3 ? 0.5 : 0.36; j.renderOrder = 905; legG.add(j); L.joints.push(j); }
    legs.push(L); }
  const tA = [0, 0, 0, 0].map(() => new THREE.Vector3()), UPV = new THREE.Vector3(0, 1, 0);
  const setSeg = (m, a, b, r) => { const d = tA[3].subVectors(b, a), len = d.length(); m.position.addVectors(a, b).multiplyScalar(0.5); m.quaternion.setFromUnitVectors(UPV, d.divideScalar(len || 1)); m.scale.set(r, len, r); };
  const sp = { x: 0, y: CORE_Y + CORE_R, z: 0, yaw: 0, a: -1, lx: 0, lz: 0, jump: null, air: false, land: 0, vx: 0, vz: 0, wander: 0 };
  const legsStep = (dt, t) => {
    spG.updateMatrix(); const M = spG.matrix;   /* spider -> world-group space */
    const walking = Math.hypot(sp.vx, sp.vz) > 0.4, landing = sp.land > 0.25, stepping = [0, 0]; legs.forEach(L => { if (L.step) stepping[L.grp]++; });
    legs.forEach(L => {
      const R = L.P[0].copy(L.rootL).applyMatrix4(M), T = tA[0].copy(L.restL).applyMatrix4(M);
      if (walking) { T.x += sp.vx * 0.2; T.z += sp.vz * 0.2; } T.y = surfW(T.x, T.z);
      if (!L.init) { L.foot.copy(T); L.init = true; }
      if (sp.air) { const tuck = tA[1].set(L.restL.x * 0.5, -0.45, L.restL.z * 0.55).applyMatrix4(M); L.foot.lerp(tuck, Math.min(1, dt * 22)); L.step = null; }
      else if (L.step) { L.step.t += dt / L.step.dur; const u = Math.min(1, L.step.t), e = u < 0.5 ? 2 * u * u : 1 - Math.pow(-2 * u + 2, 2) / 2;
        L.foot.lerpVectors(L.step.from, L.step.to, e); L.foot.y += Math.sin(Math.PI * u) * 0.45 * SS; if (u >= 1) { L.foot.copy(L.step.to); L.step = null; } }
      else { const d = Math.hypot(L.foot.x - T.x, L.foot.z - T.z), thr = walking ? 0.7 * SS : 0.32 * SS;
        if (d > 4 * SS) L.foot.copy(T);
        else if (d > thr && (landing || (walking ? stepping[1 - L.grp] === 0 : stepping[0] + stepping[1] < 2))) { L.step = { from: L.foot.clone(), to: T.clone(), t: 0, dur: landing ? 0.09 : walking ? 0.13 : 0.2 }; stepping[L.grp]++; }
        if (!L.step) L.foot.y = surfW(L.foot.x, L.foot.z) + (sp.reading && L.n === 0 ? Math.max(0, Math.sin(t * 9 + L.side) * 0.16 * SS) : 0); }
      const F = L.P[3].copy(L.foot), o = tA[1].set(F.x - R.x, 0, F.z - R.z), ol = o.length() || 1; o.divideScalar(ol);
      const A = L.P[2].set(F.x - o.x * LEN[2] * 0.3, F.y + LEN[2] * 0.92, F.z - o.z * LEN[2] * 0.3);
      const dir = tA[2].subVectors(A, R); let d = dir.length(); dir.divideScalar(d || 1); d = clamp(d, 0.2, (LEN[0] + LEN[1]) * 0.995);
      const ca = clamp((LEN[0] * LEN[0] + d * d - LEN[1] * LEN[1]) / (2 * LEN[0] * d), -1, 1), sa = Math.sqrt(1 - ca * ca);
      const perp = tA[3].copy(UPV).addScaledVector(dir, -UPV.dot(dir)).normalize();
      const Kn = L.P[1].copy(R).addScaledVector(dir, ca * LEN[0]).addScaledVector(perp, sa * LEN[0]); A.copy(R).addScaledVector(dir, d);
      setSeg(L.segs[0], R, Kn, 0.1 * SS); setSeg(L.segs[1], Kn, A, 0.08 * SS); setSeg(L.segs[2], A, F, 0.055 * SS);
      L.joints.forEach((j, q) => { j.position.copy(L.P[q]); j.scale.setScalar(j.userData.s * SS); });
    });
  };

  /* ---- 4. threads (the spider feeling a nebula) and comets (work flowing between the core and an ability) ---- */
  const TH = 10, TD = 22, thr = pts(TH * TD); for (let i = 0; i < TH * TD; i++) setPt(thr, i, 0, -999, 0, [0.85, 0.92, 1], 0.07, NA + 1);
  thr.renderOrder = 904; world.add(thr);
  const threads = Array.from({ length: TH }, () => ({ on: false, g: 0, ret: false, a: 0, lx: 0, lz: 0 }));
  const CP = 70, CT = 10, cm = pts(CP * CT); cm.renderOrder = 906; world.add(cm);
  for (let i = 0; i < CP * CT; i++) setPt(cm, i, 0, -999, 0, WHITE, 0.1, NA + 1);
  const comets = []; let cNext = 0;
  const posOf = (a, o) => a < 0 ? (o.x = 0, o.y = CORE_Y + CORE_R * 0.4, o.z = 0, o) : (o.x = CL[a].x, o.y = CL[a].y0 + CL[a].top + 0.4, o.z = CL[a].z, o);
  const comet = (a, b, dur, delay = 0, size = 0.32) => { comets[cNext] = { a, b, t: -delay, dur: dur * (0.85 + rng() * 0.3), size, col: mix3(AC[b < 0 ? a : b] || WHITE, WHITE, 0.35), lift: 0.7 + rng() * 0.6 }; cNext = (cNext + 1) % CP; };
  const burst = (a, b, n, dur, delay = 0) => { for (let i = 0; i < n; i++) comet(a, b, dur, delay + i * 0.07, 0.22 + rng() * 0.16); };
  const _p0 = {}, _p1 = {};
  const cometsStep = (dt) => { const A = cm.geometry.attributes;
    for (let i = 0; i < CP; i++) { const c = comets[i];
      for (let q = 0; q < CT; q++) { const o = i * CT + q;
        if (!c || c.t < 0 || c.t - q * 0.025 > 1 || c.t - q * 0.025 < 0) { A.position.setY(o, -999); continue; }
        const u = c.t - q * 0.025, e = u * u * (3 - 2 * u); posOf(c.a, _p0); posOf(c.b, _p1); const L = Math.hypot(_p1.x - _p0.x, _p1.z - _p0.z);
        A.position.setXYZ(o, lerp(_p0.x, _p1.x, e), lerp(_p0.y, _p1.y, e) + Math.sin(Math.PI * e) * L * 0.18 * c.lift, lerp(_p0.z, _p1.z, e));
        const w = q ? 1 - q / CT : 1; A.aCol.setXYZ(o, c.col[0] * w, c.col[1] * w, c.col[2] * w); A.aS.setX(o, c.size * (q ? 0.7 * w : 1.3)); }
      if (c) { c.t += dt / c.dur; if (c.t > 1.3) comets[i] = null; } }
    A.position.needsUpdate = A.aCol.needsUpdate = A.aS.needsUpdate = true; };
  const threadsStep = (dt) => { const H = tA[0].set(0, 1.08, HEAD.z + 0.55).applyMatrix4(spG.matrix);
    const A = thr.geometry.attributes;
    threads.forEach((th, ti) => { if (th.on) { th.g = th.ret ? th.g - dt * 2.6 : Math.min(1, th.g + dt * 3.2); if (th.g <= 0) th.on = false; }
      const p = anchorW(th.a, th.lx, th.lz, _p0), col = AC[th.a] ? mix3(AC[th.a], WHITE, 0.5) : WHITE;
      for (let j = 0; j < TD; j++) { const s = j / (TD - 1), o = ti * TD + j; if (!th.on || s > th.g) { A.position.setXYZ(o, 0, -999, 0); continue; }
        const L = Math.hypot(p.x - H.x, p.z - H.z), arc = Math.sin(Math.PI * s) * Math.min(1, L * 0.1);
        A.position.setXYZ(o, lerp(H.x, p.x, s), lerp(H.y, p.y, s) + arc, lerp(H.z, p.z, s)); A.aCol.setXYZ(o, col[0], col[1], col[2]); } });
    A.position.needsUpdate = A.aCol.needsUpdate = true; };
  const feel = (a) => { threads.forEach(th => { if (th.on) th.ret = true; });
    for (let n = 0; n < 4; n++) { const th = threads.find(x => !x.on) || threads[(rng() * TH) | 0]; const r = CL[a].R * 0.6 * Math.sqrt(rng()), f = rng() * TAU;
      Object.assign(th, { on: true, g: 0, ret: false, a, lx: Math.cos(f) * r * 1.1, lz: Math.sin(f) * r * 0.8 }); } };

  /* ---- what Jarvis is doing ---- */
  const act = FU.uAf.value, ev = new Float32Array(NA), base = new Float32Array(NA);
  let mic = 0, hold = -1, holdT = 0, scanI = 0, scanT = 0;
  const use = (i) => { ev[i] = 1; hold = i; holdT = 2.6; burst(-1, i, 5, 0.9); burst(i, -1, 5, 0.9, 0.9); };
  const onMsg = (m) => { const e = skEvent(m); if (!e) return;
    if (e.k === 'wake') { ev[SKAB_I.ear] = 1; burst(SKAB_I.ear, -1, 4, 0.8); }
    else if (e.k === 'heard') { ev[SKAB_I.ear] = 1; burst(SKAB_I.ear, -1, 8, 0.75); }
    else if (e.k === 'reply') { ev[SKAB_I.voice] = 1; burst(-1, SKAB_I.voice, 8, 0.7); }
    else if (e.k === 'level') mic = Math.max(mic, e.v);
    else if (e.k === 'sys') { ev[SKAB_I.sys] = Math.max(ev[SKAB_I.sys], 0.35); comet(SKAB_I.sys, -1, 1.8, 0, 0.18); }
    else if (e.k === 'use') use(e.i); };

  /* ---- motion ---- */
  const ctl = skControl();
  let t = 0, fade = 0, flow = 0, flowSp = 1, ampK = 1, accIdle = 0, accEar = 0, accVoice = 0, lastTarget = -2, rideT = 0;
  const jumpTo = (a, lx, lz) => { anchorW(sp.a, sp.lx, sp.lz, _p0); sp.jump = { from: { x: sp.x, y: sp.y, z: sp.z }, a, lx, lz, u: 0, dur: a === sp.a ? 0.4 : 0.55 + Math.min(0.5, Math.hypot(_p0.x, _p0.z) * 0.004) };
    sp.air = true; sp.reading = false; };
  const placeOn = (a) => { if (a < 0) return [0, 0]; const r = CL[a].R * 0.25 * Math.sqrt(rng()), f = rng() * TAU; return [Math.cos(f) * r, Math.sin(f) * r]; };
  return {
    msg: onMsg,
    frame(dt) {
      dt = Math.min(dt, 0.1); const st = typeof state === 'string' ? state : 'idle', vib = st === 'speaking' ? U.uVib.value : 0;
      t += dt; fade = Math.min(1, fade + dt / 0.9); mic *= Math.pow(0.15, dt); holdT -= dt; if (holdT <= 0) hold = -1;
      orbT += dt * (st === 'thinking' ? 2.2 : 1); orbitStep();
      /* activity per ability */
      const breath = 0.5 + 0.5 * Math.sin(t * TAU / 6); base.fill(0.12 + 0.08 * breath);
      let fT = 1, aT = 1;
      if (st === 'listening') { base[SKAB_I.ear] = 0.5 + mic; fT = 1.4; }
      else if (st === 'thinking') { fT = 3.2; aT = 1.8; }
      else if (st === 'speaking') { base[SKAB_I.voice] = 0.5 + vib; fT = 1.6 + vib; aT = 1.2 + vib * 1.4; }
      /* where the spider (Jarvis's attention) should be */
      let target = -1;
      if (hold >= 0) target = hold;
      else if (st === 'listening') target = SKAB_I.ear;
      else if (st === 'thinking') { scanT -= dt; if (scanT <= 0) { scanT = 0.85; scanI = (scanI + 1) % NA; } target = scanI; base[scanI] = Math.max(base[scanI], 0.55); }
      if (target !== lastTarget && !sp.air) { lastTarget = target; const [lx, lz] = placeOn(target); jumpTo(target, lx, lz); if (target >= 0) feel(target); }
      for (let k = 0; k < NA; k++) { ev[k] *= Math.pow(0.3, dt); act[k] = skEase(act[k], Math.max(base[k], ev[k]), 0.02, dt); }
      act[NA] = 0.25 + 0.2 * breath; act[NA + 1] = 0.6;
      /* signal traffic */
      accIdle += dt * 0.35; while (accIdle > 1) { accIdle -= 1; comet((rng() * NA) | 0, -1, 2.2, 0, 0.16); }
      if (st === 'listening') { accEar += dt * (2 + mic * 16); while (accEar > 1) { accEar -= 1; comet(SKAB_I.ear, -1, 0.8, 0, 0.18 + mic * 0.2); } }
      if (st === 'speaking') { accVoice += dt * (2 + vib * 12); while (accVoice > 1) { accVoice -= 1; comet(-1, SKAB_I.voice, 0.7, 0, 0.18 + vib * 0.2); } }
      if (st === 'thinking' && rng() < dt * 3) comet(-1, scanI, 0.6, 0, 0.2);
      /* the spider: jump, or ride its nebula and wander a little while it reads */
      const px = sp.x, pz = sp.z;
      if (sp.jump) { const J = sp.jump; J.u += dt / J.dur; const u = Math.min(1, J.u), e = u * u * (3 - 2 * u); anchorW(J.a, J.lx, J.lz, _p1);
        const L = Math.hypot(_p1.x - J.from.x, _p1.z - J.from.z);
        sp.x = lerp(J.from.x, _p1.x, e); sp.z = lerp(J.from.z, _p1.z, e); sp.y = lerp(J.from.y, _p1.y, e) + Math.sin(Math.PI * u) * (1.5 + L * 0.22);
        if (u >= 1) { sp.jump = null; sp.air = false; sp.a = J.a; sp.lx = J.lx; sp.lz = J.lz; sp.land = 1; sp.reading = sp.a >= 0; rideT = 1.2 + rng(); } }
      else { rideT -= dt; if (rideT <= 0 && sp.a >= 0) { rideT = 1.4 + rng() * 1.6; const [lx, lz] = placeOn(sp.a); sp.tlx = lx; sp.tlz = lz; }
        if (sp.tlx != null && sp.a >= 0) { sp.lx = skEase(sp.lx, sp.tlx, 0.3, dt); sp.lz = skEase(sp.lz, sp.tlz, 0.3, dt); }
        anchorW(sp.a, sp.lx, sp.lz, _p0); sp.x = _p0.x; sp.z = _p0.z; sp.y = _p0.y; }
      sp.land = Math.max(0, sp.land - dt * 3);
      sp.vx = (sp.x - px) / Math.max(dt, 1e-3); sp.vz = (sp.z - pz) / Math.max(dt, 1e-3);
      const sv = Math.hypot(sp.vx, sp.vz); let want = sp.yaw;
      if (sv > 0.6) want = Math.atan2(sp.vx, sp.vz); else if (sp.a >= 0) want = Math.atan2(CL[sp.a].x - sp.x + 1e-3, CL[sp.a].z - sp.z) ; else want = Math.atan2(0, 1) + Math.sin(t * 0.3) * 0.6;
      let d = (want - sp.yaw) % TAU; if (d > Math.PI) d -= TAU; if (d < -Math.PI) d += TAU; sp.yaw += d * (1 - Math.pow(0.02, dt));
      spG.position.set(sp.x, sp.y + 0.25 * SS - (sp.land * 0.3 * SS), sp.z); spG.rotation.set(0, sp.yaw, 0);
      legsStep(dt, t); threadsStep(dt); cometsStep(dt);
      /* the core and its rings */
      flowSp = skEase(flowSp, fT, 0.25, dt); ampK = skEase(ampK, aT, 0.1, dt); flow += dt * flowSp;
      coreBall.children.forEach(m => m.rotateOnAxis(m.userData.ax, m.userData.sp * dt * flowSp));
      abd.children.forEach(m => m.rotateOnAxis(m.userData.ax, m.userData.sp * dt * 2));
      ringsG.children.forEach(h => { h.userData.a += dt * h.userData.sp * flowSp; h.userData.dot.position.set(Math.cos(h.userData.a) * h.userData.r, 0, Math.sin(h.userData.a) * h.userData.r); });
      coreGlow.material.opacity = (0.08 + 0.06 * breath + (st === 'thinking' ? 0.14 : 0) + vib * 0.2) * fade * U.uGain.value;
      /* nebula haze and name cards follow their ability's activity */
      hazes.forEach(h => { h.material.opacity = h.userData.o * (0.7 + act[h.userData.k] * 1.4) * fade * U.uGain.value; });
      cloudG.forEach((g, k) => { const c = CL[k]; g.position.set(c.x, 0, c.z); g.rotation.y = -Math.atan2(c.sr, c.cr); });   /* each nebula rides its orbit, stretched along it */
      labels.forEach((l, k) => { const c = CL[k]; l.position.set(c.x, c.y0 + c.top + 6.5, c.z); l.material.opacity = (0.28 + act[k] * 0.9) * fade * U.uGain.value; });
      /* view: looking down onto the orbit plane, slowly turning, plus drag / palm */
      ctl.step(dt); grp.scale.setScalar(ctl.zoom); FU.uZoomP.value = Math.sqrt(ctl.zoom);
      tilt.rotation.set(1.02 + ctl.pitch, 0.12 * Math.sin(t * 0.05) + ctl.yaw + t * 0.012, 0, 'XYZ');
      FU.uTime.value = t; FU.uFlow.value = flow; FU.uFade.value = fade * U.uGain.value; FU.uAmpK.value = ampK; FU.uVib.value = vib;
      grp.visible = U.uGain.value > 0.02;
      skQuiet(0.95);
    },
    dispose() { ctl.dispose(); scene.remove(grp);
      grp.traverse(o => { if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); }); disposables.forEach(x => x.dispose()); }
  };
};
/* core:nebula>> */
