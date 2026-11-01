'use strict';
/* <<panel-swipe: portrait HUD panel toggle (batch 3). Left swipe opens the panel
   (Agent调度 / 工作汇报 / 天气 / 日程, top to bottom), right swipe returns to the
   default dialogue state. Called from native MainActivity.dispatchTouchEvent via
   evaluateJavascript("panelSet(true|false)"). The panel reuses the existing live
   sections from panels.js (#pAgents, #pReport, #pWeather, #pSched) — no data code
   here, only the body.panel-open class that re-lays them out (see hud.css).
   No wakeUI() call here: the swipe's own pointerdown already woke the page via
   the capture-phase listener, and the panel CSS keeps the sections readable even
   if the page later goes dormant while the user reads. */
window.panelSet = function(open) {
  open = !!open;
  if (document.body.classList.contains('panel-open') === open) return;
  document.body.classList.toggle('panel-open', open);
  window.scrollTo(0, 0);
};
/* panel-swipe>> */
