# DROIDPUTTER

Plug an ESP32-S3 into an Android phone over USB-OTG, open one app, and the
phone becomes the Cardputer: the ESP runs the app, the phone IS its screen,
keyboard, GPS and app launcher. The ESP needs no display or keyboard of its
own — a bare ESP32-S3 (the Cardputer's StampS3 class, 8 MB flash) is the
target; a real Cardputer ADV is used in dev only because its own TFT gives
ground truth next to the phone.

Proven on hardware (2026-09-02 → 2026-09-16, see [progress.txt](./progress.txt)
and the board matrix below): a patched M5GFX 0.2.27 / M5Cardputer 1.1.1
**shim** tees every pixel write over USB-CDC and merges KEY frames injected
from the phone. Any open-source Cardputer app, rebuilt against the shim
UNCHANGED, becomes a transparent phone mirror — no per-app protocol code.
Bruce firmware is **not** part of this project (never a dependency); see the
appendix in [SPEC.md](./SPEC.md) for why it's prior art only.

## Demo

Pense-Bem (an unmodified third-party Cardputer app) played entirely from the
phone — soft keyboard tap driving the ESP, screen mirrored live:

| | |
|---|---|
| ![first render](docs/img/phone-first-render.png) | ![live gameplay](docs/img/phone-operacao-live.png) |
| ![soft keyboard](docs/img/phone-adicao-softkeys.png) | ![demo replay](docs/img/demo-replay.png) |

The same, with no display anywhere but the phone (2026-09-16): a bare
ESP32-S3-N16R8 devkit running wisnc/stellar-map built on demand from GitHub,
and a StickS3 with its own screen dark playing Pense-Bem:

| | |
|---|---|
| ![bare S3 devkit, stellar-map](docs/img/phone-bare-s3-devkit-stellar-2026-09-16.png) | ![StickS3, Pense-Bem](docs/img/phone-sticks3-pensebem-2026-09-16.png) |

A 30 s screen recording exists at `docs/img/phone-demo.mp4` (git-ignored,
6.4 MB — regenerate with `adb shell screenrecord` per
[progress.txt](./progress.txt)'s end-to-end entry rather than pulling it from
git).

## How it works

- **Wire protocol** — [docs/PROTOCOL.md](./docs/PROTOCOL.md): framing,
  frame types (HELLO/FILL/RECT/RECT_RLE/STATS/PING, KEY/GPS_NMEA/HELLO_ACK),
  bandwidth budget, measured throughput.
- **Shim mechanism** — [shim/README.md](./shim/README.md): which functions
  are patched and why, the overlay recipe, the virtual no-display panel
  (`Panel_Droidputter`), version pins.
- **Fixtures** — [fixtures/README.md](./fixtures/README.md): captured real
  USB streams committed to the repo and replayed by both the Kotlin test
  suite and the app's offline demo mode.

## Build a shim overlay for your own app

Full recipe: [docs/PORTING.md](./docs/PORTING.md). Short version — three
ingredients on top of your app's unmodified source:

```ini
[platformio]
src_dir = /path/to/the/unmodified/app/src
lib_extra_dirs = ../../shim/lib

[env:m5cardputer]
lib_deps =
    m5stack/M5Unified@0.2.20
    symlink://../../shim/lib/DroidputterShim
build_flags =
    -DDROIDPUTTER=1
    -I ../../shim/lib/DroidputterShim/src
```

```sh
shim/apply.sh apps/<your-app> /path/to/m5cardputer/libdeps   # materialises patched lib/M5GFX + lib/M5Cardputer
cd apps/<your-app> && pio run -e m5cardputer
```

See `apps/pense-bem/` (a private app, unmodified) and `apps/m5-example/`
(the M5Cardputer library's own upstream example) for two working overlays.

## Build on demand and flash from the phone

Nothing is pre-built or hosted. In the app's Catalog:

1. **Pick the target**: `bare ESP32-S3` (the phone is the only screen) or
   `Cardputer ADV` (the board's own TFT is teed to the phone).
2. **Build**: tap "Build mirror version" on any entry with a GitHub source, or
   paste any `owner/repo` into "Build any GitHub repo". The phone asks the build
   proxy (`proxy/`, a few Vercel functions holding a repo-scoped token), which
   dispatches [`.github/workflows/build-app.yml`](./.github/workflows/build-app.yml):
   `tools/overlay.py` clones the app, generates the shim overlay, PlatformIO
   builds it on a GitHub runner (~2-4 min, cached for a day), and the proxy
   streams the parts out of the run's artifact. No Mac, no hosted binaries.
3. **Flash from phone**: the app resets the ESP32-S3 into its ROM bootloader
   over USB-OTG (its own esptool: SLIP, SYNC, compressed writes, MD5 verify),
   writes the four parts, hard-resets, and relinks within ~300 ms. A firmware
   whose USB is a software CDC (UiFlow2, MicroPython) cannot be reset that way
   the first time: hold BOOT while replugging, once.
4. **Verdict**: 20 s after the flash the app decides works / broken from the
   link evidence (boot log, HELLO, frames) or asks you; either way one tap files
   a GitHub issue through the proxy, and an Action folds it into
   [apps/verdicts.json](./apps/verdicts.json), which every phone reads back.

The Cardputer ADV recipes under `apps/` and the LauncherHub feed (prebuilt
M5Burner binaries, flash-only, no mirror) are the other two catalog sources.
Manual flashing uses the same parts and offsets:

| File             | Offset  |
|------------------|---------|
| `bootloader.bin` | `0x0`   |
| `partitions.bin` | `0x8000`|
| `boot_app0.bin`  | `0xE000`|
| `firmware.bin`   | `0x10000`|

Details: [docs/FLASHING.md](./docs/FLASHING.md); the recipe index is
[apps/catalog.json](./apps/catalog.json) (`python3 tools/make_catalog.py`).

## Tested boards and phones

Every row is a hardware result from the journal (`progress.txt`), with the date it was last seen working. "Flash from phone" = the
Droidputter app wrote and md5-verified the parts itself over USB-OTG; "link" = the shim's HELLO arrived and frames flowed to
the phone's screen. Build env = the PlatformIO env the proxy builds (`m5cardputer` = the board's own TFT is teed to the phone,
`m5cardputer-virtual` = no display driver at all, the phone is the only screen).

| Board | Chip / module | Build env | Flash from phone | Link + screen + keys | Last [REAL] | Notes |
|---|---|---|---|---|---|---|
| M5Stack Cardputer ADV | ESP32-S3 (StampS3, no PSRAM) | `m5cardputer` | yes (13 s for 1.1 MB, compressed) | yes, plus GPS feed | 2026-09-05 | Desk oracle: its own TFT shows the same frames. 19 recipes + any GitHub Cardputer app via the proxy. |
| M5Stack StickS3 | ESP32-S3-PICO-1 (octal PSRAM) | `m5cardputer-virtual` | yes (8 s for 470 KB) | yes, own screen dark | 2026-09-16 | Pense-Bem and stellar-map played from the phone. First flash over a UiFlow2/MicroPython firmware needs BOOT held while replugging (software CDC ignores the DTR/RTS reset); afterwards the phone resets it alone. |
| Bare ESP32-S3-N16R8 devkit (ESP32-S3-DevKitC-1) | ESP32-S3-WROOM-1 (octal PSRAM) | `m5cardputer-virtual` | yes (15 s for 1.07 MB) | yes, no display at all | 2026-09-16 | stellar-map built by the proxy from GitHub, star map on the phone. Use the USB-labeled port (native USB-Serial/JTAG), never the COM/UART bridge port (shows as "USB Single Serial"). |
| ESP32-C5-DevKitC-1 | ESP32-C5 (RISC-V) | — | refused | — | 2026-09-16 | The phone flasher reads the chip magic (0x30e1706f) and stops before writing: S3 only. |
| LilyGO / any board on a CH9102, CH343 or CP210x bridge | ESP32 or ESP32-S3 | — | no | no | 2026-09-05 (tried) | The shim links over the S3's native USB-Serial/JTAG only; a UART bridge never carries it, and a classic ESP32 cannot run the S3 build. |

| Phone | OS | Role | Last [REAL] | Notes |
|---|---|---|---|---|
| Poco X7 Pro | Android 16 / HyperOS | screen, keyboard, GPS, flasher | 2026-09-16 | 16 KB USB reads; the link's foreground service keeps GPS flowing with the screen off. Wireless debugging for triage (the USB-C port is the ESP's). |

Rule learned on the StickS3: a virtual build must use the generic `esp32-s3-devkitc-1` variant, the octal-PSRAM memory type and
the M5GFX board hint 26 (`board_M5StickS3`); with the StampS3 variant and the Cardputer ADV hint the same app booted to the
ROM banner and hung before any console. `tools/overlay.py` writes that env for every recipe.

## Repo layout

`shim/` (PlatformIO library: patched M5GFX + M5Cardputer + USB framing),
`tools/` (host-side Python receiver/renderer/catalog scripts), `fixtures/`
(captured real streams, committed), `apps/` (build recipes per app +
`catalog.json`), `android/` (Gradle project: `core` pure-JVM Kotlin +
`app` Android/Compose shell), `docs/`.

Ground rules and golden rules: [docs/GROUND_RULES.md](./docs/GROUND_RULES.md).
Roadmap: [SPEC.md](./SPEC.md).
