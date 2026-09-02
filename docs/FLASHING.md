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

## Catalog hand-off (future milestone)

Once `apps/catalog.json` exists, the Android app will share the bin parts +
an offsets text blob to any installed flasher via the Android share sheet,
rather than shipping its own flashing code. See the "Catalog screen in the
app" task in spec.json.
