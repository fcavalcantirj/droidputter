# Flashing Droidputter builds from a phone

Finding (2026-09-02, [REAL], see progress.txt S4a/S4b): flashing an ESP32-S3 from
an Android phone over USB-OTG works TODAY with an existing off-the-shelf app —
no droidputter-specific flasher needed for this milestone. A native in-app
flasher (task: "Catalog screen in the app") is a later milestone; until it
ships, the Android app hands off to a third-party flasher via the share sheet.

## Artifacts and offsets

PlatformIO produces four files per env under `apps/<app>/.pio/build/<env>/`:

| File             | Flash offset |
|------------------|--------------|
| `bootloader.bin`  | `0x0`        |
| `partitions.bin`  | `0x8000`     |
| `boot_app0.bin`   | `0xE000`     |
| `firmware.bin`    | `0x10000`    |

`boot_app0.bin` comes from the platform package (arduino-esp32 OTA data),
not the build dir — copy it from
`~/.platformio/packages/framework-arduinoespressif32/tools/partitions/boot_app0.bin`.

## Getting the files onto the phone

The phone's USB-C port is occupied by the Cardputer (OTG host mode), so ADB
over USB is not available at the same time. Two options that worked:

- `adb push` each `.bin` to `/sdcard/Download/droidputter/` over WIRELESS adb
  (`adb connect <phone-ip>:5555`), then trigger a media scan
  (`am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE ...`) so the
  file picker in the flasher app can see them — adb-pushed files are invisible
  to the picker until MediaStore indexes them.
- Or serve the build dir from the Mac (`python3 -m http.server 8765`) and
  download the four files in the phone's browser.

## Flashing app

Play Store app `ESP32_Flasher` (package `com.esp_flash.esp_flash_app`).
Steps: chip = ESP32S3, Bootloader Auto = ON, add the four files at the offsets
above, tap Flash.

Verified 2026-09-02 [REAL] (progress.txt S4b): Poco X7 Pro (Android 16) +
Cardputer ADV over a USB-C OTG data cable, no PC involved. Auto-bootloader
entry worked ("Sync Success"), ESP32-S3 rev 0.10 detected, firmware
504,176 B compressed to 303,684 B, flashed in 19.39 s, device reset and
booted straight into Pense-Bem.

## Catalog hand-off (implemented, 2026-09-02)

The Android app's Catalog screen (`android/app/.../catalog/`) reads the
bundled `apps/catalog.json` asset and, for whichever entries were built
locally when `./gradlew :app:assembleDebug` ran, their bin parts too — the
Gradle build (`copyCatalogManifest`/`copyCatalogBins` in `android/app/build.gradle.kts`)
copies each entry's `bootloader.bin`/`partitions.bin`/`firmware.bin` from its
`apps/<app>/.pio/build/<env>/` and `boot_app0.bin` from the PlatformIO
toolchain package into `assets/catalog/<name>-<env>/`, keyed by the
`build_dir` field `tools/make_catalog.py` now writes into each entry. An
entry whose parts weren't bundled (fresh clone, or a board nobody has built
this session) shows in the list with its metadata but has "Share to flasher"
disabled.

Tapping "Share to flasher" on an available entry copies its four bin files
into the app's cache dir, wraps them as `content://` URIs via a
`FileProvider` (`android/app/src/main/res/xml/file_paths.xml`, never a raw
`file://` path — blocked by `FileUriExposedException` on modern Android),
and fires `Intent.ACTION_SEND_MULTIPLE` with those URIs plus an
`EXTRA_TEXT` offsets blob (same offset table as above, plus each part's
sha256) through `Intent.createChooser`. Any installed flasher — ESP32_Flasher
today — appears in the share sheet; the app still has no flashing code of
its own, exactly as the "future milestone" note above originally scoped it.
See the "Catalog screen in the app" task in spec.json.
