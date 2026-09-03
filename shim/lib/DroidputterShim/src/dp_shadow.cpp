#include "dp_shadow.h"
#include <string.h>

namespace dp {

static uint8_t fb[(size_t)DP_SHADOW_W * DP_SHADOW_H * 2];
static uint16_t wx0, wy0, ww, wh;      // window
static uint32_t wcur, wpix;            // cursor (pixels from the window start), window pixel count
static int32_t dy0 = -1, dy1 = -1;     // dirty rows, inclusive; -1 = clean

static inline void dirtyRows(uint16_t a, uint16_t b) {
  if (dy0 < 0 || a < dy0) dy0 = a;
  if (dy1 < 0 || b > dy1) dy1 = b;
}

void dp_shadow_reset() {
  memset(fb, 0, sizeof fb); wx0 = wy0 = 0; ww = DP_SHADOW_W; wh = DP_SHADOW_H; wcur = 0; wpix = (uint32_t)ww * wh; dy0 = dy1 = -1;
}

void dp_shadow_set_window(uint16_t xs, uint16_t ys, uint16_t xe, uint16_t ye) {
  if (xs >= DP_SHADOW_W) xs = DP_SHADOW_W - 1; if (ys >= DP_SHADOW_H) ys = DP_SHADOW_H - 1;
  if (xe >= DP_SHADOW_W) xe = DP_SHADOW_W - 1; if (ye >= DP_SHADOW_H) ye = DP_SHADOW_H - 1;
  if (xe < xs) xe = xs; if (ye < ys) ye = ys;
  wx0 = xs; wy0 = ys; ww = xe - xs + 1; wh = ye - ys + 1; wpix = (uint32_t)ww * wh; wcur = 0;
}

// Writes n pixels at the cursor from a per-pixel source callback-free loop: two variants below
// share this row walker. `get` copies 2 bytes for pixel i into dst.
static void walk(size_t npix, const uint8_t* src, uint8_t c0, uint8_t c1) {
  while (npix) {
    uint32_t row = wcur / ww, col = wcur % ww;
    uint32_t left = ww - col; if (left > npix) left = (uint32_t)npix;
    uint8_t* dst = fb + (((size_t)(wy0 + row) * DP_SHADOW_W + wx0 + col) * 2);
    if (src) { memcpy(dst, src, left * 2); src += left * 2; }
    else { for (uint32_t i = 0; i < left; i++) { dst[i * 2] = c0; dst[i * 2 + 1] = c1; } }
    dirtyRows(wy0 + row, wy0 + row);
    wcur += left; npix -= left;
    if (wcur >= wpix) wcur = 0;                     // panel wraps to the window start
  }
}

void dp_shadow_write_bytes(const uint8_t* be, size_t nbytes) { walk(nbytes / 2, be, 0, 0); }
void dp_shadow_repeat(uint8_t c0, uint8_t c1, size_t npixels) { walk(npixels, nullptr, c0, c1); }

void dp_shadow_fill(uint16_t x, uint16_t y, uint16_t w, uint16_t h, uint8_t c0, uint8_t c1) {
  if (x >= DP_SHADOW_W || y >= DP_SHADOW_H || !w || !h) return;
  if (x + w > DP_SHADOW_W) w = DP_SHADOW_W - x; if (y + h > DP_SHADOW_H) h = DP_SHADOW_H - y;
  for (uint16_t r = 0; r < h; r++) {
    uint8_t* dst = fb + (((size_t)(y + r) * DP_SHADOW_W + x) * 2);
    for (uint16_t i = 0; i < w; i++) { dst[i * 2] = c0; dst[i * 2 + 1] = c1; }
  }
  dirtyRows(y, y + h - 1);
}

void dp_shadow_load_row(uint16_t y, const uint8_t* be) {
  if (y >= DP_SHADOW_H) return;
  memcpy(fb + (size_t)y * DP_SHADOW_W * 2, be, (size_t)DP_SHADOW_W * 2); dirtyRows(y, y);
}

bool dp_shadow_dirty(uint16_t* y0, uint16_t* y1) {
  if (dy0 < 0) return false;
  if (y0) *y0 = (uint16_t)dy0; if (y1) *y1 = (uint16_t)dy1; return true;
}
void dp_shadow_mark_all_dirty() { dy0 = 0; dy1 = DP_SHADOW_H - 1; }
void dp_shadow_clear_dirty() { dy0 = dy1 = -1; }
const uint8_t* dp_shadow_buffer() { return fb; }
uint32_t dp_shadow_cursor() { return wcur; }

}  // namespace dp
