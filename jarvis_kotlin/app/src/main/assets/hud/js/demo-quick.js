'use strict';
/* <<demo-quick: portrait quick demo buttons (batch 2). Four icon-only buttons at the
   bottom of the HUD animation area; each toggles its hologram (tap again to hide),
   mirroring the injected menu buttons in MainActivity.injectDemoMenu(). The Raptor
   labels are copied verbatim from there (desktop hologram.py ZH['raptor']). */
(function(){
  function toggleShip(){
    if (typeof SHIP !== 'undefined' && SHIP && SHIP.state.on && !SHIP.state.ending) SHIP.hide();
    else if (typeof shipCommand === 'function') shipCommand({action:'play'});
  }
  function toggleRoadster(){
    if (typeof RD !== 'undefined' && RD && RD.state.on && !RD.state.ending) RD.hide();
    else if (typeof roadsterCommand === 'function') roadsterCommand({action:'play'});
  }
  function toggleOptimus(){
    if (typeof OP !== 'undefined' && OP && OP.state.on && !OP.state.ending) OP.hide();
    else if (typeof optimusCommand === 'function') optimusCommand({action:'play'});
  }
  function toggleRaptor(){
    if (typeof MACH !== 'undefined' && MACH && MACH.on) { if (typeof machineHide === 'function') machineHide(); }
    else if (typeof machineCommand === 'function') machineCommand({action:'show', src:'figure-raptor.html', title:'RAPTOR 2 · 全流量分级燃烧发动机', labels:{
      nozzle:{name:'喷管',say:'出口 1.3 米 · 甲烷冷却'},
      downcomer:{name:'下降管与主燃料阀',say:'甲烷送进冷却夹套'},
      controller:{name:'发动机控制器与线束',say:'7 根线缆'},
      plumbing:{name:'管路',say:'7 根传感管汇入一块 · 9 根供给、吹扫与点火管'},
      lox:{name:'液氧导管与主阀',say:'从泵送往预燃室'},
      ftp:{name:'甲烷涡轮泵与富燃预燃室',say:'由富燃燃气驱动'},
      hotgas:{name:'热燃气歧管',say:'富燃燃气送往喷注器'},
      chamber:{name:'喷注器与主燃烧室',say:'300 bar · 不用点火器'},
      orpb:{name:'富氧预燃室',say:'全部液氧都从这里经过'},
      otp:{name:'液氧涡轮泵',say:'由富氧燃气驱动'},
      gimbal:{name:'万向节与推力座',say:'把 230 吨推力传给试车台'}
    }});
  }
  var map = { dqShip: toggleShip, dqRoadster: toggleRoadster, dqOptimus: toggleOptimus, dqRaptor: toggleRaptor };
  Object.keys(map).forEach(function(id){
    var b = document.getElementById(id);
    if (b) b.addEventListener('click', function(e){ e.stopPropagation(); try { map[id](); } catch (err) { console.warn('demo quick:', err); } });
  });
})();
/* demo-quick>> */
