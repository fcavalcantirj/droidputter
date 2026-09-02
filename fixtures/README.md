# Fixtures (captured from real hardware)

`pense-bem/` — 2026-09-02, Cardputer ADV running Pense-Bem rebuilt against the shim, captured on the Mac with
`tools/dp_receiver.py --record fixtures/pense-bem/boot`:
- `boot.bin` — raw USB-CDC stream (frames + the app's own `[F]`/`[K]` serial text interleaved), 309,773 B, 59 valid frames + 1 framing error.
- `boot.jsonl` — per-chunk timestamps (`dir: in`) and the host's outgoing frames (`dir: out`, hex): HELLO_ACK, then KEY down/up for `1` (0,1), `3` (0,3), Enter (2,13).
- `screen-after-keys.png` — the host framebuffer after the keys: ADICAO, question `33 + 4 =`, cursor, legend.
Replay with `python3 tools/dp_receiver.py --selftest` style code: feed `boot.bin` to `Framer` + `Screen`.
