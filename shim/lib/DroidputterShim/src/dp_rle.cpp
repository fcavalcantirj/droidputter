#include "dp_rle.h"

namespace dp {

size_t dp_rle_encode(const uint16_t* px, size_t n, uint8_t* out, size_t cap) {
  if (n == 0) return 0;
  size_t raw = n * 2;
  size_t o = 0;
  for (size_t i = 0; i < n;) {
    size_t j = i + 1;
    while (j < n && j - i < 255 && px[j] == px[i]) j++;
    if (o + 3 > cap) return 0;
    out[o++] = (uint8_t)(j - i);
    out[o++] = (uint8_t)(px[i] >> 8);   // big-endian on the wire, matches RECT
    out[o++] = (uint8_t)(px[i] & 0xFF);
    i = j;
  }
  if (o >= raw) return 0;
  return o;
}

size_t dp_rle_decode(const uint8_t* in, size_t inLen, uint16_t* px, size_t cap) {
  if (inLen == 0 || inLen % 3 != 0) return 0;
  size_t o = 0;
  for (size_t i = 0; i < inLen; i += 3) {
    uint8_t count = in[i];
    uint16_t color = (uint16_t)((in[i + 1] << 8) | in[i + 2]);
    if (count == 0 || o + count > cap) return 0;
    for (uint8_t k = 0; k < count; k++) px[o++] = color;
  }
  return o;
}

}  // namespace dp

// Runs of n wire-order pixels into out; 0 only when `cap` is exceeded (no "shorter than raw" rule here).
static size_t encode_be_runs(const uint8_t* px, size_t n, uint8_t* out, size_t cap) {
  size_t o = 0, i = 0;
  while (i < n) {
    uint8_t c0 = px[i * 2], c1 = px[i * 2 + 1];
    size_t run = 1;
    while (i + run < n && run < 255 && px[(i + run) * 2] == c0 && px[(i + run) * 2 + 1] == c1) run++;
    if (o + 3 > cap) return 0;
    out[o++] = (uint8_t)run; out[o++] = c0; out[o++] = c1;
    i += run;
  }
  return o;
}

size_t dp::dp_rle_encode_be(const uint8_t* px, size_t n, uint8_t* out, size_t cap) {
  if (!n) return 0;
  size_t o = encode_be_runs(px, n, out, cap < n * 2 ? cap : n * 2 - 1);   // must beat raw (n*2 B) to be worth it
  return o;
}

size_t dp::dp_rle_encode_rows_be(const uint8_t* px, uint16_t width, uint16_t rows, uint8_t* out, size_t cap, uint16_t* rows_done) {
  size_t o = 0; uint16_t r = 0;
  for (; r < rows; r++) {
    size_t len = encode_be_runs(px + (size_t)r * width * 2, width, out + o, cap - o);
    if (!len) break;
    o += len;
  }
  if (rows_done) *rows_done = r;
  return r ? o : 0;
}
