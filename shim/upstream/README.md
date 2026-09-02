# shim/upstream

Vendored copies of open-source Cardputer app sources, committed unchanged, so
apps/m5-example can prove the shim overlay works on code we didn't write.

- `M5Cardputer/examples/Basic/keyboard/inputText/inputText.ino` — byte-identical
  copy of the M5Cardputer library's own example (M5Stack/Sean, v1.1.1,
  `.pio/libdeps/m5cardputer/M5Cardputer/examples/Basic/keyboard/inputText/`).
  Never edit this file; it is the "any app builds unmodified" proof, same role
  Pense-Bem plays via its external `src_dir`.
