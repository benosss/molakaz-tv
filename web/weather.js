/*
 * Weather for the chosen town from Open-Meteo (no key), plus today's sunrise and sunset for the day/night glass.
 * The town comes from Settings; on first run it is guessed once from the IP address (ipwho.is), else Port Louis.
 */
var Weather = (function () {
  'use strict';

  var DEFAULT_PLACE = { name: 'Port Louis', country: 'Mauritius', cc: 'MU', lat: -20.1619, lon: 57.4989 };
  var w = U.get('weather', null);

  function place() { return U.get('place', null) || DEFAULT_PLACE; }

  /** First run only: a town from the IP address, so the weather is local before anyone opens Settings. */
  function guessPlace(cb) {
    if (U.get('place', null) || U.get('placeGuessed', false) || U.params.noguess) return cb(place());
    U.getJSON('https://ipwho.is/?fields=success,city,country,country_code,latitude,longitude', function (err, j) {
      U.set('placeGuessed', true);
      if (!err && j && j.success !== false && j.city && j.latitude != null) {
        U.set('place', { name: j.city, country: j.country || '', cc: j.country_code || '', lat: j.latitude, lon: j.longitude });
      }
      cb(place());
    }, 8000);
  }

  /** "2026-11-19T05:32" at a location's UTC offset, as epoch ms. */
  function localToEpoch(s, offsetSec) {
    var m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(s || '');
    if (!m) return 0;
    return Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]) - (offsetSec || 0) * 1000;
  }

  function refresh(cb) {
    var p = place();
    var url = 'https://api.open-meteo.com/v1/forecast?latitude=' + p.lat + '&longitude=' + p.lon +
      '&current=temperature_2m,weather_code,is_day&daily=sunrise,sunset&timezone=auto&forecast_days=2';
    U.getJSON(url, function (err, j) {
      if (err || !j || !j.current) { if (cb) cb(w); return; }
      var off = j.utc_offset_seconds || 0;
      var rises = [], sets = [];
      if (j.daily && j.daily.sunrise) {
        for (var i = 0; i < j.daily.sunrise.length; i++) {
          rises.push(localToEpoch(j.daily.sunrise[i], off));
          sets.push(localToEpoch(j.daily.sunset[i], off));
        }
      }
      w = {
        at: Date.now(), place: p.name, lat: p.lat, lon: p.lon,
        temp: Math.round(j.current.temperature_2m), code: j.current.weather_code, isDay: j.current.is_day === 1,
        rises: rises, sets: sets
      };
      U.set('weather', w);
      if (cb) cb(w);
    }, 12000);
  }

  /** Night from sunset to sunrise (the town's real times), else 18:00 to 06:00 TV time. */
  function isNight(t) {
    if (U.params.theme === 'night') return true;
    if (U.params.theme === 'day') return false;
    var p = place();
    if (w && w.rises && w.rises.length && w.lat === p.lat && w.lon === p.lon) {
      // The last sunrise or sunset before now decides; before the first sunrise it is still night.
      var last = null, lastAt = -1, i;
      for (i = 0; i < w.rises.length; i++) {
        if (w.rises[i] <= t && w.rises[i] > lastAt) { lastAt = w.rises[i]; last = 'rise'; }
        if (w.sets[i] <= t && w.sets[i] > lastAt) { lastAt = w.sets[i]; last = 'set'; }
      }
      if (last && t - lastAt < 24 * 3600000) return last === 'set';
      if (!last && t < w.rises[0] && w.rises[0] - t < 24 * 3600000) return true;
    }
    var h = new Date(t).getHours();
    return h < 6 || h >= 18;
  }

  /** WMO weather code to an icon. */
  function icon(code, night) {
    var sun = '<circle cx="12" cy="12" r="4.6" fill="#fff"/><g stroke="#fff" stroke-width="1.9" stroke-linecap="round"><path d="M12 2.2v2.4M12 19.4v2.4M2.2 12h2.4M19.4 12h2.4M5.1 5.1l1.7 1.7M17.2 17.2l1.7 1.7M5.1 18.9l1.7-1.7M17.2 6.8l1.7-1.7"/></g>';
    var moon = '<path fill="#fff" d="M15.6 3.2a8.9 8.9 0 1 0 5.2 12.9A7.4 7.4 0 0 1 15.6 3.2z"/>';
    var cloud = '<path fill="#fff" d="M7.2 19.2h10.4a4.4 4.4 0 0 0 .5-8.8 6 6 0 0 0-11.5 1.7 3.6 3.6 0 0 0 .6 7.1z"/>';
    var small = '<path fill="#fff" d="M9.6 17.6h8.6a3.6 3.6 0 0 0 .4-7.2 5 5 0 0 0-9.5 1.4 2.9 2.9 0 0 0 .5 5.8z"/>';
    var rain = '<path fill="#fff" d="M7.2 14.6h10.4a4.1 4.1 0 0 0 .5-8.2 5.6 5.6 0 0 0-10.7 1.6 3.3 3.3 0 0 0-.2 6.6z"/><g stroke="#fff" stroke-width="1.8" stroke-linecap="round"><path d="M8.5 17.2l-1 2.6M12.5 17.2l-1 2.6M16.5 17.2l-1 2.6"/></g>';
    var snow = '<path fill="#fff" d="M7.2 14.6h10.4a4.1 4.1 0 0 0 .5-8.2 5.6 5.6 0 0 0-10.7 1.6 3.3 3.3 0 0 0-.2 6.6z"/><g fill="#fff"><circle cx="8.5" cy="18.6" r="1.3"/><circle cx="12.5" cy="20" r="1.3"/><circle cx="16.5" cy="18.6" r="1.3"/></g>';
    var fog = '<g stroke="#fff" stroke-width="2" stroke-linecap="round"><path d="M4 9h16M6 13h12M4 17h16"/></g>';
    var bolt = '<path fill="#fff" d="M7.2 13.6h10.4a4.1 4.1 0 0 0 .5-8.2 5.6 5.6 0 0 0-10.7 1.6 3.3 3.3 0 0 0-.2 6.6z"/><path fill="#fff" d="M12.6 14.6l-3 5h2.6l-1 3.6 4-5.6h-2.6l1.4-3z"/>';
    var body;
    if (code == null) body = night ? moon : sun;
    else if (code === 0) body = night ? moon : sun;
    else if (code <= 2) body = '<g opacity=".95" transform="translate(-3 -3) scale(.8)">' + (night ? moon : sun) + '</g>' + small;
    else if (code === 3) body = cloud;
    else if (code === 45 || code === 48) body = fog;
    else if ((code >= 71 && code <= 77) || code === 85 || code === 86) body = snow;
    else if (code >= 95) body = bolt;
    else body = rain;
    return '<svg viewBox="0 0 24 24" width="100%" height="100%">' + body + '</svg>';
  }

  /** Town search for Settings. cb(list of {name, country, cc, admin, lat, lon}). */
  function search(q, cb) {
    var url = 'https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name=' + encodeURIComponent(q);
    U.getJSON(url, function (err, j) {
      if (err || !j) return cb(null);
      var out = [];
      var r = j.results || [];
      for (var i = 0; i < r.length; i++) {
        out.push({ name: r[i].name, country: r[i].country || '', cc: r[i].country_code || '', admin: r[i].admin1 || '',
          lat: r[i].latitude, lon: r[i].longitude });
      }
      cb(out);
    }, 10000);
  }

  return {
    place: place,
    setPlace: function (p) { U.set('place', p); w = null; U.set('weather', null); },
    guessPlace: guessPlace,
    refresh: refresh,
    current: function () { return w; },
    isNight: isNight,
    icon: icon,
    search: search,
    stale: function () { return !w || Date.now() - w.at > 30 * 60000 || w.place !== place().name; }
  };
})();
