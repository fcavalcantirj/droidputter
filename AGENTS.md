# AGENTS.md — droidputter

Product: plug an ESP32-S3 into an Android phone over USB-OTG, open one app, phone becomes the Cardputer (screen, keyboard, GPS, launcher). ESP runs the app, phone adds zero computation. Bruce firmware is NOT a dependency, milestone, or reference implementation here — appendix-only prior art in SPEC.md.

Mechanism (proven on hardware, progress.txt S1-S5 [REAL]): patched M5GFX 0.2.27 / M5Cardputer 1.1.1 shim (`shim/patches/*.patch`, `shim/lib/DroidputterShim`) tees pixel writes over USB-CDC, merges KEY frames from the phone. Any open-source Cardputer app rebuilt against it works unchanged. Wire protocol: `docs/PROTOCOL.md`.

READ FIRST: `spec.json` (task ledger, top-to-bottom priority), `progress.txt` (build journal), `docs/PROTOCOL.md`, `docs/GROUND_RULES.md`, `fixtures/README.md`, `shim/apply.sh`, `apps/pense-bem/platformio.ini`.

## Repo layout

- `shim/` — PlatformIO library DroidputterShim: patched M5GFX + patched M5Cardputer keyboard + USB framing + native (host) tests. `shim/patches/` holds diffs, not library copies; `shim/apply.sh <app-dir> [<libdeps-dir>]` materialises `apps/*/lib/M5GFX` and `apps/*/lib/M5Cardputer` (git-ignored, never edit those copies — edit the patches).
- `tools/` — host-side Python: `dp_receiver.py` (serial receiver/decoder/PNG renderer), `make_catalog.py` (regenerates `apps/catalog.json`).
- `fixtures/` — captured real USB streams, committed, replayed by Kotlin tests and the app's offline demo mode.
- `apps/` — build recipes for rebuilt apps (`pense-bem`, `m5-example`, `gps-demo`) plus `catalog.json`. Pense-Bem is built UNCHANGED from `/Users/fcavalcanti/dev/m5/cardputter-pense-pem` via `[platformio] src_dir` — never modify that external repo.
- `android/` — Gradle project: `core` (pure-JVM Kotlin, zero `android.*` imports, protocol/framebuffer/keymap/NMEA/link-state logic, TDD against fixtures) + `app` (Android/Compose, dumb shell: render + forward keys, no protocol logic).
- `docs/` — `PROTOCOL.md`, `PORTING.md`, `FLASHING.md`, `GROUND_RULES.md`.

## Golden rules

- `android/core`: zero `android.*` imports, >= 80% line coverage.
- `android/app`: dumb shell only, no protocol logic.
- C++ shim code: never allocates per pixel write, never blocks the app longer than the USB TX buffer allows.
- Every code file <= 900 lines. No stubs in production paths.
- Label every hardware result `[REAL]`, test-only `[TEST]`, reasoning `[UNVERIFIED]`.

## Hardware clause

If required hardware is absent (e.g. `ls /dev/cu.usbmodem*` prints nothing for the Cardputer ADV; Poco X7 Pro needs wireless adb since its USB-C port is on the ESP), do every build-only step, append a `UAT:` line to `progress.txt` naming exactly what a human must confirm, and still pass the task. Never weaken, skip, or sandbox-attest a gate. When stuck: journal the exact command + error, leave the task open, stop for a human.

## Gates

- Shim native tests: `cd shim && pio test -e native` (dp_frame + dp_rle round-trip/resync/oversize tests).
- App overlay build: `cd apps/pense-bem && pio run -e m5cardputer`.
- Android tests + coverage: `cd android && ./gradlew :core:test :app:assembleDebug` then `./gradlew :core:koverVerify` (>= 80% line coverage on `:core`).
- Static gate: `android/gates.sh` (asserts 0 `android.*` imports in `core/src`).
- No per-write allocation: `grep -rn 'malloc\|new ' shim/lib/DroidputterShim/src/dp_display.cpp | grep -v '//' | wc -l` prints 0.
- Fresh-clone build gate: clone to a temp dir, run the Gradle + shim + app chain above end to end (network allowed for PlatformIO platform download).
- Catalog check: `python3 tools/make_catalog.py --check`.

## Coordination

Solvr room `droidputter` — use it to coordinate with other agents/sessions working this repo (status, blockers, hardware availability handoffs).
