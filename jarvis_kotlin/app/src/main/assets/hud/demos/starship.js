/* Starship V3 · launch to recovery, as a particle hologram.
   One silent, self-running show (~2 min): the stack on the pad, its insides, ignition, ascent, hot staging,
   the booster's return to the tower, orbit, Starlink deployment, reentry, belly flop, flip and catch, full reuse.
   Units: 1 = 10 m. The stage is drawn through a virtual camera: the root group carries mainCamera * inverse(vcam),
   so the host camera stays where it is. */
export function install(ctx) {
  const { THREE, scene, camera } = ctx;
  const pixel = ctx.pixel || Math.min(devicePixelRatio || 1, 2);
  const root = new THREE.Group(); root.matrixAutoUpdate = false; root.visible = false; scene.add(root);
  if (camera.far < 400) { camera.far = 400; camera.updateProjectionMatrix(); }

  /* ---------------- shared uniforms and materials ---------------- */
  const G = { uTime: { value: 0 }, uPixel: { value: pixel }, uAppear: { value: 0 }, uPt: { value: 20 }, uSpread: { value: 5 } };
  const C = {
    holo: [0.42, 0.82, 1.0], dim: [0.22, 0.5, 0.7], bright: [0.75, 0.95, 1.0], lox: [0.86, 0.93, 1.0], ch4: [0.3, 1.0, 0.78],
    tile: [0.2, 0.36, 0.5], hot: [1.0, 0.6, 0.28], land: [0.3, 0.72, 0.95], sea: [0.08, 0.24, 0.42], ground: [0.16, 0.42, 0.6]
  };
  const STRUCT_V = `
    uniform float uTime,uPixel,uAppear,uPt,uSpread,uAlpha,uCut,uHeat,uFlow,uFlowLen;
    uniform vec4 uRing; uniform vec4 uSel;
    attribute vec3 aCol; attribute float aSize; attribute float aSeed; attribute float aKind;
    varying vec3 vC; varying float vA;
    vec3 h3(float s){ return fract(sin(vec3(s*127.1,s*311.7,s*74.7))*43758.5453); }
    void main(){
      vec3 p = position; float k = aKind; float vis = 1.0; vec3 col = aCol;
      if (k < 0.5) vis = 1.0 - 0.78*uCut;
      else if (k < 2.5) vis = uCut;
      else if (k < 3.5) vis = 1.0;
      else if (k < 4.5) { vis = 1.0 - 0.55*uCut; float fl = 0.75 + 0.25*sin(uTime*11.0 + aSeed*40.0);
        col = mix(col, vec3(1.0,0.42,0.12), clamp(uHeat*fl*1.2, 0.0, 1.0)); vis *= 1.0 + uHeat*1.4; }
      else if (k < 5.5) vis = 1.0;
      else if (k < 6.5) vis = uCut;
      else if (k < 7.5) { vis = uCut*uFlow; p.y -= fract(aSeed*7.31 + uTime*0.45)*uFlowLen; }
      else if (k < 8.5) { p.y += 0.05*sin(uTime*1.1 + p.x*1.3 + p.z*0.7); }
      else if (k >= 29.5) { float r = k - 30.0;
        float g = r < 0.5 ? uRing.x : r < 1.5 ? uRing.y : r < 2.5 ? uRing.z : uRing.w;
        float s = r < 0.5 ? uSel.x : r < 1.5 ? uSel.y : r < 2.5 ? uSel.z : uSel.w;
        col = mix(col, vec3(1.0,0.66,0.32), clamp(max(g, s), 0.0, 1.0)); vis = 1.0 + g*1.6 + s*1.0; }
      float d = clamp(uAppear*1.6 - aSeed*0.6, 0.0, 1.0); d = d*d*(3.0 - 2.0*d);
      p = mix(p + (h3(aSeed) * 2.0 - 1.0) * uSpread, p, d);
      vec4 mv = modelViewMatrix * vec4(p, 1.0);
      gl_Position = projectionMatrix * mv;
      if (k > 8.5 && k < 9.5) { vec3 n = normalize(normalMatrix * normalize(position)); float f = 1.0 - abs(dot(n, normalize(-mv.xyz))); vis = pow(f, 3.0) * 1.8; }
      float tw = 0.82 + 0.18*sin(uTime*2.1 + aSeed*61.0);
      vA = vis * uAlpha * d * tw; vC = col;
      float sz = aSize * uPt * uPixel / max(0.1, -mv.z);
      if (sz < 1.4*uPixel) { vA *= sz / (1.4*uPixel); sz = 1.4*uPixel; }
      gl_PointSize = min(sz, 7.0*uPixel);
    }`;
  const DOT_F = `varying vec3 vC; varying float vA;
    void main(){ vec2 c = gl_PointCoord - 0.5; float r = dot(c,c)*4.0; if (r > 1.0) discard; float a = 1.0 - r; gl_FragColor = vec4(vC, vA*a*a); }`;
  function structMat(o = {}) {
    const u = { ...G, uAlpha: { value: o.alpha ?? 1 }, uCut: { value: 0 }, uHeat: { value: 0 }, uFlow: { value: 0 }, uFlowLen: { value: o.flowLen ?? 3 },
      uRing: { value: new THREE.Vector4() }, uSel: { value: new THREE.Vector4() } };
    return new THREE.ShaderMaterial({ uniforms: u, vertexShader: STRUCT_V, fragmentShader: DOT_F, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending });
  }
  const JET_V = `
    uniform float uTime,uPixel,uPt,uAppear,uLen,uWide,uRate,uAlpha,uDiamond,uSpread0,uSize;
    uniform vec4 uOn; uniform vec3 uC0,uC1,uC2;
    attribute vec3 aDir; attribute vec4 aR; attribute float aSeed;
    varying vec3 vC; varying float vA;
    void main(){
      float ring = aR.x; float on = ring < 0.5 ? uOn.x : ring < 1.5 ? uOn.y : ring < 2.5 ? uOn.z : uOn.w;
      on *= uAppear;
      if (on < 0.004) { gl_Position = vec4(2.0,2.0,2.0,1.0); gl_PointSize = 0.0; vA = 0.0; vC = vec3(0.0); return; }
      float life = fract(aSeed + uTime*uRate*(0.75 + 0.5*aR.z));
      vec3 dir = normalize(aDir);
      vec3 t1 = normalize(cross(dir, abs(dir.y) < 0.9 ? vec3(0.0,1.0,0.0) : vec3(1.0,0.0,0.0))); vec3 t2 = cross(dir, t1);
      float L = uLen*aR.w*(0.45 + 0.55*on);
      float along = life*L;
      float th = aSeed*91.7 + aR.z*13.0;
      float rr = aR.y*(uSpread0 + life*uWide)*sqrt(fract(aSeed*13.7));
      vec3 p = position + dir*along + (t1*cos(th) + t2*sin(th))*rr;
      float dia = 1.0 - uDiamond*0.6*(0.5 - 0.5*cos(along/(aR.y*3.2 + 0.0001)*6.2832))*(1.0 - life);
      vC = life < 0.22 ? mix(uC0, uC1, life/0.22) : mix(uC1, uC2, (life - 0.22)/0.78);
      vA = on*uAlpha*pow(1.0 - life, 1.4)*dia;
      vec4 mv = modelViewMatrix*vec4(p, 1.0); gl_Position = projectionMatrix*mv;
      float sz = (1.0 + life*2.2)*uSize*uPt*uPixel/max(0.1, -mv.z);
      if (sz < 1.3*uPixel) { vA *= sz/(1.3*uPixel); sz = 1.3*uPixel; }
      gl_PointSize = min(sz, 14.0*uPixel);
    }`;
  function jetMat(o) {
    const v3 = (c) => new THREE.Vector3(...c);
    const u = { uTime: G.uTime, uPixel: G.uPixel, uPt: G.uPt, uAppear: { value: 1 }, uLen: { value: o.len ?? 2 }, uWide: { value: o.wide ?? 1 },
      uRate: { value: o.rate ?? 1.2 }, uAlpha: { value: o.alpha ?? 0.3 }, uDiamond: { value: o.diamond ?? 0 }, uSpread0: { value: o.spread0 ?? 0.6 },
      uSize: { value: o.size ?? 1 }, uOn: { value: new THREE.Vector4() },
      uC0: { value: v3(o.c0 || [1, 1, 1]) }, uC1: { value: v3(o.c1 || [1, 0.6, 0.3]) }, uC2: { value: v3(o.c2 || [0.6, 0.2, 0.1]) } };
    return new THREE.ShaderMaterial({ uniforms: u, vertexShader: JET_V, fragmentShader: DOT_F, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending });
  }

  /* ---------------- point-cloud builders ---------------- */
  class Pts {
    constructor() { this.p = []; this.c = []; this.s = []; this.k = []; this.e = []; }
    add(x, y, z, c, s = 1, k = 0) { this.p.push(x, y, z); this.c.push(c[0], c[1], c[2]); this.s.push(s); this.k.push(k); this.e.push(Math.random()); }
    ring(cx, y, cz, r, n, c, s, k, a0 = 0, a1 = Math.PI * 2) { const full = Math.abs(a1 - a0 - Math.PI * 2) < 1e-6; const m = full ? n : n - 1;
      for (let i = 0; i < n; i++) { const a = a0 + (a1 - a0) * i / m; this.add(cx + Math.cos(a) * r, y, cz + Math.sin(a) * r, c, s, k); } }
    line(a, b, step, c, s, k) { const d = Math.hypot(b[0] - a[0], b[1] - a[1], b[2] - a[2]); const n = Math.max(2, Math.ceil(d / step));
      for (let i = 0; i <= n; i++) { const t = i / n; this.add(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, c, s, k); } }
    fill(n, fn) { for (let i = 0; i < n; i++) fn(this); }
    mesh(mat) {
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.Float32BufferAttribute(this.p, 3));
      g.setAttribute('aCol', new THREE.Float32BufferAttribute(this.c, 3));
      g.setAttribute('aSize', new THREE.Float32BufferAttribute(this.s, 1));
      g.setAttribute('aKind', new THREE.Float32BufferAttribute(this.k, 1));
      g.setAttribute('aSeed', new THREE.Float32BufferAttribute(this.e, 1));
      const m = new THREE.Points(g, mat); m.frustumCulled = false; return m;
    }
  }
  function jetMesh(list, mat) {    /* list: [{o:[x,y,z], d:[x,y,z], ring, r, w}] repeated per particle */
    const P = [], D = [], R = [], S = [];
    list.forEach(q => { P.push(...q.o); D.push(...q.d); R.push(q.ring || 0, q.r ?? 0.05, Math.random(), q.w ?? 1); S.push(Math.random()); });
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(P, 3)); g.setAttribute('aDir', new THREE.Float32BufferAttribute(D, 3));
    g.setAttribute('aR', new THREE.Float32BufferAttribute(R, 4)); g.setAttribute('aSeed', new THREE.Float32BufferAttribute(S, 1));
    const m = new THREE.Points(g, mat); m.frustumCulled = false; return m;
  }
  const rnd = (a, b) => a + Math.random() * (b - a);
  const TAU = Math.PI * 2;

  /* hull of revolution: rings + generatrix lines, radius by height */
  function hull(P, y0, y1, rOf, ringStep, nRing, nLines, col, kind = 0) {
    for (let y = y0; y <= y1 + 1e-6; y += ringStep) { const r = rOf(y); if (r > 0.01) P.ring(0, y, 0, r, Math.max(8, Math.round(nRing * r / 0.45)), col, 0.85, kind); }
    for (let j = 0; j < nLines; j++) { const a = TAU * j / nLines;
      for (let y = y0; y <= y1; y += 0.055) { const r = rOf(y); if (r > 0.005) P.add(Math.cos(a) * r, y, Math.sin(a) * r, col, 0.75, kind); } }
  }
  function fillTank(P, n, y0, y1, r, col, kind) { P.fill(n, p => { const a = rnd(0, TAU), rr = r * Math.sqrt(Math.random()); p.add(Math.cos(a) * rr, rnd(y0, y1), Math.sin(a) * rr, col, 0.9, kind); });
    for (let y = y0; y <= y1; y += 0.25) P.ring(0, y, 0, r, 40, col, 0.7, kind); }
  function dome(P, y, r, h, col, kind, up = 1) { for (let i = 1; i <= 5; i++) { const rr = r * i / 5; P.ring(0, y + up * h * (1 - (rr / r) ** 2), 0, rr, Math.round(10 + 30 * i / 5), col, 0.8, kind); } }
  function bell(P, x, z, yTop, len, rExit, ring) {
    const k = 30 + ring, prof = (t) => 0.028 + (rExit - 0.028) * Math.pow(t, 0.8);
    [0, 0.33, 0.66, 1].forEach(t => P.ring(x, yTop - len * t, z, prof(t), Math.round(10 + 14 * t), C.bright, t === 1 ? 1.2 : 0.8, k));
    for (let j = 0; j < 8; j++) { const a = TAU * j / 8; for (let t = 0; t <= 1; t += 0.12) { const r = prof(t); P.add(x + Math.cos(a) * r, yTop - len * t, z + Math.sin(a) * r, C.holo, 0.7, k); } }
    P.ring(x, yTop + 0.05, z, 0.035, 8, C.holo, 0.8, k);
    return [x, yTop - len - 0.01, z];
  }

  /* ---------------- the booster: Super Heavy V3, 72.3 m, 33 Raptor 3 ---------------- */
  const BH = 7.23, SH = 5.21, R = 0.45;
  function buildBooster() {
    const g = new THREE.Group(), P = new Pts(), mat = structMat({ flowLen: 3.0 });
    hull(P, 0, 6.88, () => R, 0.14, 56, 28, C.holo);
    [0, 0.62, 3.65, 6.88].forEach(y => P.ring(0, y, 0, R, 90, C.bright, 1.2, 5));
    /* hot-staging ring: vented, so the ship's flame can get out */
    for (let j = 0; j < 30; j++) { const a = TAU * j / 30; P.line([Math.cos(a) * R, 6.9, Math.sin(a) * R], [Math.cos(a) * R, 7.22, Math.sin(a) * R], 0.02, C.bright, 1.0, 5); }
    P.ring(0, 7.23, 0, R, 90, C.bright, 1.2, 5); dome(P, 6.95, R * 0.95, 0.12, C.holo, 3);
    /* insides */
    fillTank(P, 2200, 0.7, 3.6, 0.42, C.lox, 1); dome(P, 3.62, 0.43, 0.14, C.lox, 1, -1);
    fillTank(P, 1700, 3.7, 6.8, 0.42, C.ch4, 2); dome(P, 3.66, 0.43, 0.14, C.ch4, 2, -1);
    for (let y = 0.62; y < 3.66; y += 0.08) P.ring(0, y, 0, 0.075, 14, C.ch4, 0.9, 6);
    P.fill(380, p => { const a = rnd(0, TAU), r = rnd(0, 0.06); p.add(Math.cos(a) * r, 3.65, Math.sin(a) * r, C.ch4, 1.2, 7); });
    /* 33 engines: 3 centre + 10 inner gimbal, 20 outer fixed */
    const exits = [];
    [[3, 0.1, 0], [10, 0.245, 1], [20, 0.385, 2]].forEach(([n, r, ring]) => { for (let i = 0; i < n; i++) { const a = TAU * i / n + (ring === 0 ? Math.PI / 2 : ring === 1 ? 0.3 : 0);
      exits.push({ p: bell(P, Math.cos(a) * r, Math.sin(a) * r, 0.3, 0.3, 0.06, ring), ring }); } });
    /* detail (v2): two raceways down the body, X-bracing in the hot-staging ring, Raptor powerheads in the engine bay,
       the aft skirt and its heat shield, chilldown vents on the methane dome */
    [0.35, 0.35 + Math.PI].forEach(a0 => { [-0.07, 0.07].forEach(da => P.line([Math.cos(a0 + da) * (R + 0.03), 0.66, Math.sin(a0 + da) * (R + 0.03)], [Math.cos(a0 + da) * (R + 0.03), 6.86, Math.sin(a0 + da) * (R + 0.03)], 0.03, C.bright, 0.8, 0));
      for (let y = 0.7; y < 6.86; y += 0.12) P.ring(0, y, 0, R + 0.03, 4, C.holo, 0.75, 0, a0 - 0.07, a0 + 0.07); });
    for (let j = 0; j < 30; j++) { const a = TAU * j / 30, b = TAU * (j + 1) / 30; P.line([Math.cos(a) * R, 6.9, Math.sin(a) * R], [Math.cos(b) * R, 7.22, Math.sin(b) * R], 0.025, C.holo, 0.75, 5); }
    for (let y = 0.02; y < 0.62; y += 0.06) P.ring(0, y, 0, R + 0.012, 70, C.dim, 0.7, 0);
    P.ring(0, 0.3, 0, 0.42, 70, C.bright, 0.9, 6); for (let r = 0.06; r < 0.42; r += 0.06) P.ring(0, 0.3, 0, r, Math.round(10 + r * 80), C.dim, 0.7, 6);
    [[3, 0.1], [10, 0.245], [20, 0.385]].forEach(([n, r], ri) => { for (let i = 0; i < n; i++) { const a = TAU * i / n + (ri === 0 ? Math.PI / 2 : ri === 1 ? 0.3 : 0), x = Math.cos(a) * r, z = Math.sin(a) * r;
      P.fill(18, p => { const u = rnd(-1, 1), t = rnd(0, TAU), s = Math.sqrt(1 - u * u); p.add(x + Math.cos(t) * s * 0.035, 0.38 + u * 0.035, z + Math.sin(t) * s * 0.035, C.ch4, 0.8, 6); });
      P.line([x, 0.3, z], [x, 0.5, z], 0.03, C.lox, 0.8, 6); } });
    for (let j = 0; j < 8; j++) { const a = TAU * j / 8 + 0.2; P.fill(14, p => p.add(Math.cos(a) * (R + 0.01), rnd(6.4, 6.85), Math.sin(a) * (R + 0.01), C.bright, 0.7, 0)); }
    const mesh = P.mesh(mat); g.add(mesh);
    /* 3 grid fins (V3: one fewer than V2, each 50% larger) */
    const fins = [];
    [Math.PI / 2, Math.PI / 2 + TAU / 3, Math.PI / 2 + 2 * TAU / 3].forEach(a => {
      const pivot = new THREE.Group(); pivot.position.set(Math.cos(a) * R, 6.55, Math.sin(a) * R); pivot.rotation.y = -a;
      const fin = new THREE.Group(); pivot.add(fin); const F = new Pts();
      for (let i = 0; i <= 10; i++) { const t = i / 10; F.line([0.03 + 0.5 * t, 0.06, -0.26], [0.03 + 0.5 * t, 0.06, 0.26], 0.022, C.bright, 0.9, 5); F.line([0.03, 0.06, -0.26 + 0.52 * t], [0.53, 0.06, -0.26 + 0.52 * t], 0.022, C.bright, 0.9, 5); }
      [[0.03, -0.26], [0.53, -0.26], [0.53, 0.26], [0.03, 0.26]].forEach(([x, z]) => F.line([x, -0.06, z], [x, 0.06, z], 0.02, C.bright, 1, 5));
      F.line([0.03, -0.06, -0.26], [0.53, -0.06, -0.26], 0.025, C.holo, 0.8, 5); F.line([0.03, -0.06, 0.26], [0.53, -0.06, 0.26], 0.025, C.holo, 0.8, 5);
      fin.add(F.mesh(mat)); g.add(pivot); fins.push(fin);
    });
    /* catch points, where the arms take the weight */
    const C2 = new Pts(); [-1, 1].forEach(s => C2.fill(40, p => p.add(rnd(-0.08, 0.08), 6.32 + rnd(-0.03, 0.03), s * (R + 0.03), C.hot, 1.3, 5))); g.add(C2.mesh(mat));
    /* plumes */
    const jl = [];
    exits.forEach(e => { for (let i = 0; i < 230; i++) jl.push({ o: e.p, d: [0, -1, 0], ring: e.ring, r: 0.06, w: rnd(0.7, 1.1) }); });
    const plumeMat = jetMat({ len: 2.6, wide: 1.4, rate: 1.6, alpha: 0.09, diamond: 1, spread0: 0.7, size: 2.6, c0: [0.9, 0.95, 1], c1: [1, 0.62, 0.28], c2: [0.7, 0.22, 0.08] });
    const plume = jetMesh(jl, plumeMat); g.add(plume);
    /* vents (hot staging) */
    const vl = []; for (let j = 0; j < 30; j++) { const a = TAU * (j + 0.5) / 30; for (let i = 0; i < 60; i++) vl.push({ o: [Math.cos(a) * R, 7.06, Math.sin(a) * R], d: [Math.cos(a), -0.35, Math.sin(a)], r: 0.04, w: rnd(0.6, 1) }); }
    const ventMat = jetMat({ len: 1.1, wide: 2.5, rate: 1.8, alpha: 0.22, c0: [1, 0.95, 0.8], c1: [1, 0.6, 0.25], c2: [0.6, 0.18, 0.06] });
    const vents = jetMesh(vl, ventMat); g.add(vents);
    /* vapour cone (Max-Q) */
    const ql = []; for (let i = 0; i < 1600; i++) { const a = rnd(0, TAU); ql.push({ o: [Math.cos(a) * 0.5, rnd(6.6, 7.4), Math.sin(a) * 0.5], d: [Math.cos(a) * 0.6, -1, Math.sin(a) * 0.6], r: 0.3, w: rnd(0.5, 1) }); }
    const vapMat = jetMat({ len: 2.2, wide: 1.5, rate: 0.9, alpha: 0.12, c0: [0.9, 0.95, 1], c1: [0.7, 0.8, 0.9], c2: [0.3, 0.4, 0.5] });
    const vapour = jetMesh(ql, vapMat); g.add(vapour);
    return { g, mat, fins, plumeMat, ventMat, vapMat };
  }

  /* ---------------- the ship: Starship upper stage, ~52 m, 6 Raptor 3 ---------------- */
  function buildShip() {
    const g = new THREE.Group(), P = new Pts(), mat = structMat({ flowLen: 1.7 });
    const nose0 = 4.0, rOf = (y) => y <= nose0 ? R : R * Math.pow(Math.max(0, 1 - Math.pow((y - nose0) / (SH - nose0), 1.9)), 0.55);
    hull(P, 0, SH, rOf, 0.14, 56, 28, C.holo);
    [0, 0.62, 2.35, 3.6].forEach(y => P.ring(0, y, 0, R, 90, C.bright, 1.1, 5));
    /* heat shield: ~18,000 hexagonal tiles on the windward half (-z) */
    for (let y = 0.08, row = 0; y < SH - 0.12; y += 0.038, row++) { const r = rOf(y), ds = 0.044, n = Math.floor(Math.PI * r / ds);
      for (let i = 0; i <= n; i++) { const a = Math.PI + Math.PI * (i + (row % 2) * 0.5) / (n + 0.5); P.add(Math.cos(a) * (r + 0.006), y, Math.sin(a) * (r + 0.006), C.tile, 0.85, 4); } }
    /* insides: LOX aft, methane forward, header tanks in the nose */
    fillTank(P, 1400, 0.66, 2.3, 0.42, C.lox, 1); dome(P, 2.33, 0.43, 0.12, C.lox, 1, -1);
    fillTank(P, 1100, 2.4, 3.55, 0.42, C.ch4, 2);
    for (let y = 0.62; y < 2.36; y += 0.08) P.ring(0, y, 0, 0.065, 12, C.ch4, 0.9, 6);
    P.fill(240, p => { const a = rnd(0, TAU), r = rnd(0, 0.05); p.add(Math.cos(a) * r, 2.35, Math.sin(a) * r, C.ch4, 1.2, 7); });
    const sphere = (cy, r, col, n) => P.fill(n, p => { const u = rnd(-1, 1), a = rnd(0, TAU), s = Math.sqrt(1 - u * u); p.add(Math.cos(a) * s * r, cy + u * r, Math.sin(a) * s * r, col, 0.7, col === C.lox ? 1 : 2); });
    sphere(4.82, 0.12, C.lox, 160); sphere(4.52, 0.12, C.ch4, 160);
    P.line([0.2, 4.4, 0.18], [0.2, 0.62, 0.18], 0.04, C.ch4, 0.9, 6); P.line([-0.2, 4.7, 0.18], [-0.2, 0.62, 0.18], 0.04, C.lox, 0.9, 6);
    /* payload bay with the Starlink "PEZ" slot on the leeward side (+z) */
    const sa = Math.PI / 2, sw = 0.34, sy0 = 3.66, sy1 = 4.3;
    P.ring(0, sy0, 0, R + 0.01, 24, C.bright, 1.1, 5, sa - sw, sa + sw); P.ring(0, sy1, 0, R + 0.01, 24, C.bright, 1.1, 5, sa - sw, sa + sw);
    [sa - sw, sa + sw].forEach(a => P.line([Math.cos(a) * (R + 0.01), sy0, Math.sin(a) * (R + 0.01)], [Math.cos(a) * (R + 0.01), sy1, Math.sin(a) * (R + 0.01)], 0.02, C.bright, 1.1, 5));
    /* 6 engines: 3 sea-level (gimbal) in the centre, 3 vacuum around them */
    const exits = [];
    [0, 1, 2].forEach(i => { const a = Math.PI / 2 + TAU * i / 3; exits.push({ p: bell(P, Math.cos(a) * 0.11, Math.sin(a) * 0.11, 0.33, 0.3, 0.06, i === 0 ? 0 : 1), ring: i === 0 ? 0 : 1, r: 0.06 }); });
    [0, 1, 2].forEach(i => { const a = Math.PI / 6 + TAU * i / 3; exits.push({ p: bell(P, Math.cos(a) * 0.3, Math.sin(a) * 0.3, 0.5, 0.46, 0.115, 2), ring: 2, r: 0.11 }); });
    /* detail (v2): tile edge lines, the leeward raceway, RCS thruster pods, aft skirt with the RVac shield, nose tip */
    [Math.PI + 0.02, TAU - 0.02].forEach(a => { for (let y = 0.08; y < SH - 0.1; y += 0.03) { const r = rOf(y) + 0.008; P.add(Math.cos(a) * r, y, Math.sin(a) * r, C.bright, 0.9, 5); } });
    { const a0 = Math.PI / 2 + 0.7; [-0.06, 0.06].forEach(da => { for (let y = 0.66; y < 4.6; y += 0.03) { const r = rOf(y) + 0.025; P.add(Math.cos(a0 + da) * r, y, Math.sin(a0 + da) * r, C.bright, 0.75, 0); } }); }
    [[4.75, 0], [4.75, Math.PI], [0.75, Math.PI / 2], [0.75, -Math.PI / 2]].forEach(([y, a]) => { const r = rOf(y) + 0.02;
      P.fill(24, p => p.add(Math.cos(a) * r + rnd(-0.03, 0.03), y + rnd(-0.04, 0.04), Math.sin(a) * r + rnd(-0.03, 0.03), C.hot, 0.9, 5)); });
    for (let y = 0.02; y < 0.62; y += 0.06) P.ring(0, y, 0, R + 0.012, 70, C.dim, 0.7, 0);
    P.ring(0, 0.5, 0, 0.42, 70, C.bright, 0.9, 6); for (let r = 0.07; r < 0.42; r += 0.07) P.ring(0, 0.5, 0, r, Math.round(10 + r * 80), C.dim, 0.7, 6);
    P.ring(0, SH - 0.04, 0, 0.04, 10, C.bright, 1.1, 5);
    g.add(P.mesh(mat));
    /* four flaps, hinged along the body: two aft, two forward (leeward-swept) */
    const flaps = [];
    const flap = (side, y0, y1, w0, w1, sweep) => {
      const pivot = new THREE.Group(); pivot.position.set(side * R * 0.97, 0, -0.1); g.add(pivot);
      const F = new Pts(), sx = side;
      const edge = (t) => [sx * (w0 + (w1 - w0) * t), y0 + (y1 - y0) * t];
      F.line([0, y0, 0], [0, y1, 0], 0.03, C.bright, 1.1, 5);
      F.line([0, y0, 0], [sx * w0, y0, 0], 0.025, C.bright, 1, 5); F.line([0, y1, 0], [sx * w1, y1, 0], 0.025, C.bright, 1, 5);
      F.line([sx * w0, y0, 0], [sx * w1, y1, 0], 0.025, C.bright, 1, 5);
      for (let i = 1; i < 6; i++) { const t = i / 6, [ex, ey] = edge(t); F.line([0, ey, 0], [ex, ey, 0], 0.035, C.holo, 0.8, 5); }
      for (let y = y0 + 0.04; y < y1; y += 0.05) for (let x = 0.03; x < Math.abs(edge((y - y0) / (y1 - y0))[0]); x += 0.05) F.add(sx * x, y, -0.008, C.tile, 0.8, 4);
      const m = F.mesh(mat); pivot.add(m); pivot.userData.base = sweep * side; pivot.rotation.y = pivot.userData.base; flaps.push(pivot);
      return pivot;
    };
    flap(1, 0.12, 1.45, 0.4, 0.3, 0); flap(-1, 0.12, 1.45, 0.4, 0.3, 0);
    flap(1, 3.85, 4.55, 0.24, 0.17, -0.45); flap(-1, 3.85, 4.55, 0.24, 0.17, -0.45);
    /* plumes: SL engine A (ring 0), SL B+C (ring 1), vacuum (ring 2) */
    const jl = []; exits.forEach(e => { for (let i = 0; i < (e.ring === 2 ? 700 : 450); i++) jl.push({ o: e.p, d: [0, -1, 0], ring: e.ring, r: e.r, w: rnd(0.7, 1.1) }); });
    const plumeMat = jetMat({ len: 2.4, wide: 1.6, rate: 1.6, alpha: 0.14, diamond: 1, spread0: 0.7, size: 2.6, c0: [0.9, 0.95, 1], c1: [1, 0.62, 0.3], c2: [0.6, 0.22, 0.1] });
    const plume = jetMesh(jl, plumeMat); g.add(plume);
    /* reentry plasma along the belly */
    const pl = []; for (let i = 0; i < 5200; i++) { const y = rnd(0.1, SH - 0.15), r = rOf(y) + 0.02, a = rnd(Math.PI * 1.05, Math.PI * 1.95);
      pl.push({ o: [Math.cos(a) * r, y, Math.sin(a) * r], d: [Math.cos(a) * 0.12, -1, Math.sin(a) * 0.35 - 0.05], r: 0.06, w: y + rnd(0.8, 2.4) }); }
    const plasmaMat = jetMat({ len: 1, wide: 3.5, rate: 1.4, alpha: 0.2, spread0: 0.4, c0: [1, 0.92, 0.75], c1: [1, 0.48, 0.18], c2: [0.75, 0.18, 0.55] });
    const plasma = jetMesh(pl, plasmaMat); g.add(plasma);
    return { g, mat, flaps, plumeMat, plasmaMat };
  }

  /* ---------------- launch tower with its arms ("chopsticks") ---------------- */
  function buildTower() {
    const g = new THREE.Group(), P = new Pts(), mat = structMat(), H = 14.6, w = 0.45;
    const cs = [[-w, -w], [w, -w], [w, w], [-w, w]];
    cs.forEach(([x, z]) => P.line([x, 0, z], [x, H, z], 0.045, C.holo, 1.0, 0));
    for (let y = 0; y <= H; y += 0.9) for (let i = 0; i < 4; i++) { const a = cs[i], b = cs[(i + 1) % 4]; P.line([a[0], y, a[1]], [b[0], y, b[1]], 0.05, C.holo, 0.8, 0);
      if (y + 0.9 <= H) P.line([a[0], y, a[1]], [b[0], y + 0.9, b[1]], 0.06, C.dim, 0.7, 0); }
    P.line([0, H, 0], [0, H + 0.9, 0], 0.04, C.bright, 1, 5);
    /* detail (v2): the other diagonal of every bay, work platforms, the carriage rails, and the ship quick-disconnect arm */
    for (let y = 0; y + 0.9 <= H; y += 0.9) for (let i = 0; i < 4; i++) { const a = cs[i], b = cs[(i + 1) % 4]; P.line([b[0], y, b[1]], [a[0], y + 0.9, a[1]], 0.07, C.dim, 0.65, 0); }
    for (let y = 2.7; y < H; y += 2.7) { for (let x = -w; x <= w + 1e-6; x += 0.15) P.line([x, y, -w], [x, y, w], 0.15, C.holo, 0.6, 0); }
    [-0.3, 0.3].forEach(z => P.line([w + 0.04, 0.2, z], [w + 0.04, H - 0.3, z], 0.05, C.bright, 0.85, 0));
    { const yq = 12.35; [[-0.06, -0.06], [-0.06, 0.06], [0.06, 0.06], [0.06, -0.06]].forEach(([dy, dz]) => P.line([w, yq + dy, dz], [w + 1.0, yq + dy, dz], 0.03, C.bright, 0.9, 5));
      for (let x = w; x < w + 1.0; x += 0.12) P.line([x, yq - 0.06, -0.06], [x + 0.12, yq + 0.06, 0.06], 0.03, C.holo, 0.7, 5);
      P.ring(w + 1.0, yq, 0, 0.08, 14, C.hot, 1.0, 5); }
    const mesh = P.mesh(mat); g.add(mesh);
    const carriage = new THREE.Group(); g.add(carriage);
    const arms = [];
    [1, -1].forEach(s => { const piv = new THREE.Group(); piv.position.set(w, 0, s * 0.56); carriage.add(piv);
      const A = new Pts(), L = 2.55;
      [[-0.07, -0.05], [0.07, -0.05], [0.07, 0.05], [-0.07, 0.05]].forEach(([y, z]) => A.line([0, y, z], [L, y, z], 0.03, C.bright, 1.0, 5));
      for (let x = 0; x < L; x += 0.15) { A.line([x, -0.07, -0.05], [x + 0.15, 0.07, 0.05], 0.03, C.holo, 0.7, 5); }
      A.line([L, -0.07, 0], [L, 0.07, 0], 0.02, C.hot, 1.2, 5);
      piv.add(A.mesh(mat)); piv.userData.s = s; arms.push(piv);
    });
    const C2 = new Pts(); C2.line([w, -0.15, -0.7], [w, 0.15, -0.7], 0.03, C.bright, 1, 5); C2.line([w, -0.15, 0.7], [w, 0.15, 0.7], 0.03, C.bright, 1, 5); carriage.add(C2.mesh(mat));
    const t = { g, mat, carriage, arms, open: 0.6 };
    t.set = (h, ang) => { carriage.position.y = h; arms.forEach(a => { a.rotation.y = -a.userData.s * ang; }); };
    t.set(10, 0.6);
    return t;
  }

  /* ---------------- pad: ground, shoreline, launch mount with water-cooled steel plate ---------------- */
  function buildPad() {
    const g = new THREE.Group(), P = new Pts(), mat = structMat();
    for (let x = -36; x <= 36; x += 0.55) for (let z = -36; z <= 36; z += 0.55) { const d = Math.hypot(x, z); if (d > 36) continue; const f = Math.max(0.15, 1 - d / 36);
      const sea = z > 10 + Math.sin(x * 0.15) * 1.5; const c = sea ? C.sea : C.ground; P.add(x + rnd(-0.1, 0.1), 0, z + rnd(-0.1, 0.1), [c[0] * f, c[1] * f, c[2] * f], 0.9, sea ? 8 : 0); }
    for (let i = -3; i <= 3; i++) { P.line([-3, 0.01, i], [3, 0.01, i], 0.08, C.dim, 0.8, 3); P.line([i, 0.01, -3], [i, 0.01, 3], 0.08, C.dim, 0.8, 3); }
    /* mount */
    [1.85, 2.0].forEach(y => P.ring(0, y, 0, 0.62, 64, C.bright, 1.0, 5)); P.ring(0, 2.0, 0, 0.8, 64, C.holo, 0.9, 5);
    for (let i = 0; i < 6; i++) { const a = TAU * i / 6 + 0.5; P.line([Math.cos(a) * 0.75, 0, Math.sin(a) * 0.75], [Math.cos(a) * 0.68, 1.85, Math.sin(a) * 0.68], 0.04, C.holo, 1, 5); }
    for (let r = 0.1; r <= 1.0; r += 0.1) P.ring(0, 0.03, 0, r, Math.round(12 + r * 50), [0.5, 0.75, 0.9], 0.8, 5);
    /* detail (v2): 20 hold-down clamps, the tank farm (LOX, methane, nitrogen) and the propellant lines to the mount */
    for (let i = 0; i < 20; i++) { const a = TAU * i / 20; P.line([Math.cos(a) * 0.5, 1.92, Math.sin(a) * 0.5], [Math.cos(a) * 0.5, 2.1, Math.sin(a) * 0.5], 0.02, C.hot, 0.9, 5);
      P.line([Math.cos(a) * 0.5, 2.1, Math.sin(a) * 0.5], [Math.cos(a) * 0.44, 2.1, Math.sin(a) * 0.44], 0.02, C.hot, 0.9, 5); }
    const farm = [[-9, -6, 0.42, 3.2, C.lox], [-8, -7.1, 0.42, 3.2, C.lox], [-10.1, -5.2, 0.42, 3.2, C.lox], [-8.9, -4.6, 0.36, 2.6, C.ch4], [-7.7, -5.6, 0.36, 2.6, C.ch4], [-10.4, -6.8, 0.3, 2.2, C.holo], [-9.6, -8.0, 0.3, 2.2, C.holo]];
    farm.forEach(([x, z, r, h, c]) => { for (let y = 0; y <= h; y += 0.16) P.ring(x, y, z, r, 26, c, 0.75, 5); for (let j = 0; j < 8; j++) { const a = TAU * j / 8; P.line([x + Math.cos(a) * r, 0, z + Math.sin(a) * r], [x + Math.cos(a) * r, h, z + Math.sin(a) * r], 0.08, c, 0.7, 5); }
      for (let k = 1; k <= 4; k++) P.ring(x, h + 0.1 * Math.sin(k / 4 * Math.PI / 2), z, r * Math.cos(k / 4 * Math.PI / 2), 18, c, 0.75, 5); });
    [[0.1, C.lox], [-0.1, C.ch4]].forEach(([dz, c]) => { P.line([-8.6, 0.25, -6 + dz], [-1.2, 0.25, -0.4 + dz], 0.09, c, 0.85, 5); P.line([-1.2, 0.25, -0.4 + dz], [-0.7, 1.9, -0.2 + dz], 0.06, c, 0.85, 5); });
    const mesh = P.mesh(mat); g.add(mesh);
    const sl = []; for (let i = 0; i < 3800; i++) { const a = rnd(0, TAU); sl.push({ o: [Math.cos(a) * 0.5, rnd(0.05, 0.4), Math.sin(a) * 0.5], d: [Math.cos(a), rnd(0.05, 0.5), Math.sin(a)], r: 0.6, w: rnd(0.4, 1.2) }); }
    const steamMat = jetMat({ len: 6, wide: 3.5, rate: 0.35, alpha: 0.12, spread0: 0.4, size: 2.2, c0: [1, 0.85, 0.6], c1: [0.8, 0.85, 0.9], c2: [0.3, 0.38, 0.45] });
    const steam = jetMesh(sl, steamMat); g.add(steam);
    return { g, mat, steamMat };
  }

  /* ---------------- Earth and its limb ---------------- */
  function buildEarth() {
    const g = new THREE.Group(), P = new Pts(), mat = structMat(), RE = 24;
    const h = (x, y, z) => { const s = Math.sin(x * 127.1 + y * 311.7 + z * 74.7) * 43758.5453; return s - Math.floor(s); };
    const vn = (x, y, z) => { const X = Math.floor(x), Y = Math.floor(y), Z = Math.floor(z), fx = x - X, fy = y - Y, fz = z - Z, s = (t) => t * t * (3 - 2 * t);
      let v = 0; for (let i = 0; i < 2; i++) for (let j = 0; j < 2; j++) for (let k = 0; k < 2; k++) v += h(X + i, Y + j, Z + k) * (i ? s(fx) : 1 - s(fx)) * (j ? s(fy) : 1 - s(fy)) * (k ? s(fz) : 1 - s(fz)); return v; };
    const N = 32000, ga = Math.PI * (3 - Math.sqrt(5));
    for (let i = 0; i < N; i++) { const y = 1 - 2 * (i + 0.5) / N, r = Math.sqrt(1 - y * y), a = ga * i, x = Math.cos(a) * r, z = Math.sin(a) * r;
      let n = 0, f = 1.6, amp = 0.5; for (let o = 0; o < 4; o++) { n += vn(x * f + 7, y * f + 3, z * f + 11) * amp; f *= 2.1; amp *= 0.5; }
      const land = n > 0.5; P.add(x * RE, y * RE, z * RE, land ? C.land : C.sea, land ? 1.0 : 0.8, 3); }
    for (let i = 0; i < 9000; i++) { const u = rnd(-1, 1), a = rnd(0, TAU), s = Math.sqrt(1 - u * u), rr = RE * rnd(1.01, 1.035); P.add(Math.cos(a) * s * rr, u * rr, Math.sin(a) * s * rr, [0.4, 0.75, 1.0], 1.3, 9); }
    g.add(P.mesh(mat)); return { g, mat, RE };
  }

  /* ---------------- a Starlink V3 satellite ---------------- */
  const satGeo = (() => { const P = new Pts();
    for (let x = -0.14; x <= 0.14; x += 0.025) for (let z = -0.06; z <= 0.06; z += 0.025) P.add(x, 0, z, C.bright, 0.9, 5);
    for (let x = 0.16; x <= 0.75; x += 0.03) for (let z = -0.05; z <= 0.05; z += 0.03) P.add(x, 0.005, z, C.holo, 0.8, 5);
    return P; })();
  const satMat = structMat();

  const B = buildBooster(), S = buildShip(), T = buildTower(), T2 = buildTower(), PAD = buildPad(), E = buildEarth();
  const sats = []; for (let i = 0; i < 8; i++) { const m = satGeo.mesh(satMat); m.visible = false; root.add(m); sats.push(m); }
  /* air streaks around whatever is climbing */
  const streakMat = jetMat({ len: 4, wide: 0, rate: 1.6, alpha: 0.16, spread0: 1, size: 0.7, c0: [0.8, 0.9, 1], c1: [0.6, 0.8, 1], c2: [0.3, 0.5, 0.7] });
  const sl = []; for (let i = 0; i < 700; i++) { const a = rnd(0, TAU), r = rnd(1.2, 6); sl.push({ o: [Math.cos(a) * r, rnd(-2, 18), Math.sin(a) * r], d: [0, -1, 0], r: 0.002, w: rnd(0.5, 1.5) }); }
  const streaks = jetMesh(sl, streakMat); const follow = new THREE.Group(); follow.add(streaks);
  [B.g, S.g, T.g, T2.g, PAD.g, E.g, follow].forEach(o => root.add(o));
  const allMats = [B.mat, S.mat, T.mat, T2.mat, PAD.mat, E.mat, satMat];
  const jets = [B.plumeMat, B.ventMat, B.vapMat, S.plumeMat, S.plasmaMat, PAD.steamMat, streakMat];

  /* ---------------- virtual camera ---------------- */
  const vcam = new THREE.PerspectiveCamera(38, 1, 0.1, 400);
  const cam = { pos: new THREE.Vector3(14, 6, 22), tgt: new THREE.Vector3(0, 7, 0), gp: new THREE.Vector3(14, 6, 22), gt: new THREE.Vector3(0, 7, 0), cut: true };
  function aim(px, py, pz, tx, ty, tz) { cam.gp.set(px, py, pz); cam.gt.set(tx, ty, tz); }
  function orbit(t, dist, yaw, pitch) { aim(t.x + Math.sin(yaw) * Math.cos(pitch) * dist, t.y + Math.sin(pitch) * dist, t.z + Math.cos(yaw) * Math.cos(pitch) * dist, t.x, t.y, t.z); }

  /* ---------------- v2 panels: mission clock, flight data, engines, propellant, altitude profile, event log, real footage ---------------- */
  const PANEL_CSS = `
  .sx-tele{display:none}
  .sxp{position:absolute;width:var(--pw);box-sizing:border-box;padding:10px 12px 11px;background:linear-gradient(160deg,rgba(6,22,34,.78),rgba(3,10,16,.72));border:1px solid rgba(115,217,255,.22);
    clip-path:polygon(0 0,calc(100% - 14px) 0,100% 14px,100% 100%,14px 100%,0 calc(100% - 14px));opacity:0;transform:translateX(var(--from));transition:opacity .7s,transform .9s cubic-bezier(.2,.75,.2,1)}
  #sx.on .sxp{opacity:1;transform:none}
  .sxp::before{content:"";position:absolute;left:0;top:0;width:22px;height:2px;background:#73D9FF;box-shadow:0 0 8px #73D9FF}
  .sxp::after{content:"";position:absolute;inset:0;background:repeating-linear-gradient(0deg,rgba(115,217,255,.035) 0 1px,transparent 1px 3px);pointer-events:none}
  .sxp h6{margin:0 0 7px;display:flex;justify-content:space-between;align-items:baseline;font:600 10px/1 var(--f-latin,ui-monospace,monospace);letter-spacing:2.2px;color:#73D9FF}
  .sxp h6 span{color:rgba(159,221,245,.45);font-weight:400;letter-spacing:1px}
  .sxL{left:max(18px,2vw);--from:-30px}.sxR{right:max(18px,2vw);--from:30px}
  .sx-met{font:300 30px/1.05 var(--f-latin,ui-monospace,monospace);color:#E8FAFF;letter-spacing:1px;text-shadow:0 0 14px rgba(115,217,255,.55);font-variant-numeric:tabular-nums}
  .sx-ph2{color:#FFB27A;font-size:13px;margin:4px 0 8px;letter-spacing:.5px}
  .sx-g{display:grid;grid-template-columns:1fr 1fr;gap:6px 10px}
  .sx-g div{border-left:2px solid rgba(115,217,255,.35);padding-left:7px}
  .sx-g small{display:block;font:9.5px/1.2 var(--f-latin,monospace);letter-spacing:1.4px;color:rgba(159,221,245,.55)}
  .sx-g b{font:500 15px/1.3 var(--f-latin,monospace);color:#E4F8FF;font-variant-numeric:tabular-nums}
  .sx-g b em{font-style:normal;font-size:10px;color:rgba(159,221,245,.6);margin-left:3px}
  .sx-bar{height:3px;margin-top:3px;background:rgba(115,217,255,.12);position:relative}.sx-bar i{position:absolute;left:0;top:0;bottom:0;background:#73D9FF;box-shadow:0 0 6px #73D9FF}
  .sx-bar.hot i{background:#FFB27A;box-shadow:0 0 6px #FFB27A}
  .sxp canvas{display:block;width:100%}
  .sx-eng{display:flex;gap:12px;align-items:center}.sx-eng svg{position:static!important;width:92px!important;height:92px!important;flex:none}
  #sx .sx-eng circle{fill:rgba(115,217,255,.1);stroke:rgba(115,217,255,.45);stroke-width:1}
  #sx .sx-eng circle.on{fill:#FFC48A;stroke:#FFE3C8;filter:drop-shadow(0 0 3px #FF9A4A)}
  .sx-eng .tx{font-size:11px;line-height:1.55;color:rgba(191,239,255,.8)}.sx-eng .tx b{display:block;font:500 17px/1.2 var(--f-latin,monospace);color:#E4F8FF}
  .sx-prop{display:grid;grid-template-columns:auto 1fr auto;gap:5px 8px;align-items:center;font-size:11px}
  .sx-prop .tk{height:7px;background:rgba(115,217,255,.1);position:relative;overflow:hidden}.sx-prop .tk i{position:absolute;left:0;top:0;bottom:0;transition:width .3s}
  .sx-prop .lox i{background:linear-gradient(90deg,#9FDDF5,#E4F8FF)}.sx-prop .ch4 i{background:linear-gradient(90deg,#3FE0B8,#9CFFE0)}
  .sx-prop b{font:500 11px var(--f-latin,monospace);color:#E4F8FF;font-variant-numeric:tabular-nums;min-width:34px;text-align:right}
  .sx-prop .hd{grid-column:1/-1;font:9.5px var(--f-latin,monospace);letter-spacing:1.6px;color:rgba(159,221,245,.55);margin-top:3px}
  .sx-log{list-style:none;margin:0;padding:0;font-size:11px;max-height:var(--lh);overflow:hidden}
  #sx .sx-log li{display:flex;gap:8px;padding:2px 0;font-size:11px;line-height:15px;height:15px;white-space:nowrap;color:rgba(159,221,245,.38);transition:color .4s}
  #sx .sx-log li span,#sx .sx-log li b{display:inline;font-size:11px;margin:0;flex:none}#sx .sx-log li b{font:400 10.5px var(--f-latin,monospace);min-width:76px;font-variant-numeric:tabular-nums}
  #sx .sx-log li.sxd{color:rgba(191,239,255,.75)}#sx .sx-log li.sxn{color:#FFE3C8;text-shadow:0 0 8px rgba(255,150,80,.6)}
  #sx .sx-log li.sxn::before{content:"▶";color:#FFB27A;margin-right:-4px}
  .sx-media{padding:8px;transform-origin:0 100%;z-index:3;pointer-events:auto;cursor:zoom-in}
  #sx.on .sx-media.big{transform:scale(var(--mk,1));border-color:rgba(115,217,255,.6);box-shadow:0 0 28px rgba(115,217,255,.25);cursor:zoom-out}
  #sx.on .sxp.sx-media{transition:opacity .7s,transform .8s cubic-bezier(.2,.75,.2,1),border-color .8s}.sx-media .fr{position:relative;aspect-ratio:16/9;overflow:hidden;background:#02070b;border:1px solid rgba(115,217,255,.25)}
  .sx-media img,.sx-media video{width:100%;height:100%;object-fit:cover;display:block;filter:saturate(.85) contrast(1.05)}
  .sx-media .fr::after{content:"";position:absolute;inset:0;background:repeating-linear-gradient(0deg,rgba(0,0,0,.18) 0 1px,transparent 1px 3px),linear-gradient(180deg,transparent 60%,rgba(2,7,11,.7));pointer-events:none}
  .sx-media .rec{position:absolute;left:8px;top:7px;z-index:1;font:600 9.5px var(--f-latin,monospace);letter-spacing:1.5px;color:#FF7A6A}.sx-media .rec::before{content:"●";margin-right:5px;animation:sxblink 1.2s infinite}
  .sx-media p{margin:6px 2px 0;font-size:11.5px;color:#DCEFF7;line-height:1.45}.sx-media small{display:block;margin:2px 2px 0;font-size:10px;color:rgba(159,221,245,.55)}
  @keyframes sxblink{50%{opacity:.2}}
  .sx-foot{position:absolute;left:50%;bottom:16px;transform:translateX(-50%);font:9.5px var(--f-latin,monospace);letter-spacing:2px;color:rgba(159,221,245,.4);white-space:nowrap}
  @media (max-width:1100px){.sxp,.sx-foot{display:none}}`;
  const PW = (w) => Math.round(Math.min(300, Math.max(230, w * 0.17)));
  const sideW = (w) => w > 1100 ? PW(w) + 40 : 16;
  /* the flight's engines: Super Heavy 3 + 10 + 20, the ship 3 sea-level + 3 vacuum */
  const engSvg = (rings) => { let s = '<svg viewBox="-50 -50 100 100">'; rings.forEach(([n, r, cr, a0], ri) => { for (let i = 0; i < n; i++) { const a = a0 + TAU * i / n; s += `<circle data-r="${ri}" cx="${(Math.cos(a) * r).toFixed(1)}" cy="${(Math.sin(a) * r).toFixed(1)}" r="${cr}"/>`; } });
    return s + '<circle cx="0" cy="0" r="47" style="fill:none;stroke:rgba(115,217,255,.25);stroke-dasharray:2 3"/></svg>'; };
  const panelHTML = `
    <div class="sxp sxL" id="sxMission"><h6>MISSION · 任务<span>SX-V3 / FLT-15</span></h6><div class="sx-met">T−01:00:00</div><div class="sx-ph2"></div>
      <div class="sx-g"><div><small>ALTITUDE 高度</small><b class="v-alt">0</b></div><div><small>VELOCITY 速度</small><b class="v-vel">0</b></div>
      <div><small>MACH 马赫</small><b class="v-mach">0</b></div><div><small>G-LOAD 过载</small><b class="v-g">1.0</b></div>
      <div><small>Q 动压</small><b class="v-q">0</b><div class="sx-bar hot"><i class="b-q"></i></div></div><div><small>RANGE 航程</small><b class="v-dr">0</b></div></div></div>
    <div class="sxp sxL" id="sxProfile"><h6>ALTITUDE PROFILE · 高度剖面<span>km</span></h6><canvas width="520" height="200"></canvas></div>
    <div class="sxp sxL sx-media" id="sxMedia" hidden><div class="fr"><span class="rec">ARCHIVE · 真实影像</span></div><p></p><small></small></div>
    <div class="sxp sxR" id="sxEngines"><h6>ENGINES · 发动机<span>RAPTOR 3</span></h6>
      <div class="sx-eng" data-v="B">${engSvg([[3, 9, 4.6, Math.PI / 2], [10, 23, 4.6, 0.3], [20, 39, 4.6, 0]])}<div class="tx">超重型助推器<b class="e-b">0 / 33</b><span class="t-b">推力 0 t</span></div></div>
      <div class="sx-eng" data-v="S" style="margin-top:8px">${engSvg([[3, 12, 8, Math.PI / 2], [3, 32, 13, Math.PI / 6]])}<div class="tx">星舰<b class="e-s">0 / 6</b><span class="t-s">推力 0 t</span></div></div></div>
    <div class="sxp sxR" id="sxProp"><h6>PROPELLANT · 推进剂<span>%</span></h6><div class="sx-prop">
      <span class="hd">SUPER HEAVY · 约 3,400 t</span><span>LOX</span><div class="tk lox"><i class="p-bl"></i></div><b class="n-bl">100</b><span>CH₄</span><div class="tk ch4"><i class="p-bm"></i></div><b class="n-bm">100</b>
      <span class="hd">SHIP · 约 1,500 t</span><span>LOX</span><div class="tk lox"><i class="p-sl"></i></div><b class="n-sl">100</b><span>CH₄</span><div class="tk ch4"><i class="p-sm"></i></div><b class="n-sm">100</b></div></div>
    <div class="sxp sxR" id="sxLog"><h6>EVENT LOG · 事件<span>MET</span></h6><ul class="sx-log"></ul></div>
    <div class="sx-foot">DATA · SPACEX PUBLIC FIGURES · 时间已压缩 · TIME COMPRESSED</div>`;
  /* real footage and photos: hud/media/starship/manifest.json, {"items":[{"stage":"ignite","src":"liftoff.jpg","caption":"...","source":"SpaceX"}]} */
  let MEDIA = [], MEDIA_V = '';
  const loadMedia = () => fetch('media/starship/manifest.json', { cache: 'no-store' }).then(r => r.ok ? r.json() : null).then(j => { MEDIA = (j && j.items) || []; MEDIA_V = (j && j.fetched) || ''; mediaKey = ''; }).catch(() => {});
  loadMedia();

  /* ---------------- overlay: top bar, telemetry, timeline, callouts ---------------- */
  const css = document.createElement('style');
  css.textContent = `
  #sx{position:fixed;inset:0;pointer-events:none;z-index:6;opacity:0;transition:opacity .6s;font-size:12px;color:#BFEFFF}
  #sx.on{opacity:1}
  #sx svg{position:absolute;inset:0;width:100%;height:100%;overflow:visible}
  #sx path{fill:none;stroke:rgba(127,219,255,.55);stroke-width:1}
  #sx circle{fill:#73D9FF}
  #sx .hot path,#sx path.hot{stroke:rgba(255,170,100,.7)} #sx circle.hot{fill:#FFB27A}
  .sx-top{position:absolute;left:50%;top:76px;transform:translateX(-50%);display:flex;align-items:center;gap:10px;padding:5px 14px;border:1px solid rgba(127,219,255,.3);border-radius:20px;background:rgba(4,12,18,.72);pointer-events:auto;cursor:pointer;white-space:nowrap}
  .sx-top b{color:#E4F8FF;font-weight:600;letter-spacing:.5px}.sx-top i{font-style:normal;color:#FFB27A}.sx-top em{font-style:normal;color:#9FDDF5;margin-left:6px}
  .sx-steps{position:absolute;left:50%;top:112px;transform:translateX(-50%);display:flex;gap:3px;width:min(760px,86vw)}
  .sx-steps div{flex:1;position:relative;padding-top:7px;color:rgba(159,221,245,.45);font-size:10.5px;text-align:center;white-space:nowrap;overflow:hidden}
  .sx-steps div::before{content:"";position:absolute;left:0;right:0;top:0;height:2px;background:rgba(127,219,255,.18)}
  .sx-steps div i{position:absolute;left:0;top:0;height:2px;background:#73D9FF;box-shadow:0 0 6px #73D9FF;width:0}
  .sx-steps div.now{color:#E4F8FF}.sx-steps div.done{color:rgba(159,221,245,.75)}
  .sx-tele{position:absolute;left:max(24px,3.2vw);top:46%;transform:translateY(-50%);min-width:190px;padding:12px 14px;border:1px solid rgba(127,219,255,.28);border-radius:8px;background:rgba(4,12,18,.62)}
  .sx-tele .tk{color:#73D9FF;font-size:11px;letter-spacing:1.5px}
  .sx-tele .ph{color:#E4F8FF;font-size:17px;font-weight:600;margin:3px 0 10px}
  .sx-tele dl{display:grid;grid-template-columns:auto 1fr;gap:5px 14px;margin:0}
  .sx-tele dt{color:rgba(159,221,245,.6)}.sx-tele dd{margin:0;text-align:right;color:#E4F8FF;font-family:var(--f-latin,ui-monospace,monospace);font-variant-numeric:tabular-nums}
  .sx-tele .nt{margin-top:10px;padding-top:8px;border-top:1px solid rgba(127,219,255,.15);color:rgba(159,221,245,.5);font-size:10.5px;line-height:1.5;max-width:200px}
  .sx-call{position:absolute;max-width:270px;padding:7px 11px 8px;border:1px solid rgba(127,219,255,.45);border-radius:6px;background:rgba(4,14,22,.72);opacity:0;transition:opacity .45s;line-height:1.45}
  .sx-call.on{opacity:1}
  .sx-call b{display:block;color:#E4F8FF;font-size:13.5px;font-weight:600;margin-bottom:2px}
  .sx-call b small{font-family:var(--f-latin,inherit);font-weight:400;font-size:10.5px;color:#73D9FF;letter-spacing:1px;margin-left:6px}
  .sx-call span{display:block;color:#9FDDF5}
  .sx-call.hot{border-color:rgba(255,170,100,.6);background:rgba(22,12,4,.7)}.sx-call.hot b small{color:#FFB27A}`;
  css.textContent += PANEL_CSS;
  document.head.appendChild(css);
  const ov = document.createElement('div'); ov.id = 'sx';
  ov.innerHTML = `<svg></svg><div class="sx-top"><b>STARSHIP V3 · 从点火到回收</b><i></i><em>收起 ×</em></div><div class="sx-steps"></div>
    <div class="sx-tele"><div class="tk"></div><div class="ph"></div><dl><dt>任务时间</dt><dd class="v-t"></dd><dt>高度</dt><dd class="v-h"></dd><dt>速度</dt><dd class="v-v"></dd></dl>
    <div class="nt">时间已压缩。回收段按 SpaceX 公布的 Flight 15 方案演示：两级都由发射塔接住。</div></div>`;
  ov.insertAdjacentHTML('beforeend', panelHTML);
  document.body.appendChild(ov);
  const svg = ov.querySelector('svg'), topPh = ov.querySelector('.sx-top i'), steps = ov.querySelector('.sx-steps');
  const tk = ov.querySelector('.tk'), ph = ov.querySelector('.ph'), vT = ov.querySelector('.v-t'), vH = ov.querySelector('.v-h'), vV = ov.querySelector('.v-v');
  ov.querySelector('.sx-top').addEventListener('click', () => hide());
  addEventListener('keydown', (e) => { if (e.key === 'Escape' && ST.on) hide(); });

  let calls = [];
  function clearCalls() { calls.forEach(c => { c.el.remove(); c.path.remove(); c.dot.remove(); }); calls = []; }
  function makeCalls(list) {
    clearCalls();
    calls = list.map(q => {
      const el = document.createElement('div'); el.className = 'sx-call' + (q.hot ? ' hot' : '');
      el.innerHTML = `<b>${q.t}${q.en ? `<small>${q.en}</small>` : ''}</b>` + q.l.map(s => `<span>${s}</span>`).join('');
      ov.appendChild(el);
      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path'); const dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      dot.setAttribute('r', '2.5'); if (q.hot) { path.classList.add('hot'); dot.classList.add('hot'); } svg.appendChild(path); svg.appendChild(dot);
      return { ...q, el, path, dot, v: new THREE.Vector3(), op: 0 };
    });
  }
  function placeCalls(u, w, h) {
    const sc = Math.min(1, Math.max(0.7, Math.min(w, h) / 900));
    calls.forEach(c => {
      const on = u >= c.a && u <= c.b && ST.on;
      c.v.set(...c.p); c.o.localToWorld(c.v); c.v.project(camera);
      const vis = on && c.v.z < 1 && Math.abs(c.v.x) < 1.15 && Math.abs(c.v.y) < 1.15;
      c.el.classList.toggle('on', vis);
      c.op += ((vis ? 1 : 0) - c.op) * 0.15;
      c.path.style.opacity = c.dot.style.opacity = String(c.op);
      if (c.op < 0.01) return;
      const ax = (c.v.x + 1) / 2 * w, ay = (1 - c.v.y) / 2 * h, side = c.s === 'l' ? -1 : 1;
      const ew = c.el.offsetWidth, eh = c.el.offsetHeight, off = (c.dx ?? 120) * sc;
      let bx = ax + side * off, by = ay + (c.dy ?? -30) * sc;
      let left = side > 0 ? bx : bx - ew;
      const M = sideW(w); left = Math.max(M, Math.min(w - ew - M, left)); const top = Math.max(150, Math.min(h - eh - 150, by - eh / 2));
      c.el.style.left = left + 'px'; c.el.style.top = top + 'px';
      const ex = side > 0 ? left : left + ew, ey = top + Math.min(eh / 2, 14);
      const mx = ax + (ex - ax) * 0.55;
      c.path.setAttribute('d', `M${ax.toFixed(1)},${ay.toFixed(1)} L${mx.toFixed(1)},${ey.toFixed(1)} L${ex.toFixed(1)},${ey.toFixed(1)}`);
      c.dot.setAttribute('cx', ax.toFixed(1)); c.dot.setAttribute('cy', ay.toFixed(1));
    });
  }

  /* ---------------- helpers for the director ---------------- */
  const cl = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
  const sm = (a, b, x) => { const t = cl((x - a) / (b - a)); return t * t * (3 - 2 * t); };
  const lerp = (a, b, t) => a + (b - a) * t;
  const q = new THREE.Quaternion(), qa = new THREE.Quaternion(), ez = new THREE.Vector3(0, 0, 1), ex = new THREE.Vector3(1, 0, 0), ey = new THREE.Vector3(0, 1, 0);
  const basisQ = (x, y, z) => new THREE.Quaternion().setFromRotationMatrix(new THREE.Matrix4().makeBasis(new THREE.Vector3(...x), new THREE.Vector3(...y), new THREE.Vector3(...z)));
  const Q_VERT = new THREE.Quaternion(), Q_FLAT = basisQ([0, 0, 1], [1, 0, 0], [0, 1, 0]), Q_TAIL = basisQ([0, 0, -1], [-1, 0, 0], [0, 1, 0]);
  function ring(mat, a, b, c, d = 0) { mat.uniforms.uOn.value.set(a, b, c, d); }
  function eng(mat, a, b, c, d = 0) { mat.uniforms.uRing.value.set(a, b, c, d); }
  function stackOnBooster() { S.g.quaternion.copy(B.g.quaternion); S.g.position.set(0, BH, 0).applyQuaternion(B.g.quaternion).add(B.g.position); }
  function show(...objs) { [B.g, S.g, T.g, T2.g, PAD.g, E.g, follow].forEach(o => o.visible = objs.includes(o)); sats.forEach(m => m.visible = false); }
  function quiet() { jets.forEach(m => ring(m, 0, 0, 0, 0)); allMats.forEach(m => { m.uniforms.uCut.value = 0; m.uniforms.uHeat.value = 0; m.uniforms.uFlow.value = 0; m.uniforms.uRing.value.set(0, 0, 0, 0); m.uniforms.uSel.value.set(0, 0, 0, 0); });
    B.fins.forEach(f => f.rotation.x = 0); S.flaps.forEach(f => f.rotation.y = f.userData.base); B.g.quaternion.identity(); S.g.quaternion.identity(); E.g.rotation.set(0, 0, 0); }
  const k2 = (n) => Math.round(n).toLocaleString('en-US');
  const ARMH_B = 9.5, ARMH_S = 9.6;      /* arm height for the booster catch (pins 6.32 above its base), for the ship */

  /* ---------------- the show ---------------- */
  const SC = [
    { key: 'stack', name: '全箭', dur: 9, track: '星舰 V3 · 全箭', phase: '发射台上',
      tele: [[0, -3600, 0, 0]],
      setup() { show(B.g, S.g, T.g, PAD.g); T.g.position.set(-1.9, 0, 0); T.set(11.5, 0.6); B.g.position.set(0, 2, 0); stackOnBooster(); },
      update(u) { orbit(new THREE.Vector3(0, 6.4, 0), 34 - u * 0.6, 0.75 - u * 0.04, 0.1); },
      calls: () => [
        { o: S.g, p: [0, SH, 0], s: 'r', dy: -10, a: 2.6, b: 5.6, t: '星舰 V3', en: 'STARSHIP', l: ['全高 124.4 m · 直径 9 m', '起飞质量约 5,300 t，史上最大的火箭', '两级都按"回收、加注、再飞"设计'] },
        { o: T.g, p: [0.45, 12.5, 0], s: 'l', a: 2.6, b: 5.6, t: '发射塔 · 机械臂', en: 'MECHAZILLA', l: ['两根"筷子"：平时吊装火箭', '火箭飞回来时，用它接住'] },
        { o: S.g, p: [R, 2.6, 0], s: 'r', a: 5.6, b: 9, t: '星舰（二级）', en: 'SHIP', l: ['约 52 m · 6 台 Raptor 3', '进入轨道，再入大气，最后被塔接住'] },
        { o: B.g, p: [R, 3.6, 0], s: 'r', a: 5.9, b: 9, t: '超重型助推器（一级）', en: 'SUPER HEAVY', l: ['72.3 m · 33 台 Raptor 3', '把星舰推到约 70 km，然后飞回发射场'] } ] },
    { key: 'inside', name: '内部结构', dur: 12, track: '星舰 V3 · 结构', phase: '推进剂加注',
      tele: [[0, -2400, 0, 0], [12, -300, 0, 0]],
      setup() { show(B.g, S.g, T.g, PAD.g); T.g.position.set(-1.9, 0, 0); T.set(11.5, 0.6); B.g.position.set(0, 2, 0); stackOnBooster(); },
      update(u) { const cut = sm(0, 1.2, u) * (1 - sm(11, 12, u)); [B.mat, S.mat].forEach(m => { m.uniforms.uCut.value = cut; m.uniforms.uFlow.value = cut; });
        const k = sm(4.5, 7, u); orbit(new THREE.Vector3(0.3, lerp(5.2, 11.0, k), 0), lerp(15, 12.5, k), 1.15 + u * 0.02, 0.06); },
      calls: () => [
        { o: B.g, p: [0.42, 5.3, 0], s: 'r', a: 1.0, b: 5.4, t: '甲烷箱（上）', en: 'CH₄', l: ['液态甲烷 −162 °C'] },
        { o: B.g, p: [0.42, 2.2, 0], s: 'r', dy: 10, a: 1.4, b: 5.4, t: '液氧箱（下）', en: 'LOX', l: ['液氧 −183 °C', '约 3,400 t 推进剂，液氧和甲烷按质量约 3.6 : 1'] },
        { o: B.g, p: [0.07, 1.6, 0], s: 'l', a: 2.0, b: 5.4, t: '下降管', en: 'DOWNCOMER', l: ['甲烷走这根粗管穿过液氧箱', '直接送到 33 台发动机'] },
        { o: B.g, p: [R, 7.05, 0], s: 'l', dy: 10, a: 5.6, b: 11, t: '热分离环', en: 'HOT STAGE', l: ['一圈镂空格栅', '分离时让星舰的火焰从这里排出'] },
        { o: S.g, p: [0.42, 1.5, 0], s: 'r', dy: 20, a: 6.2, b: 11, t: '星舰：液氧在后，甲烷在前', l: ['共底穹顶隔开两种推进剂，省去一段箱壁'] },
        { o: S.g, p: [0.1, 4.7, 0], s: 'r', dy: -10, a: 7.0, b: 11, t: '头部集管箱', en: 'HEADER TANKS', l: ['专留给着陆点火的小油箱', '翻转时主箱里的燃料会晃荡，', '小箱保证发动机不吸进气泡'] } ] },
    { key: 'ignite', name: '点火', dur: 10, track: '星舰 V3 · 全箭', phase: '发动机点火',
      tele: [[0, -6, 0, 0], [9, 0, 0, 0]],
      setup() { show(B.g, S.g, T.g, PAD.g); T.g.position.set(-1.9, 0, 0); T.set(11.5, 0.6); B.g.position.set(0, 2, 0); stackOnBooster(); },
      update(u) {
        const c = sm(3.0, 3.4, u), i = sm(3.6, 4.0, u), o = sm(4.2, 4.6, u);
        ring(B.plumeMat, c, i, o); eng(B.mat, c, i, o);
        B.mat.uniforms.uSel.value.set(sm(0.6, 1, u) * (1 - sm(2.2, 2.6, u)), sm(0.6, 1, u) * (1 - sm(2.2, 2.6, u)), 0, 0);
        ring(PAD.steamMat, sm(3.2, 5, u)); PAD.steamMat.uniforms.uLen.value = lerp(1.5, 7, sm(3.2, 8, u));
        B.plumeMat.uniforms.uLen.value = lerp(1.2, 2.6, sm(3.2, 6, u)); B.plumeMat.uniforms.uWide.value = 2.4; B.plumeMat.uniforms.uAlpha.value = 0.2;
        if (u < 4.8) aim(2.6, 0.55, 2.2, 0, 1.95, 0); else orbit(new THREE.Vector3(0, 4.2, 0), lerp(13, 17, sm(4.8, 10, u)), 0.85, 0.02);
        if (u >= 4.8 && u < 4.85) cam.cut = true; },
      calls: () => [
        { o: B.g, p: [0.245, 0, 0], s: 'r', dx: 150, dy: -40, a: 0.5, b: 4.7, hot: true, t: '33 台 Raptor 3', en: 'ENGINES', l: ['中心 3 + 内圈 10：装在万向节上，可摆动转向', '外圈 20：固定安装，只出推力'] },
        { o: B.g, p: [0, 0, 0], s: 'l', dx: 150, dy: 40, a: 2.9, b: 4.7, t: '分批点火', en: 'START', l: ['由中心向外逐圈点火', '避免 33 台同时启动的冲击'] },
        { o: B.g, p: [R, 0.2, 0], s: 'r', dy: -50, a: 5.2, b: 10, hot: true, t: '全流量分级燃烧', en: 'RAPTOR 3', l: ['燃烧室 350 bar · 单台海平面推力约 280 t', '33 × 280 t ≈ 9,200 t，远大于 5,300 t 的起飞质量'] },
        { o: PAD.g, p: [0.8, 0.05, 0.4], s: 'l', dy: 30, a: 5.8, b: 10, t: '水冷钢板', en: 'DELUGE', l: ['发射台下没有导焰槽', '钢板里向上喷水，吸走热量和声能'] } ] },
    { key: 'ascent', name: '升空', dur: 12, track: '星舰 V3 · 全箭', phase: '起飞 · 爬升',
      tele: [[0, 0, 0, 0], [3, 8, 0.2, 90], [7, 62, 12, 1500], [12, 140, 55, 5000]],
      setup() { show(B.g, S.g, T.g, PAD.g, follow); T.g.position.set(-1.9, 0, 0); T.set(11.5, 0.6); },
      update(u) {
        const hgt = 2 + 0.75 * u * u * u / (1 + 0.25 * u), tilt = sm(4, 12, u) * 0.32;
        B.g.position.set(Math.sin(tilt) * (hgt - 2) * 0.35, hgt, 0); B.g.quaternion.setFromAxisAngle(ez, -tilt); stackOnBooster();
        ring(B.plumeMat, 1, 1, 1); eng(B.mat, 1, 1, 1);
        B.plumeMat.uniforms.uLen.value = lerp(2.4, 4.5, sm(2, 12, u)); B.plumeMat.uniforms.uWide.value = lerp(1.4, 9, sm(5, 12, u)); B.plumeMat.uniforms.uAlpha.value = 2.2 * (lerp(0.09, 0.05, sm(5, 12, u)));
        ring(PAD.steamMat, 1 - sm(3, 6, u)); PAD.mat.uniforms.uAlpha.value = T.mat.uniforms.uAlpha.value = 1 - sm(4, 7, u);
        follow.position.copy(B.g.position); follow.quaternion.copy(B.g.quaternion); ring(streakMat, sm(3, 6, u) * (1 - sm(10, 12, u)));
        streakMat.uniforms.uRate.value = lerp(0.6, 2.5, sm(3, 12, u));
        ring(B.vapMat, sm(6, 6.6, u) * (1 - sm(8.6, 9.4, u)));
        const c = new THREE.Vector3(0, 7.4, 0).applyQuaternion(B.g.quaternion).add(B.g.position);
        if (u < 3.5) aim(13, 3, 18, 0, lerp(7, 11, sm(0, 3.5, u)) + Math.max(0, hgt - 8) * 0.8, 0);
        else { orbit(c, 25, 0.9 + u * 0.03, -0.08); cam.tight = true; }
        if (u >= 3.5 && u < 3.55) cam.cut = true; },
      calls: () => [
        { o: B.g, p: [R, 1.2, 0], s: 'r', a: 0.3, b: 3.4, hot: true, t: '起飞', en: 'LIFTOFF', l: ['推力稳定后，发射台夹具松开'] },
        { o: S.g, p: [R, 2, 0], s: 'r', a: 3.8, b: 6.2, t: '重力转弯', en: 'GRAVITY TURN', l: ['火箭一点点向东倾斜', '借重力把轨迹压弯，最省燃料'] },
        { o: S.g, p: [-R, 0.2, 0], s: 'l', a: 6.3, b: 9.4, t: '最大动压', en: 'MAX-Q', l: ['约 T+1:00 · 箭体承受的气动压力最大', '空气里的水汽被压缩凝结，出现白色雾锥'] },
        { o: B.g, p: [0, -1.5, 0], s: 'r', dy: 20, a: 9.4, b: 12, hot: true, t: '尾焰越飞越宽', l: ['外面的空气越来越稀薄', '燃气向四周膨胀得越厉害'] } ] },
    { key: 'stage', name: '热分离', dur: 9.5, track: '星舰 V3 · 全箭', phase: '热分离',
      tele: [[0, 150, 62, 5300], [4, 160, 70, 5600], [9.5, 172, 78, 5800]],
      setup() { show(B.g, S.g); B.g.position.set(0, 0, 0); },
      update(u) {
        const tilt = 0.55; B.g.quaternion.setFromAxisAngle(ez, -tilt);
        const sep = u > 4.2 ? 0.5 * 0.9 * (u - 4.2) * (u - 4.2) : 0; stackOnBooster();
        S.g.position.add(new THREE.Vector3(0, sep, 0).applyQuaternion(B.g.quaternion));
        const meco = sm(0.8, 1.2, u);
        ring(B.plumeMat, 0.65 * (1 - sm(4.6, 5.2, u)), 1 - meco, 1 - meco); eng(B.mat, 0.65 * (1 - sm(4.6, 5.2, u)), 1 - meco, 1 - meco);
        B.plumeMat.uniforms.uLen.value = 4; B.plumeMat.uniforms.uWide.value = 9; B.plumeMat.uniforms.uAlpha.value = 2.2 * (0.06);
        const sh = sm(2.4, 2.9, u); ring(S.plumeMat, sh, sh, sh); eng(S.mat, sh, sh, sh);
        S.plumeMat.uniforms.uLen.value = lerp(0.6, 4.5, sm(2.4, 6, u)); S.plumeMat.uniforms.uWide.value = lerp(2, 8, sm(4, 9, u)); S.plumeMat.uniforms.uAlpha.value = 2.2 * (0.12);
        ring(B.ventMat, sh * (1 - sm(4.4, 5.4, u)));
        const c = new THREE.Vector3(0, BH, 0).applyQuaternion(B.g.quaternion).add(B.g.position);
        c.addScaledVector(new THREE.Vector3(0, 1, 0).applyQuaternion(B.g.quaternion), sep * 0.4);
        orbit(c, lerp(19, 24, sm(4, 9.5, u)), 1.15 + u * 0.03, 0.1); cam.tight = true; },
      calls: () => [
        { o: B.g, p: [0, 0, 0], s: 'l', dy: 20, a: 0.6, b: 3.6, t: '主发动机关机', en: 'MECO', l: ['约 T+2:40 · 高度约 70 km', '33 台关掉 30 台，只留中心 3 台'] },
        { o: B.g, p: [R, 7.05, 0], s: 'r', a: 2.6, b: 6.2, hot: true, t: '热分离', en: 'HOT STAGING', l: ['星舰在还连着时就点火', '火焰从镂空的热分离环排出'] },
        { o: S.g, p: [R, 2.5, 0], s: 'r', a: 6.2, b: 9.5, t: '为什么先点火再分离', l: ['不用额外的分离发动机', '推力没有空窗期，据 SpaceX 可多带约一成载荷'] } ] },
    { key: 'booster', name: '助推器回收', dur: 17, track: '超重型助推器', phase: '返航 · 接住',
      tele: [[0, 165, 72, 5500], [4, 200, 85, 3500], [9, 330, 40, 3000], [12, 395, 3, 700], [15.5, 420, 0, 0], [17, 420, 0, 0]],
      setup() { show(B.g); B.g.position.set(0, 0, 0); },
      update(u) {
        if (u < 9) {
          show(B.g); const spin = u < 4.5 ? lerp(-0.55, -0.55 - Math.PI, sm(0.3, 3.2, u)) : lerp(-0.55 - Math.PI, -2 * Math.PI + 0.08, sm(5, 7.5, u));
          B.g.quaternion.setFromAxisAngle(ez, spin); B.g.position.set(0, 0, 0);
          const bb = sm(2.8, 3.2, u) * (1 - sm(4.6, 5, u)); ring(B.plumeMat, Math.max(bb, 0.5 * (1 - sm(0.2, 0.8, u))), bb, 0); eng(B.mat, Math.max(bb, 0.5 * (1 - sm(0.2, 0.8, u))), bb, 0);
          B.plumeMat.uniforms.uLen.value = 3.5; B.plumeMat.uniforms.uWide.value = 7; B.plumeMat.uniforms.uAlpha.value = 2.2 * (0.07);
          const fin = u > 5.5 ? Math.sin(u * 2.3) * 0.35 : 0; B.fins.forEach((f, i) => f.rotation.x = fin * (i ? -1 : 1));
          B.mat.uniforms.uSel.value.set(0, 0, 0, 0);
          const c = new THREE.Vector3(0, u < 5.5 ? 3.6 : 6.2, 0).applyQuaternion(B.g.quaternion);
          orbit(c, u < 5.5 ? 17 : 8.5, 0.6 + u * 0.05, u < 5.5 ? 0.1 : 0.25);
          if (u >= 5.5 && u < 5.56) cam.cut = true;
        } else {
          show(B.g, T.g, PAD.g); T.g.position.set(-1.9, 0, 0); PAD.mat.uniforms.uAlpha.value = T.mat.uniforms.uAlpha.value = 1; B.fins.forEach(f => f.rotation.x = 0);
          const s = sm(9, 14.6, u), base = ARMH_B - 6.32;
          const y = base + 24 * Math.pow(1 - s, 2.2) - (u > 14.6 ? 0.08 * sm(14.6, 15.4, u) : 0);
          B.g.quaternion.identity(); B.g.position.set(0, y, 0);
          const lb = sm(9.6, 10.0, u), inner = lb * (1 - sm(12.2, 12.6, u)), cen = lb * (1 - sm(14.5, 14.8, u));
          ring(B.plumeMat, cen, inner, 0); eng(B.mat, cen, inner, 0);
          B.plumeMat.uniforms.uLen.value = 2.2; B.plumeMat.uniforms.uWide.value = 1.8; B.plumeMat.uniforms.uAlpha.value = 2.2 * (0.1);
          T.set(ARMH_B - (u > 14.6 ? 0.08 * sm(14.6, 15.4, u) : 0), lerp(0.6, -0.035, sm(13.4, 14.7, u)));
          orbit(new THREE.Vector3(-0.6, Math.max(7.5, y + 5.5), 0), 22, 0.62, 0.02);
          if (u >= 9 && u < 9.06) cam.cut = true;
        } },
      calls: () => [
        { o: B.g, p: [0, 3.6, 0], s: 'r', a: 0.4, b: 5.2, hot: true, t: '翻转 + 回飞点火', en: 'BOOSTBACK', l: ['掉头 180°，发动机重新点火', '把落点从海上拉回发射场'] },
        { o: B.g, p: [R + 0.4, 6.55, 0], s: 'r', a: 5.8, b: 9, t: '3 片格栅舵', en: 'GRID FINS', l: ['V3 比 V2 少一片，每片大 50%', '下落时像方向盘，控制落点'] },
        { o: B.g, p: [0, 0, 0], s: 'r', dy: 30, a: 9.7, b: 13.2, hot: true, t: '着陆点火', en: 'LANDING BURN', l: ['先开 13 台急刹车', '再收成中心 3 台做精细控制'] },
        { o: T.g, p: [2.9, ARMH_B, 0.56], s: 'r', dx: 160, dy: -60, a: 13.4, b: 17, t: '机械臂接住', en: 'CATCH', l: ['两根"筷子"合拢，托住格栅舵下方的承力点', '不用着陆腿：省重量，放回发射台就能再加注'] } ] },
    { key: 'orbit', name: '入轨', dur: 11, track: '星舰', phase: '加速入轨',
      tele: [[0, 170, 75, 5800], [5, 330, 160, 16000], [8, 520, 275, 28000], [11, 540, 275, 28000]],
      setup() { show(S.g, E.g); S.g.position.set(0, 0, 0); E.g.position.set(0, -E.RE - 2.6, 0); },
      update(u) {
        qa.setFromAxisAngle(ez, -lerp(0.9, Math.PI / 2, sm(0, 6, u))); S.g.quaternion.copy(qa);
        E.g.rotation.z = u * 0.012; const on = 1 - sm(7.8, 8.2, u); ring(S.plumeMat, on, on, on); eng(S.mat, on, on, on);
        S.plumeMat.uniforms.uLen.value = 3.5; S.plumeMat.uniforms.uWide.value = 9; S.plumeMat.uniforms.uAlpha.value = 2.2 * (0.09);
        orbit(new THREE.Vector3(-0.5, -0.4, 0), lerp(13, 15, sm(0, 11, u)), lerp(0.25, -0.1, sm(0, 11, u)), 0.12); },
      calls: () => [
        { o: S.g, p: [0.3, 0.05, 0], s: 'r', dy: 40, a: 0.6, b: 4.4, hot: true, t: '3 台真空版 Raptor', en: 'RVAC', l: ['喷管大得多：在真空里把燃气膨胀得更充分', '同样的推进剂，推得更远'] },
        { o: S.g, p: [R, 3.2, 0], s: 'r', dy: -40, a: 4.4, b: 8, t: '加速到 7.8 km/s', en: 'ORBITAL SPEED', l: ['这么快时，往下掉的弧线', '刚好和地球表面一样弯', '于是绕着地球一直"掉"，却落不下来'] },
        { o: S.g, p: [R, 2.5, 0], s: 'r', a: 8.2, b: 11, t: '关机入轨', en: 'SECO', l: ['高度约 275 km', 'Flight 14 是星舰第一次真正入轨'] } ] },
    { key: 'deploy', name: '部署星链', dur: 10, track: '星舰', phase: '部署星链',
      tele: [[0, null, 275, 28000]],
      setup() { show(S.g, E.g); S.g.position.set(0, 0, 0); S.g.quaternion.copy(Q_FLAT); E.g.position.set(0, -E.RE - 2.6, 0); },
      update(u) {
        E.g.rotation.z = 0.13 + u * 0.012; S.g.updateMatrixWorld();
        sats.forEach((m, i) => { const t0 = 0.8 + i * 0.95, k = u - t0; m.visible = k > 0;
          if (k <= 0) return;
          const out = Math.min(k, 1.2) * 0.55 + Math.max(0, k - 1.2) * 0.45, lat = (i % 2 ? 1 : -1) * Math.max(0, k - 1.2) * 0.05;
          const p = new THREE.Vector3(lat, 3.98 - Math.max(0, k - 1.2) * 0.16, 0.2 + out); S.g.localToWorld(p); m.position.copy(p);
          m.rotation.set(0, i * 0.4 + Math.max(0, k - 1.2) * 0.3, Math.max(0, k - 1.2) * 0.2); });
        orbit(new THREE.Vector3(3.9, 1.2, 0), lerp(8.5, 10.5, sm(0, 10, u)), lerp(0.7, 0.35, sm(0, 10, u)), lerp(0.35, 0.25, sm(0, 10, u))); },
      calls: () => [
        { o: S.g, p: [0, 3.98, R], s: 'r', dy: -30, a: 1.0, b: 5.4, t: '星链 V3 投放', en: 'STARLINK', l: ['像 PEZ 糖果盒，从侧面槽口逐颗推出', 'Flight 14 一次部署 26 颗'] },
        { o: S.g, p: [0, 1.5, R], s: 'l', dy: 30, a: 5.4, b: 10, t: '为什么只能用星舰', l: ['V3 卫星太大，猎鹰 9 号的整流罩装不下', '每颗给网络增加约 1 Tbps 容量'] } ] },
    { key: 'reentry', name: '再入', dur: 12.5, track: '星舰', phase: '离轨 · 再入大气',
      tele: [[0, null, 275, 28000], [3, null, 265, 27700], [6, null, 100, 26500], [9, null, 75, 22000], [12.5, null, 50, 12000]],
      setup() { show(S.g, E.g); S.g.position.set(0, 0, 0); E.g.position.set(0, -E.RE - 2.6, 0); },
      update(u) {
        E.g.rotation.z = 0.3 + u * 0.01; E.g.position.y = -E.RE - lerp(2.6, 1.4, sm(3, 12.5, u));
        if (u < 3.4) { S.g.quaternion.copy(Q_TAIL); const b = sm(0.5, 0.8, u) * (1 - sm(2.8, 3.1, u)); ring(S.plumeMat, b, 0, 0); eng(S.mat, b, 0, 0);
          S.plumeMat.uniforms.uLen.value = 2.5; S.plumeMat.uniforms.uWide.value = 8; S.plumeMat.uniforms.uAlpha.value = 2.2 * (0.12);
          orbit(new THREE.Vector3(0.5, 0, 0), 11, 0.45, 0.15); }
        else { ring(S.plumeMat, 0, 0, 0); eng(S.mat, 0, 0, 0);
          qa.copy(Q_TAIL).slerp(Q_FLAT, sm(3.4, 5.4, u)); q.setFromAxisAngle(ez, 0.35 * sm(4.5, 6, u)); S.g.quaternion.copy(q).multiply(qa);
          const pl = sm(5, 6.5, u) * (1 - sm(11, 12.5, u)); ring(S.plasmaMat, pl); S.mat.uniforms.uHeat.value = pl;
          S.flaps.forEach((f, i) => f.rotation.y = f.userData.base + Math.sin(u * 1.7 + i) * 0.12);
          orbit(new THREE.Vector3(2.3, 0.3, 0), lerp(12, 10, sm(3.4, 12, u)), lerp(0.5, 0.95, sm(3.4, 12.5, u)), lerp(0.2, 0.05, sm(3.4, 12.5, u)));
          if (u >= 3.4 && u < 3.46) cam.cut = true; } },
      calls: () => [
        { o: S.g, p: [0, 0, 0], s: 'r', dy: -30, a: 0.5, b: 3.3, hot: true, t: '离轨点火', en: 'DEORBIT', l: ['单台 Raptor 反向点火，只减掉一点速度', '轨道随之下沉，切进大气层'] },
        { o: S.g, p: [0, 2.5, -R], s: 'r', dx: 140, dy: 50, a: 4.2, b: 7.6, hot: true, t: '腹部朝前再入', en: 'ENTRY', l: ['速度约 26,000 km/h', '前方空气被猛烈压缩，加热成等离子体'] },
        { o: S.g, p: [0.2, 3.5, -R], s: 'l', dy: 50, a: 7.4, b: 10.6, t: '约 18,000 块六边形隔热瓦', en: 'HEAT SHIELD', l: ['耐温约 1,400 °C', '块与块之间留缝，给热胀冷缩留余地'] },
        { o: S.g, p: [-R - 0.3, 0.8, 0], s: 'l', a: 10.4, b: 12.5, t: '4 片襟翼', en: 'FLAPS', l: ['前 2 后 2，像跳伞的人用手脚调整姿态'] } ] },
    { key: 'flop', name: '腹部俯冲', dur: 8, track: '星舰', phase: '腹部俯冲',
      tele: [[0, null, 12, 1100], [8, null, 1.5, 320]],
      setup() { show(S.g, T.g, PAD.g); T.g.position.set(-1.9, 0, 0); T.set(ARMH_S, 0.6); PAD.mat.uniforms.uAlpha.value = T.mat.uniforms.uAlpha.value = 1; },
      update(u) {
        const k = u / 8; S.g.position.set(lerp(-14, -4, k), lerp(40, 22, k), 0);
        q.setFromAxisAngle(ez, 0.12 * Math.sin(u * 0.9)); S.g.quaternion.copy(q).multiply(Q_FLAT);
        S.flaps.forEach((f, i) => f.rotation.y = f.userData.base + Math.sin(u * 2.4 + (i < 2 ? 0 : 1.6) + (i % 2) * 0.8) * 0.4);
        const c = S.g.position.clone().add(new THREE.Vector3(2.6, 0, 0)); orbit(c, 12, 0.6 + u * 0.03, -0.25 + u * 0.01); },
      calls: () => [
        { o: S.g, p: [0, 2.6, -R], s: 'r', dy: 50, a: 0.5, b: 4.4, t: '腹部俯冲', en: 'BELLY FLOP', l: ['横着下落，整个机身就是一块减速板', '空气阻力把速度降到每小时几百公里'] },
        { o: S.g, p: [R * 0.97 + 0.3, 0.8, -0.1], s: 'r', dy: -40, a: 4.3, b: 8, t: '襟翼一张一收', en: 'FLAPS', l: ['控制俯仰和滚转', '把落点对准发射塔'] } ] },
    { key: 'catch', name: '飞船回收', dur: 10.5, track: '星舰', phase: '翻转 · 接住',
      tele: [[0, null, 1.5, 320], [3, null, 0.6, 150], [7, null, 0, 0], [10.5, null, 0, 0]],
      setup() { show(S.g, T.g, PAD.g); T.g.position.set(-1.9, 0, 0); PAD.mat.uniforms.uAlpha.value = T.mat.uniforms.uAlpha.value = 1; },
      update(u) {
        const f = sm(0.3, 2.4, u); qa.copy(Q_FLAT).slerp(Q_VERT, f); S.g.quaternion.copy(qa);
        const base = ARMH_S - 4.25, s = sm(0, 7, u);
        S.g.position.set(lerp(-4, 0, sm(0, 5, u)), base + 13 * Math.pow(1 - s, 1.8) - (u > 7 ? 0.06 * sm(7, 7.8, u) : 0), 0);
        const e = sm(0.2, 0.5, u) * (1 - sm(6.9, 7.2, u)); ring(S.plumeMat, e, e * (1 - sm(4, 4.5, u)), 0); eng(S.mat, e, e * (1 - sm(4, 4.5, u)), 0);
        S.plumeMat.uniforms.uLen.value = 1.8; S.plumeMat.uniforms.uWide.value = 1.6; S.plumeMat.uniforms.uAlpha.value = 2.2 * (0.16);
        S.flaps.forEach(fl => fl.rotation.y = fl.userData.base * (1 - f) + 0);
        T.set(ARMH_S - (u > 7 ? 0.06 * sm(7, 7.8, u) : 0), lerp(0.6, -0.035, sm(5.8, 7.1, u)));
        orbit(new THREE.Vector3(-0.7, Math.max(8.5, S.g.position.y + 3), 0), lerp(15, 19, sm(0, 10, u)), 0.7, 0.04); },
      calls: () => [
        { o: S.g, p: [0, 0, 0], s: 'r', dy: 10, a: 0.4, b: 3.8, hot: true, t: '翻转 + 着陆点火', en: 'FLIP', l: ['海平面 Raptor 点火，几秒内从横着变成竖直', '燃料取自头部集管箱'] },
        { o: T.g, p: [2.9, ARMH_S, 0.56], s: 'r', dx: 150, dy: -50, a: 5.0, b: 10.5, t: '塔臂接住星舰', en: 'SHIP CATCH', l: ['Flight 15 计划首次尝试（预计 11 月）', '成功就是轨道级火箭第一次两级全部回收'] } ] },
    { key: 'reuse', name: '完全复用', dur: 8, track: '星舰 V3', phase: '两级都回到发射塔',
      tele: [[0, null, 0, 0]],
      setup() { show(B.g, S.g, T.g, T2.g, PAD.g); PAD.mat.uniforms.uAlpha.value = T.mat.uniforms.uAlpha.value = T2.mat.uniforms.uAlpha.value = 1;
        T.g.position.set(-5.9, 0, 0); T.set(ARMH_B, -0.035); B.g.position.set(-4, ARMH_B - 6.32, 0);
        T2.g.position.set(2.1, 0, 0); T2.set(ARMH_S, -0.035); S.g.position.set(4, ARMH_S - 4.25, 0); },
      update(u) { orbit(new THREE.Vector3(0, 7.5, 0), 30, 0.25 + u * 0.03, 0.06); },
      calls: () => [
        { o: S.g, p: [R, 6.2, 0], s: 'r', dy: -30, a: 0.8, b: 7.2, t: '完全复用', en: 'FULL REUSE', l: ['两级都回到塔上：不落海、不用打捞', '检查、加注，就能再飞一次——像飞机一样'] } ] }
  ];
  let acc = 0; SC.forEach(s => { s.at = acc; acc += s.dur; }); const TOTAL = acc;
  steps.innerHTML = SC.map(s => `<div>${s.name}<i></i></div>`).join('');
  const stepEls = [...steps.children];

  /* ---------------- v2 panel data ---------------- */
  const EVENTS = [['stack', 0, '推进剂加注', 'PROP LOAD'], ['ignite', 3.0, '发动机点火', 'IGNITION'], ['ascent', 0.3, '起飞', 'LIFTOFF'], ['ascent', 6.4, '最大动压', 'MAX-Q'],
    ['stage', 0.9, '主发动机关机', 'MECO'], ['stage', 2.6, '热分离', 'HOT STAGING'], ['booster', 2.9, '回飞点火', 'BOOSTBACK'], ['booster', 9.7, '着陆点火', 'LANDING BURN'],
    ['booster', 14.6, '助推器被接住', 'CATCH'], ['orbit', 8.0, '二级关机 · 入轨', 'SECO'], ['deploy', 1.0, '部署星链', 'DEPLOY'], ['reentry', 0.6, '离轨点火', 'DEORBIT'],
    ['reentry', 5.0, '再入大气', 'ENTRY'], ['flop', 0.5, '腹部俯冲', 'BELLY FLOP'], ['catch', 0.4, '翻转 · 着陆点火', 'FLIP'], ['catch', 7.0, '星舰被接住', 'SHIP CATCH']]
    .map(([k, u, zh, en]) => { const sc = SC.find(s => s.key === k); return { at: sc.at + u, zh, en, tm: teleAt(sc, u)[0], key: k }; });
  /* propellant left (fraction), by show time */
  const atK = (k, u) => SC.find(s => s.key === k).at + u;
  const PROP_B = [[0, 1], [atK('ignite', 3), 1], [atK('stage', 0.9), 0.1], [atK('booster', 2.9), 0.1], [atK('booster', 4.8), 0.05], [atK('booster', 9.7), 0.05], [atK('booster', 14.6), 0.012]];
  const PROP_S = [[0, 1], [atK('stage', 2.6), 1], [atK('orbit', 8), 0.16], [atK('reentry', 0.6), 0.16], [atK('reentry', 3), 0.13], [atK('catch', 0.3), 0.13], [atK('catch', 7), 0.07]];
  const keyed = (K, t) => { if (t <= K[0][0]) return K[0][1]; for (let j = 0; j < K.length - 1; j++) if (t <= K[j + 1][0]) return lerp(K[j][1], K[j + 1][1], cl((t - K[j][0]) / (K[j + 1][0] - K[j][0]))); return K[K.length - 1][1]; };
  /* the altitude over the whole show, for the profile chart; the stack's track until staging, then whichever stage is on screen */
  const PROFILE = []; for (let t = 0; t < TOTAL; t += TOTAL / 220) { const i = SC.findIndex(s => t < s.at + s.dur), sc = SC[i < 0 ? SC.length - 1 : i]; PROFILE.push([t, teleAt(sc, t - sc.at)[1], sc.key]); }
  const PMAX = Math.max(...PROFILE.map(p => p[1]));
  const $p = (s) => ov.querySelector(s);
  const pMission = $p('#sxMission'), pProfile = $p('#sxProfile'), pMedia = $p('#sxMedia'), pEng = $p('#sxEngines'), pProp = $p('#sxProp'), pLog = $p('#sxLog');
  const met = $p('.sx-met'), ph2 = $p('.sx-ph2'), logUl = $p('.sx-log'), cv = pProfile.querySelector('canvas'), cx2 = cv.getContext('2d');
  logUl.innerHTML = EVENTS.map(e => `<li><b>${fmtT(e.tm)}</b><span>${e.zh}</span></li>`).join(''); const logLis = [...logUl.children];
  const engB = [...pEng.querySelectorAll('[data-v="B"] circle[data-r]')], engS = [...pEng.querySelectorAll('[data-v="S"] circle[data-r]')];
  let last = { t: -1, v: 0, tm: 0, g: 1 }, panelT = 0, mediaKey = '', layoutW = 0;
  function layoutPanels(w, h) {
    const pw = PW(w); ov.style.setProperty('--pw', pw + 'px'); layoutW = w + h + (window.HANDCAM_H || 0);
    let y = 150; [pMission, pProfile, pMedia].forEach(p => { if (p.hidden) return; p.style.top = y + 'px'; y += p.offsetHeight + 12; });
    if (!pMedia.hidden) {   /* how far the footage card may grow at key moments: up over the left panels, never toward the model */
      const mh = pMedia.offsetHeight, bottom = pMedia.offsetTop + mh;
      ov.style.setProperty('--mk', Math.max(1, Math.min(2.2, w * 0.38 / pw, (bottom - 120) / mh)).toFixed(3));
    }
    y = 150 + (window.HANDCAM_H || 0); [pEng, pProp].forEach(p => { p.style.top = y + 'px'; y += p.offsetHeight + 12; });
    pLog.style.top = y + 'px'; ov.style.setProperty('--lh', Math.max(60, h - y - 90) + 'px');
  }
  function drawProfile(t) {
    const W = cv.width, H = cv.height, px = (x) => 8 + (W - 16) * x / TOTAL, py = (a) => H - 22 - (H - 40) * Math.sqrt(Math.max(0, a) / PMAX);
    cx2.clearRect(0, 0, W, H);
    cx2.strokeStyle = 'rgba(115,217,255,.12)'; cx2.lineWidth = 1;
    [0, 50, 100, 200, 300].forEach(a => { cx2.beginPath(); cx2.moveTo(8, py(a)); cx2.lineTo(W - 8, py(a)); cx2.stroke(); });
    cx2.fillStyle = 'rgba(159,221,245,.45)'; cx2.font = '19px ui-monospace,monospace'; [100, 300].forEach(a => cx2.fillText(String(a), 10, py(a) - 5));
    SC.forEach(s => { cx2.strokeStyle = 'rgba(115,217,255,.18)'; cx2.beginPath(); cx2.moveTo(px(s.at), H - 18); cx2.lineTo(px(s.at), H - 10); cx2.stroke(); });
    const g = cx2.createLinearGradient(0, 0, W, 0); g.addColorStop(0, '#73D9FF'); g.addColorStop(0.5, '#E4F8FF'); g.addColorStop(1, '#FFB27A');
    cx2.strokeStyle = 'rgba(115,217,255,.35)'; cx2.lineWidth = 2; cx2.beginPath(); PROFILE.forEach(([x, a], j) => j ? cx2.lineTo(px(x), py(a)) : cx2.moveTo(px(x), py(a))); cx2.stroke();
    cx2.strokeStyle = g; cx2.lineWidth = 3; cx2.shadowColor = '#73D9FF'; cx2.shadowBlur = 10; cx2.beginPath();
    PROFILE.filter(p => p[0] <= t).forEach(([x, a], j) => j ? cx2.lineTo(px(x), py(a)) : cx2.moveTo(px(x), py(a))); cx2.stroke(); cx2.shadowBlur = 0;
    const cur = PROFILE.reduce((b, p) => p[0] <= t ? p : b, PROFILE[0]);
    cx2.fillStyle = '#FFE3C8'; cx2.beginPath(); cx2.arc(px(t), py(cur[1]), 5, 0, TAU); cx2.fill();
    cx2.strokeStyle = 'rgba(255,178,122,.5)'; cx2.beginPath(); cx2.arc(px(t), py(cur[1]), 9 + 3 * Math.sin(G.uTime.value * 4), 0, TAU); cx2.stroke();
  }
  function showMedia(key, w, h) {
    if (key === mediaKey) return; mediaKey = key;
    const list = MEDIA.filter(m => m.stage === key), it = list.find(m => /\.(mp4|mov|webm|m4v)$/i.test(m.src)) || list[0];   /* video first */
    const fr = pMedia.querySelector('.fr'); fr.querySelectorAll('img,video').forEach(e => e.remove());
    if (!it) { pMedia.hidden = true; layoutPanels(w, h); return; }
    const src = 'media/starship/' + it.src + (MEDIA_V ? '?v=' + encodeURIComponent(MEDIA_V) : ''), vid = /\.(mp4|mov|webm|m4v)$/i.test(it.src);
    const el = document.createElement(vid ? 'video' : 'img'); el.src = src; if (vid) { el.muted = true; el.loop = true; el.autoplay = true; el.playsInline = true; }
    fr.appendChild(el); if (vid) { const pl = el.play(); if (pl && pl.catch) pl.catch(() => {}); } pMedia.querySelector('p').textContent = it.caption || ''; pMedia.querySelector('small').textContent = it.source ? '来源 · ' + it.source : '';
    pMedia.hidden = false; layoutPanels(w, h);
  }
  const RHO = (h) => 1.225 * Math.exp(-h / 8.5), SOUND = (h) => h < 11 ? 340 - 4.1 * h : h < 25 ? 295 : 300;
  const MEDIA_KEY = { ignite: [1.5, 6], stage: [1, 6], booster: [5, 7], flop: [2, 6], reuse: [1, 5.5] };
  let mediaPin = false;
  pMedia.addEventListener('click', () => { mediaPin = !mediaPin; pMedia.classList.toggle('big', mediaPin); });
  function mediaPulse(sc) {   /* small most of the time; grows for the key moment of a stage, then shrinks back */
    const k = MEDIA_KEY[sc.key], v = pMedia.querySelector('video'), lt = ST.t - sc.at;
    const big = !pMedia.hidden && (mediaPin || (!!v && !!k && lt >= k[0] && lt < k[0] + k[1]));
    if (big !== pMedia.classList.contains('big')) pMedia.classList.toggle('big', big);
  }
  function panels(sc, u, w, h) {
    if (w + h + (window.HANDCAM_H || 0) !== layoutW) layoutPanels(w, h);
    panelT -= 1; if (panelT > 0) return; panelT = 5;           /* about 12 times a second is plenty for numbers */
    const t = ST.t, [tm, alt, spd] = teleAt(sc, u);
    met.textContent = fmtT(tm); ph2.textContent = sc.phase;
    const v = spd / 3.6, dt = t - last.t;
    if (dt > 0.05 && dt < 1 && tm != null && last.tm != null && tm > last.tm) { const a = (v - last.v) / (tm - last.tm); last.g += (Math.abs(a) / 9.81 - last.g) * 0.3; }
    else if (dt < 0 || dt > 1) last.g = 1;
    last.t = t; last.v = v; last.tm = tm;
    const q = alt < 90 ? 0.5 * RHO(alt) * v * v / 1000 : 0, mach = alt < 90 ? v / SOUND(alt) : 0;
    $p('.v-alt').innerHTML = (alt < 10 ? alt.toFixed(1) : k2(alt)) + '<em>km</em>';
    $p('.v-vel').innerHTML = k2(spd) + '<em>km/h</em>';
    $p('.v-mach').textContent = alt < 90 ? mach.toFixed(mach < 10 ? 2 : 1) : '—';
    $p('.v-g').innerHTML = (spd === 0 && alt < 0.01 ? 1 : Math.max(0, last.g)).toFixed(1) + '<em>g</em>';
    $p('.v-q').innerHTML = q.toFixed(q < 10 ? 1 : 0) + '<em>kPa</em>'; $p('.b-q').style.width = Math.min(100, q / 35 * 100) + '%';
    const dr = t < atK('stage', 0) ? Math.max(0, alt * 0.35) : null; $p('.v-dr').innerHTML = dr == null ? (sc.key === 'booster' ? '回程' : sc.key === 'stack' || sc.key === 'inside' ? '0' : '—') : k2(dr) + '<em>km</em>';
    const rb = B.mat.uniforms.uRing.value, rs = S.mat.uniforms.uRing.value, bOn = B.g.visible, sOn = S.g.visible;
    const bl = [rb.x, rb.y, rb.z].map(x => bOn && x > 0.5), sl = [rs.x, rs.y, rs.z].map(x => sOn && x > 0.5);
    engB.forEach(c => c.classList.toggle('on', bl[+c.dataset.r]));
    engS.forEach((c, j) => c.classList.toggle('on', c.dataset.r === '1' ? sl[2] : j === 0 ? sl[0] : sl[1]));
    const nb = (bl[0] ? 3 : 0) + (bl[1] ? 10 : 0) + (bl[2] ? 20 : 0), ns = (sl[0] ? 1 : 0) + (sl[1] ? 2 : 0), nv = sl[2] ? 3 : 0;
    $p('.e-b').textContent = nb + ' / 33'; $p('.t-b').textContent = '推力 ' + k2(nb * 280) + ' t';
    $p('.e-s').textContent = (ns + nv) + ' / 6'; $p('.t-s').textContent = '推力 ' + k2(ns * 280 + nv * 306) + ' t';
    const fb = keyed(PROP_B, t), fs = keyed(PROP_S, t);
    [['bl', fb], ['bm', fb * 1.02], ['sl', fs], ['sm', fs * 1.03]].forEach(([k, f]) => { const p = Math.max(0, Math.min(100, f * 100)); $p('.p-' + k).style.width = p + '%'; $p('.n-' + k).textContent = p.toFixed(p < 10 ? 1 : 0); });
    let cur = -1; EVENTS.forEach((e, j) => { if (t >= e.at) cur = j; });
    logLis.forEach((li, j) => { li.className = j < cur ? 'sxd' : j === cur ? 'sxn' : ''; });
    const lh = parseFloat(getComputedStyle(ov).getPropertyValue('--lh')) || 200, rowH = 19, vis = Math.floor(lh / rowH);
    logUl.scrollTop = Math.max(0, (cur - Math.floor(vis / 2)) * rowH);
    drawProfile(t);
    showMedia(sc.key, w, h); mediaPulse(sc);
  }

  /* ---------------- state ---------------- */
  const ST = { on: false, t: 0, idx: -1, paused: false, vis: 0, ending: false };
  function enter(i) {
    ST.idx = i; cam.tight = false; quiet(); allMats.forEach(m => m.uniforms.uAlpha.value = 1);
    SC[i].setup(); makeCalls(SC[i].calls()); cam.cut = true; SC[i].update(0); cam.cut = true;
    tk.textContent = SC[i].track; ph.textContent = SC[i].phase; topPh.textContent = '· ' + SC[i].name;
    stepEls.forEach((el, j) => { el.classList.toggle('now', j === i); el.classList.toggle('done', j < i); });
  }
  function teleAt(sc, u) {
    const K = sc.tele; let a = K[0], b = K[K.length - 1];
    for (let j = 0; j < K.length - 1; j++) if (u >= K[j][0] && u <= K[j + 1][0]) { a = K[j]; b = K[j + 1]; break; }
    const t = b[0] > a[0] ? cl((u - a[0]) / (b[0] - a[0])) : 0;
    return [a[1] == null ? null : lerp(a[1], b[1], t), lerp(a[2], b[2], t), lerp(a[3], b[3], t)];
  }
  function fmtT(s) { if (s == null) return '在轨数圈后'; const n = s < 0, a = Math.abs(Math.round(s)), h = Math.floor(a / 3600), m = Math.floor(a / 60) % 60, x = a % 60;
    return `T${n ? '−' : '+'}${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(x).padStart(2, '0')}`; }
  function play(from = 0) { loadMedia(); mediaPin = false; yaw0 = null; last.t = -1; mediaKey = ''; ST.on = true; ST.paused = false; ST.ending = false; ST.t = from; root.visible = true; ov.classList.add('on'); enter(SC.findIndex(s => from < s.at + s.dur)); }
  function hide() { ST.ending = true; }
  function seek(key) { const i = typeof key === 'number' ? key : SC.findIndex(s => s.key === key || s.name === key); if (i < 0) return false; play(SC[i].at); return true; }

  /* ---------------- per frame ---------------- */
  const inv = new THREE.Matrix4();
  let yaw0 = null;
  function frame(dt, w, h) {
    G.uTime.value += dt;
    const goal = ST.on && !ST.ending ? 1 : 0;
    ST.vis += (goal - ST.vis) * (1 - Math.pow(ST.ending ? 0.08 : 0.2, dt));
    if (ST.ending && ST.vis < 0.02) { ST.on = false; ST.ending = false; ST.vis = 0; root.visible = false; ov.classList.remove('on'); clearCalls(); ctx.done && ctx.done(); }
    if (ctx.dim) ctx.dim(ST.vis);
    if (!ST.on) return;
    if (!ST.paused) ST.t += dt;
    if (ST.t >= TOTAL) { ST.t = TOTAL - 0.001; hide(); }
    let i = SC.findIndex(s => ST.t < s.at + s.dur); if (i < 0) i = SC.length - 1;
    if (i !== ST.idx) enter(i);
    const sc = SC[i], u = ST.t - sc.at;
    sc.update(u);
    /* every cut gathers the particles in again; the first one gathers them from the core */
    let ap = i === 0 ? sm(0, 2.8, u) : sm(0, 0.7, u);
    if (i < SC.length - 1) ap *= 1 - sm(sc.dur - 0.35, sc.dur, u); else ap *= 1 - sm(sc.dur - 1.6, sc.dur, u);
    G.uAppear.value = ap * Math.min(1, ST.vis * 1.4); G.uSpread.value = i === 0 ? 6 : 2.5;
    jets.forEach(m => m.uniforms.uAppear.value = ap);
    /* camera */
    const k = cam.cut ? 1 : 1 - Math.pow(cam.tight ? 0.0005 : 0.02, dt); cam.cut = false;
    cam.pos.lerp(cam.gp, k); cam.tgt.lerp(cam.gt, k);
    const spin = ctx.spin ? ctx.spin() : 0; if (yaw0 == null) yaw0 = spin; const yaw = (spin - yaw0) * 0.8;
    const off = cam.pos.clone().sub(cam.tgt).applyAxisAngle(ey, yaw);
    vcam.position.copy(cam.tgt).add(off); vcam.up.set(0, 1, 0); vcam.lookAt(cam.tgt); vcam.updateMatrixWorld(); inv.copy(vcam.matrixWorld).invert();
    camera.updateMatrixWorld(); root.matrix.multiplyMatrices(camera.matrixWorld, inv); root.matrixWorldNeedsUpdate = true;
    root.updateMatrixWorld(true);
    /* overlay */
    const [tm, alt, spd] = teleAt(sc, u);
    vT.textContent = fmtT(tm); vH.textContent = alt < 10 ? alt.toFixed(1) + ' km' : k2(alt) + ' km'; vV.textContent = k2(spd) + ' km/h';
    stepEls.forEach((el, j) => { el.firstElementChild.style.width = (j < i ? 100 : j === i ? 100 * u / sc.dur : 0) + '%'; });
    placeCalls(u, w, h);
    panels(sc, u, w, h);
  }

  function command(m) {
    const a = m.action || 'play';
    if (a === 'play' || a === 'show' || a === 'replay') { play(0); return { ok: true }; }
    if (a === 'hide') { hide(); return { ok: true }; }
    if (a === 'pause') { ST.paused = true; return { ok: true }; }
    if (a === 'resume') { if (!ST.on) play(0); ST.paused = false; return { ok: true }; }
    if (a === 'stage') { return { ok: seek(m.stage) }; }
    return { ok: false };
  }
  return { frame, command, play, hide, seek, state: ST, stages: SC.map(s => ({ key: s.key, name: s.name, at: s.at, dur: s.dur })), total: TOTAL,
    at(t) { if (!ST.on) play(t); ST.t = t; ST.paused = true; } };
}
