#!/bin/bash
# Builds the MoLakaz folder for a USB stick: the app, the films, the computer installers and, optionally, more apps.
#
#   kit/make_usb_kit.sh <app.apk> <films dir> [extras dir]
#
#   <films dir>   clips/, blur/, ambient/ and scenes.json (the same layout as the films download)
#   [extras dir]  optional: apps/*.apk and extra_apps.json, installed and shown by the first-run set-up (see Apps.kt)
#
# The result is kit/out/MoLakaz: copy that folder to the top of a USB stick (FAT32 or exFAT).
set -euo pipefail
APK=${1:?app apk}; FILMS=${2:?films dir}; EXTRAS=${3:-}
HERE=$(cd "$(dirname "$0")" && pwd); OUT="$HERE/out/MoLakaz"
rm -rf "$OUT"; mkdir -p "$OUT/films" "$OUT/computer"
cp "$APK" "$OUT/MoLakaz-TV.apk"
rsync -a --exclude '*.part*' --exclude '.DS_Store' "$FILMS"/clips "$FILMS"/blur "$FILMS"/ambient "$FILMS"/scenes.json "$OUT/films/"
if [ -n "$EXTRAS" ]; then
  [ -d "$EXTRAS/apps" ] && { mkdir -p "$OUT/apps"; cp "$EXTRAS"/apps/*.apk "$OUT/apps/"; }
  [ -f "$EXTRAS/extra_apps.json" ] && cp "$EXTRAS/extra_apps.json" "$OUT/"
fi
# Plan B, with a computer: the installers and Google's adb for Windows and Mac.
cp "$HERE"/computer/* "$OUT/computer/"
chmod +x "$OUT/computer/"*.command
mkdir -p "$OUT/computer/tools"
for os in windows darwin; do
  z="$HERE/out/platform-tools-$os.zip"
  [ -f "$z" ] || curl -fsSL -o "$z" "https://dl.google.com/android/repository/platform-tools-latest-$os.zip"
  unzip -q -o "$z" -d "$OUT/computer/tools/$os"
done
# Only adb and what it needs: the rest of platform-tools isn't used.
find "$OUT/computer/tools" -type f ! -name 'adb' ! -name 'adb.exe' ! -name 'AdbWinApi.dll' ! -name 'AdbWinUsbApi.dll' ! -name 'NOTICE.txt' -delete
find "$OUT/computer/tools" -type d -empty -delete
echo "Kit ready: $OUT ($(du -sh "$OUT" | cut -f1))"
