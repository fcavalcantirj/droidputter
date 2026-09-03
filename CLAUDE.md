# CLAUDE.md — droidputter

## North star (Felipe, 2026-09-03)

Plug an ESP32-S3FN8 (the Cardputer's StampS3 chip) or similar into an Android phone over USB-OTG and run
ESP32 stuff on it — the **whole Cardputer ecosystem and other ESP32 apps** — using the Android screen and
peripherals (keyboard, GPS, ...). The phone is the ESP's screen, keyboard, GPS and launcher; the ESP runs
the app and the phone adds zero computation.

The catalog must carry lots of Cardputer apps, sourced from bmorcelli's Launcher catalog
(https://bmorcelli.github.io/Launcher/catalog.html, which loads its entries from the M5Burner firmware
API at runtime). What stays true (SPEC.md finding B): only open-source apps rebuilt against the shim run
on the phone. M5GFX/M5Unified-based apps work today; TFT_eSPI-based apps need the TFT_eSPI shim (M4).
Bruce is prior art only, never a milestone.

## Read first

`AGENTS.md` (layout, golden rules, gates), `docs/GROUND_RULES.md`, `progress.txt` (append-only journal),
`docs/PROTOCOL.md`, `SPEC.md`.

## Rules

- Verify on the live system before claiming; label every result `[REAL]` / `[TEST]` / `[UNVERIFIED]`.
- Commit, push, tag only on Felipe's word. No new docs or files unless asked.
- Never edit the external app sources the overlays build (e.g. `/Users/fcavalcanti/dev/pense-bem-cardputer-adv`).
- Coordination: Solvr room `droidputter` — post `[EXEC] STATUS / DONE / BLOCKED` with failures and successes as you go.
