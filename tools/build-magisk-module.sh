#!/bin/bash
# Packs magisk-stylus-unblock/ into build/nib-stylus-unblock.zip (install it from the Magisk app).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
rm -f build/nib-stylus-unblock.zip
(cd magisk-stylus-unblock && zip -q -r ../build/nib-stylus-unblock.zip META-INF module.prop customize.sh system.prop)
echo "✓ build/nib-stylus-unblock.zip"
