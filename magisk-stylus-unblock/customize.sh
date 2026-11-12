# Magisk installer step: copy this device's own stylus blocker config and add Nib to its exemption list.
# Nothing from Xiaomi is shipped in the module; the patched copy is made here, on your device.
CFG=/system_ext/etc/input/stylus_palm_reject_config.json
PKG=app.nib.notes

if [ ! -f "$CFG" ]; then
  ui_print "! $CFG not found."
  ui_print "! This device has no HyperOS stylus blocker, so Nib doesn't need this module."
  abort "! Nothing to do."
fi
if ! grep -q '"blockList"' "$CFG"; then
  abort "! Unexpected config format (no blockList); not changing anything."
fi

mkdir -p "$MODPATH/system/system_ext/etc/input"
OUT="$MODPATH/system/system_ext/etc/input/stylus_palm_reject_config.json"
if grep -q "\"$PKG\"" "$CFG"; then
  cp "$CFG" "$OUT"
else
  # insert our package as the first entry of "blockList": [ ... ]
  sed "/\"blockList\"[[:space:]]*:[[:space:]]*\[/a\\    \"$PKG\"," "$CFG" > "$OUT"
fi
if ! grep -q "\"$PKG\"" "$OUT"; then
  abort "! Couldn't add $PKG to the exemption list; not installing."
fi
set_perm "$OUT" 0 0 0644
ui_print "- Nib ($PKG) added to the stylus blocker's exemption list."
ui_print "- Reboot to apply."
