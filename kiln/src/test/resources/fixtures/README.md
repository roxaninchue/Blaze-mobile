# Kiln signature test fixtures (test scope only, never shipped)

| File | What | Source | Licence |
|---|---|---|---|
| `shoppinglistcalc-wrong-signer.apk` | Shopping List Calculator 2.3 (vc 21) from F-Droid, re-signed (APK Signature Scheme v2, ECDSA P-256) with a throwaway key by `tools/kiln/make_resigned_fixture.py`. ZIP content byte-identical; signer cert SHA-256 `7f574ac8c5e16e52ea96262ba0a1e3150d2bf3fe9fea883b989b5ee04005c77e`. The private key was never stored. | https://f-droid.org/repo/io.github.buildsbyben.shoppinglistcalc_21.apk | MIT (`kiln-guests/fdroid/LICENSE-shoppinglistcalc.txt`) |
| `bouncy-v2v3.apk` | Bouncy 0.0.1 (vc 1), unmodified, signed v2 + v3 by its developer (signer `4e015e12bc0e1ae6806edf57befab90e2dcca477dd909665c87d4f29d9be29b0`, APK SHA-256 `a509db2afda544f6da9620eb473319b0a034c6ffc8b6536e2a8a7bcc0f407f54`). Used to cover the v3 path. | https://f-droid.org/repo/io.github.ebraminio.bouncy_1.apk, source https://github.com/ebraminio/bouncy | Apache-2.0 (https://github.com/ebraminio/bouncy/blob/main/LICENSE) |

Tampered and unsigned copies are generated inside `ApkSignatureTest` from the bundled F-Droid guest.
