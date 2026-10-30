'use strict';
/* <<machine-hologram */
/* ---- machine hologram: the core's particles fly apart and rebuild a machine figure as a 3D hologram, then take it apart ----
   The figure is an Anatomy isometric page (holo/*.html). Every drawn part (.it) carries its depth key (data-k) and its
   explode vector in world units (data-m); the page's data (const D) carries the camera (EX, EY, EZ, V, k), the assembly
   names and the order they come apart in. Each sampled outline point is lifted back into 3D with the part's depth. */
const MACH = { on: false, prog: 0, mesh: null, until: 0, t: 0, ids: [], share: [], vel: [], goal: [], at: [], hot: [], cent: [], move: [], tags: [], legend: null, title: null, info: null, busy: false, plan: [], step: -1 };
const machRoot = new THREE.Group(); scene.add(machRoot);
const machSpin = new THREE.Group(); machRoot.add(machSpin);
const MAXP = 16;
const MU = { uProg: { value: 0 }, uTime: U.uTime, uPixel: U.uPixel, uScan: { value: 0 }, uShare: { value: new Float32Array(MAXP) }, uHot: { value: new Float32Array(MAXP) },
  uMove: { value: Array.from({ length: MAXP }, () => new THREE.Vector3()) },
  uCent: { value: Array.from({ length: MAXP }, () => new THREE.Vector3()) }, uShift: { value: Array.from({ length: MAXP }, () => new THREE.Vector3()) },
  uScale: { value: new Float32Array(MAXP).fill(1) }, uFocus: { value: -1 }, uDim: { value: 1 } };
const machMat = new THREE.ShaderMaterial({
  uniforms: MU, transparent: true, depthWrite: false, depthTest: false, blending: THREE.AdditiveBlending,
  vertexShader: `uniform float uProg,uTime,uPixel,uScan; uniform float uShare[${MAXP}]; uniform float uHot[${MAXP}]; uniform vec3 uMove[${MAXP}]; uniform vec3 uCent[${MAXP}]; uniform vec3 uShift[${MAXP}]; uniform float uScale[${MAXP}]; uniform float uFocus,uDim;
    attribute vec3 aFrom; attribute vec4 aK;   // aK: kind (0 outline, 1 surface, 2 screw dot, 3 stand), piece, seed, size
    varying vec3 vC; varying float vA;
    void main(){
      int pc=int(aK.y+0.5); float seed=aK.z, kind=aK.x;
      float p=clamp(uProg*1.7-seed*0.7,0.0,1.0); p=p*p*(3.0-2.0*p);
      vec3 home=uCent[pc]+(position-uCent[pc])*uScale[pc]+uMove[pc]*uShare[pc]+uShift[pc];   // each assembly scales about its own centre
      vec3 q=mix(aFrom,home,p);
      q+=(1.0-p)*vec3(sin(seed*91.0+uTime*1.3),cos(seed*57.0+uTime),sin(seed*33.0-uTime))*0.18;
      vec4 mv=modelViewMatrix*vec4(q,1.0); gl_Position=projectionMatrix*mv;
      float hot=uHot[pc];
      float scan=exp(-pow((position.y-uScan)*7.0,2.0));                       // a bright band sweeping up the figure
      float fl=0.85+0.15*sin(uTime*23.0+seed*400.0);                           // hologram flicker
      float a = kind<0.5 ? 0.78 : kind<1.5 ? 0.15 : kind<2.5 ? 1.1 : 0.22;
      if(uFocus>-0.5 && abs(float(pc)-uFocus)>0.5) a*=uDim;                  // the others fade while one is in focus
      float s = aK.w*(1.0+hot*0.35)*mix(1.0,uScale[pc],0.3);
      gl_PointSize=min(s*uPixel*(7.0/-mv.z),16.0*uPixel);
      vec3 cool=kind>2.5 ? vec3(0.22,0.5,0.85) : vec3(0.38,0.82,1.0);
      vC=mix(cool,vec3(1.0,0.68,0.32),hot)+vec3(0.6,0.9,1.0)*scan*0.9;
      vA=a*fl*(1.0+scan*1.6+hot*0.5)*mix(0.35,1.0,p)*smoothstep(0.0,0.1,uProg);
    }`,
  fragmentShader: `varying vec3 vC; varying float vA; void main(){ vec2 d=gl_PointCoord-0.5; float r=dot(d,d)*4.0; if(r>1.0) discard; gl_FragColor=vec4(vC*vA*exp(-r*2.6),1.0); }`
});
const MACH_SIZE = 3.05;
const MACH_BUDGET = small ? 40000 : 70000;   /* most points a machine hologram draws; the B48 model has 152k and froze the Mac */           /* the figure's tallest side in scene units */
async function machBuild(info, { stand: keepStand = false, spread = 1.9 } = {}) {
  let D = info.data, doc;
  if (info.svg) doc = new DOMParser().parseFromString(`<!doctype html><body>${info.svg}</body>`, 'text/html');
  else {                                   /* or a figure page next to the HUD */
    const html = await (await fetch(info.src, { cache: 'no-store' })).text();
    doc = new DOMParser().parseFromString(html, 'text/html');
    const code = [...doc.scripts].map(s => s.textContent).join('\n'), at = code.indexOf('const D = ');
    if (at < 0) throw new Error('no figure data');
    D = JSON.parse(code.slice(at + 10, code.indexOf(';\n', at)));
  }
  const svgSrc = [...doc.querySelectorAll('svg')].find(s => s.querySelector('.it[data-k]'));
  if (!svgSrc) throw new Error('no parts');
  /* lay the figure out off screen so the browser can measure its paths */
  const host = document.createElement('div');
  host.style.cssText = `position:fixed;left:-${D.W + 200}px;top:0;width:${D.W}px;height:${D.H}px;pointer-events:none;opacity:0`;
  const svg = document.importNode(svgSrc, true); svg.setAttribute('width', D.W); svg.setAttribute('height', D.H);
  host.appendChild(svg); document.body.appendChild(host);
  const ids = (D.stagger || []).filter(id => id !== 'stand');
  Object.keys(D.pieces || {}).forEach(id => { if (id !== 'stand' && !ids.includes(id)) ids.push(id); });
  const idx = id => id === 'stand' ? ids.length : Math.max(0, ids.indexOf(id));
  const [EX, EY, EZ, V] = [D.EX, D.EY, D.EZ, D.V], kpx = D.camera.k, [ox, oy] = D.camera.origin;
  /* world move -> (screen right, screen up, toward viewer) in px */
  const toView = m => [m[0] * EX[0] + m[1] * EY[0] + m[2] * EZ[0], -(m[0] * EX[1] + m[1] * EY[1] + m[2] * EZ[1]), (m[0] * V[0] + m[1] * V[1] + m[2] * V[2]) * kpx];
  const P = [], F = [], K = [];
  const moves = Array.from({ length: MAXP }, () => [0, 0, 0]), sums = Array.from({ length: MAXP }, () => [0, 0, 0, 0]);
  const budget = small ? 26000 : 64000;
  const items = [...svg.querySelectorAll('.it[data-k]')];
  const lineSel = '.iso-edge,.tb-edge,.iso-crease,.iso-bevel,path.iso-line';
  let total = 0;
  const lens = items.map(el => [...el.querySelectorAll(lineSel)].map(p => { let L = 0; try { L = p.getTotalLength(); } catch (_) {} total += L; return L; }));
  const step = Math.max(0.7, total / budget);
  const from = () => { const u = Math.random() * 2 - 1, th = Math.random() * 6.283, r = 0.55 + 0.25 * Math.random(), s = Math.sqrt(1 - u * u); return [r * s * Math.cos(th), r * u * 0.8, r * s * Math.sin(th)]; };
  const add = (x, y, d, kind, pc, size) => {
    P.push(x - ox, -(y - oy), d * kpx); F.push(...from()); K.push(kind, pc, Math.random(), size);
    const s = sums[pc]; s[0] += x - ox; s[1] += -(y - oy); s[2] += d * kpx; s[3]++;
  };
  items.forEach((el, n) => {
    const id = el.dataset.piece, pc = idx(id), stand = id === 'stand';
    if (stand && !keepStand) return;
    const depth = Number(el.dataset.k), m = (el.dataset.m || '0,0,0').split(',').map(Number);
    if (!stand) moves[pc] = toView(m).map(v => v * spread);
    const jitter = () => depth + (Math.random() - 0.5) * 3;
    [...el.querySelectorAll(lineSel)].forEach((p, j) => {
      const L = lens[n][j]; if (!L) return;
      const thin = p.classList.contains('iso-crease') || p.classList.contains('iso-bevel') || !p.classList.contains('iso-edge') && !p.classList.contains('tb-edge');
      const gap = step * (thin ? 1.8 : 1);
      for (let s = Math.random() * gap; s < L; s += gap) { const q = p.getPointAtLength(s); add(q.x, q.y, jitter(), stand ? 3 : 0, pc, thin ? 1.3 : 1.8); }
    });
    el.querySelectorAll('circle').forEach(c => add(Number(c.getAttribute('cx')), Number(c.getAttribute('cy')), depth + 0.5, stand ? 3 : 2, pc, 2.4));
    /* faint surface glow: a sparse jittered grid inside each solid's fill */
    el.querySelectorAll('.iso-fill,.tb-body').forEach(f => {
      let b; try { b = f.getBBox(); } catch (_) { return; }
      const g = Math.max(4.5, step * 4);
      for (let x = b.x + Math.random() * g; x < b.x + b.width; x += g) for (let y = b.y + Math.random() * g; y < b.y + b.height; y += g) {
        const pt = new DOMPoint(x + (Math.random() - 0.5) * g * 0.6, y + (Math.random() - 0.5) * g * 0.6);
        if (f.isPointInFill(pt)) add(pt.x, pt.y, jitter(), stand ? 3 : 1, pc, 1.4);
      }
    });
  });
  host.remove();
  /* centre and scale so both the assembled and the apart figure fit */
  const pcs = []; for (let i = 0; i < K.length; i += 4) pcs.push(K[i + 1]);
  let x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9, z0 = 1e9, z1 = -1e9;
  const grow = (x, y, z) => { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); z0 = Math.min(z0, z); z1 = Math.max(z1, z); };
  for (let i = 0, n = 0; i < P.length; i += 3, n++) { const m = moves[pcs[n]]; grow(P[i], P[i + 1], P[i + 2]); grow(P[i] + m[0], P[i + 1] + m[1], P[i + 2] + m[2]); }
  const sc = MACH_SIZE / Math.max(x1 - x0, y1 - y0), cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, cz = (z0 + z1) / 2, yl = y0;
  for (let i = 0; i < P.length; i += 3) { P[i] = (P[i] - cx) * sc; P[i + 1] = (P[i + 1] - cy) * sc; P[i + 2] = (P[i + 2] - cz) * sc; }
  const cent = sums.map(s => s[3] ? new THREE.Vector3((s[0] / s[3] - cx) * sc, (s[1] / s[3] - cy) * sc, (s[2] / s[3] - cz) * sc) : new THREE.Vector3());
  moves.forEach((m, i) => MU.uMove.value[i].set(m[0] * sc, m[1] * sc, m[2] * sc));
  /* the world's up direction, in this view's axes: the turntable turns about it */
  const up = new THREE.Vector3(-EZ[0], -EZ[1], V[2] * kpx).normalize();
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(P, 3)); g.setAttribute('aFrom', new THREE.Float32BufferAttribute(F, 3)); g.setAttribute('aK', new THREE.Float32BufferAttribute(K, 4));
  g.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 10);
  /* a few hundred points per assembly, for picking it with the mouse */
  const samples = ids.map(() => []);
  for (let i = 0, n = 0; i < P.length; i += 3, n++) { const pc = K[n * 4 + 1]; if (pc < ids.length && K[n * 4] !== 1) samples[pc].push(i); }
  const picks = samples.map(list => { const out = [], step = Math.max(1, Math.floor(list.length / 360)); for (let j = 0; j < list.length; j += step) out.push(new THREE.Vector3(P[list[j]], P[list[j] + 1], P[list[j] + 2])); return out; });
  return { mesh: new THREE.Points(g, machMat), D, ids, cent, up, picks, yRange: [(yl - cy) * sc, (y1 - cy) * sc], count: P.length / 3 };
}
function machTags(info, ids, D) {
  MACH.tags.forEach(t => t.remove());
  MACH.tags = ids.map((id, i) => {
    const el = document.createElement('div'); el.className = 'holo-tag mach-tag';
    const zh = (info.labels || {})[id] || {}, en = (D.pieces[id] || {});
    el.innerHTML = `<b><i>${String(i + 1).padStart(2, '0')}</i>${escapeHtml(zh.name || en.name || id)}</b>` + ((zh.say || en.say) ? `<span>${escapeHtml(zh.say || en.say)}</span>` : '');
    document.body.appendChild(el); return el;
  });
  if (!MACH.legend) {
    MACH.legend = document.createElement('button'); MACH.legend.type = 'button'; MACH.legend.className = 'holo-legend mach-legend'; document.body.appendChild(MACH.legend);
    MACH.legend.addEventListener('click', machineHide);
  }
}
/* a true 3D point cloud of the same figure (sampled from the parts' recorded shapes), seen right from any side */
function machCloud(info, { spread = 1.6 } = {}) {
  const D = info.data, C = info.cloud;
  const ids = (D.stagger || []).filter(id => id !== 'stand');
  Object.keys(D.pieces || {}).forEach(id => { if (id !== 'stand' && !ids.includes(id)) ids.push(id); });
  const bin = atob(C.points), buf = new ArrayBuffer(bin.length), u8 = new Uint8Array(buf);
  for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
  const dv = new DataView(buf), n = C.count, q = C.scale || 0.1;
  const kinds = [0, 0, 0, 0]; for (let i = 0; i < n; i++) kinds[Math.min(3, dv.getUint8(i * 8 + 6))]++;
  const keep = [1, 1, 1, 1], fixed = kinds[0] + kinds[2], rest = kinds[1] + kinds[3];
  if (n > MACH_BUDGET) {
    if (fixed >= MACH_BUDGET * 0.8) { keep[0] = keep[2] = MACH_BUDGET * 0.8 / fixed; keep[1] = keep[3] = Math.min(1, MACH_BUDGET * 0.2 / Math.max(1, rest)); }
    else keep[1] = keep[3] = Math.min(1, (MACH_BUDGET - fixed) / Math.max(1, rest));
  }
  const map = C.pieces.map(id => ids.indexOf(id));
  const mv = ids.map(id => { const m = (C.moves[id] || [0, 0, 0]).map(v => v * spread); return [m[0], m[2], -m[1]]; });
  const P = new Float32Array(n * 3), F = new Float32Array(n * 3), K = new Float32Array(n * 4); let m = 0;
  let x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9, z0 = 1e9, z1 = -1e9;
  const grow = (x, y, z) => { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); z0 = Math.min(z0, z); z1 = Math.max(z1, z); };
  for (let i = 0; i < n; i++) {
    const o = i * 8, pc = map[dv.getUint8(o + 7)]; if (pc < 0) continue;
    const kk = keep[Math.min(3, dv.getUint8(o + 6))]; if (kk < 1 && Math.random() > kk) continue;
    const x = dv.getInt16(o, true) * q, y = dv.getInt16(o + 2, true) * q, z = dv.getInt16(o + 4, true) * q, kind = dv.getUint8(o + 6);
    P[m * 3] = x; P[m * 3 + 1] = z; P[m * 3 + 2] = -y;
    grow(x, z, -y); grow(x + mv[pc][0], z + mv[pc][1], -y + mv[pc][2]);
    const u = Math.random() * 2 - 1, th = Math.random() * 6.283, r = 0.55 + 0.25 * Math.random(), sq = Math.sqrt(1 - u * u);
    F[m * 3] = r * sq * Math.cos(th); F[m * 3 + 1] = r * u * 0.8; F[m * 3 + 2] = r * sq * Math.sin(th);
    K[m * 4] = kind; K[m * 4 + 1] = pc; K[m * 4 + 2] = Math.random(); K[m * 4 + 3] = kind === 0 ? 1.35 : kind === 2 ? 2.4 : 1.2;
    m++;
  }
  const sc = MACH_SIZE / Math.max(x1 - x0, y1 - y0, z1 - z0), cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, cz = (z0 + z1) / 2;
  const sums = ids.map(() => [0, 0, 0, 0]), picks = ids.map(() => []);
  for (let i = 0; i < m; i++) {
    P[i * 3] = (P[i * 3] - cx) * sc; P[i * 3 + 1] = (P[i * 3 + 1] - cy) * sc; P[i * 3 + 2] = (P[i * 3 + 2] - cz) * sc;
    const pc = K[i * 4 + 1], s = sums[pc]; s[0] += P[i * 3]; s[1] += P[i * 3 + 1]; s[2] += P[i * 3 + 2]; s[3]++;
    if (K[i * 4] === 0 && Math.random() < 0.12) picks[pc].push(new THREE.Vector3(P[i * 3], P[i * 3 + 1], P[i * 3 + 2]));
  }
  picks.forEach((list, i) => { if (list.length > 400) picks[i] = list.filter((_, j) => j % Math.ceil(list.length / 400) === 0); });
  mv.forEach((v, i) => MU.uMove.value[i].set(v[0] * sc, v[1] * sc, v[2] * sc));
  const cent = sums.map(s => s[3] ? new THREE.Vector3(s[0] / s[3], s[1] / s[3], s[2] / s[3]) : new THREE.Vector3());
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(P.subarray(0, m * 3), 3)); g.setAttribute('aFrom', new THREE.BufferAttribute(F.subarray(0, m * 3), 3)); g.setAttribute('aK', new THREE.BufferAttribute(K.subarray(0, m * 4), 4));
  g.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 10);
  /* start from the figure's own camera: turn so its view direction faces us, tilt by its elevation */
  const V = D.V || [0.7, 0.55, 0.45];
  return { mesh: new THREE.Points(g, machMat), D, ids, cent, picks, up: new THREE.Vector3(0, 1, 0), yRange: [(y0 - cy) * sc, (y1 - cy) * sc], count: m,
    true3d: true, yaw0: -Math.atan2(V[0], -V[1]), tilt: Math.asin(V[2]) * 0.8, xf: { sc, cx, cy, cz } };
}
async function machineShow(info) {
  if (MACH.busy) return; MACH.busy = true;
  try { dormHold = false; wakeUI(); } catch (_) {}
  try {
    holoHide && holoHide();
    const built = info.cloud ? machCloud(info, { spread: info.spread || 1.6 }) : await machBuild(info, { stand: !!info.stand, spread: info.spread || 1.9 });
    if (MACH.mesh) { machSpin.remove(MACH.mesh); MACH.mesh.geometry.dispose(); }
    MACH.mesh = built.mesh; machSpin.add(MACH.mesh);
    Object.assign(MACH, { ids: built.ids, cent: built.cent, up: built.up, picks: built.picks, hover: -1, grab: null, manual: false, sway: 1, spin: 0, yUser: 0, lastTy: null, true3d: !!built.true3d, yaw0: built.yaw0 || 0, tilt: built.tilt || 0, yRange: built.yRange, D: built.D, info });
    const n = built.ids.length;
    MACH.share = new Array(n).fill(0); MACH.vel = new Array(n).fill(0); MACH.goal = new Array(n).fill(0); MACH.hot = new Array(MAXP).fill(0);
    MU.uShare.value.fill(0); MU.uHot.value.fill(0); MU.uScale.value.fill(1); MU.uFocus.value = -1; MU.uDim.value = 1;
    MACH.scale = new Array(n).fill(1); MACH.scaleGoal = new Array(n).fill(1); MACH.focus = -1; MACH.zoom = MACH.zoomGoal = 1;
    MACH.pan = new THREE.Vector2(); MACH.panGoal = new THREE.Vector2(); MACH.dim = 1;
    MACH.shiftGoal = built.ids.map(() => new THREE.Vector3());
    built.cent.forEach((c, i) => { MU.uCent.value[i].copy(c); MU.uShift.value[i].set(0, 0, 0); });
    machTags(info, built.ids, built.D);
    /* the show: gather, hold, take apart one assembly at a time, hold, put back together */
    const t0 = 3.0, gap = info.gap || 0.62, apart = t0 + n * gap, hold = info.hold || 7;
    MACH.plan = [...built.ids.map((id, i) => ({ at: t0 + i * gap, i, to: 1 })), ...built.ids.map((id, i) => ({ at: apart + hold + (n - 1 - i) * gap * 0.55, i, to: 0 }))];
    MACH.finish = apart + hold + n * gap * 0.55 + 1.5;
    MACH.t = 0; MACH.on = true; MACH.until = performance.now() + (info.seconds || 300) * 1000;
    MU.uProg.value = Math.min(MU.uProg.value, 0.3);
    MACH.legend.innerHTML = `<i style="--c:rgb(var(--sk-pri))"></i>${escapeHtml(info.title || '全息拆解')}<span class="mach-count"></span><em>收起 ×</em>`;
    if (typeof fireBuild === 'function') { FIRE.on = false; fireBuild(info.cloud, built.xf, built.ids); }
    console.log('machine hologram', built.count, 'points,', n, 'assemblies');
  } catch (e) { console.warn('machine hologram:', e); }
  MACH.busy = false;
}
function machineHide() { MACH.on = false; if (typeof FIRE !== 'undefined') FIRE.on = false; }
/* spoken commands: put it back together, take it apart again, pull out or put back one assembly */
async function machineCommand(m) {
  const a = m.action || 'show';
  if (a === 'show') return machineShow(m);
  if (a === 'hide') return machineHide();
  if ((!MACH.mesh || !MACH.on) && m.ensure) {      /* the window lost the hologram (a reload): project it again, assembled and still */
    await machineShow(m.ensure);
    if (!MACH.mesh) return;
    MACH.plan.forEach(s => { s.done = true; }); MACH.t = 3;
  }
  if (!MACH.mesh) return;
  if (!MACH.on) { MACH.on = true; }
  MACH.manual = true; MACH.until = performance.now() + 300000;
  MACH.plan.forEach(s => { s.done = true; });
  const n = MACH.ids.length, gap = 0.4, now = MACH.t;
  if (a === 'assemble') {                         /* wherever the parts were dragged, they fly home one by one */
    if (MACH.focus >= 0) machFocus(-1);
    MACH.zoomGoal = 1; MACH.panGoal.set(0, 0);
    MACH.plan = MACH.ids.map((_, i) => ({ at: now + 0.3 + (n - 1 - i) * gap, i, to: 0, home: true }));
  }
  else if (a === 'disassemble') { if (MACH.focus >= 0) machFocus(-1); MACH.plan = MACH.ids.map((_, i) => ({ at: now + 0.3 + i * gap, i, to: 1, home: true })); }
  else if (a === 'zoom_in' || a === 'zoom_out') {
    const k = a === 'zoom_in' ? 1.6 : 1 / 1.6, i = MACH.ids.indexOf(m.part);
    if (i >= 0) { MACH.scaleGoal[i] = Math.min(6, Math.max(0.35, MACH.scaleGoal[i] * k)); MACH.hover = i; }
    else { MACH.zoomGoal = Math.min(6, Math.max(0.45, MACH.zoomGoal * k)); if (MACH.zoomGoal <= 1) MACH.panGoal.set(0, 0); }
  }
  else if (a === 'focus') { const i = MACH.ids.indexOf(m.part); if (i >= 0) machFocus(i); }
  else if (a === 'unfocus') machFocus(-1);
  else if (a === 'reset_view') machResetView();
  else if (a === 'demo') { fireStart(0); return; }
  else if (a === 'ignite') { fireStart(12.5); return; }
  else if (a === 'shutdown') { fireStop(); return; }
  else if (a === 'gimbal') { fireGimbal(); return; }
  else if (a === 'pull' || a === 'return') {
    const i = MACH.ids.indexOf(m.part); if (i < 0) return;
    MACH.plan = [{ at: now + 0.2, i, to: a === 'pull' ? 1 : 0 }];
    MACH.hover = i; clearTimeout(MACH._ht); MACH._ht = setTimeout(() => { if (MACH.hover === i) MACH.hover = -1; }, 4000);
  }
}
function springTo(i, dt) {
  const S = (MACH.D && MACH.D.spring) || { stiffness: 64, damping: 14.2, substeps: 4 };
  for (let k = 0; k < S.substeps; k++) { const h = dt / S.substeps; MACH.vel[i] += ((MACH.goal[i] - MACH.share[i]) * S.stiffness - MACH.vel[i] * S.damping) * h; MACH.share[i] += MACH.vel[i] * h; }
}
const machTmp = new THREE.Vector3(), machQ = new THREE.Quaternion();
function machFrame(dt, w, h) {
  if (MACH.on && performance.now() > MACH.until) MACH.on = false;
  MU.uProg.value = MACH.on ? Math.min(1, MU.uProg.value + dt / 2.4) : Math.max(0, MU.uProg.value - dt / 1.5);
  const p = MU.uProg.value; machRoot.visible = p > 0.001 && !!MACH.mesh;
  if (p > 0.001) {                     /* the core gives its light to the hologram while it is up */
    U.uGain.value = Math.min(U.uGain.value, 1 - Math.min(1, p * 1.6));
    rainM.opacity *= 1 - p * 0.85; rpM.opacity *= 1 - p * 0.85;
    if (p > 0.3) orbits.visible = false; if (p > 0.05) labelBox.style.opacity = '0';
  }
  if (!MACH.mesh) return p;
  if (MACH.on) {
    MACH.t += dt * (reduced ? 4 : 1);
    MACH.plan.forEach(s => { if (!s.done && MACH.t >= s.at) { s.done = true; MACH.goal[s.i] = s.to; if (s.home) { MACH.shiftGoal[s.i].set(0, 0, 0); MACH.scaleGoal[s.i] = 1; } } });
  }
  let moving = -1, apartCount = 0;
  MACH.ids.forEach((_, i) => {
    springTo(i, dt);
    if (reduced) MACH.share[i] = MACH.goal[i];
    MU.uShare.value[i] = MACH.share[i];
    const busy = Math.abs(MACH.goal[i] - MACH.share[i]) > 0.04 || Math.abs(MACH.vel[i]) > 0.05;
    if (busy && !MACH.manual) moving = i;
    const lit = (busy && MACH.goal[i] === 1 && !MACH.manual) || i === MACH.hover || (MACH.grab && MACH.grab.i === i);
    MACH.hot[i] += ((lit ? 1 : 0) - MACH.hot[i]) * (1 - Math.pow(0.002, dt));
    MU.uHot.value[i] = MACH.hot[i];
    if (MACH.share[i] > 0.5) apartCount++;
  });
  /* zoom, pan, per-assembly scale and focus all ease */
  const ez = 1 - Math.pow(0.0015, dt);
  MACH.zoom += (MACH.zoomGoal - MACH.zoom) * ez; MACH.pan.lerp(MACH.panGoal, ez);
  MACH.ids.forEach((_, i) => {
    if (MACH.focus === i && window.HAND_GRAB_I !== i) MACH.shiftGoal[i].copy(MACH.cent[i]).addScaledVector(MU.uMove.value[i], MACH.share[i]).negate();
    MACH.scale[i] += (MACH.scaleGoal[i] - MACH.scale[i]) * ez; MU.uScale.value[i] = MACH.scale[i];
    if (!(MACH.grab && MACH.grab.i === i)) MU.uShift.value[i].lerp(MACH.shiftGoal[i], 1 - Math.pow(0.02, dt));
  });
  MACH.dim += ((MACH.focus >= 0 ? 0.12 : 1) - MACH.dim) * ez; MU.uDim.value = MACH.dim;
  if (MACH.focus >= 0) MU.uFocus.value = MACH.focus; else if (MACH.dim > 0.98) MU.uFocus.value = -1;
  /* scan band sweeps up the figure every few seconds */
  const [ya, yb] = MACH.yRange || [-1.5, 1.5], cyc = (U.uTime.value * 0.22) % 1.4;
  MU.uScan.value = ya + (yb - ya) * cyc - 0.2;
  /* gentle turntable about the world's vertical, plus the user's drag */
  MACH.sway += ((MACH.manual ? (MACH.true3d ? 0 : 0.15) : 1) - MACH.sway) * (1 - Math.pow(0.3, dt));
  let yaw;
  if (MACH.true3d) {                               /* a full turntable: it is real 3D. Only the user's own drags turn it once hands are on */
    const auto = (reduced ? 0.0002 : 0.0011) * (state === 'thinking' ? 1.4 : 1);
    if (MACH.lastTy == null) MACH.lastTy = target.y;
    MACH.yUser = (MACH.yUser || 0) + (target.y - MACH.lastTy - auto); MACH.lastTy = target.y;
    MACH.spin += dt * 0.16 * MACH.sway * (reduced ? 0.2 : 1); yaw = MACH.yaw0 + MACH.spin + MACH.yUser * 1.2;
  }
  else yaw = Math.sin(U.uTime.value * 0.23) * 0.42 * MACH.sway + target.y * 0.5;
  machQ.setFromAxisAngle(MACH.up, yaw); machSpin.quaternion.copy(machQ);
  machRoot.position.set(MACH.pan.x, -0.2 + MACH.pan.y, 0); machRoot.scale.setScalar(MACH.zoom); machRoot.rotation.x = MACH.tilt + target.x * 0.4;
  const show = p > 0.85;
  const placed = [];
  MACH.tags.forEach((el, i) => {
    const apart = MACH.share[i] > 0.6, on = show && ((apart && !MACH.manual) || i === moving || i === MACH.hover || (MACH.grab && MACH.grab.i === i));
    el.classList.toggle('on', on); el.classList.toggle('hot', MACH.hot[i] > 0.4);
    if (!on) return;
    machPartPos(i, MACH.cent[i], machTmp); MACH.mesh.localToWorld(machTmp); machTmp.project(camera);
    el._w = el._w || el.offsetWidth || 120; el._h = el.offsetHeight || 36;
    placed.push({ el, x: (machTmp.x + 1) / 2 * w, y: (1 - machTmp.y) / 2 * h });
  });
  for (let it = 0; it < 12; it++) for (let a = 0; a < placed.length; a++) for (let b = a + 1; b < placed.length; b++) {
    const A = placed[a], B = placed[b], ox = (A.el._w + B.el._w) / 2 + 6 - Math.abs(A.x - B.x), oy = (A.el._h + B.el._h) / 2 + 4 - Math.abs(A.y - B.y);
    if (ox > 0 && oy > 0) { const d = (A.y <= B.y ? -1 : 1) * oy / 2; A.y += d; B.y -= d; }
  }
  placed.forEach(({ el, x, y }) => {
    const sx = Math.round(x), sy = Math.round(y);
    if (el._sx !== sx || el._sy !== sy) { el.style.left = sx + 'px'; el.style.top = sy + 'px'; el._sx = sx; el._sy = sy; }
  });
  if (MACH.legend) {
    MACH.legend.classList.toggle('on', show);
    const c = MACH.legend.querySelector('.mach-count');
    const free = MACH.manual || MACH.t > MACH.finish;
    if (c) c.textContent = (MACH.t < MACH.plan[0]?.at ? '· 建模中' : apartCount ? `· 已拆 ${apartCount}/${MACH.ids.length}` : MACH.t > MACH.finish - 2 ? '· 已复原' : '· 准备拆解') + (free ? ' · 鼠标点选或拖动零件' : '');
  }
  if (typeof fireFrame === "function") fireFrame(dt);
  return p;
}

/* ---- hands on: hover lights an assembly, a click pulls it out or puts it back, a drag slides it along its own path ---- */
const machV = new THREE.Vector3(), machW = new THREE.Vector3();
const machLive = () => MACH.on && MACH.mesh && MU.uProg.value > 0.85;
function machToScreen(v, r) { machV.copy(v).applyMatrix4(MACH.mesh.matrixWorld).project(camera); return [(machV.x + 1) / 2 * r.width + r.left, (1 - machV.y) / 2 * r.height + r.top, machV.z]; }
/* where a point of assembly i is drawn now: scaled about the assembly's centre, pulled out, shifted into focus */
function machPartPos(i, p, out) {
  const c = MACH.cent[i];
  return out.copy(p).sub(c).multiplyScalar(MU.uScale.value[i]).add(c).addScaledVector(MU.uMove.value[i], MACH.share[i]).add(MU.uShift.value[i]);
}
/* a screen point at a given depth, in the hologram's own (local) coordinates */
const machInv = new THREE.Matrix4();
function machLocalAt(x, y, z) {
  const r = canvas.getBoundingClientRect();
  const v = new THREE.Vector3((x - r.left) / r.width * 2 - 1, 1 - (y - r.top) / r.height * 2, z).unproject(camera);
  return v.applyMatrix4(machInv.copy(MACH.mesh.matrixWorld).invert());
}
function machPick(x, y) {
  const r = canvas.getBoundingClientRect(); let best = -1, bd = 22 * 22;
  MACH.picks.forEach((list, i) => {
    const m = MU.uMove.value[i], sh = MACH.share[i];
    if (MACH.focus >= 0 && MACH.focus !== i) return;
    for (const p of list) { machPartPos(i, p, machW); const [sx, sy] = machToScreen(machW, r); const d = (sx - x) * (sx - x) + (sy - y) * (sy - y); if (d < bd) { bd = d; best = i; } }
  });
  return best;
}
/* how far the cursor moves on screen for a full pull of this assembly */
function machPull(i) {
  const r = canvas.getBoundingClientRect();
  const a = machToScreen(MACH.cent[i], r); machW.copy(MACH.cent[i]).add(MU.uMove.value[i]); const b = machToScreen(machW, r);   /* direction only */
  return [b[0] - a[0], b[1] - a[1]];
}
function machTakeOver() {
  if (!MACH.manual) { MACH.manual = true; MACH.plan.forEach(s => { s.done = true; }); MACH.ids.forEach((_, i) => { MACH.goal[i] = MACH.share[i] > 0.5 ? 1 : MACH.share[i]; }); }
  MACH.until = performance.now() + 300000;
}
let machDown = null;
addEventListener('pointerdown', e => {
  if (!machLive() || e.target !== canvas || e.button !== 0) return;
  const i = machPick(e.clientX, e.clientY);
  machDown = { x: e.clientX, y: e.clientY, i, moved: 0 };
  if (i < 0) return;                                 /* empty space: let the HUD turn the view */
  e.stopImmediatePropagation(); e.preventDefault();
  const v = machPull(i); machTakeOver();
  if (MACH.focus === i) { MACH.focus = -1; }              /* a focused part keeps its size and place, and can be moved on */
  machPartPos(i, MACH.cent[i], machW); MACH.mesh.localToWorld(machW); machW.project(camera);
  MACH.grab = { i, x: e.clientX, y: e.clientY, z: machW.z, shift: MU.uShift.value[i].clone() };
  canvas.setPointerCapture(e.pointerId); canvas.style.cursor = 'grabbing';
}, true);
addEventListener('pointermove', e => {
  if (machDown) machDown.moved += Math.abs(e.movementX) + Math.abs(e.movementY);
  if (MACH.grab) {
    /* free drag: the part follows the cursor anywhere on the screen, at the depth where it was picked up */
    const g = MACH.grab, a = machLocalAt(g.x, g.y, g.z), b = machLocalAt(e.clientX, e.clientY, g.z);
    MACH.shiftGoal[g.i].copy(g.shift).add(b.sub(a)); MU.uShift.value[g.i].copy(MACH.shiftGoal[g.i]);
    e.stopImmediatePropagation(); return;
  }
  if (!machLive() || e.target !== canvas) { if (MACH.hover >= 0) { MACH.hover = -1; canvas.style.cursor = ''; } return; }
  const i = machPick(e.clientX, e.clientY);
  if (i !== MACH.hover) { MACH.hover = i; canvas.style.cursor = i >= 0 ? 'pointer' : ''; }
}, true);
addEventListener('pointerup', e => {
  const d = machDown; machDown = null;
  if (MACH.grab) {
    const g = MACH.grab; MACH.grab = null; canvas.style.cursor = MACH.hover >= 0 ? 'pointer' : '';
    if (!d || d.moved < 5) {                       /* a click pulls it out or puts it back; a double click focuses it */
      const i = g.i, now = performance.now();
      if (MACH.lastClick && MACH.lastClick.i === i && now - MACH.lastClick.at < 320) { clearTimeout(MACH.clickT); MACH.lastClick = null; machFocus(MACH.focus === i ? -1 : i); }
      else { MACH.lastClick = { i, at: now }; clearTimeout(MACH.clickT); MACH.clickT = setTimeout(() => { if (MACH.focus < 0) MACH.goal[i] = MACH.share[i] > 0.5 ? 0 : 1; }, 320); }
    }
    e.stopImmediatePropagation(); return;
  }
  if (machLive() && d && d.moved < 6) {
    machTakeOver();
    const now = performance.now();                 /* double click on empty space: leave focus, or reset the view */
    if (MACH.lastEmpty && now - MACH.lastEmpty < 320) { MACH.lastEmpty = 0; if (MACH.focus >= 0) machFocus(-1); else machResetView(); }
    else MACH.lastEmpty = now;
  }
}, true);
function machFocus(i) {
  machTakeOver();
  const was = MACH.focus;
  if (was >= 0) { MACH.shiftGoal[was].set(0, 0, 0); MACH.scaleGoal[was] = Math.min(MACH.scaleGoal[was], 1); }
  MACH.focus = i;
  if (i >= 0) { MACH.scaleGoal[i] = Math.max(MACH.scaleGoal[i], 2.1); MACH.hover = i; MACH.zoomGoal = 1; MACH.panGoal.set(0, 0.2); }   /* centred on screen */
}
function machResetView() {
  MACH.zoomGoal = 1; MACH.panGoal.set(0, 0);
  if (MACH.focus >= 0) machFocus(-1);
  MACH.scaleGoal.fill(1); MACH.shiftGoal.forEach(v => v.set(0, 0, 0));
}
/* wheel or trackpad pinch: over an assembly it grows or shrinks that one; elsewhere it zooms the whole hologram toward the cursor */
canvas.addEventListener('wheel', e => {
  if (!machLive()) return;
  e.preventDefault(); machTakeOver();
  const k = Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.0016));
  const i = MACH.focus >= 0 ? MACH.focus : machPick(e.clientX, e.clientY);
  if (i >= 0 && !e.shiftKey) { MACH.scaleGoal[i] = Math.min(6, Math.max(0.35, MACH.scaleGoal[i] * k)); MACH.hover = i; return; }
  const r = canvas.getBoundingClientRect(), half = Math.tan(camera.fov * Math.PI / 360) * camera.position.z;
  const cx = ((e.clientX - r.left) / r.width * 2 - 1) * half * camera.aspect, cy = (1 - (e.clientY - r.top) / r.height * 2) * half + 0.28;
  const z0 = MACH.zoomGoal, z1 = Math.min(6, Math.max(0.45, z0 * k));
  const px = MACH.panGoal.x, py = MACH.panGoal.y - 0.2;
  MACH.panGoal.set(cx - (cx - px) * z1 / z0, cy - (cy - py) * z1 / z0 + 0.2);
  MACH.zoomGoal = z1;
  if (z1 <= 1.02) MACH.panGoal.multiplyScalar(z1 < 1 ? 0.3 : 0.6);   /* zoomed back out: drift home */
}, { passive: false });
function machineEscape() { if (MACH.focus >= 0) machFocus(-1); else machineHide(); }
/* while the hologram is up, a click on it handles parts and never toggles the microphone */
window.machineShow = machineShow; window.machineHide = machineHide; window.machineCommand = machineCommand; window.machineEscape = machineEscape;

/* ---- firing demo: propellants run along the real lines, the pumps spin up, both preburners light, the main chamber
   lights from their hot gas, and the nozzle fires a plume with shock diamonds; then the gimbal swings and it shuts down.
   Paths, the nozzle exit and the gimbal pivot come with the 3D model (cloud.flows, cloud.plume), in world units. */
const MAXF = 40;
const FIRE = { on: false, t: 0, paths: [], flowMesh: null, plumeMesh: null, phase: new Float32Array(MAXF), step: -1, thrust: 0, until: 0 };
const GU = { uGim: { value: new THREE.Matrix3() }, uPivot: { value: new THREE.Vector3() }, uGimPc: { value: -1 }, uHeat: { value: new Float32Array(MAXP) }, uFade: { value: 1 } };
Object.assign(MU, GU);
machMat.uniforms = MU;
/* the engine swings about the gimbal pivot (everything but the gimbal itself), and parts glow with heat */
machMat.vertexShader = machMat.vertexShader
  .replace('uniform float uFocus,uDim;', 'uniform float uFocus,uDim; uniform mat3 uGim; uniform vec3 uPivot; uniform float uGimPc,uFade; uniform float uHeat[' + MAXP + '];')
  .replace('vec3 q=mix(aFrom,home,p);', 'if(abs(float(pc)-uGimPc)>0.5) home=uPivot+uGim*(home-uPivot);\n      vec3 q=mix(aFrom,home,p);')
  .replace('vA=a*fl*', 'float heat=uHeat[pc]; vC=mix(vC,vec3(1.0,0.6,0.28),heat*0.7);\n      vA=uFade*(1.0+heat*0.3)*a*fl*');
machMat.needsUpdate = true;
const PIECE_GLSL = `uniform float uShare[${MAXP}]; uniform vec3 uMove[${MAXP}]; uniform vec3 uCent[${MAXP}]; uniform vec3 uShift[${MAXP}]; uniform float uScale[${MAXP}];
  uniform mat3 uGim; uniform vec3 uPivot; uniform float uGimPc;
  vec3 placed(vec3 p, int pc){ vec3 h=uCent[pc]+(p-uCent[pc])*uScale[pc]+uMove[pc]*uShare[pc]+uShift[pc]; if(abs(float(pc)-uGimPc)>0.5) h=uPivot+uGim*(h-uPivot); return h; }`;
const FU = { ...MU, uPath: { value: null }, uFlow: { value: new Float32Array(MAXF) }, uPh: { value: FIRE.phase }, uRad: { value: new Float32Array(MAXF) }, uPiece: { value: new Float32Array(MAXF) } };
const flowMat = new THREE.ShaderMaterial({
  uniforms: FU, transparent: true, depthWrite: false, depthTest: false, blending: THREE.AdditiveBlending,
  vertexShader: `uniform float uTime,uPixel,uProg; uniform sampler2D uPath; uniform float uFlow[${MAXF}], uPh[${MAXF}], uRad[${MAXF}], uPiece[${MAXF}];
    ${PIECE_GLSL}
    attribute vec4 aF;  attribute vec2 aOff;   // aF: path, start, seed, kind (0 lox, 1 methane, 2 ox-rich gas, 3 fuel-rich gas, 4 fire, 5 spark, 6 signal)
    varying vec3 vC; varying float vA;
    void main(){
      int path=int(aF.x+0.5); float kind=aF.w, seed=aF.z;
      float u=fract(aF.y+uPh[path]*(0.85+0.3*seed));
      float s=u*63.0, i0=floor(s);
      vec3 a=texelFetch(uPath,ivec2(int(i0),path),0).xyz, b=texelFetch(uPath,ivec2(int(min(i0+1.0,63.0)),path),0).xyz;
      vec3 p=mix(a,b,fract(s)), t=normalize(b-a+vec3(1e-5));
      vec3 n=normalize(cross(t,abs(t.y)<0.9?vec3(0,1,0):vec3(1,0,0))), bb=cross(t,n);
      float wob=sin(uTime*7.0+seed*60.0)*0.25;
      p+=(n*(aOff.x+wob*0.2)+bb*aOff.y)*uRad[path];
      p=placed(p,int(uPiece[path]+0.5));
      vec4 mv=modelViewMatrix*vec4(p,1.0); gl_Position=projectionMatrix*mv;
      float ends=smoothstep(0.0,0.06,u)*smoothstep(1.0,0.9,u);
      vec3 c = kind<0.5 ? vec3(0.78,0.9,1.0) : kind<1.5 ? vec3(0.25,1.0,0.85) : kind<2.5 ? vec3(1.0,0.5,0.35) : kind<3.5 ? vec3(1.0,0.68,0.22)
             : kind<4.5 ? vec3(1.0,0.88,0.62) : kind<5.5 ? vec3(1.0,1.0,0.85) : vec3(0.55,0.75,1.0);
      float fl = kind>4.5 ? step(0.55,fract(seed*17.0+uTime*9.0))*1.6 : 0.85+0.15*sin(uTime*30.0+seed*90.0);
      vC=c; vA=uFlow[path]*ends*fl*(kind>3.5&&kind<4.5?0.28:kind<1.5?0.8:0.6)*uProg;
      gl_PointSize=(kind>3.5&&kind<4.5?2.2:kind>4.5?1.6:2.0)*(0.7+0.6*seed)*uPixel*(7.0/-mv.z);
    }`,
  fragmentShader: `varying vec3 vC; varying float vA; void main(){ vec2 d=gl_PointCoord-0.5; float r=dot(d,d)*4.0; if(r>1.0) discard; gl_FragColor=vec4(vC*vA*exp(-r*2.4),1.0); }`
});
const PU = { ...MU, uThrust: { value: 0 }, uPT: { value: 0 }, uExit: { value: new THREE.Vector3() }, uDir: { value: new THREE.Vector3(0, -1, 0) }, uR: { value: 0.3 }, uL: { value: 1 }, uNoz: { value: 0 } };
const plumeMat = new THREE.ShaderMaterial({
  uniforms: PU, transparent: true, depthWrite: false, depthTest: false, blending: THREE.AdditiveBlending,
  vertexShader: `uniform float uTime,uPixel,uProg,uThrust,uPT,uR,uL,uNoz; uniform vec3 uExit,uDir;
    ${PIECE_GLSL}
    attribute vec4 aS; varying vec3 vC; varying float vA;
    void main(){
      float life=fract(aS.x+uPT*(0.8+0.5*aS.w));
      float L=uL*(0.45+0.55*uThrust);
      float d=pow(life,0.8)*L;
      float core=1.0-sqrt(aS.y);
      float rr=uR*sqrt(aS.y)*(0.92+0.42*d/uL)*(0.94+0.06*sin(d*30.0/uL+uTime*24.0*aS.z));
      float ang=aS.z*6.2832+uTime*0.6;
      vec3 n=normalize(cross(uDir,vec3(1,0,0))), b=cross(uDir,n);
      vec3 p=uExit+uDir*d+(n*cos(ang)+b*sin(ang))*rr;
      p+=vec3(sin(uTime*13.0+aS.w*40.0),cos(uTime*11.0+aS.z*40.0),sin(uTime*9.0+aS.y*40.0))*0.012*life*uL;
      p=placed(p,int(uNoz+0.5));
      vec4 mv=modelViewMatrix*vec4(p,1.0); gl_Position=projectionMatrix*mv;
      float dd=d/(uR*1.25);                                                   // shock diamonds about one exit radius apart
      float m=exp(-pow((fract(dd)-0.5)*6.0,2.0))*core*core*(1.0-smoothstep(1.0,6.0,dd));
      vec3 c=mix(vec3(0.72,0.84,1.0),vec3(1.0,0.78,0.42),smoothstep(0.02,0.35,life));
      c=mix(c,vec3(1.0,0.36,0.16),smoothstep(0.35,0.95,life));
      c=mix(c,vec3(1.0,0.86,0.8),m);
      float hotCore=core*core*(1.0-smoothstep(0.0,0.22,life));                 // the white-blue core just out of the nozzle
      vC=mix(c,vec3(0.85,0.92,1.0),hotCore*0.6); vA=uThrust*pow(1.0-life,1.2)*(0.11+m*1.0+core*0.16+hotCore*0.5)*uProg;
      gl_PointSize=(1.3+2.6*m+life*2.4)*uPixel*(7.0/-mv.z);
    }`,
  fragmentShader: `varying vec3 vC; varying float vA; void main(){ vec2 d=gl_PointCoord-0.5; float r=dot(d,d)*4.0; if(r>1.0) discard; gl_FragColor=vec4(vC*vA*exp(-r*2.0),1.0); }`
});
/* build the flow and plume particles for the model that is up now */
function fireBuild(cloud, xf, ids) {
  [FIRE.flowMesh, FIRE.plumeMesh].forEach(m => { if (m) { machSpin.remove(m); m.geometry.dispose(); } });
  FIRE.flowMesh = FIRE.plumeMesh = null; FIRE.paths = [];
  if (!cloud || !cloud.flows || !cloud.flows.length) return false;
  const toLocal = (p) => [(p[0] - xf.cx) * xf.sc, (p[2] - xf.cy) * xf.sc, (-p[1] - xf.cz) * xf.sc];
  const paths = cloud.flows.slice(0, MAXF), tex = new Float32Array(64 * paths.length * 4);
  const KIND = { lox: 0, ch4: 1, hotox: 2, hotfuel: 3, fire: 4, spark: 5, elec: 6 };
  const F = [], O = [];
  paths.forEach((f, j) => {
    f.p.forEach((q, k) => { const l = toLocal(q); tex.set([l[0], l[1], l[2], 1], (j * 64 + k) * 4); });
    FU.uRad.value[j] = f.r * xf.sc * 0.8; FU.uPiece.value[j] = Math.max(0, ids.indexOf(f.piece));
    const kind = KIND[f.kind] ?? 0, n = Math.round(Math.min(1400, Math.max(60, f.len * (kind === 4 ? 4 : kind >= 5 ? 5 : 6) * (small ? 0.5 : 1))));
    for (let i = 0; i < n; i++) { F.push(j, Math.random(), Math.random(), kind); const a = Math.random() * 6.283, r = Math.sqrt(Math.random()); O.push(Math.cos(a) * r, Math.sin(a) * r); }
    FIRE.paths.push({ ...f, kind, len: f.len * xf.sc });
  });
  const t = new THREE.DataTexture(tex, 64, paths.length, THREE.RGBAFormat, THREE.FloatType);
  t.minFilter = t.magFilter = THREE.NearestFilter; t.needsUpdate = true; FU.uPath.value = t;
  const g = new THREE.BufferGeometry(); const n = F.length / 4;
  g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(n * 3), 3));
  g.setAttribute('aF', new THREE.Float32BufferAttribute(F, 4)); g.setAttribute('aOff', new THREE.Float32BufferAttribute(O, 2));
  g.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 20);
  FIRE.flowMesh = new THREE.Points(g, flowMat); FIRE.flowMesh.frustumCulled = false; machSpin.add(FIRE.flowMesh);
  const pl = cloud.plume;
  if (pl) {
    const e = toLocal(pl.exit), d = toLocal([pl.exit[0] + pl.dir[0], pl.exit[1] + pl.dir[1], pl.exit[2] + pl.dir[2]]);
    PU.uExit.value.set(...e); PU.uDir.value.set(d[0] - e[0], d[1] - e[1], d[2] - e[2]).normalize();
    PU.uR.value = pl.r * xf.sc; PU.uL.value = pl.len * xf.sc; PU.uNoz.value = Math.max(0, ids.indexOf(pl.piece));
    GU.uPivot.value.set(...toLocal(pl.pivot)); GU.uGimPc.value = ids.indexOf(pl.gimbal);
    const m = small ? 7000 : 16000, S = new Float32Array(m * 4);
    for (let i = 0; i < m * 4; i++) S[i] = Math.random();
    const pg = new THREE.BufferGeometry();
    pg.setAttribute('position', new THREE.BufferAttribute(new Float32Array(m * 3), 3)); pg.setAttribute('aS', new THREE.BufferAttribute(S, 4));
    pg.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 20);
    FIRE.plumeMesh = new THREE.Points(pg, plumeMat); FIRE.plumeMesh.frustumCulled = false; machSpin.add(FIRE.plumeMesh);
  }
  return true;
}
/* the show, in seconds: what each stage looks like, and the line shown for it */
const FIRE_STEPS = [
  { at: 0, zh: '准备', en: 'STANDBY' },
  { at: 3, zh: '推进剂进场', en: 'PROPELLANT FEED' },
  { at: 9, zh: '涡轮泵加速', en: 'TURBOPUMP SPIN-UP' },
  { at: 14.5, zh: '预燃室点火', en: 'PREBURNER IGNITION' },
  { at: 19.5, zh: '主燃烧室点火', en: 'MAIN CHAMBER IGNITION' },
  { at: 23, zh: '全推力', en: 'FULL THRUST' },
  { at: 27.5, zh: '万向节摆动', en: 'GIMBAL CHECK' },
  { at: 32, zh: '关机', en: 'SHUTDOWN' },
];
const FIRE_END = 39;
const env = (t, a, b, c, d) => t < a ? 0 : t < b ? (t - a) / (b - a) : t < c ? 1 : t < d ? 1 - (t - c) / (d - c) : 0;
const ease = x => x * x * (3 - 2 * x);
function fireStart(from = 0) {
  if (!MACH.mesh || !FIRE.paths.length) return false;
  /* put it together and square it up first */
  if (MACH.focus >= 0) machFocus(-1);
  MACH.zoomGoal = 1; MACH.panGoal.set(0, 0);
  MACH.ids.forEach((_, i) => { MACH.goal[i] = 0; MACH.shiftGoal[i].set(0, 0, 0); MACH.scaleGoal[i] = 1; });
  MACH.plan.forEach(s => { s.done = true; });
  const apart = MACH.share.some(s => s > 0.05) || MU.uShift.value.some((v, i) => i < MACH.ids.length && v.length() > 0.01);
  MACH.manual = false; MACH.until = performance.now() + 300000;
  FIRE.on = true; FIRE.t = (from || 0) - (apart ? 1.6 : 0); FIRE.step = -1; FIRE.phase.fill(0);
  return true;
}
function fireStop() { if (FIRE.on && FIRE.t < 32) FIRE.t = 32; }
function fireGimbal() { if (FIRE.on && FIRE.t >= 22 && FIRE.t < 32) FIRE.gimAt = FIRE.t; }
const fireTmp = new THREE.Vector3();
function fireFrame(dt) {
  if (!FIRE.flowMesh) return;
  const on = FIRE.on && MACH.on && MACH.mesh;
  if (on) FIRE.t += dt;
  const t = on ? FIRE.t : 99;
  if (on && t > FIRE_END) FIRE.on = false;
  /* stage levels */
  const off = env(t, 31.5, 32, 33, 36.5);                                  // the slow tail after shutdown
  const feed = Math.max(ease(Math.min(1, Math.max(0, (t - 3) / 1.6))) * (t < 33 ? 1 : 0), t >= 33 ? off * 0.8 : 0);
  const pump = Math.max(ease(Math.min(1, Math.max(0, (t - 9) / 2.2))) * (t < 33 ? 1 : 0), t >= 33 ? off : 0);
  const pre = Math.max(ease(Math.min(1, Math.max(0, (t - 14.8) / 1.4))) * (t < 32.4 ? 1 : 0), t >= 32.4 ? env(t, 32, 32.4, 32.6, 34.5) : 0);
  const main = ease(Math.min(1, Math.max(0, (t - 19.6) / 0.7))) * (t < 32.2 ? 1 : Math.max(0, 1 - (t - 32.2) / 1.6));
  const spark = (env(t, 13.6, 13.8, 15.3, 15.5) + env(t, 15.8, 16.0, 17.2, 17.4)) * (0.5 + 0.5 * (Math.sin(t * 40) > 0 ? 1 : 0));
  const elec = env(t, 13.2, 13.6, 18.5, 19.2) + 0.25 * main;
  FIRE.thrust += ((t >= 20.2 && t < 32 ? 1 : 0) - FIRE.thrust) * (1 - Math.pow(t < 32 ? 0.25 : 0.05, dt));
  const level = { feed, pump, pre, main, spark, elec };
  const spin = 0.35 + 0.65 * pump + 0.6 * pre;
  FIRE.paths.forEach((f, j) => {
    const lv = f.stage === 'feed' ? feed : f.stage === 'pump' ? pump : f.stage === 'pre' ? pre : f.stage === 'main' ? main : f.kind === 6 ? elec : spark;
    FU.uFlow.value[j] += (lv - FU.uFlow.value[j]) * (1 - Math.pow(0.02, dt));
    const v = f.stage === 'feed' ? 0.12 : f.stage === 'pump' ? 0.18 : f.stage === 'pre' ? 0.36 : f.stage === 'main' ? 0.9 : 1.6;   /* scene units per second */
    FIRE.phase[j] += dt * v * (f.stage === 'spark' ? 1 : spin) / Math.max(0.05, f.len);
  });
  PU.uThrust.value = FIRE.thrust; PU.uPT.value += dt * (0.9 + 0.8 * FIRE.thrust);
  /* heat: preburners and turbines glow orange, the chamber white-hot */
  const idx = id => MACH.ids.indexOf(id);
  const heat = { orpb: pre, ftp: pre * 0.8, otp: pre * 0.5, hotgas: pre, chamber: main, nozzle: main * 0.55 + FIRE.thrust * 0.2 };
  for (let i = 0; i < MAXP; i++) GU.uHeat.value[i] *= Math.pow(0.1, dt);
  Object.entries(heat).forEach(([id, h]) => { const i = idx(id); if (i >= 0) GU.uHeat.value[i] = Math.max(GU.uHeat.value[i], h); });
  /* the flash when the main chamber lights */
  const flash = env(t, 19.5, 19.7, 19.9, 20.8);
  bloom.strength = Math.max(bloom.strength, 0.42 + flash * 0.7 + FIRE.thrust * 0.08);
  GU.uFade.value += ((on && t > 1 && t < 35 ? 0.5 : 1) - GU.uFade.value) * (1 - Math.pow(0.1, dt));
  /* gimbal: two slow swings at full thrust */
  const g0 = FIRE.gimAt != null ? FIRE.gimAt : 27.5, gk = env(t, g0, g0 + 0.6, g0 + 3.4, g0 + 4);
  const ang = gk * 0.105 * Math.sin((t - g0) * Math.PI);          /* six degrees */
  if (FIRE.gimAt != null && t > g0 + 4) FIRE.gimAt = null;
  fireTmp.set(0, 0, 1);
  const m4 = new THREE.Matrix4().makeRotationAxis(fireTmp, ang);
  GU.uGim.value.setFromMatrix4(m4);
  /* rumble at thrust */
  const r = FIRE.thrust * 0.012 + flash * 0.03;
  machRoot.position.x += (Math.random() - 0.5) * r; machRoot.position.y += (Math.random() - 0.5) * r;
  /* stage caption on the legend */
  if (on) {
    let k = 0; FIRE_STEPS.forEach((s, i) => { if (t >= s.at) k = i; });
    if (k !== FIRE.step) {
      FIRE.step = k;
      const c = MACH.legend && MACH.legend.querySelector('.mach-count');
      if (MACH.legend) MACH.legend.dataset.fire = `${FIRE_STEPS[k].en} · ${FIRE_STEPS[k].zh}`;
    }
  } else if (MACH.legend && MACH.legend.dataset.fire) delete MACH.legend.dataset.fire;
  if (MACH.legend) { const c = MACH.legend.querySelector('.mach-count'); if (c && MACH.legend.dataset.fire) c.textContent = '· 点火演示 · ' + MACH.legend.dataset.fire; }
}
/* machine-hologram>> */
