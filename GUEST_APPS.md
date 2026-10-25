# Blaze Kiln trusted guest apps

Only apps listed here run inside Kiln until isolation is complete (CLEANROOM_RULES.md). Each is pinned in
`kiln/src/main/java/com/blaze/kiln/apk/ApkSignature.kt` (`TrustedGuests.PINS`): before install Kiln verifies the APK
Signature Scheme v2/v3 signature, the APK content digest, the signer certificate SHA-256 and the exact APK SHA-256.
Guest APKs are bundled into the `kilnTest` build type only (`app/build.gradle.kts`, `copyKilnGuest`); release and debug
builds never contain them or `:kiln`.

## M1 guest

| Field | Value |
|---|---|
| App | Shopping List Calculator |
| Package | `io.github.buildsbyben.shoppinglistcalc` |
| Version | 2.3 (versionCode 21), minSdk 23, targetSdk 35 |
| Licence | MIT (https://github.com/buildsbyben/shopping-list-calc/blob/main/LICENSE, copy in `kiln-guests/fdroid/LICENSE-shoppinglistcalc.txt`, also bundled next to the APK) |
| Source | https://github.com/buildsbyben/shopping-list-calc |
| F-Droid page | https://f-droid.org/packages/io.github.buildsbyben.shoppinglistcalc/ |
| APK (official, from f-droid.org) | https://f-droid.org/repo/io.github.buildsbyben.shoppinglistcalc_21.apk |
| APK size | 63,079 bytes |
| APK SHA-256 | `d5a1e434bd54270dadee55217758568c05c3f89f93693d9a9c2e8a065b9690ee` (equals F-Droid index-v2 `file.sha256`) |
| Signer cert SHA-256 | `01d815e4f55f81f61ace0b29164d957fefc5293cf862c1637f2f215c751cedd3` (APK Signature Scheme v2, RSA PKCS#1 SHA-512; equals F-Droid index-v2 `manifest.signer`; read with `work/tools/apksig_certs.py`) |
| Signing | Built and signed by the original developer; F-Droid verifies it as reproducible from the source tarball |
| Permissions | none (no INTERNET) |
| Components | 2 activities (`MainActivity` launcher, `SettingsActivity`); no services, receivers or providers; no native code; no AndroidX (plain `android.app.Activity`) |
| Kiln verdict | needs M2 on paper (more than one activity); the launcher activity is the M1 target |
| Bundled at | `kiln-guests/fdroid/io.github.buildsbyben.shoppinglistcalc_21.apk` -> kilnTest `assets/kiln/guests/` |
| Checked | 2026-10-25 |

Why this app: a calculator-style app with no permissions at all, MIT licence, 63 KB, plain framework Views and no
AndroidX/Play Services, signed by its developer with a reproducible F-Droid build, so the pinned signer is stable.

Considered: Bouncy (`io.github.ebraminio.bouncy`, Apache-2.0, 21 KB, one activity) is used only as a v2+v3 signature test
fixture; it is a toy rather than a calculator/notes/counter app.

## Updating a pin
1. Download the new APK from f-droid.org, check its SHA-256 against index-v2 and its signer with `apksig_certs.py`.
2. Update `TrustedGuests.PINS`, this table, `kiln-guests/fdroid/` and the hash checks in `ApkSignatureTest` and the
   `engine` CI job. A signer change needs the owner's approval.
