# Nib stylus unblock (optional, HyperOS only, needs root)

Xiaomi's HyperOS holds back the first ~40 ms / 8 px of every pen stroke for apps that are not on its
exemption list (`MiuiStylusEventBlocker`, a palm-rejection feature). Xiaomi's own Notes app is exempt;
other note apps are not, so their strokes start a little late and the first pixels "snap" into place.

This Magisk module adds Nib (`app.nib.notes`) to that list. It contains no Xiaomi files: when you install
it, `customize.sh` copies **your device's own** `/system_ext/etc/input/stylus_palm_reject_config.json`,
adds Nib to its `blockList`, and Magisk overlays the copy at boot. On devices without this file the
installer stops and changes nothing.

You only need it on Xiaomi / Redmi / POCO tablets running HyperOS. Nib works without it.

**Install:** build the zip with `tools/build-magisk-module.sh`, then Magisk app → Modules → Install from
storage (or `adb push` it and run `su -c magisk --install-module <zip>`), and reboot.
**Remove:** delete the module in the Magisk app and reboot.
