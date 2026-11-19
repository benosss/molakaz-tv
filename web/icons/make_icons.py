#!/usr/bin/env python3
"""Render the TV app icons from docs/assets/icon.svg with headless Chrome (no other tools needed).

    python3 web/icons/make_icons.py

Writes next to this script:
  icon-80.png, icon-130.png   webOS appinfo.json icon and largeIcon
  icon-512x423.png            Tizen config.xml icon (Samsung's TV icon size)
  splash.png                  webOS splash (1920x1080)
"""
import os
import pathlib
import struct
import subprocess
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
SVG = (REPO / "docs" / "assets" / "icon.svg").as_uri()
CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")

PAGE = """<!DOCTYPE html><html><head><meta charset="utf-8"><style>
html,body{{margin:0;padding:0;width:{w}px;height:{h}px;overflow:hidden;background:{bg};}}
img{{position:absolute;left:{x}px;top:{y}px;width:{s}px;height:{s}px;}}
.t{{position:absolute;left:0;right:0;top:{ty}px;text-align:center;color:#fff;font:200 64px "Helvetica Neue",Arial,sans-serif;letter-spacing:.01em;}}
</style></head><body><img src="{svg}">{extra}</body></html>"""


def render(name, w, h, size, bg="transparent", extra="", ty=0):
    html = PAGE.format(w=w, h=h, bg=bg, x=(w - size) // 2, y=(h - size) // 2 if not extra else (h - size) // 2 - 60,
                       s=size, svg=SVG, extra=extra, ty=ty)
    with tempfile.TemporaryDirectory() as d:
        page = pathlib.Path(d) / "icon.html"
        page.write_text(html)
        out = HERE / name
        subprocess.run([CHROME, "--headless=new", "--hide-scrollbars", "--default-background-color=00000000",
                        "--allow-file-access-from-files", "--user-data-dir=" + str(pathlib.Path(d) / "prof"),
                        "--window-size=%d,%d" % (w, h), "--screenshot=" + str(out), page.as_uri()],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
    with open(out, "rb") as f:
        head = f.read(24)
    pw, ph = struct.unpack(">II", head[16:24])
    print("wrote %s (%dx%d)" % (out.name, pw, ph))
    if (pw, ph) != (w, h):
        raise SystemExit("unexpected size for %s" % name)


if __name__ == "__main__":
    render("icon-80.png", 80, 80, 80)
    render("icon-130.png", 130, 130, 130)
    render("icon-512x423.png", 512, 423, 400)
    render("splash.png", 1920, 1080, 260, bg="linear-gradient(160deg,#0E6C86 0%,#0B4F6C 45%,#062A3A 100%)",
           extra='<div class="t">MoLakaz TV</div>', ty=1080 // 2 + 110)
