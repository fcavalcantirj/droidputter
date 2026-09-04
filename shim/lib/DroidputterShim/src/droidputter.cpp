#include "droidputter.h"
#include "dp_internal.h"
#include "dp_keys.h"
#include "dp_gps.h"
// Arduino/M5GFX-only: guarded so `pio test -e native` (dp_frame.{h,cpp}, dp_rle.{h,cpp})
// can compile this library's src/ directory without pulling in ESP32 headers.
#ifdef ARDUINO
#include <Arduino.h>
#include <string.h>
// Some apps silence their own logging by macro-redefining Serial to a null sink for every
// translation unit (M5PORKCHOP: `-include src/core/logging.h`, `#define Serial PorkchopSerialSink`).
// The link must keep the real HWCDC object (`extern HWCDC Serial` in arduino-esp32 2.0.17).
#ifdef Serial
#undef Serial
#endif
#include "hal/usb_serial_jtag_ll.h"
#include "driver/periph_ctrl.h"
#include "esp_system.h"
namespace dp {
namespace internal {
bool started = false;
uint32_t last_tx_ok_ms = 0;   // millis() of the last frame that fully entered the HWCDC ring
uint32_t last_rx_ms = 0;      // millis() of the last valid inbound frame (the host is alive and talking)
uint32_t cdc_kicks = 0, cdc_reinits = 0;
bool linked = false;
uint16_t scr_w = 240, scr_h = 135;
uint8_t scr_rot = 1;
uint32_t st_frames, st_bytes, st_dropped;
uint8_t crc8(uint8_t c, const uint8_t* p, size_t n) { while (n--) { c ^= *p++; for (int i = 0; i < 8; i++) c = (c & 0x80) ? (c << 1) ^ 0x07 : (c << 1); } return c; }
void put16(uint8_t* p, uint16_t v) { p[0] = v & 0xFF; p[1] = v >> 8; }
bool hasSpace(size_t n) { return (size_t)Serial.availableForWrite() >= n; }
size_t txFree() { int f = Serial.availableForWrite(); return f > 0 ? (size_t)f : 0; }
#ifndef DROIDPUTTER_TXBUF
#define DROIDPUTTER_TXBUF 32768
#endif
uint32_t write_budget_ms = 40;
static bool usb_write(const uint8_t* p, size_t n) {
  // A full ring means the host is not draining (dead link, suspended port): waiting the write budget
  // here turned a dead link into a 40 ms stall per frame and a ~60 s screen redraw (2026-09-03 [REAL]).
  // Callers pre-check hasSpace(); if the ring is full anyway, drop now and let the watchdog recover.
  if (Serial.availableForWrite() <= 0) return false;
  uint32_t t0 = millis();
  while (n) {
    int sp = Serial.availableForWrite();
    if (sp <= 0) { if (millis() - t0 > write_budget_ms) return false; delay(1); continue; }
    size_t w = Serial.write(p, (size_t)sp < n ? (size_t)sp : n);
    if (!w) { if (millis() - t0 > write_budget_ms) return false; delay(1); continue; }
    p += w; n -= w;
  }
  return true;
}
// frame = header(5) + payload chunks; payload may be passed in up to 2 pieces
void send(uint8_t type, const uint8_t* a, size_t na, const uint8_t* b, size_t nb) {
  if (!started) return;
  size_t len = na + nb;
  if (!hasSpace(6 + len)) { st_dropped++; return; }   // whole frame or nothing: no half frames on the wire
  uint8_t hdr[5] = { 0xD7, 0x50, type, (uint8_t)(len & 0xFF), (uint8_t)(len >> 8) };
  uint8_t c = crc8(0, hdr + 2, 3); c = crc8(c, a, na); if (b) c = crc8(c, b, nb);
  bool ok = usb_write(hdr, 5) && usb_write(a, na) && (!b || usb_write(b, nb)) && usb_write(&c, 1);
  if (ok) { st_frames++; st_bytes += 6 + len; last_tx_ok_ms = millis(); } else st_dropped++;
}
}  // namespace internal

static char app_name[32] = "app";
static uint32_t last_stats, last_hello;
static RTC_NOINIT_ATTR uint32_t wd_boot_marker;   // survives esp_restart: 0xDEAD0000 | rung that rebooted us
using internal::send;
using internal::put16;
using internal::crc8;

static void sendHello() {
  uint8_t h[1 + 2 + 2 + 1 + 1 + 16 + 32] = {0}; h[0] = 0;
  put16(h + 1, internal::scr_w); put16(h + 3, internal::scr_h); h[5] = internal::scr_rot; h[6] = 16;
  strncpy((char*)h + 7, "cardputer-adv", 16); strncpy((char*)h + 23, app_name, 32); send(HELLO, h, sizeof h); last_hello = millis();
}
void begin(const char* app, uint16_t w, uint16_t h, uint8_t rot) {
  if (internal::started) return;
  internal::started = true; if (app) strncpy(app_name, app, 31);
  internal::scr_w = w; internal::scr_h = h; internal::scr_rot = rot;
  // Some apps (e.g. the vendored M5Cardputer inputText example) never call
  // Serial.begin() themselves and rely on ARDUINO_USB_CDC_ON_BOOT alone --
  // that leaves the HWCDC ring buffers unset and every tee write silently
  // no-ops. HWCDC::begin() is idempotent (only allocates if not already
  // set up), so calling it here is a no-op for apps that already begin()
  // their own Serial and the fix for apps that never do -- either way the
  // shim, not the app, owns making the tee transparently work.
  Serial.begin(115200);
  Serial.setTxBufferSize(DROIDPUTTER_TXBUF); Serial.setTxTimeoutMs(20); sendHello();
  // Boot report (LOG 0x07): why we reset and whether our own watchdog did it -- readable by any receiver.
  uint32_t marker = wd_boot_marker; wd_boot_marker = 0;
  char msg[48]; int k = snprintf(msg, sizeof msg, "boot rst=%d wd=%lu", (int)esp_reset_reason(),
                                 (unsigned long)((marker & 0xFFFF0000u) == 0xDEAD0000u ? (marker & 0xFFFF) : 0));
  send(LOG, (const uint8_t*)msg, k > 0 ? (size_t)k : 0);
}
// ---- phone -> ESP ----
static uint8_t rx[128]; static uint8_t rxn;
static void onFrame(uint8_t type, const uint8_t* p, uint16_t n) {
  internal::last_rx_ms = millis();
  if (type == KEY && n >= 3) { dp_keys_push(p[0], p[1], p[2]); }
  else if (type == GPS_NMEA) { dp_gps_push(p, n); }
  else if (type == HELLO_ACK) { internal::linked = true; sendHello(); internal::resync();
#ifdef DROIDPUTTER_BENCH
    extern void dp_display_bench(int frames);
    dp_display_bench(30);
#endif
  }
  // PING_IN also resends HELLO (not just PONG): a phone that connects late and
  // only knows to probe (not yet ack its screen size) still learns the geometry.
  else if (type == PING_IN) { send(PING, nullptr, 0); sendHello(); }
}
// TX watchdog. Reproduced on the Mac 2026-09-03 [REAL]: a USB suspend of ~30 s (no SOF, VBUS kept --
// what an Android host does to an idle/dropped OTG port) while the tee streams and NMEA floods in leaves
// arduino-esp32 2.0.17's HWCDC mute after resume: SOF is back (isPlugged), but `connected` stays false,
// the TX ring is full, and HWCDC only re-arms its drain inside write() -- which nobody calls while the
// ring is full. Inbound frames still arrive (the ISR drains the RX FIFO). Symptom on the phone:
// enumerated, silent, PING_IN unanswered, until a power-cycle. Remedy ladder, driven only when the host
// is provably talking to us (a valid inbound frame in the last 2 s) and no frame has entered the ring
// for 2 s: (1) re-arm the drain (FIFO flush + IN_EMPTY interrupt + one real Serial.write()),
// (2) 5 s: HWCDC end()/begin() (also pulls D+/D- low, so the host re-enumerates us and the phone app
// re-links on its attach intent), (3) 10 s: reset the USB Serial/JTAG peripheral and begin() again,
// (4) 15 s: esp_restart(). Once frames flow again a LOG frame reports the rung that healed it.
static uint8_t wd_rung = 0;          // last rung fired for the current mute episode
static uint32_t wd_last_fire_ms = 0;
static uint32_t wd_episode_ms = 0;   // when the host was first seen talking while TX was already dead
static void cdcBegin() {
  Serial.begin(115200);
  Serial.setTxBufferSize(DROIDPUTTER_TXBUF); Serial.setTxTimeoutMs(20);
}
static void txWatchdog(uint32_t now) {
  using namespace internal;
  if (!last_tx_ok_ms || !last_rx_ms) return;
  if (now - last_tx_ok_ms < 2000) {                      // TX alive
    if (wd_rung) {                                        // ...again: report which rung did it
      char msg[40]; int k = snprintf(msg, sizeof msg, "cdc-recovered rung=%u", (unsigned)wd_rung);
      wd_rung = 0;
      send(LOG, (const uint8_t*)msg, k > 0 ? (size_t)k : 0);
      sendHello(); if (linked) resync();
    }
    wd_episode_ms = 0;
    return;
  }
  if (now - last_rx_ms > 2000) { wd_episode_ms = 0; return; }   // host silent too: nothing to recover
  // TX dead while the host talks. Escalate on time since the host came back, not since TX died: a 30 s
  // suspend must not jump straight to the reboot rung the moment the host resumes.
  if (!wd_episode_ms) wd_episode_ms = now;
  uint32_t mute = now - wd_episode_ms;
  if (mute < 2000) return;
  if (now - wd_last_fire_ms < 1000) return;               // one action per second at most
  wd_last_fire_ms = now;
  if (mute < 5000) {
    if (wd_rung < 1) wd_rung = 1;
    cdc_kicks++;
    usb_serial_jtag_ll_txfifo_flush();
    usb_serial_jtag_ll_ena_intr_mask(USB_SERIAL_JTAG_INTR_SERIAL_IN_EMPTY);
    static const uint8_t ping[6] = { 0xD7, 0x50, PING, 0, 0, 0x00 };  // crc8 over 06 00 00
    uint8_t c = crc8(0, ping + 2, 3); uint8_t f[6]; memcpy(f, ping, 5); f[5] = c;
    Serial.write(f, 6);                                     // forces HWCDC through write()'s re-arm path
  } else if (mute < 10000) {
    if (wd_rung < 2) { wd_rung = 2; cdc_reinits++; Serial.end(); delay(50); cdcBegin(); }
  } else if (mute < 15000) {
    if (wd_rung < 3) { wd_rung = 3; cdc_reinits++; Serial.end(); periph_module_reset(PERIPH_USB_MODULE); delay(50); cdcBegin(); }
  } else {
    wd_boot_marker = 0xDEAD0000u | 4u;
    esp_restart();
  }
}
void poll() {
  if (!internal::started) return;
  // Link-down releases every held key so a phone that disconnects mid-keypress
  // never leaves a key stuck down on the ESP (no disconnect detection sets
  // internal::linked back to false yet -- see droidputter.cpp task 14 notes --
  // this reacts correctly once a future task adds it).
  static bool wasLinked = false;
  if (wasLinked && !internal::linked) dp_keys_release_all();
  wasLinked = internal::linked;
  while (Serial.available()) { int b = Serial.read(); if (b < 0) break; rx[rxn++] = b;
    if (rxn == 1 && rx[0] != 0xD7) { rxn = 0; continue; } if (rxn == 2 && rx[1] != 0x50) { rxn = (rx[1] == 0xD7); rx[0] = 0xD7; continue; }
    if (rxn >= 5) { uint16_t len = rx[3] | (rx[4] << 8); if (len > 120) { rxn = 0; continue; } if (rxn == 5 + len + 1) { if (crc8(0, rx + 2, 3 + len) == rx[5 + len]) onFrame(rx[2], rx + 5, len); rxn = 0; } }
    if (rxn >= sizeof rx) rxn = 0; }
  uint32_t now = millis();
  if (now - last_stats >= 1000) { last_stats = now; uint8_t s[16]; uint32_t v[4] = { internal::st_frames, internal::st_bytes, internal::st_dropped, (uint32_t)ESP.getFreeHeap() }; memcpy(s, v, 16); send(STATS, s, 16); }
  txWatchdog(now);
  internal::flushTick();
}
uint8_t injectedKeys(uint8_t* rows, uint8_t* cols, uint8_t max) { return dp_keys_snapshot(rows, cols, max); }
void linkDebug(uint32_t* kicks, uint32_t* reinits, uint32_t* tx_mute_ms, uint32_t* rx_age_ms) {
  uint32_t now = millis();
  if (kicks) *kicks = internal::cdc_kicks; if (reinits) *reinits = internal::cdc_reinits;
  if (tx_mute_ms) *tx_mute_ms = internal::last_tx_ok_ms ? now - internal::last_tx_ok_ms : 0;
  if (rx_age_ms) *rx_age_ms = internal::last_rx_ms ? now - internal::last_rx_ms : 0;
}
}  // namespace dp

// Arduino Stream over dp_gps.h's ring (see droidputter.h: droidputter_gps()).
// Inbound-only: write() is a no-op, this is not a real UART, just the queue
// GPS_NMEA frames land in.
class DroidputterGPS : public Stream {
 public:
  int available() override { return (int)dp::dp_gps_available(); }
  int read() override { return dp::dp_gps_read(); }
  int peek() override { return dp::dp_gps_peek(); }
  size_t write(uint8_t) override { return 0; }
};
static DroidputterGPS gpsStream;
Stream& droidputter_gps() { return gpsStream; }
#endif  // ARDUINO
