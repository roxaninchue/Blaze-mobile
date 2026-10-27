#!/usr/bin/env python3
"""Low-RAM smoke gate: the worst APP-ATTRIBUTABLE main-thread stall of one process during a cold start.

usage: app_stall.py <logcat -v threadtime file> <pid>   -> prints JSON {raw_ms, app_ms, frames:[...], excluded_ms}

Approved gate change (owner, 2026-10-27): the emulator draws Blaze's very first frame with swiftshader (software GL), which
takes 4-6 s on the RenderThread once per process (frame 2 then takes 1-3 ms). While it runs the main thread is parked in
HWUI waiting for the RenderThread, so Choreographer reports a 5 s "Skipped N frames" that no app code caused. This script
keeps every other millisecond:

  For each frame HWUI reported (OpenGLRenderer "Davey!" lines, logged for every frame over 700 ms, so every frame that
  can fail the 700 ms limit has one), the main-thread delay before the frame started is HandleInputStart - IntendedVsync
  (exactly what Choreographer's "Skipped N frames" measures). Only for the frame right after the process's FIRST drawn
  frame, the part of that delay that overlaps the first frame's RenderThread GL work [IssueDrawCommandsStart,
  SwapBuffersCompleted] is removed, and only when that GL work itself took over 700 ms (the one-off software first frame).
  Each frame's own main-thread work (input + animation + measure/layout + record = SyncQueued - HandleInputStart) also
  counts on its own. Choreographer skips that match no Davey line count in full.

The verdict stays: app_ms must be under 700 ms (42 frames at 60 Hz). raw_ms is the old number, reported alongside.
"""
import json, re, sys

FRAME_MS = 1000.0 / 60
SOFT_FIRST_FRAME_MS = 700.0

def parse(path, pid):
    davey, skips = [], []
    for line in open(path, errors="replace"):
        f = line.split(None, 6)
        if len(f) < 7 or f[2] != str(pid):
            continue
        msg = f[6]
        if "Davey!" in msg:
            kv = dict((k, int(v)) for k, v in re.findall(r"(\w+)=(-?\d+)", msg))
            if "IntendedVsync" in kv and "HandleInputStart" in kv:
                davey.append(kv)
        elif f[3] == str(pid) and f[5].startswith("Choreographer"):
            m = re.search(r"Skipped (\d+) frames", msg)
            if m:
                skips.append(int(m.group(1)))
    davey.sort(key=lambda d: d["IntendedVsync"])
    return davey, skips

def overlap(a0, a1, b0, b1):
    return max(0, min(a1, b1) - max(a0, b0))

def analyse(davey, skips):
    frames, excluded = [], 0.0
    first = davey[0] if davey else None
    soft = None
    if first and first.get("SwapBuffersCompleted", 0) and first.get("IssueDrawCommandsStart", 0):
        gl_ms = (first["SwapBuffersCompleted"] - first["IssueDrawCommandsStart"]) / 1e6
        if gl_ms > SOFT_FIRST_FRAME_MS:
            soft = (first["IssueDrawCommandsStart"], first["SwapBuffersCompleted"], gl_ms)
    matched = set()
    for i, d in enumerate(davey):
        v0, s = d["IntendedVsync"], d["HandleInputStart"]
        delay = max(0, s - v0) / 1e6
        cut = 0.0
        if soft and i == 1:
            cut = overlap(v0, s, soft[0], soft[1]) / 1e6
        work = max(0, d.get("SyncQueued", s) - s) / 1e6
        app = max(delay - cut, work)
        excluded += cut
        frames.append({"frame": i + 1, "delay_ms": round(delay), "render_wait_excluded_ms": round(cut), "own_work_ms": round(work),
                       "app_ms": round(app), "first_frame_gl_ms": round(soft[2]) if soft and i == 0 else None})
        for j, n in enumerate(skips):
            if j not in matched and abs(n * FRAME_MS - delay) <= 2 * FRAME_MS:
                matched.add(j); break
    unmatched = [n * FRAME_MS for j, n in enumerate(skips) if j not in matched]
    raw = max([n * FRAME_MS for n in skips] + [0])
    app = max([f["app_ms"] for f in frames] + unmatched + [0])
    return {"raw_ms": round(raw), "app_ms": round(app), "excluded_ms": round(excluded), "unmatched_skips_ms": [round(x) for x in unmatched],
            "soft_first_frame": soft is not None, "frames": frames}

if __name__ == "__main__":
    d, s = parse(sys.argv[1], sys.argv[2])
    print(json.dumps(analyse(d, s)))
