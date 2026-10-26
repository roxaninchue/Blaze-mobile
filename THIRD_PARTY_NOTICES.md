# Third-party notices

Copied from Blaze Desktop. On the phone the router is built from blazerouter/ and shipped as libblazerouter.so.

## OmniRoute

BlazeRouter (`internal/router`, its dashboard in `internal/router/web` and the model catalog `internal/router/omniroute_catalog.json`) is a Go port of the routing core of OmniRoute: the auto/* presets and their category:tier ranking, combos and strategies, last-known-good preference, chaos fan-out and the provider catalog.

Source: https://github.com/diegosouzapw/OmniRoute

```
MIT License

Copyright (c) 2026 diegosouzapw

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## three.js

The Design view bundles three.js r170 (`app/src/main/assets/design/three`: `three.module.min.js`, `OrbitControls.js`, `GLTFExporter.js`) so 3D designs open offline, and since v0.23.3 also three.js r162 (`app/src/main/assets/design/three-r162`: `three.module.min.js`, `OrbitControls.js`, `GLTFExporter.js`, `TextureUtils.js`, from the npm package three@0.162.0), the last release that still runs on WebGL 1, for phones whose WebView has WebGL 2 turned off. Both are MIT-licensed by the three.js authors (each folder carries its LICENSE; r162's copyright line reads 2010-2024 three.js authors too).

Source: https://github.com/mrdoob/three.js

```
The MIT License

Copyright © 2010-2024 three.js authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

## Terminal and Linux tools bundled in the APK (app/src/main/jniLibs)
Shipped unchanged (BusyBox: one patched branch, see tools/busybox/README.md) as `lib*.so` executables. Each keeps its own
licence; the corresponding source is at the links below.

| File | Project | Version | Licence | Source |
|---|---|---|---|---|
| libproot.so, libproot_loader.so, libproot_loader32.so | PRoot, Termux's fork | 5.1.107.96 (since v0.30.2) | GPL-2.0-or-later | https://github.com/termux/proot/tree/v5.1.107.96 |
| libtalloc.so | talloc (Samba) | 2.5.0 (Termux build) | LGPL-3.0-or-later | https://www.samba.org/ftp/talloc/ |
| libandroid-shmem.so | libandroid-shmem (Termux) | 0.7 | BSD-3-Clause, (c) 2013 Sergii Pylypenko, (c) 2017 Fredrik Fornwall | https://github.com/termux/libandroid-shmem |
| libbusybox.so | BusyBox | 1.36.1 (arm64), 1.31.0 (armv7) | GPL-2.0-only | https://busybox.net/downloads/ |

PRoot, talloc and libandroid-shmem are the files of Termux's packages (`tools/proot/README.md` names each package and
`tools/proot/fetch_termux_proot.py` re-downloads them). libandroid-shmem's licence requires this notice: Redistribution and
use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
redistributions of source code must retain the above copyright notice, this list of conditions and the following
disclaimer; redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
following disclaimer in the documentation and/or other materials provided with the distribution; neither the name of the
copyright holders nor the names of its contributors may be used to endorse or promote products derived from this software
without specific prior written permission. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED; IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY
DAMAGES ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE.

## Linux desktop (v0.25.1), downloaded on first Start, not bundled
The Desktop view installs these free and open-source packages from Alpine Linux's official repositories into Blaze's
Alpine Linux on the phone, only when the user taps Start (or approves the agent's `desktop` start). None of them is
shipped inside the APK. Each keeps its own licence (as packaged by Alpine; `apk info -L <pkg>` lists its files):

| Package | Project | Licence |
|---|---|---|
| xfce4, xfce4-session, xfwm4, xfdesktop, xfce4-panel, xfce4-terminal, thunar, mousepad | Xfce | GPL-2.0-or-later / LGPL-2.0-or-later |
| tigervnc (Xvnc, vncpasswd) | TigerVNC | GPL-2.0-or-later |
| xdotool | xdotool | BSD-3-Clause |
| imagemagick (import) | ImageMagick | ImageMagick License (Apache-2.0 style) |
| dillo | Dillo | GPL-3.0-or-later |
| dbus, dbus-x11 | freedesktop.org D-Bus | AFL-2.1 OR GPL-2.0-or-later |
| font-dejavu | DejaVu Fonts | Bitstream Vera Fonts License (free) |
| adwaita-icon-theme | GNOME | LGPL-3.0-or-later OR CC-BY-SA-3.0 |

Blaze's VNC viewer (`desktop/Rfb.kt`) is Blaze's own code, written from the public RFB protocol specification (RFC 6143).

## OpenCode (v0.45.26 backup brain), downloaded in the background, not bundled
The on-device backup brain (`agent/BackupBrain.kt`) downloads OpenCode's official linux-arm64 musl release
(`opencode-linux-arm64-musl.tar.gz`, v1.19.4, SHA-256 pinned) from https://github.com/anomalyco/opencode/releases into
Blaze's Alpine Linux and runs it headless (`opencode serve` on 127.0.0.1). It is not shipped inside the APK.
Licence: MIT, Copyright (c) 2025 opencode (https://github.com/anomalyco/opencode/blob/dev/LICENSE). Its hosted free
models are OpenCode's service, used under OpenCode's own terms (https://opencode.ai).

The Linux "ready" step (v0.45.26) installs python3, pip, curl, wget, git, ca-certificates and nano from each Linux's own
official repositories (each under its own licence, as packaged by that distribution), plus libstdc++/libgcc on Alpine for
the backup brain.

## Mermaid (v0.26.3, bundled for offline diagrams)

Chat replies draw Mermaid diagrams on the phone, with no network, from the bundled mermaid 11.17.2
(`app/src/main/assets/diagram/mermaid.min.js`, the npm package's IIFE build, with its LICENSE beside it).
Mermaid is MIT-licensed by Knut Sveidqvist and contributors.

Source: https://github.com/mermaid-js/mermaid

```
The MIT License (MIT)

Copyright (c) 2014 - 2022 Knut Sveidqvist

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

mermaid.min.js also contains these libraries, each under its own licence:

| Library | Licence |
|---|---|
| dagre-d3-es, cytoscape (+ cose-bilkent, fcose), katex, khroma, marked, roughjs, dayjs, stylis, uuid, langium, lodash-es, ts-dedent, @braintree/sanitize-url, @iconify/utils | MIT |
| d3 and its d3-* modules | ISC |
| d3-sankey | BSD-3-Clause |
| chevrotain | Apache-2.0 |
| DOMPurify | MPL-2.0 OR Apache-2.0 |


## sherpa-onnx (My voice, v0.28.0)

`app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt` is vendored from sherpa-onnx v1.13.8 (Copyright (c) 2023 Xiaomi Corporation), Apache License 2.0, https://github.com/k2-fsa/sherpa-onnx. The native `libsherpa-onnx-jni.so` it talks to is not in the APK; it is downloaded on demand from this repository's `voice-runtime-1.13.8` release, unchanged from the official sherpa-onnx AAR. It statically links ONNX Runtime (MIT, Copyright (c) Microsoft Corporation).

The PocketTTS voice model is downloaded from the sherpa-onnx `tts-models` release (`sherpa-onnx-pocket-tts-int8-2026-01-26`), an ONNX export (https://github.com/KevinAHM/pocket-tts-onnx-export) of Kyutai's Pocket TTS (https://github.com/kyutai-labs/pocket-tts). Its LICENSE file is Creative Commons Attribution 4.0; its README notes it is for non-commercial use.

## Apache Commons Compress (v0.28.0)

`org.apache.commons:commons-compress:1.21` (bzip2 reader for the voice model archive), Apache License 2.0, Copyright The Apache Software Foundation.

## AndroidX Media3 (Blaze Music / Video, v0.30.1)

ExoPlayer, MediaSession and PlayerView (`androidx.media3:media3-exoplayer`, `media3-session`, `media3-ui` 1.4.1), Apache License 2.0, Copyright The Android Open Source Project. Source: https://github.com/androidx/media

## Store sources and data (v0.30.1)

No third-party code is bundled for these; Blaze calls their public APIs. Content keeps its own licence, shown on every item.

- **Jamendo** (https://developer.jamendo.com/v3.0): Creative Commons music. Needs a client_id from https://devportal.jamendo.com (Jamendo API terms: non-commercial use of the free tier; attribution). No key is shipped.
- **Internet Archive** (https://archive.org/advancedsearch.php, https://archive.org/metadata): only items whose licence url is Creative Commons or public domain (audio), the Feature Films collection (public-domain films) and the Blender open movies.
- **Blender open movies** (Big Buck Bunny, Elephants Dream, Sintel, Tears of Steel, Cosmos Laundromat, Spring, Agent 327, Sprite Fright, Caminandes): © Blender Foundation / Blender Studio, CC BY (Agent 327: CC BY-ND; Spring: public domain mark) as stated on each archive.org item. https://studio.blender.org/films/
- **LRCLIB** (https://lrclib.net): lyrics, looked up on demand, no key.

## YouTube plug-in (v0.30.1, optional module `youtube/`)

Blaze's own code (InnerTube JSON client); no third-party extractor. **NewPipeExtractor was not used:** it is GPL-3.0. Blaze-agent is licensed PolyForm Noncommercial 1.0.0 (LICENSE.md), which adds a non-commercial restriction GPL-3.0 forbids, and the APK bundles proprietary Google Play services / ML Kit libraries; a GPL-3.0 dependency would require the whole APK to be GPL-3.0. If the YouTube module moves to a private repo, it still must not pull in GPL code while it ships inside Blaze. Downloading from YouTube may breach YouTube's Terms of Service; the module is isolated so builds can leave it out (delete `youtube/`). Its source now lives in the private repo blazeinchue/Blaze-mobile and is only checked out into `youtube/` at build time; this public repo no longer contains it.

## Movies (v0.46.2)

- **Vega** (vega-app), Apache License 2.0. Blaze's Movies follows the provider interface and module layout (manifest.json + dist/<id>/{posts,meta,stream,catalog,episodes}.js) and the sandbox idea of the Vega app, as of commit 9ccc0c8 (the LICENSE at that commit is Apache-2.0; there is no NOTICE file). No Vega code after that commit is used, and Blaze bundles, suggests and links no third-party provider repo: users add their own sources. Source: https://github.com/Zenda-Cross/vega-app
- **quickjs-kt** (io.github.dokar3:quickjs-kt 1.0.0-alpha13), Apache License 2.0, Copyright dokar3. Runs user-added provider modules in a QuickJS sandbox (QuickJS itself: MIT, Copyright Fabrice Bellard and Charlie Gordon). Source: https://github.com/dokar3/quickjs-kt
- **Jsoup** (org.jsoup:jsoup 1.17.2), MIT License, Copyright Jonathan Hedley. HTML parsing for the provider sandbox's cheerio-style helper and Internet Archive descriptions. Source: https://github.com/jhy/jsoup
- **Blaze Official** example provider repo (Internet Archive films, Blender open movies): Blaze's own code, under Blaze's licence.

## REA: Reverse Engineer Anything skill (v0.46.2)

The built-in skill `reverse-engineer-anything` is adapted from REA by morluto, MIT License, Copyright (c) morluto. Source: https://github.com/morluto/rea. The skill runs the published `rea-agents` npm package in the user's Terminal Linux on demand; nothing from it is bundled in the APK.

MIT License text (applies to REA and Jsoup as named above): Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions: The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Phone control flash engine (v0.46.3)

- **ARTEMIS** (google/artemis), Apache License 2.0, Copyright 2026 Google LLC. Blaze's flash engine (`app/src/main/java/com/blaze/assistant/phone/flash/`) is a Kotlin rewrite, changed for Blaze, of ideas and code from ARTEMIS as of commit 351ca8422f7b5b54e80a9c1ce03a222e02415b6b: the compact indexed element list with 0-1000 bounds and duplicate suppression (artemis/utils/visualization.py), the UI filter's screen clipping, minimum size, merging of a clickable row's text children and parent/child redundancy (artemis/utils/ui_filter.py), the smallest-covering-element hit test (artemis/utils/element_hit_test.py), the dHash screen signature (artemis/utils/image_hash.py), the reactive flash observe-act loop with pro escalation and the stagnation rule (artemis/agents/flash). Modified files carry a notice saying so. ARTEMIS has no NOTICE file. Source: https://github.com/google/artemis
- **mobile-use** (Minitap, Inc.), Apache License 2.0, Copyright 2025-2026 Minitap, Inc. (modifications Copyright 2026 Google LLC), as included in ARTEMIS under third_party/mobile_use (upstream https://github.com/minitap-ai/mobile-use v3.6.3). The flash engine's percentage / 0-1000 normalized coordinate tap follows its `tap_percentage` (controllers/unified_controller.py). Its NOTICE reads: "Mobile-Use. Copyright 2025-2026 Minitap, Inc. This product includes software and architectural methodologies developed by Minitap, Inc. (https://github.com/minitap-ai/mobile-use)". As Minitap requests, Blaze credits Minitap, Inc. here.

Apache License 2.0 (applies to ARTEMIS and mobile-use as named above): Licensed under the Apache License, Version 2.0 (the "License"); you may not use these files except in compliance with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
