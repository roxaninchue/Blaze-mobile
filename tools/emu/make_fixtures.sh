#!/usr/bin/env bash
# app/emu-store: builds the local fixture APKs for the EMULATOR Store checks (StoreEmuTest) and writes
# app/src/androidTest/assets/emu/{*.apk,fixtures.json}. Two throwaway keys (A = "developer", B = "impostor") are made
# here with keytool on every run; they are test-only, never committed, never used for anything else.
#
#   one_v1_A.apk      com.blaze.emufixture.one  vc1  key A   -> the good install (checksum + key A pinned)
#   one_v2_B.apk      com.blaze.emufixture.one  vc2  key B   -> update signed by a different key (installed-signer check)
#   two_v1_A.apk      com.blaze.emufixture.two  vc1  key A   -> reference for the tampered copy
#   two_v1_A_tampered.apk  two_v1_A with bytes flipped mid-file -> bad checksum / broken signature
#   two_v1_B.apk      com.blaze.emufixture.two  vc1  key B   -> wrong signer (catalog pins key A)
set -euo pipefail
BT="${ANDROID_HOME}/build-tools/34.0.0"
JAR="${ANDROID_HOME}/platforms/android-34/android.jar"
OUT="app/src/androidTest/assets/emu"
W="$(mktemp -d)"
mkdir -p "$OUT"
rm -f "$OUT"/*.apk "$OUT"/fixtures.json

for k in A B; do
  keytool -genkeypair -keystore "$W/$k.jks" -storepass android -keypass android -alias emu$k -keyalg RSA -keysize 2048 \
    -validity 3650 -dname "CN=Blaze EMU fixture key $k" -noprompt >/dev/null 2>&1
done

mk() { # pkg vc key out
  local pkg=$1 vc=$2 key=$3 out=$4 d="$W/$pkg-$vc"
  mkdir -p "$d"
  cat > "$d/AndroidManifest.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg">
  <application android:label="EMU fixture ${pkg##*.} v$vc" android:hasCode="false">
    <activity android:name="$pkg.Main" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>
XML
  "$BT/aapt2" link --manifest "$d/AndroidManifest.xml" -I "$JAR" -o "$d/u.apk" \
    --min-sdk-version 21 --target-sdk-version 34 --version-code "$vc" --version-name "1.$vc"
  "$BT/zipalign" -f -p 4 "$d/u.apk" "$d/a.apk"
  "$BT/apksigner" sign --ks "$W/$key.jks" --ks-pass pass:android --key-pass pass:android --ks-key-alias emu$key \
    --v1-signing-enabled true --v2-signing-enabled true --out "$OUT/$out" "$d/a.apk"
}

mk com.blaze.emufixture.one 1 A one_v1_A.apk
mk com.blaze.emufixture.one 2 B one_v2_B.apk
mk com.blaze.emufixture.two 1 A two_v1_A.apk
mk com.blaze.emufixture.two 1 B two_v1_B.apk

# Tamper: flip 16 bytes in the middle of the file (inside the zip entries), keep the length.
python3 - "$OUT/two_v1_A.apk" "$OUT/two_v1_A_tampered.apk" <<'PY'
import sys
b = bytearray(open(sys.argv[1], 'rb').read())
m = len(b) // 2
for i in range(m, m + 16): b[i] ^= 0xFF
open(sys.argv[2], 'wb').write(b)
PY

cert() { "$BT/apksigner" verify --print-certs "$1" | grep -m1 'certificate SHA-256 digest' | awk '{print $NF}'; }
sha() { sha256sum "$1" | awk '{print $1}'; }
"$BT/apksigner" verify "$OUT/two_v1_A_tampered.apk" >/dev/null 2>&1 && TAMPER_VERIFIES=true || TAMPER_VERIFIES=false

cat > "$OUT/fixtures.json" <<JSON
{
  "certA": "$(cert "$OUT/one_v1_A.apk")",
  "certB": "$(cert "$OUT/two_v1_B.apk")",
  "sha": {
    "one_v1_A.apk": "$(sha "$OUT/one_v1_A.apk")",
    "one_v2_B.apk": "$(sha "$OUT/one_v2_B.apk")",
    "two_v1_A.apk": "$(sha "$OUT/two_v1_A.apk")",
    "two_v1_A_tampered.apk": "$(sha "$OUT/two_v1_A_tampered.apk")",
    "two_v1_B.apk": "$(sha "$OUT/two_v1_B.apk")"
  },
  "tampered_still_verifies_with_apksigner": $TAMPER_VERIFIES
}
JSON
cat "$OUT/fixtures.json"
ls -l "$OUT"
rm -rf "$W"
