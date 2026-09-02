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
