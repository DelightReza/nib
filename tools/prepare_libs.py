#!/usr/bin/env python3
"""Unpack fetched AAR/JAR dependencies (e.g. ML Kit digital ink) into libs/<name>/ for build-apk.sh.

Usage: python3 tools/prepare_libs.py <dir with .aar/.jar files>
Each AAR becomes libs/<name>/{classes.jar, extra jars, res/, AndroidManifest.xml, jni/, assets/};
libs/packages.txt lists the AAR packages that own resources (for aapt2 --extra-packages).
"""
import os, re, shutil, sys, zipfile

src, out = sys.argv[1], os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'libs')
shutil.rmtree(out, ignore_errors=True)
os.makedirs(out)
pkgs = []
for f in sorted(os.listdir(src)):
    name, ext = os.path.splitext(f)
    p = os.path.join(src, f)
    d = os.path.join(out, name)
    os.makedirs(d)
    if ext == '.jar':
        shutil.copy(p, os.path.join(d, 'classes.jar'))
        continue
    if ext != '.aar':
        continue
    with zipfile.ZipFile(p) as z:
        for n in z.namelist():
            if n.endswith('/'):
                continue
            if n == 'classes.jar' or n.startswith(('res/', 'jni/', 'assets/', 'libs/')) or n == 'AndroidManifest.xml':
                target = os.path.join(d, n.replace('libs/', 'jars/', 1))
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with z.open(n) as i, open(target, 'wb') as o:
                    shutil.copyfileobj(i, o)
    man = open(os.path.join(d, 'AndroidManifest.xml')).read()
    pkg = re.search(r'package="([^"]+)"', man).group(1)
    has_res = os.path.isdir(os.path.join(d, 'res')) and any(files for _, _, files in os.walk(os.path.join(d, 'res')))
    if not has_res:
        shutil.rmtree(os.path.join(d, 'res'), ignore_errors=True)
    elif pkg not in pkgs:
        pkgs.append(pkg)
open(os.path.join(out, 'packages.txt'), 'w').write(':'.join(pkgs))
print(f'{len(os.listdir(out)) - 1} libraries, {len(pkgs)} with resources')
