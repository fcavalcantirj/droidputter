#!/usr/bin/env bash
# Materialise the patched libraries for an app overlay: copies M5GFX 0.2.27 + M5Cardputer 1.1.1 from a
# PlatformIO libdeps dir (or downloads them with `pio pkg install`) into <app>/lib/ and applies shim/patches.
# Usage: shim/apply.sh <app-dir> [<libdeps-dir-with-M5GFX-and-M5Cardputer>]
set -euo pipefail
APP=${1:?app dir (e.g. apps/pense-bem)}; SRC=${2:-}
HERE=$(cd "$(dirname "$0")" && pwd)
if [ -z "$SRC" ]; then
  TMP=$(mktemp -d); (cd "$TMP" && pio pkg install -g -l "m5stack/M5GFX@0.2.27" -l "m5stack/M5Cardputer@1.1.1" >/dev/null); SRC=~/.platformio/lib
fi
mkdir -p "$APP/lib"; rm -rf "$APP/lib/M5GFX" "$APP/lib/M5Cardputer"
cp -R "$SRC/M5GFX" "$APP/lib/M5GFX"; cp -R "$SRC/M5Cardputer" "$APP/lib/M5Cardputer"
(cd "$APP/lib" && patch -p1 < "$HERE/patches/M5GFX-0.2.27-droidputter.patch" && patch -p1 < "$HERE/patches/M5Cardputer-1.1.1-droidputter.patch")
echo "patched libs ready in $APP/lib (M5GFX 0.2.27 + M5Cardputer 1.1.1 + droidputter hooks)"
