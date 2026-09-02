# Androputer

**Turn your Android phone into the screen, keyboard, and sensors for an ESP32 pocket computer.**
Flagship target: the M5Stack **Cardputer / Cardputer ADV**. One line: *the Flipper Zero mobile app,
but for the Cardputer.*

→ Full design in **[SPEC.md](./SPEC.md)**.

Status: **idea + spec.** Private working repo. No code yet.

---

## The idea — verbatim (Felipe, 2026-09-02)

> Dude, wtf, I had an peculiar idea. Think with me.
>
> 1. Small screen on cardputter adv.
> 2. there is a git with a remote controller, for ios, supposedly working
> WHY don't we create an android app, cardputter my android?
> Basically you connect correct esp32, and the mobile screen works as the cardputter screen -
> basically, everything that works on cardputter without Lora should work. Including GPS maybe.
> Is it possible? Anything similar?

**Verdict:** yes, it's possible — and a large part of it already exists (see prior art). The concept
is proven by the Flipper Zero mobile app; the ESP side already streams its screen on Bruce firmware;
phone-GPS-to-ESP is a solved pattern.

---

## Prior art & references (what each one proves)

### The exact pattern — phone as a device's screen + input
- **Flipper Zero Mobile App** — streams the Flipper screen + remote input over BLE; open source,
  iOS + Android. *This is Androputer's blueprint.*
  - Docs: https://docs.flipper.net/zero/mobile-app
  - F-Droid (Android, open source): https://f-droid.org/en/packages/com.flipperdevices.app/
  - App Store: https://apps.apple.com/app/id1534655259
- **Flipper RPC protocol (protobuf)** — the clean serial/BLE framing to model our protocol on:
  - Protobuf defs: https://github.com/flipperdevices/flipperzero-protobuf
  - Rust RPC client: https://github.com/elijah629/flipper-rpc
  - Desktop manager (USB serial + BLE, Tauri): https://github.com/fuckmaz/FlipperUI
  - Web (Web Serial, screen mirror via protobuf `ScreenMirror.js`): https://github.com/bruno-civongroup/flipper-zero-interface
  - Python bindings: https://github.com/flipperdevices/flipperzero_protobuf_py

### The ESP side already exists (flagship firmware)
- **Bruce WebUI "Navigator"** — mirrors the Cardputer screen + takes keyboard input over WiFi to a
  browser (`http://bruce.local`). Tier-1 MVP can wrap this with no firmware change.
  - Wiki: https://wiki.bruce.computer/controlling-device/webui/
  - Source (Wiki md): https://github.com/BruceDevices/Wiki/blob/main/docs/controlling-device/webui.md
  - Bruce firmware: https://github.com/pr3y/Bruce  ·  releases: https://github.com/BruceDevices/firmware/releases

### Screen mirroring on the exact board
- **Zeloksa/WiFi-Remote-Display-ADV** — ultra-low-latency screen mirroring for the Cardputer ADV
  (UDP streaming engine). NOTE: mirrors **PC → Cardputer** (reverse of us), but proves the streaming
  stack + latency budget on this silicon.
  - https://github.com/Zeloksa/WiFi-Remote-Display-ADV
  - Thread: https://community.m5stack.com/topic/8187/cardputer-wifi-remote-display-adv-v1-0-open-source-screen-mirroring-payload
- **aayushchouhan24/ESP32-Screen-Mirroring** — ESP32 framebuffer streaming over WiFi with
  auto-discovery: https://github.com/aayushchouhan24/ESP32-Screen-Mirroring
- **botofancalin/ESP32_Camera_System** — WiFi video ESP32→ESP32 TFT (SPI-TFT fps limits noted):
  https://github.com/botofancalin/ESP32_Camera_System
- **skyvense/ESP-Remote-Monitor** — simple/fast ESP32 screen streaming (MQTT):
  https://github.com/skyvense/ESP-Remote-Monitor

### Phone GPS → ESP (the "GPS maybe" part)
- **coniferconifer/ESP32-GPS-BTserial** — NMEA between ESP32 and Android over BT serial (use an
  external GPS as Android's GPS — invert it and the phone feeds the ESP):
  https://github.com/coniferconifer/ESP32-GPS-BTserial
- **mrichar1/esp32-gps** — ESP32 GPS over USB serial / BT / RTK / NTRIP / ESP-NOW:
  https://github.com/mrichar1/esp32-gps
- **Marauder external-GPS mod** (firmware accepts external GPS over serial for wardriving):
  https://github.com/justcallmekoko/ESP32Marauder/wiki/gps-modification
- **ESP32-DIV GPS wardriver**: https://github.com/cifertech/ESP32-DIV/wiki/GPS-Wardriver

### Cardputer ADV hardware (grounds the spec)
- M5Stack Cardputer-Adv product/docs: https://docs.m5stack.com/en/core/Cardputer-Adv  ·
  https://shop.m5stack.com/products/m5stack-cardputer-adv-version-esp32-s3
- Specs recap (ESP32-S3FN8, 1.14" 240×135 ST7789V2, 56-key, ES8311, BMI270, 1750 mAh):
  https://www.cnx-software.com/2025/10/23/m5stack-cardputer-adv-esp32-s3-computer-gains-improved-antenna-larger-1750-mah-battery-es8311-audio-codec/
- ESPP board reference: https://esp-cpp.github.io/espp/dev_boards/m5stack/m5stack_cardputer.html

### Adjacent / ecosystem
- Cardputer Ultimate Remote (IR profiles; not phone-remote, but ecosystem): https://github.com/geo-tp/Ultimate-Remote
- M5Stack Tab5 Android console (mirror/control Android over USB/WiFi): https://www.hackster.io/hiroki_kawakami/m5stack-tab5-android-console-cc265b

### Kindred name, DIFFERENT mechanism (do not confuse with Androputer)
- **"Phoneputer"** (MWLabs) — installs **NixOS / full Linux on an old Android phone** (OnePlus 6) +
  a wireless keyboard → the *phone itself* becomes a standalone Linux pocket computer. **No ESP32,
  no Cardputer, no mirroring.** Validates the *cultural itch* (people want a "real" pocket computer),
  not our mechanism (phone as screen/brain-companion for an ESP running radio firmware). Also: the
  name "Phoneputer" is **taken**.
  - Article: https://www.hackster.io/news/turn-your-phone-into-a-phoneputer-47027edb8224
  - Repo: https://github.com/mwlaboratories/phoneputer

### Community demand signal
- r/CardPuter — *"Any way to load M5 Launcher from Android phone"* (people want the **Launcher**,
  not just Bruce, reachable from Android — the exact demand for the M5GFX-shim path):
  https://www.reddit.com/r/CardPuter/comments/1oz8hqj/any_way_to_load_m5_launcher_from_android_phone/

---

## Next step

Nothing is committed to code. Open decision for Felipe: greenlight **M1** (Tier-1 WiFi mirror against
stock Bruce — proves the whole idea with zero firmware change), and decide public-vs-private + the
public name. See [SPEC.md](./SPEC.md) → *Roadmap* and *Open questions*.
