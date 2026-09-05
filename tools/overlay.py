#!/usr/bin/env python3
"""Overlay generator: turn an open-source Cardputer app repo into a droidputter build overlay.

    python3 tools/overlay.py <github-url | owner/repo> [--name NAME] [--ref REF] [--env-src ENV] [--env ENV] [--build] [--upload]

Clones the repo (shallow; --ref = branch, tag or full commit sha) into apps/_src/<name>/ (git-ignored),
reads its platformio.ini (or finds its .ino), and writes apps/<name>/platformio.ini on the
apps/pense-bem pattern: the app's own sources UNCHANGED via [platformio] src_dir, its own lib_deps kept
except the display/keyboard libraries, which the shim provides patched (M5GFX 0.2.27 + M5Cardputer 1.1.1
in apps/<name>/lib via shim/apply.sh, M5Unified pinned to the version the shim is tested with) plus
DroidputterShim. Every path in the generated ini is anchored on PlatformIO's ${PROJECT_DIR} (the overlay
dir), so the same ini builds on any checkout -- a Mac or a GitHub runner -- without regeneration.
Prints one JSON line per app so a batch run can be tabulated. --build runs `pio run -e <env>` (--env,
default m5cardputer = Cardputer ADV with the real TFT teed; m5cardputer-virtual = bare ESP32-S3, the phone
is the only screen) and reports RAM/flash or the first compiler errors; --upload flashes the board on
/dev/cu.usbmodem*.
"""
import argparse
import configparser
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_ROOT = REPO_ROOT / "apps" / "_src"
APPS = REPO_ROOT / "apps"
PIO = Path.home() / ".platformio" / "penv" / "bin" / "pio"   # the PlatformIO installer's venv (Mac)
if not PIO.exists():   # pip-installed PlatformIO (CI runner): plain `pio` on PATH
    PIO = Path(shutil.which("pio") or "pio")
SHIM_LIBS = re.compile(r"M5GFX|M5Cardputer|M5Unified", re.I)
STD_FLAG = re.compile(r"^-std=")
M5UNIFIED = "m5stack/M5Unified@0.2.20"   # the version the patched M5GFX 0.2.27 is tested with

# Arduino-IDE repos carry no dependency list: map the headers they #include to PlatformIO registry
# packages. Framework/shim headers map to None (nothing to add); unknown headers are reported.
INCLUDE_TO_DEP = {
    "ArduinoJson.h": "bblanchon/ArduinoJson@^7",
    "Audio.h": "esphome/ESP32-audioI2S@^2.0.7",
    "AudioOutputI2S.h": "earlephilhower/ESP8266Audio@^1.9.7", "AudioGeneratorMP3.h": "earlephilhower/ESP8266Audio@^1.9.7",
    "AudioFileSourceSD.h": "earlephilhower/ESP8266Audio@^1.9.7", "AudioFileSourceHTTPStream.h": "earlephilhower/ESP8266Audio@^1.9.7",
    # Arduino-IDE Cardputer repos are 2023-2024 code against the NimBLE 1.x API (BleKeyboard forks etc.)
    "NimBLEDevice.h": "h2zero/NimBLE-Arduino@^1.4.3",
    "TinyGPS++.h": "mikalhart/TinyGPSPlus@^1.1.0", "TinyGPSPlus.h": "mikalhart/TinyGPSPlus@^1.1.0",
    "FastLED.h": "fastled/FastLED@^3.9",
    "IRremoteESP8266.h": "crankyoldgit/IRremoteESP8266@^2.8.6", "IRsend.h": "crankyoldgit/IRremoteESP8266@^2.8.6", "IRrecv.h": "crankyoldgit/IRremoteESP8266@^2.8.6",
    "IRremote.h": "z3t0/IRremote@^4.4", "IRremote.hpp": "z3t0/IRremote@^4.4",
    "PubSubClient.h": "knolleary/PubSubClient@^2.8",
    "Adafruit_NeoPixel.h": "adafruit/Adafruit NeoPixel@^1.12",
    "Adafruit_GFX.h": "adafruit/Adafruit GFX Library@^1.11", "Adafruit_SSD1306.h": "adafruit/Adafruit SSD1306@^2.5",
    "ESPAsyncWebServer.h": "ottowinter/ESPAsyncWebServer-esphome@^3.3", "AsyncTCP.h": "esphome/AsyncTCP-esphome@^2.1",
    "U8g2lib.h": "olikraus/U8g2@^2.35", "lvgl.h": "lvgl/lvgl@^8.4",
    "SdFat.h": "greiman/SdFat@^2.2", "ESP32Servo.h": "madhephaestus/ESP32Servo@^3",
    "JPEGDEC.h": "bitbank2/JPEGDEC@^1.8", "ESP32Time.h": "fbiego/ESP32Time@^2.0", "PNGdec.h": "bitbank2/PNGdec@^1.1",
    "AnimatedGIF.h": "bitbank2/AnimatedGIF@^2.1", "TJpg_Decoder.h": "bodmer/TJpg_Decoder@^1.1", "ESPAsyncTCP.h": "esphome/AsyncTCP-esphome@^2.1",
    "RadioLib.h": "jgromes/RadioLib@^7", "LoRa.h": "sandeepmistry/LoRa@^0.8",
    "MFRC522.h": "miguelbalboa/MFRC522@^1.4", "DHT.h": "adafruit/DHT sensor library@^1.4",
    "OneWire.h": "paulstoffregen/OneWire@^2.3", "DallasTemperature.h": "milesburton/DallasTemperature@^4",
    "ArduinoOTA.h": None, "WiFi.h": None, "WiFiClient.h": None, "WiFiClientSecure.h": None, "WiFiUdp.h": None, "WiFiMulti.h": None,
    "HTTPClient.h": None, "WebServer.h": None, "ESPmDNS.h": None, "DNSServer.h": None, "Update.h": None, "HTTPUpdate.h": None,
    "Preferences.h": None, "SPIFFS.h": None, "LittleFS.h": None, "FS.h": None, "SD.h": None, "SD_MMC.h": None, "FFat.h": None,
    "Wire.h": None, "SPI.h": None, "EEPROM.h": None, "Ticker.h": None, "esp_now.h": None, "esp_wifi.h": None, "esp_sleep.h": None,
    "BLEDevice.h": None, "BLEServer.h": None, "BLEUtils.h": None, "BLE2902.h": None, "BLEScan.h": None, "BLEAdvertisedDevice.h": None,
    "USB.h": None, "USBHIDKeyboard.h": None, "USBHIDMouse.h": None, "driver/i2s.h": None, "driver/rmt.h": None, "esp_system.h": None,
    "Arduino.h": None, "pgmspace.h": None, "avr/pgmspace.h": None, "M5Cardputer.h": None, "M5Unified.h": None, "M5GFX.h": None, "M5Unified.hpp": None, "M5GFX.hpp": None,
    "M5UnitLCD.h": None, "M5UnitOLED.h": None, "M5AtomDisplay.h": None, "M5ModuleDisplay.h": None, "M5Stack.h": None,
}
LOCAL_HEADER_EXTS = (".h", ".hpp", ".hh")


def infer_ino_deps(sketch_dir: Path, repo: Path) -> tuple[list[str], list[str]]:
    """Registry deps for the headers an Arduino-IDE sketch includes; also the headers we cannot place."""
    local = {p.name for p in repo.rglob("*") if p.suffix in LOCAL_HEADER_EXTS}
    deps, unknown, seen = [], [], set()
    for f in list(sketch_dir.rglob("*.ino")) + list(sketch_dir.rglob("*.cpp")) + list(sketch_dir.rglob("*.h")) + list(sketch_dir.rglob("*.hpp")):
        try:
            text = f.read_text(errors="replace")
        except OSError:
            continue
        for m in re.finditer(r'^\s*#\s*include\s*[<"]([^>"]+)[>"]', text, re.M):
            hdr = m.group(1)
            if hdr in seen:
                continue
            seen.add(hdr)
            if hdr in INCLUDE_TO_DEP:
                dep = INCLUDE_TO_DEP[hdr]
                if dep and dep not in deps:
                    deps.append(dep)
            elif Path(hdr).name in local or hdr.startswith(("freertos/", "esp_", "driver/", "soc/", "hal/", "rom/", "nvs", "sys/", "lwip/", "mbedtls/")) or "/" not in hdr and not hdr.endswith((".h", ".hpp")):
                continue
            elif hdr.endswith((".h", ".hpp")) and not hdr.startswith(("std", "c")) and hdr not in ("string.h", "stdio.h", "stdlib.h", "stdint.h", "math.h", "time.h", "ctype.h", "vector", "map", "string"):
                unknown.append(hdr)
    return deps, unknown

ENV_TEMPLATE = """; DROIDPUTTER overlay generated by tools/overlay.py -- builds {slug} UNCHANGED from
; apps/_src/{name} against the patched libs in lib/ (shim/apply.sh) + DroidputterShim.
; Upstream env used for lib_deps/build_flags: [{env_src}]. Regenerate with:
;   python3 tools/overlay.py {slug} --name {name}
[platformio]
src_dir = {src_dir}
lib_extra_dirs =
{lib_extra_dirs}

[env:m5cardputer]
platform = espressif32@6.12.0
board = m5stack-stamps3
framework = arduino
board_build.mcu = esp32s3
board_build.f_cpu = 240000000L
board_build.f_flash = 80000000L
board_build.flash_mode = qio
board_build.flash_size = 8MB
board_build.psram = false
{extra_board}{src_filter}lib_deps =
{lib_deps}
lib_ldf_mode = {ldf_mode}
build_unflags = -std=gnu++11
build_flags =
{build_flags}
monitor_speed = 115200
upload_speed = 460800

; Same app, TFT dark, the phone is the only screen (Panel_Droidputter, dp_panel.h).
[env:m5cardputer-virtual]
extends = env:m5cardputer
build_flags = ${{env:m5cardputer.build_flags}} -DDROIDPUTTER_VIRTUAL=1
"""


def slug_of(url: str) -> str:
    m = re.search(r"github\.com[/:]([\w.-]+)/([\w.-]+?)(?:\.git)?/?$", url) or re.match(r"^([\w.-]+)/([\w.-]+)$", url)
    if not m:
        sys.exit(f"not a github url or owner/repo: {url}")
    return f"{m.group(1)}/{m.group(2)}"


def clone(slug: str, name: str, ref: str | None) -> Path:
    dst = SRC_ROOT / name
    if dst.exists():
        return dst
    SRC_ROOT.mkdir(parents=True, exist_ok=True)
    url = f"https://github.com/{slug}.git"
    if ref and re.fullmatch(r"[0-9a-f]{40}", ref):   # a commit: `git clone --branch` takes only branches/tags
        dst.mkdir()
        for cmd in (["git", "init", "-q"], ["git", "remote", "add", "origin", url],
                    ["git", "fetch", "-q", "--depth", "1", "origin", ref], ["git", "checkout", "-q", "FETCH_HEAD"]):
            subprocess.run(cmd, cwd=dst, check=True)
    else:
        cmd = ["git", "clone", "-q", "--depth", "1"] + (["--branch", ref] if ref else []) + [url, str(dst)]
        subprocess.run(cmd, check=True)
    return dst


def upstream_commit(src: Path) -> str:
    r = subprocess.run(["git", "rev-parse", "HEAD"], cwd=src, capture_output=True, text=True)
    return r.stdout.strip() or "unknown"


def read_ini(path: Path) -> configparser.ConfigParser | None:
    if not path.exists():
        return None
    cp = configparser.ConfigParser(allow_no_value=True, strict=False, interpolation=None, delimiters=("=",))
    cp.optionxform = str
    cp.read(path)
    return cp


def pick_env(cp: configparser.ConfigParser, wanted: str | None) -> str | None:
    envs = [s for s in cp.sections() if s.startswith("env:")]
    if wanted:
        return f"env:{wanted}" if f"env:{wanted}" in cp else None
    for e in envs:  # prefer an env that already targets the Cardputer's StampS3
        b = cp.get(e, "board", fallback="")
        if "stamps3" in b or "cardputer" in e.lower():
            return e
    return envs[0] if envs else None


def multiline(v: str) -> list[str]:
    return [ln.strip() for ln in v.strip().splitlines() if ln.strip() and not ln.strip().startswith(";")]


def anchored(path: Path, app: Path) -> str:
    """A path under apps/_src as PlatformIO sees it from the overlay dir: ${PROJECT_DIR}/../_src/<name>/...
    (${PROJECT_DIR} is a PlatformIO built-in = the project dir), so the ini carries no machine-specific path."""
    return "${PROJECT_DIR}/" + os.path.relpath(path, app)


def anchor_flag(flag: str, src: Path, app: Path) -> str:
    """-I include / -I src style include paths in upstream flags are relative to ITS project dir."""
    m = re.match(r"^(-I|-include|-imacros)\s*(\S+)$", flag)
    if m and not m.group(2).startswith("/"):
        return f"{m.group(1)} {anchored(src / m.group(2), app)}"
    return flag


def generate(slug: str, name: str, env_src: str | None, ref: str | None) -> dict:
    src = clone(slug, name, ref)
    app = APPS / name
    cp = read_ini(src / "platformio.ini")
    info = {"name": name, "repo": slug, "src": str(src), "upstream_commit": upstream_commit(src)}
    lib_deps, flags, extra_board, src_dir, extra_lib_dirs, src_filter, ldf_mode = [], [], [], None, [], "", "deep+"
    if cp:
        env = pick_env(cp, env_src)
        if not env:
            sys.exit(f"{slug}: no [env:*] in its platformio.ini")
        info["env_src"] = env
        src_dir = cp.get("platformio", "src_dir", fallback="src")
        for dep in multiline(cp.get(env, "lib_deps", fallback="")):
            if not SHIM_LIBS.search(dep):
                lib_deps.append(dep)
        for f in multiline(cp.get(env, "build_flags", fallback="")):
            f = re.sub(r"\s*;.*$", "", f)  # trailing ini comments
            if not f or STD_FLAG.match(f) and f in ("-std=gnu++11", "-std=gnu++14", "-std=c++11", "-std=c++14"):
                continue
            flags.append(anchor_flag(f, src, app))
        for key in ("board_build.partitions", "board_build.embed_files", "board_build.embed_txtfiles"):
            v = cp.get(env, key, fallback=None)
            if v:
                # built-in partition names (default_8MB.csv, ...) resolve inside the framework; only
                # repo-relative files get anchored on ${PROJECT_DIR}
                paths = [anchored(src / p, app) if not p.startswith("/") and (src / p).exists() else p for p in multiline(v)]
                extra_board.append(f"{key} = {paths[0]}" if len(paths) == 1 else f"{key} =\n" + "\n".join(f"    {p}" for p in paths))
    else:  # Arduino-IDE repo: the sketch dir is the source dir (PlatformIO compiles .ino)
        inos = sorted(src.rglob("*.ino"), key=lambda p: len(p.parts))
        if not inos:
            sys.exit(f"{slug}: neither platformio.ini nor a .ino found")
        src_dir = str(inos[0].parent.relative_to(src))
        info["env_src"] = "(ino)"
        lib_deps, unknown = infer_ino_deps(inos[0].parent, src)
        info["inferred_deps"] = lib_deps
        if unknown:
            info["unresolved_includes"] = unknown
        if (src / "libraries").is_dir():   # sketch-local library folder, Arduino-IDE style
            extra_lib_dirs.append(anchored(src / "libraries", app))
        # The Arduino IDE compiles the sketch folder's top-level files plus src/** -- not every
        # subfolder (miniacid ships an SDL desktop port next to the sketch).
        src_filter = "build_src_filter = +<*.ino> +<*.c> +<*.cpp> +<*.h> +<*.hpp> +<src/>\n"
        # Plain deep: deep+ evaluates #if guards with the S3 config and then drops FS for the
        # framework's SD_MMC library, which audio libraries include unconditionally (WebRadio).
        ldf_mode = "deep"
    src_dir_abs = src / src_dir
    if not src_dir_abs.exists():
        sys.exit(f"{slug}: src_dir {src_dir_abs} missing")
    if cp:
        # PlatformIO repos declare lib_deps, but not always all of them: Ultimate-Remote #includes <IRremote.hpp>
        # with no IRremote in its ini (Arduino-IDE users have it installed globally -- and so did this Mac's
        # ~/.platformio/lib, which hid the gap until the first GitHub-runner build failed, 2026-09-04). So the
        # include scan runs for PlatformIO repos too and adds registry deps whose package is not declared yet.
        inferred, unknown = infer_ino_deps(src_dir_abs, src)
        declared = {re.split(r"[@=]", d, 1)[0].strip().lower() for d in lib_deps}
        added = [d for d in inferred if re.split(r"[@=]", d, 1)[0].strip().lower() not in declared]
        if added:
            lib_deps += added
            info["inferred_deps"] = added
        if unknown:
            info["unresolved_includes"] = unknown
    if not any(STD_FLAG.match(f) for f in flags):
        flags.insert(0, "-std=gnu++17")
    if not any(f.startswith("-DCORE_DEBUG_LEVEL") for f in flags):
        flags.append("-DCORE_DEBUG_LEVEL=1")
    for must in ("-DM5GFX_BOARD=24", "-DARDUINO_USB_CDC_ON_BOOT=1", "-DDROIDPUTTER=1"):
        if must not in flags:
            flags.append(must)
    if (src / "include").is_dir():
        flags.append(f"-I {anchored(src / 'include', app)}")
    if (app / "include").is_dir():   # overlay-side compat headers (e.g. a credentials.h the repo only ships as an example)
        flags.append("-I include")
    flags.append("-Wall")
    flags.append("-I ../../shim/lib/DroidputterShim/src")
    lib_deps = [M5UNIFIED] + lib_deps + ["symlink://../../shim/lib/DroidputterShim"]
    lib_extra = ["    ../../shim/lib"] + ([f"    {anchored(src / 'lib', app)}"] if (src / "lib").is_dir() else []) + [f"    {d}" for d in extra_lib_dirs]

    app.mkdir(parents=True, exist_ok=True)
    ini = ENV_TEMPLATE.format(
        slug=slug, name=name, env_src=info["env_src"], src_dir=anchored(src_dir_abs, app),
        lib_extra_dirs="\n".join(lib_extra),
        extra_board="".join(x + "\n" for x in extra_board),
        src_filter=src_filter, ldf_mode=ldf_mode,
        lib_deps="\n".join(f"    {d}" for d in lib_deps),
        build_flags="\n".join(f"    {f}" for f in flags),
    )
    (app / "platformio.ini").write_text(ini)
    info.update(app=str(app.relative_to(REPO_ROOT)), src_dir=str(src_dir_abs), lib_deps=lib_deps)
    if not (app / "lib" / "M5GFX").is_dir() or not (app / "lib" / "M5Cardputer").is_dir():
        # apply.sh finds the pinned M5GFX/M5Cardputer versions in ~/.platformio/lib itself (downloading
        # them once if absent), so no libdeps dir is passed any more.
        r = subprocess.run(["bash", str(REPO_ROOT / "shim" / "apply.sh"), str(app)], capture_output=True, text=True)
        info["apply"] = "ok" if r.returncode == 0 else (r.stdout + r.stderr)[-800:]
    return info


def build(app: Path, upload: bool, env: str = "m5cardputer") -> dict:
    """`pio run -e <env>` in the overlay dir; the parts land in apps/<name>/.pio/build/<env> (reported as build_dir)."""
    cmd = [str(PIO), "run", "-e", env] + (["-t", "upload"] if upload else [])
    r = subprocess.run(cmd, cwd=app, capture_output=True, text=True)
    out = r.stdout + r.stderr
    res = {"ok": r.returncode == 0, "env": env, "build_dir": str((app / ".pio" / "build" / env).relative_to(REPO_ROOT))}
    m = re.search(r"RAM:.*?([\d.]+)%.*?used (\d+)", out, re.S)
    f = re.search(r"Flash:.*?([\d.]+)%.*?used (\d+)", out, re.S)
    if m:
        res["ram"] = f"{m.group(1)}% ({m.group(2)} B)"
    if f:
        res["flash"] = f"{f.group(1)}% ({f.group(2)} B)"
    (app / ".pio" / "overlay-build.log").parent.mkdir(parents=True, exist_ok=True)
    (app / ".pio" / "overlay-build.log").write_text(out)   # full log for triage
    if not res["ok"]:
        errs = [ln for ln in out.splitlines() if re.search(r"\berror\b:|fatal error|undefined reference|No such file|\*\*\* \[", ln)]
        res["error"] = errs[:8] if errs else out.splitlines()[-30:]
    if upload:
        res["uploaded"] = "Hard resetting" in out or "SUCCESS" in out
    return res


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("repo", help="github url or owner/repo")
    ap.add_argument("--name", help="overlay name (default: repo name, lowercased)")
    ap.add_argument("--env-src", help="upstream env to take lib_deps/build_flags from")
    ap.add_argument("--ref", help="git branch/tag to clone")
    ap.add_argument("--env", default="m5cardputer",
                    help="PlatformIO env to build: m5cardputer (Cardputer ADV, real TFT) or m5cardputer-virtual (bare ESP32-S3, phone-only)")
    ap.add_argument("--build", action="store_true")
    ap.add_argument("--upload", action="store_true")
    a = ap.parse_args()
    slug = slug_of(a.repo)
    name = a.name or slug.split("/")[1].lower()
    info = generate(slug, name, a.env_src, a.ref)
    if a.build or a.upload:
        info.update(build(REPO_ROOT / info["app"], a.upload, a.env))
        # Arduino-IDE repos straddle the NimBLE 1.x -> 2.x API break: try the other major once.
        if not info["ok"] and info.get("inferred_deps") and any("NimBLE" in e for e in info.get("error", [])):
            alt = "h2zero/NimBLE-Arduino@^2.3.7" if any(d.startswith("h2zero/NimBLE-Arduino@^1") for d in info["inferred_deps"]) else "h2zero/NimBLE-Arduino@^1.4.3"
            ini = REPO_ROOT / info["app"] / "platformio.ini"
            ini.write_text(re.sub(r"h2zero/NimBLE-Arduino@\^[0-9.]+", alt.split("@")[0] + "@" + alt.split("@")[1], ini.read_text()))
            for d in (REPO_ROOT / info["app"] / ".pio" / "libdeps").glob("*/NimBLE-Arduino*"):
                subprocess.run(["rm", "-rf", str(d)])
            info["nimble_retry"] = alt
            info.update(build(REPO_ROOT / info["app"], a.upload, a.env))
    print(json.dumps(info))
    return 0 if info.get("ok", True) else 1


if __name__ == "__main__":
    sys.exit(main())
