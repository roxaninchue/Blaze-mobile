#!/bin/bash
# 0.48.0 RC3 Home intro check (local, not the release gate), run inside the emulator by .github/workflows/home-intro-check.yml:
# installs the debug app + test APK, screen-records while HomeIntroCheck plays the Home entry sequence twice (warm-up with
# screenshots every 0.5 s, then a measured run under the 50 ms main-thread monitor, limit 700 ms), pulls everything.
set -uo pipefail
OUT=intro-out; mkdir -p "$OUT"; PKG=com.blaze.assistant
adb install -r -g app/build/outputs/apk/debug/app-debug.apk > "$OUT/install.txt" 2>&1
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >> "$OUT/install.txt" 2>&1
adb logcat -c
( adb shell screenrecord --time-limit 90 --size 540x1200 /sdcard/intro.mp4 > "$OUT/screenrecord.txt" 2>&1 ) &
REC=$!
timeout 900 adb shell am instrument -w -r -e class $PKG.smoke.HomeIntroCheck $PKG.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/instrument.txt" 2>&1 || true
sleep 2; adb shell pkill -INT screenrecord > /dev/null 2>&1 || true; sleep 4; wait $REC 2>/dev/null || true
adb pull /sdcard/intro.mp4 "$OUT/intro-emulator.mp4" > /dev/null 2>&1 || echo "no screen recording" >> "$OUT/screenrecord.txt"
adb pull /sdcard/Android/data/$PKG/files/intro "$OUT/shots" > /dev/null 2>&1 || echo "no screenshots" >> "$OUT/screenrecord.txt"
adb logcat -d -v threadtime > "$OUT/logcat.txt"
{ echo "# Home intro check"; grep -E "OK \(|FAILURES|Tests run|shortMsg|stack=" "$OUT/instrument.txt" | head -20
  grep "BlazeIntroCheck" "$OUT/logcat.txt" | sed 's/^.*BlazeIntroCheck: /  /'
  echo "- files: $(ls "$OUT" | tr '\n' ' ') shots: $(ls "$OUT/shots" 2>/dev/null | wc -l)"; } > "$OUT/SUMMARY.md"
cat "$OUT/SUMMARY.md"
grep -q "^OK (1 test)" "$OUT/instrument.txt"
