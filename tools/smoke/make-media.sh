#!/bin/bash
# 0.46.6 smoke gate: tiny test media for the instrumented playback steps (generated, nothing third-party).
set -euo pipefail
A=app/src/androidTest/assets; mkdir -p "$A"
command -v ffmpeg >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg >/dev/null; }
ffmpeg -loglevel error -y -f lavfi -i testsrc=size=320x240:rate=24 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 12 \
  -c:v libx264 -profile:v baseline -pix_fmt yuv420p -c:a aac -b:a 64k -movflags +faststart "$A/smoke.mp4"
ffmpeg -loglevel error -y -f lavfi -i sine=frequency=330:sample_rate=44100 -t 8 -c:a aac -b:a 64k "$A/smoke.m4a"
ls -la "$A"
