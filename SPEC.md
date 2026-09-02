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
| **BLE** | GATT | input + low-rate telemetry (GPS, buttons) | **color screen streaming is tight** — Flipper streams mono 128×64; color 240×135 ≈ 8× the data |

**Design rule:** color-screen *mirroring* → WiFi or USB. BLE → input + Tier-2 sensor feeds.

## What actually works — capability reality (stay honest)

The phone does **not run** Cardputer apps — the **ESP runs them**; the phone is screen + keyboard +
sensor feed. So "does X work" = "can the ESP's app be **shown, driven, and fed** from the phone."

| Want | Verdict | Why / condition |
|---|---|---|
| **WiFi apps** (scan, deauth, evil-portal) | ✅ clean | WiFi radio is on the ESP; the app runs there. Phone shows + types. Caveat: don't run the *control link* over WiFi if the app seizes the radio — control over USB/BLE then. |
| **BLE apps** | ✅ (BLE only) | BLE radio on the ESP. ESP32-S3 has **no classic Bluetooth** — a Cardputer hardware limit, not ours. Same control-transport caveat. |
| **GPS** | ⚠️ conditional | Phone streams its GPS as **NMEA** into the ESP; works for firmware that reads external GPS (**Bruce, Marauder do**). An app that hardcodes an onboard module won't. |
| **Arbitrary M5Burner apps** | ⚠️ the hard one | The phone mirrors **pixels**, but a random app doesn't broadcast its screen — only **Bruce (WebUI)** does today. Making *any* app mirror needs a **display-driver shim** (hook M5GFX/LovyanGFX so every app's draws + key-reads tunnel out). Doable *because* most Cardputer apps share that lib — but it's real work, not free. |

**Architecture consequence — prefer USB-OTG for the control link.** Wired control leaves **both
radios (WiFi + BLE) 100% free** for the app, carries GPS NMEA on the same cable, and gives the best
latency. Mirror-over-WiFi only suits apps that don't seize the WiFi radio (one 2.4 GHz radio —
can't sniff/hop channels *and* hold a control link at once).

**The crux to prototype:** the **M5GFX / LovyanGFX display + input shim**. If it works, the *entire*
Cardputer app catalog renders on the phone transparently — that's the genius move. Everything else
(Bruce-first mirror, GPS feed) is proven plumbing around it. Until then, "any app" ≠ automatic.

## MVP definition (Tier 1, Bruce-first)

An Android app that:
1. Discovers a Bruce device over WiFi (`bruce.local` / mDNS / IP).
2. Streams the Navigator screen and renders it full-phone.
3. Maps a **soft 4×14 Cardputer keyboard** (+ hardware-key passthrough) to Bruce key input.
4. Hits an interactive latency target (**goal: < 150 ms key-to-screen** over WiFi; measure & iterate).
5. Requires **no firmware modification**.

## Roadmap

- **M1** — Tier-1 WiFi mirror against stock Bruce (screen + keyboard). *Proves the whole idea.*
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
