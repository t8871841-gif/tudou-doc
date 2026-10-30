'use strict';
/* <<core:ion */
/* 回路 · Jarvis core (Claude, 2026-10-29). Rewritten for the Jarvis scene after studying the ION theme: a circuit wall of six
   decision columns, tangled plasma traces between the cards, plasma buses in the gaps, an ion orb that walks the decision path.
   Here the six columns are how Jarvis actually handles a request: 输入 (what came in) -> 理解 (what it means) -> 推理 (the
   brain) -> 决策 (which ability) -> 闸门 (safety and privacy checks) -> 执行 (what runs), and every card is a real part of Jarvis.
   idle = the orb waits on the wake-word card; listening = it moves to the speech card and the buses pulse with the mic level;
   heard = it crosses to 理解; thinking = it sits on the brain while side links flicker between cards; an ability put to use =
   it walks 决策 -> 闸门 (passes the check) -> 执行 in that ability's colour; speaking = it reaches 语音回答 and beats with the voice.
   After a quiet spell the path fades and the orb glides back to wait for "Hey Jarvis". */
SKCORES.ion = function () {
  const TAU = Math.PI * 2, WS = 0.62;                                   /* wall units -> world units */
  const lite = typeof small !== 'undefined' && small;
  const grp = new THREE.Group(); scene.add(grp);
  const tilt = new THREE.Group(); grp.add(tilt);
  const wall = new THREE.Group(); wall.scale.setScalar(WS); tilt.add(wall);
  const rng = skSeeded(20261030), lerp = (a, b, t) => a + (b - a) * t, clamp = (v, a, b) => v < a ? a : v > b ? b : v;
  const easeIO = (t) => (t = clamp(t, 0, 1), t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
  const mix3 = (a, b, t) => [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)];
  const WHITE = [1, 1, 1], C0 = skHex('#ff3d1f'), FIL = skHex('#ffd23f'), COOL = [0.55, 0.78, 1], PASS = skHex('#6ff0c0'), AMBER = skHex('#ffa31a');
  const COLS = 6, ROWS = 6, PX = 1.0, CW = 0.74, CH = 0.28, ROW = 0.36;
  const colX = (c) => (c - 2.5) * PX, gapX = (g) => colX(g) + PX / 2, rowY = (r) => (2.5 - r) * ROW;

  /* ---- the cards: what Jarvis really does at each step ---- */
  const STAGE = [['INPUT', '输入'], ['PARSE', '理解'], ['REASON', '推理'], ['DECIDE', '决策'], ['GATE', '闸门'], ['EXECUTE', '执行']];
  const CARD = [
    [['唤醒词', 'WAKE WORD', 'Hey Jarvis'], ['语音', 'SPEECH', '麦克风'], ['文字', 'TEXT', '输入框'], ['手势', 'GESTURE', '摄像头'], ['系统', 'SYSTEM', 'CPU · 内存 · 网络'], ['定时', 'TIMER', '日程提醒']],
    [['转写', 'TRANSCRIBE', 'Parakeet · SenseVoice'], ['语种', 'LANGUAGE', '中 / 英'], ['声纹', 'VOICEPRINT', '是不是你'], ['意图', 'INTENT', '想让我做什么'], ['上下文', 'CONTEXT', '多轮对话'], ['打断', 'BARGE-IN', 'Stop · Wait']],
    [['大脑', 'BRAIN', 'DeepSeek'], ['记忆', 'MEMORY', '最近的对话'], ['规划', 'PLAN', '拆成步骤'], ['工具', 'TOOLS', '选哪项能力'], ['翻译', 'TRANSLATE', '中文字幕'], ['直接回答', 'ANSWER', '不用工具']],
    [['天气', 'WEATHER', '今天 · 三天'], ['导航', 'ROUTE', '路线 · 路况'], ['日程', 'SCHEDULE', '今日安排'], ['米家', 'HOME', '灯 · 晾衣架 · 风扇'], ['全息', 'HOLOGRAM', '模型 · 演示'], ['工坊', 'HARNESS', '文档 · 程序 · 图片']],
    [['安全清单', 'SAFETY', '门锁 · 电磁炉不可控'], ['本地优先', 'LOCAL', '数据不出门'], ['需要确认', 'CONFIRM', '等你点头'], ['置信度', 'CONFIDENCE', '≥ 0.85'], ['密钥', 'SECRETS', '只在本机'], ['限速重试', 'RETRY', '失败自动重试']],
    [['高德', 'AMAP', '天气 · 路线'], ['日程表', 'PLANNER', '本地文件'], ['米家设备', 'MIJIA', '设备指令'], ['全息投影', 'HOLO', '粒子模型'], ['工坊作品', 'HARNESS', '桌面 · Jarvis 作品'], ['语音回答', 'VOICE', 'Jarvis 说话']]
  ];
  const NK = COLS * ROWS, K = (c, r) => c * ROWS + r;
  /* an ability put to use -> its rows in 推理 / 决策 / 闸门 / 执行 */
  const ROUTE = { weather: [3, 0, 3, 0], nav: [3, 1, 3, 0], plan: [2, 2, 1, 1], home: [3, 3, 0, 2], holo: [2, 4, 3, 3], harness: [2, 5, 2, 4], skin: [3, 4, 1, 3] };
  const ATLAS_W = 512, ATLAS_H = 194;
  const atlas = document.createElement('canvas'); atlas.width = ATLAS_W * COLS; atlas.height = ATLAS_H * ROWS;
  { const x = atlas.getContext('2d'); x.fillStyle = '#fff';
    for (let c = 0; c < COLS; c++) for (let r = 0; r < ROWS; r++) { const ox = c * ATLAS_W, oy = r * ATLAS_H, [zh, en, val] = CARD[c][r];
      x.globalAlpha = 0.55; x.font = '500 21px "JetBrains Mono",Menlo,monospace'; x.textBaseline = 'alphabetic';
      x.fillText(`0${c + 1} ${STAGE[c][0]} · ${STAGE[c][1]}`, ox + 26, oy + 40);
      x.globalAlpha = 1; x.font = '600 60px "PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif'; x.fillText(zh, ox + 24, oy + 112);
      const w = x.measureText(zh).width; x.globalAlpha = 0.7; x.font = '500 22px Michroma,"JetBrains Mono",monospace'; x.fillText(en, ox + 40 + w, oy + 110);
      x.globalAlpha = 0.6; x.font = '400 26px "PingFang SC","Microsoft YaHei",sans-serif'; x.fillText(val, ox + 26, oy + 160);
      x.globalAlpha = 0.5; x.fillRect(ox + ATLAS_W - 70, oy + 30, 40, 3); x.fillRect(ox + ATLAS_W - 50, oy + 38, 20, 3); } }
  const atlasT = new THREE.CanvasTexture(atlas); atlasT.anisotropy = 4;
  const FU = { uTime: { value: 0 }, uPixel: U.uPixel, uFade: { value: 0 }, uZoomP: { value: 1 }, uLit: { value: new Float32Array(NK) }, uSpi: { value: new Float32Array(COLS - 1).fill(0.5) },
    uHi: { value: new THREE.Vector3(1, 0.82, 0.25) }, uC0: { value: new THREE.Vector3(...C0) }, uFil: { value: new THREE.Vector3(...FIL) }, uTex: { value: atlasT },
    uFlow: { value: 0 }, uBus: { value: 0.5 }, uEnergy: { value: 0.5 } };

  /* dark card backs (normal blending) so the traces pass behind the cards' text */
  { const v = [], idx = []; for (let c = 0; c < COLS; c++) for (let r = 0; r < ROWS; r++) { const x0 = colX(c) - CW / 2, x1 = colX(c) + CW / 2, y0 = rowY(r) - CH / 2, y1 = rowY(r) + CH / 2, o = v.length / 3;
      v.push(x0, y0, 0, x1, y0, 0, x1, y1, 0, x0, y1, 0); idx.push(o, o + 1, o + 2, o, o + 2, o + 3); }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(v, 3)); g.setIndex(idx);
    const m = new THREE.Mesh(g, new THREE.MeshBasicMaterial({ color: 0x07050b, transparent: true, opacity: 0.6, depthWrite: false, depthTest: false })); m.renderOrder = 897; wall.add(m); }
  /* the cards: rim + faint fill + their text, lit by the decision path */
  { const v = [], uv = [], lo = [], kk = [], idx = [];
    for (let c = 0; c < COLS; c++) for (let r = 0; r < ROWS; r++) { const x0 = colX(c) - CW / 2, x1 = colX(c) + CW / 2, y0 = rowY(r) - CH / 2, y1 = rowY(r) + CH / 2, o = v.length / 3;
      const u0 = c / COLS, u1 = (c + 1) / COLS, w0 = 1 - (r + 1) / ROWS, w1 = 1 - r / ROWS;
      v.push(x0, y0, 0.002, x1, y0, 0.002, x1, y1, 0.002, x0, y1, 0.002); uv.push(u0, w0, u1, w0, u1, w1, u0, w1); lo.push(0, 0, 1, 0, 1, 1, 0, 1);
      for (let q = 0; q < 4; q++) kk.push(K(c, r)); idx.push(o, o + 1, o + 2, o, o + 2, o + 3); }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(v, 3)); g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    g.setAttribute('aL', new THREE.Float32BufferAttribute(lo, 2)); g.setAttribute('aK', new THREE.Float32BufferAttribute(kk, 1)); g.setIndex(idx);
    const m = new THREE.Mesh(g, skAdd(FU, `uniform float uLit[${NK}]; attribute vec2 aL; attribute float aK; varying vec2 vUv,vL; varying float vLit;
        void main(){ vUv=uv; vL=aL; vLit=uLit[int(aK+0.5)]; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
      `uniform sampler2D uTex; uniform vec3 uHi,uC0; uniform float uFade,uTime; varying vec2 vUv,vL; varying float vLit;
        void main(){ float asp=${(CW / CH).toFixed(3)}; vec2 q=vL; float e=min(min(q.x,1.0-q.x)*asp,min(q.y,1.0-q.y));
          float rim=exp(-e*e/0.0007), l=clamp(vLit,0.0,1.4);
          float corner=step(min(q.x,1.0-q.x)*asp,0.06)*step(min(q.y,1.0-q.y),0.06)*exp(-e*e/0.003);
          float scan=l>0.6?exp(-pow((q.x-fract(uTime*0.6))*asp/0.08,2.0))*0.25:0.0;
          float tx=texture2D(uTex,vUv).a;
          vec3 frame=mix(uC0*0.55,uHi,min(l,1.0));
          vec3 c=frame*(rim*(0.22+l*0.9)+corner*0.5+0.035+l*0.07+scan)+mix(vec3(0.62,0.6,0.66),mix(uHi,vec3(1.0),0.55),min(l,1.0))*tx*(0.42+l*0.75);
          gl_FragColor=vec4(c*uFade,1.0); }`));
    m.renderOrder = 903; wall.add(m); }

  /* ---- tangled traces between the cards (static curves, each with its own flicker and a spark that runs along it) ---- */
  const TV = [], TU = [], TP = [], TC = [], TW = [], TG = [];
  { const bez = (P, u) => { const v = 1 - u; return [v * v * v * P[0] + 3 * v * v * u * P[2] + 3 * v * u * u * P[4] + u * u * u * P[6], v * v * v * P[1] + 3 * v * v * u * P[3] + 3 * v * u * u * P[5] + u * u * u * P[7]]; };
    for (let g = 0; g < COLS; g++) for (let r = 0; r < ROWS; r++) { const nL = (lite ? 2 : 3) + Math.floor(rng() * 3);
      for (let j = 0; j < nL; j++) { const hs = rng(), ty = rng(), ya = rowY(r) + (rng() - 0.5) * CH * 0.8; let P, gi;
        if (ty < 0.42) { const sd = g === 0 ? 1 : g === 5 ? -1 : rng() < 0.5 ? -1 : 1, dd = Math.min(ROWS - 1 - r, 1 + Math.floor(rng() * 3)); if (dd < 1) continue; const ex = colX(g) + sd * CW / 2, yb = rowY(r + dd) + (rng() - 0.5) * CH * 0.8, bl = 0.06 + rng() * 0.16;
          P = [ex, ya, ex + sd * bl, ya + (yb - ya) * (0.1 + 0.2 * hs), ex + sd * bl * (0.5 + hs), yb - (yb - ya) * (0.1 + 0.25 * ty), ex, yb]; gi = sd > 0 ? g : g - 1; }
        else { const sp = ty > 0.84 && g < 4 ? 2 : 1; if (g + sp > 5) continue; const dr = clamp(Math.floor(rng() * 7) - 3, -r, ROWS - 1 - r), yb = rowY(r + dr) + (rng() - 0.5) * CH * 0.8, xa = colX(g) + CW / 2, xb = colX(g + sp) - CW / 2, tn = 0.1 + rng() * 0.25;
          P = [xa, ya, xa + tn, ya + (rng() - 0.5) * 0.5, xb - tn * (0.6 + hs), yb + (rng() - 0.5) * 0.5, xb, yb]; gi = g; }
        if (gi < 0 || gi > 4) continue;
        const f = hs < 0.22, col = f ? mix3(FIL, WHITE, 0.15) : mix3(C0, WHITE, 0.3 + 0.45 * ty), w = 0.4 + rng() * 0.6, ph = rng();
        for (let q = 0; q < 18; q++) { const a = bez(P, q / 18), b = bez(P, (q + 1) / 18); TV.push(a[0], a[1], 0, b[0], b[1], 0); TU.push(q / 18, (q + 1) / 18);
          TP.push(ph, ph); TC.push(...col, ...col); TW.push(w, w); TG.push(gi, gi); } } } }
  const tg = new THREE.BufferGeometry(); tg.setAttribute('position', new THREE.Float32BufferAttribute(TV, 3)); tg.setAttribute('aU', new THREE.Float32BufferAttribute(TU, 1));
  tg.setAttribute('aPh', new THREE.Float32BufferAttribute(TP, 1)); tg.setAttribute('aCol', new THREE.Float32BufferAttribute(TC, 3)); tg.setAttribute('aW', new THREE.Float32BufferAttribute(TW, 1));
  tg.setAttribute('aG', new THREE.Float32BufferAttribute(TG, 1)); tg.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 6);
  const TRACE_V = (pts) => `uniform float uTime,uPixel,uFade,uZoomP,uEnergy; uniform float uSpi[${COLS - 1}]; attribute float aU,aPh,aW,aG; attribute vec3 aCol; varying vec3 vC; varying float vA;
    void main(){ vec4 mv=modelViewMatrix*vec4(position,1.0); gl_Position=projectionMatrix*mv;
      float I=min(1.5,uSpi[int(aG+0.5)]), fl=0.5+0.5*sin(uTime*(0.3+aPh*0.6)*(0.6+uEnergy)+aPh*50.0);
      float sp=fract(uTime*(0.35+aPh*0.5)*(0.7+uEnergy*0.6)+aPh*3.0); float spark=exp(-pow((aU-sp)/0.035,2.0))*step(0.4,fract(uTime*0.3+aPh*7.0));
      float a=aW*I*(0.4+0.6*fl)*(0.6+0.4*sin(aU*3.14159));
      ${pts ? 'gl_PointSize=(2.0+aW*5.0)*uPixel*(6.4/-mv.z)*uZoomP*(1.0+spark*1.2); vA=(a*0.05+spark*0.5)*uFade;' : 'vA=(a*0.42+spark*0.9)*uFade;'}
      vC=mix(aCol,vec3(1.0),spark*0.5); }`;
  const traceL = new THREE.LineSegments(tg, skAdd(FU, TRACE_V(false), `varying vec3 vC; varying float vA; void main(){ gl_FragColor=vec4(vC*vA,1.0); }`)); traceL.renderOrder = 900;
  const traceP = new THREE.Points(tg, skAdd(FU, TRACE_V(true), SK_SOFT_F)); traceP.renderOrder = 899;   /* the plasma's soft width */
  wall.add(traceP, traceL);

  /* ---- plasma buses in the five gaps, ions running along them ---- */
  { const v = [], u = [], gi = [], N = 90; for (let g = 0; g < COLS - 1; g++) for (let i = 0; i < N; i++) { const y = lerp(1.25, -1.25, i / (N - 1)); v.push(gapX(g), y, 0); u.push(i / (N - 1)); gi.push(g); }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(v, 3)); g.setAttribute('aU', new THREE.Float32BufferAttribute(u, 1)); g.setAttribute('aG', new THREE.Float32BufferAttribute(gi, 1));
    const bus = new THREE.Points(g, skAdd(FU, `uniform float uTime,uPixel,uFade,uZoomP,uFlow,uBus; uniform float uSpi[${COLS - 1}]; uniform vec3 uC0,uFil; attribute float aU,aG; varying vec3 vC; varying float vA;
        void main(){ vec4 mv=modelViewMatrix*vec4(position,1.0); gl_Position=projectionMatrix*mv; float I=min(1.6,uSpi[int(aG+0.5)]);
          float ion=pow(0.5+0.5*sin((aU*26.0-uFlow*3.0)+aG*1.7),14.0); float edge=smoothstep(0.0,0.12,aU)*smoothstep(1.0,0.88,aU);
          gl_PointSize=(4.0+ion*7.0)*uPixel*(6.4/-mv.z)*uZoomP; vC=mix(uC0,mix(uFil,vec3(1.0),0.4),ion); vA=(0.05+ion*0.5*uBus)*I*edge*uFade; }`, SK_SOFT_F));
    bus.renderOrder = 901; wall.add(bus);
    const lv = []; for (let g = 0; g < COLS - 1; g++) lv.push(gapX(g), 1.25, 0, gapX(g), -1.25, 0);
    const bl = new THREE.LineSegments(new THREE.BufferGeometry().setAttribute('position', new THREE.Float32BufferAttribute(lv, 3)),
      skAdd(FU, `void main(){ gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }`, `uniform vec3 uC0; uniform float uFade; void main(){ gl_FragColor=vec4(uC0*0.35*uFade,1.0); }`));
    bl.renderOrder = 900; wall.add(bl); }

  /* ---- drifting ion dust in front of and behind the wall ---- */
  { const N = lite ? 260 : 620, v = [], s = [], ph = [], f = [];
    for (let i = 0; i < N; i++) { v.push((rng() - 0.5) * 7.8, (rng() - 0.5) * 3.4, (rng() - 0.35) * 2.4); s.push(0.4 + rng() * 1.2); ph.push(rng() * TAU); f.push(rng()); }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(v, 3)); g.setAttribute('aS', new THREE.Float32BufferAttribute(s, 1));
    g.setAttribute('aPh', new THREE.Float32BufferAttribute(ph, 1)); g.setAttribute('aF', new THREE.Float32BufferAttribute(f, 1)); g.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 8);
    const d = new THREE.Points(g, skAdd(FU, `uniform float uTime,uPixel,uFade,uZoomP; uniform vec3 uC0,uFil; attribute float aS,aPh,aF; varying vec3 vC; varying float vA;
        void main(){ vec3 p=position; p.x=mod(p.x+3.9+uTime*0.12*aS,7.8)-3.9; p.y+=0.06*cos(uTime*0.7+aPh); p.z+=0.08*sin(uTime*0.5+aPh);
          vec4 mv=modelViewMatrix*vec4(p,1.0); gl_Position=projectionMatrix*mv; gl_PointSize=aS*2.6*uPixel*(6.4/-mv.z)*uZoomP;
          float tw=0.35+0.65*pow(0.5+0.5*sin(uTime*(0.8+aF)+aPh),2.0); vC=aF<0.6?mix(uC0,vec3(1.0),0.62):aF<0.85?uFil:vec3(1.0);
          vA=0.5*tw*uFade*smoothstep(3.9,3.0,abs(p.x)); }`, SK_SOFT_F)); d.renderOrder = 898; wall.add(d); }

  /* ---- the decision path: lit routes, the ion orb, arrival rings ---- */
  const tex = (draw) => { const cv = document.createElement('canvas'); cv.width = cv.height = 128; draw(cv.getContext('2d')); return new THREE.CanvasTexture(cv); };
  const GLOWT = tex(x => { const g = x.createRadialGradient(64, 64, 0, 64, 64, 64); g.addColorStop(0, 'rgba(255,255,255,1)'); g.addColorStop(0.2, 'rgba(255,255,255,.45)'); g.addColorStop(1, 'rgba(255,255,255,0)'); x.fillStyle = g; x.fillRect(0, 0, 128, 128); });
  const FLARET = tex(x => { const g = x.createRadialGradient(64, 64, 0, 64, 64, 40); g.addColorStop(0, 'rgba(255,255,255,.8)'); g.addColorStop(1, 'rgba(255,255,255,0)'); x.fillStyle = g; x.fillRect(0, 0, 128, 128);
    x.globalCompositeOperation = 'lighter'; for (const [w, h] of [[128, 3], [3, 128]]) { const l = x.createLinearGradient(64 - w / 2, 64 - h / 2, 64 + w / 2, 64 + h / 2);
      l.addColorStop(0, 'rgba(255,255,255,0)'); l.addColorStop(0.5, 'rgba(255,255,255,.9)'); l.addColorStop(1, 'rgba(255,255,255,0)'); x.fillStyle = l; x.fillRect(64 - w / 2, 64 - h / 2, w, h); } });
  const RINGT = tex(x => { x.strokeStyle = 'rgba(255,255,255,1)'; x.lineWidth = 2.5; x.beginPath(); x.arc(64, 64, 57, 0, TAU); x.stroke(); x.globalAlpha = 0.3; x.lineWidth = 9; x.stroke(); });
  const sprite = (map, ro) => { const s = new THREE.Sprite(new THREE.SpriteMaterial({ map, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false })); s.renderOrder = ro; wall.add(s); return s; };
  const orbHalo = sprite(GLOWT, 905), orbCore = sprite(GLOWT, 906), orbFlare = sprite(FLARET, 906);
  const rings = Array.from({ length: 10 }, () => { const s = sprite(RINGT, 904); s.visible = false; s.userData = { t: 9 }; return s; });
  const ringAt = (x, y, col, big) => { const s = rings.find(r => r.userData.t > 1) || rings[0]; s.position.set(x, y, 0.01); s.material.color.setRGB(...col); s.userData = { t: 0, big }; s.visible = true; };
  /* routes: card right edge -> bus -> rounded turn -> next card's left edge */
  const routePts = (c, r1, r2) => { const xa = colX(c) + CW / 2, xb = colX(c + 1) - CW / 2, gx = gapX(c), ya = rowY(r1), yb = rowY(r2), rad = Math.min(0.06, Math.abs(yb - ya) / 2), sg = Math.sign(yb - ya);
    const P = []; const line = (x0, y0, x1, y1) => { const n = Math.max(2, Math.ceil(Math.hypot(x1 - x0, y1 - y0) / 0.012)); for (let i = 0; i < n; i++) P.push([lerp(x0, x1, i / n), lerp(y0, y1, i / n)]); };
    const arc = (cx, cy, a0, a1) => { for (let i = 0; i < 8; i++) { const a = lerp(a0, a1, i / 8); P.push([cx + Math.cos(a) * rad, cy + Math.sin(a) * rad]); } };
    if (!sg) line(xa, ya, xb, yb);
    else { line(xa, ya, gx - rad, ya); arc(gx - rad, ya + sg * rad, -sg * Math.PI / 2, 0); line(gx, ya + sg * rad, gx, yb - sg * rad); arc(gx + rad, yb - sg * rad, Math.PI, Math.PI - sg * Math.PI / 2); line(gx + rad, yb, xb, yb); }
    P.push([xb, yb]); const L = [0]; for (let i = 1; i < P.length; i++) L.push(L[i - 1] + Math.hypot(P[i][0] - P[i - 1][0], P[i][1] - P[i - 1][1]));
    return { P, L, len: L[L.length - 1] }; };
  const routeAt = (rt, s) => { const d = s * rt.len; let i = 1; while (i < rt.P.length - 1 && rt.L[i] < d) i++; const u = (d - rt.L[i - 1]) / Math.max(1e-6, rt.L[i] - rt.L[i - 1]);
    return [lerp(rt.P[i - 1][0], rt.P[i][0], u), lerp(rt.P[i - 1][1], rt.P[i][1], u)]; };
  /* lit-path buffer: rebuilt whenever the path changes; each point knows its hop and where along it lies */
  const LP = 2600, lpPos = new Float32Array(LP * 3), lpS = new Float32Array(LP), lpH = new Float32Array(LP);
  const lpg = new THREE.BufferGeometry(); lpg.setAttribute('position', new THREE.BufferAttribute(lpPos, 3)); lpg.setAttribute('aS', new THREE.BufferAttribute(lpS, 1)); lpg.setAttribute('aH', new THREE.BufferAttribute(lpH, 1));
  lpg.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 6); lpg.setDrawRange(0, 0);
  const FILL = { uFill: { value: new Float32Array(COLS - 1) }, uPathA: { value: 0 } };
  const pathMat = (pts) => skAdd(Object.assign({}, FU, FILL), `uniform float uTime,uPixel,uFade,uZoomP,uPathA; uniform float uFill[${COLS - 1}]; uniform vec3 uHi; attribute float aS,aH; varying vec3 vC; varying float vA;
      void main(){ vec4 mv=modelViewMatrix*vec4(position,1.0); gl_Position=projectionMatrix*mv; float f=uFill[int(aH+0.5)]; float on=step(aS,f);
        float sp=exp(-pow((aS-fract(uTime*1.6+aH*0.37))/0.03,2.0))*step(0.999,f); float head=exp(-pow((aS-f)/0.02,2.0))*step(f,0.999);
        ${pts ? 'gl_PointSize=(9.0+sp*8.0+head*10.0)*uPixel*(6.4/-mv.z)*uZoomP; vA=on*(0.16+sp*0.5+head*0.6)*uPathA*uFade;' : 'gl_PointSize=(2.4+sp*3.0)*uPixel*(6.4/-mv.z)*uZoomP; vA=on*(0.9+sp)*uPathA*uFade;'}
        vC=mix(uHi,vec3(1.0),${pts ? '0.0' : '0.3'}+sp*0.5); }`, SK_SOFT_F);
  const pathGlow = new THREE.Points(lpg, pathMat(true)), pathCore = new THREE.Points(lpg, pathMat(false)); pathGlow.renderOrder = 902; pathCore.renderOrder = 903; wall.add(pathGlow, pathCore);
  const path = new Array(COLS).fill(null); let routes = new Array(COLS - 1).fill(null), pathDirty = true;
  const rebuildPath = () => { let n = 0; routes = routes.map((_, c) => (path[c] != null && path[c + 1] != null) ? routePts(c, path[c], path[c + 1]) : null);
    routes.forEach((rt, h) => { if (!rt) return; for (let i = 0; i < 400 && n < LP; i++, n++) { const s = i / 399, p = routeAt(rt, s); lpPos.set([p[0], p[1], 0.006], n * 3); lpS[n] = s; lpH[n] = h; } });
    lpg.attributes.position.needsUpdate = lpg.attributes.aS.needsUpdate = lpg.attributes.aH.needsUpdate = true; lpg.setDrawRange(0, n); pathDirty = false; };
  /* side links: now and then two cards in neighbouring columns trade a quick signal (more often while thinking) */
  const NLK = 8, LKN = 48, lkPos = new Float32Array(NLK * LKN * 3), lkA = new Float32Array(NLK * LKN), lkC = new Float32Array(NLK * LKN * 3);
  const lkg = new THREE.BufferGeometry(); lkg.setAttribute('position', new THREE.BufferAttribute(lkPos, 3)); lkg.setAttribute('aA', new THREE.BufferAttribute(lkA, 1)); lkg.setAttribute('aCol', new THREE.BufferAttribute(lkC, 3));
  lkg.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 6);
  const lkPts = new THREE.Points(lkg, skAdd(FU, `uniform float uPixel,uFade,uZoomP; attribute float aA; attribute vec3 aCol; varying vec3 vC; varying float vA;
      void main(){ vec4 mv=modelViewMatrix*vec4(position,1.0); gl_Position=projectionMatrix*mv; gl_PointSize=(2.0+aA*7.0)*uPixel*(6.4/-mv.z)*uZoomP; vC=aCol; vA=aA*uFade; }`, SK_SOFT_F));
  lkPts.renderOrder = 902; wall.add(lkPts);
  const links = []; let linkAcc = 0;

  /* ---- the orb: walks the path one column at a time ---- */
  const lit = new Float32Array(NK), flash = new Float32Array(NK), ping = new Float32Array(NK);
  const orb = { c: 0, r: 0, x: colX(0), y: rowY(0), move: null, work: 0, trail: [] };
  let goalC = 0, hiCol = mix3(C0, FIL, 0.6), hiGoal = hiCol.slice(), quiet = 0, mic = 0, gatePass = PASS;
  const setGoal = (c) => { goalC = c; quiet = 0; };
  const setRow = (c, r) => { if (path[c] !== r) { path[c] = r; pathDirty = true; } };
  const reset = () => { path.fill(null); path[0] = 0; pathDirty = true; goalC = 0; hiGoal = mix3(C0, FIL, 0.6); };
  reset();
  const TRN = 16, trPos = new Float32Array(TRN * 3), trA = new Float32Array(TRN), trC = new Float32Array(TRN * 3);
  const trg = new THREE.BufferGeometry(); trg.setAttribute('position', new THREE.BufferAttribute(trPos, 3)); trg.setAttribute('aA', new THREE.BufferAttribute(trA, 1)); trg.setAttribute('aCol', new THREE.BufferAttribute(trC, 3));
  trg.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 6); const trail = new THREE.Points(trg, lkPts.material); trail.renderOrder = 904; wall.add(trail);
  const arrive = () => { const k = K(orb.c, orb.r); flash[k] = 1;
    ringAt(orb.x, orb.y, orb.c === 4 ? gatePass : mix3(hiCol, WHITE, 0.25), true); orb.work = 0.28; };
  const orbStep = (dt) => {
    if (orb.move) { const M = orb.move; M.u += dt / M.dur; const u = Math.min(1, M.u), e = easeIO(u);
      if (M.rt) { const p = routeAt(M.rt, e); orb.x = p[0]; orb.y = p[1]; FILL.uFill.value[M.h] = e; }
      else { orb.x = lerp(M.x0, M.x1, e); orb.y = lerp(M.y0, M.y1, e); }
      if (u >= 1) { orb.c = M.c; orb.r = M.r; orb.move = null; arrive(); } return; }
    if (orb.work > 0) { orb.work -= dt; return; }
    if (pathDirty) rebuildPath();
    const want = path[goalC];
    if (orb.c === goalC && orb.r === want) return;
    if (goalC > orb.c && path[orb.c] == null) { path[orb.c] = orb.r; pathDirty = true; }
    if (goalC > orb.c && path[orb.c] === orb.r) {
      let n = orb.c + 1; while (n < goalC && path[n] == null) n++;
      if (path[n] == null) return;
      if (n === orb.c + 1) {                                               /* hop to the next column along the lit route */
        const h = orb.c, rt = routes[h] || routePts(h, orb.r, path[h + 1]); orb.move = { rt, h, c: h + 1, r: path[h + 1], u: 0, dur: 0.32 + rt.len * 0.28 }; FILL.uFill.value[h] = 0; return; }
      orb.move = { c: n, r: path[n], x0: orb.x, y0: orb.y, x1: colX(n), y1: rowY(path[n]), u: 0, dur: 0.45 }; return; }   /* a step not needed (a plain answer skips 决策) */
    /* glide: back to an earlier column, or to another card in this one */
    const c = goalC > orb.c ? orb.c : goalC, r = path[c] == null ? orb.r : path[c];
    for (let h = c; h < COLS - 1; h++) FILL.uFill.value[h] = 0;
    orb.move = { c, r, x0: orb.x, y0: orb.y, x1: colX(c), y1: rowY(r), u: 0, dur: 0.3 + Math.hypot(colX(c) - orb.x, rowY(r) - orb.y) * 0.12 };
  };

  /* ---- what Jarvis is doing -> the path ---- */
  const use = (id) => { const R = ROUTE[id]; hiGoal = mix3(skHex(SKAB[SKAB_I[id]].c), WHITE, 0.15);
    if (path[0] == null) setRow(0, 1); if (path[1] == null) setRow(1, 3);
    setRow(2, R[0]); for (let c = 3; c < COLS; c++) setRow(c, R[c - 2]);
    gatePass = id === 'home' ? AMBER : PASS;                               /* home devices pass the safety list, shown amber */
    let end = 2; for (let c = 3; c < COLS; c++) if (path[c] != null) end = c; setGoal(end); };
  const onMsg = (m) => { const e = skEvent(m); if (!e) return;
    if (e.k === 'wake') { if (orb.c > 0) reset(); setRow(0, 0); flash[K(0, 0)] = 1; ringAt(colX(0), rowY(0), WHITE, true); setGoal(0); }
    else if (e.k === 'heard') { setRow(0, path[0] === 2 ? 2 : 1); setRow(1, 3); setGoal(Math.max(goalC, 1)); }
    else if (e.k === 'reply') { if (path[1] == null) setRow(1, 3); if (path[2] == null) setRow(2, 5);   /* a plain answer: 直接回答, no ability */
      if (path[4] == null) setRow(4, 3); setRow(5, 5); setGoal(5); }
    else if (e.k === 'level') mic = Math.max(mic, e.v);
    else if (e.k === 'sys') { ping[K(0, 4)] = Math.max(ping[K(0, 4)], 0.5); }
    else if (e.k === 'use') { if (e.id === 'hand') { flash[K(0, 3)] = 1; return; } if (e.id === 'ident') { flash[K(1, 2)] = 1; return; }
      if (e.id === 'memory') { setRow(2, 1); setGoal(Math.max(goalC, 2)); return; }
      if (e.id === 'ear') { setRow(0, 1); return; } if (e.id === 'voice') { setRow(5, 5); setGoal(5); return; }
      if (e.id === 'sys') { ping[K(0, 4)] = 1; return; }
      if (ROUTE[e.id]) use(e.id); } };

  /* ---- motion ---- */
  const ctl = skControl();
  let t = 0, fade = 0, flow = 0, energy = 0.5, prevSt = 'idle';
  return {
    msg: onMsg,
    frame(dt) {
      dt = Math.min(dt, 0.1); const st = typeof state === 'string' ? state : 'idle', vib = st === 'speaking' ? U.uVib.value : 0;
      t += dt; fade = Math.min(1, fade + dt / 0.9); mic *= Math.pow(0.15, dt);
      if (st !== prevSt) { if (st === 'listening') { if (orb.c > 1) reset(); setRow(0, path[0] === 2 ? 2 : 1); setGoal(0); }
        else if (st === 'thinking') { if (path[1] == null) setRow(1, 3); setRow(2, path[2] == null ? 0 : path[2]); setGoal(Math.max(goalC, 2)); }
        else if (st === 'speaking') { if (path[5] == null) onMsg({ type: 'jarvis' }); }
        prevSt = st; }
      if (st === 'idle') { quiet += dt; if (quiet > 6 && (orb.c > 0 || path[1] != null)) { reset(); quiet = -999; } } else quiet = 0;
      if (quiet < 0 && st === 'idle' && orb.c === 0 && !orb.move) quiet = 0;
      orbStep(dt);
      /* card light: the walked path stays lit, the current card burns, flashes fade */
      for (let c = 0; c < COLS; c++) for (let r = 0; r < ROWS; r++) { const k = K(c, r); flash[k] *= Math.pow(0.25, dt); ping[k] *= Math.pow(0.3, dt);
        let L = 0.0; if (path[c] === r && (c < orb.c || (c === orb.c && !orb.move) || (orb.move && c < orb.move.c))) L = 0.5;
        if (c === orb.c && r === orb.r && !orb.move) L = st === 'thinking' && c === 2 ? 0.85 + 0.15 * Math.sin(t * 9) : st === 'speaking' && c === 5 ? 0.75 + vib * 0.5 : st === 'listening' && c === 0 ? 0.7 + mic * 0.6 : 0.9;
        lit[k] = skEase(lit[k], Math.max(L, flash[k], ping[k] * 0.6), 0.02, dt); }
      FU.uLit.value.set(lit);
      /* gap intensity: busy where the orb is */
      for (let g = 0; g < COLS - 1; g++) { const near = Math.exp(-Math.pow((gapX(g) - orb.x) / 1.2, 2)); FU.uSpi.value[g] = skEase(FU.uSpi.value[g], 0.42 + near * 0.6 + (st === 'thinking' ? 0.25 : 0), 0.2, dt); }
      energy = skEase(energy, st === 'thinking' ? 1.3 : st === 'listening' ? 0.8 + mic : st === 'speaking' ? 0.8 + vib : 0.45, 0.2, dt);
      flow += dt * (0.6 + energy * 1.4);
      FU.uBus.value = skEase(FU.uBus.value, st === 'listening' ? 0.6 + mic * 1.5 : st === 'speaking' ? 0.6 + vib : st === 'thinking' ? 1.0 : 0.45, 0.1, dt);
      hiCol = hiCol.map((v, i) => skEase(v, hiGoal[i], 0.1, dt)); FU.uHi.value.set(...hiCol);
      FILL.uPathA.value = skEase(FILL.uPathA.value, path[1] != null ? 1 : 0, 0.05, dt);
      /* side links */
      linkAcc -= dt * (st === 'thinking' ? 3 : 1); if (linkAcc <= 0) { linkAcc = 0.35 + rng() * 0.5; if (links.length < NLK) { const c = Math.floor(rng() * 5), c2 = Math.min(5, c + (rng() < 0.7 ? 1 : 2)), r = Math.floor(rng() * ROWS), r2 = Math.floor(rng() * ROWS);
          links.push({ c, r, c2, r2, t: 0, life: 1.6 + rng() * 0.8, col: rng() < 0.45 ? FIL : rng() < 0.5 ? COOL : mix3(C0, WHITE, 0.5), bend: (rng() - 0.5) * 0.6 }); ping[K(c, r)] = 1; } }
      lkA.fill(0);
      links.forEach((l, i) => { l.t += dt; const e = l.t / l.life, grow = clamp(l.t / 0.5, 0, 1), fd = e > 0.7 ? 1 - (e - 0.7) / 0.3 : 1;
        if (grow >= 1 && !l.hit) { l.hit = true; ping[K(l.c2, l.r2)] = 1; ringAt(colX(l.c2) - CW / 2, rowY(l.r2), l.col, false); }
        const x0 = colX(l.c) + CW / 2, y0 = rowY(l.r), x1 = colX(l.c2) - CW / 2, y1 = rowY(l.r2), hd = grow < 1 ? easeIO(grow) : (l.t * 0.9) % 1;
        for (let q = 0; q < LKN; q++) { const u = q / (LKN - 1) * easeIO(grow), v = 1 - u, mx = (x0 + x1) / 2 + l.bend * 0.3, my = (y0 + y1) / 2 + l.bend;
          const x = v * v * x0 + 2 * v * u * mx + u * u * x1, y = v * v * y0 + 2 * v * u * my + u * u * y1, o = i * LKN + q;
          lkPos.set([x, y, 0.004], o * 3); lkC.set(l.col, o * 3); lkA[o] = (0.22 + Math.exp(-Math.pow((q / (LKN - 1) - hd) / 0.05, 2)) * 0.8) * fd * Math.max(0, Math.min(1, e < 1 ? 1 : 0)); } });
      for (let i = links.length - 1; i >= 0; i--) if (links[i].t > links[i].life) links.splice(i, 1);
      lkg.attributes.position.needsUpdate = lkg.attributes.aA.needsUpdate = lkg.attributes.aCol.needsUpdate = true;
      /* the orb and its trail */
      orb.trail.push([orb.x, orb.y]); if (orb.trail.length > TRN) orb.trail.shift();
      trA.fill(0); orb.trail.forEach((p, i) => { trPos.set([p[0], p[1], 0.012], i * 3); trC.set(mix3(hiCol, WHITE, 0.4), i * 3); trA[i] = Math.pow(i / (TRN - 1), 1.6) * 0.75 * (orb.move ? 1 : 0.3); });
      trg.attributes.position.needsUpdate = trg.attributes.aA.needsUpdate = trg.attributes.aCol.needsUpdate = true;
      const onCard = !orb.move, beat = st === 'speaking' ? vib : st === 'listening' ? mic : st === 'thinking' ? 0.5 + 0.5 * Math.sin(t * 7) : 0.3 + 0.2 * Math.sin(t * 2);
      const g = fade * U.uGain.value;
      [orbHalo, orbCore, orbFlare].forEach(s => s.position.set(orb.x, orb.y, 0.015));
      orbHalo.scale.setScalar(0.42 + beat * 0.16); orbHalo.material.color.setRGB(...hiCol); orbHalo.material.opacity = (onCard ? 0.32 : 0.55) * g;
      orbCore.scale.setScalar((onCard ? 0.09 : 0.13) * (1 + beat * 0.3)); orbCore.material.opacity = g;
      orbFlare.scale.setScalar(0.62 + beat * 0.25); orbFlare.material.color.setRGB(...mix3(hiCol, WHITE, 0.4)); orbFlare.material.opacity = (onCard ? 0.35 : 0.6) * g;
      rings.forEach(s => { const u = s.userData; if (u.t > 1) { s.visible = false; return; } u.t += dt / (u.big ? 1.6 : 0.9); const ee = 1 - Math.pow(1 - Math.min(1, u.t), 3);
        s.scale.setScalar(u.big ? 0.1 + 1.0 * ee : 0.04 + 0.25 * ee); s.material.opacity = (u.big ? 0.95 : 0.6) * Math.pow(1 - Math.min(1, u.t), 1.3) * g; });
      /* view: the wall stands at an angle, turning gently, plus drag / palm and wheel */
      ctl.step(dt); grp.scale.setScalar(ctl.zoom); FU.uZoomP.value = Math.sqrt(ctl.zoom);
      tilt.rotation.set(-0.1 + ctl.pitch + 0.03 * Math.sin(t * 0.13), -0.42 + 0.05 * Math.sin(t * 0.07) + ctl.yaw, 0, 'YXZ');
      wall.position.x = -lerp(0, orb.x, 0.18) * WS;                          /* the camera leans toward where the orb is */
      FU.uTime.value = t; FU.uFade.value = g; FU.uFlow.value = flow; FU.uEnergy.value = energy;
      grp.visible = U.uGain.value > 0.02;
      skQuiet(0.9);
    },
    dispose() { ctl.dispose(); scene.remove(grp); grp.traverse(o => { if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); });
      [atlasT, GLOWT, FLARET, RINGT].forEach(x => x.dispose()); }
  };
};
/* core:ion>> */
