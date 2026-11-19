#!/usr/bin/env python3
"""Build MoLakaz TV for Samsung Tizen, without Samsung's SDK.

    python3 tizen/build.py                    # tizen/out/MoLakazTV-tizen.wgt (unsigned) + tizen/out/build/
    python3 tizen/build.py --list             # ...and lists what's inside
    python3 tizen/build.py --tizenbrew        # also tizen/out/tizenbrew/, an npm package for TizenBrew
    python3 tizen/build.py --sign PROFILE     # also a signed .wgt, if Tizen Studio's `tizen` CLI is installed

A .wgt is a zip: config.xml (id, privileges), icon.png and the web app. Samsung TVs only install SIGNED widgets
(an author certificate tied to the TV's DUID plus a distributor certificate, both from Tizen Studio's Certificate
Manager). The unsigned .wgt is what you sign; see tizen/README.md for the two ways to get it onto a TV.
"""
import argparse
import json
import pathlib
import re
import shutil
import subprocess
import sys
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
WEB = REPO / "web"
OUT = HERE / "out"
PACKAGE = "MoLakazTV1"          # Tizen package ids are exactly 10 letters or digits
APP_ID = PACKAGE + ".Home"
SKIP_DIRS = {"preview"}
SKIP_FILES = {"make_icons.py", ".DS_Store"}

CONFIG = """<?xml version="1.0" encoding="UTF-8"?>
<widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets"
        id="https://github.com/benosss/molakaz-tv" version="{ver}" viewmodes="maximized">
    <tizen:application id="{app}" package="{pkg}" required_version="3.0"/>
    <name>MoLakaz TV</name>
    <description>A calm TV home screen: nature films, your apps on a glass bar, the clock and the weather.</description>
    <author href="https://github.com/benosss/molakaz-tv">MoLakaz TV</author>
    <license href="https://opensource.org/licenses/MIT">MIT</license>
    <content src="index.html"/>
    <icon src="icon.png"/>
    <feature name="http://tizen.org/feature/screen.size.all"/>
    <!-- Films, weather, town search and Fresh picks come from the internet. -->
    <access origin="*" subdomains="true"/>
    <tizen:privilege name="http://tizen.org/privilege/internet"/>
    <!-- List the TV's apps and open them. -->
    <tizen:privilege name="http://tizen.org/privilege/application.info"/>
    <tizen:privilege name="http://tizen.org/privilege/application.launch"/>
    <!-- Colour keys, media keys and Exit from the remote. -->
    <tizen:privilege name="http://tizen.org/privilege/tv.inputdevice"/>
    <!-- Inputs screen: pick HDMI 1-4 or the tuner on the main TV window. -->
    <tizen:privilege name="http://tizen.org/privilege/tv.window"/>
    <tizen:profile name="tv-samsung"/>
    <tizen:setting screen-orientation="landscape" context-menu="disable" background-support="disable"
                   encryption="disable" install-location="auto" hwkey-event="enable"/>
</widget>
"""


def version():
    m = re.search(r"VERSION = '(\d+\.\d+\.\d+)'", (WEB / "main.js").read_text())
    if not m:
        sys.exit("can't find VERSION in web/main.js")
    return m.group(1)


def web_files():
    out = []
    for p in sorted(WEB.rglob("*")):
        rel = p.relative_to(WEB)
        if p.is_dir() or rel.parts[0] in SKIP_DIRS or p.name in SKIP_FILES or rel.parts[0] == "icons":
            continue
        out.append((rel.as_posix(), p.read_bytes()))
    return out


def stage(ver):
    """tizen/out/build/: the app as Tizen Studio's packager wants it (also what gets signed)."""
    d = OUT / "build"
    if d.exists():
        shutil.rmtree(d)
    d.mkdir(parents=True)
    (d / "config.xml").write_text(CONFIG.format(ver=ver, app=APP_ID, pkg=PACKAGE))
    shutil.copy(WEB / "icons" / "icon-512x423.png", d / "icon.png")
    for rel, data in web_files():
        f = d / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_bytes(data)
    return d


def zip_dir(src, dest):
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
        # config.xml first, as Tizen's own packager does.
        z.write(src / "config.xml", "config.xml")
        for p in sorted(src.rglob("*")):
            if p.is_file() and p.name != "config.xml":
                z.write(p, p.relative_to(src).as_posix())


def tizenbrew(ver):
    """An npm package TizenBrew can load as an app module (it serves the files itself; nothing is installed)."""
    d = OUT / "tizenbrew"
    if d.exists():
        shutil.rmtree(d)
    (d / "app").mkdir(parents=True)
    for rel, data in web_files():
        f = d / "app" / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_bytes(data)
    pkg = {
        "name": "molakaz-tv",
        "version": ver,
        "description": "MoLakaz TV, a calm TV home screen (TizenBrew app module)",
        "license": "MIT",
        "repository": {"type": "git", "url": "https://github.com/benosss/molakaz-tv"},
        "packageType": "app",
        "appName": "MoLakaz TV",
        "appPath": "app/index.html",
        "keys": ["MediaPlayPause", "MediaPlay", "MediaPause", "MediaFastForward", "MediaRewind",
                 "ColorF0Red", "ColorF1Green", "ColorF2Yellow", "ColorF3Blue"],
        "files": ["app/"],
    }
    (d / "package.json").write_text(json.dumps(pkg, indent=2) + "\n")
    print("wrote %s (publish with: cd %s && npm publish)" % (d, d))


def sign(build_dir, profile):
    tz = shutil.which("tizen")
    if not tz:
        sys.exit("--sign needs Tizen Studio's `tizen` CLI on PATH (tizen-studio/tools/ide/bin)")
    subprocess.run([tz, "package", "-t", "wgt", "-s", profile, "--", str(build_dir)], check=True)
    made = sorted(build_dir.glob("*.wgt"), key=lambda p: p.stat().st_mtime)
    if not made:
        sys.exit("the tizen CLI didn't produce a .wgt")
    dest = OUT / "MoLakazTV-tizen-signed.wgt"
    shutil.move(str(made[-1]), dest)
    print("wrote", dest)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--list", action="store_true", help="list the package's contents after building")
    ap.add_argument("--tizenbrew", action="store_true", help="also make the TizenBrew npm package")
    ap.add_argument("--sign", metavar="PROFILE", help="also sign with this Tizen Studio security profile")
    a = ap.parse_args()
    ver = version()
    build_dir = stage(ver)
    wgt = OUT / "MoLakazTV-tizen.wgt"
    zip_dir(build_dir, wgt)
    with zipfile.ZipFile(wgt) as z:
        n = len(z.namelist())
        assert z.namelist()[0] == "config.xml"
        assert z.testzip() is None
    print("wrote %s (%d KB, version %s, %d files, unsigned)" % (wgt, wgt.stat().st_size // 1024, ver, n))
    if a.list:
        with zipfile.ZipFile(wgt) as z:
            for i in z.infolist():
                print("    %8d  %s" % (i.file_size, i.filename))
    if a.tizenbrew:
        tizenbrew(ver)
    if a.sign:
        sign(build_dir, a.sign)


if __name__ == "__main__":
    main()
