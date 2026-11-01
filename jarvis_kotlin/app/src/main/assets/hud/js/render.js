'use strict';
renderer.toneMapping = THREE.ACESFilmicToneMapping; renderer.toneMappingExposure = 0.95;
const composer = new EffectComposer(renderer);
composer.addPass(new RenderPass(scene, camera));
const bloom = new UnrealBloomPass(new THREE.Vector2(1, 1), 0.4, 0.4, 0.9);
composer.addPass(bloom);
composer.addPass(new OutputPass());

function resize() {
  const w = canvas.clientWidth, h = canvas.clientHeight;
  renderer.setSize(w, h, false); composer.setSize(w, h);
  camera.aspect = w / h;
  camera.position.z = w / h < 1 ? 9.5 : 7.2;
  camera.updateProjectionMatrix();
  skyU.uRes.value.set(w, h);
}
addEventListener('resize', resize); resize();

/* tap core to talk; drag-to-rotate removed (portrait design, batch 1) */
let drag = null, rotV = { x: 0, y: 0 }, target = { x: 0, y: 0 }, mouse = { x: 0, y: 0 };
canvas.addEventListener('pointerdown', e => { drag = { x: e.clientX, y: e.clientY, moved: 0 }; canvas.setPointerCapture(e.pointerId); canvas.classList.add('dragging'); });
canvas.addEventListener('pointermove', e => {
  mouse.x = e.clientX / innerWidth - 0.5; mouse.y = e.clientY / innerHeight - 0.5;
  if (!drag) return;
  const dx = e.clientX - drag.x, dy = e.clientY - drag.y; drag.moved += Math.abs(dx) + Math.abs(dy);
  drag.x = e.clientX; drag.y = e.clientY;
});
canvas.addEventListener('pointerup', e => {
  canvas.classList.remove('dragging');
  if (drag && drag.moved < 6) {
    const r = canvas.getBoundingClientRect(); const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    if (Math.hypot(e.clientX - cx, e.clientY - cy) < Math.min(r.width, r.height) * 0.32) toggleListen();
  }
  drag = null;
});
