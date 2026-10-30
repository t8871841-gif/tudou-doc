'use strict';
/* ---- navigation hologram: the core's particles fly apart and settle into a tilted 3D map of the route ---- */
const HOLO = { compass: [], on: false, prog: 0, until: 0, mesh: null, tag: null, end: new THREE.Vector3(), start: new THREE.Vector3() };
const holoRoot = new THREE.Group(); holoRoot.position.set(0, -0.38, 0); holoRoot.rotation.x = -1.18; holoRoot.scale.setScalar(1.45); scene.add(holoRoot);
const holoSpin = new THREE.Group(); holoRoot.add(holoSpin);
const HU = { uProg: { value: 0 }, uTime: U.uTime, uPixel: U.uPixel };
const holoMat = new THREE.ShaderMaterial({
  uniforms: HU, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
  vertexShader: `uniform float uProg,uTime,uPixel; attribute vec3 aFrom; attribute vec3 aCol; attribute vec4 aK; varying vec3 vC; varying float vA;
    // aK: kind (0 route, 1 building, 2 grid, 3 beacon), along-route position, seed, size
    void main(){
      float kind=aK.x, seed=aK.z;
      float p=clamp(uProg*1.6-seed*0.6,0.0,1.0); p=p*p*(3.0-2.0*p);
      vec3 t=position;
      if(kind>0.5&&kind<1.5) t.z*=smoothstep(0.55,1.0,uProg);                    // buildings rise after the ground settles
      if(kind>2.5) t.z*=0.4+0.6*smoothstep(0.6,1.0,uProg);
      vec3 q=mix(aFrom,t,p);
      q.xy+=(1.0-p)*vec2(sin(seed*91.0+uTime),cos(seed*57.0+uTime))*0.15;
      vec4 mv=modelViewMatrix*vec4(q,1.0); gl_Position=projectionMatrix*mv;
      float r=length(t.xy); float fade=smoothstep(1.25,0.85,r);
      float a=1.0, s=aK.w;
      if(kind<0.5){ float f=fract(aK.y*6.0-uTime*0.35); a=0.75+2.2*pow(1.0-f,10.0); s*=1.0+0.8*pow(1.0-f,10.0); fade=1.0; }
      else if(kind<1.5){ a=0.9+0.4*sin(uTime*(0.6+seed)+seed*80.0); }
      else if(kind<2.5){ a=0.8; }
      else { a=0.7+0.5*sin(uTime*3.0+seed*20.0); fade=1.0; }
      gl_PointSize=s*uPixel*(8.0/-mv.z);
      vC=aCol; vA=a*fade*mix(0.5,1.0,p)*smoothstep(0.0,0.08,uProg);
    }`,
  fragmentShader: `varying vec3 vC; varying float vA; void main(){ vec2 d=gl_PointCoord-0.5; float r=dot(d,d)*4.0; if(r>1.0) discard; gl_FragColor=vec4(vC*vA*exp(-r*2.5),1.0); }`
});
/* the real AMap street map under the route, redrawn as a hologram: edges glow cyan, water glows deep blue */
const mapMat = new THREE.ShaderMaterial({
  uniforms: { uMap: { value: null }, uProg: HU.uProg, uTex: { value: new THREE.Vector2(1 / 1024, 1 / 1024) }, uR: { value: 1.25 } },
  transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
  vertexShader: `varying vec2 vUv; varying vec2 vP; void main(){ vUv=uv; vP=position.xy; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
  fragmentShader: `uniform sampler2D uMap; uniform float uProg,uR; uniform vec2 uTex; varying vec2 vUv; varying vec2 vP;
    float L(vec2 o){ vec3 c=texture2D(uMap,vUv+o*uTex).rgb; return dot(c,vec3(0.3,0.59,0.11)); }
    void main(){
      float gx=-L(vec2(-1,-1))-2.0*L(vec2(-1,0))-L(vec2(-1,1))+L(vec2(1,-1))+2.0*L(vec2(1,0))+L(vec2(1,1));
      float gy=-L(vec2(-1,-1))-2.0*L(vec2(0,-1))-L(vec2(1,-1))+L(vec2(-1,1))+2.0*L(vec2(0,1))+L(vec2(1,1));
      float e=clamp(length(vec2(gx,gy))*2.2,0.0,1.0);
      vec3 c=texture2D(uMap,vUv).rgb; float water=smoothstep(0.06,0.18,c.b-c.r); float green=smoothstep(0.04,0.12,c.g-c.r)*(1.0-water);
      float bright=smoothstep(0.97,1.0,min(c.r,min(c.g,c.b)));                       /* white = road surface */
      float major=smoothstep(0.1,0.25,c.r-c.b)*(1.0-green);                         /* yellow/orange = main road */
      vec3 col=vec3(0.25,0.7,1.0)*e*0.9 + vec3(0.2,0.55,0.85)*bright*0.22 + vec3(1.0,0.62,0.3)*major*0.35 + vec3(0.05,0.2,0.5)*water*0.35 + vec3(0.05,0.3,0.25)*green*0.25;
      float r=length(vP); float fade=smoothstep(uR,uR*0.7,r)*smoothstep(0.35,0.85,uProg);
      gl_FragColor=vec4(col*fade,1.0);
    }`
});
let mapMesh = null;
const TCOL = { '-1': [0.45, 0.85, 1.0], 0: [0.24, 0.86, 0.59], 1: [1.0, 0.72, 0.3], 2: [1.0, 0.33, 0.3], 3: [0.85, 0.12, 0.2] };
function holoBuild(holo, hasMap) {
  const P = [], F = [], C = [], K = [];
  const rnd = (a, b) => a + Math.random() * (b - a);
  const from = () => { const u = rnd(-1, 1), th = rnd(0, 6.283), r = 0.55 + 0.25 * Math.random(), s = Math.sqrt(1 - u * u); return [r * s * Math.cos(th), r * u, r * s * Math.sin(th) + 0.2]; };
  const add = (x, y, z, col, kind, along, size) => { P.push(x, y, z); F.push(...from()); C.push(...col); K.push(kind, along, Math.random(), size); };
  /* route: points spaced evenly along the path, a few lanes wide, colored by live traffic */
  const route = [];
  holo.segs.forEach(sg => { for (let i = 1; i < sg.p.length; i++) route.push([sg.p[i - 1], sg.p[i], sg.s]); });
  let total = 0; route.forEach(r => { r.len = Math.hypot(r[1][0] - r[0][0], r[1][1] - r[0][1]); total += r.len; });
  const step = Math.max(total / 1400, 0.004); let acc = 0;
  route.forEach(([a, b, s, ], idx) => {
    const len = route[idx].len, n = Math.max(1, Math.round(len / step)), nx = -(b[1] - a[1]) / (len || 1), ny = (b[0] - a[0]) / (len || 1);
    for (let i = 0; i < n; i++) {
      const f = i / n, x = a[0] + (b[0] - a[0]) * f, y = a[1] + (b[1] - a[1]) * f, along = (acc + len * f) / total;
      for (const off of [0, -0.011, 0.011]) add(x + nx * off, y + ny * off, 0.01, TCOL[s] || TCOL[-1], 0, along, off ? 1.6 : 2.6);
    }
    acc += len;
  });
  const rpts = route.map(r => r[0]);
  const near = (x, y) => { let d = 9; for (let i = 0; i < route.length; i += 2) { const [a, b] = route[i]; const vx = b[0] - a[0], vy = b[1] - a[1], L = vx * vx + vy * vy || 1e-9; const t = Math.max(0, Math.min(1, ((x - a[0]) * vx + (y - a[1]) * vy) / L)); d = Math.min(d, Math.hypot(x - a[0] - vx * t, y - a[1] - vy * t)); } return d; };
  /* buildings: wireframe prisms made of points, packed along the route and thinning out around it */
  const N = hasMap ? (small ? 60 : 140) : (small ? 120 : 260);
  for (let b = 0, tries = 0; b < N && tries < N * 8; tries++) {
    let x, y;
    if (Math.random() < 0.7 && rpts.length) { const p = rpts[Math.floor(Math.random() * rpts.length)]; x = p[0] + rnd(-0.3, 0.3); y = p[1] + rnd(-0.3, 0.3); }
    else { x = rnd(-1.15, 1.15); y = rnd(-1.15, 1.15); }
    if (Math.hypot(x, y) > 1.15) continue;
    const d = near(x, y); if (d < 0.05) continue;
    const w = rnd(0.025, 0.06), dd = rnd(0.025, 0.06), h = (0.04 + Math.pow(Math.random(), 2.2) * 0.42) * (d < 0.25 ? 1.2 : 0.7);
    const col = Math.random() < 0.18 ? [1.0, 0.62, 0.35] : [0.4, 0.75, 1.0];
    const lv = Math.max(4, Math.round(h / 0.007));
    const cs = [[-w, -dd], [w, -dd], [w, dd], [-w, dd]];
    cs.forEach(([cx, cy]) => { for (let k = 0; k <= lv; k++) add(x + cx, y + cy, h * k / lv, col, 1, 0, 0.9); });
    for (let e = 0; e < 4; e++) { const [ax, ay] = cs[e], [bx, by] = cs[(e + 1) % 4]; for (let k = 1; k < 8; k++) { const f = k / 8; add(x + ax + (bx - ax) * f, y + ay + (by - ay) * f, h, col, 1, 0, 1.2); add(x + ax + (bx - ax) * f, y + ay + (by - ay) * f, 0, col, 1, 0, 0.8); } }
    for (let k = 0; k < lv * 0.4; k++) add(x + rnd(-w, w), y + (Math.random() < 0.5 ? -dd : dd), rnd(0, h), [1.0, 0.85, 0.6], 1, 0, 0.9);   // lit windows
    b++;
  }
  /* ground grid */
  if (!hasMap) for (let g = -1.2; g <= 1.201; g += 0.15) for (let t = -1.2; t <= 1.2; t += 0.018) { add(g, t, 0, [0.3, 0.62, 0.9], 2, 0, 0.9); add(t, g, 0, [0.3, 0.62, 0.9], 2, 0, 0.9); }
  /* beacons: a light column and a ring at the start (cyan) and destination (orange) */
  const S = holo.segs[0].p[0], lastSeg = holo.segs[holo.segs.length - 1], E = lastSeg.p[lastSeg.p.length - 1];
  [[S, [0.5, 0.9, 1.0], 0.35], [E, [1.0, 0.6, 0.25], 0.6]].forEach(([p, col, h]) => {
    for (let k = 0; k < 60; k++) add(p[0], p[1], h * k / 60, col, 3, 0, 2.0);
    for (let k = 0; k < 48; k++) { const a = k / 48 * 6.283; add(p[0] + Math.cos(a) * 0.06, p[1] + Math.sin(a) * 0.06, 0.005, col, 3, 0, 1.6); }
  });
  HOLO.start.set(S[0], S[1], 0.35); HOLO.end.set(E[0], E[1], 0.6);
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(P, 3)); g.setAttribute('aFrom', new THREE.Float32BufferAttribute(F, 3));
  g.setAttribute('aCol', new THREE.Float32BufferAttribute(C, 3)); g.setAttribute('aK', new THREE.Float32BufferAttribute(K, 4));
  return new THREE.Points(g, holoMat);
}
function holoShow(holo, info) {
  if (!holo || !holo.segs || !holo.segs.length) return;
  if (HOLO.mesh) { holoSpin.remove(HOLO.mesh); HOLO.mesh.geometry.dispose(); }
  HOLO.mesh = holoBuild(holo, !!holo.map); holoSpin.add(HOLO.mesh);
  if (mapMesh) { holoSpin.remove(mapMesh); mapMesh.geometry.dispose(); mapMat.uniforms.uMap.value && mapMat.uniforms.uMap.value.dispose(); mapMesh = null; }
  if (holo.map && holo.map.img) new THREE.TextureLoader().load(holo.map.img, tex => {
    tex.colorSpace = THREE.NoColorSpace; tex.minFilter = THREE.LinearFilter; tex.generateMipmaps = false;
    mapMat.uniforms.uMap.value = tex; mapMat.uniforms.uTex.value.set(1 / tex.image.width, 1 / tex.image.height);
    mapMesh = new THREE.Mesh(new THREE.PlaneGeometry(holo.map.half * 2, holo.map.half * 2), mapMat); mapMesh.position.z = -0.004; holoSpin.add(mapMesh);
  });
  holoSpin.rotation.z = 0; HU.uProg.value = Math.min(HU.uProg.value, 0.3);
  HOLO.on = true; HOLO.until = performance.now() + 75000;
  if (!HOLO.tag) {
    HOLO.tag = document.createElement('div'); HOLO.tag.className = 'holo-tag'; document.body.appendChild(HOLO.tag);
    HOLO.home = document.createElement('div'); HOLO.home.className = 'holo-tag start'; HOLO.home.textContent = '起点'; document.body.appendChild(HOLO.home);
    HOLO.legend = document.createElement('button'); HOLO.legend.type = 'button'; HOLO.legend.className = 'holo-legend'; document.body.appendChild(HOLO.legend);
    HOLO.legend.addEventListener('click', holoHide);
    HOLO.compass = [['北', 0, 1.2], ['东', 1.2, 0], ['南', 0, -1.15], ['西', -1.2, 0]].map(([t, x, y]) => {
      const el = document.createElement('div'); el.className = 'holo-dir' + (t === '北' ? ' n' : ''); el.textContent = t; document.body.appendChild(el);
      return [new THREE.Vector3(x, y, 0), el];
    });
  }
  HOLO.tag.innerHTML = `<b>${escapeHtml(info.to || '目的地')}</b><span>${info.minutes} 分钟 · ${info.km} km · 预计 ${info.eta || ''} 到</span>`;
  const live = holo.segs.some(s => s.s >= 0);
  HOLO.legend.innerHTML = (live ? '<i style="--c:#3DDC97"></i>畅通<i style="--c:rgb(var(--sk-acc))"></i>缓行<i style="--c:#FF5555"></i>拥堵' : '<i style="--c:rgb(var(--sk-pri))"></i>路线') + '<em>收起 ×</em>';
}
function holoHide() { HOLO.on = false; }
function holoFrame(dt, w, h) {
  if (HOLO.on && performance.now() > HOLO.until) HOLO.on = false;
  const goal = HOLO.on ? 1 : 0;
  HU.uProg.value = goal ? Math.min(1, HU.uProg.value + dt / 2.6) : Math.max(0, HU.uProg.value - dt / 1.6);
  const p = HU.uProg.value, vis = p > 0.001;
  holoRoot.visible = vis;
  U.uGain.value = 1 - Math.min(1, p * 1.6);
  const d = U.uDorm.value; U.uDorm.value += ((document.body.classList.contains('dormant') || document.body.classList.contains('demo') ? 1 : 0) - d) * (1 - Math.pow(0.2, dt));
  rainM.opacity = 0.15 * (1 - p * 0.85) * (1 - U.uDorm.value); rpM.opacity = 0.8 * (1 - p * 0.85) * (1 - U.uDorm.value);
  orbits.visible = p < 0.3 && U.uDorm.value < 0.6; labelBox.style.opacity = p > 0.05 ? '0' : '';
  if (!HOLO.tag) return;
  const show = p > 0.85;
  if (vis) {
    holoRoot.rotation.x = -1.18 + target.x * 0.4;
    [[HOLO.end, HOLO.tag], [HOLO.start, HOLO.home], ...HOLO.compass].forEach(([v, el]) => {
      tmp.copy(v); HOLO.mesh.localToWorld(tmp); tmp.project(camera);
      el.style.left = ((tmp.x + 1) / 2 * w) + 'px'; el.style.top = ((1 - tmp.y) / 2 * h) + 'px';
    });
  }
  HOLO.compass.forEach(([, el]) => el.classList.toggle('on', show));
  HOLO.tag.classList.toggle('on', show); HOLO.home.classList.toggle('on', show); HOLO.legend.classList.toggle('on', show);
}
window.holoShow = holoShow; window.holoHide = holoHide;
