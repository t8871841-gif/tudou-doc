'use strict';
/* ---- creator data hologram: numbers from the backend screenshots, counted up on screen ---- */
const fmtInt = v => Math.round(v).toLocaleString('en-US');
function countUp(el, to, ms, fmt) {
  if (reduced) { el.textContent = fmt(to); return; }
  const t0 = performance.now();
  const step = now => { const k = Math.min(1, (now - t0) / ms), e = 1 - Math.pow(1 - k, 3); el.textContent = fmt(to * e); if (k < 1) requestAnimationFrame(step); };
  requestAnimationFrame(step);
}
function statsShow(m) {
  const box = $('#statsHolo'), mt = m.metrics || {}, val = k => mt[k] ? mt[k].value : null;
  try { dormHold = false; wakeUI(); } catch (_) {}
  $('#shWhen').textContent = m.posted ? '发布于 ' + m.posted : '';
  $('#shCap').textContent = (m.title || '').replace(/\s*#\S+/g, '').trim() || '最新作品';
  countUp($('#shPlays'), val('plays') || 0, 2200, fmtInt);
  countUp($('#shFans'), val('new_fans') || 0, 2200, v => '+' + fmtInt(v));
  $('#shBoostBox').hidden = val('boost') == null;
  if (val('boost') != null) countUp($('#shBoost'), val('boost'), 2200, fmtInt);
  const chg = c => c == null ? '' : `<em class="chg${c < 0 ? ' dn' : ''}">比往期 ${c > 0 ? '+' : ''}${c}%</em>`;
  const tiles = [['likes', '点赞'], ['comments', '评论'], ['shares', '分享'], ['saves', '收藏'], ['completion', '完播率'], ['retain_5s', '5s完播率'], ['avg_watch', '平均播放时长'], ['lost_fans', '脱粉']]
    .filter(([k]) => mt[k]).slice(0, 6);
  $('#shGrid').innerHTML = tiles.map(([k, label], i) =>
    `<div style="transition-delay:${0.35 + i * 0.08}s"><span>${label}</span><b data-k="${k}">0</b>${chg(mt[k].change)}</div>`).join('');
  tiles.forEach(([k]) => {
    const el = $(`#shGrid b[data-k="${k}"]`), v = mt[k].value, pct = /%/.test(mt[k].text), sec = k === 'avg_watch';
    countUp(el, v, 1800, x => pct ? x.toFixed(2) + '%' : sec ? x.toFixed(1) + 's' : fmtInt(x));
  });
  const bars = [['src_feed', '推荐页流量', false], ['avg_ratio', '平均播放占比', false], ['bounce_2s', '2s跳出率', true], ['fan_view_ratio', '粉丝播放占比', false]]
    .filter(([k]) => mt[k]).slice(0, 3);
  $('#shBars').innerHTML = bars.map(([k, label, warn]) =>
    `<div class="bar${warn ? ' warn' : ''}"><span>${label}<b>${mt[k].value}%</b></span><i style="--w:${Math.min(100, mt[k].value)}%"></i></div>`).join('');
  const d = m.derived || {};
  $('#shRatio').textContent = d.new_fans_per_10k_views != null ? `每万播放涨粉 ${d.new_fans_per_10k_views}` : '';
  box.hidden = false; box.classList.remove('on'); void box.offsetWidth; box.classList.add('on');
  clearTimeout(box._t); box._t = setTimeout(statsHide, 60000);
}
function statsHide() { const box = $('#statsHolo'); box.classList.remove('on'); clearTimeout(box._h); box._h = setTimeout(() => { box.hidden = true; }, 900); }
$('#statsHolo').addEventListener('click', statsHide);
addEventListener('keydown', e => { if (e.key === 'Escape') statsHide(); });
addEventListener('keydown', e => { if (e.key === 'Escape') machineEscape(); });
const rate = b => b >= 1e6 ? (b / 1e6).toFixed(1) + 'MB/s' : b >= 1e3 ? Math.round(b / 1e3) + 'KB/s' : Math.round(b) + 'B/s';
function sysUpdate(m) {
  sysLive = true;
  cpuHot = m.cpu >= 90 ? cpuHot + 1 : 0;                     // amber only when it stays high (~10 s)
  const set = (id, text, hot) => { const el = $(id); el.textContent = text; el.classList.toggle('hot', !!hot); };
  set('#cpu', m.cpu + '%', cpuHot >= 5);
  set('#mem', m.mem + '%', m.mem >= 90);
  const net = $('#net'); if (net.previousSibling) net.previousSibling.textContent = '';
  net.textContent = '↓' + rate(m.down) + ' ↑' + rate(m.up);
  if (m.battery != null) { $('#batBox').hidden = false; set('#bat', m.battery + '%' + (m.charging ? '⚡' : ''), !m.charging && m.battery <= 15); }
}
const WD = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
function tick() {
  const d = new Date(); const p = n => String(n).padStart(2, '0');
  $('#clock').textContent = `${p(d.getHours())}:${p(d.getMinutes())}`;
  $('#date').textContent = `${d.getMonth() + 1}月${d.getDate()}日 ${WD[d.getDay()]}`;
  if (!sysLive) {          // demo numbers only until the real monitor reports in
    $('#mem').textContent = (55 + Math.round(Math.random() * 5)) + '%';
    $('#net').textContent = (4 + Math.random() * 4).toFixed(1) + 'MB/s';
  }
}
tick(); setInterval(tick, 5000);

/* small canvases */
function fit(cv) { const r = cv.getBoundingClientRect(); const w = Math.max(1, Math.round(r.width * DPR)), h = Math.max(1, Math.round(r.height * DPR)); if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; } return cv.getContext('2d'); }
let waveMode = 1;
