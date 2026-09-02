# DROIDPUTTER wire protocol v0 (ESP32 ⇄ phone over USB-CDC)

Implemented in `shim/lib/DroidputterShim/src/droidputter.cpp` (ESP) and `tools/dp_receiver.py` (host).
The ESP runs the app; the phone is only screen + keyboard + sensors. **Status: proven on hardware 2026-09-02** (Cardputer ADV, Pense-Bem unchanged, Mac receiver) — see `Measured`.

## Framing (both directions)

```
D7 50 | type u8 | len u16 LE | payload[len] | crc8
```
crc8 = poly 0x07, init 0, over `type + len(2 bytes) + payload`. A receiver that loses sync scans for
the next `D7 50` whose crc validates; anything between valid frames is treated as text (the app's own
`Serial` prints share the pipe — Pense-Bem's `[F]`/`[K]` debug lines are visible in the receiver).
Max payload = 65535 (a full 240×135 RGB565 frame is 64,800 B + 8).

## ESP → phone

| type | name | payload |
|---|---|---|
| 0x01 | HELLO | proto u8 (0), w u16, h u16, rotation u8, bpp u8 (16), board char[16], app char[32] (NUL padded). Sent at start and on every HELLO_ACK. |
| 0x02 | FILL | x,y,w,h u16, color 2 bytes in **panel wire order** (big-endian RGB565 as the ST7789 receives it) |
| 0x03 | RECT | x,y,w,h u16, then w·h·2 pixel bytes, row-major, panel wire order |
| 0x04 | RECT_RLE | x,y,w,h u16, then runs of `count u8 (1..255), color 2 bytes`; emitted only when shorter than RECT and ≤ 32 KB |
| 0x05 | STATS | frames u32, bytes u32, dropped u32, freeHeap u32 — once per second |
| 0x06 | PING | reply to PING_IN |

## phone → ESP

| type | name | payload |
|---|---|---|
| 0x81 | KEY | row u8, col u8, state u8 (1 down, 0 up). Cardputer physical matrix: row 0 top (`` ` `` 1 2 … 0 - = del), row 1 (tab q … \), row 2 (fn shift a … ' enter), row 3 (ctrl opt alt z … / space); col 0 leftmost. Maps to `M5Cardputer.Keyboard` `Point2D_t{x=col, y=row}`; the shim merges held keys into the key list every `updateKeyList()`. |
| 0x82 | GPS_NMEA | one NMEA sentence without CRLF (reserved; shim support is a ledger task) |
| 0x83 | PING_IN | — |
| 0x84 | HELLO_ACK | phone w u16, h u16. Marks the link up; ESP re-sends HELLO. |

## Where the pixels come from (the shim)

Patched LovyanGFX `Panel_LCD` (M5GFX 0.2.27, `shim/patches/M5GFX-0.2.27-droidputter.patch`):
`setWindow` → `dp::window`; `writeFillRectPreclipped` → FILL; `drawPixelPreclipped` → 1×1 FILL;
`writeBlock` → `dp::repeat`; `writePixels` → `dp::bytes` (no-convert) / `dp::pixelsConv` (runs the
`pixelcopy_t` converter on a copy); `write_bytes` and the DMA row queue → `dp::bytes`. Pixels accumulate
in a 64,800 B window buffer and flush as one RECT/RECT_RLE when the window is full or a new window opens,
so an M5Canvas `pushSprite(0,0)` becomes exactly one frame.

Patched `M5Cardputer` 1.1.1 (`shim/patches/M5Cardputer-1.1.1-droidputter.patch`): `Keyboard_Class::updateKeyList()`
calls `dp::poll()` and appends injected keys via a new `KeyboardReader::mutableKeyList()`.

USB: HWCDC `Serial` (ESP32-S3 USB-Serial/JTAG, `0x303A:0x1001`), TX ring 32 KB, write budget 40 ms per
frame — a frame that cannot be queued in time is dropped whole (counted in STATS.dropped) and the phone
catches up on the next full redraw.

## Budget

240×135 RGB565 = 64,800 B per full frame. USB full-speed ≈ 12 Mbit/s; realistic CDC 300–800 KB/s →
5–12 full frames/s raw; RLE on mostly-flat screens and dirty-rect apps do far better. Measured values go
in the `Measured` table below once S2/S5 run.

## Measured [REAL] — 2026-09-02, Cardputer ADV + Pense-Bem (unchanged app) + Mac receiver

| what | value |
|---|---|
| HELLO | 240×135, rotation 1, 16 bpp, board `cardputer-adv` |
| full-frame push (M5Canvas `pushSprite(0,0)`) | one `RECT_RLE` of **6,227 B** (vs 64,800 raw; Pense-Bem is mostly black) |
| idle stream (blink redraws) | ~1 frame/s, 6–18 KB/s |
| key → first draw on host | **33.0 ms** (`3`), **36.1 ms** (Enter) — measured by `tools/dp_receiver.py` |
| ESP STATS after 17 s | frames 61, bytes 301,479, dropped 43 (all during boot before HELLO_ACK; 0 afterwards), free heap 160,708 B |
| framing errors on host | 1 (boot text / partial first frame) |
| render fidelity | `fixtures/pense-bem/screen-after-keys.png` = the TFT content, colors correct (panel byte order = big-endian 565 confirmed) |

**S5 raw ceiling [REAL]:** 30 uncompressed full frames (RECT 64,808 B, RLE-defeating pattern, `-DDROIDPUTTER_BENCH=1`) in 2,103 ms → **13.8 fps, 873 KB/s** sustained over HWCDC with a 32 KB TX ring. Worst case for any app; partial redraws and RLE scale from there.

**S4 flash-from-phone [REAL]:** Poco X7 Pro (Android 16) + ESP32_Flasher over USB-C OTG flashed the same 4 parts (auto-bootloader, stub, 504 KB firmware in 19.4 s). The phone enumerates the Cardputer as `0x303A:0x1001` "USB JTAG/serial debug unit", port `dfp`/source/host. No PC involved.
