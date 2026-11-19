/*
 * The screens that open over Home on a glass sheet: Inputs (HDMI 1-4, Live TV), All apps, and Settings (town for
 * the weather, the apps on the bar and their order, a few switches). Focus moves spatially: the nearest focusable
 * thing in the direction pressed.
 */
var Screens = (function () {
  'use strict';

  var current = null;   // 'inputs' | 'apps' | 'settings'
  var page = null;      // settings page: 'main' | 'rail' | 'town'
  var fx = [];
  var cur = null;
  var scroller = null;
  var scrollY = 0;
  var bodyH = 448;
  var input = null;
  var searchTimer = null;

  // ---------------------------------------------------------------- frame

  function clearSheet() {
    var s = U.$('sheet');
    var keep = [];
    for (var i = 0; i < s.childNodes.length; i++) {
      var n = s.childNodes[i];
      if (n.className === 'gd' || n.className === 'gn') keep.push(n);
    }
    s.innerHTML = '';
    for (var k = 0; k < keep.length; k++) s.appendChild(keep[k]);
    fx = [];
    cur = null;
    input = null;
    scrollY = 0;
    return s;
  }

  /** A fresh sheet: title, subtitle, a scrolling body and a hint line. [size] is '', 'wide' or 'short'. */
  function frame(title, sub, hint, size) {
    var s = clearSheet();
    s.className = 'glass panel on' + (size ? ' ' + size : '');
    var head = U.el('div', 'sheetHead', '<h1>' + U.esc(title) + '</h1>' + (sub ? '<p>' + U.esc(sub) + '</p>' : ''));
    s.appendChild(head);
    var body = U.el('div', 'sheetBody');
    scroller = U.el('div', 'scroller');
    body.appendChild(scroller);
    s.appendChild(body);
    if (hint) s.appendChild(U.el('div', 'hint', U.esc(hint)));
    bodyH = body.offsetHeight || bodyH;
    return scroller;
  }

  function addFx(e, ok) {
    e.className += ' fx';
    e._ok = ok;
    e.onmouseover = function () { focus(e); };
    e.onclick = function () { focus(e); if (e._ok) e._ok(e); };
    fx.push(e);
    return e;
  }

  function focus(e) {
    if (!e) return;
    if (cur && cur !== e) cur.classList.remove('focus');
    cur = e;
    e.classList.add('focus');
    reveal(e);
  }

  /** Keep the focused thing inside the sheet's body, scrolling the content. */
  function reveal(e) {
    if (!scroller) return;
    var top = 0;
    var n = e;
    while (n && n !== scroller) { top += n.offsetTop; n = n.offsetParent; }
    var h = e.offsetHeight;
    var maxY = Math.max(0, scroller.offsetHeight - bodyH);
    if (top - 12 < scrollY) scrollY = Math.max(0, top - 12);
    else if (top + h + 24 > scrollY + bodyH) scrollY = Math.min(maxY, top + h + 24 - bodyH);
    U.setTransform(scroller, 'translateY(' + (-scrollY) + 'px)');
  }

  function center(e) {
    var r = e.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, top: r.top, bottom: r.bottom };
  }

  function move(dir) {
    if (!cur) { focus(fx[0]); return; }
    var a = center(cur);
    var best = null;
    var bestScore = 1e9;
    for (var i = 0; i < fx.length; i++) {
      var f = fx[i];
      if (f === cur || !f.offsetParent) continue;
      var b = center(f);
      var dx = b.x - a.x;
      var dy = b.y - a.y;
      var main, cross;
      if (dir === 'up') { if (dy > -4) continue; main = -dy; cross = Math.abs(dx); }
      else if (dir === 'down') { if (dy < 4) continue; main = dy; cross = Math.abs(dx); }
      else {
        // Left and right stay on the same line.
        if (b.bottom <= a.top + 4 || b.top >= a.bottom - 4) continue;
        if (dir === 'left') { if (dx > -4) continue; main = -dx; } else { if (dx < 4) continue; main = dx; }
        cross = Math.abs(dy);
      }
      var score = main + cross * (dir === 'left' || dir === 'right' ? 3 : 1.2);
      if (score < bestScore) { bestScore = score; best = f; }
    }
    if (best) focus(best);
  }

  function open(name) {
    current = name;
    U.$('scrim').classList.add('on');
    if (name === 'inputs') buildInputs();
    else if (name === 'apps') buildApps();
    else { page = 'main'; buildSettings(); }
  }

  function close() {
    if (input) { try { input.blur(); } catch (e) { /* ignore */ } }
    current = null;
    page = null;
    U.$('scrim').classList.remove('on');
    U.$('sheet').classList.remove('on');
    fx = [];
    cur = null;
  }

  // ---------------------------------------------------------------- inputs

  function buildInputs() {
    var sc = frame('Inputs', 'Switch the TV to something plugged into it.', 'OK switches · Back returns to Home', 'short');
    var box = U.el('div', 'inputs');
    var ins = Platform.INPUTS;
    for (var i = 0; i < ins.length; i++) {
      (function (inp) {
        var t = U.el('div', 'inputTile big');
        t.appendChild(U.el('div', 'bgi'));
        t.appendChild(U.el('div', 'g', U.ICONS[inp.icon]));
        t.appendChild(U.el('div', 'n', U.esc(inp.name)));
        t.appendChild(U.el('div', 's', inp.key === 'tv' ? 'Aerial or cable' : 'HDMI input'));
        t.appendChild(U.el('div', 'ring'));
        addFx(t, function () {
          Platform.openInput(inp, function (err, how) {
            if (how === 'preview') Home.toast('On the TV this switches to ' + inp.name);
            else if (err) Home.toast(Platform.name === 'tizen' && inp.key !== 'tv'
              ? 'Press SOURCE on the remote to pick ' + inp.name : 'Couldn’t switch to ' + inp.name);
          });
        });
        box.appendChild(t);
      })(ins[i]);
    }
    sc.appendChild(box);
    focus(fx[0]);
  }

  // ---------------------------------------------------------------- all apps

  function buildApps() {
    var list = Home.apps();
    var sc = frame('All apps', list.length + ' apps on this TV', 'OK opens · Back returns to Home', 'wide');
    if (!Home.listed()) {
      sc.appendChild(U.el('div', 'note', 'This TV didn’t share its list of apps, so these are the ones MoLakaz TV knows. One that isn’t installed won’t open.'));
    }
    var grid = U.el('div', 'appsGrid');
    var inputsCell = U.el('div', 'appCell');
    var ic0 = U.el('div', 'ic');
    var gb = Home.glyphEl('inputs');
    gb.style.background = 'linear-gradient(135deg, #0E5A73, #06222E)';
    ic0.appendChild(gb);
    inputsCell.appendChild(ic0);
    inputsCell.appendChild(U.el('div', 'nm', 'Inputs'));
    inputsCell.appendChild(U.el('div', 'ring'));
    addFx(inputsCell, function () { close(); open('inputs'); });
    grid.appendChild(inputsCell);
    for (var i = 0; i < list.length; i++) {
      (function (a) {
        var c = U.el('div', 'appCell');
        var ic = U.el('div', 'ic');
        ic.appendChild(Home.iconEl(a, 80));
        c.appendChild(ic);
        c.appendChild(U.el('div', 'nm', U.esc(a.name)));
        c.appendChild(U.el('div', 'ring'));
        addFx(c, function () { Home.launch(a, c); });
        grid.appendChild(c);
      })(list[i]);
    }
    sc.appendChild(grid);
    focus(fx[0]);
  }

  // ---------------------------------------------------------------- settings

  function rowEl(k, v, extra) {
    var r = U.el('div', 'row');
    r.appendChild(U.el('div', 'k', U.esc(k)));
    if (v != null) r.appendChild(U.el('div', 'v', U.esc(v)));
    if (extra) r.appendChild(extra);
    r.appendChild(U.el('div', 'ring'));
    return r;
  }

  function switchEl(on) {
    var s = U.el('div', 'switch' + (on ? ' on' : ''));
    s.appendChild(U.el('i'));
    return s;
  }

  function chev() { return U.el('div', 'chev', U.ICONS.chevron); }

  function buildSettings(focusKey) {
    if (page === 'rail') return buildRailPage(focusKey);
    if (page === 'town') return buildTownPage();
    var sc = frame('Settings', null, 'OK changes · Back returns to Home');
    var p = Weather.place();
    var rows = [];

    sc.appendChild(U.el('div', 'sectionTitle', 'HOME'));
    rows.push(sc.appendChild(addFx(rowEl('Weather town', p.name + (p.country ? ', ' + p.country : ''), chev()), function () { page = 'town'; buildSettings(); })));
    rows[rows.length - 1]._key = 'town';
    var n = 0;
    var keys = Home.railKeys();
    for (var i = 0; i < keys.length; i++) if (keys[i] === 'inputs' || Home.appByKey(keys[i])) n++;
    rows.push(sc.appendChild(addFx(rowEl('Apps on the bar', Math.min(n, Home.MAX_RAIL) + ' of ' + Home.MAX_RAIL, chev()), function () { page = 'rail'; buildSettings(); })));
    rows[rows.length - 1]._key = 'rail';

    sc.appendChild(U.el('div', 'sectionTitle', 'LOOK AND FEEL'));
    function toggle(key, label, value, apply, sub) {
      var r = addFx(rowEl(label, sub || null, switchEl(value)), function () {
        apply(!value);
        buildSettings(key);
      });
      r._key = key;
      rows.push(sc.appendChild(r));
    }
    toggle('picks', 'Fresh picks', Home.picksOn(), function (v) {
      U.set('picksOn', v);
      App.picksChanged();
    }, Home.picksOn() && !Picks.items().length ? 'Not reachable from this TV yet' : null);
    toggle('films', 'Moving films', U.get('filmMode', 'moving') === 'moving', function (v) {
      U.set('filmMode', v ? 'moving' : 'stills');
      Films.setMode(v ? 'moving' : 'stills');
    }, U.get('filmMode', 'moving') === 'moving' ? 'About 6 Mbps while Home is on screen' : 'Still pictures, almost no data');
    var canBlur = U.hasBackdrop();
    toggle('glass', 'Frosted glass', canBlur && U.get('blur', true), function (v) {
      if (!canBlur) { Home.toast('This TV’s browser can’t blur'); return; }
      U.set('blur', v);
      App.applyGlass();
    }, canBlur ? 'Turn off if Home feels slow' : 'Not supported on this TV');
    toggle('clock', '24-hour clock', U.get('clock24', null) === null ? !/am|pm/i.test(new Date(2020, 0, 1, 15, 0).toLocaleTimeString()) : U.get('clock24', true), function (v) {
      U.set('clock24', v);
      Home.tickClock();
    });

    sc.appendChild(U.el('div', 'note', 'MoLakaz TV ' + App.VERSION + ' for ' + Platform.label + '. Films from Pexels (credits in the project’s CREDITS.md), weather by Open-Meteo.' +
      (Films.singleDecoder() ? ' This TV plays one film at a time, so films change through a still picture.' : '')));

    var target = rows[0];
    for (var k = 0; k < rows.length; k++) if (rows[k]._key === focusKey) target = rows[k];
    focus(target);
  }

  function buildTownPage() {
    var sc = frame('Weather town', 'Type a town and pick it from the list.', 'OK on the box opens the keyboard · Back returns to Settings');
    var box = U.el('div', 'searchBox');
    box.appendChild(U.el('div', 'g', U.ICONS.search));
    input = U.el('input');
    input.type = 'text';
    input.placeholder = 'Port Louis, Paris, Cape Town…';
    input.value = U.get('townQuery', '');
    box.appendChild(input);
    box.appendChild(U.el('div', 'ring'));
    sc.appendChild(addFx(box, function () { try { input.focus(); } catch (e) { /* ignore */ } }));
    var results = U.el('div', 'results');
    sc.appendChild(results);

    function run() {
      var q = input.value.replace(/^\s+|\s+$/g, '');
      U.set('townQuery', q);
      if (q.length < 2) { results.innerHTML = ''; return; }
      results.innerHTML = '<div class="note">Looking for “' + U.esc(q) + '”…</div>';
      Weather.search(q, function (list) {
        if (page !== 'town') return;
        results.innerHTML = '';
        // Drop the old result rows from the focus list.
        var keep = [];
        for (var i = 0; i < fx.length; i++) if (!fx[i]._result) keep.push(fx[i]);
        fx = keep;
        if (!list) { results.innerHTML = '<div class="note">Couldn’t reach the town search. Check the TV’s internet.</div>'; return; }
        if (!list.length) { results.innerHTML = '<div class="note">No town called “' + U.esc(q) + '”.</div>'; return; }
        for (var k = 0; k < list.length; k++) {
          (function (pl) {
            var where = [pl.admin, pl.country].filter(function (x) { return x && x !== pl.name; }).join(', ');
            var r = addFx(rowEl(pl.name, where), function () {
              Weather.setPlace({ name: pl.name, country: pl.country, cc: pl.cc, lat: pl.lat, lon: pl.lon });
              Home.refreshWeather();
              App.picksChanged();
              Home.toast('Weather for ' + pl.name);
              page = 'main';
              buildSettings('town');
            });
            r._result = true;
            results.appendChild(r);
          })(list[k]);
        }
        // Typed and pressed Enter: land on the first town, so OK picks it.
        if (document.activeElement !== input) {
          for (var f = 0; f < fx.length; f++) if (fx[f]._result) { focus(fx[f]); break; }
        }
      });
    }
    input.onkeydown = function (e) {
      var c = e.keyCode;
      if (c === 13 || c === 65376) { e.preventDefault(); e.stopPropagation(); run(); try { input.blur(); } catch (x) { /* ignore */ } focus(fx[1] || box); }
      else if (c === 65385 || c === 27) { e.preventDefault(); e.stopPropagation(); try { input.blur(); } catch (x2) { /* ignore */ } }
    };
    input.oninput = function () { clearTimeout(searchTimer); searchTimer = setTimeout(run, 600); };
    focus(box);
    if (input.value) run();
  }

  function buildRailPage(focusKey) {
    var sc = frame('Apps on the bar', 'Up to ' + Home.MAX_RAIL + '. OK on an app takes it off or puts it on; the arrows move it.', 'Back returns to Settings');
    var keys = [];
    var all = Home.railKeys();
    for (var i = 0; i < all.length; i++) if (all[i] === 'inputs' || Home.appByKey(all[i])) keys.push(all[i]);
    keys = keys.slice(0, Home.MAX_RAIL);

    function save(k) { U.set('rail', k); Home.rebuild(); }
    function label(k) { return k === 'inputs' ? 'Inputs' : Home.appByKey(k).name; }
    function iconOf(k) {
      var ic = U.el('div', 'ic');
      if (k === 'inputs') { var g = Home.glyphEl('inputs'); g.style.background = 'linear-gradient(135deg, #0E5A73, #06222E)'; ic.appendChild(g); }
      else ic.appendChild(Home.iconEl(Home.appByKey(k), 38));
      return ic;
    }
    var target = null;

    sc.appendChild(U.el('div', 'sectionTitle', 'ON THE BAR'));
    for (var r = 0; r < keys.length; r++) {
      (function (k, idx) {
        var line = U.el('div', 'railRow');
        var row = rowEl(label(k), 'On', U.el('div', 'chev', U.ICONS.check));
        row.insertBefore(iconOf(k), row.firstChild);
        addFx(row, function () {
          var nk = keys.slice(0);
          nk.splice(idx, 1);
          if (!nk.length) { Home.toast('Keep at least one app on the bar'); return; }
          save(nk);
          buildRailPage(k + ':row');
        });
        row._key = k + ':row';
        line.appendChild(row);
        var up = U.el('div', 'btn' + (idx === 0 ? ' off' : ''));
        up.appendChild(U.el('div', 'g', U.ICONS.up));
        up.appendChild(U.el('div', 'ring'));
        addFx(up, function () {
          if (idx === 0) return;
          var nk = keys.slice(0);
          nk[idx] = nk[idx - 1]; nk[idx - 1] = k;
          save(nk);
          buildRailPage(k + ':up');
        });
        up._key = k + ':up';
        line.appendChild(up);
        var dn = U.el('div', 'btn' + (idx === keys.length - 1 ? ' off' : ''));
        dn.appendChild(U.el('div', 'g', U.ICONS.down));
        dn.appendChild(U.el('div', 'ring'));
        addFx(dn, function () {
          if (idx === keys.length - 1) return;
          var nk = keys.slice(0);
          nk[idx] = nk[idx + 1]; nk[idx + 1] = k;
          save(nk);
          buildRailPage(k + ':down');
        });
        dn._key = k + ':down';
        line.appendChild(dn);
        sc.appendChild(line);
        if (focusKey && (focusKey === row._key || focusKey === up._key || focusKey === dn._key)) {
          target = focusKey === row._key ? row : (focusKey === up._key ? up : dn);
        }
      })(keys[r], r);
    }

    var rest = [];
    if (U.indexOf(keys, 'inputs') < 0) rest.push('inputs');
    var apps = Home.apps();
    for (var a = 0; a < apps.length; a++) if (U.indexOf(keys, apps[a].key) < 0) rest.push(apps[a].key);
    if (rest.length) sc.appendChild(U.el('div', 'sectionTitle', 'ADD TO THE BAR'));
    for (var j = 0; j < rest.length; j++) {
      (function (k) {
        var row = rowEl(label(k), null, U.el('div', 'chev', U.ICONS.plus));
        row.insertBefore(iconOf(k), row.firstChild);
        row.style.marginRight = '124px';
        addFx(row, function () {
          if (keys.length >= Home.MAX_RAIL) { Home.toast('The bar holds ' + Home.MAX_RAIL + '. Take one off first.'); return; }
          save(keys.concat([k]));
          buildRailPage(k + ':row');
        });
        row._key = k + ':row';
        if (focusKey === row._key) target = row;
        sc.appendChild(row);
      })(rest[j]);
    }

    var reset = addFx(rowEl('Back to the usual apps', null, null), function () {
      U.set('rail', null);
      Home.rebuild();
      buildRailPage('reset');
      Home.toast('The bar is back to the usual apps');
    });
    reset._key = 'reset';
    reset.style.marginTop = '18px';
    reset.style.marginRight = '124px';
    if (focusKey === 'reset') target = reset;
    sc.appendChild(reset);
    focus(target || fx[0]);
  }

  // ---------------------------------------------------------------- keys

  function key(a) {
    if (input && document.activeElement === input) {
      if (a === 'up' || a === 'down') { try { input.blur(); } catch (e) { /* ignore */ } move(a); return true; }
      if (a === 'back') { try { input.blur(); } catch (e2) { /* ignore */ } return true; }
      return false; // let the field have left, right and OK
    }
    if (a === 'back') {
      if (current === 'settings' && page !== 'main') { var was = page; page = 'main'; buildSettings(was); return true; }
      close();
      return true;
    }
    if (a === 'ok') { if (cur && cur._ok) cur._ok(cur); return true; }
    if (a === 'up' || a === 'down' || a === 'left' || a === 'right') { move(a); return true; }
    return false;
  }

  return {
    open: open,
    close: close,
    key: key,
    isOpen: function () { return !!current; },
    which: function () { return current; },
    typing: function () { return !!(input && document.activeElement === input); }
  };
})();
