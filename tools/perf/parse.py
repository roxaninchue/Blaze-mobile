#!/usr/bin/env python3
"""perf-browser bench: turns the raw files bench.sh wrote (dumpsys gfxinfo per phase, page timings, am start -W) into
results.json and a BEFORE vs AFTER markdown table (medians over rounds). usage: parse.py <outdir>"""
import glob, json, os, re, statistics, sys

OUT = sys.argv[1] if len(sys.argv) > 1 else "perf-out"
PHASES = ["scroll", "loadscroll", "tabs", "drag"]


def gfx(path):
    t = open(path, errors="replace").read()
    # Only the process-wide summary at the top (before the per-window "Profile data" section).
    head = t.split("Profile data in ms")[0]
    def num(label):
        m = re.search(re.escape(label) + r":\s*([\d.]+)", head)
        return float(m.group(1)) if m else None
    r = {
        "frames": num("Total frames rendered"),
        "janky": None, "janky_pct": None,
        "p50": num("50th percentile"), "p90": num("90th percentile"), "p95": num("95th percentile"), "p99": num("99th percentile"),
        "slow_ui": num("Number Slow UI thread"), "slow_draw": num("Number Slow issue draw commands"),
        "slow_bitmap": num("Number Slow bitmap uploads"), "deadline_missed": num("Number Frame deadline missed"),
        "gpu_p50": num("50th gpu percentile"), "gpu_p99": num("99th gpu percentile"),
    }
    m = re.search(r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)", head)
    if m:
        r["janky"], r["janky_pct"] = float(m.group(1)), float(m.group(2))
    m = re.search(r"^HISTOGRAM:(.*)$", head, re.M)
    if m:
        buckets = [(int(a), int(b)) for a, b in re.findall(r"(\d+)ms=(\d+)", m.group(1))]
        used = [ms for ms, c in buckets if c > 0]
        r["max_frame_ms"] = float(max(used)) if used else None
        r["frames_over_100ms"] = float(sum(c for ms, c in buckets if ms >= 100))
        r["frames_over_250ms"] = float(sum(c for ms, c in buckets if ms >= 250))
    return r


def load_json(path):
    try:
        t = open(path).read().strip()
        return json.loads(t) if t else None
    except Exception:
        return None


def start_times(path):
    try:
        t = open(path).read()
    except Exception:
        return {}
    d = {}
    for k in ("TotalTime", "WaitTime"):
        m = re.search(k + r":\s*(\d+)", t)
        if m:
            d[k] = float(m.group(1))
    return d


rounds = {}
for f in sorted(glob.glob(os.path.join(OUT, "*-r*-start.txt"))):
    base = os.path.basename(f)[: -len("-start.txt")]
    variant, r = base.rsplit("-r", 1)
    rec = {"variant": variant, "round": int(r)}
    st = start_times(f)
    rec["launch_total_ms"] = st.get("TotalTime")
    t0 = load_json(os.path.join(OUT, base + "-t0.json")) or {}
    la = load_json(os.path.join(OUT, base + "-load.json")) or {}
    if la and t0.get("t0") and la.get("origin") and la.get("fcp", -1) > 0:
        rec["intent_to_fcp_ms"] = float(la["origin"] + la["fcp"] - t0["t0"])
    for k in ("fcp", "load"):
        if la.get(k, -1) > 0:
            rec["page_" + k + "_ms"] = float(la[k])
    t1 = load_json(os.path.join(OUT, base + "-t1.json")) or {}
    lb = load_json(os.path.join(OUT, base + "-loadB.json")) or {}
    if lb and t1.get("t1") and lb.get("origin") and lb.get("fcp", -1) > 0:
        rec["newtab_intent_to_fcp_ms"] = float(lb["origin"] + lb["fcp"] - t1["t1"])
    if lb.get("load", -1) > 0:
        rec["newtab_page_load_ms"] = float(lb["load"])
    ch = os.path.join(OUT, base + "-choreo.txt")
    if os.path.exists(ch):
        sk = [int(x) for x in re.findall(r"Skipped (\d+) frames", open(ch, errors="replace").read())]
        rec["stalls_reported"] = float(len(sk)); rec["stall_frames_skipped"] = float(sum(sk))
    for ph in PHASES:
        p = os.path.join(OUT, f"{base}-{ph}.txt")
        if os.path.exists(p):
            for k, v in gfx(p).items():
                rec[f"{ph}.{k}"] = v
    rounds.setdefault(variant, []).append(rec)


def med(variant, key):
    xs = [r.get(key) for r in rounds.get(variant, []) if r.get(key) is not None]
    return (statistics.median(xs), len(xs)) if xs else (None, 0)


keys = ["launch_total_ms", "intent_to_fcp_ms", "page_fcp_ms", "page_load_ms", "newtab_intent_to_fcp_ms", "newtab_page_load_ms",
        "stalls_reported", "stall_frames_skipped"]
for ph in PHASES:
    keys += [f"{ph}.{k}" for k in ("frames", "janky_pct", "p50", "p90", "p95", "p99", "max_frame_ms", "frames_over_100ms", "slow_ui", "deadline_missed")]

summary = {}
lines = ["| metric | BEFORE (main 785d23f, 0.45.25) | AFTER (app/perf-browser) | change |", "|---|---|---|---|"]
for k in keys:
    b, nb = med("before", k); a, na = med("after", k)
    if b is None and a is None:
        continue
    ch = ""
    if b not in (None, 0) and a is not None:
        ch = f"{(a - b) / b * 100:+.0f}%"
    summary[k] = {"before": b, "after": a, "n_before": nb, "n_after": na}
    fmt = lambda x: "n/a" if x is None else (f"{x:.1f}" if abs(x) < 100 else f"{x:.0f}")
    lines.append(f"| {k} | {fmt(b)} (n={nb}) | {fmt(a)} (n={na}) | {ch} |")

json.dump({"rounds": rounds, "medians": summary}, open(os.path.join(OUT, "results.json"), "w"), indent=1)
md = ["## In-app browser: BEFORE vs AFTER (EMULATOR, medians over rounds)", "",
      "x86_64 emulator on a GitHub runner (software GPU): absolute numbers are slow; compare the two columns. "
      "Frame times in ms from `dumpsys gfxinfo` per phase; jank % = gfxinfo Janky frames; slow_ui = frames with a slow UI thread.", ""] + lines
open(os.path.join(OUT, "summary.md"), "w").write("\n".join(md) + "\n")
print("\n".join(md))
