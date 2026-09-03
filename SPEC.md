# DROIDPUTTER — Project Spec

> **Name:** **DROIDPUTTER** — plug an ESP32-S3 into an Android phone over USB-OTG, open one app,
> and the phone *is* the Cardputer.

## North Star (built and proven on hardware, 2026-09-02)

> Plug the ESP32 into the Android phone (USB-OTG). Open one app. The ESP runs the app; the phone
> IS its screen, keyboard, GPS and app launcher. The ESP needs no display or keyboard of its own.

The product ESP is a bare ESP32-S3 (the Cardputer's StampS3 class: ESP32-S3, 8 MB flash). A real
Cardputer ADV is used in the spikes because it is in hand and its own TFT gives ground truth next
to the phone, but the target is the bare chip — the phone supplies everything else. The ESP is
always the brain; the phone adds **zero computation**. Bruce firmware is **not** part of this
project — see the [Bruce appendix](#appendix-bruce--prior-art-only) for why it mattered during
research and why it isn't a dependency.

## The mechanism — a patched display + keyboard shim (proven, not a bet)

Early research (finding B below) predicted ESP32 apps are statically linked, so nothing can hook
an arbitrary shipped `.bin`; the only lever is rebuilding an open-source app against a patched
display/input library. That prediction is now a working system:

- **`shim/patches/M5GFX-0.2.27-droidputter.patch`** hooks the 8 `Panel_LCD` write entry points
  (`setWindow`/`writePixels`/`writeBlock`/`writeImage`/`writeFillRectPreclipped`/
  `drawPixelPreclipped`) to tee every pixel write to USB-CDC as FILL/RECT/RECT_RLE frames
  (`docs/PROTOCOL.md`), plus a `Panel_Droidputter` virtual panel (no physical bus at all) so an app
  can run on a board with no display attached.
- **`shim/patches/M5Cardputer-1.1.1-droidputter.patch`** merges KEY frames injected over USB into
  `Keyboard_Class::updateKeyList()`, indistinguishable from a physical keypress, for both the
  GPIO-matrix Cardputer and the TCA8418 Cardputer ADV.
- **`shim/apply.sh`** materializes both patches onto a pristine copy of the libraries into an app's
  `lib/`; the app's own source is never touched. Any Cardputer app that builds against
  M5GFX 0.2.27 / M5Cardputer 1.1.1 gets USB mirroring and keyboard injection for free by rebuilding
  against the shim (`docs/PORTING.md`).

Proven end to end on real hardware with an **unmodified** third-party app (Pense-Bem, spikes S1–S5,
`progress.txt`) and a second, independent app (`apps/m5-example`, the M5Cardputer library's own
`inputText` example, 0-byte diff from upstream).

## What it is — and what it is NOT

**Not an emulator.** An emulator runs firmware on the phone's CPU with no ESP present. This is the
opposite: the real ESP32 runs the real firmware and the phone adds zero computation — it is pure
I/O. The ESP is always in the loop.

**Not a VNC/mirror-only tool.** It also **lends the ESP hardware it lacks** — GPS, a screen,
a keyboard — over the same USB-CDC link the display/keyboard tee already uses (`docs/PROTOCOL.md`
GPS_NMEA frames, `shim/lib/DroidputterShim/src/dp_gps.*`).

> **A USB peripheral dock for a headless ESP32.** The phone is the ESP's borrowed head (embedded
> boards with no display are "headless" — the phone is a detachable head) *and* its sensor pack.
> The ESP is always the brain.

**Hard requirement:** an ESP must be connected over USB-OTG. No ESP → not a Cardputer, just a
phone. Wireless (WiFi/BLE) transports and running the phone *as* the computer with no ESP are
explicit non-goals (see Non-goals).

## Target hardware

**M5Stack Cardputer ADV** — ESP32-S3FN8, 8 MB flash, 1.14" 240×135 IPS (ST7789V2), 56-key (4×14
QWERTY), BMI270 6-axis IMU, microSD, no onboard GPS, no LoRa. Used in the spikes for its own TFT as
ground truth. **M5Stack StickS3** — same ESP32-S3 family, built for `apps/m5-example`
(`env:m5stack-sticks3`, `env:m5stack-sticks3-virtual`). **Bare ESP32-S3 devkit / StampS3-class
chip** — the actual product target: no display, no keyboard, proven via `Panel_Droidputter`
(virtual panel, `env:esp32-s3-devkitc-1-virtual`). **ESP32-C5** — attempted, blocked by a real
toolchain gap: `espressif32@6.12.0` (arduino-esp32 2.0.17, this repo's pin) has no C5 board
definitions; arduino-esp32 3.x is required (`progress.txt`, 2026-09-03 task).

## Capability reality — corrected against hardware (2026-09-02/03)

| Capability over USB-OTG | Status | Notes |
|---|---|---|
| ESP runs the app | ✅ real | ESP32-S3 is the brain; the phone changes nothing here. |
| Power the board from the phone | ✅ real | Phone sources VBUS over OTG (S4a/S4b, `progress.txt`); watch weak/contended OTG current. |
| Keyboard/control → ESP | ✅ real | Injected KEY frames merge into `Keyboard_Class` on real hardware (S3), indistinguishable from a physical press. |
| **Screen → phone over USB** | ✅ real, proven S2 | This was the open caveat in the pre-hardware research (finding A/B below): "needs firmware assist." The shim tee **is** that firmware assist — `RECT`/`RECT_RLE` frames stream over USB-CDC today, no WiFi involved. Measured numbers below. |
| GPS → ESP as NMEA | ✅ real | `dp_gps.h` ring + `droidputter_gps()` Stream; proven end to end with TinyGPSPlus (`apps/gps-demo`, task 16). |
| Both ESP radios (WiFi + BLE) free for the app | ✅ real | The control/display/keyboard/GPS link is 100% USB-CDC; nothing seizes a radio. |
| Arbitrary M5Burner `.bin` (unmodified) | ❌ not possible | Still true (finding B) — ESP32 static linking forecloses hooking a shipped binary. |
| Any open-source app, **rebuilt against the shim** | ✅ real, proven twice | Pense-Bem (unmodified source) and `apps/m5-example` (unmodified upstream example) both mirror+inject transparently after a shim rebuild. |

The old table in this spec said the screen leg "needs firmware assist" and pointed at Bruce's WiFi
WebUI as the only proven screen path. That was true before hardware; it is superseded. The shim
*is* the firmware assist, it runs over the same USB cable as everything else, and it works with
unmodified app source.

## Measured [REAL] — spikes S2/S3/S5, Cardputer ADV + Pense-Bem (unchanged app) + Mac receiver

Full detail and worked byte examples in `docs/PROTOCOL.md`; summary:

| what | value |
|---|---|
| full-frame push (mostly-black UI) | one `RECT_RLE` of 6,227–11,315 B (vs 64,800 B raw) |
| idle stream (blink redraws) | ~1 frame/s, 6–18 KB/s |
| key → first draw on host (S3) | 33.0 ms (`3`), 36.1 ms (Enter) |
| S5 raw full-frame ceiling, 32 KB TX ring | 13.8–14.2 fps, 873–874 KB/s |
| S5 TX-buffer sweep | 8 KB ring → 11.0 fps / 721 KB/s · 32 KB ring → 14.2 fps / 874 KB/s · 64 KB ring → 14.7 fps / 880 KB/s |
| decision (`docs/PROTOCOL.md`) | RECT_RLE not required to clear 8 fps even at the smallest ring; shim ships a 32 KB default TX buffer |
| phone (not Mac) end-to-end, 2026-09-02 21:30/22:00 passes | soft on-screen keyboard drives Pense-Bem live over `usb-serial-for-android`; HELLO decoded 161 ms after permission grant |

Bandwidth budget: a full 240×135 RGB565 frame is 64,800 B; ESP32-S3's USB-CDC is full-speed
USB (12 Mbit/s), realistic 300–880 KB/s measured — full-frame redraws land at 11–15 fps,
partial (dirty-rect) redraws are what makes a text/UI app feel instant, matching the pre-hardware
estimate in finding C almost exactly.

## Architecture

- **`shim/`** — PlatformIO library `DroidputterShim`: patched M5GFX + patched M5Cardputer keyboard
  + USB framing (`dp_frame`, `dp_rle`, `dp_display`, `dp_keys`, `dp_gps`), all natively unit-tested
  (`shim/test/`, `pio test -e native`).
- **`tools/`** — host-side Python receiver/renderer/capture tools (`dp_receiver.py`) and the
  catalog generator (`make_catalog.py`).
- **`fixtures/`** — captured real USB streams, committed, replayed by both the Python receiver and
  the Kotlin `FixtureTransport` test/demo path.
- **`apps/`** — build recipes for rebuilt apps (`pense-bem`, `m5-example`, `gps-demo`) plus
  `catalog.json`, the droidputter-ready build manifest.
- **`android/`** — Gradle project: `core` (pure-JVM Kotlin, zero `android.*` imports, ≥80% line
  coverage — framing, message decoding, screen model, key map, NMEA synth, link state machine,
  transport) + `app` (dumb shell: USB-OTG transport, Compose renderer, soft keyboard, connection
  screen, catalog screen, GPS feed).
- **`docs/`** — `PROTOCOL.md` (wire format), `PORTING.md` (how to bring a third app), `FLASHING.md`
  (phone-only flashing via ESP32_Flasher + the catalog share sheet), `GROUND_RULES.md`.

## Wire protocol

Full spec in [`docs/PROTOCOL.md`](./docs/PROTOCOL.md): sync `0xD7 0x50`, type u8, length u16 LE,
payload, crc8. ESP→phone: HELLO, FILL, RECT, RECT_RLE, STATS, PING. Phone→ESP: KEY, GPS_NMEA,
PING, HELLO_ACK.

## Research findings — verified from source (2026-09-02, pre-hardware)

These four findings were read from the actual upstream source before any hardware spike ran. They
shaped the design and are kept here as the record of *why* the shim approach was chosen; where they
predicted something hardware later confirmed or corrected, that is noted inline.

### B. M5GFX / LovyanGFX shim — the "any app" bet [CONFIRMED by hardware]

**"Transparent mirror for ANY M5Burner app" is not achievable.** ESP32 is a single statically-linked
binary — no dynamic linker, no `LD_PRELOAD`, no interposition. `M5GFX::init_impl()` calls
`autodetect()` unconditionally and overwrites any panel injected before `M5.begin()`; keyboard is a
per-app GPIO matrix scan. You cannot hook an existing binary.

**What IS real: "transparent mirror for any open-source app you rebuild against a patched M5GFX."**
`Panel_LCD`'s write methods are `virtual` — subclass/patch to tee the pixel stream; rebuild the app
against the patched lib, app source unchanged. This is exactly what `shim/` does, proven on two
independent apps (see above). Input injection rides the same rebuild (`Keyboard_Class` patch).
- Source: lovyan03/LovyanGFX `Panel_LCD.cpp`, `Bus_SPI.hpp`, `Panel_Device.hpp`; m5stack/M5GFX
  `M5GFX.cpp`; m5stack/M5Cardputer `IOMatrix.cpp`.

### C. Flipper protobuf RPC — protocol considered, not adopted

Flipper's varint-length-delimited `PB.Main` envelope and `InputKey`/`InputType` enums were
considered as prior art for a semantic (non-pixel) control channel. Droidputter's actual wire
format (`docs/PROTOCOL.md`) is a simpler fixed 6-byte-header + crc8 framing, closer to a raw
tee than an RPC envelope — pixels are the payload, not menu semantics, since the shim mirrors
whatever the app draws rather than reimplementing its UI. The bandwidth math these findings
predicted held up almost exactly: 240×135 RGB565 = 64,800 B/frame predicted vs. 64,800 B/frame
measured (S5); Flipper's 128×64 mono comparison (1,024 B/frame) is roughly 63× smaller, matching
the "~16×/pixel, ~63×/frame" estimate.
- Source: flipperdevices/flipperzero-protobuf `gui.proto`/`flipper.proto`.

### D. Hardware / USB-OTG / GPS [VERIFIED, confirmed on hardware]

- **USB driver path is clean:** the Cardputer ADV, StickS3 and StampS3-class chips all use ESP32-S3
  native USB (CDC-ACM, VID `0x303A`) — no bridge chip. `mik3y/usb-serial-for-android` binds it with
  a custom `ProbeTable` entry (`android/app/.../UsbDpTransport.kt`), confirmed live on the Poco X7
  Pro (S4a, task "USB-OTG transport"). Native USB re-enumerates on ESP reset; the app treats serial
  as unreliable and reconnects (`LinkStateMachine`, `UsbLinkManager`).
- **GPS:** Android goes silent on fused/network location; the app forces `GPS_PROVIDER` /
  `addNmeaListener` and falls back to synthesizing GGA/RMC from a `Location` after 3 s of silence
  (`NmeaSynth`, `GpsFeed.kt`), and normalizes `$GN`→`$GP` talker IDs (`NmeaNormalizer`) — all
  exactly as this finding predicted, now shipped and tested.

### E. bmorcelli's "Launcher" — not used

Considered as a possible boot-menu/catalog layer; not adopted. Droidputter's own catalog
(`apps/catalog.json`, the in-app Catalog screen + ESP32_Flasher share-sheet hand-off) covers the
same need without depending on a second firmware.

## Roadmap

- **M1 — Shim + USB app** *(this ledger, built)*. The patched M5GFX/M5Cardputer shim streams
  display + keyboard over USB-CDC (spikes S1–S5, `shim/` native-tested framing/RLE/keys/GPS
  modules, `Panel_Droidputter` virtual panel); the Android app (`:core` + `:app`) renders the
  framebuffer, drives a soft 4×14 keyboard + hardware-keyboard passthrough, and holds the USB-OTG
  link. Proven end to end with Pense-Bem, both on the Cardputer ADV's real panel and on the virtual
  (display-less) panel.
- **M2 — Catalog + native flasher.** `apps/catalog.json` (droidputter-ready builds, sha256'd bin
  parts) plus the in-app Catalog screen ship today via a share-sheet hand-off to ESP32_Flasher (no
  PC involved, proven S4a/S4b). A native in-app flasher (no third-party app required) is the open
  remainder of this milestone.
- **M3 — GPS / peripherals through the shim.** `dp_gps.h` + `droidputter_gps()` Stream and the
  phone-side `NmeaSynth`/`GpsFeed` are built and hardware-proven (`apps/gps-demo` + TinyGPSPlus).
  Further peripherals (time sync, notifications, phone-as-uplink) are the open remainder.
- **M4 — More boards / more libraries.** StickS3 built (`env:m5stack-sticks3[-virtual]`); bare
  ESP32-S3 devkit proven via the virtual panel; ESP32-C5 blocked on an arduino-esp32 3.x toolchain
  bump (real gap, not a design limitation). Open: a TFT_eSPI-based shim (Bruce and many other
  Cardputer apps use TFT_eSPI, not M5GFX/LovyanGFX — same tee pattern, different library to patch)
  and LovyanGFX-only apps that don't go through M5GFX.

## Open questions

- **Native flasher vs. share-sheet hand-off** — worth building in-app esptool-over-USB (M2), or is
  "share to ESP32_Flasher" (already proven, S4b) good enough long-term?
- **TFT_eSPI shim** — same tee pattern as the M5GFX patch, different library; needed to bring in
  Bruce and other TFT_eSPI-based Cardputer apps as catalog entries without their own firmware.
- **iOS later?** Android is the first target; keep `:core`'s protocol/model layer free of platform
  assumptions so a second client isn't a rewrite.
- **Name & trademark:** avoid "Cardputer" in any public name; M5Stack owns that trademark.

## Non-goals

- **LoRa** — the Cardputer ADV has none; nothing to mirror.
- **Wireless (WiFi/BLE) display mirroring** — considered in early research, not built; USB-OTG is
  the only supported transport (see Capability reality table).
- **Bruce firmware as a dependency** — see the appendix below. It is prior art, and a possible
  future catalog entry once a TFT_eSPI shim exists; it is never a build requirement for this repo.
- **Running the phone as the computer with no ESP** — a different project (the "Phoneputer" /
  Linux-on-phone camp).

## Security / scope

A UI/peripheral bridge for hardware the operator already owns and flashed — it streams pixels and
forwards keypresses/GPS over a cable the user physically plugged in; it is not an attack tool.
Private until Felipe decides otherwise.

---

## Appendix: Bruce — prior art only

Early research (before any droidputter hardware existed) looked at **pr3y/Bruce**, a pentest
firmware for Bruce-capable ESP32 boards, as a possible zero-firmware-change MVP path: its WebUI
"Navigator" feature streams a serialized TFT draw-command log over WiFi/HTTP polling
(`GET /getscreen`, `POST /cm`) and can be watched/driven from a browser with no firmware change.
That path was **not built** — droidputter's actual mechanism (the M5GFX/M5Cardputer USB shim,
above) supersedes it: it works over USB instead of WiFi (freeing both ESP radios for the app, per
finding D), works with any open-source app rebuilt against the shim rather than only Bruce, and is
proven on real hardware today.

Bruce remains relevant only as:
1. **Prior art for the tee pattern** — Bruce's own `tft_logger` (TFT_eSPI) proves the same
   "intercept the display driver's write calls" idea on a different graphics library.
2. **A possible future catalog entry** — Bruce uses TFT_eSPI, not M5GFX/LovyanGFX, so it needs a
   TFT_eSPI shim (open question, M4) before it could be rebuilt against droidputter like Pense-Bem
   was.

It is never a dependency, milestone, or reference implementation for this repo
(`docs/GROUND_RULES.md`).

### A. Bruce WebUI protocol — considered, not used [VERIFIED from source, historical]

Bruce's screen mirror ("Navigator") is not a framebuffer/JPEG/MJPEG stream — it's a serialized TFT
draw-command log (`0xAA` sync, `int8 size`, `int8 fn`, big-endian int16 params, RGB565 colors) over
plain HTTP GET polling, replayed onto an HTML canvas by a JS interpreter. Input is `POST /cm` with
`cmnd=nav up|down|...`. This was evaluated as a possible Tier-1 MVP path requiring zero firmware
change; droidputter instead built its own USB-CDC framing (`docs/PROTOCOL.md`) that works with any
app, not just Bruce.
- Source: `src/core/wifi/webInterface.cpp`, `embedded_resources/web_interface/index.js`
  (pr3y/Bruce); https://wiki.bruce.computer/controlling-device/webui/

### E (continued) — bmorcelli's Launcher, historical detail

The Launcher is a resident boot menu that flashes-and-boots separate firmware `.bin` images into
OTA partitions — not an in-process runtime, and it has no screen-mirror or nav-input endpoint of
its own (`src/webInterface.cpp` is a firmware/file manager only). Its relevance was "would this
save us from writing a catalog" — answer no, so droidputter built its own (`apps/catalog.json` +
Catalog screen, M2).
- Source: bmorcelli/Launcher `src/webInterface.cpp`, `webUi/scripts.js`.
