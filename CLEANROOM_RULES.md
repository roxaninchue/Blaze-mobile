# Blaze Kiln clean-room rules

Blaze Kiln (the Blaze Engine) runs simple Android apps inside Blaze. It is written from scratch under these rules.
Every change to `kiln/`, `kiln-testguest/` or `app/src/kilnTest/` must follow them.

## Allowed sources
- Android Open Source Project (AOSP) source and the public Android developer documentation.
- Code and libraries under Apache-2.0, MIT or BSD licences only. Each one is listed with its licence URL in `ENGINE_DEPS.md` before use.
- Our own tests and our own test guest app (`kiln-testguest`).

## Forbidden
- Any code, design notes or copied structure from VirtualApp, BlackBox (BlackDex/BlackBox family), DroidPlugin, VirtualAPK,
  VirtualXposed, Shelter, Parallel-Space-style or any other app-virtualization project.
- GPL, LGPL, AGPL, EPL (inside Kiln, test scope included), Anti-996, unlicensed or licence-unclear code.
- Paid servers or paid services of any kind.
- Reading forbidden projects' source "for reference". If a contributor has studied one, they do not write Kiln code for the same area.

## Trust and safety
- Only trusted open-source apps (F-Droid, reproducible or signer-pinned) run inside Kiln until isolation is complete.
- Every guest APK is checked before install: package parse, manifest validation, SHA-256 against the source index,
  and the APK signer against a pinned certificate.
- Each guest gets its own storage under Kiln's private directory; guests never share data with each other or with Blaze.
- Release and debug builds of Blaze never contain Kiln. Only the `kilnTest` build type does, until the owner approves shipping.

## Process
- Branch `feat/blaze-engine`; module `:kiln` (formerly `:blaze-engine`).
- Every new dependency: licence checked against its LICENSE file, recorded in `ENGINE_DEPS.md`.
- Heavy CI builds one at a time. No secrets in the repo.
- No public claim that Kiln runs an app until the owner's real-phone test passes.
