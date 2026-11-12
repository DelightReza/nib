#!/usr/bin/env python3
"""Downloads the pinned ML Kit handwriting-recognition libraries listed in tools/libs.lock, checks every
SHA-256, and unpacks them into libs/ (via prepare_libs.py) for build-apk.sh.

Usage: python3 tools/fetch_libs.py
The libraries are Google's (ML Kit terms: https://developers.google.com/ml-kit/terms) and are not stored
in this repository.
"""
import hashlib, os, subprocess, sys, tempfile, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPOS = {'google': 'https://dl.google.com/dl/android/maven2', 'central': 'https://repo1.maven.org/maven2'}

def url(repo, g, a, v, ext):
    return f"{REPOS[repo]}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}.{ext}"

def get(u, dest):
    with urllib.request.urlopen(urllib.request.Request(u, headers={'User-Agent': 'nib-build'}), timeout=60) as r:
        data = r.read()
    open(dest, 'wb').write(data)
    return data

entries = [l.split() for l in open(os.path.join(HERE, 'libs.lock')) if l.strip() and not l.startswith('#')]
tmp = tempfile.mkdtemp(prefix='nib-libs-')
for i, (coord, sha, repo) in enumerate(entries, 1):
    g, a, v, ext = coord.split(':')
    dest = os.path.join(tmp, f'{a}-{v}.{ext}')
    data = None
    for r in [repo] + [x for x in REPOS if x != repo]:
        try:
            data = get(url(r, g, a, v, ext), dest)
            break
        except Exception:
            continue
    if data is None:
        sys.exit(f'\n✗ could not download {coord}')
    if hashlib.sha256(data).hexdigest() != sha:
        sys.exit(f'\n✗ checksum mismatch for {coord}; refusing to continue')
    bar = '#' * (30 * i // len(entries))
    print(f'\r[{bar:<30}] {i}/{len(entries)} {a[:28]:<28}', end='', flush=True)
print()
subprocess.check_call([sys.executable, os.path.join(HERE, 'prepare_libs.py'), tmp])
