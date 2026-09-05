// Panel tee, coalescing edition (2026-09-03): every Panel_LCD write lands in a wire-order shadow
// framebuffer (dp_shadow.h) and only the dirty ROW BAND is flushed, as one RECT_RLE (or raw RECT),
// at most once per DP_FLUSH_MS. Before this, a text redraw was ~1,500 FILL frames streamed one by
// one: the phone painted each as it arrived and showed torn, half-drawn screens (Felipe's
// screenshots 2026-09-03 12:55), and every frame cost the ESP ~600 us of USB work. Now a redraw
// is a handful of coherent frames. RAM: the shadow (64,800 B) replaces the old window buffer and
// the 64 KB pixel-conversion buffer is gone (the RLE encoder reads wire order directly).
// Governed like before: a full/dead USB ring never blocks the app -- the band simply stays dirty
// and is retried on the next tick (the display self-heals once the host reads again).
#include "droidputter.h"
#include "dp_internal.h"
#include "dp_rle.h"
#include "dp_shadow.h"
#ifdef ARDUINO
#include <Arduino.h>
#include <lgfx/v1/misc/pixelcopy.hpp>
#include <string.h>
namespace dp {
#ifndef DP_FLUSH_MS
#define DP_FLUSH_MS 16
#endif
static const uint16_t MAXW = DP_SHADOW_W;
static uint8_t stage[32768];             // RLE staging for one flush
static uint8_t convbuf[MAXW * 2];        // pixelsConv chunk (one row of converted pixels)
static uint32_t last_flush_ms = 0;
static bool shadow_ready = false;

static void ensureShadow() { if (!shadow_ready) { dp_shadow_reset(); shadow_ready = true; } }

// Flush the dirty row band [y0..y1] (full width): as many whole rows from the top as fit the ring's free
// space right now, as one RECT_RLE (or one raw RECT when RLE would not be shorter); the rows that did not fit
// stay dirty for the next tick. Before 2026-09-04 it was the whole band or nothing, so a band whose RLE beat
// the 32 KB stage fell back to raw (up to 64,800 B) that a 32 KB ring can never take: the band stayed dirty
// forever and the mirror froze on photo-like content. Returns true if something went out (or nothing was
// dirty); false = paced, or not even one row fits (ring full / dead link -> the watchdog's business).
static bool flushDirty(bool force) {
  if (!internal::linked) return false;   // the shadow keeps collecting; nothing leaves before HELLO_ACK
  internal::LinkLock guard;
  uint16_t y0, y1;
  if (!dp_shadow_dirty(&y0, &y1)) return true;
  uint32_t now = millis();
  if (!force && now - last_flush_ms < DP_FLUSH_MS) return false;
  last_flush_ms = now;
  uint16_t h = (uint16_t)(y1 - y0 + 1);
  const uint8_t* px = dp_shadow_buffer() + (size_t)y0 * MAXW * 2;   // rows are contiguous
  size_t free_ = internal::txFree();
  size_t budget = free_ > 6 + 8 ? free_ - (6 + 8) : 0;              // frame header + RECT header + crc
  if (budget > sizeof stage) budget = sizeof stage;
  uint16_t rleRows = 0;
  size_t rleLen = budget ? dp_rle_encode_rows_be(px, MAXW, h, stage, budget, &rleRows) : 0;
  uint16_t rawRows = (uint16_t)(budget / ((size_t)MAXW * 2)); if (rawRows > h) rawRows = h;
  bool useRle = rleRows > 0 && rleRows >= rawRows && rleLen < (size_t)rleRows * MAXW * 2;
  uint16_t rows = useRle ? rleRows : rawRows;
  if (!rows) { internal::st_dropped++; return false; }               // not one row fits: nothing to do this tick
  uint8_t hd[8]; internal::put16(hd, 0); internal::put16(hd + 2, y0); internal::put16(hd + 4, MAXW); internal::put16(hd + 6, rows);
  if (useRle) internal::send(RECT_RLE, hd, 8, stage, rleLen);
  else internal::send(RECT, hd, 8, px, (size_t)rows * MAXW * 2);
  dp_shadow_clear_dirty_top(rows);
  return true;
}

// Every entry point below updates the shadow from the very first write, linked or not (2026-09-04): the shadow
// is the phone's whole truth, so a HELLO_ACK that arrives after the app has drawn its screen is answered from
// memory (resync = mark all dirty + flush) instead of reading the panel back over SPI from another task.
// Nothing is sent before HELLO_ACK (flushDirty gates on `linked`); the per-write cost is a memcpy into RAM.
void window(uint16_t xs, uint16_t ys, uint16_t xe, uint16_t ye) {
  if (!internal::started) begin(nullptr, internal::scr_w, internal::scr_h, internal::scr_rot);
  internal::pollIfDue();   // fast path for apps that draw; the link task covers the ones that do not
  ensureShadow();
  flushDirty(false);
  dp_shadow_set_window(xs, ys, xe, ye);
}
void bytes(const uint8_t* data, uint32_t nbytes) {
  ensureShadow(); dp_shadow_write_bytes(data, nbytes);
}
void repeat(uint32_t raw, uint32_t npixels) {
  ensureShadow(); dp_shadow_repeat((uint8_t)(raw & 0xFF), (uint8_t)((raw >> 8) & 0xFF), npixels);
}
void fill(uint16_t x, uint16_t y, uint16_t w, uint16_t h, uint32_t raw) {
  internal::pollIfDue();
  ensureShadow(); flushDirty(false);
  dp_shadow_fill(x, y, w, h, (uint8_t)(raw & 0xFF), (uint8_t)((raw >> 8) & 0xFF));
}
void pixel(uint16_t x, uint16_t y, uint32_t raw) { fill(x, y, 1, 1, raw); }
void pixelsConv(lgfx::v1::pixelcopy_t* param, uint32_t np) {
  ensureShadow();
  lgfx::v1::pixelcopy_t p2 = *param;
  while (np) {
    uint32_t k = np < MAXW ? np : MAXW;
    p2.fp_copy(convbuf, 0, k, &p2);
    dp_shadow_write_bytes(convbuf, k * 2);
    np -= k;
  }
}
namespace internal {
// Called from dp::poll() every loop: the periodic flush for apps that draw in bursts.
void flushTick() { if (linked && shadow_ready) flushDirty(false); }
// Full-frame resync on HELLO_ACK (and on the phone's Repaint button): the shadow has every pixel the app
// drew since its first write, so mark it all dirty and flush -- memory only, safe from the link task
// (before 2026-09-04 this read the panel back over SPI, which only the app's task may do).
void resync() {
  ensureShadow();
  dp_shadow_mark_all_dirty();
  flushDirty(true);
}
}  // namespace internal
#ifdef DROIDPUTTER_BENCH
// Raw full-frame throughput probe (S5): bypasses the governor and RLE on purpose -- it measures
// how fast the HWCDC ring drains under sustained backpressure, so it must block rather than drop.
void dp_display_bench(int frames) {
  internal::write_budget_ms = 3000;
  static uint8_t buf[(size_t)MAXW * MAXH * 2];
  for (int f = 0; f < frames; f++) {
    for (uint32_t i = 0; i < (uint32_t)MAXW * MAXH; i++) { uint16_t v = (uint16_t)(i * 7 + f * 13); buf[i*2] = v >> 8; buf[i*2+1] = v; }
    uint8_t hd[8]; internal::put16(hd, 0); internal::put16(hd + 2, 0); internal::put16(hd + 4, MAXW); internal::put16(hd + 6, MAXH);
    internal::send(RECT, hd, 8, buf, (uint32_t)MAXW * MAXH * 2);
  }
  internal::write_budget_ms = 40;
}
#endif
}  // namespace dp
#endif  // ARDUINO
