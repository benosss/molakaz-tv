/*
 * The home screen: the glass app bar on the left, the clock and weather top right, the place line, and the row of
 * cards (Fresh picks when they load, else your apps). Focus moves between three zones: rail, tabs and row.
 */
var Home = (function () {
  'use strict';

  var MAX_RAIL = 6;
  var CARD_STEP = 268;   // 250 wide + 18 gap
  var VIEW_W = 1060;     // the row shows x 164..1224 fully (later cards peek in at the right)

  var apps = [];
  var listed = false;
  var rail = [];
  var tabs = [];
  var tabEls = [];
  var cards = [];
  var picks = [];
  var zone = 'rail';
  var railIdx = 0;
  var rowIdx = 0;
  var tabIdx = 0;
  var rowTx = 0;
  var toastTimer = null;
  var lastScene = null;
  var whereTimer = null;
  var lastMinute = '';

  var DAYS = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'];
  var MONTHS = ['JANUARY', 'FEBRUARY', 'MARCH', 'APRIL', 'MAY', 'JUNE', 'JULY', 'AUGUST', 'SEPTEMBER', 'OCTOBER', 'NOVEMBER', 'DECEMBER'];

  // ---------------------------------------------------------------- pieces

  function glassify(e) {
    if (e.querySelector && e.querySelector('.gd')) return;
    e.insertBefore(U.el('i', 'gn'), e.firstChild);
    e.insertBefore(U.el('i', 'gd'), e.firstChild);
  }

  function initials(name) {
    var w = String(name || '?').replace(/[^A-Za-z0-9 ]/g, ' ').replace(/^\s+|\s+$/g, '').split(/\s+/);
    var s = (w[0] || '?').charAt(0) + (w.length > 1 ? w[1].charAt(0) : '');
    return s.toUpperCase();
  }

  /** An app's icon, filling its parent: the TV's own icon, else a bundled one, else its initials on its colours. */
  function iconEl(app, size) {
    var holder = U.el('div', 'iconWrap');
    var srcs = [];
    if (app.icon) srcs.push(app.icon);
    if (app.fallback) srcs.push(app.fallback);
    function letter() {
      holder.innerHTML = '';
      var l = U.el('div', 'letter', U.esc(initials(app.name)));
      l.style.background = 'linear-gradient(135deg, ' + app.colors[0] + ', ' + app.colors[1] + ')';
      l.style.fontSize = Math.round(size * 0.4) + 'px';
      holder.appendChild(l);
    }
    function tryNext() {
      if (!srcs.length) { letter(); return; }
      var img = U.el('img', 'icon');
      img.onerror = tryNext;
      img.alt = '';
      img.src = srcs.shift();
      holder.innerHTML = '';
      holder.appendChild(img);
    }
    tryNext();
    return holder;
  }

  function glyphEl(name) {
    var b = U.el('div', 'glyphBox');
    b.appendChild(U.el('div', 'g', U.ICONS[name]));
    return b;
  }

  /** The shared focusable shape: shadow, face (content + light sweep), white rim. */
  function focusable(cls, content) {
    var t = U.el('div', cls);
    t.appendChild(U.el('div', 'lift'));
    var face = U.el('div', 'face');
    face.appendChild(content);
    face.appendChild(U.el('div', 'sheen'));
    t.appendChild(face);
    t.appendChild(U.el('div', 'ring'));
    return t;
  }

  function press(e) {
    e.classList.add('press');
    setTimeout(function () { e.classList.remove('press'); }, 130);
  }

  function toast(text, ms) {
    U.$('toastText').textContent = text;
    var t = U.$('toast');
    t.classList.add('on');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.classList.remove('on'); }, ms || 2600);
  }

  // ---------------------------------------------------------------- apps

  function appByKey(k) {
    for (var i = 0; i < apps.length; i++) if (apps[i].key === k) return apps[i];
    return null;
  }

  function railKeys() {
    var saved = U.get('rail', null);
    if (saved && saved.length) return saved;
    // A TV that hasn't been set up: the known streaming apps that are installed (Spotify first), then Inputs.
    var out = [];
    for (var i = 0; i < apps.length && out.length < MAX_RAIL - 1; i++) if (apps[i].known) out.push(apps[i].key);
    out.push('inputs');
    return out;
  }

  function launch(app, el) {
    if (el) press(el);
    Platform.launch(app, function (err, how) {
      if (how === 'preview') toast('On the TV this opens ' + app.name);
      else if (err) toast('Couldn’t open ' + app.name + (listed ? '' : '. Is it installed?'));
    });
  }

  // ---------------------------------------------------------------- rail

  function buildRail() {
    var box = U.$('rail');
    for (var r = 0; r < rail.length; r++) if (rail[r].el.parentNode) rail[r].el.parentNode.removeChild(rail[r].el);
    rail = [];
    var keys = railKeys();
    var items = [];
    for (var i = 0; i < keys.length && items.length < MAX_RAIL; i++) {
      if (keys[i] === 'inputs') items.push({ kind: 'inputs', name: 'Inputs' });
      else { var a = appByKey(keys[i]); if (a) items.push({ kind: 'app', app: a, name: a.name }); }
    }
    var gap = items.length <= 4 ? 22 : 14;
    var top = 26;
    for (var k = 0; k < items.length; k++) {
      addTile(items[k], top);
      top += 62 + gap;
    }
    addTile({ kind: 'settings', name: 'Settings' }, 664 - 110 - 62);
    addTile({ kind: 'allapps', name: 'All apps' }, 664 - 26 - 62);
    railIdx = U.clamp(railIdx, 0, rail.length - 1);
    if (zone === 'rail') paintFocus();
  }

  function addTile(item, top) {
    var content = item.kind === 'app' ? iconEl(item.app, 62)
      : glyphEl(item.kind === 'inputs' ? 'inputs' : (item.kind === 'settings' ? 'gear' : 'grid'));
    var t = focusable('tile', content);
    t.style.top = top + 'px';
    var idx = rail.length;
    t.onmouseover = function () { if (!Screens.isOpen()) setFocus('rail', idx); };
    t.onclick = function () { if (!Screens.isOpen()) { setFocus('rail', idx); activate(); } };
    item.el = t;
    rail.push(item);
    U.$('rail').appendChild(t);
  }

  function railAction(item) {
    if (item.kind === 'app') launch(item.app, item.el);
    else if (item.kind === 'inputs') { press(item.el); Screens.open('inputs'); }
    else if (item.kind === 'settings') { press(item.el); Screens.open('settings'); }
    else { press(item.el); Screens.open('apps'); }
  }

  // ---------------------------------------------------------------- shelf

  function picksOn() { return U.get('picksOn', true); }

  function buildTabs() {
    var was = tabs[tabIdx] ? tabs[tabIdx].key : null;
    tabs = [];
    if (picks.length && picksOn()) tabs.push({ key: 'picks', name: 'Fresh picks' });
    tabs.push({ key: 'apps', name: 'Your apps' });
    tabIdx = 0;
    for (var i = 0; i < tabs.length; i++) if (tabs[i].key === was) tabIdx = i;
    var box = U.$('tabs');
    box.innerHTML = '';
    tabEls = [];
    for (var k = 0; k < tabs.length; k++) {
      var t = U.el('div', 'tab' + (k === tabIdx ? ' on' : ''));
      var pill = U.el('div', 'pill glass');
      glassify(pill);
      pill.appendChild(U.el('div', 'ring'));
      t.appendChild(pill);
      t.appendChild(U.el('span', 'label', U.esc(tabs[k].name)));
      (function (n) {
        t.onmouseover = function () { if (!Screens.isOpen()) setFocus('tabs', n); };
        t.onclick = function () { if (!Screens.isOpen()) selectTab(n); };
      })(k);
      box.appendChild(t);
      tabEls.push(t);
    }
  }

  function appsRow() {
    var out = [];
    var seen = {};
    var keys = railKeys();
    var i, a;
    for (i = 0; i < keys.length; i++) {
      a = appByKey(keys[i]);
      if (a && !seen[a.key]) { seen[a.key] = 1; out.push({ kind: 'app', app: a }); }
    }
    for (i = 0; i < apps.length && out.length < 12; i++) {
      a = apps[i];
      if (!seen[a.key]) { seen[a.key] = 1; out.push({ kind: 'app', app: a }); }
    }
    out.push({ kind: 'inputs' });
    return out;
  }

  function cardEl(item) {
    var c;
    if (item.kind === 'pick') {
      var p = item.pick;
      var inner = U.el('div', 'cardIn');
      var app = Platform.knownByKey(p.app);
      inner.style.background = 'linear-gradient(135deg, ' + (app ? app.colors[0] : '#0E7490') + ', ' + (app ? app.colors[1] : '#062A3A') + ')';
      if (p.art) {
        var img = U.el('img', 'art');
        img.alt = '';
        img.onerror = function () { if (img.parentNode) img.parentNode.removeChild(img); };
        img.src = p.art;
        inner.appendChild(img);
      }
      inner.appendChild(U.el('div', 'shade'));
      inner.appendChild(U.el('div', 'txt',
        '<div class="badge">' + U.esc(p.badge) + '</div><div class="title">' + U.esc(p.title) + '</div>' +
        (p.sub ? '<div class="sub">' + U.esc(p.sub) + '</div>' : '')));
      c = focusable('card', inner);
    } else {
      var a = item.app;
      var box = U.el('div', 'cardIn');
      var colors = a ? a.colors : ['#0E5A73', '#06222E'];
      box.style.background = 'linear-gradient(135deg, ' + colors[0] + ', ' + colors[1] + ')';
      box.appendChild(U.el('div', 'glow'));
      var ic = U.el('div', 'appIcon');
      ic.appendChild(a ? iconEl(a, 72) : glyphEl('inputs'));
      box.appendChild(ic);
      box.appendChild(U.el('div', 'appName soft', U.esc(a ? a.name : 'Inputs')));
      c = focusable('card', box);
    }
    return c;
  }

  function buildRow(dir) {
    var row = U.$('row');
    var old = cards;
    var items = tabs[tabIdx] && tabs[tabIdx].key === 'picks'
      ? picks.map(function (p) { return { kind: 'pick', pick: p }; })
      : appsRow();
    // Old cards slide away, new ones fade in one after another.
    for (var o = 0; o < old.length; o++) {
      old[o].el.style.opacity = '0';
      U.setTransform(old[o].el, 'translateX(' + (-(dir || 1) * 30) + 'px)');
    }
    setTimeout(function () {
      for (var r = 0; r < old.length; r++) if (old[r].el.parentNode === row) row.removeChild(old[r].el);
    }, dir ? 170 : 0);
    cards = [];
    rowTx = 0;
    U.setTransform(row, 'translateX(0px)');
    for (var i = 0; i < items.length; i++) {
      var el = cardEl(items[i]);
      el.style.left = (i * CARD_STEP) + 'px';
      el.className += ' enter';
      (function (n, e) {
        e.onmouseover = function () { if (!Screens.isOpen()) setFocus('row', n); };
        e.onclick = function () { if (!Screens.isOpen()) { setFocus('row', n); activate(); } };
        setTimeout(function () { e.classList.remove('enter'); }, (dir ? 170 : 30) + 40 * n);
      })(i, el);
      items[i].el = el;
      cards.push(items[i]);
      row.appendChild(el);
    }
    rowIdx = U.clamp(rowIdx, 0, Math.max(0, cards.length - 1));
    U.$('empty').classList.toggle('on', cards.length === 0);
    scrollRow();
    if (zone === 'row') paintFocus();
  }

  function selectTab(i) {
    if (i === tabIdx || !tabs[i]) { setFocus('tabs', i); return; }
    var dir = i > tabIdx ? 1 : -1;
    tabEls[tabIdx].classList.remove('on');
    tabIdx = i;
    tabEls[tabIdx].classList.add('on');
    rowIdx = 0;
    buildRow(dir);
    setFocus('tabs', i);
  }

  function scrollRow() {
    var left = rowIdx * CARD_STEP;
    if (left + 250 + rowTx > VIEW_W) rowTx = VIEW_W - 250 - left;
    if (left + rowTx < 0) rowTx = -left;
    U.setTransform(U.$('row'), 'translateX(' + rowTx + 'px)');
    // Cards that pass the left edge fade, so they never slide under the app bar.
    for (var i = 0; i < cards.length; i++) {
      var gone = i * CARD_STEP + rowTx < -20;
      if (!cards[i].el.classList.contains('enter')) cards[i].el.style.opacity = gone ? '0' : '';
    }
  }

  function openCard(item) {
    press(item.el);
    if (item.kind === 'app') { launch(item.app, null); return; }
    if (item.kind === 'inputs') { Screens.open('inputs'); return; }
    var p = item.pick;
    var app = appByKey(p.app);
    var kn = Platform.knownByKey(p.app);
    if (!app) { toast((kn ? kn.name : 'That app') + ' isn’t on this TV'); return; }
    Platform.openLink(app, p.link, function (err, how) {
      if (how === 'preview') toast('On the TV this opens “' + p.title + '” in ' + app.name);
      else if (err) toast('Couldn’t open ' + app.name);
    });
  }

  // ---------------------------------------------------------------- focus

  function focusEl() {
    if (zone === 'rail') return rail[railIdx] && rail[railIdx].el;
    if (zone === 'tabs') return tabEls[tabIdx];
    return cards[rowIdx] && cards[rowIdx].el;
  }

  var painted = null;
  function paintFocus() {
    var e = focusEl();
    if (painted && painted !== e) painted.classList.remove('focus');
    if (e) e.classList.add('focus');
    painted = e;
  }

  function setFocus(z, i) {
    if (z === 'row' && !cards.length) z = 'tabs';
    zone = z;
    if (z === 'rail') railIdx = U.clamp(i, 0, rail.length - 1);
    else if (z === 'tabs') tabIdx = U.clamp(i, 0, tabs.length - 1);
    else { rowIdx = U.clamp(i, 0, cards.length - 1); scrollRow(); }
    paintFocus();
  }

  function activate() {
    if (zone === 'rail') railAction(rail[railIdx]);
    else if (zone === 'tabs') setFocus('row', 0);
    else if (cards[rowIdx]) openCard(cards[rowIdx]);
  }

  /** Remote keys while Home is in front. Returns true when handled. */
  function key(a) {
    if (zone === 'rail') {
      if (a === 'up') setFocus('rail', railIdx - 1);
      else if (a === 'down') setFocus('rail', railIdx + 1);
      else if (a === 'right') setFocus(cards.length ? 'row' : 'tabs', cards.length ? rowIdx : tabIdx);
      else if (a === 'ok') activate();
      else return false;
      return true;
    }
    if (zone === 'tabs') {
      if (a === 'left') { if (tabIdx > 0) selectTab(tabIdx - 1); else setFocus('rail', railIdx); }
      else if (a === 'right') { if (tabIdx < tabs.length - 1) selectTab(tabIdx + 1); }
      else if (a === 'down' || a === 'ok') setFocus('row', rowIdx);
      else if (a === 'up') { /* nothing above */ }
      else return false;
      return true;
    }
    if (a === 'left') { if (rowIdx > 0) setFocus('row', rowIdx - 1); else setFocus('rail', railIdx); }
    else if (a === 'right') setFocus('row', rowIdx + 1);
    else if (a === 'up') setFocus('tabs', tabIdx);
    else if (a === 'ok') activate();
    else if (a === 'down') { /* bottom row */ }
    else return false;
    return true;
  }

  /** Back on Home: go to the bar first; on the bar, Back twice closes the app. */
  var backAt = 0;
  function back() {
    if (zone !== 'rail') { setFocus('rail', railIdx); return; }
    var t = Date.now();
    if (t - backAt < 2500) { Platform.exit(); return; }
    backAt = t;
    toast('Press Back again to close MoLakaz TV', 2500);
  }

  // ---------------------------------------------------------------- clock, weather, place, theme

  function two(n) { return n < 10 ? '0' + n : '' + n; }

  function use24() {
    var s = U.get('clock24', null);
    if (s !== null) return s;
    try { return !/am|pm/i.test(new Date(2020, 0, 1, 15, 0).toLocaleTimeString()); } catch (e) { return true; }
  }

  function tickClock() {
    var t = new Date(U.now());
    var h = t.getHours();
    var hh = use24() ? two(h) : String(h % 12 === 0 ? 12 : h % 12);
    var text = hh + ':' + two(t.getMinutes());
    var hello = h < 5 ? 'GOOD NIGHT' : h < 12 ? 'GOOD MORNING' : h < 17 ? 'GOOD AFTERNOON' : 'GOOD EVENING';
    var line = hello + ' · ' + DAYS[t.getDay()] + ' ' + t.getDate() + ' ' + MONTHS[t.getMonth()];
    if (text + line !== lastMinute) {
      lastMinute = text + line;
      U.$('clock').textContent = text;
      U.$('greet').textContent = line;
      applyTheme();
    }
  }

  function applyTheme() {
    var night = Weather.isNight(U.now());
    document.documentElement.classList.toggle('night', night);
  }

  function showWeather(w) {
    var s = U.$('status');
    var p = Weather.place();
    if (!w) { s.innerHTML = '<span>' + U.esc(p.name) + '</span>'; s.classList.add('on'); return; }
    var night = Weather.isNight(U.now());
    s.innerHTML = '<div class="wx">' + Weather.icon(w.code, night) + '</div><b>' + w.temp + '°</b><span>' + U.esc(p.name) + '</span>';
    s.classList.add('on');
    applyTheme();
  }

  function refreshWeather() {
    var w = Weather.current();
    if (w && w.place === Weather.place().name) showWeather(w); else showWeather(null);
    if (Weather.stale()) Weather.refresh(function (nw) { showWeather(nw); });
  }

  function showPlace(s) {
    lastScene = s;
    var w = U.$('where');
    w.classList.remove('on');
    clearTimeout(whereTimer);
    whereTimer = setTimeout(function () {
      if (lastScene !== s || !s) return;
      w.innerHTML = '<div class="pin">' + U.ICONS.pin + '</div><span class="name">' + U.esc(s.label || '') + '</span>' +
        (s.about ? '<span class="about">' + U.esc(s.about) + '</span>' : '');
      w.classList.add('on');
    }, 800);
  }

  // ---------------------------------------------------------------- public

  function loadApps(cb) {
    Platform.apps(function (list, ok) {
      apps = list;
      listed = ok;
      buildRail();
      buildTabs();
      buildRow(0);
      if (cb) cb();
    });
  }

  function setPicks(items) {
    picks = items || [];
    var wasPicks = tabs[tabIdx] && tabs[tabIdx].key === 'picks';
    buildTabs();
    // When picks arrive for the first time, show them.
    if (!wasPicks && tabs[0].key === 'picks' && zone !== 'row') tabIdx = 0;
    for (var i = 0; i < tabEls.length; i++) tabEls[i].classList.toggle('on', i === tabIdx);
    buildRow(0);
    if (zone === 'tabs') paintFocus();
  }

  return {
    init: function () {
      glassify(U.$('rail'));
      glassify(U.$('sheet'));
      glassify(U.$('toast'));
      U.$('empty').textContent = '';
      tickClock();
      setInterval(tickClock, 1000);
      refreshWeather();
      setInterval(refreshWeather, 5 * 60000);
      picks = picksOn() ? Picks.items() : [];
      loadApps(function () { setFocus('rail', 0); });
    },
    key: key,
    back: back,
    toast: toast,
    showPlace: showPlace,
    setPicks: setPicks,
    picksOn: picksOn,
    apps: function () { return apps; },
    listed: function () { return listed; },
    appByKey: appByKey,
    railKeys: railKeys,
    rebuild: function () { buildRail(); buildTabs(); buildRow(0); paintFocus(); },
    reloadApps: loadApps,
    refreshWeather: refreshWeather,
    tickClock: function () { lastMinute = ''; tickClock(); },
    iconEl: iconEl,
    glyphEl: glyphEl,
    glassify: glassify,
    launch: launch,
    focusRail: function (i) { setFocus('rail', i); },
    focusAt: function (z, i) { setFocus(z === 'row' || z === 'tabs' ? z : 'rail', i); },
    MAX_RAIL: MAX_RAIL
  };
})();
