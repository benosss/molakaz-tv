#!/usr/bin/env python3
"""Build MoLakaz TV for LG webOS: out/MoLakazTV-webos.ipk, without LG's SDK.

    python3 webos/build.py            # writes webos/out/MoLakazTV-webos.ipk
    python3 webos/build.py --list     # ...and lists what's inside

An .ipk is an `ar` archive of three members, the same layout `ares-package` writes:
  debian-binary     "2.0"
  control.tar.gz    ./control
  data.tar.gz       ./usr/palm/applications/tv.molakaz.home/  (appinfo.json, icons, the web app)
                    ./usr/palm/packages/tv.molakaz.home/packageinfo.json
"""
import argparse
import gzip
import io
import json
import pathlib
import re
import sys
import tarfile
import time

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
WEB = REPO / "web"
OUT = HERE / "out"
APP_ID = "tv.molakaz.home"
TITLE = "MoLakaz TV"
VENDOR = "MoLakaz TV"

# What goes into the package from web/ (the preview screenshots and icon tools stay out).
SKIP_DIRS = {"preview"}
SKIP_FILES = {"make_icons.py", ".DS_Store"}


def version():
    m = re.search(r"VERSION = '(\d+\.\d+\.\d+)'", (WEB / "main.js").read_text())
    if not m:
        sys.exit("can't find VERSION in web/main.js")
    return m.group(1)


def appinfo(ver):
    return {
        "id": APP_ID,
        "version": ver,
        "vendor": VENDOR,
        "type": "web",
        "main": "index.html",
        "title": TITLE,
        "icon": "icons/icon-80.png",
        "largeIcon": "icons/icon-130.png",
        "splashBackground": "icons/splash.png",
        "iconColor": "#0B4F6C",
        "resolution": "1920x1080",
        # Back (461) reaches the app instead of closing it; Home handles it (Back twice closes).
        "disableBackHistoryAPI": True,
        # Opening the app again while it runs sends webOSRelaunch instead of reloading the page.
        "handlesRelaunch": True,
        "transparent": False,
    }


def web_files():
    out = []
    for p in sorted(WEB.rglob("*")):
        rel = p.relative_to(WEB)
        if p.is_dir() or rel.parts[0] in SKIP_DIRS or p.name in SKIP_FILES:
            continue
        if rel.parts[0] == "icons" and p.name not in ("icon-80.png", "icon-130.png", "splash.png"):
            continue
        out.append((rel.as_posix(), p.read_bytes()))
    return out


def tar_gz(entries, mtime):
    """entries: list of (path, bytes or None for a directory). Owned by root, like ares-package makes them."""
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", mtime=mtime) as gz:
        with tarfile.open(fileobj=gz, mode="w", format=tarfile.USTAR_FORMAT) as tar:
            for path, data in entries:
                ti = tarfile.TarInfo(path)
                ti.mtime = mtime
                ti.uid = ti.gid = 0
                ti.uname = ti.gname = "root"
                if data is None:
                    ti.type = tarfile.DIRTYPE
                    ti.mode = 0o755
                    tar.addfile(ti)
                else:
                    ti.mode = 0o644
                    ti.size = len(data)
                    tar.addfile(ti, io.BytesIO(data))
    return buf.getvalue()


def with_dirs(files):
    """Add every parent directory (./usr, ./usr/palm, ...) before the files, in order."""
    seen = set()
    out = [("./", None)]
    for path, data in files:
        parts = path.split("/")[1:-1]  # skip leading "."
        for i in range(1, len(parts) + 1):
            d = "./" + "/".join(parts[:i]) + "/"
            if d not in seen:
                seen.add(d)
                out.append((d, None))
        out.append((path, data))
    return out


def ar(members, mtime):
    """A common-format `ar` archive (what dpkg and opkg read)."""
    out = io.BytesIO()
    out.write(b"!<arch>\n")
    for name, data in members:
        hdr = "%-16s%-12d%-6d%-6d%-8s%-10d`\n" % (name, mtime, 0, 0, "100644", len(data))
        assert len(hdr) == 60
        out.write(hdr.encode("ascii"))
        out.write(data)
        if len(data) % 2:
            out.write(b"\n")
    return out.getvalue()


def build():
    ver = version()
    mtime = int(time.time())
    app_dir = "./usr/palm/applications/%s/" % APP_ID
    files = [(app_dir + "appinfo.json", (json.dumps(appinfo(ver), indent=2) + "\n").encode())]
    for rel, data in web_files():
        files.append((app_dir + rel, data))
    pkginfo = {"app": APP_ID, "id": APP_ID, "loc_name": TITLE, "package_format_version": 2, "vendor": VENDOR, "version": ver}
    files.append(("./usr/palm/packages/%s/packageinfo.json" % APP_ID, (json.dumps(pkginfo, indent=2) + "\n").encode()))
    files.sort(key=lambda f: f[0])
    data_tgz = tar_gz(with_dirs(files), mtime)

    size_kb = max(1, sum(len(d) for _, d in files) // 1024)
    control = (
        "Package: %s\n"
        "Version: %s\n"
        "Section: misc\n"
        "Priority: optional\n"
        "Architecture: all\n"
        "Installed-Size: %d\n"
        "Maintainer: MoLakaz TV <https://github.com/benosss/molakaz-tv>\n"
        "Description: %s, a calm TV home screen\n"
        "webOS-Package-Format-Version: 2\n"
        "webOS-Packager-Version: x.y.x\n"
    ) % (APP_ID, ver, size_kb, TITLE)
    control_tgz = tar_gz([("./", None), ("./control", control.encode())], mtime)

    ipk = ar([("debian-binary", b"2.0\n"), ("control.tar.gz", control_tgz), ("data.tar.gz", data_tgz)], mtime)
    OUT.mkdir(exist_ok=True)
    path = OUT / "MoLakazTV-webos.ipk"
    path.write_bytes(ipk)
    print("wrote %s (%d KB, version %s, %d files)" % (path, len(ipk) // 1024, ver, len(files)))
    return path


def list_ipk(path):
    """Read the ipk back: ar members, then each tar's contents."""
    raw = path.read_bytes()
    assert raw[:8] == b"!<arch>\n", "not an ar archive"
    pos = 8
    while pos < len(raw):
        hdr = raw[pos:pos + 60]
        name = hdr[:16].decode().strip()
        size = int(hdr[48:58].decode().strip())
        data = raw[pos + 60:pos + 60 + size]
        print("ar member: %-16s %8d bytes" % (name, size))
        if name.endswith(".tar.gz"):
            with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as t:
                for m in t.getmembers():
                    print("    %s %s %8d  %s" % ("d" if m.isdir() else "-", oct(m.mode), m.size, m.name))
        else:
            print("    %r" % data)
        pos += 60 + size + (size % 2)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--list", action="store_true", help="list the package's contents after building")
    a = ap.parse_args()
    p = build()
    if a.list:
        list_ipk(p)
