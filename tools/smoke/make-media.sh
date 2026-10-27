#!/bin/bash
# 0.46.6 smoke gate: tiny test media for the instrumented playback steps (generated, nothing third-party).
set -euo pipefail
A=app/src/androidTest/assets; mkdir -p "$A"
# 0.48.0: apt on the runner can hang for 20+ min; bound it, then fall back to a static ffmpeg build.
if ! command -v ffmpeg >/dev/null; then
  APT="-o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20 -o Acquire::Retries=2 -o DPkg::Lock::Timeout=60"
  timeout 240 sudo apt-get $APT install -y -qq --no-install-recommends ffmpeg >/dev/null 2>&1 \
    || { timeout 240 sudo apt-get $APT update -qq && timeout 300 sudo apt-get $APT install -y -qq --no-install-recommends ffmpeg >/dev/null; } \
    || true
fi
if ! command -v ffmpeg >/dev/null; then
  echo "apt ffmpeg unavailable: using the static build"
  mkdir -p /tmp/ffs && curl -fsSL --retry 3 --max-time 300 https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz | tar -xJ -C /tmp/ffs --strip-components=1
  export PATH="/tmp/ffs:$PATH"
fi
ffmpeg -loglevel error -y -f lavfi -i testsrc=size=320x240:rate=24 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 12 \
  -c:v libx264 -profile:v baseline -pix_fmt yuv420p -c:a aac -b:a 64k -movflags +faststart "$A/smoke.mp4"
ffmpeg -loglevel error -y -f lavfi -i sine=frequency=330:sample_rate=44100 -t 8 -c:a aac -b:a 64k "$A/smoke.m4a"
ls -la "$A"
