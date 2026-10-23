#!/usr/bin/env bash
# app/emu-store: runs the EMULATOR Store checks on a booted emulator and collects evidence into out/api<N>/:
# results-api<N>.json (per step pass / fail / not-tested), screenshots, logcat, instrumentation output, pm list.
set -u
API="$1"
PKG=com.blaze.assistant
OUT="out/api$API"
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
mkdir -p "$OUT"
echo "EMULATOR evidence (not a real phone). API $API" > "$OUT/EVIDENCE.txt"

adb wait-for-device
adb shell getprop ro.build.version.sdk | tr -d '\r' >> "$OUT/EVIDENCE.txt"
adb shell getprop ro.product.cpu.abilist | tr -d '\r' >> "$OUT/EVIDENCE.txt"
adb logcat -c || true

adb install -r -t apk/app-debug.apk > "$OUT/install-app.txt" 2>&1; echo "exit=$?" >> "$OUT/install-app.txt"
adb install -r -t apk/app-debug-androidTest.apk > "$OUT/install-test.txt" 2>&1; echo "exit=$?" >> "$OUT/install-test.txt"
cat "$OUT/install-app.txt" "$OUT/install-test.txt"

# Start with Blaze NOT allowed to install apps (the pipeline must ask for it).
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES deny
adb shell appops get $PKG REQUEST_INSTALL_PACKAGES | tee "$OUT/appops-before.txt"
adb shell settings put global package_verifier_enable 0 || true
adb shell input keyevent 82 || true

timeout 600 adb shell am instrument -w -r -e class com.blaze.assistant.ui.StoreUiEmuTest $RUNNER 2>&1 | tee "$OUT/instrument-ui.txt"
timeout 1800 adb shell am instrument -w -r -e class com.blaze.assistant.appstore.StoreEmuTest $RUNNER 2>&1 | tee "$OUT/instrument-store.txt"

adb exec-out screencap -p > "$OUT/final-screen.png" || true
adb shell pm list packages 2>/dev/null | tr -d '\r' | grep -E 'emufixture|aegis' > "$OUT/pm-list.txt" || true
for f in $(adb shell run-as $PKG ls files/emu 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as $PKG cat "files/emu/$f" > "$OUT/$f"
done
[ -f "$OUT/results.json" ] && mv "$OUT/results.json" "$OUT/results-api$API.json"
adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
adb logcat -d -s EMU-STORE:I > "$OUT/logcat-emu-store.txt" 2>&1 || true
ls -l "$OUT"

python3 - "$OUT/results-api$API.json" "$API" <<'PY' | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
import json, sys, os
p, api = sys.argv[1], sys.argv[2]
print(f"### EMULATOR evidence: API {api} (x86_64 google_apis, not a real phone)\n")
if not os.path.exists(p):
    print("No results file: the instrumentation did not write any (see instrument-*.txt / logcat)."); sys.exit(0)
d = json.load(open(p))
print("| scenario | step | status | detail |\n|---|---|---|---|")
for r in d["results"]:
    det = r["detail"].replace("|", "/").replace("\n", " ")[:220]
    print(f"| {r['scenario']} | {r['step']} | **{r['status']}** | {det} |")
fails = [r for r in d["results"] if r["status"] == "fail"]
print(f"\nfail={len(fails)} pass={sum(r['status']=='pass' for r in d['results'])} not-tested={sum(r['status']=='not-tested' for r in d['results'])}")
PY

if [ ! -f "$OUT/results-api$API.json" ]; then echo "no results"; exit 1; fi
if grep -q '"status": "fail"' "$OUT/results-api$API.json"; then echo "some steps failed (see results)"; exit 1; fi
exit 0
