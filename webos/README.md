# MoLakaz TV for LG webOS

The web version of MoLakaz TV (in [`../web`](../web)) packaged as a webOS app, `tv.molakaz.home`. It runs on webOS 3
(2016 TVs) and newer.

## Build

```bash
python3 webos/build.py          # → webos/out/MoLakazTV-webos.ipk
python3 webos/build.py --list   # ...and show what's inside
```

Only Python 3 is needed: the script writes the `.ipk` itself (an `ar` archive of `debian-binary`, `control.tar.gz`
and `data.tar.gz` with `usr/palm/applications/tv.molakaz.home/`), the same layout LG's `ares-package` makes.

## Install

LG TVs install apps from outside the LG Content Store in **Developer Mode**. Pick one way:

**1. Developer Mode + LG's command-line tools**

1. On the TV: install **Developer Mode** from the LG Content Store, sign in with a free LG developer account
   ([webostv.developer.lge.com](https://webostv.developer.lge.com)), turn on *Dev Mode Status* and *Key Server*.
2. On the computer: install Node.js, then `npm install -g @webos-tools/cli`.
3. `python3 webos/install.py <TV IP>`. It adds the TV to `ares`, asks for the passphrase the Developer Mode app
   shows, then runs `ares-install` and `ares-launch`.

By hand, that is:

```bash
ares-setup-device -a molakaz-tv -i "host=<TV IP>" -i "port=9922" -i "username=prisoner"
ares-novacom --device molakaz-tv --getkey          # type the passphrase from the Developer Mode app
ares-install --device molakaz-tv webos/out/MoLakazTV-webos.ipk
ares-launch  --device molakaz-tv tv.molakaz.home
```

A Developer Mode session lasts about 50 hours. Open the Developer Mode app and press *Extend* now and then, or the
TV hides the apps you installed this way.

**2. webOS Dev Manager (no command line)**

[webOS Dev Manager](https://github.com/webosbrew/dev-manager-desktop) from the webOS Homebrew project is a desktop
app that pairs with a TV in Developer Mode and installs an `.ipk` you pick. Add the TV, then *Apps › Install* and
choose `MoLakazTV-webos.ipk`.

**3. webOS Homebrew Channel**

On a TV that already has the [Homebrew Channel](https://github.com/webosbrew/webos-homebrew-channel), install the
`.ipk` with Dev Manager (above). Whether apps keep working after a Developer Mode session runs out depends on how the
Homebrew Channel was set up on that TV; its README explains.

## What to expect on an LG TV

- MoLakaz TV is an app you open from the LG app bar. webOS doesn't let an app replace LG's own home screen.
- **Back** goes to the app bar on the left; Back again twice closes MoLakaz TV. Colour keys: red Inputs, green All
  apps, yellow next film, blue Settings. Play/pause pauses the film. The Magic Remote pointer works too.
- Your apps come from `luna://com.webos.applicationManager/listApps`. Some webOS versions refuse that call to
  Developer Mode apps: then the bar offers the known streaming apps (Spotify, Netflix, YouTube, Prime Video, Disney+,
  Apple TV, Plex) and says so if one isn't installed when you open it.
- **Inputs** opens `com.webos.app.hdmi1`–`hdmi4` and `com.webos.app.livetv`.
- Everything above is untested on a real LG TV so far. The app is checked in desktop Chrome with a stand-in for the
  TV (see [`../web/README.md`](../web/README.md)).
