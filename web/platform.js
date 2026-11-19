/*
 * The TV layer: which apps are installed, opening them, switching inputs, remote keys and closing.
 * One of three implementations is picked at start:
 *   webos   - LG webOS, through PalmServiceBridge (luna://com.webos.applicationManager)
 *   tizen   - Samsung Tizen, through tizen.application / tizen.tvinputdevice / tizen.tvwindow
 *   browser - a stand-in with sample apps, for previewing on a computer
 * Force one with ?platform=browser (or webos / tizen) in the URL.
 *
 * App ids of the streaming apps put on the bar by default (Spotify first). Each id was checked against at least one
 * public list; every TV model and region can differ, so installed apps are also matched by name.
 *   webOS: netflix, youtube.leanback.v4, amazon - Home Assistant community "App ID's for LG smart TV's" and the
 *          Ircama "Replacing Netflix and Amazon keys" gist; spotify-beehive, cdp-30 (Plex), com.apple.appletv,
 *          com.disney.disneyplus-prod - same gist's comments, SimpleControl "LG WebOS 3.0 Application Launch
 *          Parameters", RTI "LG Direct App Integration" (2020).
 *   Tizen: Samsung store package ids from rs.ltd "Samsung Custom Apps" and Home Assistant SamsungTV configs:
 *          Spotify 3201606009684, Netflix 3201907018807 / 11101200001, YouTube 111299001912 (9Ur5IzDKqV.TizenYouTube),
 *          Prime Video 3201910019365 / 3201512006785, Disney+ 3201901017640 / 3202009021709 / 3202204027038,
 *          Apple TV 3201807016597, Plex 3201512006963.
 */
var Platform = (function () {
  'use strict';

  var SELF_WEBOS = 'tv.molakaz.home';
  var SELF_TIZEN_PKG = 'MoLakazTV1';

  var KNOWN = [
    { key: 'spotify', name: 'Spotify', webos: ['spotify-beehive', 'com.spotify.app'], tizen: ['3201606009684'],
      match: /^spotify/i, colors: ['#14532D', '#05140B'] },
    { key: 'netflix', name: 'Netflix', webos: ['netflix'], tizen: ['3201907018807', '11101200001'],
      match: /^netflix$/i, colors: ['#3F0A0D', '#0B0B0F'] },
    { key: 'youtube', name: 'YouTube', webos: ['youtube.leanback.v4'], tizen: ['111299001912', '9Ur5IzDKqV'],
      match: /^youtube$/i, colors: ['#3B0A10', '#0F0F12'] },
    { key: 'prime', name: 'Prime Video', webos: ['amazon', 'lovefilm.de'], tizen: ['3201910019365', '3201512006785'],
      match: /prime video|amazon prime|^amazon$/i, colors: ['#0A2A4D', '#050E1A'] },
    { key: 'disney', name: 'Disney+', webos: ['com.disney.disneyplus-prod'], tizen: ['3201901017640', '3202009021709', '3202204027038'],
      match: /^disney\s*\+|^disney plus/i, colors: ['#0B1A4A', '#050A1C'] },
    { key: 'appletv', name: 'Apple TV', webos: ['com.apple.appletv'], tizen: ['3201807016597'],
      match: /^apple tv$/i, colors: ['#1C1C1E', '#000000'] },
    { key: 'plex', name: 'Plex', webos: ['cdp-30'], tizen: ['3201512006963'],
      match: /^plex$/i, colors: ['#2A2210', '#0F0C05'] }
  ];
  var DEFAULT_COLORS = ['#0E7490', '#062A3A'];

  var INPUTS = [
    { key: 'hdmi1', name: 'HDMI 1', icon: 'hdmi', n: 1 },
    { key: 'hdmi2', name: 'HDMI 2', icon: 'hdmi', n: 2 },
    { key: 'hdmi3', name: 'HDMI 3', icon: 'hdmi', n: 3 },
    { key: 'hdmi4', name: 'HDMI 4', icon: 'hdmi', n: 4 },
    { key: 'tv', name: 'Live TV', icon: 'tv', n: 0 }
  ];

  function knownByKey(k) {
    for (var i = 0; i < KNOWN.length; i++) if (KNOWN[i].key === k) return KNOWN[i];
    return null;
  }

  function detect() {
    var forced = U.params.platform;
    if (forced === 'webos' || forced === 'tizen' || forced === 'browser') return forced;
    if (window.tizen && window.tizen.application) return 'tizen';
    if (typeof window.PalmServiceBridge !== 'undefined' || /Web0S|webOS/i.test(navigator.userAgent)) return 'webos';
    return 'browser';
  }

  function fileUrl(p) {
    if (!p) return '';
    if (/^(https?|file|data):/i.test(p)) return p;
    if (p.charAt(0) === '/') return 'file://' + p;
    return p;
  }

  /**
   * An app as Home uses it: key (stable, saved in settings), name, the platform id to open, an icon URL from the TV
   * (may fail to load), a bundled fallback icon for known apps, and card colours.
   */
  function entry(known, id, name, icon) {
    return {
      key: known ? known.key : 'app:' + id,
      name: known ? known.name : name,
      id: id,
      icon: icon || '',
      fallback: known ? 'img/apps/' + known.key + '.svg' : '',
      colors: known ? known.colors : DEFAULT_COLORS,
      known: !!known
    };
  }

  /** Match the TV's app list against the known apps; known ones first (in KNOWN order), the rest by name. */
  function classify(list, idsOf, selfTest) {
    var used = {};
    var known = [];
    var others = [];
    var i, j, k, a;
    for (k = 0; k < KNOWN.length; k++) {
      var kn = KNOWN[k];
      var ids = kn[idsOf];
      var hit = null;
      for (i = 0; i < list.length && !hit; i++) {
        a = list[i];
        for (j = 0; j < ids.length; j++) {
          if (a.id === ids[j] || a.pkg === ids[j] || U.startsWith(a.id, ids[j] + '.')) { hit = a; break; }
        }
      }
      for (i = 0; i < list.length && !hit; i++) {
        if (!used[list[i].id] && kn.match.test(list[i].name || '')) hit = list[i];
      }
      if (hit) { used[hit.id] = true; known.push(entry(kn, hit.id, hit.name, hit.icon)); }
    }
    for (i = 0; i < list.length; i++) {
      a = list[i];
      if (used[a.id] || selfTest(a)) continue;
      others.push(entry(null, a.id, a.name || a.id, a.icon));
    }
    others.sort(function (x, y) { return x.name.toLowerCase() < y.name.toLowerCase() ? -1 : 1; });
    return known.concat(others);
  }

  /** When the TV won't list its apps, offer the known ones and find out on launch. */
  function guessed(idsOf) {
    var out = [];
    for (var k = 0; k < KNOWN.length; k++) out.push(entry(KNOWN[k], KNOWN[k][idsOf][0], KNOWN[k].name, ''));
    return out;
  }

  // ---------------------------------------------------------------- webOS

  var bridges = [];

  function luna(uri, params, cb) {
    var bridge;
    var done = false;
    try { bridge = new window.PalmServiceBridge(); } catch (e) { if (cb) cb(e); return; }
    bridges.push(bridge); // keep a reference until it answers, or it can be collected mid-call
    var timer = setTimeout(function () { reply(JSON.stringify({ returnValue: false, errorText: 'timeout' })); }, 8000);
    function reply(msg) {
      if (done) return;
      done = true;
      clearTimeout(timer);
      var i = U.indexOf(bridges, bridge);
      if (i >= 0) bridges.splice(i, 1);
      var r;
      try { r = JSON.parse(msg); } catch (e) { r = { returnValue: false, errorText: 'bad reply' }; }
      if (!cb) return;
      if (r.returnValue === false) cb(new Error(r.errorText || String(r.errorCode || 'failed')), r);
      else cb(null, r);
    }
    bridge.onservicecallback = reply;
    try { bridge.call(uri, JSON.stringify(params || {})); } catch (e2) { reply(JSON.stringify({ returnValue: false, errorText: String(e2) })); }
  }

  var webos = {
    name: 'webos',
    label: 'LG webOS',
    keys: { back: [461, 8], exit: [], ok: [13], playPause: [10252, 415, 19], next: [417], red: [403], green: [404], yellow: [405], blue: [406] },
    init: function () { /* back key is ours thanks to disableBackHistoryAPI in appinfo.json */ },
    apps: function (cb) {
      luna('luna://com.webos.applicationManager/listApps', {}, function (err, r) {
        if (err || !r || !r.apps) return cb(guessed('webos'), false);
        var list = [];
        for (var i = 0; i < r.apps.length; i++) {
          var a = r.apps[i];
          if (a.visible === false || !a.id) continue;
          if (/^com\.webos\.app\.(hdmi|livetv|externalinput|tvhotkey|inputcommon)/.test(a.id)) continue;
          var icon = a.largeIcon || a.icon || '';
          if (icon && icon.charAt(0) !== '/' && !/^[a-z]+:/i.test(icon) && a.folderPath) icon = a.folderPath + '/' + icon;
          list.push({ id: a.id, name: a.title || a.id, icon: fileUrl(icon) });
        }
        cb(classify(list, 'webos', function (x) { return x.id === SELF_WEBOS; }), true);
      });
    },
    launch: function (app, params, cb) {
      var p = { id: app.id };
      if (params) p.params = params;
      luna('luna://com.webos.applicationManager/launch', p, function (err) { if (cb) cb(err); });
    },
    openInput: function (input, cb) {
      var id = input.key === 'tv' ? 'com.webos.app.livetv' : 'com.webos.app.hdmi' + input.n;
      luna('luna://com.webos.applicationManager/launch', { id: id }, function (err) { if (cb) cb(err); });
    },
    /** Opens a Fresh pick in its app (deep links are best effort; the app opens either way). */
    openLink: function (app, link, cb) {
      var target = link;
      var m = /[?&]v=([\w-]{11})/.exec(link);
      if (app.key === 'youtube' && m) target = 'https://www.youtube.com/tv?v=' + m[1];
      var self = this;
      this.launch(app, { contentTarget: target }, function (err) {
        if (err) self.launch(app, null, cb); else if (cb) cb(null);
      });
    },
    exit: function () { try { window.close(); } catch (e) { /* ignore */ } }
  };

  // ---------------------------------------------------------------- Tizen

  var TIZEN_KEYS = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaFastForward', 'MediaRewind', 'MediaStop',
    'ColorF0Red', 'ColorF1Green', 'ColorF2Yellow', 'ColorF3Blue', 'Exit', 'Info'];

  var tizenApi = {
    name: 'tizen',
    label: 'Samsung Tizen',
    keys: { back: [10009, 8], exit: [10182], ok: [13], playPause: [10252, 415, 19], next: [417], red: [403], green: [404], yellow: [405], blue: [406] },
    init: function () {
      var tv = window.tizen && window.tizen.tvinputdevice;
      if (!tv) return;
      var supported = {};
      try {
        var all = tv.getSupportedKeys();
        for (var i = 0; i < all.length; i++) supported[all[i].name] = all[i].code;
      } catch (e) { /* older firmware: register blindly below */ }
      for (var k = 0; k < TIZEN_KEYS.length; k++) {
        var n = TIZEN_KEYS[k];
        if (supported.hasOwnProperty(n) || !Object.keys(supported).length) {
          try { tv.registerKey(n); } catch (e2) { /* not on this model */ }
        }
      }
      if (supported.Exit) this.keys.exit = [supported.Exit];
      if (supported.MediaPlayPause) this.keys.playPause = [supported.MediaPlayPause, 415, 19];
    },
    apps: function (cb) {
      var app = window.tizen && window.tizen.application;
      if (!app) return cb(guessed('tizen'), false);
      try {
        app.getAppsInfo(function (infos) {
          var list = [];
          for (var i = 0; i < infos.length; i++) {
            var a = infos[i];
            if (a.show === false) continue;
            list.push({ id: a.id, pkg: a.packageId, name: a.name || a.id, icon: fileUrl(a.iconPath) });
          }
          cb(classify(list, 'tizen', function (x) { return x.pkg === SELF_TIZEN_PKG || /^org\.tizen\.(tv-viewer|homescreen)/.test(x.id); }), true);
        }, function () { cb(guessed('tizen'), false); });
      } catch (e) { cb(guessed('tizen'), false); }
    },
    launch: function (a, params, cb) {
      var api = window.tizen && window.tizen.application;
      if (!api) { if (cb) cb(new Error('no tizen')); return; }
      try {
        api.launch(a.id, function () { if (cb) cb(null); }, function (e) { if (cb) cb(e || new Error('launch failed')); });
      } catch (e) { if (cb) cb(e); }
    },
    openInput: function (input, cb) {
      var t = window.tizen;
      function showViewer() {
        try {
          t.application.launch('org.tizen.tv-viewer', function () { if (cb) cb(null); }, function () {
            try { t.application.getCurrentApplication().hide(); if (cb) cb(null); } catch (e) { if (cb) cb(e); }
          });
        } catch (e) { if (cb) cb(e); }
      }
      // Pick the source on the main TV window (privilege tv.window), then show the TV viewer.
      try {
        t.systeminfo.getPropertyValue('VIDEOSOURCE', function (vs) {
          var list = (vs && vs.connected ? vs.connected : []).concat(vs && vs.disconnected ? vs.disconnected : []);
          var want = null;
          for (var i = 0; i < list.length; i++) {
            var s = list[i];
            if (input.key === 'tv' && s.type === 'TV') { want = s; break; }
            if (input.key !== 'tv' && s.type === 'HDMI' && s.number === input.n) { want = s; break; }
          }
          if (!want) {
            if (input.key === 'tv') return showViewer();
            if (cb) cb(new Error('no such input'));
            return;
          }
          t.tvwindow.setSource(want, function () { showViewer(); }, function (e) { if (input.key === 'tv') showViewer(); else if (cb) cb(e); });
        }, function (e) { if (input.key === 'tv') showViewer(); else if (cb) cb(e); });
      } catch (e) {
        if (input.key === 'tv') showViewer(); else if (cb) cb(e);
      }
    },
    openLink: function (a, link, cb) {
      var t = window.tizen;
      var self = this;
      try {
        var ctl = new t.ApplicationControl('http://tizen.org/appcontrol/operation/view', link, null, null,
          [new t.ApplicationControlData('PAYLOAD', [JSON.stringify({ values: link })])]);
        t.application.launchAppControl(ctl, a.id, function () { if (cb) cb(null); }, function () { self.launch(a, null, cb); });
      } catch (e) { self.launch(a, null, cb); }
    },
    exit: function () {
      try { window.tizen.application.getCurrentApplication().exit(); } catch (e) { /* ignore */ }
    }
  };

  // ---------------------------------------------------------------- browser stand-in

  var SAMPLE_OTHERS = [
    { id: 'sample.photos', name: 'Photos' }, { id: 'sample.browser', name: 'Web Browser' },
    { id: 'sample.music', name: 'Music Player' }, { id: 'sample.guide', name: 'TV Guide' },
    { id: 'sample.weather', name: 'Weather' }, { id: 'sample.radio', name: 'Radio' }
  ];

  var browser = {
    name: 'browser',
    label: 'Browser preview',
    keys: { back: [27, 8, 461, 10009], exit: [], ok: [13], playPause: [32, 10252], next: [78], red: [403, 49], green: [404, 50], yellow: [405, 51], blue: [406, 52] },
    init: function () {},
    apps: function (cb) {
      var list = [];
      for (var k = 0; k < KNOWN.length; k++) list.push({ id: KNOWN[k].webos[0], name: KNOWN[k].name, icon: '' });
      for (var i = 0; i < SAMPLE_OTHERS.length; i++) list.push({ id: SAMPLE_OTHERS[i].id, name: SAMPLE_OTHERS[i].name, icon: '' });
      setTimeout(function () { cb(classify(list, 'webos', function () { return false; }), true); }, 0);
    },
    launch: function (a, params, cb) { if (cb) cb(null, 'preview'); },
    openInput: function (input, cb) { if (cb) cb(null, 'preview'); },
    openLink: function (a, link, cb) { if (cb) cb(null, 'preview'); },
    exit: function () {}
  };

  var impl = { webos: webos, tizen: tizenApi, browser: browser }[detect()];

  return {
    impl: impl,
    name: impl.name,
    label: impl.label,
    keys: impl.keys,
    KNOWN: KNOWN,
    INPUTS: INPUTS,
    DEFAULT_COLORS: DEFAULT_COLORS,
    knownByKey: knownByKey,
    init: function () { impl.init(); },
    /** cb(apps, listed): listed is false when the TV wouldn't say what's installed (the known apps are offered). */
    apps: function (cb) { impl.apps(cb); },
    launch: function (app, cb) { impl.launch(app, null, cb); },
    openInput: function (input, cb) { impl.openInput(input, cb); },
    openLink: function (app, link, cb) { impl.openLink(app, link, cb); },
    exit: function () { impl.exit(); }
  };
})();
