# Link reproduction harness (Mac, Cardputer ADV on /dev/cu.usbmodem*)

Scratch tools promoted from the 2026-09-03 session that root-caused the "silent Cardputer"
(ESP32-S3 HWCDC mute after a USB suspend). Not part of any build; run from the repo root.

- `reenum.c` — IOKit: `clang -o reenum reenum.c -framework IOKit -framework CoreFoundation`;
  `./reenum` re-enumerates the 303A:1001 device (bus reset, no power loss);
  `./reenum suspend 30` suspends it for 30 s (what an Android host does to an idle OTG port).
- `flood_drop_repro.py {close|reenum|suspend N}` — link (HELLO_ACK, tee storm) + inbound NMEA
  flood, then the chosen host drop, then PING_IN probes; prints STATS and LOG frames
  (`cdc-recovered rung=N`). Expects `./reenum` next to it.
- `gps_proof.py {canonical|flood} [seconds]` — canonical GGA -> PNG of the ESP screen; or an
  NMEA flood at phone rate. Imports `tools/dp_receiver.py`.
- `mac_triage.py` — no-reset classification of a silent board: raw read, PING_IN, esptool SYNC.
- `cardputer_classified.json` — output of the Launcher/M5Burner catalog
  classification (repo -> display libs, license, pio/ino); source list from
  `https://api.launcherhub.net/giveMeTheList` (category == "cardputer", `github` field).
