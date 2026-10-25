#!/usr/bin/env python3
"""Centre of the first uiautomator node whose <attr> equals (or starts with) <value>. Prints "x y"; exit 1 if none.
usage: ui.py <text|content-desc> <value> < ui.xml"""
import re, sys

attr, want = sys.argv[1], sys.argv[2]
xml = sys.stdin.read()
for node in re.finditer(r"<node [^>]*>", xml):
    n = node.group(0)
    m = re.search(r'\b' + re.escape(attr) + r'="([^"]*)"', n)
    if not m:
        continue
    v = m.group(1)
    if v == want or v.startswith(want):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            if x2 > x1 and y2 > y1:
                print((x1 + x2) // 2, (y1 + y2) // 2)
                sys.exit(0)
sys.exit(1)
