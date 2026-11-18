#!/bin/bash
# MoLakaz TV - install from a Mac (double-click). Same steps as the Windows one.
cd "$(dirname "$0")"
ADB="$PWD/tools/darwin/platform-tools/adb"; KIT="$PWD/.."
xattr -dr com.apple.quarantine "$PWD/tools" 2>/dev/null
echo; echo "  MoLakaz TV - install from a computer"; echo "  ------------------------------------"
echo "  The TV box and this Mac must be on the same Wi-Fi, and USB debugging must be on (see the guide, step 3)."; echo
read -r -p "  Type the TV box's IP address (TV: Settings > Network > Wi-Fi) and press Enter: " IP
"$ADB" connect "$IP:5555"
echo; read -r -p "  Look at the TV: if it asks \"Allow USB debugging?\", tick \"Always allow\" and choose OK. Then press Enter here. "
A=("$ADB" -s "$IP:5555"); F=/sdcard/Android/data/com.lagoon.home/files
"${A[@]}" install -r "$KIT/MoLakaz-TV.apk"
for f in "$KIT"/apps/*.apk; do [ -f "$f" ] && "${A[@]}" install -r "$f"; done
echo "  Copying the films (a few minutes)..."
"${A[@]}" shell mkdir -p $F
for d in clips blur ambient; do "${A[@]}" push "$KIT/films/$d" $F/; done
[ -f "$KIT/extra_apps.json" ] && "${A[@]}" push "$KIT/extra_apps.json" $F/
"${A[@]}" push "$KIT/films/scenes.json" $F/
"${A[@]}" shell am start -n com.lagoon.home/.HomeActivity
echo; echo "  Done here. Finish on the TV: MoLakaz TV is open, press OK to start the set-up."
read -r -p "  Press Enter to close. "
