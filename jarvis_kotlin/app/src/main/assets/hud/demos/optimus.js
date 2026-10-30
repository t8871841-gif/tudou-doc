/* Optimus walking, as a particle hologram in the same family as roadster.js.
   Seven scenes: bot, car_vs_bot, pendulum, zmp, capture, stack, hard.
   Units: 1 = 1 m. The root carries mainCamera * inverse(vcam), like roadster.js.
   Number policy: 报道 = a published / reported figure. 估算 = this teaching model.
   示意 = an animation, not a measurement. 未公开 = Tesla has not stably published it.
   The slow walk is a kinematic illustration timed to the reported 0.6 m/s.
   Pendulum and capture integrate the linear inverted pendulum; they are not a torque-closed robot. */
export function install(ctx) {
  const { THREE, scene, camera } = ctx;
  const pixel = ctx.pixel || Math.min(devicePixelRatio || 1, 2);
  const root = new THREE.Group(); root.matrixAutoUpdate = false; root.visible = false; scene.add(root);
  if (camera.far < 400) { camera.far = 400; camera.updateProjectionMatrix(); }

  const G = { uTime: { value: 0 }, uPixel: { value: pixel }, uAppear: { value: 0 }, uPt: { value: 14 }, uSpread: { value: 4 } };
  const C = {
    holo: [0.42, 0.82, 1.0], dim: [0.2, 0.46, 0.66], bright: [0.78, 0.96, 1.0], glass: [0.16, 0.36, 0.55],
    hot: [1.0, 0.62, 0.28], red: [1.0, 0.32, 0.26], ground: [0.16, 0.42, 0.6]
  };
  const STRUCT_V = `
    uniform float uTime,uPixel,uAppear,uPt,uSpread,uAlpha;
    attribute vec3 aCol; attribute float aSize; attribute float aSeed; attribute float aKind;
    varying vec3 vC; varying float vA;
    vec3 h3(float s){ return fract(sin(vec3(s*127.1,s*311.7,s*74.7))*43758.5453); }
    void main(){
      vec3 p = position; float vis = 1.0; vec3 col = aCol;
      if (aKind > 3.5 && aKind < 4.5) col = mix(col, vec3(1.0,0.32,0.25), 0.45 + 0.55*sin(uTime*5.0));
      float d = clamp(uAppear*1.6 - aSeed*0.6, 0.0, 1.0); d = d*d*(3.0 - 2.0*d);
      p = mix(p + (h3(aSeed)*2.0 - 1.0)*uSpread, p, d);
      vec4 mv = modelViewMatrix * vec4(p, 1.0);
      gl_Position = projectionMatrix * mv;
      float tw = 0.82 + 0.18*sin(uTime*2.1 + aSeed*61.0);
      vA = vis * uAlpha * d * tw; vC = col;
      float sz = aSize * uPt * uPixel / max(0.1, -mv.z);
      if (sz < 1.4*uPixel) { vA *= sz / (1.4*uPixel); sz = 1.4*uPixel; }
      gl_PointSize = min(sz, 7.0*uPixel);
    }`;
  const DOT_F = `varying vec3 vC; varying float vA;
    void main(){ vec2 q = gl_PointCoord - 0.5; float r = dot(q,q)*4.0; if (r > 1.0) discard; float a = 1.0 - r; gl_FragColor = vec4(vC, vA*a*a); }`;
  function structMat(alpha = 1) {
    const u = { ...G, uAlpha: { value: alpha } };
    return new THREE.ShaderMaterial({ uniforms: u, vertexShader: STRUCT_V, fragmentShader: DOT_F, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending });
  }

  class Pts {
    constructor() { this.p = []; this.c = []; this.s = []; this.k = []; this.e = []; }
    add(x, y, z, c, s = 1, k = 0) { this.p.push(x, y, z); this.c.push(c[0], c[1], c[2]); this.s.push(s); this.k.push(k); this.e.push(Math.random()); }
    line(a, b, step, c, s, k) {
      const d = Math.hypot(b[0] - a[0], b[1] - a[1], b[2] - a[2]); const n = Math.max(1, Math.ceil(d / step));
      for (let i = 0; i <= n; i++) { const t = i / n; this.add(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, c, s, k); }
    }
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
  function livePts(maxN) {
    const g = new THREE.BufferGeometry();
    const pos = new THREE.BufferAttribute(new Float32Array(maxN * 3), 3); pos.setUsage(THREE.DynamicDrawUsage);
    const col = new THREE.BufferAttribute(new Float32Array(maxN * 3), 3); col.setUsage(THREE.DynamicDrawUsage);
    const siz = new Float32Array(maxN), kind = new Float32Array(maxN), seed = new Float32Array(maxN);
    for (let i = 0; i < maxN; i++) { siz[i] = 1; kind[i] = 5; seed[i] = Math.random(); }
    g.setAttribute('position', pos); g.setAttribute('aCol', col);
    g.setAttribute('aSize', new THREE.BufferAttribute(siz, 1));
    g.setAttribute('aKind', new THREE.BufferAttribute(kind, 1));
    g.setAttribute('aSeed', new THREE.BufferAttribute(seed, 1));
    g.setDrawRange(0, 0);
    const mat = structMat(); const mesh = new THREE.Points(g, mat); mesh.frustumCulled = false; mesh.visible = false;
    return { mesh, mat, set(pts, color) {
      const n = Math.min(pts.length, maxN);
      for (let i = 0; i < n; i++) { pos.setXYZ(i, pts[i][0], pts[i][1], pts[i][2]); col.setXYZ(i, color[0], color[1], color[2]); }
      g.setDrawRange(0, n); pos.needsUpdate = col.needsUpdate = true; mesh.visible = n > 1;
    } };
  }

  const clamp = (x, a, b) => Math.max(a, Math.min(b, x));
  const lerp = (a, b, t) => a + (b - a) * t;
  const smooth = (a, b, x) => { const t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); };
  const TAU = Math.PI * 2;
  const g0 = 9.81, ZC = 0.95, OMEGA = Math.sqrt(g0 / ZC);
  const V_WALK = 0.6, STEP_T = 0.5, STEP_L = V_WALK * STEP_T;
  const FH = 0.13, FW = 0.055, ANK = -0.04, AY = 0.08, HIP_Y = 0.84, L1 = 0.43, L2 = 0.43, HIP_Z = 0.09;
  const MPH5 = 5 * 1609.344 / 3600;
  const ankleX = (cx) => cx + ANK;

  function lip(x0, v0, p, t) {
    const wt = OMEGA * t, c = Math.cosh(wt), s = Math.sinh(wt);
    return { x: p + (x0 - p) * c + (v0 / OMEGA) * s, v: (x0 - p) * OMEGA * s + v0 * c };
  }
  function exitTime(x0, v0, p, limit) {
    if (lip(x0, v0, p, 0).x >= limit) return 0;
    let lo = 0, hi = 3;
    if (lip(x0, v0, p, hi).x < limit) return hi;
    for (let i = 0; i < 28; i++) { const m = (lo + hi) / 2; if (lip(x0, v0, p, m).x < limit) lo = m; else hi = m; }
    return hi;
  }
  function solve2(dx, dy) {
    const d0 = Math.hypot(dx, dy) || 1e-4;
    const dist = Math.min(L1 + L2 - 0.002, Math.max(Math.abs(L1 - L2) + 0.002, d0));
    const flexion = Math.PI - Math.acos(clamp((L1 * L1 + L2 * L2 - dist * dist) / (2 * L1 * L2), -1, 1));
    const alpha = Math.acos(clamp((L1 * L1 + dist * dist - L2 * L2) / (2 * L1 * dist), -1, 1));
    const hipPitch = Math.atan2(dx, -dy) + alpha;
    return { hipPitch, flexion, anklePitch: -(hipPitch - flexion) };
  }
  function hull(points) {
    const p = points.slice().sort((a, b) => a[0] - b[0] || a[1] - b[1]);
    if (p.length < 3) return p;
    const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    const lower = []; for (const pt of p) { while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], pt) <= 0) lower.pop(); lower.push(pt); }
    const upper = []; for (let i = p.length - 1; i >= 0; i--) { const pt = p[i]; while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], pt) <= 0) upper.pop(); upper.push(pt); }
    upper.pop(); lower.pop(); return lower.concat(upper);
  }
  function shoelace(h) { let a = 0; for (let i = 0; i < h.length; i++) { const c = h[i], d = h[(i + 1) % h.length]; a += c[0] * d[1] - d[0] * c[1]; } return Math.abs(a) / 2; }
  function insidePoly(poly, x, z) {
    let c = false;
    for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
      const xi = poly[i][0], zi = poly[i][1], xj = poly[j][0], zj = poly[j][1];
      if ((zi > z) !== (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) c = !c;
    }
    return c;
  }
  function footCorners(cx, cz) { return [[cx - FH, cz - FW], [cx + FH, cz - FW], [cx + FH, cz + FW], [cx - FH, cz + FW]]; }
  function support(pose) {
    const pts = []; pose.feet.forEach(f => { if (f.planted) footCorners(f.cx, f.cz).forEach(p => pts.push(p)); });
    const h = hull(pts);
    return { h, area: shoelace(h), inside: h.length >= 3 && insidePoly(h, pose.com.x, pose.com.z) };
  }
  function polyPts(h, y = 0.025) {
    if (!h || h.length < 3) return [];
    const pts = [];
    for (let i = 0; i < h.length; i++) {
      const a = h[i], b = h[(i + 1) % h.length], d = Math.hypot(b[0] - a[0], b[1] - a[1]), n = Math.max(2, Math.ceil(d / 0.035));
      for (let k = 0; k < n; k++) { const t = k / n; pts.push([a[0] + (b[0] - a[0]) * t, y, a[1] + (b[1] - a[1]) * t]); }
    }
    let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
    h.forEach(p => { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minZ = Math.min(minZ, p[1]); maxZ = Math.max(maxZ, p[1]); });
    const step = Math.max(0.045, Math.min(0.16, (maxX - minX) / 22));
    for (let x = minX; x <= maxX; x += step) for (let z = minZ; z <= maxZ; z += step) if (insidePoly(h, x, z)) pts.push([x, y * 0.6, z]);
    return pts;
  }
  function column(x, y0, y1, z) { const pts = []; const n = 18; for (let i = 0; i <= n; i++) pts.push([x, y0 + (y1 - y0) * i / n, z]); return pts; }
  function spun(pivot, ang, x, y, z) {
    if (!ang) return [x, y, z];
    const th = -ang, c = Math.cos(th), s = Math.sin(th), dx = x - pivot.x, dy = y - pivot.y;
    return [pivot.x + dx * c - dy * s, pivot.y + dx * s + dy * c, z];
  }

  function capsule(P, len, r0, r1) {
    const rings = Math.max(4, Math.round(len / 0.032));
    for (let i = 0; i <= rings; i++) {
      const t = i / rings, y = -len * t, r = r0 + (r1 - r0) * t, n = 11, edge = i % 4 === 0;
      for (let j = 0; j < n; j++) { const a = TAU * j / n; P.add(Math.cos(a) * r, y, Math.sin(a) * r, edge ? C.bright : C.holo, edge ? 0.95 : 0.72, edge ? 5 : 0); }
    }
  }
  function buildSole(mat) {
    const ss = [], zz = [], pos = [], col = [], siz = [], kind = [], seed = [];
    for (let i = 0; i <= 16; i++) for (let j = 0; j <= 8; j++) {
      const s = -FH + 2 * FH * i / 16, z = -FW + 2 * FW * j / 8;
      ss.push(s); zz.push(z); pos.push(s - ANK, -AY, z); col.push(C.dim[0], C.dim[1], C.dim[2]); siz.push(0.85); kind.push(0); seed.push(Math.random());
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    const ca = new THREE.Float32BufferAttribute(col, 3); ca.setUsage(THREE.DynamicDrawUsage);
    g.setAttribute('aCol', ca);
    g.setAttribute('aSize', new THREE.Float32BufferAttribute(siz, 1));
    g.setAttribute('aKind', new THREE.Float32BufferAttribute(kind, 1));
    g.setAttribute('aSeed', new THREE.Float32BufferAttribute(seed, 1));
    const mesh = new THREE.Points(g, mat); mesh.frustumCulled = false;
    return { mesh, s: ss, z: zz, col: ca };
  }
  function paintSole(sole, zmpS, tripped) {
    const c = sole.col.array;
    for (let i = 0; i < sole.s.length; i++) {
      const ds = sole.s[i] - zmpS, dz = sole.z[i], hot = Math.exp(-(ds * ds + dz * dz) / 0.0018), out = Math.abs(zmpS) > FH || tripped;
      c[i * 3] = out ? 1 : 0.22 + hot * 0.78;
      c[i * 3 + 1] = out ? 0.28 : 0.52 + hot * 0.28;
      c[i * 3 + 2] = out ? 0.22 : 0.82 - hot * 0.4;
    }
    sole.col.needsUpdate = true;
  }
  function coolSole(sole) {
    const c = sole.col.array;
    for (let i = 0; i < sole.s.length; i++) { c[i * 3] = C.dim[0]; c[i * 3 + 1] = C.dim[1]; c[i * 3 + 2] = C.dim[2]; }
    sole.col.needsUpdate = true;
  }
  function addFootShell(P) {
    for (let s = -FH; s <= FH; s += 0.018) { P.add(s - ANK, -AY, -FW, C.bright, 1.05, 5); P.add(s - ANK, -AY, FW, C.bright, 1.05, 5); }
    for (let z = -FW; z <= FW; z += 0.016) { P.add(-FH - ANK, -AY, z, C.bright, 1.05, 5); P.add(FH - ANK, -AY, z, C.bright, 1.05, 5); }
    for (let s = -FH; s <= FH; s += 0.04) P.add(s - ANK, -AY + 0.03, 0, C.holo, 0.7, 0);
  }
  function buildLeg(mat, side) {
    const hip = new THREE.Group(); hip.position.z = side * HIP_Z;
    const up = new Pts(); capsule(up, L1, 0.052, 0.04); hip.add(up.mesh(mat));
    const knee = new THREE.Group(); knee.position.y = -L1; hip.add(knee);
    const lo = new Pts(); capsule(lo, L2, 0.04, 0.03); addFootShell(lo);
    for (let j = 0; j < 14; j++) { const a = TAU * j / 14; lo.add(Math.cos(a) * 0.045, 0, Math.sin(a) * 0.045, C.bright, 1, 5); }
    knee.add(lo.mesh(mat));
    const ankle = new THREE.Group(); ankle.position.y = -L2; knee.add(ankle);
    const sole = buildSole(mat); ankle.add(sole.mesh);
    return { hip, knee, ankle, sole, hz: side * HIP_Z };
  }
  function buildArm(mat, side) {
    const shoulder = new THREE.Group(); shoulder.position.set(0, 0.44, side * 0.2);
    const up = new Pts(); capsule(up, 0.26, 0.034, 0.028); shoulder.add(up.mesh(mat));
    const elbow = new THREE.Group(); elbow.position.y = -0.26; shoulder.add(elbow);
    const fore = new Pts(); capsule(fore, 0.24, 0.028, 0.02);
    for (let x = -0.015; x <= 0.04; x += 0.014) for (let z = -0.02; z <= 0.02; z += 0.014) fore.add(x, -0.27, z, C.bright, 0.75, 5);
    elbow.add(fore.mesh(mat));
    return { shoulder, elbow };
  }
  function buildBot() {
    const mat = structMat();
    const spin = new THREE.Group(), inner = new THREE.Group(); spin.add(inner);
    const pelvis = new THREE.Group(); inner.add(pelvis);
    const pel = new Pts();
    for (let j = 0; j < 18; j++) { const a = TAU * j / 18; pel.add(Math.cos(a) * 0.09, 0, Math.sin(a) * 0.055, C.bright, 0.9, 5); }
    pelvis.add(pel.mesh(mat));
    const torso = new THREE.Group(); pelvis.add(torso);
    const T = new Pts();
    for (let i = 0; i <= 12; i++) {
      const t = i / 12, y = t * 0.5, rx = 0.1 + 0.04 * Math.sin(t * Math.PI), rz = 0.07 + 0.018 * Math.sin(t * Math.PI), edge = i % 3 === 0;
      for (let j = 0; j < 14; j++) { const a = TAU * j / 14; T.add(Math.cos(a) * rx, y, Math.sin(a) * rz, edge ? C.bright : C.holo, edge ? 0.95 : 0.72, edge ? 5 : 0); }
    }
    for (let y = 0.24; y <= 0.4; y += 0.028) for (let z = -0.055; z <= 0.055; z += 0.028) T.add(0.125, y, z, C.bright, 0.7, 5);
    torso.add(T.mesh(mat));
    const head = new THREE.Group(); head.position.y = 0.64; torso.add(head);
    const H = new Pts(), r = 0.1;
    for (let i = 0; i < 90; i++) {
      const a = Math.random() * TAU, u = Math.random() * 2 - 1, rr = Math.sqrt(Math.max(0, 1 - u * u)) * r;
      const x = Math.cos(a) * rr, y = u * r * 1.08, z = Math.sin(a) * rr * 0.9;
      const visor = x > r * 0.45 && Math.abs(y + 0.005) < 0.012;
      H.add(x, y, z, visor ? C.bright : C.holo, visor ? 1.35 : 0.75, visor ? 5 : 0);
    }
    for (let j = 0; j < 22; j++) { const a = TAU * j / 22; H.add(Math.cos(a) * r * 0.9, -0.02, Math.sin(a) * r * 0.78, C.bright, 1, 5); }
    head.add(H.mesh(mat));
    const legL = buildLeg(mat, 1), legR = buildLeg(mat, -1);
    pelvis.add(legL.hip, legR.hip);
    const armL = buildArm(mat, 1), armR = buildArm(mat, -1);
    torso.add(armL.shoulder, armR.shoulder);
    return { spin, inner, pelvis, torso, legL, legR, armL, armR, mat, soles: [legL.sole, legR.sole] };
  }
  function applyLeg(leg, hip, ankle) {
    const dx = ankle.x - hip.x, dy = ankle.y - hip.y, dz = ankle.z - (hip.z + leg.hz);
    const s = solve2(dx, dy);
    const roll = Math.atan2(dz, Math.max(0.25, Math.hypot(dx, dy)));
    leg.hip.rotation.set(roll, 0, s.hipPitch);
    leg.knee.rotation.set(0, 0, -s.flexion);
    leg.ankle.rotation.set(0, 0, s.anklePitch);
  }
  function poseRig(rig, pose, tip) {
    const ang = tip && tip.ang ? tip.ang : 0;
    const pv = (tip && tip.pivot) || { x: 0, y: 0, z: 0 };
    rig.spin.position.set(pv.x, pv.y, pv.z);
    rig.spin.rotation.set(0, 0, -ang);
    rig.inner.position.set(-pv.x, -pv.y, -pv.z);
    rig.pelvis.position.set(pose.hip.x, pose.hip.y, pose.hip.z);
    rig.pelvis.rotation.set(0, 0, 0);
    rig.torso.rotation.set(0, 0, pose.lean || 0);
    applyLeg(rig.legL, pose.hip, pose.ankles.L);
    applyLeg(rig.legR, pose.hip, pose.ankles.R);
    rig.armL.shoulder.rotation.set(-0.18, 0, pose.arms.L);
    rig.armR.shoulder.rotation.set(0.18, 0, pose.arms.R);
    rig.armL.elbow.rotation.set(0, 0, -0.4);
    rig.armR.elbow.rotation.set(0, 0, -0.4);
  }
  function gait(t, anchor) {
    const n = Math.floor(Math.max(0, t) / STEP_T), u = t / STEP_T - n, leftStance = n % 2 === 0;
    const px = anchor + V_WALK * t, py = HIP_Y + Math.sin(u * Math.PI) * 0.012;
    const pz = lerp(leftStance ? -HIP_Z : HIP_Z, leftStance ? HIP_Z : -HIP_Z, smooth(0, 0.18, u));
    const stanceC = anchor + (n + 0.5) * STEP_L;
    const swingC = lerp(anchor + (n - 0.5) * STEP_L, anchor + (n + 1.5) * STEP_L, smooth(0.16, 0.84, u));
    const lift = (u > 0.16 && u < 0.86) ? Math.sin(Math.PI * clamp((u - 0.16) / 0.7, 0, 1)) * 0.075 : 0;
    const plantedSwing = lift < 0.008;
    const L = leftStance
      ? { x: ankleX(stanceC), y: AY, z: HIP_Z, c: stanceC, planted: true }
      : { x: ankleX(swingC), y: AY + lift, z: HIP_Z, c: swingC, planted: plantedSwing };
    const R = leftStance
      ? { x: ankleX(swingC), y: AY + lift, z: -HIP_Z, c: swingC, planted: plantedSwing }
      : { x: ankleX(stanceC), y: AY, z: -HIP_Z, c: stanceC, planted: true };
    const sw = Math.sin(Math.PI * clamp((u - 0.08) / 0.84, 0, 1)) * 0.48;
    return {
      hip: { x: px, y: py, z: pz }, com: { x: px, y: ZC, z: pz },
      ankles: { L: { x: L.x, y: L.y, z: L.z }, R: { x: R.x, y: R.y, z: R.z } },
      feet: [{ cx: L.c, cz: L.z, planted: L.planted }, { cx: R.c, cz: R.z, planted: R.planted }],
      arms: { L: leftStance ? sw : -sw, R: leftStance ? -sw : sw }, lean: 0, leftStance, u
    };
  }
  function pinX(N, x0) {
    const dx = x0 - N.hip.x;
    N.hip.x += dx; N.com.x += dx; N.ankles.L.x += dx; N.ankles.R.x += dx;
    N.feet.forEach(f => { f.cx += dx; });
    return N;
  }
  function leanOf(N) {
    if (N.sup.inside) return 0;
    const planted = N.feet.filter(f => f.planted);
    if (!planted.length) return 0;
    return -clamp(Math.max(0, N.com.x - Math.max(...planted.map(f => f.cx + FH))) * 3.2, 0, 0.42);
  }

  function buildCar() {
    const g = new THREE.Group(), P = new Pts(), mat = structMat();
    const L = 4.2, W = 1.8;
    for (let x = -L / 2; x <= L / 2; x += 0.14) {
      const tn = (x + L / 2) / L, roof = 0.55 + 0.72 * Math.sin(clamp(tn, 0, 1) * Math.PI) * (tn < 0.82 ? 1 : 0.55);
      const y0 = 0.38, y1 = y0 + roof, z1 = (W / 2) * (1 - 0.18 * Math.pow(Math.abs(x) / (L / 2), 2));
      [[y0, -z1], [y0, z1], [y1, -z1 * 0.72], [y1, z1 * 0.72]].forEach(() => {});
      P.line([x, y0, -z1], [x, y0, z1], 0.08, C.dim, 0.65, 0);
      P.line([x, y1, -z1 * 0.72], [x, y1, z1 * 0.72], 0.08, C.holo, 0.7, 0);
      P.line([x, y0, -z1], [x, y1, -z1 * 0.72], 0.07, C.holo, 0.75, 0);
      P.line([x, y0, z1], [x, y1, z1 * 0.72], 0.07, C.holo, 0.75, 0);
      const glass = tn > 0.28 && tn < 0.7 && roof > 0.7;
      if (glass) { P.add(x, y1 - 0.02, 0, C.glass, 0.8, 0); P.add(x, (y0 + y1) / 2, z1 * 0.96, C.glass, 0.7, 0); P.add(x, (y0 + y1) / 2, -z1 * 0.96, C.glass, 0.7, 0); }
    }
    [[-L / 2, 0.42, -W / 2], [-L / 2, 0.42, W / 2], [L / 2, 0.48, -W / 2], [L / 2, 0.48, W / 2]].forEach((a, i) => {
      const b = i < 2 ? [L / 2, a[1], a[2]] : [a[0], 1.15, 0];
      if (i < 2) P.line(a, [L / 2, a[1], a[2]], 0.1, C.bright, 0.85, 5);
    });
    P.line([-L / 2, 1.05, 0], [L / 2 * 0.2, 1.35, 0], 0.08, C.bright, 0.8, 5);
    P.line([L / 2 * 0.2, 1.35, 0], [L / 2, 0.85, 0], 0.08, C.bright, 0.8, 5);
    const wb = 2.8, tr = 1.55, wr = 0.34;
    [[wb / 2, tr / 2], [wb / 2, -tr / 2], [-wb / 2, tr / 2], [-wb / 2, -tr / 2]].forEach(([x, z]) => {
      for (let k = 0; k < 2; k++) for (let j = 0; j < 28; j++) {
        const a = TAU * j / 28; P.add(x + Math.cos(a) * wr, wr + Math.sin(a) * wr, z + (k ? 0.07 : 0), k ? C.holo : C.bright, 0.85, 5);
      }
    });
    for (let z = -0.55; z <= 0.55; z += 0.04) { P.add(L / 2 - 0.05, 0.62, z, C.bright, 1, 5); P.add(-L / 2 + 0.05, 0.7, z, C.red, 1, 4); }
    g.add(P.mesh(mat));
    return { g, mat, wb, tr };
  }
  function buildGround() {
    const g = new THREE.Group(), P = new Pts(), mat = structMat(), R = 14;
    for (let x = -R; x <= R; x += 0.5) for (let z = -R; z <= R; z += 0.5) {
      if (Math.hypot(x, z) > R) continue;
      const f = Math.max(0.15, 1 - Math.hypot(x, z) / R);
      P.add(x, 0, z, [C.ground[0] * f, C.ground[1] * f, C.ground[2] * f], 0.8, 0);
    }
    for (let i = -8; i <= 8; i += 2) P.line([-R * 0.7, 0.004, i], [R * 0.7, 0.004, i], 0.18, C.dim, 0.45, 0);
    g.add(P.mesh(mat)); return { g, mat };
  }
  function beacon(col, r, flat) {
    const P = new Pts();
    if (flat) {
      for (let j = 0; j < 28; j++) { const a = TAU * j / 28; P.add(Math.cos(a) * r, 0.03, Math.sin(a) * r, col, 1.25, 5); }
      P.add(0, 0.04, 0, col, 1.6, 5);
    } else {
      for (let i = 0; i < 42; i++) {
        const a = Math.random() * TAU, b = Math.acos(2 * Math.random() - 1);
        P.add(Math.sin(b) * Math.cos(a) * r, Math.sin(b) * Math.sin(a) * r, Math.cos(b) * r, col, 1.3, 5);
      }
    }
    const mat = structMat(), mesh = P.mesh(mat); mesh.visible = false;
    return { mesh, mat };
  }
  function buildSlab(w, d) {
    const P = new Pts(), mat = structMat(0.45);
    for (let x = -w / 2; x <= w / 2; x += 0.07) for (let z = -d / 2; z <= d / 2; z += 0.07) {
      const edge = Math.abs(Math.abs(x) - w / 2) < 0.08 || Math.abs(Math.abs(z) - d / 2) < 0.08;
      P.add(x, 0, z, edge ? C.bright : C.holo, edge ? 1 : 0.65, edge ? 5 : 0);
    }
    const g = new THREE.Group(); g.add(P.mesh(mat)); return { g, mat };
  }

  const BOT = buildBot(), BOTL = buildBot(), BOTR = buildBot();
  const CAR = buildCar(), GR = buildGround();
  const COM = beacon(C.bright, 0.045, false), PROJ = beacon(C.holo, 0.07, true), CAP = beacon(C.hot, 0.08, true);
  const POLY = livePts(900), POLY2 = livePts(900), DROP = livePts(40);
  const SLAB = [1.62, 1.06, 0.5].map(y => { const s = buildSlab(1.35, 0.42); s.g.position.set(-0.85, y, 0); return s; });
  const STACK = new THREE.Group(); SLAB.forEach(s => STACK.add(s.g));
  const DOT = beacon(C.hot, 0.04, false);
  const ACTORS = [BOT.spin, BOTL.spin, BOTR.spin, CAR.g, GR.g, STACK];
  [BOT.spin, BOTL.spin, BOTR.spin, CAR.g, GR.g, STACK, COM.mesh, PROJ.mesh, CAP.mesh, DOT.mesh, POLY.mesh, POLY2.mesh, DROP.mesh].forEach(o => root.add(o));
  const SOLES = [...BOT.soles, ...BOTL.soles, ...BOTR.soles];
  function show(...list) { const on = new Set(list); ACTORS.forEach(o => { o.visible = on.has(o); }); }

  function gaitPose(t, anchor, pin) {
    const N = gait(t, anchor);
    if (pin != null) pinX(N, pin);
    N.sup = support(N);
    N.lean = leanOf(N);
    return N;
  }
  function placeMarkers(x, z, tip, pivot, showCap, xi) {
    const c = spun(pivot, tip, x, ZC, z);
    COM.mesh.visible = true; COM.mesh.position.set(c[0], c[1], c[2]);
    PROJ.mesh.visible = true; PROJ.mesh.position.set(c[0], 0.03, c[2]);
    DROP.set(column(c[0], 0.04, c[1], c[2]), C.dim);
    if (showCap) { CAP.mesh.visible = true; CAP.mesh.position.set(xi, 0.04, z); } else CAP.mesh.visible = false;
    return c;
  }

  /* ---------------- virtual camera ---------------- */
  const vcam = new THREE.PerspectiveCamera(38, 1, 0.1, 400);
  const cam = { pos: new THREE.Vector3(4, 2, 4), tgt: new THREE.Vector3(0, 0.8, 0), gp: new THREE.Vector3(4, 2, 4), gt: new THREE.Vector3(0, 0.8, 0), cut: true };
  function aim(px, py, pz, tx, ty, tz) { cam.gp.set(px, py, pz); cam.gt.set(tx, ty, tz); }
  function orbit(t, dist, yaw, pitch) { aim(t.x + Math.sin(yaw) * Math.cos(pitch) * dist, t.y + Math.sin(pitch) * dist, t.z + Math.cos(yaw) * Math.cos(pitch) * dist, t.x, t.y, t.z); }

  /* ---------------- overlay ---------------- */
  const css = document.createElement('style');
  css.textContent = `
  #op{position:fixed;inset:0;pointer-events:none;z-index:6;opacity:0;transition:opacity .6s;font-size:12px;color:#BFEFFF;font-family:var(--f-cn,'PingFang SC','Noto Sans CJK SC',sans-serif)}
  #op.on{opacity:1}
  #op svg{position:absolute;inset:0;width:100%;height:100%;overflow:visible}
  #op path{fill:none;stroke:rgba(127,219,255,.55);stroke-width:1} #op circle{fill:#73D9FF}
  #op path.hot{stroke:rgba(255,170,100,.75)} #op circle.hot{fill:#FFB27A}
  .op-top{position:absolute;left:50%;top:76px;transform:translateX(-50%);display:flex;align-items:center;gap:10px;padding:5px 14px;border:1px solid rgba(127,219,255,.3);border-radius:20px;background:rgba(4,12,18,.72);white-space:nowrap;pointer-events:auto;cursor:pointer}
  .op-top b{color:#E4F8FF;font-weight:600;letter-spacing:.5px}.op-top i{font-style:normal;color:#FFB27A}.op-top em{font-style:normal;color:#9FDDF5;margin-left:6px}
  .op-steps{position:absolute;left:50%;top:112px;transform:translateX(-50%);display:flex;gap:3px;width:min(760px,82vw)}
  .op-steps div{flex:1;position:relative;padding-top:7px;color:rgba(159,221,245,.45);font-size:10.5px;text-align:center;white-space:nowrap;overflow:hidden}
  .op-steps div::before{content:"";position:absolute;left:0;right:0;top:0;height:2px;background:rgba(127,219,255,.18)}
  .op-steps div i{position:absolute;left:0;top:0;height:2px;background:#73D9FF;box-shadow:0 0 6px #73D9FF;width:0}
  .op-steps div.now{color:#E4F8FF}.op-steps div.done{color:rgba(159,221,245,.75)}
  .op-call{position:absolute;max-width:280px;padding:8px 12px 9px;border:1px solid rgba(127,219,255,.45);border-radius:6px;background:rgba(4,14,22,.76);opacity:0;transition:opacity .45s;line-height:1.45}
  .op-call.on{opacity:1}
  .op-call b{display:block;color:#E4F8FF;font-size:15px;font-weight:600;margin-bottom:2px}
  .op-call b small{font-family:var(--f-latin,inherit);font-weight:400;font-size:10.5px;color:#73D9FF;letter-spacing:1px;margin-left:6px}
  .op-call span{display:block;color:#9FDDF5;font-size:12.5px}
  .op-call.hot{border-color:rgba(255,170,100,.6);background:rgba(22,12,4,.74)}.op-call.hot b small{color:#FFB27A}
  .op-card{position:absolute;width:var(--pw,260px);box-sizing:border-box;padding:10px 12px 11px;background:linear-gradient(160deg,rgba(6,22,34,.82),rgba(3,10,16,.76));border:1px solid rgba(115,217,255,.25);opacity:0;transition:opacity .5s;
    clip-path:polygon(0 0,calc(100% - 12px) 0,100% 12px,100% 100%,12px 100%,0 calc(100% - 12px))}
  .op-card.on{opacity:1}
  .op-card h6{margin:0 0 7px;font:600 11px/1 var(--f-latin,monospace);letter-spacing:1.6px;color:#73D9FF;display:flex;justify-content:space-between;gap:8px}
  .op-card h6 span:last-child{color:#FFB27A;letter-spacing:.4px;font-family:inherit}
  .op-big{font:300 28px/1.05 var(--f-latin,monospace);color:#E8FAFF;font-variant-numeric:tabular-nums;text-shadow:0 0 14px rgba(115,217,255,.45)}
  .op-big em{font-style:normal;font-size:13px;color:rgba(159,221,245,.7);margin-left:4px}
  .op-ph{margin:5px 0 8px;font-size:12.5px;color:#FFB27A;line-height:1.4;min-height:16px}
  .op-rows{display:grid;grid-template-columns:1fr 1fr;gap:6px 10px}
  .op-rows div{border-left:2px solid rgba(115,217,255,.35);padding-left:7px;min-width:0}
  .op-rows small{display:block;font-size:10px;color:rgba(159,221,245,.62);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .op-rows b{display:block;font:500 14px/1.3 var(--f-latin,monospace);color:#E4F8FF;font-variant-numeric:tabular-nums}
  .op-rows b em{font-style:normal;font-size:10px;color:rgba(159,221,245,.6);margin-left:3px}
  .op-rows i{display:block;font-style:normal;font-size:10px}
  .op-rows i.rep{color:#73D9FF}.op-rows i.est{color:rgba(159,221,245,.75)}.op-rows i.demo{color:rgba(255,210,160,.8)}.op-rows i.goal{color:#FFB27A}.op-rows i.no{color:#FF8A7A}
  .op-card canvas{display:block;width:100%;height:auto;margin-top:4px}
  #opR .note{margin-top:6px;font-size:10.5px;color:rgba(159,221,245,.55);line-height:1.4}
  .op-bill{position:absolute;display:flex;flex-direction:column;gap:8px;width:var(--pw,280px)}
  .op-bill div{padding:10px 12px;border:1px solid rgba(127,219,255,.4);background:rgba(4,14,22,.78);opacity:0;transform:translateX(26px);transition:opacity .45s,transform .6s cubic-bezier(.2,.75,.2,1)}
  .op-bill div.on{opacity:1;transform:none}
  .op-bill div.bad{border-color:rgba(255,110,90,.8);background:rgba(36,8,6,.82)}
  .op-bill b{display:block;font:600 15px/1.35 var(--f-cn,sans-serif);color:#E4F8FF}
  .op-bill div.bad b{color:#FFB0A4}
  .op-bill span{display:block;margin-top:2px;color:#9FDDF5;font-size:12px;line-height:1.4}
  .op-foot{position:absolute;left:50%;bottom:16px;transform:translateX(-50%);font:10px/1.4 var(--f-cn,sans-serif);letter-spacing:.4px;color:rgba(159,221,245,.55);white-space:nowrap}
  @media (max-width:1100px){.op-card,.op-bill{display:none}}`;
  document.head.appendChild(css);
  const ov = document.createElement('div'); ov.id = 'op';
  ov.innerHTML = `<svg></svg>
    <div class="op-top"><b>OPTIMUS · 走路</b><i></i><em>收起 ×</em></div>
    <div class="op-steps"></div>
    <div class="op-card" id="opL"><h6><span class="t"></span><span class="tg"></span></h6><div class="op-big"></div><div class="op-ph"></div><div class="op-rows"></div></div>
    <div class="op-card" id="opR"><h6><span class="t"></span><span class="tg"></span></h6><canvas width="520" height="200"></canvas><div class="note"></div></div>
    <div class="op-bill"></div>
    <div class="op-foot">报道 0.6 m/s（2024-02）· 目标 5 mph ≈ 2.2 m/s（报道转述）｜ 质心、捕获点、面积、延迟为估算或示意｜ 质量 / 力矩 / 控制频率 / 跌倒率未公开</div>`;
  document.body.appendChild(ov);
  const svg = ov.querySelector('svg'), topPh = ov.querySelector('.op-top i'), steps = ov.querySelector('.op-steps'), billEl = ov.querySelector('.op-bill');
  const cardL = ov.querySelector('#opL'), cardR = ov.querySelector('#opR'), cv = cardR.querySelector('canvas'), cx = cv.getContext('2d');
  ov.querySelector('.op-top').addEventListener('click', () => hide());
  addEventListener('keydown', (e) => { if (e.key === 'Escape' && ST.on && !ST.ending) hide(); });

  let calls = [];
  function clearCalls() { calls.forEach(c => { c.el.remove(); c.path.remove(); c.dot.remove(); }); calls = []; }
  function makeCalls(list) {
    clearCalls();
    calls = list.map(q => {
      const el = document.createElement('div'); el.className = 'op-call' + (q.hot ? ' hot' : '');
      el.innerHTML = `<b>${q.t}${q.en ? `<small>${q.en}</small>` : ''}</b>` + q.l.map(s => `<span>${s}</span>`).join('');
      ov.appendChild(el);
      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path'), dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      dot.setAttribute('r', '2.5'); if (q.hot) { path.classList.add('hot'); dot.classList.add('hot'); }
      svg.appendChild(path); svg.appendChild(dot);
      return { ...q, el, path, dot, v: new THREE.Vector3(), op: 0, o: root, p: q.p.slice() };
    });
  }
  function setCall(i, x, y, z) { if (calls[i]) calls[i].p = [x, y, z]; }
  const tagCls = (t) => t === '报道' ? 'rep' : t === '目标' ? 'goal' : t === '未公开' ? 'no' : t === '示意' ? 'demo' : 'est';
  function fillCard(d) {
    cardL.querySelector('.t').textContent = d.title;
    cardL.querySelector('.tg').textContent = d.tag || '';
    cardL.querySelector('.op-big').innerHTML = d.big;
    cardL.querySelector('.op-ph').textContent = d.phase || '';
    cardL.querySelector('.op-rows').innerHTML = d.rows.map(r => `<div><small>${r[0]}</small><b>${r[1]}</b><i class="${tagCls(r[2])}">${r[2]}</i></div>`).join('');
  }
  const CH = { grid: 'rgba(115,217,255,.14)', txt: 'rgba(159,221,245,.7)', cy: '#73D9FF', am: '#FFB27A', red: '#FF6A5A', wh: '#E4F8FF' };
  function chartHead(title, tag) { cardR.querySelector('.t').textContent = title; cardR.querySelector('.tg').textContent = tag || ''; cx.clearRect(0, 0, cv.width, cv.height); cx.font = '16px ui-monospace,Menlo,monospace'; cx.textBaseline = 'middle'; }
  function bars(list, max, unit) {
    const W = cv.width, H = cv.height, row = (H - 28) / list.length, x0 = 132, x1 = W - 92;
    list.forEach(([lab, v, col], i) => {
      const y = 8 + i * row + row / 2, bw = (x1 - x0) * clamp(v / max, 0, 1);
      cx.fillStyle = CH.txt; cx.textAlign = 'right'; cx.fillText(lab, x0 - 8, y);
      cx.fillStyle = 'rgba(115,217,255,.08)'; cx.fillRect(x0, y - row * 0.22, x1 - x0, row * 0.44);
      cx.fillStyle = col; cx.fillRect(x0, y - row * 0.22, Math.max(2, bw), row * 0.44);
      cx.fillStyle = CH.wh; cx.textAlign = 'left'; cx.fillText(unit(v), x1 + 8, y);
    });
  }
  function linePlot(series, xMax, yMin, yMax, xNow, yNow) {
    const W = cv.width, H = cv.height, L = 46, R = W - 12, T = 16, B = H - 28;
    const px = (x) => L + (R - L) * clamp(x / xMax, 0, 1);
    const py = (y) => B - (B - T) * clamp((y - yMin) / (yMax - yMin), 0, 1);
    cx.strokeStyle = CH.grid; cx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) { const y = yMin + (yMax - yMin) * i / 4; cx.beginPath(); cx.moveTo(L, py(y)); cx.lineTo(R, py(y)); cx.stroke(); cx.fillStyle = CH.txt; cx.textAlign = 'right'; cx.fillText(y.toFixed(2), L - 6, py(y)); }
    series.forEach(sr => {
      cx.strokeStyle = sr.col; cx.lineWidth = 2; cx.beginPath();
      sr.pts.forEach((q, j) => j ? cx.lineTo(px(q[0]), py(q[1])) : cx.moveTo(px(q[0]), py(q[1]))); cx.stroke();
    });
    if (xNow != null) { cx.fillStyle = CH.wh; cx.beginPath(); cx.arc(px(xNow), py(yNow), 5, 0, TAU); cx.fill(); }
    cx.textAlign = 'left';
  }
  let sim = { key: '' };
  function drawChart() {
    cardR.querySelector('.note').textContent = sim.note || '';
    if (sim.key === 'bot') {
      chartHead('步速对照', 'm/s');
      bars([['现在 · 报道', V_WALK, CH.cy], ['目标 · 5 mph', MPH5, CH.am]], 2.6, v => v.toFixed(2));
    } else if (sim.key === 'car_vs_bot') {
      chartHead('支撑面积', '估算 m²');
      const max = Math.max(sim.carA, sim.botA, 0.01);
      bars([['车 · 四轮', sim.carA, CH.cy], ['当前 · 双足', Math.max(sim.botA, 0.0001), CH.am]], max, v => v >= 0.1 ? v.toFixed(2) : v.toFixed(3));
    } else if (sim.key === 'pendulum' || sim.key === 'capture') {
      chartHead(sim.key === 'capture' ? '质心水平位置' : '质心 x · 模型秒', '估算');
      linePlot(sim.series || [], sim.xMax || 1.2, sim.yMin ?? -0.05, sim.yMax || 0.45, sim.model, sim.yNow);
    } else if (sim.key === 'zmp') {
      chartHead('足底压力中心', '相对脚心 m');
      linePlot(sim.series || [], 11, -0.16, 0.26, sim.u, sim.zmp);
    } else if (sim.key === 'stack') {
      chartHead('落点落后 ≈ 速度 × 延迟', '示意');
      linePlot(sim.series || [], 0.36, 0, 0.28, sim.tau, sim.miss);
    }
  }
  function layout(w, bill) {
    const pw = Math.round(Math.min(300, Math.max(232, w * 0.17)));
    ov.style.setProperty('--pw', pw + 'px');
    const x = Math.max(18, w * 0.02);
    cardL.style.left = x + 'px'; cardL.style.right = 'auto'; cardL.style.top = '150px'; cardL.classList.add('on');
    if (bill) { cardR.classList.remove('on'); billEl.style.right = x + 'px'; billEl.style.left = 'auto'; billEl.style.top = '150px'; }
    else { cardR.classList.add('on'); cardR.style.right = x + 'px'; cardR.style.left = 'auto'; cardR.style.top = '150px'; }
  }
  const PW = (w) => (w > 1100 ? Math.round(Math.min(300, Math.max(232, w * 0.17))) + 28 : 16);
  function placeCalls(u, w, h) {
    calls.forEach(c => {
      const on = u >= c.a && u <= c.b && ST.on;
      c.v.set(c.p[0], c.p[1], c.p[2]); c.o.localToWorld(c.v); c.v.project(camera);
      const vis = on && c.v.z < 1 && Math.abs(c.v.x) < 1.2 && Math.abs(c.v.y) < 1.2;
      c.el.classList.toggle('on', vis);
      c.op += ((vis ? 1 : 0) - c.op) * 0.15;
      c.path.style.opacity = c.dot.style.opacity = String(c.op);
      if (c.op < 0.01) return;
      const ax = (c.v.x + 1) / 2 * w, ay = (1 - c.v.y) / 2 * h, side = c.s === 'l' ? -1 : 1;
      const ew = c.el.offsetWidth || 180, eh = c.el.offsetHeight || 64;
      const bx = ax + side * (c.dx ?? 150), by = ay + (c.dy ?? -20);
      const M = PW(w); let left = side > 0 ? bx : bx - ew; left = Math.max(M, Math.min(w - ew - M, left));
      const top = Math.max(148, Math.min(h - eh - 48, by - eh / 2));
      c.el.style.left = left + 'px'; c.el.style.top = top + 'px';
      const ex = side > 0 ? left : left + ew, ey = top + Math.min(eh / 2, 16), mx = ax + (ex - ax) * 0.55;
      c.path.setAttribute('d', `M${ax.toFixed(1)},${ay.toFixed(1)} L${mx.toFixed(1)},${ey.toFixed(1)} L${ex.toFixed(1)},${ey.toFixed(1)}`);
      c.dot.setAttribute('cx', ax.toFixed(1)); c.dot.setAttribute('cy', ay.toFixed(1));
    });
  }

  const XI0 = V_WALK / OMEGA;
  function zmpS(u) { return u < 4.6 ? lerp(-0.07, 0.05, smooth(0.4, 4.2, u)) : lerp(0.05, 0.22, smooth(4.6, 8.4, u)); }
  function stackTau(u) { return 0.03 + 0.33 * smooth(4.0, 10.6, u); }
  let uCross = 10; for (let u = 4; u <= 11; u += 0.02) if (V_WALK * stackTau(u) >= FH) { uCross = u; break; }

  function penAt(u) {
    const fall = (u >= 3.3 && u < 8.0) || u >= 9.3;
    const rate = 0.32;
    if (!fall) {
      const x = 0.045 * Math.sin(u * 1.2), v = 0.045 * 1.2 * Math.cos(u * 1.2);
      return { mode: '投影钉在脚心', x, v, p: x, tip: 0, model: 0, rate: 1, hold: true };
    }
    const start = u >= 9.3 ? 9.3 : 3.3, tm = (u - start) * rate, x0 = 0.045 * Math.sin(start * 1.2), v0 = 0.32, p = 0;
    const s = lip(x0, v0, p, tm);
    if (s.x <= FH) return { mode: '松手之后', x: s.x, v: s.v, p, tip: 0, model: tm, rate, x0, v0 };
    const te = exitTime(x0, v0, p, FH), at = lip(x0, v0, p, te);
    const tip = Math.min(1.12, Math.max(0, at.v) / ZC / OMEGA * Math.sinh(OMEGA * Math.max(0, tm - te)));
    return { mode: tip > 0.95 ? '出脚 · 教学摔倒' : '投影出了脚尖', x: FH, v: at.v, p, tip, model: tm, rate, x0, v0 };
  }
  function capSide(x0, v0, p, tm) {
    const toe = p + FH, s = lip(x0, v0, p, tm);
    if (s.x <= toe) return { x: s.x, v: s.v, p, tip: 0 };
    const te = exitTime(x0, v0, p, toe), at = lip(x0, v0, p, te);
    const tip = Math.min(1.12, Math.max(0, at.v) / ZC / OMEGA * Math.sinh(OMEGA * Math.max(0, tm - te)));
    return { x: toe, v: at.v, p, tip };
  }
  function seriesLip(x0, v0, p, tMax, toe) {
    const pts = []; for (let t = 0; t <= tMax; t += tMax / 48) { const s = lip(x0, v0, p, t); pts.push([t, Math.min(s.x, toe + 0.02)]); }
    return pts;
  }

  /* ---------------- scenes ---------------- */
  const SC = [
    { key: 'bot', name: '慢走', dur: 12,
      setup() { show(BOT.spin, GR.g); },
      update(u) {
        const N = gaitPose(u, -0.6);
        poseRig(BOT, N, { ang: 0 });
        POLY.set(polyPts(N.sup.h), N.sup.inside ? C.holo : C.hot);
        POLY2.set([], C.holo);
        const c = placeMarkers(N.com.x, N.com.z, 0, { x: 0, y: 0, z: 0 }, true, N.com.x + V_WALK / OMEGA);
        setCall(0, c[0], c[1] + 0.35, c[2]); setCall(1, N.com.x, 0.25, N.com.z); setCall(2, N.com.x + V_WALK / OMEGA, 0.2, N.com.z);
        orbit(new THREE.Vector3(N.hip.x, 0.86, 0), 5.2, 0.95, 0.18);
        sim = { key: 'bot', card: {
          title: '慢走 · 示意步态', tag: '对准报道速度', big: `${V_WALK.toFixed(2)}<em>m/s</em>`,
          phase: N.sup.inside ? '投影还在支撑多边形里' : '投影已经出了这只脚',
          rows: [['步速', '0.60<em>m/s</em>', '报道'], ['目标', '5<em>mph</em>', '目标'], ['约合', `${MPH5.toFixed(1)}<em>m/s</em>`, '目标'],
            ['支撑', N.feet.filter(f => f.planted).length === 2 ? '双脚' : '单脚', '示意'], ['面积', `${N.sup.area.toFixed(3)}<em>m²</em>`, '估算'],
            ['一步停住', `${(V_WALK / OMEGA).toFixed(2)}<em>m</em>`, '估算']]
        } };
      },
      calls: () => [
        { p: [0, 1.6, 0], s: 'r', a: 0.6, b: 6.2, t: '报道步速约 0.6 m/s', en: '2024-02', l: ['Kovac / Optimus 账号', '比前一条大约快三成'] },
        { p: [0, 0.2, 0], s: 'l', a: 2.2, b: 8, t: '先把重心挪到支撑脚', en: 'ZMP 观感', l: ['再抬另一只脚', '步态本身是示意，不是力矩闭包'] },
        { p: [0.2, 0.2, 0], s: 'r', a: 6.4, b: 12, hot: true, t: '目标约 5 mph', en: '≈ 2.2 m/s', l: ['对外目标，报道转述', '不是已经走到的速度'] } ] },
    { key: 'car_vs_bot', name: '车与人', dur: 13,
      setup() { show(BOT.spin, CAR.g, GR.g); CAR.g.position.set(-2.7, 0, 0); CAR.g.rotation.set(0, 0, 0); },
      update(u) {
        const N = gaitPose(u, 1.7, 1.7);
        poseRig(BOT, N, { ang: 0 });
        const carH = hull([[-2.7 - CAR.wb / 2, -CAR.tr / 2], [-2.7 + CAR.wb / 2, -CAR.tr / 2], [-2.7 + CAR.wb / 2, CAR.tr / 2], [-2.7 - CAR.wb / 2, CAR.tr / 2]]);
        POLY.set(polyPts(carH, 0.03), C.holo);
        POLY2.set(polyPts(N.sup.h), N.sup.inside ? C.bright : C.hot);
        COM.mesh.visible = PROJ.mesh.visible = CAP.mesh.visible = false; DROP.set([], C.dim);
        setCall(0, -2.7, 1.5, 0); setCall(1, N.hip.x, 1.55, 0); setCall(2, N.com.x, 0.2, N.com.z);
        aim(-0.2, 6.5, 8.4, 0.15, 0.25, 0);
        const carA = shoelace(carH);
        sim = { key: 'car_vs_bot', carA, botA: N.sup.area, note: '条长按真实比例。车身尺寸是示意。', card: {
          title: '稳定多边形', tag: '车 vs 双足', big: `${(carA / Math.max(N.sup.area, 1e-4)).toFixed(0)}<em>× 面积</em>`,
          phase: '车的多边形又大又连续；单脚时只剩一只脚掌',
          rows: [['车 · 面积', `${carA.toFixed(2)}<em>m²</em>`, '估算'], ['双足 · 当前', `${N.sup.area.toFixed(3)}<em>m²</em>`, '估算'],
            ['车 · 轴距', '2.8<em>m</em>', '示意'], ['车 · 轮距', '1.55<em>m</em>', '示意'],
            ['脚掌', `${(2 * FH).toFixed(2)}×${(2 * FW).toFixed(2)}<em>m</em>`, '估算'], ['切换', N.feet.filter(f => f.planted).length === 2 ? '双支撑' : '单支撑', '示意']]
        } };
      },
      calls: () => [
        { p: [-2.7, 0.4, 0], s: 'l', a: 0.5, b: 13, t: '四个轮子同时着地', en: 'CAR', l: ['支撑多边形大，而且不会突然换形'] },
        { p: [1.7, 1.5, 0], s: 'r', a: 1.2, b: 13, hot: true, t: '单脚时只剩脚掌', en: 'FOOT', l: ['多边形在两只脚之间来回跳', '质心却不能跳'] } ] },
    { key: 'pendulum', name: '倒立摆', dur: 14,
      setup() { show(BOT.spin, GR.g); },
      update(u) {
        const st = penAt(u), pivot = { x: FH, y: 0, z: 0 };
        poseRig(BOT, {
          hip: { x: st.x, y: HIP_Y, z: 0 }, lean: 0,
          ankles: { L: { x: ankleX(0), y: AY, z: HIP_Z }, R: { x: st.x - 0.22, y: AY + 0.1, z: -HIP_Z } },
          arms: { L: 0.25, R: -0.15 }
        }, { ang: st.tip, pivot });
        POLY.set(polyPts(hull(footCorners(0, HIP_Z))), C.holo); POLY2.set([], C.holo);
        const xi = st.x + st.v / OMEGA;
        placeMarkers(st.x, 0, st.tip, pivot, !st.hold, xi);
        setCall(0, st.x, ZC, 0); setCall(1, 0, 0.15, HIP_Z); setCall(2, xi, 0.18, 0);
        aim(st.x * 0.3, 0.86, 3.7, st.x * 0.25, 0.7, 0);
        const x0 = st.x0 ?? st.x, v0 = st.v0 ?? st.v;
        sim = { key: 'pendulum', model: st.model, yNow: Math.min(st.x, FH + 0.02), xMax: 1.1, yMin: -0.08, yMax: 0.42,
          series: [{ col: CH.cy, pts: st.hold ? [[0, st.x]] : seriesLip(x0, v0, 0, 1.1, FH) }, { col: CH.am, pts: [[0, FH], [1.1, FH]] }],
          note: st.hold ? '这一段把压力中心钉在投影下，所以不发散。' : `模型时间 ${st.model.toFixed(2)} s · 慢放 ${st.rate.toFixed(2)}×`,
          card: { title: '线性倒立摆', tag: '教学估算', big: `${st.x.toFixed(2)}<em>m</em>`, phase: st.mode,
            rows: [['z_c', `${ZC.toFixed(2)}<em>m</em>`, '估算'], ['ẋ', `${st.v.toFixed(2)}<em>m/s</em>`, '估算'],
              ['ω', `${OMEGA.toFixed(2)}<em>/s</em>`, '估算'], ['脚尖', `+${FH.toFixed(2)}<em>m</em>`, '估算'],
              ['捕获点', `${xi.toFixed(2)}<em>m</em>`, '估算'], ['方程', 'ẍ=(g/z)(x−p)', '估算']] }
        };
      },
      calls: () => [
        { p: [0, 1.15, 0], s: 'r', a: 0.4, b: 3.2, t: '质心在上，脚在下', en: 'LIP', l: ['ẍ = (g / z_c) · (x − p)', 'z_c、速度都是估算'] },
        { p: [0, 0.12, 0], s: 'l', a: 0.5, b: 3.2, t: '投影钉在脚心', en: 'STIFF', l: ['稳，但是走起来发僵'] },
        { p: [0.1, 1.2, 0], s: 'r', a: 3.5, b: 8, hot: true, t: '投影一出脚尖就开始摔', en: 'FALL', l: ['出脚之前按倒立摆积分', '出脚之后绕脚尖转，教学示意'] },
        { p: [0.25, 0.2, 0], s: 'r', a: 9.4, b: 14, hot: true, t: '下一步要踩在捕获点', en: 'ξ = x + ẋ/ω', l: ['热圈是按现在的位置和速度算的', '估算，不是实测落点'] } ] },
    { key: 'zmp', name: '足底', dur: 11,
      setup() { show(BOT.spin, GR.g); },
      update(u) {
        const s = zmpS(u), over = s - FH, tip = over > 0 ? clamp(over / 0.08, 0, 1.05) : 0;
        const hx = Math.min(Math.max(s, -0.04), FH) * 0.75;
        const pivot = { x: FH, y: 0, z: HIP_Z };
        poseRig(BOT, {
          hip: { x: hx, y: HIP_Y, z: 0.02 }, lean: 0,
          ankles: { L: { x: ankleX(0), y: AY, z: HIP_Z }, R: { x: ankleX(-0.02), y: AY, z: -HIP_Z } },
          arms: { L: 0.1, R: -0.05 }
        }, { ang: tip, pivot });
        paintSole(BOT.legL.sole, s, tip > 0.2);
        POLY.set(polyPts(hull(footCorners(0, HIP_Z))), over > 0 ? C.hot : C.holo);
        POLY2.set([], C.holo);
        COM.mesh.visible = false; PROJ.mesh.visible = false; DROP.set([], C.dim);
        CAP.mesh.visible = true; const zm = spun(pivot, tip, s, 0.045, HIP_Z); CAP.mesh.position.set(zm[0], zm[1], zm[2]);
        setCall(0, s, 0.2, HIP_Z); setCall(1, FH, 0.28, HIP_Z);
        aim(0.42, 0.38, 0.78, 0.02, 0.04, 0.08);
        const curve = []; for (let t = 0; t <= 11; t += 0.25) curve.push([t, zmpS(t)]);
        sim = { key: 'zmp', u, zmp: s, series: [{ col: CH.am, pts: curve }, { col: CH.red, pts: [[0, FH], [11, FH]] }, { col: CH.cy, pts: [[0, -FH], [11, -FH]] }],
          note: '两条横线是脚尖和脚跟。压力中心出了线，脚就开始翻。',
          card: { title: '零力矩点', tag: 'ZMP', big: `${s.toFixed(2)}<em>m</em>`, phase: over > 0 ? '压力中心出了脚边' : '压力中心还在脚里',
            rows: [['相对脚心', `${s.toFixed(2)}<em>m</em>`, '示意'], ['脚尖', `+${FH.toFixed(2)}<em>m</em>`, '估算'],
              ['脚跟', `${(-FH).toFixed(2)}<em>m</em>`, '估算'], ['状态', over > 0 ? '翻边' : '在多边形内', '示意'],
              ['和投影', '不是同一个点', '估算'], ['Optimus', '早期观感偏先移重心', '报道']] }
        };
      },
      calls: () => [
        { p: [0, 0.22, 0.09], s: 'l', a: 0.4, b: 4.6, t: '压力中心在脚底板里', en: 'ZMP', l: ['它出了支撑多边形，脚就会翻边'] },
        { p: [0.13, 0.25, 0.09], s: 'r', a: 5.2, b: 11, hot: true, t: '跑出脚尖', en: 'TIP', l: ['先移重心再抬脚，是在买这个保险', '翻边动作是教学示意'] } ] },
    { key: 'capture', name: '捕获点', dur: 15,
      setup() { show(BOTL.spin, BOTR.spin, GR.g); },
      update(u) {
        const rate = 0.28, intro = 2.4, tm = Math.max(0, (u - intro) * rate);
        const bad = capSide(0, V_WALK, 0, tm), good = capSide(0, V_WALK, XI0, tm);
        const offL = -1.2, offR = 1.2;
        const poseAt = (st, off) => ({
          hip: { x: st.x + off, y: HIP_Y, z: 0 }, lean: 0,
          ankles: { L: { x: ankleX(st.p) + off, y: AY, z: HIP_Z }, R: { x: st.x - 0.2 + off, y: st.v < 0.05 ? AY : AY + 0.1, z: -HIP_Z } },
          arms: { L: 0.35, R: -0.2 }
        });
        poseRig(BOTL, poseAt(bad, offL), { ang: bad.tip, pivot: { x: bad.p + FH + offL, y: 0, z: 0 } });
        poseRig(BOTR, poseAt(good, offR), { ang: good.tip, pivot: { x: good.p + FH + offR, y: 0, z: 0 } });
        POLY.set(polyPts(hull(footCorners(bad.p + offL, HIP_Z))), C.hot);
        POLY2.set(polyPts(hull(footCorners(good.p + offR, HIP_Z))), C.holo);
        const c = placeMarkers(good.x + offR, 0, good.tip, { x: good.p + FH + offR, y: 0, z: 0 }, true, good.p + offR);
        setCall(0, bad.x + offL, 1.35, 0); setCall(1, c[0], c[1] + 0.25, 0); setCall(2, good.p + offR, 0.2, 0);
        aim(0.05, 1.2, 5.6, 0.05, 0.78, 0);
        sim = { key: 'capture', model: tm, yNow: good.x, xMax: 1.6, yMin: -0.02, yMax: 0.4,
          series: [{ col: CH.red, pts: seriesLip(0, V_WALK, 0, 1.6, FH) }, { col: CH.cy, pts: seriesLip(0, V_WALK, XI0, 1.6, XI0 + FH) }],
          note: `同一初态。慢放 ${rate.toFixed(2)}× · 横轴是模型秒`,
          card: { title: '捕获点', tag: '教学估算', big: `${XI0.toFixed(2)}<em>m</em>`,
            phase: bad.tip > 0.9 ? '短了就摔倒，踩中就接住' : '脚要踩在 ξ = x + ẋ/ω',
            rows: [['初速度', '0.60<em>m/s</em>', '报道'], ['ω', `${OMEGA.toFixed(2)}<em>/s</em>`, '估算'],
              ['捕获点', `${XI0.toFixed(2)}<em>m</em>`, '估算'], ['错落误差', `${XI0.toFixed(2)}<em>m</em>`, '估算'],
              ['左 · 速度', `${bad.v.toFixed(2)}<em>m/s</em>`, '估算'], ['右 · 速度', `${good.v.toFixed(2)}<em>m/s</em>`, '估算']] }
        };
      },
      calls: () => [
        { p: [-1.2, 1.45, 0], s: 'l', a: 0.4, b: 15, hot: true, t: '踩在身子底下', en: 'MISS', l: ['比捕获点短了一整段', '速度还在，脚接不住'] },
        { p: [1.35, 1.5, 0], s: 'r', a: 0.8, b: 15, t: '踩在捕获点上', en: 'CATCH', l: ['质心会倒到这只脚上，然后停住', '距离是估算，不是实测落点'] } ] },
    { key: 'stack', name: '三层栈', dur: 13,
      setup() { show(BOT.spin, GR.g, STACK); },
      update(u) {
        const tau = u < 3.6 ? 0.03 : stackTau(u), miss = V_WALK * tau, xi = 0.15 + XI0, cx = xi - miss;
        const over = xi - (cx + FH), tip = u > uCross ? clamp((u - uCross) / 2.4, 0, 1.05) : 0;
        const base = 0.15, z = 0;
        poseRig(BOT, {
          hip: { x: base, y: HIP_Y, z }, lean: 0,
          ankles: { L: { x: ankleX(cx), y: AY, z: HIP_Z }, R: { x: base - 0.18, y: AY + 0.08, z: -HIP_Z } },
          arms: { L: 0.2, R: -0.1 }
        }, { ang: tip, pivot: { x: cx + FH, y: 0, z } });
        const active = u < 1.5 ? 0 : u < 3.0 ? 1 : 2;
        SLAB.forEach((s, i) => { s.mat.uniforms.uAlpha.value = i === active ? 1 : 0.28; s.g.scale.setScalar(i === active ? 1.04 : 1); });
        DOT.mesh.visible = true; DOT.mesh.position.set(-0.85, SLAB[active].g.position.y + 0.08, 0);
        POLY.set(polyPts(hull(footCorners(xi, HIP_Z))), C.bright);
        POLY2.set(polyPts(hull(footCorners(cx, HIP_Z))), over > 0 ? C.hot : C.holo);
        COM.mesh.visible = true; COM.mesh.position.set(base, ZC, 0);
        PROJ.mesh.visible = false; CAP.mesh.visible = true; CAP.mesh.position.set(xi, 0.04, HIP_Z); DROP.set([], C.dim);
        setCall(0, -0.85, 1.85, 0); setCall(1, -0.85, 1.22, 0); setCall(2, -0.85, 0.62, 0); setCall(3, cx, 0.25, HIP_Z);
        aim(0.15, 1.35, 5.1, 0.05, 0.9, 0);
        const curve = []; for (let t = 0; t <= 0.36; t += 0.01) curve.push([t, V_WALK * t]);
        sim = { key: 'stack', tau, miss, series: [{ col: CH.am, pts: curve }, { col: CH.red, pts: [[0, FH], [0.36, FH]] }],
          note: '0.6 m/s 用报道步速。延迟毫秒没有公开，滑块是示意。',
          card: { title: '规划 → 估计 → 伺服', tag: '延迟示意', big: `${(tau * 1000).toFixed(0)}<em>ms</em>`,
            phase: over > 0 ? '迟到的脚已经盖不住捕获点' : '延迟还小，落点盖得住',
            rows: [['延迟', `${(tau * 1000).toFixed(0)}<em>ms</em>`, '示意'], ['步速', '0.60<em>m/s</em>', '报道'],
              ['落后', `${miss.toFixed(2)}<em>m</em>`, '示意'], ['脚尖余量', `${FH.toFixed(2)}<em>m</em>`, '估算'],
              ['规划', '落脚 → 质心 → 关节', '报道'], ['估计', '视觉 · 前庭 · 接地', '报道']] }
        };
      },
      calls: () => [
        { p: [-0.85, 1.7, 0], s: 'l', a: 0.3, b: 1.6, t: '规划', en: 'PLAN', l: ['先落脚，再质心，再关节'] },
        { p: [-0.85, 1.15, 0], s: 'l', a: 1.6, b: 3.1, t: '估计', en: 'STATE', l: ['视觉和前庭看姿态', '接地逻辑判断踩实了没有'] },
        { p: [-0.85, 0.58, 0], s: 'l', a: 3.1, b: 5.2, t: '伺服', en: 'SERVO', l: ['高频跟上参考', 'Kovac 说过要砍整机延迟'] },
        { p: [0.2, 0.25, 0], s: 'r', a: 6.5, b: 13, hot: true, t: '延迟一大就接不住', en: 'LATE', l: ['具体毫秒没有公开', '这里的滑块只是示意'] } ] },
    { key: 'hard', name: '复刻账单', dur: 12,
      setup() {
        show(BOT.spin, GR.g);
        billEl.innerHTML = [
          ['示意慢走', '对准报道 0.6 m/s。看起来在走，力矩并没有闭包。', false],
          ['多边形会跳', '单脚、双脚切得出来。', false],
          ['倒立摆 · 捕获点', '教学方程能积分。距离标的是估算。', false],
          ['质量 · 惯量', '没有稳定公开。', true],
          ['关节峰值力矩', '曲线没有公开。', true],
          ['控制频率 · 跌倒率', '都没有公开。', true]
        ].map(([b, s, bad]) => `<div class="${bad ? 'bad' : ''}"><b>${b}</b><span>${s}</span></div>`).join('');
        billEls = [...billEl.children];
      },
      update(u) {
        const N = gaitPose(u + 20, -0.4);
        poseRig(BOT, N, { ang: 0 });
        POLY.set(polyPts(N.sup.h), N.sup.inside ? C.holo : C.hot);
        POLY2.set([], C.holo);
        const c = placeMarkers(N.com.x, N.com.z, 0, { x: 0, y: 0, z: 0 }, false, 0);
        setCall(0, c[0], 1.55, c[2]);
        orbit(new THREE.Vector3(N.hip.x - 0.1, 0.85, 0), 4.5, 0.9 + u * 0.02, 0.18);
        billEls.forEach((el, j) => el.classList.toggle('on', u > 0.45 + j * 1.15));
        sim = { key: 'hard', card: {
          title: '复刻账单', tag: '画得像 ≠ 闭包', big: '未闭合', phase: '皮囊能走。时间轴上的力矩还对不上。',
          rows: [['慢走', '示意', '示意'], ['倒立摆', '已积分', '估算'], ['捕获点', '对落 / 错落', '估算'],
            ['质量', '—', '未公开'], ['力矩曲线', '—', '未公开'], ['跌倒率', '—', '未公开']]
        } };
      },
      calls: () => [
        { p: [0, 1.5, 0], s: 'l', a: 7.6, b: 12, hot: true, t: '轮子在路上', en: '双足在跟地心谈判', l: ['看世界能搬一部分', '站得住是另一门物理课'] } ] }
  ];

  let billEls = [];
  let acc = 0; SC.forEach(s => { s.at = acc; acc += s.dur; }); const TOTAL = acc;
  steps.innerHTML = SC.map(s => `<div>${s.name}<i></i></div>`).join('');
  const stepEls = [...steps.children];

  const ST = { on: false, t: 0, idx: -1, paused: false, vis: 0, ending: false };
  function quiet() {
    show();
    POLY.set([], C.holo); POLY2.set([], C.holo); DROP.set([], C.dim);
    [COM, PROJ, CAP, DOT].forEach(b => { b.mesh.visible = false; });
    SOLES.forEach(coolSole);
    [BOT, BOTL, BOTR].forEach(r => { r.spin.position.set(0, 0, 0); r.spin.rotation.set(0, 0, 0); r.inner.position.set(0, 0, 0); });
    SLAB.forEach(s => { s.mat.uniforms.uAlpha.value = 0.45; s.g.scale.setScalar(1); });
    billEl.innerHTML = ''; billEls = [];
  }
  function enter(i) {
    ST.idx = i; quiet(); SC[i].setup(); makeCalls(SC[i].calls()); cam.cut = true; SC[i].update(0); cam.cut = true;
    topPh.textContent = '· ' + SC[i].name;
    stepEls.forEach((el, j) => { el.classList.toggle('now', j === i); el.classList.toggle('done', j < i); });
  }
  function play(from = 0) {
    ST.on = true; ST.paused = false; ST.ending = false; ST.t = from; root.visible = true; ov.classList.add('on'); yaw0 = null;
    const i = SC.findIndex(s => from < s.at + s.dur);
    enter(i < 0 ? SC.length - 1 : i);
  }
  function hide() { ST.ending = true; }
  function seek(key) {
    const i = typeof key === 'number' ? key : SC.findIndex(s => s.key === key || s.name === key);
    if (i < 0) return false; play(SC[i].at); return true;
  }

  const inv = new THREE.Matrix4(), ey = new THREE.Vector3(0, 1, 0);
  let yaw0 = null, panelT = 0;
  function frame(dt, w, h) {
    G.uTime.value += dt;
    const goal = ST.on && !ST.ending ? 1 : 0;
    ST.vis += (goal - ST.vis) * (1 - Math.pow(ST.ending ? 0.08 : 0.2, dt));
    if (ST.ending && ST.vis < 0.02) { ST.on = false; ST.ending = false; ST.vis = 0; root.visible = false; ov.classList.remove('on'); clearCalls(); quiet(); ctx.done && ctx.done(); }
    if (ctx.dim) ctx.dim(ST.vis);
    if (!ST.on) return;
    if (!ST.paused) ST.t += dt;
    if (ST.t >= TOTAL) { ST.t = TOTAL - 0.001; hide(); }
    let i = SC.findIndex(s => ST.t < s.at + s.dur); if (i < 0) i = SC.length - 1;
    if (i !== ST.idx) enter(i);
    const sc = SC[i], u = ST.t - sc.at;
    G.uPt.value = sc.key === 'zmp' ? 9 : 14;
    sc.update(u);
    let ap = i === 0 ? smooth(0, 2.2, u) : smooth(0, 0.6, u);
    if (i < SC.length - 1) ap *= 1 - smooth(sc.dur - 0.35, sc.dur, u); else ap *= 1 - smooth(sc.dur - 1.4, sc.dur, u);
    G.uAppear.value = (ctx.still ? 1 : ap) * Math.min(1, ST.vis * 1.4);
    G.uSpread.value = i === 0 ? 4.2 : 2;
    const k = cam.cut || ctx.still ? 1 : 1 - Math.pow(0.02, dt); cam.cut = false;
    cam.pos.lerp(cam.gp, k); cam.tgt.lerp(cam.gt, k);
    const spin = ctx.spin ? ctx.spin() : 0; if (yaw0 == null) yaw0 = spin; const yaw = (spin - yaw0) * 0.8;
    const off = cam.pos.clone().sub(cam.tgt).applyAxisAngle(ey, yaw);
    vcam.position.copy(cam.tgt).add(off); vcam.up.set(0, 1, 0); vcam.lookAt(cam.tgt); vcam.updateMatrixWorld();
    inv.copy(vcam.matrixWorld).invert();
    camera.updateMatrixWorld(); root.matrix.multiplyMatrices(camera.matrixWorld, inv); root.matrixWorldNeedsUpdate = true;
    root.updateMatrixWorld(true);
    topPh.textContent = '· ' + sc.name + (ST.paused ? ' · 暂停' : '');
    stepEls.forEach((el, j) => { el.firstElementChild.style.width = (j < i ? 100 : j === i ? 100 * u / sc.dur : 0) + '%'; });
    placeCalls(u, w, h);
    panelT -= 1;
    if (panelT <= 0 || ctx.still) {
      panelT = 4;
      layout(w, sc.key === 'hard');
      if (sim.card) fillCard(sim.card);
      if (sc.key !== 'hard') drawChart();
    }
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

  (function selfCheck() {
    const h = hull([[0, 0], [1, 0], [1, 0.5], [0, 0.5]]);
    if (Math.abs(shoelace(h) - 0.5) > 1e-3 || !insidePoly(h, 0.2, 0.2) || insidePoly(h, 2, 2)) console.warn('optimus polygon');
    const xi = V_WALK / OMEGA, late = lip(0, V_WALK, xi, 1.4);
    if (!(late.v < 0.08 && late.x > xi * 0.8)) console.warn('optimus capture', late);
    const te = exitTime(0, V_WALK, 0, FH);
    if (!(te > 0.05 && te < 0.8)) console.warn('optimus exit', te);
    for (let t = 0; t < 2; t += 0.1) {
      const N = gait(t, 0);
      [N.ankles.L, N.ankles.R].forEach((a, i) => {
        const hz = i ? -HIP_Z : HIP_Z;
        const d = Math.hypot(a.x - N.hip.x, a.y - N.hip.y, a.z - (N.hip.z + hz));
        if (d > L1 + L2 - 0.001) console.warn('optimus reach', t.toFixed(2), d.toFixed(3));
      });
    }
  })();

  return { frame, command, play, hide, seek, state: ST, stages: SC.map(s => ({ key: s.key, name: s.name, at: s.at, dur: s.dur })), total: TOTAL,
    at(t) { if (!ST.on) play(t); const i = SC.findIndex(s => t < s.at + s.dur); if (i !== ST.idx && i >= 0) enter(i); ST.t = t; ST.paused = true; } };
}
