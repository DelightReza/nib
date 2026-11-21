#!/usr/bin/env python3
"""Writes a big synthetic notebook (old single-file NIB6 format) for memory / speed tests.
usage: make_notebook.py OUTDIR PAGES [STROKES_PER_PAGE]"""
import math, os, random, struct, sys, time

out, pages = sys.argv[1], int(sys.argv[2])
per = int(sys.argv[3]) if len(sys.argv) > 3 else 600
random.seed(7)
os.makedirs(out, exist_ok=True)
b = bytearray()
b += struct.pack('>ii', 0x4E494236, pages)
total = 0
for pg in range(pages):
    b += struct.pack('>ifi', 3, 1414.0, -1)
    b += struct.pack('>i', per)
    lines = 25
    for s in range(per):
        line = s * lines // per
        x0 = 60 + (s % (per // lines)) * (880 / (per // lines)) + random.uniform(-3, 3)
        y0 = 90 + line * 52
        n = random.randint(30, 60)
        b += struct.pack('>iIfi', 0, 0xff1a1c20, 2.6, n)
        a = random.uniform(0, 6.28)
        for k in range(n):
            t = k / n
            x = x0 + t * 28 + 7 * math.cos(a + t * 9)
            y = y0 + 10 * math.sin(a + t * 7)
            b += struct.pack('>fff', x, y, 0.35 + 0.2 * math.sin(t * 3.14))
        total += n
    b += struct.pack('>i', 0)          # items
    b += struct.pack('>?', False)      # bookmark
    b += struct.pack('>fifi', 1.0, 0, 1.0, 0)
open(os.path.join(out, 'ink.bin'), 'wb').write(b)
now = int(time.time() * 1000)
open(os.path.join(out, 'meta.properties'), 'w').write(
    f"title=Stress test {pages} pages\npages={pages}\ncreated={now}\nmodified={now}\npaper=0\ncanvas=false\n")
print(f"{pages} pages, {per * pages} strokes, {total} points, {len(b) / 1e6:.1f} MB")
