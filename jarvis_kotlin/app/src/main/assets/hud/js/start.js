'use strict';
/* boot */
setState('boot');
setTimeout(() => {
  setState('idle');
  const h = new Date().getHours(); const g = h < 5 ? '夜深了' : h < 11 ? '早上好' : h < 13 ? '中午好' : h < 18 ? '下午好' : '晚上好';
  const { cur, next } = schedInfo();
  const tail = cur >= 0 ? `现在是${SCHED[cur][2]}。` : next >= 0 ? `下一项是 ${SCHED[next][0]} 的${SCHED[next][2]}。` : '今天的安排都已经结束了。';
  const body = liveTasks && !total ? '今天还没有安排。' : `今天的任务完成了 ${done} 项，${tail}`;
  openTalk(); typeOut('jarvis', `${g}${window.JV_NAME ? '，' + window.JV_NAME : ''}。${body}说 “Hey Jarvis” 就可以跟我说话，也可以在下面打字。`, 40, () => scheduleClose(5000));
}, 1800);
