# Droidputter — Ground Rules

Plug an ESP32-S3 into an Android phone over USB-OTG, open one app, and the phone becomes the Cardputer: the ESP runs the app, the phone IS its screen, keyboard, GPS and app launcher. The ESP needs no display or keyboard of its own.

The product ESP is a bare ESP32-S3 (the Cardputer's StampS3 class: ESP32-S3, 8 MB flash). A real Cardputer ADV is used in the spikes ONLY because it is in hand and its own TFT gives ground truth next to the phone. The ESP is always the brain; the phone adds zero computation. Bruce firmware is NOT the target and must not appear as a dependency anywhere in this ledger; it is at most one future catalog entry.

## Mechanism

From SPEC.md finding B, verified: ESP32 binaries are statically linked, so the phone cannot mirror arbitrary M5Burner binaries. What works is a display+keyboard SHIM: a patched copy of M5GFX (LovyanGFX Panel_LCD write path: setWindow / writePixels / writeBlock / writeImage / writeFillRectPreclipped / drawPixelPreclipped) that tees every pixel write to USB-CDC, plus a patched M5Cardputer Keyboard_Class that merges key events received over USB. Any open-source Cardputer app REBUILT against the shim runs on the phone unchanged. Bruce's tft_logger (TFT_eSPI) is prior art for the same pattern.

## Repo layout

- `shim/` — PlatformIO library DroidputterShim: patched M5GFX + patched M5Cardputer keyboard + USB framing.
- `tools/` — host-side Python receiver/renderer and capture tools.
- `fixtures/` — captured real streams, committed.
- `apps/` — build recipes for rebuilt apps; first one = Pense-Bem at `/Users/fcavalcanti/dev/m5/cardputter-pense-pem`.
- `android/` — Gradle project: `core` pure-JVM Kotlin module + `app` Android module.
- `docs/` — protocol, porting, flashing and ground-rules documentation.

## Golden rules

- Kotlin `android/core` has ZERO `android.*` imports and >= 80% line coverage (TDD against fixtures).
- `android/app` is a dumb shell (render + forward keys, no protocol logic).
- C++ shim code never allocates per pixel write and never blocks the app longer than the USB TX buffer allows.
- Every code file <= 900 lines.
- No stubs in production paths.
- Label every hardware result [REAL], test-only [TEST], reasoning [UNVERIFIED].

## Toolchain facts

- PlatformIO 6.x with `espressif32@6.12.0` (arduino-esp32 2.0.17), board `m5stack-stamps3`.
- Libs: M5Unified ^0.2.20 / M5Cardputer ^1.1.1 / M5GFX 0.2.27 (vendored copy exists at `/Users/fcavalcanti/dev/m5/cardputter-pense-pem/.pio/libdeps/m5cardputer/`).
- Android: SDK at `~/Library/Android/sdk` (platforms 34/35/36, build-tools 35.0.0 + 36.1.0), JDK = `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (JBR 21), `adb` at `/opt/homebrew/bin/adb`, AVD `Medium_Phone_API_36.1` (no USB in the emulator: USB tasks need the real Poco X7 Pro over WIRELESS adb because its USB-C is on the ESP).

## Hardware clause

Used by every hardware task: if the Cardputer ADV is not attached (`ls /dev/cu.usbmodem*` prints nothing), do every build-only step, append a `UAT:` line to progress.txt describing exactly what a human must confirm on hardware, and still mark the task passed.
