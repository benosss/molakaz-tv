#!/usr/bin/env python3
"""Install MoLakaz TV on an LG TV in Developer Mode, using LG's `ares` command-line tools.

    python3 webos/install.py 192.168.1.30          # the TV's IP (Settings > Network > Wi-Fi or Ethernet)

Before the first run:
  1. On the TV, install "Developer Mode" from the LG Content Store, sign in with an LG developer account
     (free, https://webostv.developer.lge.com), turn on Dev Mode Status and Key Server, and let the TV restart.
  2. On the computer: Node.js, then `npm install -g @webos-tools/cli` (the ares tools; no full SDK needed).

What this script does (nothing else):
  - builds webos/out/MoLakazTV-webos.ipk if it isn't there,
  - adds the TV as device "molakaz-tv" to ares (port 9922, user prisoner),
  - fetches the TV's key (`ares-novacom --getkey`, it asks for the passphrase shown in the Developer Mode app),
  - `ares-install` the package and `ares-launch` it.

No ares tools? Use webOS Dev Manager (a desktop app from webosbrew.org) or the Homebrew Channel instead; see
webos/README.md.
"""
import pathlib
import shutil
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
IPK = HERE / "out" / "MoLakazTV-webos.ipk"
DEVICE = "molakaz-tv"
APP_ID = "tv.molakaz.home"


def run(*cmd, check=True):
    print("$ " + " ".join(cmd))
    return subprocess.run(cmd, check=check)


def main():
    if len(sys.argv) != 2 or sys.argv[1].startswith("-"):
        print(__doc__)
        sys.exit(1)
    ip = sys.argv[1]
    missing = [t for t in ("ares-setup-device", "ares-novacom", "ares-install", "ares-launch") if not shutil.which(t)]
    if missing:
        print("Missing: %s\nInstall LG's CLI with: npm install -g @webos-tools/cli\n(or see webos/README.md for the "
              "Dev Manager and Homebrew Channel routes)" % ", ".join(missing))
        sys.exit(1)
    if not IPK.exists():
        run(sys.executable, str(HERE / "build.py"))
    info = "host=%s" % ip
    # Add the device, or update it if it's already known.
    if run("ares-setup-device", "-a", DEVICE, "-i", info, "-i", "port=9922", "-i", "username=prisoner", check=False).returncode:
        run("ares-setup-device", "-m", DEVICE, "-i", info, "-i", "port=9922", "-i", "username=prisoner")
    print("\nThe TV's Developer Mode app shows a passphrase; type it when asked.")
    run("ares-novacom", "--device", DEVICE, "--getkey")
    run("ares-install", "--device", DEVICE, str(IPK))
    run("ares-launch", "--device", DEVICE, APP_ID)
    print("\nInstalled. MoLakaz TV is in the TV's app list. Developer Mode apps stop working when the Dev Mode "
          "session runs out (about 50 hours): open the Developer Mode app and press Extend.")


if __name__ == "__main__":
    main()
