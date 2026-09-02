# DROIDPUTTER wire protocol v0

ESP32-S3 <-> Android phone, over USB-CDC (the ESP's native USB-Serial/JTAG,
`HWCDC Serial` in Arduino terms), 115200 8N1 framing at the UART-emulation
layer (irrelevant to throughput — USB-CDC ignores the baud rate; real ceiling
is USB full-speed, see Bandwidth below).

This document is the byte-exact source of truth for `shim/lib/DroidputterShim/src/droidputter.cpp`
(ESP side, ships today) and `tools/dp_receiver.py` (host side, ships today).
Every field below was cross-checked against a real captured stream,
`fixtures/pense-bem/boot.bin`/`boot.jsonl` (Cardputer ADV running the S2/S3
shim build, 2026-09-02) — see the worked examples.

## Framing (both directions)

```
sync  2 B   0xD7 0x50               ('DP')
type  1 B   message type, see tables below
length 2 B  u16 little-endian, payload byte count (NOT including sync/type/length/crc)
payload N B
crc8  1 B   poly 0x07, computed over type + length + payload (5 header bytes' last 3 + payload)
```

`crc8(c, byte)`: `c ^= byte; repeat 8: c = (c & 0x80) ? (c<<1)^0x07 : (c<<1)`, seeded `c=0`,
run over `[type, length_lo, length_hi, payload...]`.

A receiver that loses sync scans forward for the next `0xD7 0x50` whose crc8
validates and discards everything before it (both `tools/dp_receiver.py`'s
`Framer` and the ESP's `dp::poll()` do this byte-at-a-time).

Length field is u16, so the wire format itself allows payloads up to 65,535 B.
The ESP's own inbound parser (`droidputter.cpp: poll()`) caps INCOMING
(phone->ESP) frames at 120 B payload (`rx[128]` buffer) — fine, since every
phone->ESP type below is a few bytes. OUTGOING (ESP->phone) frames are not
capped by the ESP and routinely exceed the pre-hardware sketch of "4096 B
max": a full 240x135 screen is 64,800 B of raw pixel data, so a full-frame
RECT payload is 8 (header) + 64,800 = 64,808 B — measured for real in S5
(`progress.txt`, `droidputter_bench()`, 30 such frames sent back to back).
Treat 65,535 B (the u16 ceiling) as the real max payload, not 4096.

## ESP -> phone types

| type | name | payload |
|------|------|---------|
| 0x01 | HELLO | `proto u8=0, w u16, h u16, rotation u8, bpp u8(=16), board char[16], app char[32]` — 55 B total, fixed-size fields, NUL-padded strings |
| 0x02 | FILL | `x,y,w,h u16, color u16` — 10 B, solid-fill rect |
| 0x03 | RECT | `x,y,w,h u16` (8 B header) then `w*h*2` bytes of raw RGB565 pixel data, row-major, in the exact byte order the panel bus receives (big-endian / MSB-first per pixel — confirmed [REAL] against the Cardputer ADV's TFT, `fixtures/pense-bem/screen-after-keys.png`) |
| 0x04 | RECT_RLE | same 8 B header, then runs of `(count u8 <=255, color u16)` = 3 B/run, same big-endian pixel byte order as RECT; emitted only when shorter than RECT (`droidputter.cpp: flushRect`, `rle < raw`) |
| 0x05 | STATS | `frames u32, bytes u32, dropped u32, heap_free u32` — 16 B, little-endian, once per second |
| 0x06 | PING | 0 B payload, reply to phone's PING_IN |

Note on FILL's color field: unlike RECT/RECT_RLE (raw bytes copied unmodified
off the panel bus), `dp::fill()` packs `color` the same way it packs the u16
geometry fields — low byte first (`p[8]=raw&0xFF; p[9]=raw>>8`). This is a
real asymmetry in the current shim (source-verified; no FILL frame appears in
the S2/S3 hardware capture to confirm visually, so treat FILL's byte order as
[UNVERIFIED] on real hardware, vs. RECT/RECT_RLE which are [REAL]). Kotlin
decoders (task: message decoders) must special-case it.

STATS grew a 4th field (`heap_free`) beyond the original 3-field sketch —
kept here because it's what ships and what `progress.txt` S2's "heap 160,708 B
free" line was read from (see worked example below, byte-exact match).

## phone -> ESP types

| type | name | payload |
|------|------|---------|
| 0x81 | KEY | `row u8, col u8, state u8 (1 down / 0 up)` — Cardputer 4x14 physical matrix, row 0 = top row `esc..del`, col 0 = leftmost |
| 0x82 | GPS_NMEA | one NMEA sentence, no CRLF (reserved for the GPS milestone, not sent yet) |
| 0x83 | PING_IN | 0 B payload |
| 0x84 | HELLO_ACK | `w u16, h u16` — phone screen size; ESP replies by re-sending HELLO and (bench builds) starts the throughput test |

`KEY {row, col, state}` down events replace/hold a slot until the matching up
arrives (`droidputter.cpp: onFrame`, a 16-slot held-key table).

## Bandwidth budget

A full 240x135 RGB565 frame is 64,800 B. The ESP32-S3's USB-Serial/JTAG is
USB full-speed (12 Mbit/s); realistic sustained throughput is 300-800 KB/s
over HWCDC, so full-frame redraws land around 5-12 fps while partial
(dirty-rect) redraws are what makes a text/menu app feel instant. S5 measured
13.8 fps / 873 KB/s for raw back-to-back full-frame RECTs at a 32 KB TX ring
(`progress.txt`, `docs/PROTOCOL.md` "Measured" section is filled in by the S5
task). The receiver must tolerate dropped frames — `STATS.dropped` rising is
reported, never fatal; S2 saw 43 dropped frames during boot before the first
HELLO_ACK, then 0 for the rest of the session.

## Worked examples

All bytes below are real, decoded from `fixtures/pense-bem/boot.bin` /
`boot.jsonl` (S2/S3 capture) with `tools/dp_receiver.py`'s `Framer`.

### ESP -> phone: HELLO

```
d7 50 01 37 00  00  f0 00  87 00  01  10  63 61 72 64 70 75 74 65 72 2d 61 64 76 00 00 00  61 70 70 00...00  <crc>
sync  ty len=55 proto w=240 h=135 rot bpp  "cardputer-adv\0\0\0" (16B)                     "app"+29x\0 (32B)
```
`proto=0`, `w=240`, `h=135`, `rotation=1`, `bpp=16`, `board="cardputer-adv"`,
`app="app"` (the shim's `dp::begin()` is called lazily with `app=nullptr` from
the first `setWindow`, so `app_name` stays its default `"app"` — real captured
value, not a placeholder).

### ESP -> phone: RECT_RLE (first run of the boot screen, full 240x135 frame)

```
d7 50 04 <len_lo> <len_hi>  00 00 00 00 f0 00 87 00  ff 00 00  ff 00 00  d7 00 00  05 c6 18 ...  <crc>
sync  ty  len(=9956)         x=0 y=0 w=240 h=135      run(255,#000000) run(255,#000000) run(215,#000000) run(5,#??)...
```
9,956 B payload for a full-screen redraw (vs. 64,800 B raw) — RLE wins big on
the mostly-solid boot background.

### ESP -> phone: STATS

```
d7 50 05 10 00  31 00 00 00  87 d6 03 00  2b 00 00 00  c4 73 02 00  <crc>
sync  ty len=16 frames=49    bytes=251,527  dropped=43     heap_free=160,708
```
`dropped=43` and `heap_free=160,708` are byte-exact against the S2 line in
`progress.txt` ("ESP dropped 43 frames during boot before HELLO_ACK then 0;
... heap 160,708 B free").

### phone -> ESP: HELLO_ACK (Poco X7 Pro screen size)

```
d7 50 84 04 00  38 04 60 09  23
sync  ty len=4  w=1080 h=2400  crc
```

### phone -> ESP: KEY down/up for key '1' at (row=0, col=1)

```
down: d7 50 81 03 00  00 01 01  71
up:   d7 50 81 03 00  00 01 00  76
```
This is the exact KEY pair S3 used to start "Adição" in Pense-Bem
(`progress.txt` S3: "(0,1)->Adicao").

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
