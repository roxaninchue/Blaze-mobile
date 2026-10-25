# Kiln preview (private test build)

`kilnPreview` is a build type of `:app` for the owner's own phone. It is not a release and is not published on the site.

- applicationId `com.blaze.assistant` (same as release), versionName `0.45.26-kiln-preview`, versionCode **171**: it installs
  over 0.45.25 (vc170), and the official 0.45.26 (vc172) updates over it. vc170 is never reused.
- Release-like: R8, not debuggable, built unsigned by CI (`kiln-preview` job) and signed outside the repo with the release key.
- Bundles `:kiln` and the pinned F-Droid guest (Shopping List Calculator 2.3, GUEST_APPS.md).
- Hidden switch: Settings > About, tap "Version ..." 7 times -> "Kiln preview" switch (off by default). Turning it on opens the
  Kiln screen: "Install & open Shopping List Calculator" runs VERIFY -> INSTALL -> launch and lists every KilnDiag step;
  "Copy log" copies them.
- Release, debug and kilnTest compile `src/noPreview` (no-op hooks): no switch, no Kiln. CI checks that release/debug have
  no `:kiln` dependency and that `app/src/main` and the debug classes never name Kiln.
- Kiln guest processes (`:g0`) skip Blaze's own start-up (VariantHooks.skipAppInit) so they never touch the owner's data.
