#!/usr/bin/env bash
# Blaze Kiln M1: EMULATOR evidence (not a real phone). Run by the `kiln-emulator` CI job inside a booted emulator.
#
# Installs the kilnTest APK, starts the Kiln harness with --ez kiln.autorun true (Kiln verifies the pinned F-Droid
# guest's signer + integrity, installs it into Kiln storage and launches it in the :g0 guest process), then collects
# logcat (KilnDiag lines), dumpsys, a UI dump, Kiln's own diag.log and a screenshot.
#
# Passes only when ALL of these hold:
#   1. KilnDiag VERIFY ok        (pinned signer + APK integrity accepted before install)
#   2. KilnDiag GUEST_SWAP ok    (the slot launch was swapped for the guest's own activity)
#   3. KilnDiag GUEST_ACTIVITY ok (an instance of the guest's MainActivity is resumed and shown in the guest process)
#   4. Android's top resumed activity is a Kiln slot of the kilnTest app (the guest runs INSIDE Kiln, not installed)
#   5. the pid reported by GUEST_ACTIVITY is the kilnTest app's :g0 process
#   6. the guest package is NOT installed on the device (pm path fails)
#
# usage: emulator-check.sh <kilnTest.apk> [out-dir]
# Kiln preview build (com.blaze.assistant, Kiln screen behind the hidden switch):
#   KILN_HOST=com.blaze.assistant KILN_ACTIVITY=com.blaze.assistant.kiln.KilnPreviewActivity \
#   KILN_EXTRA="--ez kiln.enable true" emulator-check.sh <kilnPreview.apk> [out-dir]
set -u
APK=${1:?kilnTest apk}
OUT=${2:-emu}
mkdir -p "$OUT"
HOST=${KILN_HOST:-com.blaze.assistant.kilntest}
GUEST=io.github.buildsbyben.shoppinglistcalc
HARNESS=${KILN_ACTIVITY:-com.blaze.assistant.kiln.KilnHarnessActivity}
EXTRA=${KILN_EXTRA:-}

log() { echo "[kiln-emu] $*" | tee -a "$OUT/run.txt"; }
diaglines() { adb logcat -d -v brief -s KilnDiag:I 2>/dev/null; }
has_step() { diaglines | grep -E "STEP[[:space:]]+$1[[:space:]]+$2[[:space:]]" >/dev/null; }

adb wait-for-device
{
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "release=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
  echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "fingerprint=$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
} > "$OUT/device.txt"
cat "$OUT/device.txt"

adb install -r -t "$APK" 2>&1 | tee "$OUT/install.txt"
adb shell pm path "$GUEST" > "$OUT/guest-pm-path-before.txt" 2>&1 || true
adb logcat -c
# Wake + unlock so the launch is really in the foreground.
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell am start -W -n "$HOST/$HARNESS" $EXTRA --ez kiln.autorun true 2>&1 | tee "$OUT/am-start.txt"

verdict_activity=""
for i in $(seq 1 120); do
  if has_step GUEST_ACTIVITY 1; then verdict_activity=ok; break; fi
  if has_step GUEST_ACTIVITY 0; then verdict_activity=fail; break; fi
  if has_step VERIFY 0; then verdict_activity=verify-rejected; break; fi
  sleep 1
done
log "waited ${i}s for GUEST_ACTIVITY: ${verdict_activity:-timeout}"
sleep 3

adb exec-out screencap -p > "$OUT/screenshot.png" || true
adb shell dumpsys activity activities > "$OUT/dumpsys-activities.txt" 2>&1 || true
adb shell dumpsys activity top > "$OUT/dumpsys-activity-top.txt" 2>&1 || true
adb shell dumpsys window windows > "$OUT/dumpsys-window.txt" 2>&1 || true
adb shell uiautomator dump /sdcard/kiln-ui.xml >/dev/null 2>&1 && adb pull /sdcard/kiln-ui.xml "$OUT/ui.xml" >/dev/null 2>&1 || true
adb shell ps -A -o PID,USER,NAME > "$OUT/ps.txt" 2>&1 || true
adb shell pm path "$GUEST" > "$OUT/guest-pm-path-after.txt" 2>&1 || true
adb shell run-as "$HOST" cat files/kiln/diag.log > "$OUT/kiln-diag.log" 2>/dev/null || true
diaglines > "$OUT/kilndiag-logcat.txt"
adb logcat -d -v threadtime -s KilnDiag:V BlazeKiln:V AndroidRuntime:E ActivityTaskManager:I > "$OUT/kiln-logcat.txt" 2>&1 || true
adb logcat -d -v threadtime > "$OUT/logcat-full.txt" 2>&1 || true

# ---- judge
fail=0
check() { if [ "$2" = 1 ]; then log "PASS $1"; else log "FAIL $1"; fail=1; fi; }
has_step VERIFY 1 && v=1 || v=0; check "1 VERIFY ok (pinned signer + integrity)" $v
has_step GUEST_SWAP 1 && v=1 || v=0; check "2 GUEST_SWAP ok" $v
has_step GUEST_ACTIVITY 1 && v=1 || v=0; check "3 GUEST_ACTIVITY ok (guest MainActivity resumed + shown)" $v

top=$(grep -m1 -E "topResumedActivity|mResumedActivity|ResumedActivity:" "$OUT/dumpsys-activities.txt" | tr -d '\r')
log "top resumed: $top"
echo "$top" | grep -q "$HOST/com.blaze.kiln.slot.A0_" && v=1 || v=0
check "4 top resumed activity is a Kiln slot of $HOST" $v

gpid=$(diaglines | grep -E "STEP[[:space:]]+GUEST_ACTIVITY[[:space:]]+1[[:space:]]" | tail -1 | grep -oE "pid [0-9]+" | grep -oE "[0-9]+")
pname=$(awk -v p="$gpid" '$1==p {print $3}' "$OUT/ps.txt" | tr -d '\r')
log "guest activity pid ${gpid:-?} = process ${pname:-?}"
[ -n "$gpid" ] && [ "$pname" = "$HOST:g0" ] && v=1 || v=0
check "5 guest activity runs in $HOST:g0" $v

grep -q "^package:" "$OUT/guest-pm-path-after.txt" && v=0 || v=1
check "6 $GUEST is not installed on the device (it runs only inside Kiln)" $v

if grep -q "package=\"$GUEST\"" "$OUT/ui.xml" 2>/dev/null; then log "INFO ui.xml shows views of package $GUEST"; else log "INFO ui.xml has no nodes for $GUEST"; fi
grep -E "STEP[[:space:]]+GUEST_STORAGE" "$OUT/kilndiag-logcat.txt" | tail -1 | sed 's/^/[kiln-emu] INFO /' | tee -a "$OUT/run.txt"
grep -E "CRASH|REFUSAL" "$OUT/kilndiag-logcat.txt" | sed 's/^/[kiln-emu] INFO /' | tee -a "$OUT/run.txt" || true

if [ $fail = 0 ]; then log "RESULT: PASS (EMULATOR evidence, not a real phone)"; else log "RESULT: FAIL"; fi
{
  echo "### Kiln emulator check: $HOST (EMULATOR evidence, not a real phone)"
  echo '```'; cat "$OUT/device.txt"; grep -E "PASS|FAIL|INFO|RESULT|top resumed|pid" "$OUT/run.txt"; echo '```'
  echo "KilnDiag lines:"; echo '```'; cut -c1-400 "$OUT/kilndiag-logcat.txt" | tail -40; echo '```'
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
exit $fail
