#include "droidputter.h"
#include <Arduino.h>
#include <lgfx/v1/misc/pixelcopy.hpp>
#include <string.h>
namespace dp {
static const uint16_t MAXW = 240, MAXH = 135;
static uint8_t winbuf[MAXW * MAXH * 2];
static uint32_t win_x, win_y, win_w, win_h, cursor /*pixels*/, winpix;
static bool started = false, linked = false;
static uint32_t st_frames, st_bytes, st_dropped, last_stats, last_hello;
static char app_name[32] = "app"; static uint16_t scr_w = 240, scr_h = 135; static uint8_t scr_rot = 1;
static uint8_t crc8(uint8_t c, const uint8_t* p, size_t n) { while (n--) { c ^= *p++; for (int i = 0; i < 8; i++) c = (c & 0x80) ? (c << 1) ^ 0x07 : (c << 1); } return c; }
#ifndef DROIDPUTTER_TXBUF
#define DROIDPUTTER_TXBUF 32768
#endif
static uint32_t write_budget_ms = 40;
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
static void send(uint8_t type, const uint8_t* a, size_t na, const uint8_t* b = nullptr, size_t nb = 0) {
  if (!started) return;
  size_t len = na + nb; uint8_t hdr[5] = { 0xD7, 0x50, type, (uint8_t)(len & 0xFF), (uint8_t)(len >> 8) };
  uint8_t c = crc8(0, hdr + 2, 3); c = crc8(c, a, na); if (b) c = crc8(c, b, nb);
  bool ok = usb_write(hdr, 5) && usb_write(a, na) && (!b || usb_write(b, nb)) && usb_write(&c, 1);
  if (ok) { st_frames++; st_bytes += 6 + len; } else st_dropped++;
}
static void put16(uint8_t* p, uint16_t v) { p[0] = v & 0xFF; p[1] = v >> 8; }
static void sendHello() {
  uint8_t h[1 + 2 + 2 + 1 + 1 + 16 + 32] = {0}; h[0] = 0; put16(h + 1, scr_w); put16(h + 3, scr_h); h[5] = scr_rot; h[6] = 16;
  strncpy((char*)h + 7, "cardputer-adv", 16); strncpy((char*)h + 23, app_name, 32); send(HELLO, h, sizeof h); last_hello = millis();
}
void begin(const char* app, uint16_t w, uint16_t h, uint8_t rot) {
  if (started) return; started = true; if (app) strncpy(app_name, app, 31); scr_w = w; scr_h = h; scr_rot = rot;
  Serial.setTxBufferSize(DROIDPUTTER_TXBUF); Serial.setTxTimeoutMs(20); sendHello();
}
static void flushRect(uint32_t x, uint32_t y, uint32_t w, uint32_t h, const uint8_t* px) {
  uint8_t hd[8]; put16(hd, x); put16(hd + 2, y); put16(hd + 4, w); put16(hd + 6, h);
  uint32_t n = w * h, raw = n * 2, rle = 0;                       // pass 1: RLE size
  for (uint32_t i = 0; i < n;) { uint32_t j = i + 1; while (j < n && j - i < 255 && px[j*2] == px[i*2] && px[j*2+1] == px[i*2+1]) j++; rle += 3; i = j; }
  static uint8_t stage[32768];
  if (rle < raw && rle <= sizeof stage) {
    uint32_t o = 0;                                                  // pass 2
    for (uint32_t i = 0; i < n;) { uint32_t j = i + 1; while (j < n && j - i < 255 && px[j*2] == px[i*2] && px[j*2+1] == px[i*2+1]) j++; stage[o++] = j - i; stage[o++] = px[i*2]; stage[o++] = px[i*2+1]; i = j; }
    send(RECT_RLE, hd, 8, stage, o);
  } else send(RECT, hd, 8, px, raw);
}
static void flushPending() {
  if (!winpix || !cursor || cursor >= 0xFFFFFFF0) { cursor = 0; return; }
  uint32_t rows = cursor / win_w, rem = cursor % win_w;
  if (rows) flushRect(win_x, win_y, win_w, rows, winbuf);
  if (rem) flushRect(win_x, win_y + rows, rem, 1, winbuf + rows * win_w * 2);
  cursor = 0;
}
void window(uint16_t xs, uint16_t ys, uint16_t xe, uint16_t ye) {
  if (!started) begin(nullptr, scr_w, scr_h, scr_rot);
  flushPending(); win_x = xs; win_y = ys; win_w = xe - xs + 1; win_h = ye - ys + 1; winpix = win_w * win_h; cursor = 0;
  if (winpix * 2 > sizeof winbuf) winpix = sizeof winbuf / 2;
}
void bytes(const uint8_t* data, uint32_t nbytes) {
  uint32_t np = nbytes / 2; while (np) { uint32_t room = winpix - cursor; uint32_t k = np < room ? np : room; memcpy(winbuf + cursor * 2, data, k * 2); cursor += k; data += k * 2; np -= k; if (cursor >= winpix) { flushRect(win_x, win_y, win_w, win_h, winbuf); cursor = 0; } if (!room) break; }
}
void repeat(uint32_t raw, uint32_t npixels) {
  uint8_t c0 = raw & 0xFF, c1 = (raw >> 8) & 0xFF;
  while (npixels) { uint32_t room = winpix - cursor; uint32_t k = npixels < room ? npixels : room; uint8_t* p = winbuf + cursor * 2; for (uint32_t i = 0; i < k; i++) { p[i*2] = c0; p[i*2+1] = c1; } cursor += k; npixels -= k; if (cursor >= winpix) { flushRect(win_x, win_y, win_w, win_h, winbuf); cursor = 0; } if (!room) break; }
}
void fill(uint16_t x, uint16_t y, uint16_t w, uint16_t h, uint32_t raw) {
  uint8_t p[10]; put16(p, x); put16(p + 2, y); put16(p + 4, w); put16(p + 6, h); p[8] = raw & 0xFF; p[9] = (raw >> 8) & 0xFF; send(FILL, p, 10); cursor = 0; winpix = 0;
}
void pixel(uint16_t x, uint16_t y, uint32_t raw) { fill(x, y, 1, 1, raw); }
void pixelsConv(lgfx::v1::pixelcopy_t* param, uint32_t np) {
  lgfx::v1::pixelcopy_t p2 = *param; uint32_t room = winpix - cursor; if (np > room) np = room; if (!np) return;
  p2.fp_copy(winbuf + cursor * 2, 0, np, &p2); cursor += np; if (cursor >= winpix) { flushRect(win_x, win_y, win_w, win_h, winbuf); cursor = 0; }
}
// ---- phone -> ESP ----
static uint8_t rx[128]; static uint8_t rxn; static uint8_t held_r[16], held_c[16]; static uint8_t nheld;
static void onFrame(uint8_t type, const uint8_t* p, uint16_t n) {
  if (type == KEY && n >= 3) { uint8_t r = p[0], c = p[1], s = p[2]; int i = 0; for (; i < nheld; i++) if (held_r[i] == r && held_c[i] == c) break;
    if (s) { if (i == nheld && nheld < 16) { held_r[nheld] = r; held_c[nheld] = c; nheld++; } }
    else if (i < nheld) { for (int j = i; j + 1 < nheld; j++) { held_r[j] = held_r[j+1]; held_c[j] = held_c[j+1]; } nheld--; } }
  else if (type == HELLO_ACK) { linked = true; sendHello();
#ifdef DROIDPUTTER_BENCH
    write_budget_ms = 3000;
    for (int f = 0; f < 30; f++) { for (uint32_t i = 0; i < (uint32_t)MAXW * MAXH; i++) { uint16_t v = (uint16_t)(i * 7 + f * 13); winbuf[i*2] = v >> 8; winbuf[i*2+1] = v; }
      flushRect(0, 0, MAXW, MAXH, winbuf); }
    write_budget_ms = 40; cursor = 0;
#endif
  }
  else if (type == PING_IN) { send(PING, nullptr, 0); }
}
void poll() {
  if (!started) return;
  while (Serial.available()) { int b = Serial.read(); if (b < 0) break; rx[rxn++] = b;
    if (rxn == 1 && rx[0] != 0xD7) { rxn = 0; continue; } if (rxn == 2 && rx[1] != 0x50) { rxn = (rx[1] == 0xD7); rx[0] = 0xD7; continue; }
    if (rxn >= 5) { uint16_t len = rx[3] | (rx[4] << 8); if (len > 120) { rxn = 0; continue; } if (rxn == 5 + len + 1) { if (crc8(0, rx + 2, 3 + len) == rx[5 + len]) onFrame(rx[2], rx + 5, len); rxn = 0; } }
    if (rxn >= sizeof rx) rxn = 0; }
  uint32_t now = millis();
  if (now - last_stats >= 1000) { last_stats = now; uint8_t s[16]; uint32_t v[4] = { st_frames, st_bytes, st_dropped, (uint32_t)ESP.getFreeHeap() }; memcpy(s, v, 16); send(STATS, s, 16); }
}
uint8_t injectedKeys(uint8_t* rows, uint8_t* cols, uint8_t max) { uint8_t n = nheld < max ? nheld : max; memcpy(rows, held_r, n); memcpy(cols, held_c, n); return n; }
}
