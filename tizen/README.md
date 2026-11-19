# MoLakaz TV for Samsung Tizen

The web version of MoLakaz TV (in [`../web`](../web)) packaged as a Tizen TV web app, `MoLakazTV1.Home`. It runs on
Tizen 3 (2017 TVs) and newer.

## Build

```bash
python3 tizen/build.py               # → tizen/out/MoLakazTV-tizen.wgt (unsigned) and tizen/out/build/
python3 tizen/build.py --list        # ...and show what's inside
python3 tizen/build.py --tizenbrew   # also tizen/out/tizenbrew/, an npm package for TizenBrew
```

The `.wgt` is a zip with `config.xml`, `icon.png` (512×423) and the web app. `config.xml` declares the privileges the
app uses: `internet`, `application.info` and `application.launch` (your apps), `tv.inputdevice` (colour, media and
Exit keys) and `tv.window` (the Inputs screen).

## Signing: why the .wgt alone won't install

Samsung TVs only install **signed** widgets: an *author* certificate and a *distributor* certificate, and for a
retail TV the distributor certificate must list that TV's DUID. There are two ways to get MoLakaz TV onto a TV.

**1. Sign it yourself with Tizen Studio (installs like any app)**

1. Install [Tizen Studio](https://developer.samsung.com/smarttv/develop/getting-started/setting-up-sdk/installing-tv-sdk.html)
   with the *TV Extensions* and *Samsung Certificate Extension* from its Package Manager.
2. On the TV: open *Apps*, type `1 2 3 4 5` on the remote, turn *Developer mode* on and enter your computer's IP,
   then restart the TV.
3. Connect: `sdb connect <TV IP>` (or Tizen Studio's Device Manager). Note the TV's name from `sdb devices`.
4. Certificate Manager → new **Samsung** certificate profile, type *TV*, with a Samsung account. It reads the
   connected TV's DUID into the distributor certificate.
5. Sign and install:

   ```bash
   python3 tizen/build.py --sign <profile name>      # runs: tizen package -t wgt -s <profile> -- tizen/out/build
   tizen install -n tizen/out/MoLakazTV-tizen-signed.wgt -t <TV name>
   ```

The certificate only works on the TVs whose DUIDs it lists. Add more TVs by making the certificate again.

**2. TizenBrew (no certificate of your own)**

[TizenBrew](https://github.com/reisxd/TizenBrew) is a signed launcher you install once; it then loads *modules*
published on npm. MoLakaz TV becomes an *app module*:

1. `python3 tizen/build.py --tizenbrew`, then `cd tizen/out/tizenbrew && npm publish` (once per version; the npm
   name in `package.json` is `molakaz-tv`, free as of November 2026).
2. On the TV, in TizenBrew's module manager, add the npm module `molakaz-tv` (TizenBrew's docs give the exact
   prefix it wants).

TizenBrew serves the files from a CDN rather than installing them, so the app runs from the web. Whether the Tizen
device APIs (your apps, launching them, inputs) are open to a module page depends on TizenBrew. If they aren't,
MoLakaz TV shows the known streaming apps instead.

## What to expect on a Samsung TV

- MoLakaz TV is an app you open from Samsung's Smart Hub; Tizen doesn't let an app replace Samsung's home screen.
- **Back** (10009) goes to the app bar; Back twice closes the app. **Exit** closes it. Colour keys: red Inputs, green
  All apps, yellow next film, blue Settings.
- Your apps come from `tizen.application.getAppsInfo()` and are matched to the known streaming apps by package id
  (Spotify `3201606009684`, Netflix `3201907018807`/`11101200001`, YouTube `111299001912`, Prime Video
  `3201910019365`/`3201512006785`, Disney+ `3201901017640`, Apple TV `3201807016597`, Plex `3201512006963`) or by name.
- **Inputs** sets the main TV window's source with `tizen.tvwindow.setSource()` and opens `org.tizen.tv-viewer`. If
  the TV refuses, Live TV still opens the TV viewer and HDMI asks you to press *SOURCE* on the remote.
- Everything above is untested on a real Samsung TV so far. The app is checked in desktop Chrome with a stand-in
  for the TV (see [`../web/README.md`](../web/README.md)).
