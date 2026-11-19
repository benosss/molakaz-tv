/*
 * The living background: nature films played one after another, cross-faded.
 *
 * Two <video> elements take turns. Many TVs have one hardware video decoder, so the second film may refuse to start
 * while the first still plays: that is detected (no frame within a few seconds, or a decode error), remembered, and
 * from then on films change by fading through the next film's still picture instead.
 *
 * Order: a shuffle bag like the Android app. Every film plays once before any repeats, never the same film twice in
 * a row, never two films from the same country back to back.
 */
var Films = (function () {
  'use strict';

  var SCENES_URL = 'https://raw.githubusercontent.com/benosss/molakaz-tv/main/films/scenes.json';
  var RELEASE = 'https://github.com/benosss/molakaz-tv/releases/download/films-v1/';
  var FADE_MS = 2000;
  var LEAD_S = 3.5;        // start the next film this long before the current one ends
  var START_TIMEOUT = 9000; // no picture by then = this TV can't run a second decoder (or the network is stuck)

  var filmBase = U.params.films || RELEASE;
  var stillBase = U.params.stills || filmBase;

  var list = [];
  var cur = null;
  var vids = [];
  var active = 0;
  var stills = [];
  var topStill = 0;
  var busy = false;
  var running = false;
  var hidden = false;
  var mode = 'moving'; // 'moving' or 'stills'
  var holding = false; // idle: keep looping the film on screen (no new downloads)
  var singleDecoder = !!U.get('singleDecoder', false) || U.params.decoders === '1';
  var stillTimer = null;
  var onScene = function () {};

  function regionOf(s) {
    var l = s.label || '';
    var i = l.lastIndexOf(', ');
    var r = i >= 0 ? l.substr(i + 2) : '';
    var j = r.indexOf(' ·');
    if (j >= 0) r = r.substr(0, j);
    return r.replace(/^\s+|\s+$/g, '');
  }

  function byName(n) {
    for (var i = 0; i < list.length; i++) if (list[i].name === n) return list[i];
    return null;
  }

  /** Next film from the bag: not the current one, not the same country, not one that just closed the last round. */
  function pickNext() {
    if (!list.length) return null;
    var bagNames = U.get('bag', []);
    var recent = U.get('recent', []);
    var bag = [];
    var i;
    for (i = 0; i < bagNames.length; i++) {
      var s = byName(bagNames[i]);
      if (s && s !== cur) bag.push(s);
    }
    if (!bag.length) {
      for (i = 0; i < list.length; i++) if (list[i] !== cur) bag.push(list[i]);
      if (!bag.length) bag = list.slice(0);
    }
    var hold = recent.slice(0, Math.max(1, Math.min(5, Math.floor(list.length / 4))));
    var region = cur ? regionOf(cur) : '';
    function pool(useHold, useRegion) {
      var out = [];
      for (var k = 0; k < bag.length; k++) {
        var c = bag[k];
        if (useHold && U.indexOf(hold, c.name) >= 0) continue;
        if (useRegion && region && regionOf(c) === region) continue;
        out.push(c);
      }
      return out;
    }
    var p = pool(true, true);
    if (!p.length) p = pool(false, true);
    if (!p.length) {
      // The bag only holds films from this country: take any other film from another country, outside the bag.
      for (i = 0; i < list.length; i++) if (list[i] !== cur && (!region || regionOf(list[i]) !== region)) p.push(list[i]);
    }
    if (!p.length) p = bag;
    var pick = p[Math.floor(Math.random() * p.length)];
    var left = [];
    for (i = 0; i < bag.length; i++) if (bag[i] !== pick) left.push(bag[i].name);
    U.set('bag', left);
    recent.unshift(pick.name);
    U.set('recent', recent.slice(0, 12));
    return pick;
  }

  function filmUrl(s) { return filmBase + s.name + '.mp4'; }
  function stillUrl(s) { return stillBase + s.name + '.jpg'; }

  function setOpacity(e, v, ms) {
    e.style.transition = e.style.webkitTransition = 'opacity ' + (ms == null ? FADE_MS : ms) + 'ms ease-in-out';
    e.style.opacity = String(v);
  }

  /** Free a decoder: on TVs a paused video still holds it. */
  function release(v) {
    v.onTick = null;
    try { v.pause(); } catch (e) { /* ignore */ }
    v.removeAttribute('src');
    try { v.load(); } catch (e2) { /* ignore */ }
  }

  /**
   * Load [s] into [v] and play it; cb(true) once a frame has really been decoded, cb(false) on error or timeout.
   * Returns a function that gives up early (cb(false)).
   */
  function playOn(v, s, cb) {
    var done = false;
    var timer = setTimeout(function () { finish(false); }, START_TIMEOUT);
    function finish(ok) {
      if (done) return;
      done = true;
      clearTimeout(timer);
      v.removeEventListener('timeupdate', onTime);
      v.removeEventListener('error', onErr);
      cb(ok);
    }
    function onTime() { if (v.currentTime > 0.04) finish(true); }
    function onErr() { finish(false); }
    v.addEventListener('timeupdate', onTime);
    v.addEventListener('error', onErr);
    v.muted = true;
    v.loop = false;
    v.src = filmUrl(s);
    try { v.load(); } catch (e) { /* ignore */ }
    try {
      var r = v.play();
      if (r && typeof r.then === 'function') r.then(null, function () { /* autoplay refusal shows up as a timeout */ });
    } catch (e2) { finish(false); }
    return function () { finish(false); };
  }

  /**
   * Did the second film fail because the decoder is taken (not because the network is slow)? It had data to show,
   * or the TV said it couldn't decode. Two such failures in a row (or one decode error) and this TV is remembered as
   * a one-decoder TV.
   */
  var decoderFails = 0;
  function blameDecoder(v) {
    var buffered = 0;
    try { if (v.buffered && v.buffered.length) buffered = v.buffered.end(v.buffered.length - 1); } catch (e) { /* ignore */ }
    var decodeErr = !!(v.error && v.error.code === 3);
    if (decodeErr || buffered >= 1.5) decoderFails++;
    else decoderFails = 0;
    if (decodeErr || decoderFails >= 2) { singleDecoder = true; U.set('singleDecoder', true); }
  }

  /** Watch the playing film so the next one starts in time. */
  function watch(v) {
    v.onTick = function () {
      if (busy || hidden || !running) return;
      if (holding) { if (!v.loop) v.loop = true; return; }
      if (v.loop) v.loop = false;
      var d = v.duration;
      if (d && isFinite(d) && v.currentTime >= d - LEAD_S) next();
    };
  }

  function showStill(s, ms, cb) {
    // Two pictures cross-fade when films are off; one is enough to bridge a film change.
    var img = stills[topStill];
    var other = stills[1 - topStill];
    var fired = false;
    function go() {
      if (fired) return;
      fired = true;
      img.style.zIndex = 4;
      other.style.zIndex = 3;
      setOpacity(img, 1, ms);
      setTimeout(function () { setOpacity(other, 0, 0); if (cb) cb(); }, (ms || 0) + 60);
    }
    img.onload = go;
    img.onerror = go;
    img.src = stillUrl(s);
    if (img.complete && img.naturalWidth) go();
    setTimeout(go, 2500);
    topStill = 1 - topStill;
  }

  function hideStills(ms) {
    setOpacity(stills[0], 0, ms);
    setOpacity(stills[1], 0, ms);
  }

  function setScene(s) {
    cur = s;
    onScene(s);
  }

  /** First film: its still at once, then the film fades in over it when it plays. */
  function begin() {
    var s = cur || pickNext();
    if (!s) return;
    setScene(s);
    showStill(s, 0);
    if (mode === 'stills') { scheduleStill(); return; }
    var v = vids[active];
    setOpacity(vids[1 - active], 0, 0);
    v.style.zIndex = 2;
    vids[1 - active].style.zIndex = 1;
    setOpacity(v, 0, 0);
    busy = true;
    playOn(v, s, function (ok) {
      busy = false;
      if (!ok) { release(v); setTimeout(function () { if (running && !hidden) next(); }, 4000); return; }
      setOpacity(v, 1, 0);
      watch(v);
      hideStills(1200);
    });
  }

  function next() {
    if (busy || !running || hidden) return;
    var nx = pickNext();
    if (!nx) return;
    if (mode === 'stills') { setScene(nx); showStill(nx, FADE_MS); scheduleStill(); return; }
    busy = true;
    var from = vids[active];
    var to = vids[1 - active];
    if (singleDecoder || !from.getAttribute('src')) { viaStill(nx); return; }
    to.style.zIndex = 2;
    from.style.zIndex = 1;
    setOpacity(to, 0, 0);
    // Preload the still too: it's the fallback if this TV turns out to have one decoder.
    (new Image()).src = stillUrl(nx);
    function ranOut() { from.removeEventListener('ended', ranOut); giveUp(); }
    var giveUp = playOn(to, nx, function (ok) {
      from.removeEventListener('ended', ranOut);
      if (!running || hidden) { release(to); busy = false; return; }
      if (ok) {
        setScene(nx);
        setOpacity(to, 1);
        setTimeout(function () {
          release(from);
          active = 1 - active;
          watch(to);
          busy = false;
        }, FADE_MS + 80);
      } else {
        // No picture from the second film: this change goes through the still either way.
        blameDecoder(to);
        release(to);
        viaStill(nx);
      }
    });
    // The film on screen ended before the next one showed a frame: don't sit on a frozen picture.
    from.addEventListener('ended', ranOut);
  }

  /** One decoder: fade the next film's still over this film, swap the film underneath, fade the still away. */
  function viaStill(nx) {
    var v = vids[active];
    showStill(nx, FADE_MS, function () {
      setScene(nx);
      release(v);
      setOpacity(v, 0, 0);
      playOn(v, nx, function (ok) {
        busy = false;
        if (!running || hidden) { release(v); return; }
        if (!ok) { release(v); setTimeout(function () { if (running && !hidden) next(); }, 6000); return; }
        setOpacity(v, 1, 0);
        watch(v);
        hideStills(1200);
      });
    });
  }

  function scheduleStill() {
    clearTimeout(stillTimer);
    stillTimer = setTimeout(function () { if (running && !hidden && !holding) next(); else scheduleStill(); }, 20000);
  }

  function tick(e) { var v = e.target; if (v.onTick) v.onTick(); }
  function ended(e) { var v = e.target; if (v.onTick && !busy && running && !hidden && !holding) next(); }

  function load(cb) {
    function useList(j) {
      var clips = j && (j.clips || j.films || j);
      if (!clips || !clips.length) return false;
      list = [];
      for (var i = 0; i < clips.length; i++) if (clips[i] && clips[i].name) list.push(clips[i]);
      return list.length > 0;
    }
    var cached = U.get('scenes', null);
    U.getJSON(U.params.scenes || SCENES_URL, function (err, j) {
      if (!err && useList(j)) { U.set('scenes', j); return cb(); }
      if (cached && useList(cached)) return cb();
      U.getJSON('scenes.json', function (err2, j2) { if (!err2) useList(j2); cb(); }, 8000);
    }, 10000);
  }

  return {
    init: function (opts) {
      vids = [U.$('vA'), U.$('vB')];
      stills = [U.$('sA'), U.$('sB')];
      onScene = opts.onScene || onScene;
      mode = opts.mode || 'moving';
      for (var i = 0; i < 2; i++) {
        vids[i].addEventListener('timeupdate', tick);
        vids[i].addEventListener('ended', ended);
        vids[i].muted = true;
        vids[i].setAttribute('muted', '');
        vids[i].setAttribute('playsinline', '');
      }
      load(function () {
        if (!list.length) return;
        running = true;
        if (U.params.film) { var s = byName(U.params.film); if (s) cur = s; }
        begin();
      });
    },
    /** App hidden (another app in front): give the decoder back. Shown again: carry on with a fresh film. */
    setHidden: function (h) {
      if (h === hidden) return;
      hidden = h;
      if (!running) return;
      if (h) {
        if (cur) showStill(cur, 0);
        release(vids[0]);
        release(vids[1]);
        clearTimeout(stillTimer);
        busy = false;
      } else {
        begin();
      }
    },
    /** Idle for a while: loop the film on screen so the TV stops downloading new ones. */
    setHolding: function (h) { holding = !!h; },
    setMode: function (m) {
      if (m === mode) return;
      mode = m;
      if (!running) return;
      release(vids[0]);
      release(vids[1]);
      busy = false;
      clearTimeout(stillTimer);
      begin();
    },
    skip: function () { next(); },
    togglePause: function () {
      var v = vids[active];
      if (!v.getAttribute('src')) return;
      if (v.paused) { try { v.play(); } catch (e) { /* ignore */ } } else v.pause();
    },
    current: function () { return cur; },
    singleDecoder: function () { return singleDecoder; },
    regionOf: regionOf,
    _pickNext: pickNext,
    _setList: function (l) { list = l; },
    _setCur: function (s) { cur = s; }
  };
})();
