'use strict';
const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
const small = innerWidth < 900;
const $ = (s) => document.querySelector(s);
function escapeHtml(s) { return s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }
const routeCv = $('#route');
// route polyline (0..1 of the canvas): defined here because loop.js reads it before creator.js has loaded
const routePts = [[0.05, 0.5], [0.16, 0.5], [0.24, 0.38], [0.36, 0.42], [0.44, 0.62], [0.56, 0.58], [0.64, 0.74], [0.75, 0.66], [0.85, 0.7], [0.92, 0.46], [0.95, 0.24]];
window.SKC = {pri: '127,219,255', line: '140,200,225', soft: '159,221,245', hi: '228,248,255', deep: '60,140,200', ink: '220,239,247', acc: '255,174,107', accl: '255,194,122', acch: '255,140,60', accp: '255,227,200', bg: '2,5,9', pnl: '4,12,18', wbg: '22,12,4'};   /* skins: the live colour triplets for canvas drawing (skin block keeps them current) */

/* ---------------- WebGL core ---------------- */
const canvas = $('#stage');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: false, powerPreference: 'high-performance' });
const DPR = Math.min(devicePixelRatio || 1, 2);
renderer.setPixelRatio(DPR);
renderer.setClearColor(0x000000, 1);
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(38, 1, 0.1, 100);
camera.position.set(0, 0, 7.2);
const world = new THREE.Group();
scene.add(world);

/* deep-space backdrop: drifting nebula, dust lanes and twinkling stars; full strength in standby, dimmed behind the panels */
const skyU = { uRes: { value: new THREE.Vector2(1, 1) } };
const sky = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), new THREE.ShaderMaterial({
  depthWrite: false, depthTest: false,
  uniforms: { get uTime() { return U.uTime; }, get uDorm() { return U.uDorm; }, get uSkN() { return U.uSkN; }, uRes: skyU.uRes },
  vertexShader: `varying vec2 vUv; void main(){ vUv=uv; gl_Position=vec4(position.xy,0.9999,1.0); }`,
  fragmentShader: `uniform float uTime,uDorm; uniform vec2 uRes; uniform vec3 uSkN; varying vec2 vUv;
    float h21(vec2 p){ p=fract(p*vec2(123.34,456.21)); p+=dot(p,p+45.32); return fract(p.x*p.y); }
    float noise(vec2 p){ vec2 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f);
      return mix(mix(h21(i),h21(i+vec2(1,0)),f.x),mix(h21(i+vec2(0,1)),h21(i+vec2(1,1)),f.x),f.y); }
    float fbm(vec2 p){ float v=0.0,a=0.5; mat2 m=mat2(1.6,1.2,-1.2,1.6); for(int i=0;i<5;i++){ v+=a*noise(p); p=m*p; a*=0.5; } return v; }
    /* one layer of stars: few per cell, brightness on a steep power law so most are faint and a handful shine */
    vec3 stars(vec2 p, float scale, float density, float px){
      vec2 g=p*scale, id=floor(g), f=fract(g);
      float h=h21(id); if(h>density) return vec3(0.0);
      vec2 o=vec2(h21(id+7.1),h21(id+3.7))*0.4+0.3;
      float d=length(f-o)/scale;                                  /* distance in screen units */
      float b=pow(h21(id+5.3),9.0);                               /* 0..1, mostly tiny */
      float core=smoothstep(px*(1.0+2.2*b),0.0,d);
      float halo=b*b*exp(-d*d/(px*px*18.0))*0.5;                /* only bright stars get a soft glow */
      float tw=0.8+0.2*sin(uTime*(0.4+h*3.0)+h*60.0);
      float t=h21(id+1.3);
      vec3 c=t<0.15?vec3(1.0,0.78,0.55):t<0.35?vec3(1.0,0.93,0.85):vec3(0.75,0.85,1.0);
      return c*(core*(0.25+1.6*b)+halo)*tw;
    }
    void main(){
      vec2 p=(vUv-0.5)*vec2(uRes.x/uRes.y,1.0);
      float px=1.0/uRes.y;
      vec2 q=p*1.4+vec2(uTime*0.004,uTime*0.0015);
      float bandY=p.y+0.28*p.x-0.05+0.06*sin(p.x*3.0);
      float band=exp(-bandY*bandY*14.0);                          /* a faint Milky Way across the frame */
      float clump=fbm(q*2.3+3.0);
      float dust=smoothstep(0.5,0.75,fbm(q*3.5+11.0));
      vec3 col=vec3(0.0,0.0,0.004);
      float glow=band*pow(clump,2.2)*(1.0-dust*0.85);
      col+=uSkN*vec3(0.035,0.04,0.07)*glow*1.8;
      col+=uSkN*vec3(0.06,0.025,0.06)*pow(fbm(q*0.8+21.0),4.0)*band*1.5;   /* faint purple-brown nebulosity */
      col+=uSkN*vec3(0.015,0.03,0.06)*pow(fbm(q*0.6+40.0),5.0);            /* very faint blue clouds elsewhere */
      float rich=0.12+band*(1.0-dust)*2.4*clump;                      /* stars crowd into the band, thin out elsewhere */
      col*=mix(0.3,1.0,uDorm);
      gl_FragColor=vec4(col,1.0);
    }`
}));
sky.frustumCulled = false; sky.renderOrder = -100; scene.add(sky);

/* stars: randomly scattered points far behind the core, crowding into the Milky Way band; brightness falls off
   steeply so most are faint pinpricks and only a few shine with a soft glow */
const starsU = { uPixel: { value: DPR } };
{
  const n = small ? 1000 : 2300, pos = [], size = [], col = [], seed = [];
  const gauss = () => (Math.random() + Math.random() + Math.random() + Math.random() - 2) / 2;
  for (let i = 0; i < n; i++) {
    let x, y;
    if (Math.random() < 0.55) { x = (Math.random() * 2 - 1) * 30; y = -0.28 * x + 1.6 + gauss() * 4.5; }   /* in the band */
    else { x = (Math.random() * 2 - 1) * 30; y = (Math.random() * 2 - 1) * 19; }
    pos.push(x, y, -40 - Math.random() * 10);
    const b = Math.pow(Math.random(), 7);
    size.push(1.1 + b * 7.5);
    const t = Math.random(), k = 0.45 + 0.75 * Math.pow(Math.random(), 1.5) + b;
    const c = t < 0.12 ? [1.0, 0.75, 0.5] : t < 0.35 ? [1.0, 0.93, 0.84] : [0.74, 0.84, 1.0];
    col.push(c[0] * k, c[1] * k, c[2] * k); seed.push(Math.random());
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setAttribute('aSize', new THREE.Float32BufferAttribute(size, 1));
  g.setAttribute('aCol', new THREE.Float32BufferAttribute(col, 3)); g.setAttribute('aSeed', new THREE.Float32BufferAttribute(seed, 1));
  const m = new THREE.ShaderMaterial({
    transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    uniforms: { uPixel: starsU.uPixel, get uTime() { return U.uTime; }, get uDorm() { return U.uDorm; } },
    vertexShader: `uniform float uPixel,uTime,uDorm; attribute float aSize; attribute vec3 aCol; attribute float aSeed; varying vec3 vC; varying float vBig;
      void main(){ gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); gl_PointSize=aSize*uPixel;
        vBig=smoothstep(2.5,7.0,aSize); vC=aCol*(0.82+0.18*sin(uTime*(0.3+aSeed*2.0)+aSeed*50.0))*mix(0.35,1.0,uDorm); }`,
    fragmentShader: `varying vec3 vC; varying float vBig; void main(){ vec2 d=gl_PointCoord-0.5; float r=length(d)*2.0; if(r>1.0) discard;
      float core=exp(-r*r*mix(9.0,40.0,vBig)); float halo=vBig*exp(-r*r*5.0)*0.25; gl_FragColor=vec4(vC*(core+halo),1.0); }`
  });
  const pts = new THREE.Points(g, m); pts.renderOrder = -90; scene.add(pts);
}

/* ---- silk core v3: one main woven ring made of flat ribbons, each ribbon a sheet of thousands of hair-thin fibers ---- */
const RIB = [
  // R0, wobble, height, k | width, twist, phase, flowDir | color
  [[1.55, 0.12, 0.34, 2], [0.62, 1, 0.0, 1], [1.0, 0.46, 0.18]],
  [[1.46, 0.18, 0.50, 3], [0.42, 2, 1.7, -1], [0.32, 0.68, 1.0]],
  [[1.64, 0.10, 0.28, 2], [0.34, 1, 3.1, 1], [0.55, 0.82, 1.0]],
  [[1.36, 0.20, 0.55, 3], [0.30, 1, 4.4, -1], [1.0, 0.55, 0.26]],
  [[1.72, 0.08, 0.22, 1], [0.24, 2, 5.2, 1], [0.40, 0.74, 1.0]],
  [[1.50, 0.15, 0.44, 3], [0.36, 1, 2.3, 1], [1.0, 0.62, 0.36]],
  [[1.30, 0.22, 0.60, 2], [0.22, 2, 0.9, -1], [0.30, 0.62, 1.0]],
  [[1.58, 0.12, 0.40, 3], [0.26, 1, 3.8, 1], [1.0, 0.50, 0.22]]
];
const NR = RIB.length;
const ribRot = [[0, 0, 0], [0.22, -0.12, 0.8], [-0.18, 0.16, 1.7], [0.14, 0.24, 2.6], [-0.16, -0.2, 3.5], [0.26, 0.08, 4.4], [-0.1, 0.3, 5.2], [0.2, -0.26, 6.0]]
  .map(([x, y, z]) => new THREE.Matrix3().setFromMatrix4(new THREE.Matrix4().makeRotationFromEuler(new THREE.Euler(x, y, z))));
const U = {
  uTime: { value: 0 }, uSpeed: { value: 0.3 }, uPhase: { value: 0 }, uRoll: { value: 0 }, uMob: { value: 0 }, uTurb: { value: 0.2 }, uExpand: { value: 0.02 },
  uBright: { value: 1.0 }, uWarm: { value: 0.5 }, uVib: { value: 0 }, uPixel: { value: DPR }, uGain: { value: 1.0 }, uDorm: { value: 1.0 }, uLine: { value: DPR > 1.4 ? 1.8 : 1.0 },
  uA: { value: RIB.map(r => new THREE.Vector4(...r[0])) }, uB: { value: RIB.map(r => new THREE.Vector4(...r[1])) },
  uC: { value: RIB.map(r => new THREE.Vector3(...r[2])) }, uR: { value: ribRot },
  uSkW: { value: new THREE.Vector3(1.0, 0.58, 0.30) }, uSkC: { value: new THREE.Vector3(0.55, 0.82, 1.0) }, uSkN: { value: new THREE.Vector3(1, 1, 1) }
};
const CORE_GLSL = `
  uniform float uTime,uSpeed,uTurb,uExpand,uBright,uWarm,uVib,uPixel,uGain,uLine,uPhase,uRoll,uMob;
  uniform vec4 uA[8]; uniform vec4 uB[8]; uniform vec3 uC[8]; uniform mat3 uR[8]; uniform vec3 uSkW,uSkC;
  attribute vec4 aSeed; attribute float aT; attribute float aB;
  varying vec3 vC; varying float vA;
  float gT, gU; vec3 gN, gNw;
  vec3 corePos(float tOff){
    int b=int(aB+0.5); vec4 A=uA[b]; vec4 Bp=uB[b];
    /* uPhase is the flow already integrated over time, so a speed change never makes the ring jump */
    float t=aT*6.2831853+tOff+uPhase*0.35*Bp.w;
    float R=A.x*(1.0+A.y*sin(2.0*t+Bp.z));
    vec3 c=vec3(cos(t)*R,sin(t)*R,A.z*sin(A.w*t+Bp.z));
    vec3 rad=vec3(cos(t),sin(t),0.0), up=vec3(0.0,0.0,1.0);
    float phi=Bp.y*t+Bp.z+uTime*0.18*Bp.w;
    vec3 d=cos(phi)*rad+sin(phi)*up;
    vec3 n=-sin(phi)*rad+cos(phi)*up;
    float u=aSeed.x*2.0-1.0;
    float wid=Bp.x*(0.7+0.45*sin(t*2.0+Bp.z*1.3+uTime*0.35));
    vec3 p=c+d*u*wid+n*((aSeed.y-0.5)*0.035*(0.4+abs(u))+0.018*sin(t*7.0+aSeed.z*30.0+uTime*0.6));
    float tt=uTime*0.8;
    p+=uTurb*0.1*vec3(sin(p.y*2.6+tt+Bp.z),sin(p.z*2.2+tt*1.1+Bp.z),sin(p.x*2.4+tt*0.9));
    /* voice: fine fast tremor across the sheet; the shape never changes */
    p+=n*uVib*(0.012*sin(uTime*58.0+t*26.0+aSeed.z*6.0)+0.007*sin(uTime*97.0+aSeed.x*40.0));
    /* thinking / speaking: every ribbon gathers into one Mobius band (a single half twist) and
       the fibres flow along it in the same direction while the twist slowly rolls round */
    float tm=aT*6.2831853+tOff+uPhase*0.35;
    vec3 radm=vec3(cos(tm),sin(tm),0.0);
    float ph=0.5*tm+uRoll;
    vec3 dm=cos(ph)*radm+sin(ph)*up;
    vec3 nm=-sin(ph)*radm+cos(ph)*up;
    float lay=(float(b)-3.5)/3.5;
    /* fibres bunch into fine parallel strands across the band, like a woven tape */
    float uq=(floor(aSeed.x*9.0)+0.5)/9.0*2.0-1.0;
    float um=mix(u,uq,0.75);
    vec3 pm=radm*(1.55+0.03*sin(tm*3.0+uTime*0.4))+dm*um*(0.40+0.04*lay)
           +nm*((aSeed.y-0.5)*0.03*(0.4+abs(um))+lay*0.02+0.01*sin(tm*7.0+aSeed.z*30.0+uTime*0.5));
    pm+=nm*uVib*(0.014*sin(uTime*58.0+tm*26.0+aSeed.z*6.0)+0.008*sin(uTime*97.0+aSeed.x*40.0));
    /* tip the band towards the viewer so its half twist reads clearly */
    const float TI=0.62; mat3 tip=mat3(1.0,0.0,0.0, 0.0,cos(TI),sin(TI), 0.0,-sin(TI),cos(TI));
    pm=tip*pm; nm=tip*nm;
    float m=smoothstep(0.0,1.0,clamp(uMob*1.15-aSeed.z*0.15,0.0,1.0));   /* fibres arrive a little staggered */
    gT=mix(t,tm,m); gU=u; gN=n;
    gNw=normalize(mix(uR[b]*n,nm,m));
    return mix(uR[b]*p,pm,m)*uExpand;
  }
  float depthFade(vec4 mv){ float z=clamp((-mv.z-5.6)/3.4,0.0,1.0); return mix(1.35,0.28,z); }
  vec3 ribColor(){
    int b=int(aB+0.5); vec3 c=uC[b];
    vec3 warm=uSkW, cool=uSkC;
    c=mix(c,(uWarm>0.5?warm:cool),abs(uWarm-0.5)*0.9);
    return c*(0.85+0.3*aSeed.w);
  }`;

const FIBERS = small ? 2600 : 9600, SEGV = small ? 120 : 200;
const ribW = RIB.map(r => r[1][0]); const ribSum = ribW.reduce((a, b) => a + b, 0);
function buildFibers() {
  const n = FIBERS * SEGV;
  const sd = new Float32Array(n * 4), t = new Float32Array(n), b = new Float32Array(n), idx = new Uint32Array(n * 2);
  let v = 0, e = 0, f = 0;
  for (let B = 0; B < NR; B++) {
    const count = B === NR - 1 ? FIBERS - f : Math.round(FIBERS * ribW[B] / ribSum);
    for (let s = 0; s < count; s++, f++) {
      const u = Math.random(); const r = [0.5 + (u - 0.5) * (0.6 + 0.4 * Math.random()) * 1.6, Math.random(), Math.random(), Math.random()];
      r[0] = Math.min(1, Math.max(0, r[0]));
      const start = v;
      for (let p = 0; p < SEGV; p++) { sd.set(r, v * 4); t[v] = p / SEGV; b[v] = B; idx[e++] = v; idx[e++] = p === SEGV - 1 ? start : v + 1; v++; }
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(n * 3), 3));
  g.setAttribute('aSeed', new THREE.BufferAttribute(sd, 4)); g.setAttribute('aT', new THREE.BufferAttribute(t, 1)); g.setAttribute('aB', new THREE.BufferAttribute(b, 1));
  g.setIndex(new THREE.BufferAttribute(idx, 1)); g.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 10);
  return g;
}
const fiberMat = new THREE.ShaderMaterial({
  uniforms: U, transparent: true, depthWrite: false, depthTest: false, blending: THREE.AdditiveBlending,
  vertexShader: CORE_GLSL + `
    void main(){
      vec3 p=corePos(0.0);
      vec4 mv=modelViewMatrix*vec4(p,1.0);
      gl_Position=projectionMatrix*mv;
      int b=int(aB+0.5);
      /* sheen bands that travel along each ribbon */
      float s=pow(0.5+0.5*sin(gT*2.0-uPhase*2.4*mix(uB[b].w,1.0,uMob)+aSeed.z*0.9),5.0);
      float edge=0.75+0.7*pow(abs(gU),4.0);
      vec3 col=ribColor();
      vC=mix(col,vec3(1.0),s*0.12)*uBright;
      vA=0.011*(0.55+0.9*aSeed.w)*(0.3+1.6*s)*edge*depthFade(mv)*(1.0+uVib*0.2)*uGain*uLine*mix(1.0,0.5,uMob);
    }`,
  fragmentShader: `varying vec3 vC; varying float vA; void main(){ gl_FragColor=vec4(vC*vA,1.0); }`
});
const fibers = new THREE.LineSegments(buildFibers(), fiberMat);
fibers.scale.setScalar(1.05);
world.add(fibers);

/* sparkles riding the ribbons, a few sprayed off the edges */
const SPK = small ? 25000 : 70000;
const spG = new THREE.BufferGeometry();
{
  const sd = new Float32Array(SPK * 4), t = new Float32Array(SPK), b = new Float32Array(SPK);
  for (let i = 0; i < SPK; i++) { sd.set([Math.random(), Math.random(), Math.random(), Math.random()], i * 4); t[i] = Math.random(); b[i] = Math.floor(Math.random() * NR); }
  spG.setAttribute('position', new THREE.BufferAttribute(new Float32Array(SPK * 3), 3));
  spG.setAttribute('aSeed', new THREE.BufferAttribute(sd, 4)); spG.setAttribute('aT', new THREE.BufferAttribute(t, 1)); spG.setAttribute('aB', new THREE.BufferAttribute(b, 1));
  spG.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 10);
}
const sparkMat = new THREE.ShaderMaterial({
  uniforms: U, transparent: true, depthWrite: false, depthTest: false, blending: THREE.AdditiveBlending,
  vertexShader: CORE_GLSL + `
    void main(){
      vec3 p=corePos(uPhase*0.25*(0.5+aSeed.y));
      int b=int(aB+0.5);
      float spray=step(0.86,aSeed.w);
      float life=fract(uTime*0.12*(0.5+aSeed.z)+aSeed.y*7.0);
      p+=gNw*(sign(gU)+0.3)*spray*life*0.55*uExpand*(1.0-0.6*uMob);
      vec4 mv=modelViewMatrix*vec4(p,1.0);
      gl_Position=projectionMatrix*mv;
      float tw=pow(0.5+0.5*sin(uTime*(2.0+aSeed.w*5.0)+aSeed.z*80.0),10.0);
      gl_PointSize=(0.9+tw*2.6+spray*0.8)*uPixel*(4.2/-mv.z);
      vC=mix(ribColor(),vec3(1.0),0.35+tw*0.5)*uBright;
      vA=(0.06+tw*0.8)*mix(1.0,1.0-life,spray)*depthFade(mv)*uGain;
    }`,
  fragmentShader: `varying vec3 vC; varying float vA; void main(){ vec2 d=gl_PointCoord-0.5; float r=dot(d,d)*4.0; if(r>1.0) discard; gl_FragColor=vec4(vC*vA*exp(-r*3.0),1.0); }`
});
const core = new THREE.Points(spG, sparkMat);
core.scale.setScalar(1.05);
world.add(core);

/* out-of-focus bokeh in front of and behind the core */
{
  const n = small ? 40 : 110, a = new Float32Array(n * 3), s = new Float32Array(n), c = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const front = Math.random() < 0.35, low = !front && Math.random() < 0.55;
    a.set(low ? [(Math.random() * 2 - 1) * 4.5, -1.6 - Math.random() * 1.4, -0.5 - Math.random() * 3] : [(Math.random() * 2 - 1) * (front ? 3.2 : 6), (Math.random() * 2 - 1) * (front ? 2.0 : 3.5), front ? 2.2 + Math.random() * 2.4 : -2 - Math.random() * 4], i * 3);
    s[i] = Math.random(); c[i] = Math.random();
  }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(a, 3)); g.setAttribute('aS', new THREE.BufferAttribute(s, 1)); g.setAttribute('aC', new THREE.BufferAttribute(c, 1));
  scene.add(new THREE.Points(g, new THREE.ShaderMaterial({
    uniforms: { uTime: U.uTime, uPixel: U.uPixel, uDorm: U.uDorm, uSkW: U.uSkW, uSkC: U.uSkC }, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    vertexShader: `uniform float uTime,uPixel,uDorm; uniform vec3 uSkW,uSkC; attribute float aS; attribute float aC; varying float vA; varying vec3 vCol;
      void main(){ vec3 p=position; p.x+=sin(uTime*0.07+aS*20.0)*0.2; p.y+=cos(uTime*0.05+aS*30.0)*0.15; vec4 mv=modelViewMatrix*vec4(p,1.0); gl_Position=projectionMatrix*mv;
        float dist=abs(-mv.z-7.2); gl_PointSize=clamp(5.0+dist*6.0,5.0,42.0)*uPixel*(0.6+aS*0.8); vA=(0.06+0.09*aS)/(1.0+dist*0.25)*(1.0-0.9*uDorm);
        vCol=mix((uSkC*vec3(0.909,0.976,1.000)),(uSkW*vec3(1.000,1.069,1.167)),step(0.45,aC)); }`,
    fragmentShader: `varying float vA; varying vec3 vCol; void main(){ vec2 d=gl_PointCoord-0.5; float r=length(d)*2.0; if(r>1.0) discard; float a=smoothstep(1.0,0.75,r)*(0.55+0.45*smoothstep(0.6,0.95,r)); gl_FragColor=vec4(vCol*a*vA,1.0); }`
  })));
}

/* ground: scattered city-like lights far below */
{
  const n = small ? 1500 : 4500, a = new Float32Array(n * 3), s = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const street = Math.random() < 0.6; const lane = Math.floor(Math.random() * 14) - 7;
    const x = street ? lane * 1.1 + (Math.random() - 0.5) * 0.08 : (Math.random() * 2 - 1) * 9;
    const z = street && Math.random() < 0.5 ? (Math.random() * 2 - 1) * 9 : -9 + Math.random() * 11;
    a.set([x, -2.0 + Math.random() * 0.08, z], i * 3); s[i] = Math.random();
  }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(a, 3)); g.setAttribute('aS', new THREE.BufferAttribute(s, 1));
  scene.add(new THREE.Points(g, new THREE.ShaderMaterial({
    uniforms: { uTime: U.uTime, uPixel: U.uPixel, uDorm: U.uDorm, uSkW: U.uSkW, uSkC: U.uSkC }, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    vertexShader: `uniform float uTime,uPixel,uDorm; uniform vec3 uSkW,uSkC; attribute float aS; varying float vA; varying vec3 vCol;
      void main(){ vec4 mv=modelViewMatrix*vec4(position,1.0); gl_Position=projectionMatrix*mv; gl_PointSize=(0.8+aS*1.8)*uPixel*(5.0/-mv.z);
        vA=(0.3+0.9*pow(0.5+0.5*sin(uTime*(0.4+aS)+aS*90.0),6.0))*smoothstep(16.0,4.0,-mv.z)*(1.0-uDorm);
        vCol=mix((uSkC*vec3(0.818,0.915,0.950)),(uSkW*vec3(1.000,1.207,1.500)),step(0.7,aS)); }`,
    fragmentShader: `varying float vA; varying vec3 vCol; void main(){ vec2 d=gl_PointCoord-0.5; if(dot(d,d)>0.25) discard; gl_FragColor=vec4(vCol*vA,1.0); }`
  })));
}

/* dust + rain */
function pointsLayer(count, fill, size, color, opacity) {
  const g = new THREE.BufferGeometry(); const a = new Float32Array(count * 3); const sd = new Float32Array(count);
  for (let i = 0; i < count; i++) { fill(a, i); sd[i] = Math.random(); }
  g.setAttribute('position', new THREE.BufferAttribute(a, 3)); g.setAttribute('aS', new THREE.BufferAttribute(sd, 1));
  return g;
}
const dustG = pointsLayer(small ? 1500 : 4000, (a, i) => { const r = 2.6 + Math.random() * 5; const u = Math.random() * 2 - 1, t = Math.random() * 6.283; const s = Math.sqrt(1 - u * u); a.set([r * s * Math.cos(t), r * u * 0.7, r * s * Math.sin(t) - 1], i * 3); });
const dustM = new THREE.ShaderMaterial({ uniforms: { uTime: U.uTime, uPixel: U.uPixel, uDorm: U.uDorm, uSkC: U.uSkC }, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
  vertexShader: `uniform float uTime,uPixel,uDorm; attribute float aS; varying float vA; void main(){ vec3 p=position; p.y+=sin(uTime*0.2+aS*30.0)*0.08; vec4 mv=modelViewMatrix*vec4(p,1.); gl_Position=projectionMatrix*mv; gl_PointSize=(0.6+aS*1.6)*uPixel*(4.0/-mv.z); vA=(0.12+0.4*pow(0.5+0.5*sin(uTime*(0.5+aS*2.)+aS*50.),6.))*(1.0-0.75*uDorm); }`,
  fragmentShader: `uniform vec3 uSkC; varying float vA; void main(){ vec2 d=gl_PointCoord-0.5; if(dot(d,d)>0.25) discard; gl_FragColor=vec4((uSkC*vec3(1.000,0.976,0.950))*vA,1.); }` });
scene.add(new THREE.Points(dustG, dustM));

const rainLines = []; const rainPts = new Float32Array((small ? 60 : 160) * 3);
const rainSeeds = [];
for (let i = 0; i < rainPts.length / 3; i++) {
  const x = (Math.random() * 2 - 1) * 3.1, z = -1.5 - Math.random() * 3, top = Math.random() * 3.5 - 0.5, h = 0.6 + Math.random() * 3.2;
  rainLines.push(x, top, z, x, top - h, z); rainSeeds.push({ x, z, top, h, sp: 0.2 + Math.random() * 0.6, o: Math.random() });
}
const rlG = new THREE.BufferGeometry(); rlG.setAttribute('position', new THREE.Float32BufferAttribute(rainLines, 3));
const rainM = new THREE.LineBasicMaterial({ color: 0x6fc6e8, transparent: true, opacity: 0.15, blending: THREE.AdditiveBlending, depthWrite: false });
scene.add(new THREE.LineSegments(rlG, rainM));
const rpG = new THREE.BufferGeometry(); rpG.setAttribute('position', new THREE.BufferAttribute(rainPts, 3));
const rpM = new THREE.PointsMaterial({ color: 0xbfeeff, size: 0.05, transparent: true, opacity: 0.8, blending: THREE.AdditiveBlending, depthWrite: false });
scene.add(new THREE.Points(rpG, rpM));

/* orbits + nodes */
const orbits = new THREE.Group(); world.add(orbits);
const orbitDefs = [[2.15, 2.0, 0.25, 0.2, 0xffb27a], [2.4, 2.25, -0.3, -0.5, 0x7fdbff], [1.95, 1.85, 0.45, 0.9, 0xffc79a], [2.6, 2.3, 0.1, 2.2, 0x9fe3ff]];
const nodes = [];
orbitDefs.forEach(([rx, ry, tiltX, tiltZ, color], i) => {
  const pts = []; for (let j = 0; j <= 256; j++) { const a = j / 256 * Math.PI * 2; pts.push(new THREE.Vector3(Math.cos(a) * rx, Math.sin(a) * ry, 0)); }
  const g = new THREE.BufferGeometry().setFromPoints(pts);
  const l = new THREE.Line(g, new THREE.LineBasicMaterial({ color: color || 0xffb27a, transparent: true, opacity: 0.22, blending: THREE.AdditiveBlending, depthWrite: false }));
  const holder = new THREE.Group(); holder.rotation.set(tiltX, 0, tiltZ); holder.add(l); orbits.add(holder);
  const m = new THREE.Mesh(new THREE.SphereGeometry(0.022, 12, 12), new THREE.MeshBasicMaterial({ color: i % 2 ? 0xbfeeff : 0xffd2a8 }));
  holder.add(m); nodes.push({ m, rx, ry, a: Math.random() * 6.28, sp: 0.04 + Math.random() * 0.05 });
});
const labelBox = $('#labels');
nodes.forEach(n => { n.m.visible = false; });
const labels = nodes.map((n, i) => { const s = document.createElement('span'); if (i % 2 === 0) s.className = 'warm'; labelBox.appendChild(s); return s; });
const linkPath = $('#linkPath'); let linkDash = 0;
function relabel() { labels.forEach(s => { s.textContent = (Math.random() * 2 - 1).toFixed(2); }); }
relabel(); setInterval(relabel, 2600);
