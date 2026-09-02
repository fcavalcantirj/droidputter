# Fixtures (captured from real hardware)

`pense-bem/` — 2026-09-02, Cardputer ADV running Pense-Bem rebuilt against the shim, captured on the Mac with
`tools/dp_receiver.py --record fixtures/pense-bem/boot` (appended across two sessions: the original S2/S3 hardware spike, then the task-5 re-verify run):
- `boot.bin` — raw USB-CDC stream (frames + the app's own serial text interleaved), 455,062 B, 3 HELLO + 57 RECT_RLE + 30 STATS frames (`python3 tools/dp_receiver.py --decode fixtures/pense-bem/boot.bin`), 3 framing errors (resynced).
- `boot.jsonl` — per-chunk timestamps (`dir: in`) and the host's outgoing frames (`dir: out`, hex): HELLO_ACK, then KEY down/up for `1` (0,1), `3` (0,3), Enter (2,13).
- `screen-after-keys.png` — the host framebuffer after the keys: ADICAO, a question, cursor, legend.
Replay with `python3 tools/dp_receiver.py --selftest` style code: feed `boot.bin` to `Framer` + `Screen`.
- Full replay of `boot.bin` (all 90 frames: 3 HELLO + 57 RECT_RLE + 30 STATS) through `Framer` +
  `ScreenModel` ends with a non-black 240x135 framebuffer whose top-left pixel (0,0) is `0x0000`
  (the ADICAO screen's top-left corner is black background; the title/menu text starts a few
  pixels in) — recorded by replaying the fixture with `tools/dp_receiver.py`'s `Screen` class.
