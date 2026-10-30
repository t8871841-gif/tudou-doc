'use strict';
/* ---------------- interaction ---------------- */
const sub = $('#subtitle'), stateText = $('#stateText'), badge = $('#badge'), input = $('#cmd');
const STATE_LABEL = { idle: '待命中', listening: '正在聆听…', thinking: '思考中…', speaking: '正在回答', boot: '启动中…' };
let closeT = null;
function openTalk() { clearTimeout(closeT); document.body.classList.add('talk'); }
function scheduleClose(ms = 6000) { clearTimeout(closeT); closeT = setTimeout(() => { if (state === 'idle' && document.activeElement !== input && !input.value && !(approvalBox && approvalBox.isConnected)) document.body.classList.remove('talk'); }, ms); }
let dormT = 0, dormAsked = false, dormHold = false;   /* asked: go to standby once Jarvis finishes speaking; hold: stay there until the next "Hey Jarvis" */
function wakeUI() { if (dormHold) return; clearTimeout(dormT); document.body.classList.remove('dormant'); }
function dormNow() { clearTimeout(dormT); dormAsked = false; dormHold = true; holoHide(); document.body.classList.add('dormant'); }
function sleepUI(ms = 15000) {                 /* back to the starfield once the conversation has been quiet a while */
  clearTimeout(dormT);
  dormT = setTimeout(() => {
    if (state === 'idle' && !(window.SHIP && window.SHIP.state.on) && document.activeElement !== input && !input.value && !(approvalBox && approvalBox.isConnected)) { holoHide(); document.body.classList.add('dormant'); }
    else sleepUI(5000);
  }, ms);
}
addEventListener('keydown', e => { if (!e.metaKey && !e.ctrlKey && document.body.classList.contains('dormant')) { dormHold = false; wakeUI(); sleepUI(30000); } }, true);
addEventListener('pointerdown', () => { if (document.body.classList.contains('dormant')) { dormHold = false; wakeUI(); sleepUI(30000); } }, true);
function setState(s) { if (dormAsked && s !== 'speaking' && s !== 'thinking') dormNow(); if (s === 'listening' || s === 'thinking' || s === 'speaking') wakeUI(); else if (s === 'idle' && !document.body.classList.contains('dormant')) sleepUI(); state = s; document.body.dataset.state = s; stateText.textContent = STATE_LABEL[s]; badge.textContent = s === 'listening' ? 'LISTENING' : 'THINKING'; if (s === 'listening' || s === 'thinking' || s === 'speaking') openTalk(); else if (s === 'idle') scheduleClose(); }
let typeTimer = null;
