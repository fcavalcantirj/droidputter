// Panel tee: coalesces the writes inside one Panel_LCD setWindow() into a single
// FILL/RECT/RECT_RLE, governed so a slow/absent USB host never blocks the app.
#include "droidputter.h"
#include "dp_internal.h"
#include "dp_rle.h"
#ifdef ARDUINO
#include <Arduino.h>
#include <lgfx/v1/misc/pixelcopy.hpp>
#include <string.h>
namespace dp {
static const uint16_t MAXW = 240, MAXH = 135;
static uint8_t winbuf[MAXW * MAXH * 2];      // pixel bytes, big-endian wire order (panel bus order)
static uint32_t win_x, win_y, win_w, win_h, cursor /*pixels*/, winpix;

// Governor: an outgoing RECT/RECT_RLE needs at most 6 (frame overhead) + 8 (rect
// header) + n*2 (raw pixels) bytes. If the HWCDC ring doesn't have that much free
// right now, drop and count the whole rect instead of blocking the app in usb_write's
// wait loop -- a slow/detached phone must never slow the panel down.
static void flushRect(uint32_t x, uint32_t y, uint32_t w, uint32_t h, const uint8_t* px) {
  uint32_t n = w * h, raw = n * 2;
  if (!internal::hasSpace(6 + 8 + raw)) { internal::st_dropped++; return; }
  uint8_t hd[8]; internal::put16(hd, x); internal::put16(hd + 2, y); internal::put16(hd + 4, w); internal::put16(hd + 6, h);
  static uint16_t pxval[MAXW * MAXH];
  for (uint32_t i = 0; i < n; i++) pxval[i] = (uint16_t)(px[i * 2] << 8 | px[i * 2 + 1]);
  static uint8_t stage[32768];
  size_t rleLen = dp_rle_encode(pxval, n, stage, sizeof stage);
  if (rleLen) internal::send(RECT_RLE, hd, 8, stage, rleLen);
  else internal::send(RECT, hd, 8, px, raw);
}
static void flushPending() {
  if (!winpix || !cursor || cursor >= 0xFFFFFFF0) { cursor = 0; return; }
  uint32_t rows = cursor / win_w, rem = cursor % win_w;
  if (rows) flushRect(win_x, win_y, win_w, rows, winbuf);
  if (rem) flushRect(win_x, win_y + rows, rem, 1, winbuf + rows * win_w * 2);
  cursor = 0;
}
void window(uint16_t xs, uint16_t ys, uint16_t xe, uint16_t ye) {
  if (!internal::started) begin(nullptr, internal::scr_w, internal::scr_h, internal::scr_rot);
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
  uint8_t p[10]; internal::put16(p, x); internal::put16(p + 2, y); internal::put16(p + 4, w); internal::put16(p + 6, h); p[8] = raw & 0xFF; p[9] = (raw >> 8) & 0xFF; internal::send(FILL, p, 10); cursor = 0; winpix = 0;
}
void pixel(uint16_t x, uint16_t y, uint32_t raw) { fill(x, y, 1, 1, raw); }
void pixelsConv(lgfx::v1::pixelcopy_t* param, uint32_t np) {
  lgfx::v1::pixelcopy_t p2 = *param; uint32_t room = winpix - cursor; if (np > room) np = room; if (!np) return;
  p2.fp_copy(winbuf + cursor * 2, 0, np, &p2); cursor += np; if (cursor >= winpix) { flushRect(win_x, win_y, win_w, win_h, winbuf); cursor = 0; }
}
#ifdef DROIDPUTTER_BENCH
// Raw full-frame throughput probe (S5): bypasses the governor and RLE on purpose --
// it measures how fast the HWCDC ring drains under sustained backpressure, so it
// must block (via a raised write_budget_ms) rather than drop.
void dp_display_bench(int frames) {
  internal::write_budget_ms = 3000;
  for (int f = 0; f < frames; f++) {
    for (uint32_t i = 0; i < (uint32_t)MAXW * MAXH; i++) { uint16_t v = (uint16_t)(i * 7 + f * 13); winbuf[i*2] = v >> 8; winbuf[i*2+1] = v; }
    uint8_t hd[8]; internal::put16(hd, 0); internal::put16(hd + 2, 0); internal::put16(hd + 4, MAXW); internal::put16(hd + 6, MAXH);
    internal::send(RECT, hd, 8, winbuf, (uint32_t)MAXW * MAXH * 2);
  }
  internal::write_budget_ms = 40; cursor = 0;
}
#endif
}  // namespace dp
#endif  // ARDUINO
