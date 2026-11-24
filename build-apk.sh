#!/bin/bash
# Builds build/nib.apk (Nib, low-latency notes for Xiaomi Pad 6) straight from SDK tools, no Gradle.
#   ./build-apk.sh             build
#   ./build-apk.sh --install   build, then install on the connected phone and open it
# Needs: Android SDK (platform 35, build-tools 35), JDK 17, zip. Signs with ~/.android/debug.keystore
# unless NIB_KEYSTORE / NIB_KS_PASS / NIB_KEY_ALIAS are set.
# NIB_DEBUG=1 builds a debuggable build-debug/nib.apk for the emulator (run-as, seeded test data).
# With more than one device attached, pick one with ANDROID_SERIAL=<serial>.
set -euo pipefail
cd "$(dirname "$0")"

if [ -n "${PREFIX:-}" ] && [[ "$PREFIX" == *com.termux* ]]; then
  # Termux on the phone: native aapt2, the SDK's Java tools as jars, a Python zipalign (the SDK binaries are x86-only)
  ON_PHONE=1
  SDK=${ANDROID_HOME:-$HOME/android-sdk}
  BT=$SDK/build-tools/34.0.0
  AAPT2=$(command -v aapt2)
  D8=(java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8)
  APKSIGNER=(java -jar "$BT/lib/apksigner.jar")
  ZIPALIGN=(python3 tools/zipalign.py)
else
  ON_PHONE=0
  SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
  BT=$SDK/build-tools/35.0.0
  AAPT2=$BT/aapt2
  D8=("$BT/d8")
  APKSIGNER=("$BT/apksigner")
  ZIPALIGN=("$BT/zipalign" -f -p 4)
fi
JAR=$SDK/platforms/android-35/android.jar
OUT=build
DEBUG_FLAG=""
if [ "${NIB_DEBUG:-}" = "1" ]; then OUT=build-debug; DEBUG_FLAG="--debug-mode"; fi
VERSION_NAME=2.5.0
VERSION_CODE=$(git rev-list --count HEAD 2>/dev/null || echo 1)
KS=${NIB_KEYSTORE:-$HOME/.android/debug.keystore}
KS_PASS=${NIB_KS_PASS:-android}
KEY_ALIAS=${NIB_KEY_ALIAS:-androiddebugkey}
KEY_PASS=${NIB_KEY_PASS:-$KS_PASS}

rm -rf "$OUT"
rm -rf "$OUT/classes"   # never ship classes left over from an earlier build
mkdir -p "$OUT/classes" "$OUT/dex"

# Third-party libraries (ML Kit handwriting recognition), unpacked into libs/ by tools/prepare_libs.py
MANIFEST=AndroidManifest.xml
LIB_RES=()
EXTRA_PKGS=()
LIB_JARS=""
if [ -d libs ]; then
  echo "› libraries"
  python3 tools/merge_manifest.py AndroidManifest.xml libs "$OUT/AndroidManifest.xml" >/dev/null
  MANIFEST="$OUT/AndroidManifest.xml"
  for d in libs/*/res; do
    n=$(basename "$(dirname "$d")")
    "$AAPT2" compile --dir "$d" -o "$OUT/libres-$n.zip"
    LIB_RES+=(-R "$OUT/libres-$n.zip")
  done
  [ -s libs/packages.txt ] && EXTRA_PKGS=(--extra-packages "$(cat libs/packages.txt)")
  LIB_JARS=$(find libs -name '*.jar' | sort | tr '\n' ':')
fi

echo "› resources"
"$AAPT2" compile --dir res -o "$OUT/res.zip"
"$AAPT2" link -o "$OUT/unsigned.apk" -I "$JAR" \
  --manifest "$MANIFEST" "${LIB_RES[@]}" -R "$OUT/res.zip" --java "$OUT/gen" "${EXTRA_PKGS[@]}" \
  --min-sdk-version 34 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --auto-add-overlay $DEBUG_FLAG

echo "› java"
javac -nowarn -Xlint:-options --release 11 -classpath "$JAR:$LIB_JARS" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java') 2>&1 | grep -v "^Note:" || true
[ "${PIPESTATUS[0]}" -eq 0 ] || { echo "javac failed" >&2; exit 1; }
[ -f "$OUT/classes/app/nib/notes/LibraryActivity.class" ] || { echo "javac failed" >&2; exit 1; }
"${D8[@]}" --release --min-api 34 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class') \
  $(find libs -name '*.jar' 2>/dev/null | sort) 2>&1 | grep -v -E "^Warning|^Type .* was not found|^Missing class|^Info in|^Classes with missing EnclosingMethod" || true
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes*.dex)
if [ -d libs ]; then # native code (64-bit ARM only: the tablet) and library assets
  mkdir -p "$OUT/apkx/lib/arm64-v8a" "$OUT/apkx/assets"
  find libs -path '*/jni/arm64-v8a/*.so' -exec cp {} "$OUT/apkx/lib/arm64-v8a/" \;
  for a in libs/*/assets; do cp -r "$a/." "$OUT/apkx/assets/"; done
fi
if [ -d assets ]; then # Nib's own assets (fonts)
  mkdir -p "$OUT/apkx/assets"
  cp -r assets/. "$OUT/apkx/assets/"
fi
if [ -d "$OUT/apkx" ]; then
  (cd "$OUT/apkx" && zip -q -r ../unsigned.apk $(ls -d lib assets 2>/dev/null))
fi

echo "› sign"
"${ZIPALIGN[@]}" "$OUT/unsigned.apk" "$OUT/aligned.apk"
"${APKSIGNER[@]}" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KEY_ALIAS" \
  --key-pass "pass:$KEY_PASS" --out "$OUT/nib.apk" "$OUT/aligned.apk"
"${APKSIGNER[@]}" verify "$OUT/nib.apk"
rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk" "$OUT/nib.apk.idsig"
echo "✓ $OUT/nib.apk ($(du -h "$OUT/nib.apk" | cut -f1), v$VERSION_NAME build $VERSION_CODE)"

if [ "${1:-}" = "--install" ] && [ "$ON_PHONE" = 1 ] && ! adb get-state >/dev/null 2>&1; then
  # Termux without an adb connection: hand the APK to Android's installer
  mkdir -p "$HOME/storage/downloads"
  cp "$OUT/nib.apk" "$HOME/storage/downloads/Nib.apk"
  echo "Opening the installer. Tap Install / Update. (First time: allow Termux to install unknown apps.)"
  termux-open "$HOME/storage/downloads/Nib.apk"
elif [ "${1:-}" = "--install" ]; then
  adb install -r "$OUT/nib.apk"
  # adb installs start interpreted ("verify"); compile ahead of time so the ink path is JIT-free from the first stroke.
  adb shell cmd package compile -m speed -f app.nib.notes
  adb shell am start -n app.nib.notes/.LibraryActivity ${NIB_REOPEN:+--ez reopen true}
fi
