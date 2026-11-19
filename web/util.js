/*
 * MoLakaz TV for LG webOS and Samsung Tizen: small helpers shared by every other file.
 * ES5 only (webOS 3 runs Chromium 38, Tizen 3 runs Chromium 47): no arrow functions, let/const, classes,
 * template strings, promises or fetch.
 */
var U = (function () {
  'use strict';

  var PREFIX = 'mlk.';

  function $(id) { return document.getElementById(id); }

  /** Make an element: el('div', 'card focusable', '<b>hi</b>'). */
  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function get(key, def) {
    try {
      var v = window.localStorage.getItem(PREFIX + key);
      return v == null ? def : JSON.parse(v);
    } catch (e) { return def; }
  }

  function set(key, value) {
    try { window.localStorage.setItem(PREFIX + key, JSON.stringify(value)); } catch (e) { /* storage full or off */ }
  }

  /** Query-string flags for previewing on a computer: ?platform=browser&films=...&now=... */
  var params = (function () {
    var out = {};
    var q = (window.location.search || '').replace(/^\?/, '');
    if (!q) return out;
    var parts = q.split('&');
    for (var i = 0; i < parts.length; i++) {
      var kv = parts[i].split('=');
      if (kv[0]) out[decodeURIComponent(kv[0])] = kv.length > 1 ? decodeURIComponent(kv.slice(1).join('=').replace(/\+/g, ' ')) : '1';
    }
    return out;
  })();

  /**
   * XMLHttpRequest with a timeout. cb(err, text, xhr). Local files (scenes.json in the package) answer with
   * status 0 on file:// pages, so a 0 with a body counts as success.
   */
  function request(method, url, body, headers, cb, timeoutMs) {
    var x;
    var done = false;
    function finish(err, text) {
      if (done) return;
      done = true;
      clearTimeout(timer);
      cb(err, text, x);
    }
    var timer = setTimeout(function () {
      try { x.abort(); } catch (e) { /* ignore */ }
      finish(new Error('timeout'));
    }, timeoutMs || 15000);
    try {
      x = new XMLHttpRequest();
      x.open(method, url, true);
      if (headers) {
        for (var k in headers) if (headers.hasOwnProperty(k)) x.setRequestHeader(k, headers[k]);
      }
      x.onreadystatechange = function () {
        if (x.readyState !== 4) return;
        var ok = (x.status >= 200 && x.status < 300) || (x.status === 0 && x.responseText);
        if (ok) finish(null, x.responseText);
        else finish(new Error('HTTP ' + x.status));
      };
      x.onerror = function () { finish(new Error('network')); };
      x.send(body == null ? null : body);
    } catch (e) {
      finish(e);
    }
  }

  function getText(url, cb, timeoutMs) { request('GET', url, null, null, cb, timeoutMs); }

  function getJSON(url, cb, timeoutMs) {
    getText(url, function (err, text) {
      if (err) return cb(err);
      var j;
      try { j = JSON.parse(text); } catch (e) { return cb(e); }
      cb(null, j);
    }, timeoutMs);
  }

  function postJSON(url, obj, cb, timeoutMs) {
    request('POST', url, JSON.stringify(obj), { 'Content-Type': 'application/json' }, function (err, text) {
      if (err) return cb(err);
      var j;
      try { j = JSON.parse(text); } catch (e) { return cb(e); }
      cb(null, j);
    }, timeoutMs);
  }

  /** Does this browser do frosted glass? Old TV browsers (webOS 3/4, Tizen 3/4) don't. */
  function hasBackdrop() {
    try {
      if (window.CSS && CSS.supports) {
        return CSS.supports('backdrop-filter', 'blur(2px)') || CSS.supports('-webkit-backdrop-filter', 'blur(2px)');
      }
    } catch (e) { /* ignore */ }
    var s = document.createElement('div').style;
    return 'backdropFilter' in s || 'webkitBackdropFilter' in s;
  }

  function setTransform(e, t) {
    e.style.webkitTransform = t;
    e.style.transform = t;
  }

  function now() {
    // ?now=2026-11-18T21:30 freezes the clock for screenshots (local time).
    if (params.now) {
      var m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(params.now);
      if (m) return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], 0).getTime();
    }
    return Date.now();
  }

  function clamp(v, a, b) { return v < a ? a : (v > b ? b : v); }

  function indexOf(arr, v) {
    for (var i = 0; i < arr.length; i++) if (arr[i] === v) return i;
    return -1;
  }

  function startsWith(s, p) { return String(s).indexOf(p) === 0; }

  /** Small inline icons (no icon font: TVs ship few fonts). White strokes on a 24 grid. */
  var ICONS = {
    gear: '<svg viewBox="0 0 24 24" width="100%" height="100%"><path fill="#fff" fill-rule="evenodd" d="M10.3 2h3.4l.5 2.6c.6.2 1.2.5 1.7.9l2.5-.9 1.7 2.9-2 1.7c.1.6.1 1.2 0 1.8l2 1.7-1.7 2.9-2.5-.9c-.5.4-1.1.7-1.7.9l-.5 2.6h-3.4l-.5-2.6c-.6-.2-1.2-.5-1.7-.9l-2.5.9-1.7-2.9 2-1.7c-.1-.6-.1-1.2 0-1.8l-2-1.7 1.7-2.9 2.5.9c.5-.4 1.1-.7 1.7-.9zM12 8.6a3.4 3.4 0 1 0 0 6.8 3.4 3.4 0 0 0 0-6.8z" transform="translate(0 1.5)"/></svg>',
    grid: '<svg viewBox="0 0 24 24" width="100%" height="100%"><g fill="#fff"><circle cx="5" cy="5" r="2.1"/><circle cx="12" cy="5" r="2.1"/><circle cx="19" cy="5" r="2.1"/><circle cx="5" cy="12" r="2.1"/><circle cx="12" cy="12" r="2.1"/><circle cx="19" cy="12" r="2.1"/><circle cx="5" cy="19" r="2.1"/><circle cx="12" cy="19" r="2.1"/><circle cx="19" cy="19" r="2.1"/></g></svg>',
    inputs: '<svg viewBox="0 0 24 24" width="100%" height="100%"><g fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="4" width="19" height="13" rx="2.5"/><path d="M8 21h8"/><path d="M9 10.5h6M12.5 8l2.5 2.5-2.5 2.5"/></g></svg>',
    hdmi: '<svg viewBox="0 0 24 24" width="100%" height="100%"><g fill="none" stroke="#fff" stroke-width="1.8" stroke-linejoin="round"><path d="M3 8.5h18v4.2l-2.6 2.8H5.6L3 12.7z"/><path d="M7 11.5h10" stroke-linecap="round"/></g></svg>',
    tv: '<svg viewBox="0 0 24 24" width="100%" height="100%"><g fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="6.5" width="19" height="13" rx="2.5"/><path d="M8.5 2.8L12 6.3l3.5-3.5"/></g></svg>',
    pin: '<svg viewBox="0 0 24 24" width="100%" height="100%"><path fill="#fff" d="M12 2.2c-4 0-7.2 3.1-7.2 7.1 0 5.1 6.1 11.6 6.4 11.9.4.4 1.1.4 1.5 0 .3-.3 6.4-6.8 6.4-11.9 0-4-3.2-7.1-7.1-7.1zm0 9.9a2.8 2.8 0 1 1 0-5.6 2.8 2.8 0 0 1 0 5.6z"/></svg>',
    chevron: '<svg viewBox="0 0 24 24" width="100%" height="100%"><path d="M9 5l7 7-7 7" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    up: '<svg viewBox="0 0 24 24" width="100%" height="100%"><path d="M6 15l6-6 6 6" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    down: '<svg viewBox="0 0 24 24" width="100%" height="100%"><path d="M6 9l6 6 6-6" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    search: '<svg viewBox="0 0 24 24" width="100%" height="100%"><g fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round"><circle cx="10.5" cy="10.5" r="6.5"/><path d="M15.5 15.5l5 5"/></g></svg>',
    check: '<svg viewBox="0 0 24 24" width="100%" height="100%"><path d="M5 12.5l4.5 4.5L19 7.5" fill="none" stroke="#fff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    plus: '<svg viewBox="0 0 24 24" width="100%" height="100%"><path d="M12 5v14M5 12h14" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round"/></svg>'
  };

  return {
    $: $, el: el, esc: esc, get: get, set: set, params: params,
    getText: getText, getJSON: getJSON, postJSON: postJSON, request: request,
    hasBackdrop: hasBackdrop, setTransform: setTransform, now: now, clamp: clamp,
    indexOf: indexOf, startsWith: startsWith, ICONS: ICONS
  };
})();
