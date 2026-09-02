# DROIDPUTTER — Project Spec

> **Name:** **DROIDPUTTER** — the verb form of Felipe's original idea: **"Cardputer my Android."**
> (Working title; earlier candidates: Androputer, PocketDeck, CardCast, Deckhand.) The concept
> generalizes to any Bruce-capable ESP32, not just M5Stack's Cardputer — "Cardputer" is M5Stack's
> product name, so the public name stays device-neutral.

## North Star (Felipe's product statement, 2026-09-02)

> *"I want to plug an ESP on my Android, install an app, and the Cardputer + ESP32 ecosystem to
> work on my Android."*

The whole product in one sentence. Decoded into the user story we build toward:

1. **Plug** the ESP32 into the Android phone (**USB-OTG** — the right call: wired control leaves both
   radios free for the apps and carries GPS on the same cable).
2. **Install one app** (the universal Androputer client).
3. **The ecosystem just works** — the M5-Launcher catalog and any M5Burner/Cardputer app render on
   the phone, driven by the phone keyboard, GPS fed from the phone.

**What it takes — two pieces, stated honestly:**
- **A) The Android app** — a USB-serial client that renders the streamed screen, maps a soft
  Cardputer keyboard (+ hardware passthrough), and feeds phone GPS as NMEA. *Proven plumbing.*
- **B) A shim-enabled firmware/launcher** — a display+input driver layer (over **M5GFX / LovyanGFX**)
  so **every** app launched through it streams to the phone transparently. This is the crux that
  turns "one Bruce screen" into "the whole ecosystem." **Flashed once.**

**The unification that makes it feel like magic:** the *same* Android app flashes the shim-launcher
onto the ESP over USB on first plug-in (reusing **esp-atlas First-Flash / flash-from-phone**). After
that one-time step: **plug → open app → the entire catalog is on your phone. No PC ever involved.**

---

## One-liner

Turn an **Android phone into the screen, keyboard, and sensor-pack for an ESP32 pocket computer**
(flagship: M5Stack **Cardputer / Cardputer ADV**). Connect the ESP32 (USB / WiFi / BLE); the phone
becomes its display and input — and *feeds it what it lacks* (GPS, internet, time). In one line:
**"the Flipper Zero mobile app, but for the Cardputer."**

## What it is — and what it is NOT (read this first)

**Not an emulator.** An emulator runs the ESP firmware on the phone's CPU with *no ESP present*.
This is the opposite: the real ESP32 runs the real firmware and the phone adds **zero computation**
— it is pure I/O. The ESP is **always** in the loop.

Accurate category, best-fit first:
- **Thin client / remote terminal** — the ESP is the "server" running everything; the phone is a
  dumb display + input front-end.
- **Wireless KVM** (keyboard-video-mouse) — the phone is a detachable **console** for a
  headless-ish board.
- **Companion app** — the loose consumer term (what Flipper calls theirs). Good for the public
  name, vague for the architecture.

But it is **more than a KVM**: a KVM/VNC only *mirrors*; this also **lends the ESP hardware it
lacks** — GPS, internet, a big screen, clock. Tightest definition:

> **A wireless KVM + peripheral dock for an ESP32.** The phone is the ESP's **borrowed head**
> (embedded boards with no display are "headless" — the phone is a detachable head) *and* its
> sensor pack. The ESP is always the brain.

**Hard requirement:** an ESP must be connected — plugged (USB) *or* wireless (WiFi/BLE). No ESP →
not a Cardputer, just a phone. Running the phone *as* the computer with no ESP is a different
project (the "Phoneputer" / Linux-on-phone camp) — an explicit non-goal (see Non-goals).

## Wired mode (USB-OTG) — what works, honestly

The model: *plug an ESP32 (Cardputer ADV specs) into Android over USB-OTG; the ESP runs the apps
while the phone provides screen + keyboard + peripherals.* Correct — with one caveat on the screen.

| Capability over USB-OTG | Status | Notes |
|---|---|---|
| ESP runs the apps (Bruce, M5Launcher catalog) | ✅ real | ESP32-S3 is the brain; the phone changes nothing here. |
| Power the board from the phone | ✅ real | Phone sources VBUS over OTG (watch weak/contended OTG current). |
| Keyboard / control → ESP | ✅ real | `usb-serial-for-android` → ESP32-S3 **native USB (CDC-ACM)**, no bridge chip. |
| GPS → ESP as NMEA | ✅ real | Phone NMEA forwarded on the same cable; Bruce/Marauder read external GPS. |
| Both ESP radios (WiFi + BLE) free for the app | ✅ real | The whole point of wired control — nothing seizes a radio for the link. |
| **Screen → phone over USB** | ⚠️ needs firmware assist | Stock Bruce streams its screen over **WiFi** (HTTP draw-command log), not serial. Piping it down USB needs a firmware path that pushes that log over serial, or the **M5GFX shim**. |

So the wired end-state (**M2**) is "screen + keys + GPS on one cable, radios free" — but the *screen*
leg needs firmware cooperation. **M1** proves the whole idea first over **WiFi with stock,
unmodified Bruce** (the screen already streams there).

## Origin (Felipe, 2026-09-02)

Verbatim, see [README.md](./README.md). The seed: the Cardputer ADV screen is tiny; an iOS remote
controller reportedly exists; **why not an Android app that makes the phone *be* the Cardputer** —
connect the right ESP32, the phone screen acts as the Cardputer screen, and *everything that runs on
the Cardputer minus LoRa should work — including GPS via the phone.*

## Why this is possible — every piece has proven prior art

| Piece | Proven by | What it proves |
|---|---|---|
| Phone as a device's screen + input | **Flipper Zero Mobile App** (open source, iOS+Android, BLE screen-stream + remote input) | The whole pattern ships and scales to a mass audience. |
| ESP/Cardputer screen already streams + takes input | **Bruce WebUI "Navigator"** (WiFi → browser, mirrors screen, keyboard input) | The **ESP side already exists today** on the flagship firmware. Tier-1 MVP needs *no firmware change*. |
| Low-latency mirror on the exact board | **Zeloksa/WiFi-Remote-Display-ADV** (UDP streaming engine, Cardputer ADV) | The streaming stack + latency budget are achievable on this silicon (note: it mirrors PC→Cardputer, the reverse direction). |
| Clean semantic protocol | **Flipper protobuf RPC** (`flipperdevices/flipperzero-protobuf`; clients: flipper-rpc, FlipperUI, flipper-zero-interface) | A battle-tested framing (screen frames + input + file ops over serial/BLE) to adopt rather than invent. |
| Phone GPS → the ESP | **ESP32-GPS-BTserial**, Marauder/ESP32-DIV external-GPS wardriving | NMEA over BT/serial is a solved pattern; the phone can *be* the Cardputer's GPS. |

Full annotated link list in [README.md](./README.md).

## Target hardware (grounded in vendor specs)

**M5Stack Cardputer ADV** — ESP32-S3FN8, 8 MB flash, **1.14" 240×135 IPS (ST7789V2)**, 56-key
(4×14 QWERTY), ES8311 audio codec + MEMS mic + 1 W speaker, IR emitter, **BMI270 6-axis IMU**,
microSD, Grove, WS2812 RGB, 1750 mAh. **No onboard GPS. No LoRa.** (Original Cardputer: same idea,
ESP32-S3, smaller battery, no ES8311.) Secondary target: **any Bruce-capable ESP32**.

The absence of onboard GPS/LoRa is exactly why Felipe scoped it: **GPS comes from the phone**;
**LoRa is out of scope** (no radio to mirror).

## Architecture — three tiers

### Tier 1 — Thin mirror (MVP)
Phone renders the ESP's framebuffer and sends key events back. Speaks the **existing Bruce WebUI
protocol** → works with off-the-shelf Bruce, **zero firmware change**. Honest limitation: 240×135
upscaled to a phone panel looks blocky, and it's pixels-only (no native polish).

### Tier 2 — Companion peripherals
The phone stops being just a screen and becomes a *peripheral provider* to the ESP:
- **GPS** — stream phone NMEA to the ESP's expected GPS input (UART/BT/WiFi) → wardriving & maps
  with no GPS module.
- **Internet uplink** — phone shares connectivity (BT-PAN / it being the ESP's WiFi STA gateway).
- **Clock/timezone, notifications, storage** — phone as the ESP's rich backing services.

### Tier 3 — Semantic UI (the "beautiful" version)
A **companion firmware** (Bruce fork or a purpose-built fw) exposes a **UI-description protocol**
(menus, lists, fields, key map) instead of a bitmap. The phone renders **native, crisp UI** — the
real answer to "make it not look like an upscaled 240×135 screen." Requires firmware cooperation;
this is where the project's lasting value is.

## Transports (pick per tier)

| Transport | Lib / path | Good for | Weak at |
|---|---|---|---|
| **USB-OTG serial** | `usb-serial-for-android` | reliable, powers the ESP, high bandwidth for color frames | tethered |
| **WiFi** (ESP AP, or ESP joins phone hotspot / STA) | Bruce WebUI path | wireless, full-frame bandwidth, phone keeps cellular | needs the ESP powered separately |
| **BLE** | GATT | input + low-rate telemetry (GPS, buttons) | **color screen streaming is out** — Flipper streams mono 128×64 (1 KB/frame); color 240×135 RGB565 ≈ **63 KiB/frame (~16×/pixel, ~63×/frame)** — see Research finding C |

**Design rule:** color-screen *mirroring* → WiFi or USB. BLE → input + Tier-2 sensor feeds.

## What actually works — capability reality (stay honest)

The phone does **not run** Cardputer apps — the **ESP runs them**; the phone is screen + keyboard +
sensor feed. So "does X work" = "can the ESP's app be **shown, driven, and fed** from the phone."

| Want | Verdict | Why / condition |
|---|---|---|
| **WiFi apps** (scan, deauth, evil-portal) | ✅ clean | WiFi radio is on the ESP; the app runs there. Phone shows + types. Caveat: don't run the *control link* over WiFi if the app seizes the radio — control over USB/BLE then. |
| **BLE apps** | ✅ (BLE only) | BLE radio on the ESP. ESP32-S3 has **no classic Bluetooth** — a Cardputer hardware limit, not ours. Same control-transport caveat. |
| **GPS** | ⚠️ conditional | Phone streams its GPS as **NMEA** into the ESP; works for firmware that reads external GPS (**Bruce, Marauder do**). An app that hardcodes an onboard module won't. |
| **Arbitrary M5Burner apps** (unmodified binaries) | ❌ not possible | ESP32 static-linking forecloses hooking a shipped binary — see Research finding **B**. Only **Bruce (WebUI)** broadcasts its screen today. |
| **Any OPEN-SOURCE app, recompiled** | ⚠️ real, but a rebuild | A patched-M5GFX display+input shim mirrors *any* app you rebuild against it — app source unchanged. Real work, not free, and not for M5Burner binaries. See finding **B**. |

**Architecture consequence — prefer USB-OTG for the control link.** Wired control leaves **both
radios (WiFi + BLE) 100% free** for the app, carries GPS NMEA on the same cable, and gives the best
latency. Mirror-over-WiFi only suits apps that don't seize the WiFi radio (one 2.4 GHz radio —
can't sniff/hop channels *and* hold a control link at once).

**The crux to prototype:** the **M5GFX / LovyanGFX display + input shim** — but see Research finding
**B**: it makes every **open-source app you recompile** mirror transparently, **not** arbitrary
M5Burner binaries (ESP32 static-linking forecloses that). Everything else (Bruce-first mirror, GPS
feed) is proven plumbing. Until the shim, "any app" ≠ automatic; after it, "any *rebuilt* app" is.

---

## Research findings — verified from source (2026-09-02)

Four parallel digs read the **actual source** (not blog summaries) for every piece this project
leans on. Facts are labeled and cited. **Where this section conflicts with looser claims elsewhere
in the spec, this section wins.**

### A. Bruce WebUI protocol — the M1 contract  [VERIFIED from source]

Bruce's screen mirror ("Navigator") is **not** a framebuffer / JPEG / MJPEG stream. It is a
**serialized TFT draw-command log** ("bin log") over **plain HTTP GET polling**, replayed onto an
HTML canvas by a JS interpreter (`renderTFT`). This is *better* than pixels — vector ops are tiny
and give crisp text — so **M1's real work is reimplementing that interpreter, not decoding an image.**

- **Screen:** `GET /getscreen` → `application/octet-stream`; body = draw-command records. Each
  record: `0xAA` sync byte, `int8 size`, `int8 fn` (command), then params. Integers **big-endian
  int16**; colors **RGB565**; strings fixed-length byte slices. Commands seen: 0 FILLSCREEN, 1–4
  rects, 5–6 circles, 7–8 triangles, 11–13 lines/arcs, 14–17 text, 18 DRAWIMAGE (fetches
  `/file?fs=…`), 99 SCREEN_INFO (device sends its own w/h → **do not hardcode 240×135**).
- **Input:** `POST /cm`, body param `cmnd` = `nav up|down|prev|next|sel|esc` (+ optional hold-ms,
  e.g. `nav sel 500`), plus `nextpage`/`prevpage`. Same endpoint runs any Bruce serial command.
- **Discovery / auth:** mDNS `bruce.local`; AP SSID `BruceNet`/`brucenet`; `POST /login` (default
  `admin`/`bruce`) → session cookie carried on every request.
- **Availability:** WebUI is **core** (not per-board), launched from **Files → WebUI** (STA "My
  Network" or AP Mode). Not auto-started.
- **OPEN QUESTION (needs live Cardputer):** does `/getscreen` return a **full redraw or a delta**
  each poll? Decides whether the Android renderer is stateless or must persist the canvas and handle
  ring-buffer overflow/resync. **Confirm on hardware.**
- Source: `src/core/wifi/webInterface.cpp`, `embedded_resources/web_interface/index.js` (pr3y/Bruce);
  https://wiki.bruce.computer/controlling-device/webui/

### B. M5GFX / LovyanGFX shim — the "any app" bet  [VERIFIED verdict]

**Blunt truth: "transparent mirror for ANY M5Burner app" is NOT achievable.** ESP32 is a single
statically-linked binary — no dynamic linker, no `LD_PRELOAD`, no interposition. Worse,
`M5GFX::init_impl()` calls `autodetect()` **unconditionally** and overwrites any panel you inject
before `M5.begin()`. Keyboard is a per-app GPIO matrix scan. You cannot hook an existing binary.

**What IS real: "transparent mirror for any OPEN-SOURCE app you REBUILD against a patched M5GFX."**
The `_bus` object (LovyanGFX `Panel_LCD` → `IBus`) is a genuine single chokepoint; all write methods
(`writeBytes`/`writePixels`/`writeData`/`writeDataRepeat`/`writeCommand`) are `virtual`. Subclass it
to duplicate the pixel/command stream to a network sink; rebuild the app against the patched lib —
app source unchanged. Input injection rides the same rebuild (override `KeyboardReader`).

**Consequence for the pitch:** it's "free mirroring for the open-source Cardputer ecosystem you can
recompile," **not** "anything on M5Burner." No prior art does the transparent tap (closest is
app-cooperative `readPixels()` screen-servers).
- Source: lovyan03/LovyanGFX `Panel_LCD.cpp`, `Bus_SPI.hpp`, `Panel_Device.hpp`; m5stack/M5GFX
  `M5GFX.cpp`; m5stack/M5Cardputer `IOMatrix.cpp`.

### C. Flipper protobuf RPC — protocol to borrow for Tier 3  [VERIFIED from source]

**Borrow wholesale:** varint-length-delimited **`PB.Main` envelope** (`command_id` +
`command_status` + `has_next`), the **`InputKey`/`InputType` enums** (UP/DOWN/LEFT/RIGHT/OK/BACK ×
PRESS/RELEASE/SHORT/LONG/REPEAT), the start/stop stream request pair, and the virtual-display request
shape. The same byte stream runs over USB-CDC **and** BLE.
**Don't borrow:** the raw 1bpp full-framebuffer push. Flipper is 128×64 mono = **1024 B/frame**; ours
is 240×135 **RGB565 = ~63 KiB/frame** — that's **~16× per pixel / ~63× per full frame** (the spec's
earlier "8×" was an understatement). Color mirroring **mandates WiFi/USB + dirty-rect deltas**; BLE
stays fine for input + the semantic-UI control channel. Extend `InputKey` with a keycode/text field
for the Cardputer's full QWERTY; add a region `(x,y,w,h)` to the frame message.
- Source: flipperdevices/flipperzero-protobuf `gui.proto`/`gui.options`/`flipper.proto`;
  flipperzero-firmware `rpc_gui.c`/`rpc.c`.

### D. Hardware / USB-OTG / GPS  [VERIFIED]

- **USB driver path is clean:** both **Cardputer and StickC-S3 use ESP32-S3 native USB (CDC-ACM, VID
  `0x303A`)** — no bridge chip. `mik3y/usb-serial-for-android` (v3.5.0+ binds CDC-ACM by interface
  class), no CH9102/CP210x driver needed. **Risk:** native USB **re-enumerates** on firmware USB
  re-init, and phones deliver weak/contended OTG VBUS → intermittent disconnects; the app must treat
  serial as unreliable and **auto-reconnect**.
- **Board targets:** **Cardputer IS officially Bruce-supported** (240×135 ST7789V2, full 56-key
  QWERTY). **StickC-S3 is NOT officially supported by Bruce** (open issue #2148, hardware-rev) and has
  only ~2 buttons → menu-heavy Bruce flows are painful. **→ Cardputer = safe primary M1 target; spare
  StickC-S3 = at-risk secondary.** (`sticks3-ptt`, the Casa Viva catheter stick, is **off-limits**.)
  Both panels mirror as one **240×135 landscape** frame.
- **GPS:** forward Android raw NMEA (`addNmeaListener`) at **9600 baud, 1 Hz, GGA+RMC** down the same
  serial link; Bruce/Marauder parse external NMEA (TinyGPS++). Two gotchas: the callback goes
  **silent** when Android serves fused/network location (force `GPS_PROVIDER`, or **synthesize**
  GGA/RMC from `Location`), and normalize **`$GN` vs `$GP`** talker IDs before forwarding.

### E. bmorcelli's "Launcher" — support verdict  [VERIFIED from source]

**The Launcher has no screen-mirror of its own.** It's a resident **boot menu that flashes-and-boots
separate firmware `.bin` images** (into OTA partitions), *not* an in-process runtime. Its web server
(`src/webInterface.cpp`) is a **firmware/file manager** — the full route table is OTA upload, file /
NVS / partition ops, `/systeminfo`, `/reboot`, `/bootapp`, login — with **no `/getscreen` and no
`/cm` / nav input endpoint**. bmorcelli (Pirata) is a **Bruce contributor**; Bruce is one of the apps
you boot from the Launcher (Bruce ships `LITE_VERSION` "Launcher-compatible" builds for small-flash
boards).

**Consequences:**
- **Mirror the Launcher's own menu, or arbitrary catalog apps → recompile shim only** (finding B).
  The Launcher adds *zero* mirroring passthrough; each app's mirrorability is its own firmware's
  business.
- **Bruce launched *via* the Launcher still works** — once booted, Bruce *is* the running firmware
  and its WebUI streams exactly as if flashed directly. So **"Bruce anywhere" = M1**; the Launcher
  menu + non-Bruce apps = **shim tier**. (To-verify on hardware: that the small-board `LITE_VERSION`
  Bruce still ships the WebUI.)
- Source: bmorcelli/Launcher `src/webInterface.cpp`, `webUi/scripts.js`, wiki "Explaining the
  project"; Bruce LITE-build feature matrix.

### My engineering read (considerations)

- **M1 is honest and de-risked.** Stock Bruce over WiFi already streams (draw-command log) and takes
  nav input — zero firmware change. The build is: an Android **draw-command renderer** + soft 4×14
  keyboard + login/mDNS. Real risks are **latency** and the **full-vs-delta** unknown (A) — both
  measurable on your Cardputer, today.
- **The "any app" dream is scoped to reality.** Promote the **open-source-recompile shim** as the
  honest Tier-3 lever; **drop "any M5Burner binary"** from the pitch — it's foreclosed by silicon.
- **Transport split is settled.** Color pixels → WiFi/USB with dirty-rect deltas; input + semantic
  control → fine on BLE. USB-OTG stays the preferred end-state (both radios free, GPS on the cable),
  with the screen-over-USB firmware-assist caveat.
- **Sequence:** M1 (WiFi Bruce mirror on the Cardputer) → measure latency → *then* choose firmware-
  assist (push the draw-log over serial) vs. the shim spike for the wired / any-rebuilt-app tiers.
- **Launcher = shim-tier, not M1.** "Run the Launcher catalog on the phone" needs the recompile shim
  (finding E — the Launcher exposes no mirror). Ship Bruce-mirror (M1) first; fold Launcher-menu
  mirroring into the shim milestone. "Bruce, however you booted it" is the M1-reachable slice.

### Sources read (beyond the prior-art list in README)

- Bruce firmware: https://github.com/pr3y/Bruce  ·  WebUI: https://wiki.bruce.computer/controlling-device/webui/  ·  StickC-S3 support issue #2148: https://github.com/BruceDevices/firmware/issues/2148
- Flipper protobuf: https://github.com/flipperdevices/flipperzero-protobuf  ·  firmware RPC: https://github.com/flipperdevices/flipperzero-firmware/tree/dev/applications/services/rpc
- LovyanGFX: https://github.com/lovyan03/LovyanGFX  ·  M5GFX: https://github.com/m5stack/M5GFX  ·  M5Cardputer: https://github.com/m5stack/M5Cardputer
- Android USB serial: https://github.com/mik3y/usb-serial-for-android  ·  NMEA listener: https://developer.android.com/reference/android/location/OnNmeaMessageListener
- Boards: https://docs.m5stack.com/en/core/Cardputer%20V1.1  ·  https://docs.m5stack.com/en/core/StickS3

## MVP definition (Tier 1, Bruce-first)

An Android app that:
1. Discovers a Bruce device over WiFi (`bruce.local` / mDNS / IP).
2. Streams the Navigator screen and renders it full-phone.
3. Maps a **soft 4×14 Cardputer keyboard** (+ hardware-key passthrough) to Bruce key input.
4. Hits an interactive latency target (**goal: < 150 ms key-to-screen** over WiFi; measure & iterate).
5. Requires **no firmware modification**.

## Roadmap

- **M1** — Tier-1 WiFi mirror against stock Bruce (screen + keyboard). *Proves the whole idea.*
  **Build spec: [M1-SPEC.md](./M1-SPEC.md)** (client contract, S0 spike, DoD, test plan).
- **M2** — USB-OTG transport (wired, powers the board, best latency).
- **M3** — Tier-2 GPS injection (phone → ESP NMEA); demo wardriving with no GPS module.
- **M4** — Draft the open **Companion Protocol** (framing, input, sensor feeds) — propose to Bruce.
- **M5** — Tier-3 semantic-UI proof of concept (native rendering of a Bruce menu).

## Open questions

- **Protocol:** adopt Bruce's WebUI protocol as-is for MVP, then converge on a Flipper-style
  protobuf RPC for Tier 3? Or design fresh?
- **Firmware partner:** upstream into Bruce, or ship a companion firmware fork?
- **iOS later?** Flipper's app is cross-platform; Android is Felipe's first target. Keep the core
  transport/protocol layer platform-neutral (KMP/Rust core?) to not repaint twice.
- **Name & trademark:** avoid "Cardputer" in the public name.
- **Powering/enumeration on Android:** USB-OTG quirks (the C5 J5-jumper class of gotcha) — document
  per board, reuse esp-atlas First-Flash boot data.

## Non-goals

- **LoRa** — the Cardputer ADV has none; nothing to mirror.
- **Generic phone-screen mirroring** — that's the *reverse* (phone→device); not this.
- **Replacing Bruce** — this is a *companion*, not a firmware.

## Relationship to esp-atlas

Natural sibling: esp-atlas already catalogs Bruce + Cardputer boots + recipes (`download_mode`,
`usb_serial`, First-Flash). Androputer can **reuse that board/boot data** for its connect step, and
esp-atlas can link out to Androputer as the "drive it from your phone" companion. Same universe.

## Security / scope

A dual-use **companion** for pentest firmware (Bruce), built in a white-hat context. It mirrors and
feeds a device the operator already owns and flashed — it is a UI bridge, not an attack tool.
**Private until Felipe decides otherwise.**
