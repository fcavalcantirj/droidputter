# PORTING.md — droidputter build for a third-party Cardputer app

How any open-source M5GFX/M5Unified Cardputer app gets a droidputter build.
Proven twice: `apps/pense-bem` (private repo, unmodified) and
`apps/m5-example` (M5Cardputer library's own `inputText` example, unmodified,
byte-diffed 0 differences against upstream).

## The three overlay ingredients

An app is never edited in place. A small `platformio.ini` living OUTSIDE the
app's own repo (`apps/<name>/platformio.ini`, see `apps/pense-bem/platformio.ini`
or `apps/m5-example/platformio.ini`) adds exactly three things on top of the
app's normal build:

1. **`lib_extra_dirs = ../../shim/lib`** (plus `[platformio] src_dir = /path/to/app/src`
   pointing PlatformIO at the app's unmodified source instead of copying it).
   This is what makes PlatformIO's LDF see `shim/lib/DroidputterShim` and the
   patched libs materialized by `shim/apply.sh` (below) ahead of the registry
   copies.
2. **`lib_deps = m5stack/M5Unified@0.2.20` + `symlink://../../shim/lib/DroidputterShim`**
   — pulls in the shim library itself.
3. **`build_flags = -DDROIDPUTTER=1 -I ../../shim/lib/DroidputterShim/src`**
   — the compile-time switch the patched Panel_LCD/Keyboard_Class hooks check
   before calling into `dp::`.

Before building, run `shim/apply.sh <app-dir> [<libdeps-dir>]` once: it copies
pristine M5GFX 0.2.27 + M5Cardputer 1.1.1 into `<app-dir>/lib/` and applies
`shim/patches/*.patch` on top. Skipping this step is a silent no-op, not a
build error — the LDF falls back to the unpatched registry libs and the app
builds and boots but the display tee never fires (hit for real in
`apps/gps-demo`, see progress.txt Task 16). Full recipe and rationale:
`shim/README.md`.

## App-side changes: normally zero, sometimes one line

Any app that already owns an `M5Cardputer::Keyboard_Class` (calls
`M5Cardputer.begin(cfg, true)`) needs NO source changes at all — the patched
`Keyboard_Class::updateKeyList()` calls `dp::poll()` for it. This is true for
`apps/pense-bem` and the vendored `apps/m5-example` (`inputText.ino`, 0 byte
diff from upstream).

An app with no `Keyboard_Class` (e.g. a GPS-only app with no keyboard) must
call `dp::poll()` itself once per `loop()` — the only app-side line
`apps/gps-demo/src/main.cpp` adds. Everything else (display tee, GPS ring via
`droidputter_gps()`) needs no other app change.

## Version constraints

- M5GFX pinned at **0.2.27**, M5Cardputer at **1.1.1**. M5Unified 0.2.21+
  requires M5GFX 0.2.28, so M5Unified stays pinned at **0.2.20** until the
  patch is rebased onto 0.2.28 — do not bump any of the three independently.
- `espressif32@6.12.0` (arduino-esp32 2.0.17). The ESP32-C5 needs arduino
  core >= 3.x, which this platform version cannot target (see spec.json's
  "Other boards" task).

## What breaks / not covered yet

- **Apps that talk to the panel bus directly** (raw SPI/`Panel_LCD` register
  pokes bypassing the LovyanGFX `writePixels`/`writeBlock`/`writeImage`/
  `writeFillRectPreclipped`/`drawPixelPreclipped` write path the patch hooks)
  are invisible to the tee — nothing to mirror, no error either.
- **TFT_eSPI-based apps** (Bruce's own `tft_logger` prior art included): not
  covered. The patch targets M5GFX's `Panel_LCD`, not TFT_eSPI's driver
  classes. A TFT_eSPI shim would be a separate patch set.
- **Apps with no `Keyboard_Class` and no explicit `dp::poll()` call**: the
  tee's HELLO/STATS/PING/KEY/GPS_NMEA handling never runs — see "App-side
  changes" above.
- A first `shim/apply.sh` run against a fresh app's own resolved libdeps
  (rather than reusing `apps/pense-bem`'s `.pio/libdeps/m5cardputer`) has not
  been exercised; pass that app's own libdeps dir as the second argument if
  `pio pkg install`'s registry fetch is undesired.

## Adding the result to `apps/catalog.json`

1. Add an entry (or entries, one per built `env`) to the `APPS` list in
   `tools/make_catalog.py`: `name`, `board`, `env`, `build_dir`
   (`apps/<name>/.pio/build/<env>`), `description`, `source_repo`, `license`.
2. Build that env (`cd apps/<name> && pio run -e <env>`) so the `.pio/build/<env>/`
   bin parts exist.
3. Run `python3 tools/make_catalog.py` to regenerate `apps/catalog.json` with
   the sha256 of every part (bootloader.bin @ 0x0, partitions.bin @ 0x8000,
   boot_app0.bin @ 0xe000, firmware.bin @ 0x10000).
4. `python3 tools/make_catalog.py --check` must exit 0 (catalog matches the
   build outputs) before committing.
