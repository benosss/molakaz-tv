#!/usr/bin/env python3
"""Screenshots of the web version in headless Chrome at 1920x1080, for checking the look against the Android app.

    python3 web/preview/shoot.py                       # films and stills from the GitHub release
    python3 web/preview/shoot.py --films DIR --stills DIR   # local copies (H.264 plays in Chrome; HEVC may not)

Uses the browser stand-in (sample apps), a fixed town (Port Louis) and a fixed clock so shots are repeatable.
Writes JPEGs next to this script (PNGs where macOS's sips isn't available).
"""
import argparse
import os
import pathlib
import shutil
import subprocess
import tempfile
import urllib.parse

HERE = pathlib.Path(__file__).resolve().parent
WEB = HERE.parent
CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")

SHOTS = [
    # name, query
    ("home", dict(film="jokulsarlon", now="2026-11-18T17:21", theme="day")),
    ("rail-focus", dict(film="le_morne", now="2026-11-18T11:21", theme="day", focus="rail:2")),
    ("row-focus", dict(film="lofoten", now="2026-11-18T15:05", theme="day", focus="row:1")),
    ("picks", dict(film="manali", now="2026-11-18T23:26", theme="day", focus="row:0", needs="picks")),
    ("night", dict(film="halong_sunset", now="2026-11-18T21:30", theme="night", focus="rail:0")),
    ("inputs", dict(film="dolomites", now="2026-11-18T19:02", theme="day", screen="inputs", keys="right")),
    ("settings", dict(film="ladakh", now="2026-11-18T19:02", theme="day", screen="settings")),
    ("settings-bar", dict(film="ladakh", now="2026-11-18T19:02", theme="day", screen="settings", keys="down,ok,down")),
    ("all-apps", dict(film="bacalar", now="2026-11-18T19:02", theme="day", screen="apps", keys="right,right")),
]


def file_url(p):
    return pathlib.Path(p).resolve().as_uri() + "/"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--films", help="folder of <name>.mp4 (default: the GitHub release)")
    ap.add_argument("--stills", help="folder of <name>.jpg (default: same as --films)")
    ap.add_argument("--picks", help="a picks.json ({items: [...]}) saved from the real sources, for the Fresh picks shot")
    ap.add_argument("--only", help="comma-separated shot names")
    ap.add_argument("--budget", type=int, default=7000, help="virtual time budget in ms")
    a = ap.parse_args()
    only = set(a.only.split(",")) if a.only else None
    for name, q in SHOTS:
        if only and name not in only:
            continue
        query = dict(platform="browser", noguess="1", scenes="scenes.json", picks="0")
        if a.films:
            query["films"] = file_url(a.films)
        if a.stills or a.films:
            query["stills"] = file_url(a.stills or a.films)
        q = dict(q)
        if q.pop("needs", None) == "picks":
            if not a.picks:
                print("skipped", name, "(needs --picks)")
                continue
            query["picksfile"] = pathlib.Path(a.picks).resolve().as_uri()
        query.update(q)
        url = (WEB / "index.html").as_uri() + "?" + urllib.parse.urlencode(query)
        prof = tempfile.mkdtemp(prefix="mlk-chrome-")
        out = pathlib.Path(prof) / (name + ".png")
        try:
            subprocess.run([
                CHROME, "--headless=new", "--hide-scrollbars", "--autoplay-policy=no-user-gesture-required",
                "--allow-file-access-from-files", "--user-data-dir=" + prof, "--window-size=1920,1080",
                "--virtual-time-budget=%d" % a.budget, "--screenshot=" + str(out), url,
            ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
            # JPEG keeps the repo small (a 1080p PNG of a film is ~2 MB); sips ships with macOS.
            if shutil.which("sips"):
                dest = HERE / (name + ".jpg")
                subprocess.run(["sips", "-s", "format", "jpeg", "-s", "formatOptions", "84", str(out), "--out", str(dest)],
                               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            else:
                dest = HERE / (name + ".png")
                shutil.copy(out, dest)
            print("wrote", dest)
        finally:
            shutil.rmtree(prof, ignore_errors=True)


if __name__ == "__main__":
    main()
