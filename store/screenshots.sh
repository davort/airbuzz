#!/usr/bin/env bash
# Takes the Play Store phone screenshots from the debug build on an emulator, in light and dark, so they can be taken
# again whenever the app's look changes. The data is made up: fictional people, the emulator's own apps, and a fixed day of
# activity (09:12–18:22, outside its quiet hours). Nothing here touches a real phone's settings, so point it at an emulator only.
#
# Usage: store/screenshots.sh [emulator serial, default emulator-5554] [output dir, default store/screenshots]
# Needs: a booted emulator, and app/build/outputs/apk/debug/app-debug.apk (./gradlew :app:assembleDebug).
# Output: 1080 x 1920 PNGs (9:16, what Play wants), named <n>-<screen>-<light|dark>.png. Captions are added later.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
serial="${1:-emulator-5554}"
out="${2:-store/screenshots}"
pkg=com.davort.airbuzz
adb=(adb -s "$serial")
[[ "$("${adb[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo "$serial is not an emulator" >&2; exit 1; }
mkdir -p "$out"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

"${adb[@]}" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"${adb[@]}" shell wm size 1080x1920
"${adb[@]}" shell wm density 420
"${adb[@]}" shell pm grant $pkg android.permission.BLUETOOTH_CONNECT
"${adb[@]}" shell pm grant $pkg android.permission.POST_NOTIFICATIONS
"${adb[@]}" shell cmd notification allow_listener $pkg/$pkg.service.AlertListener
"${adb[@]}" shell svc bluetooth enable >/dev/null 2>&1 || true
# A clean status bar: 18:30 (just after the made-up day), 24-hour, full battery and signal, no
# notifications.
"${adb[@]}" shell settings put system time_12_24 24
"${adb[@]}" shell settings put global sysui_demo_allowed 1
demo() { "${adb[@]}" shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
demo clock -e hhmm 1830
demo battery -e level 100 -e plugged false
demo network -e fully true
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile hide
demo notifications -e visible false

messages=com.google.android.apps.messaging
gmail=com.google.android.gm
dialer=com.google.android.dialer
# A fixed day, all outside the quiet hours below, with the status bar clock at 18:30.
midnight=$(( $(date -j -v0H -v0M -v0S +%s) * 1000 ))
at() { echo $(( midnight + (10#${1%:*} * 60 + 10#${1#*:}) * 60000 )); }
cat > "$tmp/activity.json" <<JSON
[
 {"t":$(at 09:12),"k":"MESSAGE","p":"$gmail","a":"Gmail","w":"Newsletter","n":null,"r":"SKIPPED","why":"not chosen","c":1,"rang":null,"lat":null},
 {"t":$(at 11:47),"k":"CALL","p":"$dialer","a":"Phone","w":"Unknown caller","n":"+46812345678","r":"SKIPPED","why":"not chosen","c":1,"rang":null,"lat":null},
 {"t":$(at 13:05),"k":"MESSAGE","p":"$messages","a":"Messages","w":"Noor","n":null,"r":"BUZZED","why":null,"c":2,"rang":null,"lat":2300},
 {"t":$(at 15:41),"k":"MESSAGE","p":"$messages","a":"Messages","w":"Erik Lund","n":null,"r":"SKIPPED","why":"phone in use","c":1,"rang":null,"lat":null},
 {"t":$(at 17:36),"k":"CALL","p":"$dialer","a":"Phone","w":"Mum","n":"+46701112233","r":"BUZZED","why":null,"c":1,"rang":9000,"lat":2100},
 {"t":$(at 18:22),"k":"MESSAGE","p":"$messages","a":"Messages","w":"Noor","n":null,"r":"BUZZED","why":null,"c":1,"rang":null,"lat":1800}
]
JSON

settings() { # $1 = LIGHT or DARK
  local json='{"onboarded":true,"enabled":true,"theme":"'"$1"'","callMode":"CHOSEN",
"people":[{"name":"Mum","number":"+46701112233"},{"name":"Noor Ahlgren","number":"+46702223344"},{"name":"Erik Lund","number":"+46703334455"}],
"apps":["'"$messages"'"],"messageTaps":1,"quietDnd":true,"quietHours":true,"quietStart":1380,"quietEnd":420,"quietInUse":true,
"ringAnyway":true,"ringAnywayPeople":[{"name":"Mum","number":"+46701112233"}]}'
  json="$(tr -d '\n' <<<"$json" | python3 -c 'import html,sys; print(html.escape(sys.stdin.read()))')"
  printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n<string name="v1">%s</string>\n</map>\n' "$json" > "$tmp/settings.xml"
}

put() { # file in $tmp, path inside the app's data
  "${adb[@]}" push "$tmp/$1" "/data/local/tmp/$1" >/dev/null
  "${adb[@]}" shell run-as $pkg cp "/data/local/tmp/$1" "$2"
}

center() { # the centre of the first node whose text or description starts with $1
  "${adb[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null
  "${adb[@]}" shell cat /sdcard/ui.xml | grep -oE "(text|content-desc)=\"$1[^\"]*\"[^>]*bounds=\"[^\"]*\"" | head -1 |
    sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' | awk '{ print int(($1+$3)/2), int(($2+$4)/2) }'
}
tap() { local p; p="$(center "$1")"; [[ -n "$p" ]] || { echo "Can't find \"$1\" on screen" >&2; exit 1; }; "${adb[@]}" shell input tap $p; sleep "${2:-1.5}"; }
shot() { "${adb[@]}" exec-out screencap -p > "$out/$1"; echo "$out/$1"; }

for mode in LIGHT DARK; do
  m="$(tr A-Z a-z <<<"$mode")"
  settings $mode
  "${adb[@]}" shell am force-stop $pkg
  "${adb[@]}" shell run-as $pkg mkdir -p shared_prefs files
  put settings.xml shared_prefs/settings.xml
  put activity.json files/activity.json
  "${adb[@]}" shell run-as $pkg touch files/store-demo
  "${adb[@]}" shell am start -n $pkg/.ui.MainActivity >/dev/null
  sleep 3
  shot "1-home-$m.png"
  tap "Who can ring"; shot "2-who-can-ring-$m.png"; "${adb[@]}" shell input keyevent BACK; sleep 1.2
  tap "Apps that tap"; shot "3-apps-$m.png"; "${adb[@]}" shell input keyevent BACK; sleep 1.2
  tap "When to stay quiet"; shot "4-stay-quiet-$m.png"; "${adb[@]}" shell input keyevent BACK; sleep 1.2
  tap "3 BUZZED"; tap "All"; shot "5-activity-$m.png"; "${adb[@]}" shell input keyevent BACK; sleep 1
done

"${adb[@]}" shell run-as $pkg rm -f files/store-demo
demo exit
"${adb[@]}" shell wm size reset
"${adb[@]}" shell wm density reset
