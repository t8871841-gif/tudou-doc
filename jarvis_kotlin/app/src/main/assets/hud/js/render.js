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
  camera.position.z = 8.5; /* 7.2 核心铺满但左右裁太多，8.5 少裁一点（多看 18% 宽），核心只小 15%；9.5 又太小、演示按钮上方留黑 */
  camera.updateProjectionMatrix();
  skyU.uRes.value.set(w, h);
}
addEventListener('resize', resize); resize();

/* drag to rotate. A tap on the core no longer toggles the mic: the HUD follows the mic state, it never drives it (the native mic button owns listening). Tap-to-wake from dormant is the window pointerdown listener in interaction.js. */
let drag = null, rotV = { x: 0, y: 0 }, target = { x: 0, y: 0 }, mouse = { x: 0, y: 0 };
canvas.addEventListener('pointerdown', e => { drag = { x: e.clientX, y: e.clientY, moved: 0 }; canvas.setPointerCapture(e.pointerId); canvas.classList.add('dragging'); });
canvas.addEventListener('pointermove', e => {
  mouse.x = e.clientX / innerWidth - 0.5; mouse.y = e.clientY / innerHeight - 0.5;
  if (!drag) return;
  const dx = e.clientX - drag.x, dy = e.clientY - drag.y; drag.moved += Math.abs(dx) + Math.abs(dy);
  rotV.y += dx * 0.0009; rotV.x += dy * 0.0009; drag.x = e.clientX; drag.y = e.clientY;
});
canvas.addEventListener('pointerup', e => {
  canvas.classList.remove('dragging');
  const wasTap = drag && drag.moved < 6;
  drag = null;
  if (!wasTap) { window.__hudJustWoke = false; return; }
  /* wake tap only wakes (see interaction.js); a tap on the awake HUD toggles the
     demo buttons row. If the skin picker is open this tap is for closing it (the
     document click handler does that), so don't toggle the row. */
  if (window.__hudJustWoke) { window.__hudJustWoke = false; return; }
  const sp = document.getElementById('skinPick');
  if (sp && !sp.hidden) return;
  if (typeof window.dqToggle === 'function') window.dqToggle();
});
