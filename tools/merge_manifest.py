#!/usr/bin/env python3
"""Minimal manifest merger for the Gradle-free build: app manifest + every libs/*/AndroidManifest.xml.

Usage: merge_manifest.py <app manifest> <libs dir> <out>
Adds library permissions, <queries> and <application> components (services, providers, receivers, meta-data),
substituting ${applicationId}; components declared by several libraries are merged child by child (meta-data).
"""
import os, sys, xml.etree.ElementTree as ET

A = 'http://schemas.android.com/apk/res/android'
T = 'http://schemas.android.com/tools'
ET.register_namespace('android', A)
ET.register_namespace('tools', T)
NAME = '{%s}name' % A

app_path, libs, out = sys.argv[1:4]
tree = ET.parse(app_path)
root = tree.getroot()
pkg = root.get('package')
app = root.find('application')

def clean(e):
    for k in list(e.attrib):
        if k.startswith('{%s}' % T):
            del e.attrib[k]
        elif '${applicationId}' in e.attrib[k]:
            e.attrib[k] = e.attrib[k].replace('${applicationId}', pkg)
    for c in list(e):
        if c.get('{%s}node' % T) == 'remove':
            e.remove(c)
        else:
            clean(c)
    return e

# Privacy: drop Google's usage-statistics upload backend (ML Kit's "firelog"). Without a backend the
# transport runtime keeps nothing to send, so recognition stays on the tablet.
EXCLUDE = {'backend:com.google.android.datatransport.cct.CctBackendFactory'}

def strip_excluded(e):
    for c in list(e):
        if c.get(NAME) in EXCLUDE:
            e.remove(c)
        else:
            strip_excluded(c)

perms = {p.get(NAME) for p in root.findall('uses-permission')}
comps = {(c.tag, c.get(NAME)): c for c in app}
queries = root.find('queries')
for lib in sorted(os.listdir(libs)):
    m = os.path.join(libs, lib, 'AndroidManifest.xml')
    if not os.path.exists(m):
        continue
    lr = ET.parse(m).getroot()
    for p in lr.findall('uses-permission'):
        if p.get('{%s}node' % T) == 'remove' or p.get(NAME) in perms:
            continue
        perms.add(p.get(NAME))
        root.insert(0, clean(p))
    for q in lr.findall('queries'):
        if queries is None:
            queries = ET.SubElement(root, 'queries')
        for c in q:
            queries.append(clean(c))
    la = lr.find('application')
    if la is None:
        continue
    for c in la:
        if c.get('{%s}node' % T) == 'remove':
            continue
        c = clean(c)
        strip_excluded(c)
        key = (c.tag, c.get(NAME))
        if key in comps:  # same component from several libraries: merge children it doesn't have yet
            have = {(x.tag, x.get(NAME)) for x in comps[key]}
            for x in c:
                if (x.tag, x.get(NAME)) not in have:
                    comps[key].append(x)
        else:
            comps[key] = c
            app.append(c)
ET.indent(tree)
tree.write(out, encoding='utf-8', xml_declaration=True)
print(f'merged manifest: {len(perms)} permissions, {len(comps)} application entries')
