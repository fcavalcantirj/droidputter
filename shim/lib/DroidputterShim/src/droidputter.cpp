#include "droidputter.h"
#include "dp_internal.h"
// Arduino/M5GFX-only: guarded so `pio test -e native` (dp_frame.{h,cpp}, dp_rle.{h,cpp})
// can compile this library's src/ directory without pulling in ESP32 headers.
#ifdef ARDUINO
#include <Arduino.h>
#include <string.h>
namespace dp {
namespace internal {
bool started = false;
uint16_t scr_w = 240, scr_h = 135;
uint8_t scr_rot = 1;
uint32_t st_frames, st_bytes, st_dropped;
uint8_t crc8(uint8_t c, const uint8_t* p, size_t n) { while (n--) { c ^= *p++; for (int i = 0; i < 8; i++) c = (c & 0x80) ? (c << 1) ^ 0x07 : (c << 1); } return c; }
void put16(uint8_t* p, uint16_t v) { p[0] = v & 0xFF; p[1] = v >> 8; }
bool hasSpace(size_t n) { return (size_t)Serial.availableForWrite() >= n; }
#ifndef DROIDPUTTER_TXBUF
#define DROIDPUTTER_TXBUF 32768
#endif
uint32_t write_budget_ms = 40;
static bool usb_write(const uint8_t* p, size_t n) {
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
  size_t len = na + nb; uint8_t hdr[5] = { 0xD7, 0x50, type, (uint8_t)(len & 0xFF), (uint8_t)(len >> 8) };
  uint8_t c = crc8(0, hdr + 2, 3); c = crc8(c, a, na); if (b) c = crc8(c, b, nb);
  bool ok = usb_write(hdr, 5) && usb_write(a, na) && (!b || usb_write(b, nb)) && usb_write(&c, 1);
  if (ok) { st_frames++; st_bytes += 6 + len; } else st_dropped++;
}
}  // namespace internal

static char app_name[32] = "app";
static uint32_t last_stats, last_hello;
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
  Serial.setTxBufferSize(DROIDPUTTER_TXBUF); Serial.setTxTimeoutMs(20); sendHello();
}
// ---- phone -> ESP ----
static uint8_t rx[128]; static uint8_t rxn; static uint8_t held_r[16], held_c[16]; static uint8_t nheld;
static void onFrame(uint8_t type, const uint8_t* p, uint16_t n) {
  if (type == KEY && n >= 3) { uint8_t r = p[0], c = p[1], s = p[2]; int i = 0; for (; i < nheld; i++) if (held_r[i] == r && held_c[i] == c) break;
    if (s) { if (i == nheld && nheld < 16) { held_r[nheld] = r; held_c[nheld] = c; nheld++; } }
    else if (i < nheld) { for (int j = i; j + 1 < nheld; j++) { held_r[j] = held_r[j+1]; held_c[j] = held_c[j+1]; } nheld--; } }
  else if (type == HELLO_ACK) { sendHello();
#ifdef DROIDPUTTER_BENCH
    extern void dp_display_bench(int frames);
    dp_display_bench(30);
#endif
  }
  else if (type == PING_IN) { send(PING, nullptr, 0); }
}
void poll() {
  if (!internal::started) return;
  while (Serial.available()) { int b = Serial.read(); if (b < 0) break; rx[rxn++] = b;
    if (rxn == 1 && rx[0] != 0xD7) { rxn = 0; continue; } if (rxn == 2 && rx[1] != 0x50) { rxn = (rx[1] == 0xD7); rx[0] = 0xD7; continue; }
    if (rxn >= 5) { uint16_t len = rx[3] | (rx[4] << 8); if (len > 120) { rxn = 0; continue; } if (rxn == 5 + len + 1) { if (crc8(0, rx + 2, 3 + len) == rx[5 + len]) onFrame(rx[2], rx + 5, len); rxn = 0; } }
    if (rxn >= sizeof rx) rxn = 0; }
  uint32_t now = millis();
  if (now - last_stats >= 1000) { last_stats = now; uint8_t s[16]; uint32_t v[4] = { internal::st_frames, internal::st_bytes, internal::st_dropped, (uint32_t)ESP.getFreeHeap() }; memcpy(s, v, 16); send(STATS, s, 16); }
}
uint8_t injectedKeys(uint8_t* rows, uint8_t* cols, uint8_t max) { uint8_t n = nheld < max ? nheld : max; memcpy(rows, held_r, n); memcpy(cols, held_c, n); return n; }
}  // namespace dp
#endif  // ARDUINO
