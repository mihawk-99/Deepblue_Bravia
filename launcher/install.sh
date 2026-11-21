#!/usr/bin/env bash
# Install DeepBlue on the TV and make it the Home screen.   ./install.sh          (after ./build.sh)
# Put Google TV's home back:                                 ./install.sh restore
# TV address via TV=<tv-ip>:5555 (required).
set -euo pipefail
cd "$(dirname "$0")"
export PATH="$PWD/../platform-tools:$PATH" ANDROID_SERIAL="${TV:?set TV=<tv-ip>:5555}"
adb connect "$ANDROID_SERIAL" >/dev/null

# Google TV's home and setup apps claim Home at a priority only system apps may use, so a third-party
# launcher only gets Home once they are disabled (not removed: `restore` re-enables them).
STOCK=(com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith)

if [ "${1:-}" = restore ]; then
  for p in "${STOCK[@]}"; do adb shell pm enable "$p"; done
  echo "Google TV home restored; DeepBlue is still installed (adb uninstall com.deepblue.shell to remove it)."
  exit 0
fi

# Android deletes the Wi-Fi networks an app saved when that app is disabled. Networks saved during
# Google TV setup belong to these apps, so disabling them would knock the TV off Wi-Fi.
owner=$(adb shell dumpsys wifi | grep -oE "cname=($(IFS='|'; echo "${STOCK[*]}"))" | sort -u || true)
if [ -n "$owner" ]; then
  echo "Stopping: a saved Wi-Fi network belongs to ${owner#cname=}, and disabling it would delete the network."
  echo "On the TV: Settings > Network & Internet > forget your network, then join it again (it then belongs to Settings). Re-run after."
  exit 1
fi

adb install -r build/DeepBlue.apk
adb shell am start -W -n com.deepblue.shell/.Home >/dev/null  # first start creates the media slot folder
if [ -f tv-media/art.intro.mp4 ] && [ -f tv-media/art.loop.mp4 ]; then
  adb push tv-media/art.intro.mp4 tv-media/art.loop.mp4 /sdcard/Android/data/com.deepblue.shell/files/
fi
# Deck theme: its page, plus the game files make_assets.py built (if you built them). Back twice on the remote switches to it.
if [ -f prototype/deck/bg.png ]; then
  adb shell mkdir -p /sdcard/Android/data/com.deepblue.shell/files/web
  adb push prototype/deck.html prototype/deck /sdcard/Android/data/com.deepblue.shell/files/web/
fi
adb shell cmd role add-role-holder android.app.role.HOME com.deepblue.shell
for p in "${STOCK[@]}"; do adb shell pm disable-user --user 0 "$p"; done
echo "Home now: $(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
