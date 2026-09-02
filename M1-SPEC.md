# DROIDPUTTER M1 — Tier-1 WiFi Mirror (build spec)

> **Milestone contract.** The north-star vision, architecture, and verified research live in
> [SPEC.md](./SPEC.md). This file is the **build-ready scope for M1** — what the Android app must do,
> the exact Bruce contract it codes against, and how we know it's done. Read SPEC.md → *Research
> finding A* first; that is the protocol this milestone implements.

**Status:** specced, not started. **Depends on:** a live Bruce device (Cardputer — the officially
supported, safe target; **not** the `sticks3-ptt` catheter stick).

---

## Goal — one sentence

An Android app that connects to a **stock, unmodified Bruce** device over WiFi, renders its screen
full-phone by **replaying Bruce's draw-command log**, and drives it with a soft keyboard — **proving
the whole DROIDPUTTER idea with zero firmware change.**

## Definition of Done (acceptance criteria)

1. Discovers a Bruce device: mDNS `bruce.local` → AP `BruceNet` → manual IP (any one works).
2. Authenticates (`POST /login`, default `admin`/`bruce`) and holds the session cookie.
3. Streams `GET /getscreen` and renders the Navigator screen **legibly, full-phone** — text crisp
   (vector redraw, **not** an upscaled 240×135 bitmap).
4. A soft key/nav pad (+ hardware-key passthrough) drives the device via `POST /cm` — **menu
   navigation works end-to-end** on a real Cardputer.
5. **Latency measured**, goal **< 150 ms key-to-screen** over WiFi (report the actual number).
6. Survives a connection drop: auto-reconnect back to streaming.
7. **No firmware modification** anywhere.
8. The draw-command **decoder has ≥ 80% test coverage** against captured real frames.

## In scope / Out of scope

**In:** WiFi transport, mDNS/login/session, `/getscreen` decode + render, `/cm` nav input, soft
keyboard, connection lifecycle + reconnect.

**Out (deferred milestones — do not build in M1):** USB-OTG transport (M2), phone-GPS→ESP NMEA (M3),
the open Companion Protocol (M4), Tier-3 semantic UI (M5), the M5GFX recompile shim, and **any**
firmware change. Full text entry beyond Bruce's nav abstraction is out (see Input note below).

---

## The Bruce client contract (implement against SPEC finding A)

Bruce is the "server" and **already exists** — so M1's contract is the **client's obligations**. All
of the below is Bruce's *existing* protocol; nothing here asks Bruce to change.

### 1. Connect & authenticate
- **Discovery order:** resolve `bruce.local` (mDNS) → else join AP SSID `BruceNet` / pass `brucenet`
  and target the gateway → else user-entered IP. Base URL `http://<host>:80`.
- **Login:** `POST /login` with credentials (default `admin`/`bruce`), capture the **session cookie**,
  attach it to every subsequent request. (Cookie name/lifetime: confirm live in S0.)

### 2. Screen — `GET /getscreen` (poll)
- Response `application/octet-stream` = a stream of **draw-command records**. Per record:
  `0xAA` sync byte · `int8 size` · `int8 fn` (command) · `params…`. Multi-byte ints are **big-endian
  int16**; colors are **RGB565** (expand client-side); strings are fixed-length byte slices.
- **Command set (known):** `0` FILLSCREEN · `1–4` rects (draw/fill/rounded) · `5–6` circles/arcs ·
  `7–8` triangles · `11–13` lines/arcs · `14–17` text (centre/right/normal/print) · `18` DRAWIMAGE
  (→ fetch `GET /file?fs=<0=SD|1=LittleFS>&…`, cache by path) · `99` SCREEN_INFO (**sets canvas
  w/h — never hardcode 240×135**). Unknown `fn` → **skip via `size`** (forward-compatible).
- **Full-vs-delta (SPEC finding A open question) — M1 handles both:** maintain a **persistent
  canvas** and apply commands incrementally; treat `FILLSCREEN`/`SCREEN_INFO` as a natural full
  redraw. On any parse desync (missing `0xAA`) → **resync**: clear + refetch. **S0 resolves the
  actual behavior and sets the poll cadence** (start 200–500 ms, tune).

### 3. Input — `POST /cm`, body param `cmnd`
- Nav vocabulary: `nav up|down|prev|next|sel|esc`, plus `nextpage`/`prevpage`; optional hold in ms
  (`nav sel 500`). Map soft-pad + hardware keys to these.
- **Scope note:** M1 targets **nav parity with Bruce's WebUI** — the 6-button + page abstraction Bruce
  exposes (Bruce maps browser Arrows/Enter/Backspace/PageUp-Down/`m` to these). Raw full-QWERTY text
  entry is a firmware/Companion-Protocol concern, **out of M1**.

---

## Architecture (components)

Ordered by the golden-rule "smart core, dumb client" split — the decoder is the brain, the UI is dumb.

1. **Transport (platform-neutral core):** HTTP client, session cookie, poll scheduler, discovery.
2. **Draw-command decoder (PURE, unit-testable):** `bytes → List<DrawOp>`. **Zero Android
   dependencies** so it runs on the JVM against captured `/getscreen` fixtures. This is the 80%+
   coverage target and the equivalent of the "API spec" here.
3. **Canvas renderer:** applies `DrawOp`s to a bitmap; handles SCREEN_INFO resize, image fetch/cache,
   and resync. (Android-side, thin.)
4. **Input mapper:** UI key events → `/cm nav` commands.
5. **Connection manager:** discovery → login → stream → reconnect; explicit state machine
   (disconnected / connecting / streaming / error).
6. **UI:** full-screen canvas + soft nav/keyboard overlay + connection-status chrome.

**Keep transport + decoder as a platform-neutral module (Kotlin, KMP-ready)** so a later iOS client
reuses the core instead of repainting it (per SPEC open question on cross-platform).

---

## S0 — de-risk spike (do this BEFORE full M1)

A tiny throwaway: point a script at the **live Cardputer's** `/getscreen`, capture raw bytes across a
few screens + interactions. Answers, from real hardware:
- **Full redraw or delta per poll?** (decides stateless vs. stateful renderer + resync design)
- **Sustainable poll rate / latency floor** on this silicon.
- **Which commands actually appear** in practice; confirm the cookie/auth details.
- **Output:** save the raw captures as **golden fixtures** for the decoder's TDD.

S0 is the single thing that converts finding A's open question into a settled design. It needs only
the Cardputer + WiFi — no app yet.

---

## Test plan

- **Unit (no device):** decoder vs. captured `/getscreen` golden fixtures from S0 — byte-in →
  `DrawOp`-out. Covers command parsing, big-endian int16, RGB565 expansion, unknown-`fn` skip,
  SCREEN_INFO resize, resync-on-desync. **≥ 80% coverage.**
- **Integration (device):** against the live Cardputer — render fidelity (manual visual) + **measured
  key-to-screen latency**. Reconnect-after-drop.
- Emulator is **not** needed for the decoder; render/latency need the Cardputer (in hand).

## Proposed tech (confirm)

- **Native Android, Kotlin**, min-SDK 24. Transport + decoder as a **pure Kotlin module** (KMP-ready).
  HTTP: OkHttp. Rendering: Android `Canvas`/`Bitmap`. mDNS: Android NSD.
- Rationale: Android is the first target; a platform-neutral core avoids a rewrite for iOS later.
  *(Flag: this is the one genuine tech-stack decision — say the word or override.)*

## Build sequence

`S0 capture → decoder (TDD on fixtures) → transport + connection manager → renderer → input →
UI + latency measurement → DoD sign-off on the Cardputer.`
