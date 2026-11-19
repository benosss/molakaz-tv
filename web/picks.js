/*
 * Fresh picks, like the Android app: Netflix Top 10 in your country (Netflix Tudum), YouTube Charts trending and
 * Spotify's New Music Friday, refreshed twice a day, no keys and no server.
 *
 * A TV browser may refuse these requests (CORS, or a site that wants other headers). Every failure is quiet: the
 * last good list stays cached, and with nothing cached Home shows a row of your apps instead.
 */
var Picks = (function () {
  'use strict';

  var FORMAT = 1;
  var MAX_AGE = 12 * 3600000;
  var YT_KEY = 'AIzaSyCzEW7JUJdSql0-2V4tHUb6laYm4iAE_dM';

  function cached() {
    var c = U.get('picks', null);
    return c && c.v === FORMAT && c.items ? c : null;
  }

  function unescapeJson(s) {
    try { return JSON.parse('"' + s + '"'); } catch (e) { return s.replace(/\\'/g, "'").replace(/\\"/g, '"'); }
  }

  // ---- parsers (pure, so they can be tested on a computer against the real pages)

  function parseNetflix(html, country, kind) {
    var out = [];
    var re = /"storyArt":\{[\s\S]*?"url":"(https:\/\/[^"]+)"[\s\S]*?"top10Video":\{"__typename":"Top10PulseVideo","title":"((?:[^"\\]|\\.)*)","videoId":(\d+)/g;
    var m;
    while ((m = re.exec(html)) && out.length < 5) {
      out.push({ app: 'netflix', badge: 'NETFLIX · #' + (out.length + 1) + ' IN ' + country.toUpperCase(),
        title: unescapeJson(m[2]), sub: kind + ' · Top 10 this week', art: m[1],
        link: 'https://www.netflix.com/title/' + m[3] });
    }
    return out;
  }

  function parseYouTube(j, where) {
    var views;
    try {
      views = j.contents.sectionListRenderer.contents[0].musicAnalyticsSectionRenderer.content.videos[0].videoViews;
    } catch (e) { return []; }
    var out = [];
    for (var i = 0; i < views.length && out.length < 4; i++) {
      var v = views[i];
      if (!v || !v.id || v.id.length !== 11) continue;
      var artist = v.artists && v.artists[0] && v.artists[0].name ? v.artists[0].name : '';
      var art = 'https://i.ytimg.com/vi/' + v.id + '/mqdefault.jpg';
      var th = v.thumbnail && v.thumbnail.thumbnails;
      if (th) for (var k = 0; k < th.length; k++) if (th[k].url && th[k].url.indexOf('maxresdefault') >= 0) art = th[k].url;
      out.push({ app: 'youtube', badge: 'YOUTUBE · #' + (i + 1) + ' TRENDING' + where, title: v.title || '', sub: artist,
        art: art, link: 'https://www.youtube.com/watch?v=' + v.id });
    }
    return out;
  }

  function parseSpotify(html) {
    var m = /<script id="__NEXT_DATA__" type="application\/json">([\s\S]*?)<\/script>/.exec(html);
    if (!m) return [];
    var list;
    try { list = JSON.parse(m[1]).props.pageProps.state.data.entity.trackList; } catch (e) { return []; }
    var out = [];
    for (var i = 0; i < list.length && out.length < 4; i++) {
      var t = list[i];
      if (!t.uri || t.uri.indexOf('spotify:track:') !== 0) continue;
      out.push({ app: 'spotify', badge: 'SPOTIFY · NEW MUSIC FRIDAY', title: t.title || '', sub: t.subtitle || '', art: '', link: t.uri });
    }
    return out;
  }

  // ---- fetchers

  function netflix(country, cb) {
    var slug = country.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
    var films = [], shows = [], pending = 2;
    function one(path, kind, into) {
      U.getText('https://www.netflix.com/tudum/top10/' + slug + path, function (err, html) {
        if (!err) { var r = parseNetflix(html, country, kind); for (var i = 0; i < r.length; i++) into.push(r[i]); }
        if (--pending === 0) {
          var out = [];
          for (var k = 0; k < Math.max(films.length, shows.length); k++) {
            if (films[k]) out.push(films[k]);
            if (shows[k]) out.push(shows[k]);
          }
          cb(out);
        }
      }, 15000);
    }
    one('', 'Film', films);
    one('/tv', 'Series', shows);
  }

  function youtube(cc, cb) {
    var tries = [];
    if (cc && cc.length === 2) tries.push(cc.toLowerCase());
    if (U.indexOf(tries, 'us') < 0) tries.push('us');
    function attempt(i) {
      if (i >= tries.length) return cb([]);
      var c = tries[i];
      var body = {
        context: { client: { clientName: 'WEB_MUSIC_ANALYTICS', clientVersion: '2.0', hl: 'en', gl: 'US', theme: 'MUSIC' } },
        browseId: 'FEmusic_analytics_charts_home',
        query: 'perspective=CHART_DETAILS&chart_params_country_code=' + c + '&chart_params_chart_type=TRENDING_VIDEOS&chart_params_period_type=RIGHT_NOW'
      };
      U.postJSON('https://charts.youtube.com/youtubei/v1/browse?alt=json&key=' + YT_KEY, body, function (err, j) {
        var out = err ? [] : parseYouTube(j, c === (cc || '').toLowerCase() ? '' : ' (' + c.toUpperCase() + ')');
        if (out.length) cb(out); else attempt(i + 1);
      }, 15000);
    }
    attempt(0);
  }

  function spotify(cb) {
    U.getText('https://open.spotify.com/embed/playlist/37i9dQZF1DX4JAvHpjipBk', function (err, html) {
      var list = err ? [] : parseSpotify(html);
      var pending = list.length;
      if (!pending) return cb([]);
      // Cover art from Spotify's oEmbed, one small request per song.
      for (var i = 0; i < list.length; i++) (function (p) {
        U.getJSON('https://open.spotify.com/oembed?url=' + encodeURIComponent(p.link), function (e, j) {
          if (!e && j && j.thumbnail_url) p.art = j.thumbnail_url;
          if (--pending === 0) cb(list);
        }, 10000);
      })(list[i]);
    }, 15000);
  }

  /** Fetch all three, mix them like the Android app (Netflix, YouTube, Spotify, ...), cache, cb(items). */
  function refresh(place, cb) {
    var nf = [], yt = [], sp = [], pending = 3;
    function done() {
      if (--pending) return;
      var mixed = [];
      for (var i = 0; i < 6; i++) {
        if (nf[i]) mixed.push(nf[i]);
        if (i < 4) { if (yt[i]) mixed.push(yt[i]); if (sp[i]) mixed.push(sp[i]); }
      }
      if (mixed.length) U.set('picks', { v: FORMAT, at: Date.now(), country: place.country, items: mixed });
      else {
        // Nothing reachable: remember when we tried, so a TV that blocks these doesn't retry on every start.
        var c = cached();
        if (!c) U.set('picksTried', Date.now());
      }
      cb(mixed.length ? mixed : (cached() ? cached().items : []));
    }
    netflix(place.country || 'Mauritius', function (r) { nf = r; done(); });
    youtube(place.cc || '', function (r) { yt = r; done(); });
    spotify(function (r) { sp = r; done(); });
  }

  return {
    items: function () { var c = cached(); return c ? c.items : []; },
    stale: function (place) {
      var c = cached();
      if (c) return Date.now() - c.at > MAX_AGE || c.country !== place.country;
      return Date.now() - U.get('picksTried', 0) > MAX_AGE / 2;
    },
    refresh: refresh,
    parseNetflix: parseNetflix,
    parseYouTube: parseYouTube,
    parseSpotify: parseSpotify
  };
})();
