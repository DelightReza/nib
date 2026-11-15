#!/usr/bin/env python3
"""Minimal zipalign: copies an APK so every stored (uncompressed) entry's data starts on a 4-byte boundary.
Usage: zipalign.py in.apk out.apk   (used in Termux, where the SDK's zipalign binary isn't for ARM)"""
import sys, zipfile

src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    for info in zin.infolist():
        data = zin.read(info)
        out = zipfile.ZipInfo(info.filename, info.date_time)
        out.compress_type = info.compress_type
        out.external_attr = info.external_attr
        out.extra = b""
        if info.compress_type == zipfile.ZIP_STORED:
            offset = zout.fp.tell() + 30 + len(info.filename.encode("utf-8"))
            pad = (-offset) % 4
            out.extra = b"\x00" * pad
        zout.writestr(out, data)
