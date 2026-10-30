/* Tesla Roadster cold-gas thrusters and the suicide burn, as a particle hologram.
   One self-running show (~90 s), seven scenes:
     car      the Roadster
     suction  fans empty the floor, the air outside presses it onto a ceiling
     thrust   10 nozzles, 2 COPV tanks, 600 L of air at ~380 bar
     nozzle   a cold-gas nozzle cut open: throat at Mach 1, supersonic exit, shock diamonds, frost
     compare  two Roadsters drop 10 m: lower it gently vs the suicide burn (gas gauges)
     modely   a Model Y with the same kit drops 10 m and lands on a suicide burn
     bill     what it costs in the real world
   Units: 1 = 1 m. Drawn through a virtual camera like starship.js: root carries mainCamera * inverse(vcam).
   Flight numbers come from the same 1-D model as sim_burn.py (ve 540 m/s, ideal control), so the screen matches the script.
   Tesla published thrust (>80 kN), 10 thrusters, ~600 L, ~250 kg of air, ~5,500 psi; masses and the drop are estimates. */
export function install(ctx) {
  const { THREE, scene, camera } = ctx;
  const pixel = ctx.pixel || Math.min(devicePixelRatio || 1, 2);
  const root = new THREE.Group(); root.matrixAutoUpdate = false; root.visible = false; scene.add(root);
  if (camera.far < 400) { camera.far = 400; camera.updateProjectionMatrix(); }

  /* ---------------- shaders (same family as starship.js) ---------------- */
  const G = { uTime: { value: 0 }, uPixel: { value: pixel }, uAppear: { value: 0 }, uPt: { value: 20 }, uSpread: { value: 4 } };
  const C = {
    holo: [0.42, 0.82, 1.0], dim: [0.2, 0.46, 0.66], bright: [0.78, 0.96, 1.0], glass: [0.16, 0.36, 0.55], gas: [0.86, 0.95, 1.0],
    hot: [1.0, 0.6, 0.28], red: [1.0, 0.32, 0.26], ground: [0.16, 0.42, 0.6], tank: [0.55, 0.95, 0.85], frost: [0.9, 0.97, 1.0]
  };
  const STRUCT_V = `
    uniform float uTime,uPixel,uAppear,uPt,uSpread,uAlpha,uCut;
    uniform vec4 uRing; uniform vec4 uSel;
    attribute vec3 aCol; attribute float aSize; attribute float aSeed; attribute float aKind;
    varying vec3 vC; varying float vA;
    vec3 h3(float s){ return fract(sin(vec3(s*127.1,s*311.7,s*74.7))*43758.5453); }
    void main(){
      vec3 p = position; float k = aKind; float vis = 1.0; vec3 col = aCol;
      if (k < 0.5) vis = 1.0 - 0.75*uCut;                 /* skin */
      else if (k < 1.5) vis = uCut;                        /* insides: tanks, lines */
      else if (k < 2.5) vis = 0.55 + 0.45*uCut;            /* glass */
      else if (k < 3.5) vis = 1.0;                         /* ground, always */
      else if (k < 4.5) { vis = 1.0; col = mix(col, vec3(1.0,0.3,0.25), 0.5+0.5*sin(uTime*6.0)); }
      else if (k < 5.5) vis = 1.0;                         /* bright edges */
      else if (k >= 29.5) { float r = k - 30.0;            /* nozzles, by group */
        float g = r < 0.5 ? uRing.x : r < 1.5 ? uRing.y : r < 2.5 ? uRing.z : uRing.w;
        float s = r < 0.5 ? uSel.x : r < 1.5 ? uSel.y : r < 2.5 ? uSel.z : uSel.w;
        col = mix(col, vec3(1.0,0.72,0.4), clamp(max(g, s), 0.0, 1.0)); vis = 1.0 + g*1.4 + s*1.4; }
      float d = clamp(uAppear*1.6 - aSeed*0.6, 0.0, 1.0); d = d*d*(3.0 - 2.0*d);
      p = mix(p + (h3(aSeed) * 2.0 - 1.0) * uSpread, p, d);
      vec4 mv = modelViewMatrix * vec4(p, 1.0);
      gl_Position = projectionMatrix * mv;
      float tw = 0.82 + 0.18*sin(uTime*2.1 + aSeed*61.0);
      vA = vis * uAlpha * d * tw; vC = col;
      float sz = aSize * uPt * uPixel / max(0.1, -mv.z);
      if (sz < 1.4*uPixel) { vA *= sz / (1.4*uPixel); sz = 1.4*uPixel; }
      gl_PointSize = min(sz, 7.0*uPixel);
    }`;
  const DOT_F = `varying vec3 vC; varying float vA;
    void main(){ vec2 c = gl_PointCoord - 0.5; float r = dot(c,c)*4.0; if (r > 1.0) discard; float a = 1.0 - r; gl_FragColor = vec4(vC, vA*a*a); }`;
  function structMat(o = {}) {
    const u = { ...G, uAlpha: { value: o.alpha ?? 1 }, uCut: { value: 0 }, uRing: { value: new THREE.Vector4() }, uSel: { value: new THREE.Vector4() } };
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
      uC0: { value: v3(o.c0 || [1, 1, 1]) }, uC1: { value: v3(o.c1 || [0.8, 0.92, 1]) }, uC2: { value: v3(o.c2 || [0.35, 0.5, 0.65]) } };
    return new THREE.ShaderMaterial({ uniforms: u, vertexShader: JET_V, fragmentShader: DOT_F, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending });
  }
  class Pts {
    constructor() { this.p = []; this.c = []; this.s = []; this.k = []; this.e = []; }
    add(x, y, z, c, s = 1, k = 0) { this.p.push(x, y, z); this.c.push(c[0], c[1], c[2]); this.s.push(s); this.k.push(k); this.e.push(Math.random()); }
    line(a, b, step, c, s, k) { const d = Math.hypot(b[0] - a[0], b[1] - a[1], b[2] - a[2]); const n = Math.max(2, Math.ceil(d / step));
      for (let i = 0; i <= n; i++) { const t = i / n; this.add(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, c, s, k); } }
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
  function jetMesh(list, mat) {
    const P = [], D = [], R = [], S = [];
    list.forEach(q => { P.push(...q.o); D.push(...q.d); R.push(q.ring || 0, q.r ?? 0.05, Math.random(), q.w ?? 1); S.push(Math.random()); });
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(P, 3)); g.setAttribute('aDir', new THREE.Float32BufferAttribute(D, 3));
    g.setAttribute('aR', new THREE.Float32BufferAttribute(R, 4)); g.setAttribute('aSeed', new THREE.Float32BufferAttribute(S, 1));
    const m = new THREE.Points(g, mat); m.frustumCulled = false; return m;
  }
  const rnd = (a, b) => a + Math.random() * (b - a);
  const TAU = Math.PI * 2;
  const interp = (K, x) => { if (x >= K[0][0]) return K[0][1]; for (let j = 0; j < K.length - 1; j++) { const [x0, y0] = K[j], [x1, y1] = K[j + 1]; if (x <= x0 && x >= x1) { const t = (x0 - x) / (x0 - x1); const s = t * t * (3 - 2 * t); return y0 + (y1 - y0) * (0.35 * t + 0.65 * s); } } return K[K.length - 1][1]; };

  /* ---------------- a car, as rings of points along its length ----------------
     spec: L, W, clr (ground clearance), top [[x, y] front→back], belt (beltline height), wb (wheelbase), wr (wheel radius), tr (track).
     x points forward. Returns { g, mat, P-groups, nozzles: [{p, d, ring}] } */
  function buildCar(sp) {
    const g = new THREE.Group(), P = new Pts(), mat = structMat();
    const hx = sp.L / 2;
    const halfW = (x) => sp.W / 2 * Math.pow(Math.max(0, 1 - Math.pow(Math.abs(x) / hx, 8)), 0.18) * (x > 0 ? 1 - 0.1 * Math.pow(x / hx, 2) : 1);
    /* cross-section: a rounded body up to the beltline, then the cabin leans in (tumblehome); sampled evenly by arc length */
    const section = (w, y0, top, belt) => {
      const b = Math.min(belt, top - 0.04), gw = w * (top - b > 0.25 ? 0.7 : 0.86), r0 = Math.min(0.12, (b - y0) * 0.4);
      const Q = [[0, y0], [w - r0, y0]]; for (let k = 1; k <= 4; k++) { const a = -Math.PI / 2 + k / 4 * Math.PI / 2; Q.push([w - r0 + Math.cos(a) * r0, y0 + r0 + Math.sin(a) * r0]); }
      Q.push([w, b - 0.06], [w * 0.985, b]); const rt = Math.min(0.14, (top - b) * 0.5);
      Q.push([gw, top - rt], [gw - rt * 0.8, top], [0, top]);
      let L = 0; const seg = []; for (let k = 0; k < Q.length - 1; k++) { const d = Math.hypot(Q[k + 1][0] - Q[k][0], Q[k + 1][1] - Q[k][1]); seg.push(d); L += d; }
      return (f) => { let d = f * L; for (let k = 0; k < seg.length; k++) { if (d <= seg[k] || k === seg.length - 1) { const t = seg[k] ? Math.min(1, d / seg[k]) : 0; return [Q[k][0] + (Q[k + 1][0] - Q[k][0]) * t, Q[k][1] + (Q[k + 1][1] - Q[k][1]) * t]; } d -= seg[k]; } return Q[Q.length - 1]; };
    };
    const NS = 34;
    for (let x = hx - 0.01, i = 0; x > -hx; x -= 0.055, i++) {
      const top = interp(sp.top, x), w = halfW(x), sec = section(w, sp.clr, top, sp.belt(x));
      const edgeRow = i % 6 === 0;
      for (let j = 0; j <= NS; j++) { const [zq, yy] = sec(j / NS);
        [1, -1].forEach(sd => { if (sd < 0 && zq < 1e-4) return; const zz = sd * zq;
          const glass = yy > sp.belt(x) + 0.02 && x < sp.glassF && x > sp.glassB && (zq > 0.3 * w || (sp.roofGlass && yy > top - 0.03));
          P.add(x, yy, zz, glass ? C.glass : edgeRow ? C.bright : C.holo, edgeRow ? 0.95 : 0.75, glass ? 2 : edgeRow ? 5 : 0); });
      }
    }
    /* character lines: shoulder, sill, centreline of the roof */
    for (let x = hx; x > -hx; x -= 0.03) { const w = halfW(x), top = interp(sp.top, x), b = sp.belt(x);
      [-1, 1].forEach(s => { P.add(x, Math.min(b, top - 0.05), s * w * 0.99, C.bright, 1.0, 5); P.add(x, sp.clr + 0.06, s * w * 0.97, C.dim, 0.9, 5); });
      P.add(x, top + 0.004, 0, C.bright, 0.9, 5); }
    /* wheels */
    [[sp.wb / 2, 1], [sp.wb / 2, -1], [-sp.wb / 2, 1], [-sp.wb / 2, -1]].forEach(([x, s]) => {
      const z = s * sp.tr / 2, r = sp.wr;
      for (let k = 0; k < 3; k++) for (let j = 0; j < 46; j++) { const a = TAU * j / 46; P.add(x + Math.cos(a) * (r - k * 0.035), r + Math.sin(a) * (r - k * 0.035), z + s * 0.02 * k, k === 0 ? C.bright : C.holo, 0.85, 5); }
      for (let j = 0; j < sp.spokes; j++) { const a = TAU * j / sp.spokes; P.line([x, r, z + s * 0.06], [x + Math.cos(a) * r * 0.62, r + Math.sin(a) * r * 0.62, z + s * 0.06], 0.03, C.holo, 0.75, 5); }
    });
    /* lights: a full-width bar at each end */
    { const wf = halfW(hx - 0.12) * 0.88, wr = halfW(-hx + 0.08) * 0.92;
      for (let z = -wf; z <= wf; z += 0.025) P.add(hx - 0.12, sp.headY, z, C.bright, 1.0, 5);
      for (let z = -wr; z <= wr; z += 0.025) P.add(-hx + 0.08, sp.tailY, z, C.red, 1.0, 5); }
    /* insides: two COPV tanks (cylinders with domes) along the tunnel, and the gas lines */
    const tanks = sp.tanks || [[-0.4, 0.35, 1.5, 0.2, 0.28], [-0.4, 0.35, 1.5, 0.2, -0.28]];
    tanks.forEach(([cx, cy, len, r, cz]) => {
      for (let x = cx - len / 2; x <= cx + len / 2; x += 0.045) for (let j = 0; j < 22; j++) { const a = TAU * j / 22; P.add(x, cy + Math.cos(a) * r, cz + Math.sin(a) * r, C.tank, 0.8, 1); }
      [-1, 1].forEach(sd => { for (let k = 1; k <= 4; k++) { const rr = r * Math.cos(k / 5 * Math.PI / 2), xx = cx + sd * (len / 2 + r * 0.6 * Math.sin(k / 5 * Math.PI / 2));
        for (let j = 0; j < 18; j++) { const a = TAU * j / 18; P.add(xx, cy + Math.cos(a) * rr, cz + Math.sin(a) * rr, C.tank, 0.8, 1); } } });
      for (let w = 0; w < 26; w++) { const x = cx - len / 2 + len * w / 26; P.line([x, cy - r, cz], [x + len / 52, cy + r, cz], 0.04, C.bright, 0.6, 1); }   /* the carbon overwrap */
    });
    /* nozzles: 4 corners pointing down (ring 0), 4 rear pointing back (ring 1), 1 each front fender pointing out (ring 2 left, 3 right) */
    const nz = [];
    const fx = sp.wb / 2 + sp.wr + 0.15, rx = -sp.wb / 2 - sp.wr - 0.12, cz = sp.W / 2 - 0.22;
    [[fx, cz], [fx, -cz], [rx, cz], [rx, -cz]].forEach(([x, z]) => nz.push({ p: [x, sp.clr + 0.02, z], d: [0, -1, 0], ring: 0 }));
    [-0.6, -0.2, 0.2, 0.6].forEach(f => nz.push({ p: [-hx + 0.02, sp.clr + 0.22, f * sp.W / 2 * 0.8], d: [-1, 0, 0], ring: 1 }));
    nz.push({ p: [hx - 0.75, sp.clr + 0.32, sp.W / 2 - 0.02], d: [0, 0, 1], ring: 2 }); nz.push({ p: [hx - 0.75, sp.clr + 0.32, -sp.W / 2 + 0.02], d: [0, 0, -1], ring: 3 });
    nz.forEach(n => { const [x, y, z] = n.p, d = n.d, k = 30 + n.ring;
      const t1 = Math.abs(d[1]) > 0.9 ? [1, 0, 0] : [0, 1, 0], t2 = [d[1] * t1[2] - d[2] * t1[1], d[2] * t1[0] - d[0] * t1[2], d[0] * t1[1] - d[1] * t1[0]];
      [0.03, 0.055, 0.08].forEach((r, ri) => { for (let j = 0; j < 16; j++) { const a = TAU * j / 16, off = ri * 0.03;
        P.add(x + (t1[0] * Math.cos(a) + t2[0] * Math.sin(a)) * r + d[0] * off, y + (t1[1] * Math.cos(a) + t2[1] * Math.sin(a)) * r + d[1] * off, z + (t1[2] * Math.cos(a) + t2[2] * Math.sin(a)) * r + d[2] * off, C.bright, 1.1, k); } });
      /* gas line from the nearest tank */
      const tk = tanks[Math.abs(z) > 0 && z < 0 ? 1 % tanks.length : 0];
      P.line([tk[0], tk[1], tk[4]], [x, y + 0.05, z], 0.05, C.tank, 0.7, 1);
    });
    g.add(P.mesh(mat));
    /* plumes */
    const jl = []; nz.forEach(n => { const N = n.ring === 0 ? 520 : n.ring === 1 ? 420 : 300; for (let i = 0; i < N; i++) jl.push({ o: n.p, d: n.d, ring: n.ring, r: 0.06, w: rnd(0.7, 1.1) }); });
    const plumeMat = jetMat({ len: 2.4, wide: 1.6, rate: 1.7, alpha: 0.2, diamond: 1, spread0: 0.6, size: 2.2, c0: [0.95, 0.99, 1], c1: [0.72, 0.9, 1], c2: [0.4, 0.55, 0.7] });
    g.add(jetMesh(jl, plumeMat));
    /* ground mist when the down jets hit something */
    const ml = []; for (let i = 0; i < 2600; i++) { const a = rnd(0, TAU), n = nz[i % 4]; ml.push({ o: [n.p[0] * 0.8, 0, n.p[2] * 0.8], d: [Math.cos(a), rnd(0.02, 0.35), Math.sin(a)], r: 0.5, w: rnd(0.5, 1.3) }); }
    const mistMat = jetMat({ len: 3.2, wide: 2.6, rate: 0.5, alpha: 0.11, spread0: 0.4, size: 2.6, c0: [0.95, 0.98, 1], c1: [0.8, 0.88, 0.95], c2: [0.35, 0.42, 0.5] });
    const mist = jetMesh(ml, mistMat); mist.matrixAutoUpdate = false;
    return { g, mat, plumeMat, mistMat, mist, nz, sp };
  }

  /* Tesla Roadster (2nd gen, 2026): low wedge. Dimensions are drawn from photos; not a published spec. */
  const RS = buildCar({ L: 4.4, W: 2.0, clr: 0.11, spokes: 10,
    top: [[2.2, 0.48], [1.7, 0.62], [0.75, 0.8], [0.05, 1.12], [-0.6, 1.1], [-1.6, 0.92], [-2.2, 0.86]], belt: (x) => 0.78 + 0.04 * (-x), glassF: 0.8, glassB: -1.5,
    wb: 2.6, wr: 0.36, tr: 1.7, headY: 0.5, tailY: 0.8, tanks: [[-0.35, 0.36, 1.8, 0.2, 0.26], [-0.35, 0.36, 1.8, 0.2, -0.26]] });
  /* Model Y (2026): 4,797 × 1,920 × 1,624 mm, wheelbase 2,890 mm */
  const MY = buildCar({ L: 4.8, W: 1.92, clr: 0.17, spokes: 5, roofGlass: true,
    top: [[2.4, 0.72], [2.1, 0.92], [1.25, 1.02], [0.3, 1.58], [-0.5, 1.62], [-1.5, 1.45], [-2.15, 1.2], [-2.4, 1.05]], belt: (x) => 1.04 + 0.02 * (-x), glassF: 1.2, glassB: -2.15,
    wb: 2.89, wr: 0.37, tr: 1.64, headY: 0.82, tailY: 1.0, tanks: [[-0.3, 0.42, 2.0, 0.21, 0.3], [-0.3, 0.42, 2.0, 0.21, -0.3], [-1.85, 0.62, 0.9, 0.22, 0]] });

  /* ---------------- set pieces ---------------- */
  function buildGround(R = 16) {
    const g = new THREE.Group(), P = new Pts(), mat = structMat();
    for (let x = -R; x <= R; x += 0.4) for (let z = -R; z <= R; z += 0.4) { const d = Math.hypot(x, z); if (d > R) continue; const f = Math.max(0.12, 1 - d / R); P.add(x + rnd(-0.05, 0.05), 0, z + rnd(-0.05, 0.05), [C.ground[0] * f, C.ground[1] * f, C.ground[2] * f], 0.85, 3); }
    for (let i = -6; i <= 6; i += 2) { P.line([-R * 0.6, 0.005, i], [R * 0.6, 0.005, i], 0.1, C.dim, 0.6, 3); }
    g.add(P.mesh(mat)); return { g, mat };
  }
  /* a slab the car can stick to; flipped over for the ceiling */
  function buildSlab() {
    const g = new THREE.Group(), P = new Pts(), mat = structMat();
    for (let x = -3.6; x <= 3.6; x += 0.18) for (let z = -2; z <= 2; z += 0.18) P.add(x, 0, z, C.dim, 0.8, 3);
    [-3.6, 3.6].forEach(x => P.line([x, 0, -2], [x, 0, 2], 0.05, C.bright, 1, 5)); [-2, 2].forEach(z => P.line([-3.6, 0, z], [3.6, 0, z], 0.05, C.bright, 1, 5));
    for (let x = -3.6; x <= 3.6; x += 0.18) for (let z = -2; z <= 2; z += 0.6) P.add(x, -0.25, z, C.dim, 0.6, 3);
    g.add(P.mesh(mat)); return { g, mat };
  }
  /* a 10 m ledge with a height scale */
  function buildLedge(H = 10) {
    const g = new THREE.Group(), P = new Pts(), mat = structMat();
    for (let x = -7; x <= 0; x += 0.22) for (let z = -1.6; z <= 1.6; z += 0.22) P.add(x, H, z, C.dim, 0.85, 3);
    P.line([0, H, -1.6], [0, H, 1.6], 0.04, C.bright, 1.1, 5);
    for (let y = 0; y < H; y += 0.5) [-1.6, 1.6].forEach(z => P.line([0, y, z], [-0.8, y, z], 0.1, C.dim, 0.6, 3));
    [-1.6, 1.6].forEach(z => P.line([0, 0, z], [0, H, z], 0.06, C.holo, 0.9, 5));
    for (let y = 0; y <= H; y += 1) { P.line([0.25, y, -1.9], [y % 5 === 0 ? 0.75 : 0.5, y, -1.9], 0.04, C.bright, 1, 5); }
    P.line([0.25, 0, -1.9], [0.25, H, -1.9], 0.05, C.bright, 1, 5);
    g.add(P.mesh(mat)); return { g, mat };
  }
  /* a cold-gas nozzle, cut open: tank, electric valve, converging-diverging nozzle (along +x) */
  function buildNozzle() {
    const g = new THREE.Group(), P = new Pts(), mat = structMat();
    const prof = (x) => x < 0 ? 0.55 - 0.4 * Math.pow(Math.min(1, -x / 1.2), 0.9) * 0 - 0.4 * (1 - Math.min(1, -x / 1.2)) : 0.15 + 0.62 * Math.pow(Math.min(1, x / 3.2), 0.85);
    for (let x = -1.2; x <= 3.2; x += 0.04) { const r = prof(x); for (let j = 0; j < 40; j++) { const a = TAU * j / 40; if (Math.sin(a) > 0.15 && Math.cos(a) > -0.2) continue;   /* open a window */
      P.add(x, Math.cos(a) * r, Math.sin(a) * r, Math.abs(x) < 0.06 ? C.bright : C.holo, 0.8, 0); }
      P.add(x, r, 0, C.bright, 1.1, 5); P.add(x, -r, 0, C.bright, 1.1, 5); }
    /* valve block and the tank behind it */
    for (let x = -2.2; x <= -1.2; x += 0.05) for (let j = 0; j < 30; j++) { const a = TAU * j / 30; P.add(x, Math.cos(a) * 0.32, Math.sin(a) * 0.32, C.hot, 0.8, 5); }
    for (let x = -6.5; x <= -2.4; x += 0.06) for (let j = 0; j < 48; j++) { const a = TAU * j / 48; P.add(x, Math.cos(a) * 1.1, Math.sin(a) * 1.1, C.tank, 0.75, 0); }
    for (let w = 0; w < 40; w++) { const x = -6.5 + 4.1 * w / 40; P.line([x, -1.1, 0.02], [x + 0.1, 1.1, 0.02], 0.06, C.bright, 0.6, 0); }
    P.line([-2.4, 0, 0], [-2.2, 0, 0], 0.03, C.tank, 1, 5);
    g.add(P.mesh(mat));
    /* flow inside: slow and dense in the chamber, fast after the throat; and the plume with shock diamonds outside */
    const fl = []; for (let i = 0; i < 2200; i++) fl.push({ o: [-1.2, rnd(-0.12, 0.12), rnd(-0.12, 0.12)], d: [1, 0, 0], ring: 0, r: 0.12, w: rnd(0.8, 1.1) });
    const flowMat = jetMat({ len: 4.4, wide: 4.2, rate: 1.1, alpha: 0.32, spread0: 1, size: 1.3, c0: [0.6, 1, 0.85], c1: [0.75, 0.95, 1], c2: [0.95, 0.99, 1] });
    g.add(jetMesh(fl, flowMat));
    const pl = []; for (let i = 0; i < 3600; i++) pl.push({ o: [3.2, 0, 0], d: [1, 0, 0], ring: 0, r: 0.75, w: rnd(0.8, 1.2) });
    const plumeMat = jetMat({ len: 7, wide: 0.7, rate: 1.4, alpha: 0.22, diamond: 1, spread0: 0.85, size: 2.4, c0: [1, 1, 1], c1: [0.85, 0.95, 1], c2: [0.5, 0.62, 0.75] });
    g.add(jetMesh(pl, plumeMat));
    const fr = []; for (let i = 0; i < 1600; i++) { const a = rnd(0, TAU); fr.push({ o: [rnd(3.4, 8), Math.cos(a) * 0.9, Math.sin(a) * 0.9], d: [1, Math.cos(a) * 0.5, Math.sin(a) * 0.5], ring: 0, r: 0.4, w: rnd(0.5, 1) }); }
    const frostMat = jetMat({ len: 2.4, wide: 2.4, rate: 0.5, alpha: 0.14, spread0: 0.5, size: 2.6, c0: [1, 1, 1], c1: [0.9, 0.95, 1], c2: [0.55, 0.62, 0.7] });
    g.add(jetMesh(fr, frostMat));
    return { g, mat, flowMat, plumeMat, frostMat };
  }
  /* air under the floor being pulled out, and the outside air pressing in */
  function buildAir(car) {
    const g = new THREE.Group(), sp = car.sp;
    const ul = []; for (let i = 0; i < 2600; i++) { const x = rnd(-sp.L / 2 + 0.3, sp.L / 2 - 0.3), z = rnd(-sp.W / 2 + 0.2, sp.W / 2 - 0.2); ul.push({ o: [x, sp.clr * 0.5, z], d: [rnd(-0.3, 0.3), 0, Math.sign(z) || 1], ring: 0, r: 0.02, w: rnd(0.5, 1) }); }
    const underMat = jetMat({ len: 1.6, wide: 0.6, rate: 0.9, alpha: 0.35, spread0: 0.2, size: 0.9, c0: [0.6, 1, 0.9], c1: [0.4, 0.85, 1], c2: [0.2, 0.4, 0.6] });
    g.add(jetMesh(ul, underMat));
    const P = new Pts(), mat = structMat();
    /* pressure arrows: pointing at the car from the free side (above when on the floor) */
    for (let x = -1.8; x <= 1.81; x += 0.6) for (let z = -0.8; z <= 0.81; z += 0.8) {
      const y0 = interp(sp.top, x) + 1.5, y1 = interp(sp.top, x) + 0.35;
      P.line([x, y0, z], [x, y1, z], 0.05, C.hot, 1.0, 5);
      [[0.1, 0], [-0.1, 0], [0, 0.1], [0, -0.1]].forEach(([dx, dz]) => P.line([x + dx, y1 + 0.16, z + dz], [x, y1, z], 0.03, C.hot, 1.0, 5)); }
    const arrows = P.mesh(mat); g.add(arrows);
    return { g, underMat, mat };
  }

  const GR = buildGround(), SLAB = buildSlab(), LEDGE_A = buildLedge(), LEDGE_B = buildLedge(), NOZ = buildNozzle(), AIR = buildAir(RS);
  /* a second Roadster for the side-by-side drop */
  const RS2 = buildCar(RS.sp);
  const carRig = (car) => { const rig = new THREE.Group(); rig.add(car.g); return rig; };
  const RSR = carRig(RS), RS2R = carRig(RS2), MYR = carRig(MY);
  RSR.add(AIR.g);
  const mists = [RS.mist, RS2.mist, MY.mist];
  [RSR, RS2R, MYR, GR.g, SLAB.g, LEDGE_A.g, LEDGE_B.g, NOZ.g, ...mists].forEach(o => root.add(o));
  const allMats = [RS.mat, RS2.mat, MY.mat, GR.mat, SLAB.mat, LEDGE_A.mat, LEDGE_B.mat, NOZ.mat, AIR.mat];
  const jets = [RS.plumeMat, RS2.plumeMat, MY.plumeMat, RS.mistMat, RS2.mistMat, MY.mistMat, NOZ.flowMat, NOZ.plumeMat, NOZ.frostMat, AIR.underMat];

  /* ---------------- the drop: 1-D vertical model, same as sim_burn.py ---------------- */
  const g0 = 9.81, VE = 540, TANK = 250;
  function simulate(m0, Fmax, h0, mode, tank = 1e9, vSoft = 1.0) {
    const dt = 1e-4, out = []; let nextS = 0; let h = h0, v = 0, m = m0, used = 0, t = 0, fired = false, fireH = null, fireT = null, empty = null;
    while (h > 0 && t < 30) {
      let F;
      if (mode === 'gentle') F = Math.max(0, Math.min(Fmax, m * g0 + m * (v - vSoft) / 0.3));
      else { const an = Fmax / m - g0; if (!fired && v * v / (2 * an) >= h - 0.02) { fired = true; fireH = h; fireT = t; } F = fired && v > 0.05 ? Fmax : fired ? m * g0 * 0.999 : 0; }
      if (used >= tank) { F = 0; if (empty == null) empty = { t, h }; }
      const a = g0 - F / m; v += a * dt; h -= v * dt; const d = F / VE * dt; m -= d; used += d; t += dt;
      if (t >= nextS) { nextS += 1 / 60; out.push({ t, h: Math.max(0, h), v, used, f: F / Fmax, acc: F / m / g0 }); }
    }
    out.push({ t, h: 0, v, used, f: 0, acc: 0 });
    return { pts: out, T: t, used, vLand: v, fireH, fireT, empty };
  }
  const sampleAt = (S, t) => { const p = S.pts; if (t <= 0) return { t: 0, h: p[0].h, v: 0, used: 0, f: 0, acc: 0 }; if (t >= S.T) return { ...p[p.length - 1], landed: true };
    let lo = 0, hi = p.length - 1; while (hi - lo > 1) { const mid = (lo + hi) >> 1; if (p[mid].t <= t) lo = mid; else hi = mid; } return p[lo]; };
  const M_RS = 2350, M_MY = 1891 + 250, F = 80000, H0 = 10;
  const SIM = {
    rsGentle: simulate(M_RS, F, H0, 'gentle', TANK), rsGentleFree: simulate(M_RS, F, H0, 'gentle'),
    rsBurn: simulate(M_RS, F, H0, 'suicide', TANK), myBurn: simulate(M_MY, F, H0, 'suicide', TANK), myGentleFree: simulate(M_MY, F, H0, 'gentle') };
  ctx.sim = SIM;

  /* ---------------- virtual camera ---------------- */
  const vcam = new THREE.PerspectiveCamera(38, 1, 0.1, 400);
  const cam = { pos: new THREE.Vector3(8, 3, 9), tgt: new THREE.Vector3(0, 0.8, 0), gp: new THREE.Vector3(8, 3, 9), gt: new THREE.Vector3(0, 0.8, 0), cut: true };
  function aim(px, py, pz, tx, ty, tz) { cam.gp.set(px, py, pz); cam.gt.set(tx, ty, tz); }
  function orbit(t, dist, yaw, pitch) { aim(t.x + Math.sin(yaw) * Math.cos(pitch) * dist, t.y + Math.sin(pitch) * dist, t.z + Math.cos(yaw) * Math.cos(pitch) * dist, t.x, t.y, t.z); }

  /* ---------------- overlay ---------------- */
  const css = document.createElement('style');
  css.textContent = `
  #rd{position:fixed;inset:0;pointer-events:none;z-index:6;opacity:0;transition:opacity .6s;font-size:12px;color:#BFEFFF;font-family:var(--f-cn,'PingFang SC','Noto Sans CJK SC',sans-serif)}
  #rd.on{opacity:1}
  #rd svg{position:absolute;inset:0;width:100%;height:100%;overflow:visible}
  #rd path{fill:none;stroke:rgba(127,219,255,.55);stroke-width:1}#rd circle{fill:#73D9FF}
  #rd path.hot{stroke:rgba(255,170,100,.75)}#rd circle.hot{fill:#FFB27A}
  .rd-top{position:absolute;left:50%;top:76px;transform:translateX(-50%);display:flex;align-items:center;gap:10px;padding:5px 14px;border:1px solid rgba(127,219,255,.3);border-radius:20px;background:rgba(4,12,18,.72);white-space:nowrap;pointer-events:auto;cursor:pointer}
  .rd-top b{color:#E4F8FF;font-weight:600;letter-spacing:.5px}.rd-top i{font-style:normal;color:#FFB27A}.rd-top em{font-style:normal;color:#9FDDF5;margin-left:6px}
  .rd-steps{position:absolute;left:50%;top:112px;transform:translateX(-50%);display:flex;gap:3px;width:min(720px,80vw)}
  .rd-steps div{flex:1;position:relative;padding-top:7px;color:rgba(159,221,245,.45);font-size:10.5px;text-align:center;white-space:nowrap;overflow:hidden}
  .rd-steps div::before{content:"";position:absolute;left:0;right:0;top:0;height:2px;background:rgba(127,219,255,.18)}
  .rd-steps div i{position:absolute;left:0;top:0;height:2px;background:#73D9FF;box-shadow:0 0 6px #73D9FF;width:0}
  .rd-steps div.now{color:#E4F8FF}.rd-steps div.done{color:rgba(159,221,245,.75)}
  .rd-call{position:absolute;max-width:290px;padding:8px 12px 9px;border:1px solid rgba(127,219,255,.45);border-radius:6px;background:rgba(4,14,22,.76);opacity:0;transition:opacity .45s;line-height:1.45}
  .rd-call.on{opacity:1}
  .rd-call b{display:block;color:#E4F8FF;font-size:15px;font-weight:600;margin-bottom:2px}
  .rd-call b small{font-family:var(--f-latin,inherit);font-weight:400;font-size:10.5px;color:#73D9FF;letter-spacing:1px;margin-left:6px}
  .rd-call span{display:block;color:#9FDDF5;font-size:12.5px}
  .rd-call.hot{border-color:rgba(255,170,100,.6);background:rgba(22,12,4,.74)}.rd-call.hot b small{color:#FFB27A}
  .rd-g{position:absolute;width:var(--pw,236px);box-sizing:border-box;padding:10px 12px 11px;background:linear-gradient(160deg,rgba(6,22,34,.82),rgba(3,10,16,.76));border:1px solid rgba(115,217,255,.25);opacity:0;transition:opacity .5s;
    clip-path:polygon(0 0,calc(100% - 12px) 0,100% 12px,100% 100%,12px 100%,0 calc(100% - 12px))}
  .rd-g.on{opacity:1}.rd-g.bad{border-color:rgba(255,110,90,.8);background:linear-gradient(160deg,rgba(40,10,8,.85),rgba(16,4,3,.8))}
  .rd-g h6{margin:0 0 8px;font:600 11px/1 var(--f-latin,monospace);letter-spacing:2px;color:#73D9FF;display:flex;justify-content:space-between}
  .rd-g h6 span{color:#FFB27A;letter-spacing:.5px;font-family:inherit}
  .rd-g .big{font:300 34px/1 var(--f-latin,monospace);color:#E8FAFF;font-variant-numeric:tabular-nums;text-shadow:0 0 14px rgba(115,217,255,.5)}
  .rd-g .big em{font-style:normal;font-size:13px;color:rgba(159,221,245,.65);margin-left:4px}
  .rd-g .tk{height:8px;margin:7px 0 2px;background:rgba(115,217,255,.12);position:relative;overflow:hidden}
  .rd-g .tk i{position:absolute;left:0;top:0;bottom:0;background:linear-gradient(90deg,#3FE0B8,#9CFFE0);box-shadow:0 0 8px #3FE0B8}
  .rd-g.bad .tk i{background:#FF6A5A;box-shadow:0 0 8px #FF6A5A}
  .rd-g .cap{display:flex;justify-content:space-between;font:10px var(--f-latin,monospace);color:rgba(159,221,245,.55);letter-spacing:1px}
  .rd-g dl{display:grid;grid-template-columns:auto 1fr;gap:4px 10px;margin:8px 0 0}
  .rd-g dt{color:rgba(159,221,245,.6);font-size:11.5px}.rd-g dd{margin:0;text-align:right;color:#E4F8FF;font:500 14px var(--f-latin,monospace);font-variant-numeric:tabular-nums}
  .rd-g canvas{display:block;width:100%;height:46px;margin-top:6px}
  .rd-g .ph{margin-top:7px;font-size:13px;color:#FFB27A;letter-spacing:.5px;min-height:18px}
  .rd-g.bad .ph{color:#FF8A7A}
  .rd-bill{position:absolute;right:max(18px,2vw);top:150px;display:flex;flex-direction:column;gap:10px;width:var(--pw,300px)}
  .rd-bill div{padding:12px 16px;border:1px solid rgba(255,170,100,.45);background:rgba(22,12,4,.74);opacity:0;transform:translateX(30px);transition:opacity .5s,transform .7s cubic-bezier(.2,.75,.2,1)}
  .rd-bill div.on{opacity:1;transform:none}
  .rd-bill b{display:block;font:300 28px/1.1 var(--f-latin,monospace);color:#FFE3C8}.rd-bill span{color:#FFC9A0;font-size:13px}
  .rd-bill small{display:block;margin-top:2px;font-size:10.5px;color:rgba(255,200,160,.5)}
  .rp{position:absolute;width:var(--pw);box-sizing:border-box;padding:10px 12px 11px;background:linear-gradient(160deg,rgba(6,22,34,.8),rgba(3,10,16,.74));border:1px solid rgba(115,217,255,.22);
    clip-path:polygon(0 0,calc(100% - 14px) 0,100% 14px,100% 100%,14px 100%,0 calc(100% - 14px));opacity:0;transform:translateX(var(--from));transition:opacity .6s,transform .8s cubic-bezier(.2,.75,.2,1)}
  .rp.on{opacity:1;transform:none}.rpL{--from:-30px}.rpR{--from:30px}
  .rp::before{content:"";position:absolute;left:0;top:0;width:22px;height:2px;background:#73D9FF;box-shadow:0 0 8px #73D9FF}
  .rp::after{content:"";position:absolute;inset:0;background:repeating-linear-gradient(0deg,rgba(115,217,255,.035) 0 1px,transparent 1px 3px);pointer-events:none}
  .rp h6{margin:0 0 7px;display:flex;justify-content:space-between;align-items:baseline;font:600 10px/1 var(--f-latin,ui-monospace,monospace);letter-spacing:2.2px;color:#73D9FF}
  .rp h6 span{color:rgba(159,221,245,.45);font-weight:400;letter-spacing:1px}
  .rp-big{font:300 30px/1.05 var(--f-latin,ui-monospace,monospace);color:#E8FAFF;letter-spacing:.5px;text-shadow:0 0 14px rgba(115,217,255,.55);font-variant-numeric:tabular-nums}
  .rp-big em{font-style:normal;font-size:12px;color:rgba(159,221,245,.6);margin-left:6px;letter-spacing:0}
  .rp-ph{color:#FFB27A;font-size:12.5px;margin:4px 0 8px}
  .rp-g{display:grid;grid-template-columns:1fr 1fr;gap:6px 10px}
  .rp-g div{border-left:2px solid rgba(115,217,255,.35);padding-left:7px;min-width:0}.rp-g div.hot{border-left-color:#FFB27A}
  .rp-g small{display:block;font-size:10px;line-height:1.3;color:rgba(159,221,245,.6);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .rp-g b{display:block;font:500 14.5px/1.3 var(--f-latin,monospace);color:#E4F8FF;font-variant-numeric:tabular-nums;white-space:nowrap}
  .rp-g b em{font-style:normal;font-size:10px;color:rgba(159,221,245,.6);margin-left:3px}
  .rp-g i{display:block;font-style:normal;font-size:9.5px;color:rgba(255,178,122,.75)}
  .rp canvas{display:block;width:100%}
  .rp-media{padding:8px}.rp-media .fr{position:relative;aspect-ratio:16/9;overflow:hidden;background:#02070b;border:1px solid rgba(115,217,255,.25)}
  .rp-media img,.rp-media video{width:100%;height:100%;object-fit:cover;display:block;filter:saturate(.85) contrast(1.05)}
  .rp-media .fr::after{content:"";position:absolute;inset:0;background:repeating-linear-gradient(0deg,rgba(0,0,0,.18) 0 1px,transparent 1px 3px),linear-gradient(180deg,transparent 60%,rgba(2,7,11,.7));pointer-events:none}
  .rp-media .rec{position:absolute;left:8px;top:7px;z-index:1;font:600 9.5px var(--f-latin,monospace);letter-spacing:1.5px;color:#FF7A6A}.rp-media .rec::before{content:"●";margin-right:5px;animation:rdblink 1.2s infinite}
  @keyframes rdblink{50%{opacity:.2}}
  .rp-media p{margin:6px 2px 0;font-size:11.5px;color:#DCEFF7;line-height:1.45}.rp-media small{display:block;margin:2px 2px 0;font-size:10px;color:rgba(159,221,245,.55)}
  .rp-thr{display:flex;gap:12px;align-items:center}.rp-thr svg{position:static!important;width:92px!important;height:150px!important;flex:none}
  #rd .rp-thr path{fill:rgba(115,217,255,.06);stroke:rgba(115,217,255,.5)}
  #rd .rp-thr circle{fill:rgba(115,217,255,.12);stroke:rgba(115,217,255,.5);stroke-width:1}
  #rd .rp-thr circle.on{fill:#FFE3C8;stroke:#FFE3C8;filter:drop-shadow(0 0 4px #FF9A4A)}
  .rp-thr .tx{font-size:11px;line-height:1.6;color:rgba(191,239,255,.8);min-width:0}.rp-thr .tx b{display:block;font:500 18px/1.2 var(--f-latin,monospace);color:#E4F8FF}
  .rp-thr .tx span{display:block}.rp-thr .tx span.on{color:#FFC48A}
  .rp-air{display:flex;flex-direction:column;gap:7px}
  .rp-air .row{display:grid;grid-template-columns:auto 1fr auto;gap:4px 8px;align-items:center;font-size:11px}
  .rp-air .bar{height:7px;background:rgba(115,217,255,.1);position:relative;overflow:hidden}.rp-air .bar i{position:absolute;left:0;top:0;bottom:0;background:linear-gradient(90deg,#3FE0B8,#9CFFE0);transition:width .25s}
  .rp-air .bar.p i{background:linear-gradient(90deg,#73D9FF,#E4F8FF)}.rp-air .bar.low i{background:#FF6A5A}
  .rp-air b{font:500 11px var(--f-latin,monospace);color:#E4F8FF;font-variant-numeric:tabular-nums;min-width:52px;text-align:right}
  .rp-air .hd{font:9.5px var(--f-latin,monospace);letter-spacing:1.6px;color:rgba(159,221,245,.55)}
  .rp-air .nt{font-size:10px;color:rgba(159,221,245,.45)}
  .rp-log{list-style:none;margin:0;padding:0;font-size:11px}
  #rd .rp-log li{display:flex;gap:8px;padding:2px 0;line-height:15px;white-space:nowrap;color:rgba(159,221,245,.38);transition:color .4s;overflow:hidden}
  #rd .rp-log li b{font:400 10.5px var(--f-latin,monospace);min-width:44px;font-variant-numeric:tabular-nums}
  #rd .rp-log li.d{color:rgba(191,239,255,.75)}#rd .rp-log li.n{color:#FFE3C8;text-shadow:0 0 8px rgba(255,150,80,.6)}
  #rd .rp-log li.n::before{content:"▶";color:#FFB27A;margin-right:-4px}
  @media (max-width:1100px){.rp,.rd-g,.rd-bill{display:none}}
  .rd-foot{position:absolute;left:50%;bottom:16px;transform:translateX(-50%);font:9.5px var(--f-latin,monospace);letter-spacing:2px;color:rgba(159,221,245,.4);white-space:nowrap}`;
  document.head.appendChild(css);
  const ov = document.createElement('div'); ov.id = 'rd';
  const gaugeHTML = (id, title) => `<div class="rd-g" id="${id}"><h6>${title}<span></span></h6><div class="big"><b class="u">0.0</b><em>kg 用气</em></div>
    <div class="tk"><i></i></div><div class="cap"><span>满罐 250 kg</span><span class="pct">0%</span></div>
    <dl><dt>高度</dt><dd class="h">10.0 m</dd><dt>下落速度</dt><dd class="v">0.0 m/s</dd><dt>喷口开度</dt><dd class="f">0%</dd><dt>过载</dt><dd class="a">0.0 g</dd></dl>
    <canvas width="420" height="80"></canvas><div class="ph"></div></div>`;
  ov.innerHTML = `<svg></svg><div class="rd-top"><b>ROADSTER · 冷气推进器</b><i></i><em>收起 ×</em></div><div class="rd-steps"></div>
    ${gaugeHTML('rdA', 'A · 慢慢放')}${gaugeHTML('rdB', 'B · 自杀式点火')}
    <div class="rd-bill"></div>
    <div class="rp rpL" id="rpV"><h6><span class="t"></span><span class="tg"></span></h6><div class="rp-big"></div><div class="rp-ph"></div><div class="rp-g"></div></div>
    <div class="rp rpL" id="rpC"><h6><span class="t"></span><span class="tg"></span></h6><canvas width="560" height="250"></canvas></div>
    <div class="rp rpL rp-media" id="rpM"><div class="fr"><span class="rec">ARCHIVE · 真实影像</span></div><p></p><small></small></div>
    <div class="rp rpR" id="rpT"><h6>THRUSTERS · 推进器<span>COLD GAS</span></h6><div class="rp-thr"><svg viewBox="-60 -112 120 230"></svg><div class="tx"><b class="n">0 / 10</b><span class="g0">四角 · 朝下 × 4</span><span class="g1">车尾 · 朝后 × 4</span><span class="g2">侧面 · 横向 × 2</span><span class="kn"></span></div></div></div>
    <div class="rp rpR" id="rpA"><h6>AIR SUPPLY · 气源<span>COPV × 2</span></h6><div class="rp-air"></div></div>
    <div class="rp rpR" id="rpL"><h6>EVENT LOG · 事件<span>T+</span></h6><ul class="rp-log"></ul></div>
    <div class="rd-foot">特斯拉公布：10 个推进器 · 约 600 L · 约 250 kg 空气 · 推力 >80 kN ｜ 车重、下落过程为估算</div>`;
  document.body.appendChild(ov);
  const svg = ov.querySelector('svg'), topPh = ov.querySelector('.rd-top i'), steps = ov.querySelector('.rd-steps'), billEl = ov.querySelector('.rd-bill');
  const GA = ov.querySelector('#rdA'), GB = ov.querySelector('#rdB');
  ov.querySelector('.rd-top').addEventListener('click', () => hide());
  addEventListener('keydown', (e) => { if (e.key === 'Escape' && ST.on) hide(); });

  let calls = [];
  function clearCalls() { calls.forEach(c => { c.el.remove(); c.path.remove(); c.dot.remove(); }); calls = []; }
  function makeCalls(list) {
    clearCalls();
    calls = list.map(q => {
      const el = document.createElement('div'); el.className = 'rd-call' + (q.hot ? ' hot' : '');
      el.innerHTML = `<b>${q.t}${q.en ? `<small>${q.en}</small>` : ''}</b>` + q.l.map(s => `<span>${s}</span>`).join('');
      ov.appendChild(el);
      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path'); const dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      dot.setAttribute('r', '2.5'); if (q.hot) { path.classList.add('hot'); dot.classList.add('hot'); } svg.appendChild(path); svg.appendChild(dot);
      return { ...q, el, path, dot, v: new THREE.Vector3(), op: 0 };
    });
  }
  const PW = (w) => Math.round(Math.min(300, Math.max(230, w * 0.16)));
  const sideW = (w) => w > 1100 ? PW(w) + 40 : 16;
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
      const ew = c.el.offsetWidth, eh = c.el.offsetHeight, off = (c.dx ?? 140) * sc;
      const bx = ax + side * off, by = ay + (c.dy ?? -30) * sc;
      const M = sideW(w); let left = side > 0 ? bx : bx - ew; left = Math.max(M, Math.min(w - ew - M, left)); const top = Math.max(150, Math.min(h - eh - 60, by - eh / 2));
      c.el.style.left = left + 'px'; c.el.style.top = top + 'px';
      const ex = side > 0 ? left : left + ew, ey = top + Math.min(eh / 2, 14), mx = ax + (ex - ax) * 0.55;
      c.path.setAttribute('d', `M${ax.toFixed(1)},${ay.toFixed(1)} L${mx.toFixed(1)},${ey.toFixed(1)} L${ex.toFixed(1)},${ey.toFixed(1)}`);
      c.dot.setAttribute('cx', ax.toFixed(1)); c.dot.setAttribute('cy', ay.toFixed(1));
    });
  }
  /* gauges: anchored over a car, fed from a simulated drop */
  const gauge = (el) => ({ el, u: el.querySelector('.u'), bar: el.querySelector('.tk i'), pct: el.querySelector('.pct'), h: el.querySelector('.h'), v: el.querySelector('.v'), f: el.querySelector('.f'),
    a: el.querySelector('.a'), ph: el.querySelector('.ph'), tag: el.querySelector('h6 span'), cv: el.querySelector('canvas'), anchor: new THREE.Vector3() });
  const gA = gauge(GA), gB = gauge(GB);
  function setGauge(gg, S, t, phase, tag) {
    const s = sampleAt(S, t);
    gg.u.textContent = s.used.toFixed(1); const p = Math.min(100, s.used / TANK * 100); gg.bar.style.width = p + '%'; gg.pct.textContent = p.toFixed(0) + '%';
    gg.h.textContent = s.h.toFixed(1) + ' m'; gg.v.textContent = Math.max(0, s.v).toFixed(1) + ' m/s'; gg.f.textContent = Math.round(s.f * 100) + '%'; gg.a.textContent = (s.acc || 0).toFixed(1) + ' g';
    gg.ph.textContent = phase || ''; gg.tag.textContent = tag || '';
    gg.el.classList.toggle('bad', !!(S.empty && t >= S.empty.t));
    const c = gg.cv.getContext('2d'), W = gg.cv.width, H = gg.cv.height; c.clearRect(0, 0, W, H);
    c.strokeStyle = 'rgba(115,217,255,.15)'; c.beginPath(); c.moveTo(0, H - 2); c.lineTo(W, H - 2); c.stroke();
    const TT = 11; c.strokeStyle = '#FFB27A'; c.lineWidth = 2; c.shadowColor = '#FF9A4A'; c.shadowBlur = 6; c.beginPath();
    S.pts.filter(q => q.t <= t).forEach((q, j) => { const x = q.t / TT * W, y = H - 4 - q.f * (H - 10); j ? c.lineTo(x, y) : c.moveTo(x, y); }); c.stroke(); c.shadowBlur = 0;
    c.fillStyle = 'rgba(159,221,245,.5)'; c.font = '18px ui-monospace,monospace'; c.fillText('喷口开度', 4, 18);
    return s;
  }
  function placeGauge(gg, obj, off, w, h, on) {
    gg.el.classList.toggle('on', on); if (!on) return;
    gg.anchor.set(...off); obj.localToWorld(gg.anchor); gg.anchor.project(camera);
    const x = (gg.anchor.x + 1) / 2 * w, y = (1 - gg.anchor.y) / 2 * h;
    gg.el.style.left = Math.max(20, Math.min(w - 256, x - 118)) + 'px'; gg.el.style.top = Math.max(140, Math.min(h - 420, y - 360)) + 'px';
  }
  function fixGauge(gg, side, w, h, on) {
    gg.el.classList.toggle('on', on); if (!on) return;
    gg.el.style.left = (side === 'l' ? Math.round(w * 0.05) : Math.round(w * 0.95 - 236)) + 'px'; gg.el.style.top = Math.round(Math.max(160, h * 0.2)) + 'px';
  }
  function billCards(list) { billEl.innerHTML = list.map(c => `<div><b>${c.b}</b><span>${c.s}</span>${c.n ? `<small>${c.n}</small>` : ''}</div>`).join(''); return [...billEl.children]; }
  let billEls = [];

  /* ---------------- side panels: data card, chart, real footage | thrusters, air supply, event log ---------------- */
  const $ = (q) => ov.querySelector(q);
  const P = { V: $('#rpV'), C: $('#rpC'), M: $('#rpM'), T: $('#rpT'), A: $('#rpA'), L: $('#rpL') };
  const cv = P.C.querySelector('canvas'), cx = cv.getContext('2d');
  /* top view of the car, front up, with the 10 nozzles in their groups */
  { const sv = P.T.querySelector('svg'); let h = '<path d="M-34,-100 Q0,-112 34,-100 L46,-60 L48,80 Q46,104 30,106 L-30,106 Q-46,104 -48,80 L-46,-60 Z"/>';
    h += '<path d="M-28,-40 L28,-40 L32,30 L-32,30 Z" style="fill:rgba(115,217,255,.03)"/>';
    [[-36, -78], [36, -78], [-36, 82], [36, 82]].forEach(([x, y]) => h += `<circle data-g="0" cx="${x}" cy="${y}" r="7"/>`);
    [-27, -9, 9, 27].forEach(x => h += `<circle data-g="1" cx="${x}" cy="110" r="6"/>`);
    h += '<circle data-g="2" cx="-52" cy="-46" r="6"/><circle data-g="3" cx="52" cy="-46" r="6"/>';
    sv.innerHTML = h; }
  const thrDots = [...P.T.querySelectorAll('circle')];
  let MEDIA = [], MEDIA_V = '', mediaKey = '', layoutKey = '', logKey = '';
  fetch('media/roadster/manifest.json', { cache: 'no-store' }).then(r => r.ok ? r.json() : null).then(j => { MEDIA = (j && j.items) || []; MEDIA_V = (j && j.fetched) || ''; mediaKey = ''; }).catch(() => {});
  function showMedia(key) {
    if (key === mediaKey) return; mediaKey = key;
    const it = MEDIA.find(m => m.stage === key), fr = P.M.querySelector('.fr'); fr.querySelectorAll('img,video').forEach(e => e.remove());
    if (!it) { P.M.dataset.empty = '1'; return; } delete P.M.dataset.empty;
    const vid = /\.(mp4|mov|webm|m4v)$/i.test(it.src), el = document.createElement(vid ? 'video' : 'img'); el.src = 'media/roadster/' + it.src + (MEDIA_V ? '?v=' + MEDIA_V : '');
    if (vid) { el.muted = true; el.loop = true; el.autoplay = true; el.playsInline = true; } fr.appendChild(el); if (vid) { const pl = el.play(); if (pl && pl.catch) pl.catch(() => {}); }
    P.M.querySelector('p').textContent = it.caption || ''; P.M.querySelector('small').textContent = it.source ? '来源 · ' + it.source : '';
  }
  const fmt = (n, d = 1) => (Math.abs(n) >= 1000 ? Math.round(n).toLocaleString('en-US') : n.toFixed(d));
  /* tank state from air used: adiabatic blowdown, p ∝ (m/m0)^1.4, T ∝ (m/m0)^0.4 (CellCog-style estimate) */
  const tankState = (used) => { const f = Math.max(0, 1 - used / TANK); return { left: TANK * f, p: 380 * Math.pow(f, 1.4), T: 293 * Math.pow(f, 0.4) - 273, f }; };
  function airRows(list) {   /* list: [{name, used}] */
    return list.map(t => { const k = tankState(t.used); const low = k.f < 0.05;
      return `<span class="hd">${t.name}</span><div class="row"><span>剩余</span><div class="bar${low ? ' low' : ''}"><i style="width:${(k.f * 100).toFixed(1)}%"></i></div><b>${k.left.toFixed(0)} kg</b></div>
        <div class="row"><span>压力</span><div class="bar p"><i style="width:${(k.p / 380 * 100).toFixed(1)}%"></i></div><b>${k.p.toFixed(0)} bar</b></div>
        <div class="row"><span>气温</span><div class="bar p"><i style="width:${Math.max(0, (k.T + 120) / 140 * 100).toFixed(1)}%"></i></div><b>${k.T.toFixed(0)} °C</b></div>`; }).join('') +
      '<span class="nt">满罐 ≈250 kg · ≈380 bar（特斯拉）；压力和气温随用气下降为估算</span>';
  }
  function dataCard(d) {
    P.V.querySelector('.t').textContent = d.title; P.V.querySelector('.tg').textContent = d.tag || '';
    P.V.querySelector('.rp-big').innerHTML = d.big; P.V.querySelector('.rp-ph').textContent = d.phase || '';
    P.V.querySelector('.rp-g').innerHTML = d.rows.map(r => `<div class="${r[3] ? 'hot' : ''}"><small>${r[0]}</small><b>${r[1]}</b>${r[2] ? `<i>${r[2]}</i>` : ''}</div>`).join('');
  }
  function thrusters(on, kn) {
    const g = [on.x, on.y, on.z, on.w], lit = (i) => g[i] > 0.05;
    thrDots.forEach(c => c.classList.toggle('on', lit(+c.dataset.g)));
    const n = (lit(0) ? 4 : 0) + (lit(1) ? 4 : 0) + (lit(2) ? 1 : 0) + (lit(3) ? 1 : 0);
    P.T.querySelector('.n').textContent = n + ' / 10';
    P.T.querySelector('.g0').classList.toggle('on', lit(0)); P.T.querySelector('.g1').classList.toggle('on', lit(1)); P.T.querySelector('.g2').classList.toggle('on', lit(2) || lit(3));
    P.T.querySelector('.kn').textContent = kn || '';
  }
  function eventLog(list, u) {
    const ul = P.L.querySelector('ul'), key = ST.idx + ':' + list.length;
    if (key !== logKey) { logKey = key; ul.innerHTML = list.map(e => `<li><b>${e[0] < 0 ? '—' : 'T+' + e[0].toFixed(1)}</b><span>${e[1]}</span></li>`).join(''); }
    let cur = -1; list.forEach((e, j) => { if (u >= e[2]) cur = j; });
    [...ul.children].forEach((li, j) => li.className = j < cur ? 'd' : j === cur ? 'n' : '');
  }
  /* charts */
  const CH = { grid: 'rgba(115,217,255,.12)', txt: 'rgba(159,221,245,.6)', cy: '#73D9FF', am: '#FFB27A', red: '#FF6A5A', wh: '#E4F8FF' };
  function chartFrame(title, tag) { P.C.querySelector('.t').textContent = title; P.C.querySelector('.tg').textContent = tag || ''; cx.clearRect(0, 0, cv.width, cv.height); cx.font = '19px ui-monospace,Menlo,monospace'; cx.textBaseline = 'middle'; }
  function bars(list, max, unit, fmtv = (v) => v) {   /* list: [label, value, color, note] */
    const W = cv.width, H = cv.height, n = list.length, rowH = (H - 16) / n, x0 = 150, x1 = W - 96;
    list.forEach(([lab, v, col, note], i) => { const y = 10 + i * rowH + rowH / 2, bw = (x1 - x0) * Math.min(1, v / max);
      cx.fillStyle = CH.txt; cx.textAlign = 'right'; cx.fillText(lab, x0 - 12, y);
      cx.fillStyle = 'rgba(115,217,255,.08)'; cx.fillRect(x0, y - rowH * 0.28, x1 - x0, rowH * 0.56);
      cx.fillStyle = col; cx.shadowColor = col; cx.shadowBlur = 8; cx.fillRect(x0, y - rowH * 0.28, bw, rowH * 0.56); cx.shadowBlur = 0;
      cx.fillStyle = CH.wh; cx.textAlign = 'left'; cx.fillText(fmtv(v) + ' ' + unit + (note || ''), Math.min(x0 + bw + 10, x1 + 6), y); });
    cx.textAlign = 'left';
  }
  function lines(series, tMax, yMax, u, opts = {}) {   /* series: [{S, key, col, label}] plotted over sim time */
    const W = cv.width, H = cv.height, L = 46, R = W - 12, T = 16, B = H - 30, px = (t) => L + (R - L) * t / tMax, py = (y) => B - (B - T) * y / yMax;
    cx.strokeStyle = CH.grid; cx.lineWidth = 1; for (let y = 0; y <= yMax; y += opts.step || 2) { cx.beginPath(); cx.moveTo(L, py(y)); cx.lineTo(R, py(y)); cx.stroke(); cx.fillStyle = CH.txt; cx.textAlign = 'right'; cx.fillText(String(y), L - 8, py(y)); }
    cx.textAlign = 'left'; cx.fillStyle = CH.txt; cx.fillText(opts.yLabel || '', L + 4, T + 2); cx.textAlign = 'right'; cx.fillText(opts.xLabel || '秒', R, B + 18); cx.textAlign = 'left';
    series.forEach(sr => { const pts = sr.S.pts; cx.strokeStyle = sr.col; cx.globalAlpha = 0.25; cx.lineWidth = 2; cx.beginPath(); pts.forEach((q, j) => j ? cx.lineTo(px(q.t), py(q[sr.key])) : cx.moveTo(px(q.t), py(q[sr.key]))); cx.stroke(); cx.globalAlpha = 1;
      cx.lineWidth = 3; cx.shadowColor = sr.col; cx.shadowBlur = 10; cx.beginPath(); pts.filter(q => q.t <= u).forEach((q, j) => j ? cx.lineTo(px(q.t), py(q[sr.key])) : cx.moveTo(px(q.t), py(q[sr.key]))); cx.stroke(); cx.shadowBlur = 0;
      const c = sampleAt(sr.S, Math.max(0, u)); cx.fillStyle = sr.col; cx.beginPath(); cx.arc(px(Math.min(u, sr.S.T)), py(c[sr.key] ?? 0), 6, 0, TAU); cx.fill();
      if (sr.label) { cx.fillStyle = sr.col; cx.fillText(sr.label, px(Math.min(u, sr.S.T)) + 10, py(c[sr.key] ?? 0) - 14); }
      if (sr.mark) sr.mark(px, py); });
  }
  const PANELS = {
    car: (u) => ({ L: ['V', 'C', 'M'], R: ['T', 'A', 'L'],
      v: { title: 'ROADSTER · 第二代', tag: 'TESLA', big: '0.81<em>s · 0–97 km/h 现场</em>', phase: '发布会 · 2026.10.15 · 计划 2028 年量产',
        rows: [['推进器', '10<em>个</em>', '冷气 · 不烧燃料'], ['车尾推力', '>80<em>kN</em>', '', 1], ['气瓶容积', '≈600<em>L</em>'], ['装气', '≈250<em>kg</em>'], ['气瓶压力', '≈380<em>bar</em>', '5,500 psi'], ['吸附下压力', '4,700<em>kg</em>', '屏幕读数']] },
      chart() { chartFrame('0–97 km/h · 用时', '秒'); bars([['只靠轮胎*', 2.17, CH.txt, ''], ['Rimac Nevera', 1.74, CH.cy], ['Performance', 1.6, CH.cy, ' 内'], ['Thrust', 0.9, CH.am, ' 内'], ['发布会现场', 0.81, CH.am], ['Rocket', 0.59, CH.am, ' 内']], 2.4, 's', v => v.toFixed(2)); cx.fillStyle = CH.txt; cx.font = '15px ui-monospace,monospace'; cx.fillText('*按轮胎极限 1.26 g 推算', 12, cv.height - 8); },
      thr: [0, 0, 0, 0], kn: '静止', air: [{ name: 'COPV · 满罐', used: 0 }],
      log: [[0, '粒子重建 Roadster', 0.3], [1.4, '发布 · 2026.10.15', 1.4], [3.2, '现场 0.81 s', 3.2], [6, '量产计划 · 2028', 6]] }),
    suction: (u) => { const dp = 11.5 * sm(1.2, 2.6, u), inv = sm(5.5, 8.5, u) > 0.5;
      return { L: ['V', 'C', 'M'], R: ['T', 'A', 'L'],
        v: { title: 'SUCTION · 风扇吸附', tag: inv ? '倒挂' : '正放', big: `${dp.toFixed(1)}<em>kPa · 车底比外面低</em>`, phase: `≈ ${(dp / 101.3 * 100).toFixed(0)}% 个大气压 · 外面的空气把车按住`,
          rows: [['下压力读数', '4,700<em>kg</em>', '发布会屏幕', 1], ['估算车重', '≈2,350<em>kg</em>', '= 4,700 ÷ 2 g'], ['密封底面积', '≈4<em>m²</em>', '估算'], ['最大吸附', '≈2<em>g</em>', '马斯克现场'], ['过弯极限', '≈2<em>g</em>', 'Moravy'], ['车速', '0<em>km/h</em>', '静止也能吸']] },
        chart() { chartFrame('风扇功率 · 按裙边缝隙', '估算 kW'); bars([['缝隙 3 mm', 62, CH.cy], ['缝隙 5 mm', 104, CH.cy], ['缝隙 10 mm', 208, CH.am]], 240, 'kW'); cx.fillStyle = CH.txt; cx.font = '15px ui-monospace,monospace'; cx.fillText('难点是密封：每多 1 mm 缝隙 ≈ 多 20 kW', 12, cv.height - 8); },
        thr: [0, 0, 0, 0], kn: '推进器未点火 · 只用风扇', air: [{ name: 'COPV · 满罐', used: 0 }],
        log: [[0.5, '风扇启动', 1.2], [1.5, '车底压力下降', 2.0], [2.8, '大气压把车按住', 2.8], [5.5, '平台翻转', 5.5], [8.6, '倒挂 · 4,700 kg', 8.6]] }; },
    thrust: (u) => { const used = 43 * Math.max(0, u - 10.2);
      return { L: ['V', 'C', 'M'], R: ['T', 'A', 'L'],
        v: { title: 'THRUSTERS · 推进器', tag: 'SPACEX', big: '10<em>个喷口 · 全电动阀门</em>', phase: u > 10.2 ? '全部点火 · 离地' : '透视：两个碳纤维气瓶 + 10 个喷口',
          rows: [['四角 · 朝下', '4', '托起 · 落地'], ['车尾 · 朝后', '4', '>80 kN', 1], ['侧面 · 横向', '2', '转身 · 横滚'], ['阀门响应', '<1<em>s</em>', '零点几秒微调'], ['推力 / 轮胎', '4–5<em>×</em>', '特斯拉'], ['喷流', '超音速', '马赫环']] },
        chart() { chartFrame('推力对比', 'kN'); bars([['轮胎抓地上限', 29, CH.cy, ' ≈'], ['托起车身需要', 23, CH.cy, ' ≈'], ['车尾 4 个喷口', 80, CH.am, '+']], 95, 'kN'); cx.fillStyle = CH.txt; cx.font = '15px ui-monospace,monospace'; cx.fillText('车重按 2,350 kg 估 · 轮胎 1.26 g（Moravy）', 12, cv.height - 8); },
        thr: null, kn: u > 10.2 ? '≈ 23 kN 向上 · 托住车身（估）' : '', air: [{ name: 'COPV × 2 · ≈600 L', used }],
        log: [[1.2, '车身透视 · 气瓶', 1.2], [2.0, '四角喷口 × 4', 2.0], [4.6, '车尾喷口 × 4', 4.6], [7.2, '侧面喷口 × 2', 7.2], [10.0, '全部点火 · 离地', 10.0]] }; },
    nozzle: (u) => ({ L: ['V', 'C', 'M'], R: ['A', 'L'],
      v: { title: 'NOZZLE · 喷管剖面', tag: '理想估算', big: '380<em>bar → 喉部 Ma 1 → 超音速</em>', phase: u > 6.5 ? '出口气温骤降 · 水汽冻成雾' : '收窄 → 音速 → 张开 → 继续加速',
        rows: [['气瓶', '380<em>bar</em>', '20 °C'], ['喉部', 'Ma 1', '≈200 bar · ≈−29 °C'], ['出口（理想）', 'Ma 4.7', '完全膨胀'], ['出口气温', '−219<em>°C</em>', '理想 · 实际高些', 1], ['排气速度', '540–690<em>m/s</em>'], ['比冲', '55–70<em>s</em>', '化学火箭的 1/5']] },
      chart() { chartFrame('沿喷管：速度↑ 温度↓', '理想'); const W = cv.width, H = cv.height, L = 40, R = W - 20, T = 26, B = H - 40;
        const X = [0, 0.33, 1], V = [0, 313, 693], Tt = [20, -29, -219], px = (x) => L + (R - L) * x;
        cx.strokeStyle = CH.grid; [0, 0.5, 1].forEach(y => { cx.beginPath(); cx.moveTo(L, T + (B - T) * y); cx.lineTo(R, T + (B - T) * y); cx.stroke(); });
        const draw = (Y, lo, hi, col, lab) => { cx.strokeStyle = col; cx.lineWidth = 3; cx.shadowColor = col; cx.shadowBlur = 8; cx.beginPath(); X.forEach((x, i) => { const y = B - (B - T) * (Y[i] - lo) / (hi - lo); i ? cx.lineTo(px(x), y) : cx.moveTo(px(x), y); }); cx.stroke(); cx.shadowBlur = 0;
          X.forEach((x, i) => { const y = B - (B - T) * (Y[i] - lo) / (hi - lo); cx.fillStyle = col; cx.beginPath(); cx.arc(px(x), y, 5, 0, TAU); cx.fill(); cx.fillText(lab(Y[i]), Math.min(px(x) + 8, R - 110), y - 14); }); };
        draw(V, 0, 750, CH.am, v => v + ' m/s'); draw(Tt, -240, 40, CH.cy, v => v + ' °C');
        cx.fillStyle = CH.txt; ['气瓶', '喉部', '出口'].forEach((t, i) => cx.fillText(t, Math.min(px(X[i]) - 12, R - 50), H - 14)); },
      thr: null, kn: '', air: [{ name: '单个喷口 · 全开时', used: 0 }],
      log: [[0.8, '高压空气 · 380 bar', 0.8], [1.5, '电动阀门打开', 1.5], [2.2, '喉部 · 刚好音速', 2.2], [3.2, '扩张段 · 超音速', 3.2], [4.2, '马赫环', 4.2], [7.0, '降温结霜 · 白雾', 7.0]] }),
    compare: (u) => { const t = u - 2.2, A = SIM.rsGentle, B = SIM.rsBurn;
      return { L: ['gA', 'C', 'M'], R: ['gB', 'A', 'L'],
        chart() { chartFrame('高度 · 两种落法', 'm / 秒'); lines([{ S: A, key: 'h', col: CH.cy, label: 'A', mark: (px, py) => { if (A.empty) { cx.fillStyle = CH.red; cx.fillText('没气', px(A.empty.t) + 8, py(A.empty.h) + 18); cx.beginPath(); cx.arc(px(A.empty.t), py(A.empty.h), 5, 0, TAU); cx.fill(); } } },
          { S: B, key: 'h', col: CH.am, label: 'B', mark: (px, py) => { cx.fillStyle = CH.am; cx.fillText('全开', px(B.fireT) + 8, py(B.fireH) - 14); } }], 8, 10.5, Math.max(0, t), { yLabel: '高度 m', step: 2 }); },
        air: [{ name: 'A · 慢慢放', used: sampleAt(A, Math.max(0, t)).used }, { name: 'B · 自杀式点火', used: sampleAt(B, Math.max(0, t)).used }],
        log: [[0, 'A 一离开就开喷', 2.2], [0, 'B 喷口全关 · 自由落体', 2.2], [B.fireT, `B 离地 ${B.fireH.toFixed(1)} m · 全开`, 2.2 + B.fireT], [B.T, `B 落地 · ${B.used.toFixed(0)} kg`, 2.2 + B.T],
          [A.empty.t, `A 没气 · 离地 ${A.empty.h.toFixed(1)} m`, 2.2 + A.empty.t], [A.T, `A 砸地 · ${(A.vLand * 3.6).toFixed(0)} km/h`, 2.2 + A.T]] }; },
    modely: (u) => { const t = u - 9.0, S = SIM.myBurn, s = sampleAt(S, Math.max(0, t));
      return { L: [u > 7.4 ? 'gB' : 'V', 'C', 'M'], R: ['T', 'A', 'L'],
        v: { title: 'MODEL Y · 我们的车', tag: '2026 款', big: '1,891<em>kg · 长续航后驱</em>', phase: u < 2.4 ? '整车载入' : '装上 Roadster 同款：10 个喷口 · 250 kg 空气',
          rows: [['车长', '4,797<em>mm</em>'], ['轴距', '2,890<em>mm</em>'], ['加满气后', '2,141<em>kg</em>', '未计气瓶自重'], ['托起需要', '21.0<em>kN</em>', '估算', 1], ['悬停耗气', '38.9<em>kg/s</em>', '估算'], ['纯悬停', '≈6.4<em>s</em>', '一罐 · 估算']] },
        chart() { chartFrame('高度 · 喷口开度', 'MODEL Y'); lines([{ S, key: 'h', col: CH.am, label: '高度' }, { S: { ...S, pts: S.pts.map(q => ({ ...q, f10: q.f * 10 })) }, key: 'f10', col: CH.cy }], 3.4, 10.5, Math.max(0, t), { yLabel: '高度 m · 开度 ×10%', step: 2 });
          cx.fillStyle = CH.am; cx.fillText(`离地 ${S.fireH.toFixed(1)} m 全开`, 52, 40); },
        thr: null, kn: s.f > 0.05 && !s.landed ? '80 kN · 四角全开刹车' : '', air: [{ name: 'COPV · Model Y', used: s.used }],
        log: [[0.8, '载入 Model Y · 1,891 kg', 0.8], [3.0, '装四角喷口', 3.0], [4.0, '装车尾喷口', 4.0], [5.0, '装侧面喷口 · 气瓶', 5.0], [9.0, '离开平台 · 自由落体', 9.0],
          [S.fireT, `离地 ${S.fireH.toFixed(1)} m · 全开`, 9.0 + S.fireT], [S.T, `落地 · ${S.used.toFixed(0)} kg · ${S.T.toFixed(2)} s`, 9.0 + S.T]] }; },
    bill: (u) => ({ L: ['V', 'C', 'M'], R: ['bill'],
      v: { title: 'REALITY · 现实账单', tag: '估算', big: '105<em>kg 空气 · 一次 10 m 落地</em>', phase: '数学上能落 · 现实里别试',
        rows: [['满罐', '≈250<em>kg</em>'], ['压力', '≈380<em>bar</em>'], ['爆破能量', '≈11<em>kg TNT</em>', 'CellCog 估算', 1], ['充一罐电', '60–75<em>kWh</em>', '估算'], ['公路', '不允许', 'Rocket 版', 1], ['发布会飞行', '无人', '车里没坐人']] },
      chart() { chartFrame('充满一罐要多久', '估算 · 小时'); bars([['11 kW 压缩机', 6.8, CH.am], ['20 kW 压缩机', 3.7, CH.cy]], 8, 'h'); cx.fillStyle = CH.txt; cx.font = '15px ui-monospace,monospace'; cx.fillText('按压缩效率 50% 估 · 飞一次 2.77 秒', 12, cv.height - 8); },
      thr: [0, 0, 0, 0], air: [] })
  };
  function panels(sc, u, w, h) {
    const pw = PW(w); ov.style.setProperty('--pw', pw + 'px');
    const d = (PANELS[sc.key] || (() => null))(u); if (!d) { Object.values(P).forEach(e => e.classList.remove('on')); return; }
    const vis = new Set([...d.L, ...d.R]);
    const els = { V: P.V, C: P.C, M: P.M, T: P.T, A: P.A, L: P.L, gA: GA, gB: GB, bill: billEl };
    Object.entries(els).forEach(([k, e]) => { if (k === 'bill') return; e.classList.toggle('on', vis.has(k) && !(k === 'M' && P.M.dataset.empty)); });
    const stack = (keys, side) => { let y = 150; keys.forEach(k => { const e = els[k]; if (!e || (k === 'M' && P.M.dataset.empty)) return; e.style.top = y + 'px';
      if (side === 'l') { e.style.left = Math.max(18, w * 0.02) + 'px'; e.style.right = 'auto'; } else { e.style.right = Math.max(18, w * 0.02) + 'px'; e.style.left = 'auto'; } y += e.offsetHeight + 12; }); };
    stack(d.L, 'l'); stack(d.R, 'r');
    if (d.v && vis.has('V')) dataCard(d.v);
    if (d.chart) d.chart();
    if (vis.has('T')) { const live = sc.key === 'thrust' ? RS.plumeMat.uniforms.uOn.value : sc.key === 'modely' ? MY.plumeMat.uniforms.uOn.value : new THREE.Vector4(0, 0, 0, 0); thrusters(live, d.kn); }
    if (vis.has('A')) P.A.querySelector('.rp-air').innerHTML = airRows(d.air || []);
    if (d.log) eventLog(d.log, u);
    showMedia(sc.key);
  }

  /* ---------------- director helpers ---------------- */
  const cl = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
  const sm = (a, b, x) => { const t = cl((x - a) / (b - a)); return t * t * (3 - 2 * t); };
  const lerp = (a, b, t) => a + (b - a) * t;
  const ex = new THREE.Vector3(1, 0, 0), ey = new THREE.Vector3(0, 1, 0);
  function ring(mat, a, b, c, d = 0) { mat.uniforms.uOn.value.set(a, b, c, d); }
  function noz(mat, a, b, c, d = 0) { mat.uniforms.uRing.value.set(a, b, c, d); }
  const RIGS = [RSR, RS2R, MYR, GR.g, SLAB.g, LEDGE_A.g, LEDGE_B.g, NOZ.g, ...mists];
  function show(...objs) { RIGS.forEach(o => o.visible = objs.includes(o)); }
  function quiet() {
    jets.forEach(m => ring(m, 0, 0, 0, 0));
    allMats.forEach(m => { m.uniforms.uCut.value = 0; m.uniforms.uRing.value.set(0, 0, 0, 0); m.uniforms.uSel.value.set(0, 0, 0, 0); m.uniforms.uAlpha.value = 1; });
    [RSR, RS2R, MYR, SLAB.g].forEach(o => { o.position.set(0, 0, 0); o.quaternion.identity(); }); AIR.g.visible = false;
    [GA, GB].forEach(e => e.classList.remove('on')); billEl.innerHTML = ''; billEls = []; logKey = '';
  }
  /* put a car on a drop: x offset, along the ledge until u0, then the simulated fall */
  function drop(rig, car, S, x, t, mode) {
    const s = sampleAt(S, t), tilt = t > 0 ? Math.min(0.06, t * 0.1) : 0;
    rig.position.set(x + (t > 0 ? 0.9 + Math.min(1.4, t * 0.9) : lerp(-3.2, 0.9, cl((t + 2.2) / 2.2))), s.h, 0);   /* rolls to the edge, then drifts a little */
    rig.quaternion.setFromAxisAngle(ex.clone().set(0, 0, 1), -tilt * (s.landed ? 0 : 1));
    const f = s.landed ? 0 : s.f; ring(car.plumeMat, f, 0, 0, 0); noz(car.mat, f, 0, 0, 0);
    car.plumeMat.uniforms.uLen.value = lerp(0.8, 2.8, f); car.plumeMat.uniforms.uAlpha.value = 0.22;
    /* mist: on the ground under the car while jets reach it */
    car.mist.matrix.makeTranslation(rig.position.x, 0.02, rig.position.z); car.mist.matrixWorldNeedsUpdate = true;
    const reach = f > 0.05 && s.h < 4.5 ? f * (1 - s.h / 4.5) : 0; const after = s.landed ? Math.max(0, 1 - (t - S.T) / 3) : 0;
    ring(car.mistMat, Math.max(reach, after * 0.8), 0, 0, 0);
    return s;
  }

  /* ---------------- the show ---------------- */
  const crashT = (S) => S.empty ? S.T : null;
  const SC = [
    { key: 'car', name: '整车', dur: 9,
      setup() { show(RSR, GR.g); },
      update(u) { RSR.position.set(0, 0, 0); orbit(new THREE.Vector3(0, 0.6, 0), lerp(8.2, 6.6, sm(0, 9, u)), 0.9 + u * 0.09, 0.16); },
      calls: () => [
        { o: RS.g, p: [0.2, 1.1, 0], s: 'r', a: 1.4, b: 9, t: '特斯拉 Roadster · 第二代', en: 'ROADSTER', l: ['2026 年 10 月 15 日发布 · 计划 2028 年量产', '三个版本：Performance / Thrust / Rocket'] },
        { o: RS.g, p: [-2.2, 0.4, 0], s: 'l', a: 3.2, b: 9, hot: true, t: '0–97 km/h · 0.81 s', en: 'LAUNCH', l: ['发布会现场跑出', '靠车尾的冷气推进器'] } ] },
    { key: 'suction', name: '倒挂', dur: 13,
      setup() { show(RSR, SLAB.g); AIR.g.visible = true; },
      update(u) {
        const flip = sm(5.5, 8.5, u) * Math.PI;   /* car and slab turn over together: floor becomes ceiling */
        const q = new THREE.Quaternion().setFromAxisAngle(ex, flip); const lift = sm(5.5, 8.5, u) * 3.2;
        SLAB.g.quaternion.copy(q); SLAB.g.position.set(0, lift, 0); RSR.quaternion.copy(q); RSR.position.set(0, lift, 0);
        ring(AIR.underMat, sm(1.2, 2.2, u)); AIR.mat.uniforms.uAlpha.value = sm(2.6, 3.4, u) * (0.6 + 0.4 * Math.sin(u * 5));
        RS.mat.uniforms.uCut.value = 0.35 * sm(1, 2, u);
        orbit(new THREE.Vector3(0, lerp(0.6, 2.4, sm(5.5, 8.5, u)), 0), lerp(7.4, 9.2, sm(5, 9, u)), 1.25 + u * 0.03, lerp(0.08, -0.14, sm(5.5, 9, u))); },
      calls: () => [
        { o: RS.g, p: [0.5, 0.06, 0.6], s: 'l', a: 1.3, b: 5.3, t: '底盘风扇 + 密封裙边', en: 'FAN · SEAL', l: ['把车底的空气往外抽', '车底气压比外面低约 1/10 个大气压（估算）'] },
        { o: RS.g, p: [0, 2.2, 0], s: 'r', a: 2.8, b: 5.3, hot: true, t: '是“按”，不是“吸”', en: 'PRESSURE', l: ['外面的大气压把车按在路面上'] },
        { o: RS.g, p: [0, 0.6, 0], s: 'r', dy: 40, a: 8.6, b: 13, hot: true, t: '下压力读数 4,700 kg', en: 'INVERTED', l: ['发布会屏幕读数 · 车静止时也能倒挂', '真正难的是密封：漏一条缝，风扇就得拼命补'] } ] },
    { key: 'thrust', name: '10 个喷口', dur: 14,
      setup() { show(RSR, GR.g); },
      update(u) {
        const cut = sm(0.5, 1.6, u); RS.mat.uniforms.uCut.value = cut;
        const s0 = sm(2.0, 2.5, u) * (1 - sm(4.4, 4.8, u)), s1 = sm(4.6, 5.1, u) * (1 - sm(7.0, 7.4, u)), s2 = sm(7.2, 7.7, u) * (1 - sm(9.4, 9.8, u));
        RS.mat.uniforms.uSel.value.set(s0, s1, s2, s2);
        const fire = sm(10, 10.4, u); ring(RS.plumeMat, fire, fire * 0.6, 0, 0); noz(RS.mat, fire, fire * 0.6, 0, 0); RS.plumeMat.uniforms.uLen.value = 2.2;
        const hov = sm(10.2, 12.5, u) * 0.9; RSR.position.set(0, hov, 0);
        RS.mist.matrix.makeTranslation(0, 0.02, 0); RS.mist.matrixWorldNeedsUpdate = true; ring(RS.mistMat, fire); RS.mist.visible = true;
        orbit(new THREE.Vector3(-0.3, 0.6 + hov * 0.6, 0), lerp(8.2, 9.5, sm(9.5, 14, u)), 2.25 + u * 0.035, lerp(0.42, 0.12, sm(8, 11, u))); },
      calls: () => [
        { o: RS.g, p: [-0.35, 0.56, 0], s: 'r', a: 1.2, b: 9.8, t: '碳纤维缠绕高压气瓶 × 2', en: 'COPV', l: ['约 600 L · 塞满车里能用的空隙', '约 380 bar（5,500 psi）· 装约 250 kg 空气'] },
        { o: RS.g, p: [2.35, 0.13, 0.78], s: 'l', a: 2.0, b: 4.6, hot: true, t: '四角 · 朝下喷', en: '× 4', l: ['把车托起来，也管落地'] },
        { o: RS.g, p: [-2.2, 0.33, 0], s: 'l', a: 4.6, b: 7.2, hot: true, t: '车尾 · 朝后喷', en: '× 4', l: ['推力 >80 kN · 0.81 秒破 97 km/h 靠它'] },
        { o: RS.g, p: [1.45, 0.43, 1.0], s: 'r', a: 7.2, b: 9.8, hot: true, t: '前翼子板 · 朝侧面喷', en: '× 2', l: ['空中转身、横滚用'] },
        { o: RS.g, p: [0, -0.4, 0], s: 'r', dy: 30, a: 10.6, b: 14, t: '喷的是空气，不是火', en: 'COLD GAS', l: ['不烧任何燃料 · 电动阀门零点几秒内微调'] } ] },
    { key: 'nozzle', name: '喷管', dur: 13,
      setup() { show(NOZ.g); },
      update(u) {
        ring(NOZ.flowMat, sm(1.5, 2.2, u)); ring(NOZ.plumeMat, sm(2.6, 3.4, u)); ring(NOZ.frostMat, sm(6.5, 8, u));
        NOZ.mat.uniforms.uSel.value.set(0, 0, 0, 0);
        orbit(new THREE.Vector3(lerp(-1.5, 2.5, sm(0, 13, u)), 0, 0), lerp(10.5, 11.5, sm(0, 13, u)), 0.32 + u * 0.012, 0.22); },
      calls: () => [
        { o: NOZ.g, p: [-4.4, 1.1, 0], s: 'l', a: 0.8, b: 6, t: '高压空气', en: '380 bar', l: ['大约是轮胎气压的 150 倍'] },
        { o: NOZ.g, p: [-1.7, 0.32, 0], s: 'l', dy: 70, a: 1.5, b: 6, hot: true, t: '电动阀门', en: 'VALVE', l: ['开多大、开多久，电脑每秒调很多次'] },
        { o: NOZ.g, p: [0, 0.15, 0], s: 'r', dy: -90, a: 2.2, b: 6.4, t: '喉部：刚好音速', en: 'MACH 1', l: ['先收窄，气流在最窄处到音速'] },
        { o: NOZ.g, p: [2.6, 0.62, 0], s: 'r', dy: -40, a: 3.2, b: 6.4, t: '扩张段：超音速', en: 'SUPERSONIC', l: ['再张开，气流继续加速'] },
        { o: NOZ.g, p: [5.5, 0, 0], s: 'r', dy: 60, a: 4.2, b: 13, hot: true, t: '马赫环', en: 'SHOCK DIAMONDS', l: ['喷流里一节一节的亮带，是激波'] },
        { o: NOZ.g, p: [6.5, 0.9, 0], s: 'l', dy: -60, a: 7, b: 13, t: '白雾是冻出来的', en: 'FROST', l: ['空气猛地膨胀，温度暴跌', '水汽直接凝成雾'] } ] },
    { key: 'compare', name: '两种落法', dur: 15,
      setup() { show(RSR, RS2R, GR.g, LEDGE_A.g, LEDGE_B.g, RS.mist, RS2.mist); LEDGE_A.g.position.set(0, 0, 2.8); LEDGE_B.g.position.set(0, 0, -2.8); },
      update(u) {
        const t = u - 2.2;
        const a = drop(RSR, RS, SIM.rsGentle, 0, t, 'gentle'), b = drop(RS2R, RS2, SIM.rsBurn, 0, t, 'suicide');
        RSR.position.z = 2.8; RS2R.position.z = -2.8;
        RS.mist.matrix.makeTranslation(RSR.position.x, 0.02, 2.8); RS2.mist.matrix.makeTranslation(RS2R.position.x, 0.02, -2.8);
        /* gentle run out of gas: it hits the ground hard; shake the hologram for a beat */
        const ce = SIM.rsGentle.empty; if (ce && t > SIM.rsGentle.T) { const k = Math.max(0, 1 - (t - SIM.rsGentle.T) * 2); RSR.position.y += Math.sin(t * 60) * 0.04 * k; RS.mat.uniforms.uSel.value.set(0, 0, 0, 0); }
        const phA = t < 0 ? '准备' : ce && t >= ce.t ? (t >= SIM.rsGentle.T ? `没气了 · 以 ${(SIM.rsGentle.vLand * 3.6).toFixed(0)} km/h 砸地` : `没气了 · 离地 ${ce.h.toFixed(1)} m`) : '一离开就开喷，托着慢慢放';
        const phB = t < 0 ? '准备' : b.landed ? `落地 · 只用 ${SIM.rsBurn.used.toFixed(0)} kg` : t < SIM.rsBurn.fireT ? '自由落体 · 喷口全关' : '离地 ' + SIM.rsBurn.fireH.toFixed(1) + ' m · 全开';
        setGauge(gA, SIM.rsGentle, Math.max(0, t), phA, '需 ' + SIM.rsGentleFree.used.toFixed(0) + ' kg');
        setGauge(gB, SIM.rsBurn, Math.max(0, t), phB, '');
        aim(28, 6.8, 0, 0.8, 6.0, 0); },
      calls: () => [
        { o: LEDGE_A.g, p: [0, 10, 0], s: 'l', dx: 60, dy: -60, a: 0.2, b: 2.2, t: '同一辆车 · 同样 10 米', en: 'ROADSTER ≈ 2,350 kg', l: ['一罐约 250 kg 空气（估算车重）'] } ] },
    { key: 'modely', name: '我们的车', dur: 17,
      setup() { show(MYR, GR.g, LEDGE_A.g, MY.mist); LEDGE_A.g.position.set(0, 0, 0); },
      update(u) {
        const cut = sm(2.2, 3.0, u) * (1 - sm(6.8, 7.4, u)); MY.mat.uniforms.uCut.value = cut;
        const s0 = sm(3.0, 3.4, u) * (1 - sm(6.6, 7, u)), s1 = sm(4.0, 4.4, u) * (1 - sm(6.6, 7, u)), s2 = sm(5.0, 5.4, u) * (1 - sm(6.6, 7, u));
        MY.mat.uniforms.uSel.value.set(s0, s1, s2, s2);
        if (u < 7.4) { MYR.position.set(0, 0, 0); MYR.quaternion.identity(); LEDGE_A.g.visible = false; ring(MY.plumeMat, 0, 0, 0, 0); ring(MY.mistMat, 0);
          orbit(new THREE.Vector3(0, 0.85, 0), lerp(9, 8, sm(0, 7, u)), 0.8 + u * 0.08, 0.14); }
        else { LEDGE_A.g.visible = true; const t = u - 9.0, s = drop(MYR, MY, SIM.myBurn, 0, t, 'suicide');
          GA.classList.remove('on');
          const ph = t < 0 ? '准备' : s.landed ? `落地 · 用气 ${SIM.myBurn.used.toFixed(0)} kg` : t < SIM.myBurn.fireT ? `自由落体 · ${(Math.max(0, s.v) * 3.6).toFixed(0)} km/h` : `离地 ${SIM.myBurn.fireH.toFixed(1)} m · 全开刹车`;
          setGauge(gB, SIM.myBurn, Math.max(0, t), ph, 'MODEL Y');
          if (u < 7.5) cam.cut = true;
          { const ty = Math.max(1.0, s.h * 0.85 + 0.7); aim(6.4, ty + 1.6, 7.6, 0.9, ty, 0); } } },
      calls: () => [
        { o: MY.g, p: [0.2, 1.62, 0], s: 'r', a: 0.8, b: 7, t: 'Model Y · 长续航后驱', en: '1,891 kg', l: ['2026 款整备质量 · 4,797 × 1,920 × 1,624 mm'] },
        { o: MY.g, p: [-0.3, 0.63, 0], s: 'l', a: 2.4, b: 7, hot: true, t: '装上 Roadster 同款', en: 'KIT', l: ['10 个喷口 · 约 250 kg 空气', '加满后约 2,141 kg（没算气瓶自重）'] },
        { o: MY.g, p: [0, 0.2, 0], s: 'r', dy: 60, a: 15, b: 17, t: '自杀式点火 · 落地', en: 'TOUCHDOWN', l: ['自由落体 7.4 m → 离地 2.6 m 全开 → 0.44 s 刹停', '车里的人要扛约 3.8 g（估算）'] } ] },
    { key: 'bill', name: '现实账单', dur: 11,
      setup() { show(MYR, GR.g); billEls = billCards([
          { b: '≈ 250 kg', s: '空气 · 约占车重的一成' },
          { b: '380 bar', s: '气瓶压力 · 轮胎的 150 倍左右' },
          { b: '≈ 11 kg TNT', s: '气瓶一下子炸开时释放的能量', n: '估算 · CellCog 物理推算' },
          { b: '60–75 kWh', s: '充满一罐要的电 · 小压缩机要几个小时', n: '估算' },
          { b: '不允许上公路', s: '特斯拉对 Rocket 版的说明 · 发布会上飞的车里没坐人' } ]); },
      update(u) { MYR.position.set(0, 0, 0); MY.mat.uniforms.uCut.value = 0.5; orbit(new THREE.Vector3(-1.2, 0.8, 0), 9.5, 2.6 + u * 0.03, 0.14);
        billEls.forEach((el, j) => el.classList.toggle('on', u > 0.8 + j * 1.6)); },
      calls: () => [] }
  ];
  let acc = 0; SC.forEach(s => { s.at = acc; acc += s.dur; }); const TOTAL = acc;
  steps.innerHTML = SC.map(s => `<div>${s.name}<i></i></div>`).join('');
  const stepEls = [...steps.children];

  /* ---------------- state ---------------- */
  const ST = { on: false, t: 0, idx: -1, paused: false, vis: 0, ending: false };
  function enter(i) {
    ST.idx = i; quiet(); SC[i].setup(); makeCalls(SC[i].calls()); cam.cut = true; SC[i].update(0); cam.cut = true;
    topPh.textContent = '· ' + SC[i].name;
    stepEls.forEach((el, j) => { el.classList.toggle('now', j === i); el.classList.toggle('done', j < i); });
  }
  function play(from = 0) { ST.on = true; ST.paused = false; ST.ending = false; ST.t = from; root.visible = true; ov.classList.add('on'); yaw0 = null; enter(SC.findIndex(s => from < s.at + s.dur)); }
  function hide() { ST.ending = true; }
  function seek(key) { const i = typeof key === 'number' ? key : SC.findIndex(s => s.key === key || s.name === key); if (i < 0) return false; play(SC[i].at); return true; }

  const inv = new THREE.Matrix4();
  let yaw0 = null, panelT = 0;
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
    sc.update(u); if (ctx.cam) aim(...ctx.cam);
    let ap = i === 0 ? sm(0, 2.4, u) : sm(0, 0.7, u);
    if (i < SC.length - 1) ap *= 1 - sm(sc.dur - 0.35, sc.dur, u); else ap *= 1 - sm(sc.dur - 1.6, sc.dur, u);
    G.uAppear.value = (ctx.still ? 1 : ap) * Math.min(1, ST.vis * 1.4); G.uSpread.value = i === 0 ? 5 : 2.2;
    jets.forEach(m => m.uniforms.uAppear.value = ctx.still ? 1 : ap);
    const k = cam.cut || ctx.still ? 1 : 1 - Math.pow(0.02, dt); cam.cut = false;
    cam.pos.lerp(cam.gp, k); cam.tgt.lerp(cam.gt, k);
    const spin = ctx.spin ? ctx.spin() : 0; if (yaw0 == null) yaw0 = spin; const yaw = (spin - yaw0) * 0.8;
    const off = cam.pos.clone().sub(cam.tgt).applyAxisAngle(ey, yaw);
    vcam.position.copy(cam.tgt).add(off); vcam.up.set(0, 1, 0); vcam.lookAt(cam.tgt); vcam.updateMatrixWorld(); inv.copy(vcam.matrixWorld).invert();
    camera.updateMatrixWorld(); root.matrix.multiplyMatrices(camera.matrixWorld, inv); root.matrixWorldNeedsUpdate = true;
    root.updateMatrixWorld(true);
    stepEls.forEach((el, j) => { el.firstElementChild.style.width = (j < i ? 100 : j === i ? 100 * u / sc.dur : 0) + '%'; });
    placeCalls(u, w, h);
    panelT -= 1; if (panelT <= 0 || ctx.still) { panelT = 4; panels(sc, u, w, h); }
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
  return { frame, command, play, hide, seek, state: ST, sim: SIM, stages: SC.map(s => ({ key: s.key, name: s.name, at: s.at, dur: s.dur })), total: TOTAL,
    at(t) { if (!ST.on) play(t); if (Math.abs(t - ST.t) > 1e-6) { const i = SC.findIndex(s => t < s.at + s.dur); if (i !== ST.idx) enter(i); } ST.t = t; ST.paused = true; } };
}
