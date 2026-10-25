# Blaze Kiln dependencies (allowed: Apache-2.0, MIT, BSD only)

Checked 2026-10-23 against each project's LICENSE file (GitHub licence API + the file text).

## In use / planned
| Library | Coordinates | Licence | Licence URL | Used for | Milestone |
|---|---|---|---|---|---|
| TestNG | org.testng:testng:7.10.2 (test only, never shipped) | Apache-2.0 | https://github.com/testng-team/testng/blob/master/LICENSE.txt | JVM unit tests | M1 |
| (TestNG transitive) JCommander 1.82 | com.beust:jcommander | Apache-2.0 | https://github.com/cbeust/jcommander/blob/master/license.txt | test only | M1 |
| (TestNG transitive) SLF4J API 1.7.36 | org.slf4j:slf4j-api | MIT | https://www.slf4j.org/license.html | test only | M1 |
| (TestNG transitive, runtime) jQuery webjar 3.7.1 | org.webjars:jquery | MIT | https://github.com/jquery/jquery/blob/main/LICENSE.txt | TestNG HTML report, test only | M1 |
| AndroidHiddenApiBypass | org.lsposed.hiddenapibypass:hiddenapibypass:6.1 | Apache-2.0 | https://github.com/LSPosed/AndroidHiddenApiBypass/blob/main/LICENSE | non-SDK access on API 28+ (only through `runtime/HiddenApi`) | M1 runtime |
| ByteHook (bhook) | com.bytedance:bytehook | MIT | https://github.com/bytedance/bhook/blob/main/LICENSE | PLT hooks for native path redirection | M3 (planned) |

Test runner: the app's own tests use JUnit 4 (EPL-1.0). Kiln does NOT use JUnit, so that every Kiln dependency,
test scope included, is Apache-2.0/MIT/BSD. TestNG's optional deps (Guice, SnakeYAML) are not pulled.

## Guest apps and test tooling (not linked into Kiln)
| Item | Licence | URL | Used for |
|---|---|---|---|
| Shopping List Calculator 2.3 (F-Droid APK) | MIT | https://github.com/buildsbyben/shopping-list-calc/blob/main/LICENSE | M1 guest, kilnTest assets + unit-test fixture only (GUEST_APPS.md) |
| Bouncy 0.0.1 (F-Droid APK) | Apache-2.0 | https://github.com/ebraminio/bouncy/blob/main/LICENSE | v3 signature unit-test fixture only |
| Python `cryptography` | Apache-2.0 OR BSD-3-Clause | https://github.com/pyca/cryptography/blob/main/LICENSE | `tools/kiln/make_resigned_fixture.py` (offline fixture generator, not in any build) |
| reactivecircus/android-emulator-runner | Apache-2.0 | https://github.com/ReactiveCircus/android-emulator-runner/blob/main/LICENSE | CI emulator job only |

APK signature verification (`kiln/.../apk/ApkSignature.kt`) uses only `java.security` and is written from the public
APK Signature Scheme v2/v3 specification (source.android.com/docs/security/features/apksigning); no apksig code.

## Checked and REJECTED (do not add)
| Library | Licence found | Why |
|---|---|---|
| Pine (canyie/pine) | Anti-996 | not Apache/MIT/BSD |
| SandHook (asLody/SandHook) | Anti-996 | not allowed |
| YAHFA | GPL-3.0 | copyleft |
| JUnit 4 (for Kiln tests) | EPL-1.0 | not on the allowed list; TestNG used instead |
| Any VirtualApp/BlackBox-family/DroidPlugin/VirtualAPK/Shelter/VirtualXposed code | GPL/LGPL/unlicensed | forbidden by CLEANROOM_RULES.md |

## Checked, allowed but not planned
| Library | Licence | URL | Note |
|---|---|---|---|
| ShadowHook (bytedance/android-inline-hook) | MIT | https://github.com/bytedance/android-inline-hook/blob/main/LICENSE | inline hooks; only if PLT hooks are not enough |
| Dobby | Apache-2.0 | https://github.com/jmpews/Dobby/blob/master/LICENSE | alternative inline hook |
| FreeReflection | MIT | https://github.com/tiann/FreeReflection/blob/master/LICENSE | alternative to the bypass library |
| epic (tiann/epic) | Apache-2.0 (LICENSE file) | https://github.com/tiann/epic/blob/master/LICENSE | ART method hooking; GitHub flags NOASSERTION, re-check before any use |
| xHook (iqiyi) | MIT for most files, "some other source code" under other terms | https://github.com/iqiyi/xHook/blob/master/LICENSE | mixed; prefer bhook |
