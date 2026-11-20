#!/usr/bin/env bash
# Copy each TV app's launcher banner (or icon if it has none) into prototype/banners/ + apps.json,
# so the browser prototype shows the real app list. Only small files leave the TV: manifest,
# resource table and one image per app. Output is gitignored (third-party logos).
# Usage: TV=ip:port ./pull_banners.sh
set -u
cd "$(dirname "$0")"
ROOT=../..
export PATH="$ROOT/platform-tools:$PATH" ANDROID_SERIAL="${TV:-192.168.50.143:5555}"
AAPT2=$ROOT/android-sdk/build-tools/35.0.0/aapt2
TMP=$(mktemp -d) && trap 'rm -rf "${TMP:?}"' EXIT
mkdir -p banners && rm -f banners/*
adb connect "$ANDROID_SERIAL" >/dev/null
DEV=/data/local/tmp/ban

pkgs=$(adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER | grep / | cut -d/ -f1 | tr -d ' \r' | sort -u)
json="["
for p in $pkgs com.android.tv.settings; do
  apks=$(adb shell pm path "$p" | cut -d: -f2 | tr -d '\r')
  base=$(echo "$apks" | head -1)  # base.apk for store apps, Foo.apk for system apps
  adb shell "rm -rf $DEV && mkdir -p $DEV && cd $DEV && unzip -o -q '$base' AndroidManifest.xml resources.arsc" || continue
  adb pull -q "$DEV/AndroidManifest.xml" "$DEV/resources.arsc" "$TMP/" || continue
  (cd "$TMP" && zip -q -0 -FS mini.apk AndroidManifest.xml resources.arsc)
  badge=$("$AAPT2" dump badging "$TMP/mini.apk" 2>/dev/null | grep '^application:')
  label=$(echo "$badge" | sed -n "s/.*label='\([^']*\)'.*/\1/p")
  # every file in base + split APKs, as "entry apk" lines
  listing=$(for a in $apks; do adb shell "unzip -l '$a' 2>/dev/null" | tr -d '\r' | awk -v a="$a" '$4 ~ /^res\// {print $4" "a}'; done)
  best=""; bestapk=""
  for attr in banner icon; do  # banner first, app icon as fallback
    ref=$(echo "$badge" | sed -n "s/.* $attr='\([^']*\)'.*/\1/p"); [ -z "$ref" ] && continue
    dir=$(dirname "$ref"); stem=$(basename "${ref%.*}")
    if [ "$dir" = res ]; then  # shrunk apps use flat names like res/dN.png: only that exact file (res/dN.jpg is another resource)
      [[ "$ref" == *.xml ]] && continue
      pats=("^${ref//./\\.} ")
    else  # sharpest density first; an adaptive .xml usually has png twins in density folders
      kind=$(basename "$dir" | cut -d- -f1)
      pats=(); for d in xxxhdpi xxhdpi xhdpi hdpi mdpi nodpi ''; do pats+=("^res/$kind${d:+-$d}(-v[0-9]+)?/$stem\.(png|webp|jpg) "); done
    fi
    for pat in "${pats[@]}"; do
      hit=$(echo "$listing" | grep -E -e "$pat" | head -1)
      [ -n "$hit" ] && { best=${hit%% *}; bestapk=${hit#* }; break 2; }
    done
  done
  file=""
  if [ -n "$best" ]; then
    file="banners/$p.${best##*.}"
    adb exec-out "unzip -p '$bestapk' '$best'" > "$file"  # exec-out: binary-safe, no tty mangling
  fi
  [ "$p" = com.android.tv.settings ] && label=System
  echo "$p: ${label:-?} -> ${file:-no image}"
  json+="{\"pkg\":\"$p\",\"label\":\"${label//\"/}\",\"banner\":\"$file\"},"
done
adb shell "rm -rf $DEV"
echo "${json%,}]" > banners/apps.json
