#!/bin/bash
# 0.46.6 low-RAM release gate, run inside the emulator by .github/workflows/lowram-smoke.yml.
# usage: run-smoke.sh <release.apk (CI-signed, install only)> <app-debug.apk> <app-debug-androidTest.apk>
# PASS needs: release cold start with zero crash / ANR and no main-thread stall over 700 ms (Choreographer "Skipped N frames",
# N >= 42), and the instrumented LowRamSmokeTest green (browse 200 x concurrent loads, details, 10 s video, music, 30 tool-chat
# turns with DSML, main thread sampled every 50 ms) with zero crash / ANR in logcat.
set -uo pipefail
REL="$1"; DBG="$2"; TST="$3"; PKG=com.blaze.assistant; OUT=smoke-out; mkdir -p "$OUT"
FAIL=(); note() { echo "$*" | tee -a "$OUT/SUMMARY.md"; }
echo "# Blaze low-RAM smoke gate" > "$OUT/SUMMARY.md"
note "- emulator: $(adb shell getprop ro.product.model | tr -d '\r') API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), abis $(adb shell getprop ro.product.cpu.abilist | tr -d '\r'), RAM $(adb shell cat /proc/meminfo | head -1 | tr -s ' ' | cut -d' ' -f2) kB, heap $(adb shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r')"

crashes() {   # $1 = logcat file; prints crash / ANR lines of our package
  grep -E "FATAL EXCEPTION|Fatal signal|ANR in $PKG|Process: $PKG|am_crash|am_anr|Force finishing activity $PKG" "$1" | grep -E "$PKG|Fatal signal" || true
}
stall_frames() {   # $1 = logcat (threadtime), $2 = pid: the largest "Skipped N frames" of that pid
  awk -v p="$2" '$3==p && /Choreographer/ && /Skipped [0-9]+ frames/ { for (i=1;i<=NF;i++) if ($i=="Skipped") { n=$(i+1)+0; if (n>m) m=n } } END { print m+0 }' "$1"
}

# ---- 1. release cold start
adb logcat -c; adb logcat -b crash -c 2>/dev/null || true
if ! adb install -r -g "$REL" > "$OUT/install-release.txt" 2>&1; then FAIL+=("release install failed"); cat "$OUT/install-release.txt"; fi
adb shell am start -W -n $PKG/.MainActivity > "$OUT/start-first.txt" 2>&1; sleep 25          # first start (dexopt, setup)
adb shell am force-stop $PKG; sleep 3; adb logcat -c
adb shell am start -W -n $PKG/.MainActivity > "$OUT/start-cold.txt" 2>&1
PID=$(adb shell pidof $PKG | tr -d '\r'); sleep 20
TT=$(grep -oP 'TotalTime: \K[0-9]+' "$OUT/start-cold.txt" || echo "?")
adb logcat -d -v threadtime > "$OUT/logcat-release.txt"; adb logcat -d -b crash > "$OUT/logcat-release-crash.txt" 2>/dev/null || true
N=$(stall_frames "$OUT/logcat-release.txt" "$PID"); MS=$(( N * 1000 / 60 ))
C=$(crashes "$OUT/logcat-release.txt"; crashes "$OUT/logcat-release-crash.txt")
STILL=$(adb shell pidof $PKG | tr -d '\r')
note "- release cold start: TotalTime ${TT} ms, worst main stall ~${MS} ms (${N} frames skipped), process alive after 20 s: $([ -n "$STILL" ] && echo yes || echo NO)"
[ -n "$C" ] && { FAIL+=("crash/ANR during release cold start"); echo "$C" | head -20 | tee -a "$OUT/SUMMARY.md"; }
[ -z "$STILL" ] && FAIL+=("release process died after cold start")
[ "$N" -ge 42 ] && FAIL+=("release cold start main stall ~${MS} ms > 700 ms")
grep -E "$PID .*Choreographer.*Skipped" "$OUT/logcat-release.txt" | head -5 >> "$OUT/SUMMARY.md" || true
adb uninstall $PKG > /dev/null 2>&1 || true

# ---- 2. instrumented scenarios (debug build of the same commit)
adb logcat -c
adb install -r -g "$DBG" > "$OUT/install-debug.txt" 2>&1 || FAIL+=("debug install failed")
adb install -r -g "$TST" > "$OUT/install-test.txt" 2>&1 || FAIL+=("test install failed")
timeout 1500 adb shell am instrument -w -r -e class com.blaze.assistant.smoke.LowRamSmokeTest $PKG.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/instrument.txt" 2>&1
adb logcat -d -v threadtime > "$OUT/logcat-smoke.txt"; adb logcat -d -b crash > "$OUT/logcat-smoke-crash.txt" 2>/dev/null || true
grep -E "INSTRUMENTATION_STATUS: (test|stack)=|INSTRUMENTATION_STATUS_CODE: -2|OK \(|FAILURES|Tests run|Process crashed|INSTRUMENTATION_RESULT|shortMsg" "$OUT/instrument.txt" | grep -v "INSTRUMENTATION_STATUS: test=" | head -40 >> "$OUT/SUMMARY.md"
grep "BlazeSmoke" "$OUT/logcat-smoke.txt" | sed 's/^.*BlazeSmoke: /  /' >> "$OUT/SUMMARY.md"
grep -q "^OK (6 tests)" "$OUT/instrument.txt" || FAIL+=("instrumented smoke test not green")
C=$(crashes "$OUT/logcat-smoke.txt"; crashes "$OUT/logcat-smoke-crash.txt")
[ -n "$C" ] && { FAIL+=("crash/ANR during instrumented smoke"); echo "$C" | head -20 | tee -a "$OUT/SUMMARY.md"; }

if [ ${#FAIL[@]} -eq 0 ]; then note "## RESULT: PASS"; exit 0; fi
note "## RESULT: FAIL"; for f in "${FAIL[@]}"; do note "- $f"; done; exit 1
