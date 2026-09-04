#!/usr/bin/env bash
# Materialise the patched libraries for an app overlay: copies pristine M5GFX 0.2.27 + M5Cardputer 1.1.1
# into <app>/lib/ and applies shim/patches on top.
#
# The pristine sources come from PlatformIO's global library storage (~/.platformio/lib, or
# $PLATFORMIO_CORE_DIR/lib), downloaded once with `pio pkg install -g` when missing, or from the optional
# <libdeps-dir>. Either way the folder is picked by VERSION (library.json), never by name: PlatformIO
# stores a second release of a library as <Name>@<version>, so the plain M5GFX/ folder can be a different
# release (2026-09-03: 0.2.28, pulled in as an M5Unified 0.2.21 dependency, sat in M5GFX/ while 0.2.27
# sat in M5GFX@0.2.27/).
# CI-safe: bash + coreutils + patch + python3 + pio only; touches nothing outside the repo and
# ~/.platformio; idempotent (a re-run replaces the copies); GNU (Ubuntu) and BSD (macOS) tools alike.
# Usage: shim/apply.sh <app-dir> [<libdeps-dir-with-M5GFX-and-M5Cardputer>]
set -euo pipefail
APP=${1:?app dir (e.g. apps/pense-bem)}; SRC=${2:-}
HERE=$(cd "$(dirname "$0")" && pwd)
M5GFX_VER=0.2.27; M5CARDPUTER_VER=1.1.1
CORE=${PLATFORMIO_CORE_DIR:-$HOME/.platformio}
PIO=${PIO:-$(command -v pio || echo "$CORE/penv/bin/pio")}

lib_version() {   # <lib-dir> -> version from its library.json ("" if none)
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("version", ""))' "$1/library.json" 2>/dev/null || true
}
find_lib() {   # <storage-dir> <Name> <version> -> the folder holding exactly that version
  local d
  for d in "$1/$2@$3" "$1/$2" "$1/$2"@*; do
    [ -d "$d" ] && [ "$(lib_version "$d")" = "$3" ] && { echo "$d"; return 0; }
  done
  return 1
}

GFX=$(find_lib "${SRC:-$CORE/lib}" M5GFX "$M5GFX_VER" || find_lib "$CORE/lib" M5GFX "$M5GFX_VER" || true)
CARD=$(find_lib "${SRC:-$CORE/lib}" M5Cardputer "$M5CARDPUTER_VER" || find_lib "$CORE/lib" M5Cardputer "$M5CARDPUTER_VER" || true)
if [ -z "$GFX" ] || [ -z "$CARD" ]; then
  TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
  (cd "$TMP" && "$PIO" pkg install -g --skip-dependencies -l "m5stack/M5GFX@$M5GFX_VER" -l "m5stack/M5Cardputer@$M5CARDPUTER_VER" >/dev/null)
  GFX=$(find_lib "$CORE/lib" M5GFX "$M5GFX_VER")
  CARD=$(find_lib "$CORE/lib" M5Cardputer "$M5CARDPUTER_VER")
fi

mkdir -p "$APP/lib"; rm -rf "$APP/lib/M5GFX" "$APP/lib/M5Cardputer"
cp -R "$GFX" "$APP/lib/M5GFX"; cp -R "$CARD" "$APP/lib/M5Cardputer"
(cd "$APP/lib" && patch -p1 < "$HERE/patches/M5GFX-0.2.27-droidputter.patch" && patch -p1 < "$HERE/patches/M5Cardputer-1.1.1-droidputter.patch")
echo "patched libs ready in $APP/lib (M5GFX $M5GFX_VER from $GFX + M5Cardputer $M5CARDPUTER_VER from $CARD + droidputter hooks)"
