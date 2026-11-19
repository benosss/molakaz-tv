/*
 * Start-up and the remote: scales the 1280x720 layout to the TV, starts the films, weather and Fresh picks, maps
 * remote keys, and gives the video decoder back whenever another app comes to the front.
 */
var App = (function () {
  'use strict';

  var VERSION = '1.1.0';
  var IDLE_HOLD_MS = 15 * 60000; // nobody pressed a key for 15 min: loop the film on screen, stop downloading new ones
  var lastKey = Date.now();
  var holding = false;

  function fit() {
    var w = window.innerWidth || document.documentElement.clientWidth || 1920;
    var h = window.innerHeight || document.documentElement.clientHeight || 1080;
    var s = Math.min(w / 1280, h / 720);
    var stage = U.$('stage');
    U.setTransform(stage, 'translate(' + Math.round((w - 1280 * s) / 2) + 'px,' + Math.round((h - 720 * s) / 2) + 'px) scale(' + s + ')');
  }

  function applyGlass() {
    var on = U.hasBackdrop() && U.get('blur', true) && U.params.blur !== '0';
    document.documentElement.classList.toggle('blur', !!on);
  }

  function inList(list, c) { return list && U.indexOf(list, c) >= 0; }

  function actionOf(e) {
    var c = e.keyCode;
    var k = Platform.keys;
    if (c === 37) return 'left';
    if (c === 38) return 'up';
    if (c === 39) return 'right';
    if (c === 40) return 'down';
    if (inList(k.ok, c)) return 'ok';
    if (inList(k.back, c)) return 'back';
    if (inList(k.exit, c)) return 'exit';
    if (inList(k.playPause, c)) return 'playPause';
    if (inList(k.next, c)) return 'next';
    if (inList(k.red, c)) return 'red';
    if (inList(k.green, c)) return 'green';
    if (inList(k.yellow, c)) return 'yellow';
    if (inList(k.blue, c)) return 'blue';
    return null;
  }

  function onKey(e) {
    lastKey = Date.now();
    if (holding) { holding = false; Films.setHolding(false); }
    if (Screens.typing() && (e.keyCode === 37 || e.keyCode === 39 || (e.keyCode >= 48 && e.keyCode <= 90) || e.keyCode === 32 || e.keyCode === 8)) return;
    var a = actionOf(e);
    if (!a) return;
    // Backspace while typing deletes a letter; elsewhere it is Back (keyboards in the preview).
    if (a === 'back' && e.keyCode === 8 && Screens.typing()) return;
    e.preventDefault();
    if (a === 'exit') { Platform.exit(); return; }
    if (a === 'playPause') { Films.togglePause(); return; }
    if (a === 'next') { Films.skip(); return; }
    if (a === 'red' || a === 'green' || a === 'yellow' || a === 'blue') {
      // Colour keys: red Inputs, green All apps, yellow next film, blue Settings.
      if (a === 'yellow') { Films.skip(); return; }
      if (Screens.isOpen()) Screens.close();
      Screens.open(a === 'red' ? 'inputs' : a === 'green' ? 'apps' : 'settings');
      return;
    }
    if (Screens.isOpen()) { Screens.key(a); return; }
    if (a === 'back') { Home.back(); return; }
    Home.key(a);
  }

  function visibility() {
    var hidden = document.hidden || document.webkitHidden || false;
    Films.setHidden(!!hidden);
    if (!hidden) {
      Home.refreshWeather();
      Home.tickClock();
      maybePicks();
    }
  }

  function maybePicks() {
    if (U.params.picksfile) {
      // Preview only: picks saved from the real sources on a computer (a desktop browser can't fetch them: CORS).
      U.getJSON(U.params.picksfile, function (err, j) { if (!err && j && j.items) Home.setPicks(j.items); });
      return;
    }
    if (!Home.picksOn() || U.params.picks === '0') return;
    var p = Weather.place();
    if (!Picks.stale(p)) return;
    Picks.refresh(p, function (items) { Home.setPicks(items); });
  }

  function start() {
    Platform.init();
    applyGlass();
    fit();
    window.addEventListener('resize', fit);
    Home.init();
    Films.init({
      mode: U.get('filmMode', 'moving'),
      onScene: function (s) { Home.showPlace(s); }
    });
    document.addEventListener('keydown', onKey, true);
    document.addEventListener('visibilitychange', visibility);
    document.addEventListener('webkitvisibilitychange', visibility);
    // webOS sends this when Home is opened again while it was running in the background.
    document.addEventListener('webOSRelaunch', function () { Films.setHidden(false); Home.focusRail(0); });
    Weather.guessPlace(function () {
      Home.refreshWeather();
      maybePicks();
    });
    setInterval(maybePicks, 30 * 60000);
    setInterval(function () {
      if (!holding && Date.now() - lastKey > IDLE_HOLD_MS) { holding = true; Films.setHolding(true); }
    }, 30000);
    // Preview helpers for screenshots: ?focus=rail:2 or row:1, ?screen=inputs|apps|settings, ?keys=down,down,ok
    setTimeout(function () {
      if (U.params.focus) { var f = U.params.focus.split(':'); Home.focusAt(f[0], +(f[1] || 0)); }
      if (U.params.screen) Screens.open(U.params.screen);
      if (U.params.keys) {
        var ks = U.params.keys.split(',');
        for (var i = 0; i < ks.length; i++) { if (Screens.isOpen()) Screens.key(ks[i]); else Home.key(ks[i]); }
      }
    }, +(U.params.wait || 400));
  }

  return {
    VERSION: VERSION,
    start: start,
    applyGlass: applyGlass,
    picksChanged: function () {
      if (!Home.picksOn()) { Home.setPicks([]); return; }
      Home.setPicks(Picks.items());
      maybePicks();
    }
  };
})();

App.start();
