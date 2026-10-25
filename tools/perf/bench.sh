#!/usr/bin/env bash
# perf-browser bench (EMULATOR evidence, not a real phone). Runs the BEFORE and AFTER debug APKs on the SAME booted
# emulator, alternating rounds (B A, A B, B A...) so drift hits both equally, and records per phase:
#   cold open of the browser on a page (am start -W, page FCP / load from the page's own console report),
#   scroll, open a 2nd tab and scroll while it loads, tab switching through the grid, toolbar drag to top and back
# with `dumpsys gfxinfo` (jank %, frame percentiles, slow UI thread frames, worst frame) after a reset per phase.
# usage: bench.sh <before.apk> <after.apk> <outdir>        env: ROUNDS (default 3)
set -u
BEFORE="$1"; AFTER="$2"; OUT="${3:-perf-out}"; ROUNDS="${ROUNDS:-3}"
PKG=com.blaze.assistant
ACT="$PKG/.browser.BrowserActivity"
PORT=8765
HOST="http://10.0.2.2:$PORT"
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"

python3 "$HERE/server.py" "$PORT" > "$OUT/server.log" 2>&1 &
SRV=$!
trap 'kill $SRV 2>/dev/null || true' EXIT

adb wait-for-device
for i in $(seq 1 90); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 2; done
adb shell settings put global window_animation_scale 1 || true
adb shell settings put global transition_animation_scale 1 || true
adb shell settings put global animator_duration_scale 1 || true
adb shell svc power stayon true || true
adb shell input keyevent 82 || true
adb shell wm dismiss-keyguard || true

SIZE=$(adb shell wm size | tr -d '\r' | awk -F': ' '/Override/{o=$2} /Physical/{p=$2} END{print (o!=""?o:p)}')
W=${SIZE%x*}; H=${SIZE#*x}
{
  echo "EMULATOR (x86_64 google_apis on a GitHub runner, software GPU). Not a real phone."
  echo "size=$SIZE density=$(adb shell wm density | tr -d '\r' | tail -1)"
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r') abi=$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
  echo "webview=$(adb shell dumpsys webviewupdate 2>/dev/null | tr -d '\r' | grep -m1 'Current WebView package')"
  echo "rounds=$ROUNDS before=$(sha256sum "$BEFORE" | cut -c1-16) after=$(sha256sum "$AFTER" | cut -c1-16)"
} > "$OUT/device.txt"
cat "$OUT/device.txt"

now_ms() {
  local t; t=$(adb shell date +%s%3N 2>/dev/null | tr -d '\r')
  case "$t" in *N*|"") t=$(adb shell 'echo $EPOCHREALTIME' | tr -d '\r.' | cut -c1-13) ;; esac
  echo "$t"
}
page_down() { adb shell input swipe $((W/2)) $((H*72/100)) $((W/2)) $((H*32/100)) 220; }   # finger up: page scrolls down
page_up()   { adb shell input swipe $((W/2)) $((H*32/100)) $((W/2)) $((H*72/100)) 220; }
tap() { adb shell input tap "$1" "$2"; }
gfx_reset() { adb shell dumpsys gfxinfo "$PKG" reset > /dev/null 2>&1; }
gfx_dump() { adb shell dumpsys gfxinfo "$PKG" > "$1" 2>&1; }
ui_dump() {   # uiautomator xml on stdout (retries: it refuses while the screen is busy)
  for k in 1 2 3 4; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb shell cat /sdcard/ui.xml 2>/dev/null && return 0
    sleep 1
  done
  return 1
}
find_ui() { python3 "$HERE/ui.py" "$1" "$2" < "$3"; }   # attr value file -> "x y"
wait_perf() {   # page title, timeout s -> the page's BLZPERF json on stdout
  for k in $(seq 1 "$2"); do
    local l; l=$(adb logcat -d 2>/dev/null | grep 'BLZPERF' | grep "\"title\":\"$1\"" | tail -1)
    if [ -n "$l" ]; then echo "$l" | sed -n 's/.*BLZPERF \({.*}\)".*/\1/p'; return 0; fi
    sleep 1
  done
  return 1
}

run_one() {
  local v="$1" apk="$2" r="$3" P="$OUT/$1-r$3"
  echo "=== $v round $r"
  adb uninstall "$PKG" > /dev/null 2>&1 || true
  adb install -r -g -t "$apk" > "$P-install.txt" 2>&1 || { echo "install failed"; cat "$P-install.txt"; return 1; }

  # Warm-up: the first launch runs Blaze's one-time setup and loads the WebView provider; then a clean cold start.
  adb shell am start -W -a android.intent.action.VIEW -d "$HOST/page.html?name=W" -n "$ACT" > "$P-warm.txt" 2>&1
  sleep 25
  adb shell am force-stop "$PKG"; sleep 3

  # 1) Cold open of the browser on page A.
  adb logcat -c || true
  echo "{\"t0\": $(now_ms)}" > "$P-t0.json"
  adb shell am start -W -a android.intent.action.VIEW -d "$HOST/page.html?name=A" -n "$ACT" > "$P-start.txt" 2>&1
  wait_perf "Perf A" 45 > "$P-load.json" || echo "no page report for A"
  local APID; APID=$(adb shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')
  sleep 3

  # 2) Scroll the loaded page down and back up.
  gfx_reset
  for i in $(seq 1 12); do page_down; sleep 0.35; done
  for i in $(seq 1 12); do page_up; sleep 0.35; done
  sleep 1; gfx_dump "$P-scroll.txt"

  # 3) A link opens page B in a new tab; scroll while it is still loading.
  gfx_reset
  echo "{\"t1\": $(now_ms)}" > "$P-t1.json"
  adb shell am start -a android.intent.action.VIEW -d "$HOST/page.html?name=B" -n "$ACT" > /dev/null 2>&1
  for i in $(seq 1 8); do sleep 0.25; page_down; done
  wait_perf "Perf B" 30 > "$P-loadB.json" || echo "no page report for B"
  for i in $(seq 1 8); do page_up; sleep 0.3; done
  sleep 1; gfx_dump "$P-loadscroll.txt"

  # 4) Tab switching through the grid: Tabs -> A, Tabs -> B, five times.
  page_up; page_up; sleep 1.5
  ui_dump > "$P-ui-page.xml"
  local TABS="" GA="" GB=""
  TABS=$(find_ui content-desc "Tabs" "$P-ui-page.xml")
  if [ -n "$TABS" ]; then
    tap $TABS; sleep 2.5
    ui_dump > "$P-ui-grid.xml"
    GA=$(find_ui text "Perf A" "$P-ui-grid.xml"); GB=$(find_ui text "Perf B" "$P-ui-grid.xml")
    if [ -n "$GB" ]; then tap $GB; else adb shell input keyevent 4; fi
    sleep 2
  fi
  if [ -n "$TABS" ] && [ -n "$GA" ] && [ -n "$GB" ]; then
    gfx_reset
    for i in $(seq 1 5); do
      tap $TABS; sleep 1.5; tap $GA; sleep 1.5
      tap $TABS; sleep 1.5; tap $GB; sleep 1.5
    done
    gfx_dump "$P-tabs.txt"
  else
    echo "tabs phase skipped (Tabs=$TABS A=$GA B=$GB)" | tee "$P-tabs-skipped.txt"
  fi

  # 5) Drag the floating toolbar to the top edge and back to the bottom, three times.
  page_up; sleep 1.5
  ui_dump > "$P-ui-bar.xml"
  local GRIP="" GRIPT=""
  GRIP=$(find_ui content-desc "Move the toolbar" "$P-ui-bar.xml")
  if [ -n "$GRIP" ]; then
    adb shell input swipe $GRIP $((W/2)) $((H*20/100)) 700; sleep 2.5
    ui_dump > "$P-ui-bartop.xml"
    GRIPT=$(find_ui content-desc "Move the toolbar" "$P-ui-bartop.xml")
    if [ -n "$GRIPT" ]; then
      adb shell input swipe $GRIPT $((W/2)) $((H*88/100)) 700; sleep 2.5
      gfx_reset
      for i in 1 2 3; do
        adb shell input swipe $GRIP $((W/2)) $((H*20/100)) 700; sleep 1.8
        adb shell input swipe $GRIPT $((W/2)) $((H*88/100)) 700; sleep 1.8
      done
      gfx_dump "$P-drag.txt"
    else echo "drag phase skipped (no grip at top)" | tee "$P-drag-skipped.txt"; fi
  else echo "drag phase skipped (no grip)" | tee "$P-drag-skipped.txt"; fi

  adb exec-out screencap -p > "$P-screen.png" 2>/dev/null || true
  adb logcat -d 2>/dev/null | grep -E 'BLZPERF|FATAL EXCEPTION|AndroidRuntime|BlazeStart' > "$P-logcat.txt" || true
  # Main-thread stalls the app itself reported (Choreographer "Skipped N frames", logged from 30 frames up), all phases.
  if [ -n "$APID" ]; then adb logcat -d --pid="$APID" 2>/dev/null | grep 'Choreographer' > "$P-choreo.txt" || true; fi
  adb shell am force-stop "$PKG"
}

for r in $(seq 1 "$ROUNDS"); do
  if [ $((r % 2)) -eq 1 ]; then run_one before "$BEFORE" "$r"; run_one after "$AFTER" "$r"
  else run_one after "$AFTER" "$r"; run_one before "$BEFORE" "$r"; fi
done

python3 "$HERE/parse.py" "$OUT" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
